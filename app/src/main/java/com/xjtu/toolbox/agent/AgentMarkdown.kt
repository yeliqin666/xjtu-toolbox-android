package com.xjtu.toolbox.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 轻量 Markdown 渲染器（无第三方依赖，按主流实现自写）。覆盖 LLM 回复常见语法：
 * 标题 #~######、**粗** *斜* ***粗斜*** ~~删除~~ `行内码` [链接](url)、
 * 代码块 ```、引用 >、有序/无序列表（含缩进嵌套）、分隔线 ---、表格、
 * 公式（行内 `$…$` `\(…\)`，块级 `$$…$$` `\[…\]`，经 [TexLite] 转成 Unicode）、
 * 独占一行的 https 图片。
 */
@Composable
fun MarkdownText(text: String, color: Color, modifier: Modifier = Modifier, onLink: (String) -> Unit = {}) {
    val blocks = remember(text) { parseBlocks(text) }
    // 链接回调做成稳定引用，否则每次重组 onLink 是新 lambda，下面按块缓存的 AnnotatedString 全部失效
    val currentOnLink = rememberUpdatedState(onLink)
    val linkHandler = remember { { url: String -> currentOnLink.value(url) } }
    val linkColor = MiuixTheme.colorScheme.primary
    val errorColor = MiuixTheme.colorScheme.error
    val codeBg = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val quoteColor = MiuixTheme.colorScheme.onSurfaceVariantSummary

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    rememberInline(block.text, linkColor, codeBg, errorColor, linkHandler),
                    color = color,
                    fontWeight = FontWeight.Bold,
                    style = when (block.level) {
                        1 -> MiuixTheme.textStyles.title2
                        2 -> MiuixTheme.textStyles.title3
                        3 -> MiuixTheme.textStyles.subtitle
                        else -> MiuixTheme.textStyles.body1
                    }
                )
                is MdBlock.Bullet -> ListRow(block.indent, "•") {
                    Text(rememberInline(block.text, linkColor, codeBg, errorColor, linkHandler), color = color,
                        style = MiuixTheme.textStyles.body1, modifier = Modifier.weight(1f))
                }
                is MdBlock.Task -> ListRow(block.indent, if (block.checked) "☑" else "☐") {
                    Text(
                        rememberInline(block.text, linkColor, codeBg, errorColor, linkHandler),
                        color = if (block.checked) MiuixTheme.colorScheme.onSurfaceVariantSummary else color,
                        style = MiuixTheme.textStyles.body1,
                        textDecoration = if (block.checked) TextDecoration.LineThrough else null,
                        modifier = Modifier.weight(1f)
                    )
                }
                is MdBlock.Numbered -> ListRow(block.indent, "${block.num}.") {
                    Text(rememberInline(block.text, linkColor, codeBg, errorColor, linkHandler), color = color,
                        style = MiuixTheme.textStyles.body1, modifier = Modifier.weight(1f))
                }
                is MdBlock.Quote -> Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight()
                        .background(quoteColor.copy(alpha = 0.5f), RoundedCornerShape(2.dp)))
                    Spacer(Modifier.width(8.dp))
                    Text(rememberInline(block.text, linkColor, codeBg, errorColor, linkHandler), color = quoteColor,
                        style = MiuixTheme.textStyles.body2)
                }
                is MdBlock.Code -> Box(
                    Modifier.fillMaxWidth()
                        .background(codeBg, RoundedCornerShape(8.dp))
                        .horizontalScroll(rememberScrollState())
                        .padding(10.dp)
                ) {
                    Text(block.text, color = color, style = MiuixTheme.textStyles.footnote1,
                        fontFamily = FontFamily.Monospace)
                }
                MdBlock.Rule -> HorizontalDivider(
                    Modifier.padding(vertical = 4.dp),
                    color = quoteColor.copy(alpha = 0.25f)
                )
                is MdBlock.Table -> Column(
                    Modifier.fillMaxWidth()
                        .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.04f), RoundedCornerShape(8.dp))
                        .padding(vertical = 4.dp)
                ) {
                    val cols = block.headers.size.coerceAtLeast(1)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 5.dp)) {
                        block.headers.forEach { h ->
                            Text(rememberInline(h, linkColor, codeBg, errorColor, linkHandler), color = color, fontWeight = FontWeight.Bold,
                                style = MiuixTheme.textStyles.footnote1,
                                modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
                        }
                    }
                    HorizontalDivider(color = quoteColor.copy(alpha = 0.2f))
                    block.rows.forEach { row ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp)) {
                            for (ci in 0 until cols) {
                                Text(rememberInline(row.getOrElse(ci) { "" }, linkColor, codeBg, errorColor, linkHandler), color = color,
                                    style = MiuixTheme.textStyles.footnote1,
                                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
                            }
                        }
                    }
                }
                is MdBlock.Para -> Text(rememberInline(block.text, linkColor, codeBg, errorColor, linkHandler), color = color,
                    style = MiuixTheme.textStyles.body1)
                is MdBlock.Math -> Text(
                    remember(block.tex) { TexLite.toUnicode(block.tex) },
                    color = color,
                    style = MiuixTheme.textStyles.body1,
                    fontStyle = FontStyle.Italic,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
                is MdBlock.Image -> MdImage(block.alt, block.url, linkColor, onClick = { linkHandler(block.url) })
            }
        }
    }
}

@Composable
private fun ListRow(indent: Int, marker: String, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = (indent.coerceIn(0, 4) * 16).dp)) {
        Text("$marker ", style = MiuixTheme.textStyles.body1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        content()
    }
}

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Bullet(val text: String, val indent: Int) : MdBlock
    data class Task(val checked: Boolean, val text: String, val indent: Int) : MdBlock
    data class Numbered(val num: Int, val text: String, val indent: Int) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val text: String) : MdBlock
    data object Rule : MdBlock
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MdBlock
    data class Para(val text: String) : MdBlock
    /** 独占一段的公式：`$$…$$` 或 `\[…\]`，转成 Unicode 后居中显示。 */
    data class Math(val tex: String) : MdBlock
    /** 独占一行的图片 `![说明](https://…)`。行内夹在文字里的图片仍按链接显示。 */
    data class Image(val alt: String, val url: String) : MdBlock
}

