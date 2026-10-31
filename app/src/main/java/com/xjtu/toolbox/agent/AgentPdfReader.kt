package com.xjtu.toolbox.agent

import android.content.Context
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

internal object AgentPdfReader {
    private const val TEXT_LIMIT = 12_000

    fun read(context: Context, name: String, bytes: ByteArray, pages: String?, mode: String, offset: Int): AgentDocReader.Doc {
        require(mode in setOf("auto", "info", "text", "images")) { "mode 应为 auto、info、text 或 images" }
        require(offset >= 0) { "text_offset 不能为负数" }
        if (mode == "images") return AgentDocReader.pdfImages(context, name, bytes, pages)
        PDFBoxResourceLoader.init(context.applicationContext)
        return PDDocument.load(bytes.inputStream(), MemoryUsageSetting.setupMixed(4L * 1024 * 1024).apply { tempDir = context.cacheDir }).use { pdf ->
            val header = metadata(pdf, name, bytes.size)
            if (mode == "info") return@use AgentDocReader.Doc("$header\n${outline(pdf)}\n${ToolReply.EXTERNAL_DATA}")
            val (body, empty) = text(pdf, pages, offset)
            if (mode == "auto" && empty.isNotEmpty()) {
                val render = empty.take(AgentDocReader.PAGES_PER_READ)
                val images = AgentDocReader.pdfImages(context, name, bytes, render.joinToString(","))
                val more = empty.drop(render.size)
                AgentDocReader.Doc("$header\n$body\n${images.text}" +
                    if (more.isEmpty()) "" else "\nimage_pages_pending: ${more.joinToString(",")}", images.images)
            } else {
                val hint = if (empty.isEmpty()) "" else "\nimage_pages: ${empty.joinToString(",")}; 可用 mode=images 读取"
                AgentDocReader.Doc("$header\n$body$hint\n${ToolReply.EXTERNAL_DATA}")
            }
        }
    }

    internal fun text(pdf: PDDocument, pages: String?, offset: Int): Pair<String, List<Int>> {
        val selected = AgentDocumentPages.select(pages, pdf.numberOfPages, limit = 20)
        require(offset >= 0 && (offset == 0 || selected.size == 1)) { "text_offset 用于单页续读，且不能为负数" }
        val stripper = PDFTextStripper().apply { sortByPosition = true }
        val empty = mutableListOf<Int>()
        val body = StringBuilder()
        val visited = mutableListOf<Int>()
        for (page in selected) {
            stripper.startPage = page
            stripper.endPage = page
            val text = stripper.getText(pdf).trim()
            visited += page
            body.append("\n【PDF 第 $page 页】\n")
            if (text.isBlank()) {
                empty += page
                body.append("text: empty\n")
            } else {
                val from = offset.coerceAtMost(text.length)
                val end = minOf(text.length, from + (TEXT_LIMIT - body.length).coerceAtLeast(0))
                body.append(text.substring(from, end)).append('\n')
                if (end < text.length) {
                    body.append("text_truncated: page=$page; next: pages=$page, text_offset=$end\n")
                    break
                }
            }
            if (body.length >= TEXT_LIMIT) break
        }
        val remaining = selected.filter { it !in visited }
        if (remaining.isNotEmpty()) body.append("unread_pages: ${remaining.joinToString(",")}\n")
        return body.toString() to empty
    }

    internal fun metadata(pdf: PDDocument, name: String, size: Int): String = buildString {
        append("file: $name; format: pdf; bytes: $size; pages: ${pdf.numberOfPages}; page_numbering: physical_1_based\n")
        val info = pdf.documentInformation
        listOf("title" to info.title, "author" to info.author, "subject" to info.subject,
            "keywords" to info.keywords, "creator" to info.creator, "producer" to info.producer).forEach { (key, value) ->
            value?.takeIf { it.isNotBlank() }?.let { append("$key: ${it.replace('\n', ' ').take(300)}\n") }
        }
        runCatching { info.creationDate?.toInstant() }.getOrNull()?.let { append("created_at: $it\n") }
        runCatching { info.modificationDate?.toInstant() }.getOrNull()?.let { append("modified_at: $it\n") }
    }.trimEnd()

    internal fun outline(pdf: PDDocument): String = buildString {
        val root = pdf.documentCatalog.documentOutline
        if (root == null) {
            append("outline: none")
            return@buildString
        }
        append("outline (PDF 页码):\n")
        val seen = hashSetOf<Any>()
        var count = 0
        fun walk(first: PDOutlineItem?, depth: Int) {
            var item = first
            while (item != null && count < 80) {
                val current = item
                if (!seen.add(current.cosObject)) break
                count++
                val page = runCatching { current.findDestinationPage(pdf)?.let { pdf.pages.indexOf(it) + 1 } }.getOrNull()
                append("${"  ".repeat(depth)}- ${current.title.orEmpty().replace('\n', ' ').take(160)}; page: ${page?.takeIf { it > 0 } ?: "unknown"}\n")
                if (depth < 4) walk(current.firstChild, depth + 1)
                item = current.nextSibling
            }
            if (item != null && count >= 80) append("outline_truncated: true\n")
        }
        walk(root.firstChild, 0)
        if (count == 0) append("outline: none")
    }.trimEnd()
}
