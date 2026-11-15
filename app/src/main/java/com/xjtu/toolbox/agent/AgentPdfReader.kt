package com.xjtu.toolbox.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * PDF 用系统 [PdfRenderer]（API 35 起能取文字、检索）：文字页直接读；空文字页有 DeepSeek Key 时逐页 OCR，
 * 否则转页面图给模型看。系统接口不给书签，info 改列每页首行，search 按关键词找页。
 */
internal object AgentPdfReader {
    private val MODES = setOf("auto", "info", "search", "text", "images")
    private const val TEXT_LIMIT = 12_000
    private const val TEXT_PAGES = 20
    private const val IMAGE_PAGES = 4
    private const val OCR_PAGES = 8
    private const val HEAD_PAGES = 150
    private const val SEARCH_HITS = 30
    private const val PAGE_WIDTH = 1400

    /** 本机一趟的产物：[renders] 是待 OCR 或直接看的页面图（页码 → JPEG）。 */
    private class Pass(val text: String, val renders: Map<Int, ByteArray> = emptyMap(), val forOcr: Boolean = false)

    suspend fun read(context: Context, name: String, bytes: ByteArray, o: AgentDocReader.Options, ocr: DeepSeekOcr?): AgentDocReader.Doc {
        require(o.mode in MODES) { "mode 应为 auto、info、search、text 或 images" }
        require(o.textOffset >= 0) { "text_offset 不能为负数" }
        val pass = try {
            open(context, bytes) { pdf ->
                val header = "file: $name; format: pdf; bytes: ${bytes.size}; pages: ${pdf.pageCount}; page_numbering: physical_1_based"
                when (o.mode) {
                    "info" -> Pass("$header\n${heads(pdf)}")
                    "search" -> Pass("$header\n${search(pdf, o.query?.trim().orEmpty())}")
                    "images" -> {
                        val selected = AgentDocumentPages.select(o.pages, pdf.pageCount, IMAGE_PAGES)
                        Pass("$header\nimage_pages: ${selected.joinToString(",")}", selected.associateWith { render(pdf, it) })
                    }
                    else -> {
                        val (body, empty) = text(pdf, o.pages, o.textOffset)
                        when {
                            empty.isEmpty() -> Pass("$header\n$body")
                            o.mode == "text" -> Pass("$header\n$body\nimage_pages: ${empty.joinToString(",")}; 可用 mode=images 读取")
                            ocr != null -> Pass("$header\n$body", empty.take(OCR_PAGES).associateWith { render(pdf, it) }, forOcr = true)
                            else -> {
                                val more = empty.drop(IMAGE_PAGES)
                                Pass("$header\n$body" + if (more.isEmpty()) "" else "\nimage_pages_pending: ${more.joinToString(",")}",
                                    empty.take(IMAGE_PAGES).associateWith { render(pdf, it) })
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (e is IllegalArgumentException) throw e
            return AgentDocReader.Doc(ToolReply.failed("read_pdf", e.message ?: e.javaClass.simpleName))
        }
        return if (pass.forOcr && ocr != null) withOcr(pass, ocr, name, bytes) else images(pass.text, pass.renders)
    }

    private fun images(text: String, renders: Map<Int, ByteArray>): AgentDocReader.Doc {
        val note = if (renders.isEmpty()) "" else "\n页面图（第 ${renders.keys.joinToString(",")} 页）在下一条消息中。"
        return AgentDocReader.Doc("$text$note\n${ToolReply.EXTERNAL_DATA}", renders.values.map(AgentDocReader::dataUrl))
    }

    /** 空文字页逐页 OCR；识别为空（多半是纯图）或失败的页退回页面图。 */
    private suspend fun withOcr(pass: Pass, ocr: DeepSeekOcr, name: String, bytes: ByteArray): AgentDocReader.Doc {
        val doc = AgentDocReader.contentKey(bytes)
        val results = coroutineScope {
            pass.renders.map { (page, jpeg) -> async { page to AgentDocReader.cachedOcr(ocr, "$doc:$page", "$name-p$page.jpg", jpeg) } }.awaitAll()
        }
        val text = StringBuilder(pass.text)
        val fallback = linkedMapOf<Int, ByteArray>()
        for ((page, r) in results) {
            val recognized = (r as? DeepSeekOcr.Result.Ok)?.pages?.firstOrNull().orEmpty()
            if (recognized.isNotBlank()) {
                text.append("\n【PDF 第 $page 页·OCR】\n").append(recognized.take(TEXT_LIMIT / OCR_PAGES * 2))
            } else {
                text.append("\n【PDF 第 $page 页·OCR】").append(if (r is DeepSeekOcr.Result.Failed) "failed: ${r.reason}" else "无文字，可能是纯图")
                if (fallback.size < IMAGE_PAGES) fallback[page] = pass.renders.getValue(page)
            }
        }
        text.append("\nocr_note: 扫描页已识别为文字；公式、图表要看原样用 mode=images")
        return images(text.toString(), fallback)
    }

    private fun <T> open(context: Context, bytes: ByteArray, block: (PdfRenderer) -> T): T {
        val tmp = File.createTempFile("read-", ".pdf", File(context.cacheDir, "agent_docs").apply { mkdirs() })
        return try {
            tmp.writeBytes(bytes)
            ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use(block) }
        } finally {
            tmp.delete()
        }
    }

    private fun pageText(pdf: PdfRenderer, page: Int): String =
        pdf.openPage(page - 1).use { p -> p.textContents.joinToString("\n") { it.text } }.trim()

    private fun render(pdf: PdfRenderer, page: Int): ByteArray = pdf.openPage(page - 1).use { p ->
        val h = (p.height.toFloat() / p.width * PAGE_WIDTH).toInt().coerceIn(1, PAGE_WIDTH * 3)
        val bmp = Bitmap.createBitmap(PAGE_WIDTH, h, Bitmap.Config.ARGB_8888)
        try {
            bmp.eraseColor(Color.WHITE)
            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            AgentDocReader.jpeg(bmp)
        } finally {
            bmp.recycle()
        }
    }

    private fun text(pdf: PdfRenderer, pages: String?, offset: Int): Pair<String, List<Int>> {
        val selected = AgentDocumentPages.select(pages, pdf.pageCount, TEXT_PAGES)
        require(offset == 0 || selected.size == 1) { "text_offset 只用于单页续读" }
        val empty = mutableListOf<Int>()
        val body = StringBuilder()
        var read = 0
        for (page in selected) {
            val text = pageText(pdf, page)
            read++
            body.append("\n【PDF 第 $page 页】\n")
            if (text.isBlank()) {
                empty += page
                body.append("text: empty\n")
            } else {
                val from = offset.coerceAtMost(text.length)
                val end = minOf(text.length, from + (TEXT_LIMIT - body.length).coerceAtLeast(0))
                body.append(text, from, end).append('\n')
                if (end < text.length) {
                    body.append("text_truncated: page=$page; next: pages=$page, text_offset=$end\n")
                    break
                }
            }
            if (body.length >= TEXT_LIMIT) break
        }
        selected.drop(read).takeIf { it.isNotEmpty() }?.let { body.append("unread_pages: ${it.joinToString(",")}\n") }
        return body.toString() to empty
    }

    /** 每页首行（幻灯片多半就是标题）当目录用，无文字的页合并成区间。 */
    private fun heads(pdf: PdfRenderer): String = buildString {
        val total = minOf(pdf.pageCount, HEAD_PAGES)
        val blank = mutableListOf<Int>()
        append("page_heads（每页首行，可据此定位）:\n")
        for (page in 1..total) {
            val head = pageText(pdf, page).lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            if (head == null) blank += page else append("$page: ${head.take(40)}\n")
        }
        if (pdf.pageCount > total) append("heads_truncated: 只列前 $total 页，后面用 mode=search 找\n")
        if (blank.isNotEmpty()) append("image_only_pages: ${ranges(blank)}（扫描或纯图，auto 会识别或转图）\n")
        append("outline: 系统接口不提供书签，找章节用 mode=search 加 query")
    }.trimEnd()

    private fun search(pdf: PdfRenderer, query: String): String {
        if (query.isEmpty()) return ToolReply.missing("query")
        val hits = mutableListOf<String>()
        var hitPages = 0
        var blank = 0
        for (page in 1..pdf.pageCount) {
            pdf.openPage(page - 1).use { p ->
                val matches = p.searchText(query)
                val text = p.textContents.joinToString("\n") { it.text }
                if (text.isBlank()) blank++
                if (matches.isNotEmpty()) {
                    hitPages++
                    if (hits.size < SEARCH_HITS) {
                        val at = text.indexOf(query, ignoreCase = true).takeIf { it >= 0 } ?: matches.first().textStartIndex
                        val snippet = text.substring((at - 40).coerceIn(0, text.length), (at + query.length + 60).coerceIn(0, text.length))
                        hits += "$page（${matches.size} 处）: ${snippet.replace(Regex("\\s+"), " ").trim()}"
                    }
                }
            }
        }
        return buildString {
            append("query: $query; hit_pages: $hitPages\n")
            hits.forEach { append(it).append('\n') }
            if (hitPages > hits.size) append("hits_truncated: 只列前 ${hits.size} 页\n")
            if (blank > 0) append("unsearchable_pages: $blank（无文字层的扫描页检索不到）\n")
        }.trimEnd()
    }

    private fun ranges(pages: List<Int>): String = buildList {
        var start = pages.first()
        var prev = start
        for (p in pages.drop(1) + Int.MIN_VALUE) {
            if (p == prev + 1) { prev = p; continue }
            add(if (start == prev) "$start" else "$start-$prev")
            start = p
            prev = p
        }
    }.joinToString(",")
}