private val imageLineRe = Regex("""^!\[([^\]]*)]\((https://[^)\s]+)\)$""")

private val tableSepCharsRe = Regex("""[\s|:-]""")

private fun isTableSep(line: String): Boolean =
    line.contains("-") && line.replace(tableSepCharsRe, "").isEmpty()

private fun splitCells(line: String): List<String> =
    line.trim().trim('|').split("|").map { it.trim() }

private val headingRe = Regex("""^(#{1,6})\s+(.*)""")
private val bulletRe = Regex("""^[-*+]\s+(.*)""")
private val numberedRe = Regex("""^(\d+)[.)]\s+(.*)""")
private val ruleRe = Regex("""^(-{3,}|\*{3,}|_{3,})$""")
private val taskRe = Regex("""^\[([ xX])]\s+(.*)""")

private fun parseBlocks(text: String): List<MdBlock> {
    val out = ArrayList<MdBlock>()
    val lines = text.replace("\r\n", "\n").split("\n")
    var i = 0
    while (i < lines.size) {
        val raw = lines[i]
        val line = raw.trimStart()
        val indent = (raw.length - line.length) / 2

        // 表格：| 表头 | 行，下一行是 | --- | --- | 分隔
        if (line.startsWith("|") && i + 1 < lines.size && isTableSep(lines[i + 1].trim())) {
            val headers = splitCells(line)
            var j = i + 2
            val rows = ArrayList<List<String>>()
            while (j < lines.size && lines[j].trimStart().startsWith("|")) {
                rows.add(splitCells(lines[j].trim())); j++
            }
            out.add(MdBlock.Table(headers, rows))
            i = j
            continue
        }

        when {
            // 块级公式：$$…$$ / \[…\]，可以一行写完，也可以跨行
            line.startsWith("$$") || line.startsWith("\\[") -> {
                val close = if (line.startsWith("$$")) "$$" else "\\]"
                val body = line.removePrefix(if (close == "$$") "$$" else "\\[")
                if (body.trimEnd().endsWith(close)) {
                    out.add(MdBlock.Math(body.trimEnd().removeSuffix(close)))
                } else {
                    val sb = StringBuilder(body)
                    i++
                    while (i < lines.size && !lines[i].trimEnd().endsWith(close)) {
                        sb.append(' ').append(lines[i].trim()); i++
                    }
                    if (i < lines.size) sb.append(' ').append(lines[i].trimEnd().removeSuffix(close).trim())
                    out.add(MdBlock.Math(sb.toString()))
                }
            }
            imageLineRe.matches(line.trimEnd()) -> {
                val m = imageLineRe.find(line.trimEnd())!!
                out.add(MdBlock.Image(m.groupValues[1], m.groupValues[2]))
            }
            line.startsWith("```") -> {
                val sb = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    sb.appendLine(lines[i]); i++
                }
                out.add(MdBlock.Code(sb.toString().trimEnd('\n')))
            }
            ruleRe.matches(line) -> out.add(MdBlock.Rule)
            headingRe.matches(line) -> {
                val m = headingRe.find(line)!!
                out.add(MdBlock.Heading(m.groupValues[1].length, m.groupValues[2]))
            }
            line.startsWith(">") -> out.add(MdBlock.Quote(line.removePrefix(">").trim()))
            bulletRe.matches(line) -> {
                val t = bulletRe.find(line)!!.groupValues[1]
                val task = taskRe.find(t)
                if (task != null)
                    out.add(MdBlock.Task(task.groupValues[1].equals("x", true), task.groupValues[2], indent))
                else out.add(MdBlock.Bullet(t, indent))
            }
            numberedRe.matches(line) -> {
                val m = numberedRe.find(line)!!
                out.add(MdBlock.Numbered(m.groupValues[1].toIntOrNull() ?: 1, m.groupValues[2], indent))
            }
            line.isBlank() -> { /* 段间空行由 spacedBy 体现 */ }
            else -> out.add(MdBlock.Para(raw.trim()))
        }
        i++
    }
    return out
}

