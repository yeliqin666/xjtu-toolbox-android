package com.xjtu.toolbox.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/** 文档在本机提取文字；PDF 见 [AgentPdfReader]，老式 doc/ppt 和扫描页交给 DeepSeek OCR（有 Key 时）。 */
internal object AgentDocReader {

    /** [text] 进工具结果；[images] 由调度循环随后作为一条 user 消息交给模型看。 */
    class Doc(val text: String, val images: List<String> = emptyList())

    /** 工具参数：页码（或段号）、模式、PDF 单页续读偏移、PDF 检索词。 */
    class Options(val pages: String?, val mode: String = "auto", val textOffset: Int = 0, val query: String? = null)

    private const val TEXT_CHARS = 6000

    private val TEXT_EXTS = setOf("txt", "md", "markdown", "csv", "json", "html", "htm", "xml", "tex", "py", "java", "c", "cpp", "h", "kt", "js", "m", "log")
    private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
    private val OCR_EXTS = setOf("doc", "ppt")

    fun ext(name: String) = name.substringAfterLast('.', "").lowercase()

    fun canRead(name: String): Boolean = ext(name).let {
        it in TEXT_EXTS || it in IMAGE_EXTS || it in OCR_EXTS || it in setOf("pdf", "docx", "pptx", "xlsx")
    }

    suspend fun read(context: Context, name: String, bytes: ByteArray, options: Options, ocr: DeepSeekOcr?): Doc = when (ext(name)) {
        "pdf" -> AgentPdfReader.read(context, name, bytes, options, ocr)
        "docx" -> chunk(name, docxText(bytes), options.pages)
        "pptx" -> slides(name, pptxSlides(bytes), options.pages)
        "xlsx" -> chunk(name, xlsxText(bytes), options.pages)
        in OCR_EXTS -> legacyOffice(name, bytes, options.pages, ocr)
        in IMAGE_EXTS -> imageDoc(name, bytes)
        in TEXT_EXTS -> chunk(name, decodeText(bytes), options.pages)
        else -> Doc("result: unsupported_type; file: $name; supported: pdf, doc(x), ppt(x), xlsx, 图片, 纯文本")
    }

    /** 同一份内容的 OCR 结果缓存，翻页、续读不重复计费。 */
    private val ocrCache = object : LinkedHashMap<String, List<String>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>) = size > 64
    }

    suspend fun cachedOcr(ocr: DeepSeekOcr, key: String, name: String, bytes: ByteArray): DeepSeekOcr.Result {
        synchronized(ocrCache) { ocrCache[key] }?.let { return DeepSeekOcr.Result.Ok(it) }
        return ocr.recognize(name, bytes).also { r ->
            if (r is DeepSeekOcr.Result.Ok) synchronized(ocrCache) { ocrCache[key] = r.pages }
        }
    }

    fun contentKey(bytes: ByteArray) = "${bytes.size}:${bytes.contentHashCode()}"

    private suspend fun legacyOffice(name: String, bytes: ByteArray, pages: String?, ocr: DeepSeekOcr?): Doc {
        if (ocr == null) return Doc("result: needs_ocr; file: $name; 老式 doc/ppt 要在设置里开联网搜索（DeepSeek Key）才能识别，也可以用 save_file 存进下载")
        return when (val r = cachedOcr(ocr, contentKey(bytes), name, bytes)) {
            is DeepSeekOcr.Result.Failed -> Doc(ToolReply.failed("ocr", r.reason))
            is DeepSeekOcr.Result.Ok ->
                if (ext(name) == "ppt") slides(name, r.pages, pages) else chunk(name, r.pages.joinToString("\n"), pages)
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

    private fun slides(name: String, slides: List<String>, pages: String?): Doc {
        if (slides.none { it.isNotBlank() }) return Doc(ToolReply.empty("slides: $name"))
        val r = AgentDocumentPages.select(pages, slides.size, 20)
        val body = r.joinToString("\n") { "【第 $it 页】${slides[it - 1]}" }
        return Doc("「$name」幻灯片文字：\n$body\n共 ${slides.size} 页；本次页码：${r.joinToString(",")}\n${ToolReply.EXTERNAL_DATA}")
    }

    private fun pptxSlides(bytes: ByteArray): List<String> =
        zipEntries(bytes) { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }
            .sortedBy { (n, _) -> Regex("""slide(\d+)\.xml""").find(n)?.groupValues?.get(1)?.toIntOrNull() ?: 0 }
            .map { (_, xml) -> xmlTexts(xml, "a:t").joinToString(" ") }

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
        return Doc("「$name」是图片，在下一条消息里", listOf(dataUrl(jpeg(scaled))))
    }

    fun jpeg(bmp: Bitmap): ByteArray = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }.toByteArray()

    fun dataUrl(jpeg: ByteArray) = "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP)
}
