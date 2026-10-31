package com.xjtu.toolbox.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/** 文档在本机提取文字，PDF 按需读取目录或渲染页面。 */
internal object AgentDocReader {

    /** [text] 进工具结果；[images] 由调度循环随后作为一条 user 消息交给模型看。 */
    class Doc(val text: String, val images: List<String> = emptyList())

    const val PAGES_PER_READ = 4
    private const val TEXT_CHARS = 6000
    private const val PAGE_WIDTH = 1400

    private val TEXT_EXTS = setOf("txt", "md", "markdown", "csv", "json", "html", "htm", "xml", "tex", "py", "java", "c", "cpp", "h", "kt", "js", "m", "log")
    private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

    fun ext(name: String) = name.substringAfterLast('.', "").lowercase()

    fun canRead(name: String): Boolean = ext(name).let {
        it in TEXT_EXTS || it in IMAGE_EXTS || it in setOf("pdf", "docx", "pptx", "xlsx")
    }

    fun read(context: Context, name: String, bytes: ByteArray, pages: String?, mode: String = "auto", textOffset: Int = 0): Doc = when (ext(name)) {
        "pdf" -> AgentPdfReader.read(context, name, bytes, pages, mode, textOffset)
        "docx" -> chunk(name, docxText(bytes), pages)
        "pptx" -> pptx(name, bytes, pages)
        "xlsx" -> chunk(name, xlsxText(bytes), pages)
        in IMAGE_EXTS -> imageDoc(name, bytes)
        in TEXT_EXTS -> chunk(name, decodeText(bytes), pages)
        else -> Doc("result: unsupported_type; file: $name; supported: pdf, docx, pptx, xlsx, 图片, 纯文本")
    }

    internal fun pdfImages(context: Context, name: String, bytes: ByteArray, pages: String?): Doc {
        val tmp = File.createTempFile("read-", ".pdf", File(context.cacheDir, "agent_docs").apply { mkdirs() })
        return try {
            tmp.writeBytes(bytes)
            ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { pdf ->
                    val total = pdf.pageCount
                    if (total == 0) return Doc(ToolReply.empty("pdf: $name"))
                    val r = AgentDocumentPages.select(pages, total, PAGES_PER_READ)
                    val images = r.map { i ->
                        pdf.openPage(i - 1).use { page ->
                            val w = PAGE_WIDTH
                            val h = (page.height.toFloat() / page.width * w).toInt().coerceAtMost(w * 3)
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)
                            try {
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                jpeg(bmp)
                            } finally {
                                bmp.recycle()
                            }
                        }
                    }
                    Doc("file: $name; pages: $total; image_pages: ${r.joinToString(",")}; page_numbering: physical_1_based\n页面图在下一条消息中。\n${ToolReply.EXTERNAL_DATA}", images)
                }
            }
        } catch (e: Exception) {
            Doc(ToolReply.failed("read_pdf", e.message))
        } finally {
            tmp.delete()
        }
    }

    private fun chunk(name: String, text: String, pages: String?): Doc {
        if (text.isBlank()) return Doc(ToolReply.empty("text: $name"))
        val parts = (text.length + TEXT_CHARS - 1) / TEXT_CHARS
        val part = (Regex("""\d+""").find(pages.orEmpty())?.value?.toIntOrNull() ?: 1).coerceIn(1, parts)
        val body = text.substring((part - 1) * TEXT_CHARS, minOf(text.length, part * TEXT_CHARS))
        val more = if (part < parts) "\n…（共 $parts 段，这是第 $part 段；要看下一段带 pages=${part + 1}）" else ""
        return Doc("「$name」内容${if (parts > 1) "（第 $part/$parts 段）" else ""}：\n$body$more\n${ToolReply.EXTERNAL_DATA}")
    }

    private fun pptx(name: String, bytes: ByteArray, pages: String?): Doc {
        val slides = zipEntries(bytes) { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }
            .sortedBy { (n, _) -> Regex("""slide(\d+)\.xml""").find(n)?.groupValues?.get(1)?.toIntOrNull() ?: 0 }
            .map { (_, xml) -> xmlTexts(xml, "a:t").joinToString(" ") }
        if (slides.isEmpty()) return Doc(ToolReply.empty("pptx: $name"))
        val r = AgentDocumentPages.select(pages, slides.size, 20)
        val body = r.joinToString("\n") { "【第 $it 页】${slides[it - 1]}" }
        val more = "\n共 ${slides.size} 页；本次页码：${r.joinToString(",")}"
        return Doc("「$name」幻灯片文字：\n$body$more\n${ToolReply.EXTERNAL_DATA}")
    }

    private fun docxText(bytes: ByteArray): String =
        zipEntries(bytes) { it == "word/document.xml" }.firstOrNull()?.second
            ?.split("</w:p>")
            ?.map { p -> xmlTexts(p, "w:t").joinToString("") }
            ?.filter { it.isNotBlank() }
            ?.joinToString("\n")
            .orEmpty()

    private fun xlsxText(bytes: ByteArray): String =
        zipEntries(bytes) { it == "xl/sharedStrings.xml" || (it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml")) }
            .joinToString("\n") { (_, xml) -> (xmlTexts(xml, "t") + Regex("""<v>([^<]*)</v>""").findAll(xml).map { it.groupValues[1] }).joinToString(" ") }

    private fun zipEntries(bytes: ByteArray, want: (String) -> Boolean): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (!e.isDirectory && want(e.name)) out += e.name to zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        return out
    }

    private fun xmlTexts(xml: String, tag: String): List<String> =
        Regex("""<$tag(?:\s[^>]*)?>([^<]*)</$tag>""").findAll(xml).map { unescape(it.groupValues[1]) }.toList()

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")

    /** UTF-8 解出来乱码（替换符多）就按 GBK 再解一次：老课件常是 GBK。 */
    private fun decodeText(bytes: ByteArray): String {
        val utf8 = bytes.toString(Charsets.UTF_8)
        if (utf8.count { it == '�' } <= utf8.length / 200) return utf8
        return runCatching { String(bytes, charset("GBK")) }.getOrDefault(utf8)
    }

    private fun imageDoc(name: String, bytes: ByteArray): Doc {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return Doc(ToolReply.failed("read_image", "decode"))
        val scale = minOf(1f, 1600f / maxOf(bmp.width, bmp.height))
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        return Doc("「$name」是图片，在下一条消息里", listOf(jpeg(scaled)))
    }

    private fun jpeg(bmp: Bitmap): String {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
        return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