// 顺序即优先级：图片 | ***粗斜*** | **粗** | ~~删除~~ | `码` | *斜* | _斜_ | [文字](链接) | 行内公式
private val inlineRe = Regex(
    """!\[([^\]]*)]\(([^)]+)\)""" +          // 1 img-alt, 2 img-url
        """|\*\*\*(.+?)\*\*\*""" +           // 3 bold-italic
        """|\*\*(.+?)\*\*""" +               // 4 bold
        """|~~(.+?)~~""" +                   // 5 strike
        """|`([^`]+)`""" +                   // 6 code
        """|\*(.+?)\*""" +                   // 7 italic
        """|_(.+?)_""" +                     // 8 italic
        """|\[([^\]]+)]\(([^)]+)\)""" +      // 9 link-text, 10 link-url
        // 11 行内公式 $…$：紧挨 $ 的不能是空格，免得把「$5 和 $10」这种美元金额当公式
        """|\$(?! )([^$\n]+?)(?<! )\$""" +
        """|\\\((.+?)\\\)"""                 // 12 行内公式 \(…\)
)

/**
 * 仅允许 http(s) / mailto / tel scheme。其他（javascript: / data: / file: / intent: ...）
 * 一律不形成可点击 LinkAnnotation——模型被 prompt injection 注入的恶意 URL 不该被执行。
 */
private fun isSafeLinkScheme(url: String): Boolean {
    val s = url.trim()
    if (s.isEmpty()) return false
    // 纯相对路径或井号锚点——onLink 调用方负责解析
    if (s.startsWith("#") || s.startsWith("/") || s.startsWith("?")) return true
    val scheme = s.substringBefore(":", missingDelimiterValue = "").lowercase()
    if (scheme.isEmpty()) return true   // 没 scheme 默认相对 URL
    return when (scheme) {
        "http", "https", "mailto", "tel" -> true
        else -> false
    }
}

/**
 * 行内样式按文本缓存。流式输出时每次只有最后一两个块在变，前面已经定型的块
 * 直接复用上次构建的 AnnotatedString，不再每次重组都整段重跑行内正则。
 */
@Composable
private fun rememberInline(
    s: String,
    linkColor: Color,
    codeBg: Color,
    errorColor: Color,
    onLink: (String) -> Unit,
): AnnotatedString = remember(s, linkColor, codeBg, errorColor, onLink) {
    inline(s, linkColor, codeBg, errorColor, onLink)
}

private fun inline(s: String, linkColor: Color, codeBg: Color, errorColor: Color, onLink: (String) -> Unit): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in inlineRe.findAll(s)) {
        if (m.range.first > last) append(s.substring(last, m.range.first))
        val g = m.groupValues
        when {
            // 夹在文字里的图片不内嵌（会把一行字撑开），显示成可点的链接
            g[2].isNotEmpty() -> if (isSafeLinkScheme(g[2])) {
                withLink(LinkAnnotation.Clickable(
                    tag = g[2],
                    styles = TextLinkStyles(SpanStyle(color = linkColor)),
                    linkInteractionListener = { onLink(g[2]) }
                )) { append("🖼 ${g[1].ifBlank { "图片" }}") }
            } else {
                withStyle(SpanStyle(color = linkColor)) { append("🖼 ${g[1].ifBlank { "图片" }}") }
            }
            g[11].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(TexLite.toUnicode(g[11])) }
            g[12].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(TexLite.toUnicode(g[12])) }
            g[3].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) { append(g[3]) }
            g[4].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(g[4]) }
            g[5].isNotEmpty() -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(g[5]) }
            g[6].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(" ${g[6]} ") }
            g[7].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[7]) }
            g[8].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[8]) }
            g[9].isNotEmpty() -> {
                val url = g[10]
                if (!isSafeLinkScheme(url)) {
                    // 文本照常显示，URL 标为「被阻止」并用 error 色渲染——不形成可点击 LinkAnnotation。
                    withStyle(SpanStyle(color = errorColor)) {
                        append("[${g[9]}（链接被阻止：非 http(s) scheme）]")
                    }
                } else {
                    withLink(LinkAnnotation.Clickable(
                        tag = url,
                        styles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                        linkInteractionListener = { onLink(url) }
                    )) { append(g[9]) }
                }
            }
        }
        last = m.range.last + 1
    }
    if (last < s.length) append(s.substring(last))
}

/**
 * 回复里的图片。做法照搬教师证件照（FacultyPhotoLoader）：项目里没有 Coil / Glide，
 * 用 OkHttp + BitmapFactory 自己加载，按屏宽采样解码、内存 LRU 缓存。
 *
 * 只认 https，且单张不超过 8MB：图片地址是模型给的（可能来自搜到的网页），
 * 不能让它随手拉一个巨型文件或明文地址。加载失败就退成一行可点的链接。
 */
private object MdImageLoader {
    private const val MAX_BYTES = 8L * 1024 * 1024
    private const val TARGET_PX = 1080

    private val cache = object : android.util.LruCache<String, android.graphics.Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap): Int = value.byteCount
    }
    private val failed = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val client by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    fun cached(url: String): android.graphics.Bitmap? = cache.get(url)
    fun hasFailed(url: String) = url in failed

    suspend fun load(url: String): android.graphics.Bitmap? {
        if (!url.startsWith("https://") || url in failed) return null
        cache.get(url)?.let { return it }
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val bytes = client.newCall(okhttp3.Request.Builder().url(url).get().build()).execute().use { resp ->
                    val len = resp.body?.contentLength() ?: -1L
                    if (!resp.isSuccessful || len > MAX_BYTES) return@use null
                    resp.body?.bytes()?.takeIf { it.size <= MAX_BYTES }
                } ?: return@runCatching null
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / sample > TARGET_PX * 2) sample *= 2
                android.graphics.BitmapFactory.decodeByteArray(
                    bytes, 0, bytes.size,
                    android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
                )
            }.getOrNull()?.also { cache.put(url, it) } ?: null.also { failed.add(url) }
        }
    }
}

@Composable
private fun MdImage(alt: String, url: String, linkColor: Color, onClick: () -> Unit) {
    var bitmap by androidx.compose.runtime.remember(url) {
        androidx.compose.runtime.mutableStateOf(MdImageLoader.cached(url))
    }
    var failed by androidx.compose.runtime.remember(url) {
        androidx.compose.runtime.mutableStateOf(MdImageLoader.hasFailed(url))
    }
    androidx.compose.runtime.LaunchedEffect(url) {
        if (bitmap == null && !failed) {
            bitmap = MdImageLoader.load(url)
            failed = bitmap == null
        }
    }
    val bmp = bitmap
    when {
        bmp != null -> androidx.compose.foundation.Image(
            bitmap = bmp.asImageBitmapCompat(),
            contentDescription = alt.ifBlank { "图片" },
            contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onClick),
        )
        failed -> Text(
            "🖼 ${alt.ifBlank { "图片" }}（加载失败，点开看原图）",
            color = linkColor,
            style = MiuixTheme.textStyles.body2,
            modifier = Modifier.clickable(onClick = onClick),
        )
        else -> Box(
            Modifier
                .fillMaxWidth()
                .height(160.dp)
                .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.06f), RoundedCornerShape(10.dp)),
        )
    }
}

internal fun android.graphics.Bitmap.asImageBitmapCompat() = asImageBitmap()
