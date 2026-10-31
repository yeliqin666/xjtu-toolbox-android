package com.xjtu.toolbox.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withAnnotation
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.hrm.latex.parser.LatexParser
import com.hrm.latex.parser.model.LatexNode
import com.hrm.latex.renderer.Latex
import com.hrm.latex.renderer.measure.rememberLatexMeasurer
import com.hrm.latex.renderer.model.LatexConfig
import com.hrm.latex.renderer.model.LatexTheme
import com.xjtu.toolbox.ui.theme.LocalIsDarkTheme
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 轻量 Markdown 渲染器（按主流实现自写）。覆盖 LLM 回复常见语法：
 * 标题 #~######、**粗** *斜* ***粗斜*** ~~删除~~ `行内码` [链接](url)、
 * 代码块 ```、引用 >、有序/无序列表（含缩进嵌套）、分隔线 ---、表格、
 * 独占一行的 https 图片。
 * 公式（行内 $…$ \(…\)，块级 $$…$$ \[…\]）由 huarangmeng/latex 排版，失败或流式输出中显示源码。
 */
@Composable
fun MarkdownText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    onLink: (String) -> Unit = {},
    /** 内联图的取图引擎（见 [AgentImageLoader]）：默认没有 ⇒ 图退成一行可点的链接。 */
    images: AgentImageLoader = AgentImageLoader.None,
    /** 为 false 时公式只显示源码。 */
    typesetMath: Boolean = true,
) {
    val blocks = remember(text) { parseBlocks(text) }
    // 文本停止变化后才排版公式，排版过就不再退回源码
    var stableText by remember { mutableStateOf(text) }
    var typesetOnce by remember { mutableStateOf(false) }
    LaunchedEffect(text) {
        if (text != stableText) { delay(MATH_SETTLE_MS); stableText = text }
    }
    val mathLive = typesetMath && (typesetOnce || text == stableText)
    LaunchedEffect(mathLive) { if (mathLive) typesetOnce = true }
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
                is MdBlock.Heading -> MdText(
                    mathLive, rememberInline(block.text, linkColor, codeBg, errorColor, linkHandler),
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
                    MdText(mathLive, rememberInline(block.text, linkColor, codeBg, errorColor, linkHandler), color = color,
                        style = MiuixTheme.textStyles.body1, modifier = Modifier.weight(1f))
                }
                is MdBlock.Task -> ListRow(block.indent, if (block.checked) "☑" else "☐") {
                    MdText(
                        mathLive, rememberInline(block.text, linkColor, codeBg, errorColor, linkHandler),
                        color = if (block.checked) MiuixTheme.colorScheme.onSurfaceVariantSummary else color,
                        style = MiuixTheme.textStyles.body1,
                        textDecoration = if (block.checked) TextDecoration.LineThrough else null,
                        modifier = Modifier.weight(1f)
                    )
                }
                is MdBlock.Numbered -> ListRow(block.indent, "${block.num}.") {
                    MdText(mathLive, rememberInline(block.text, linkColor, codeBg, errorColor, linkHandler), color = color,
                        style = MiuixTheme.textStyles.body1, modifier = Modifier.weight(1f))
                }
                is MdBlock.Quote -> Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight()
                        .background(quoteColor.copy(alpha = 0.5f), RoundedCornerShape(2.dp)))
                    Spacer(Modifier.width(8.dp))
                    MdText(mathLive, rememberInline(block.text, linkColor, codeBg, errorColor, linkHandler), color = quoteColor,
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
                            MdText(mathLive, rememberInline(h, linkColor, codeBg, errorColor, linkHandler), color = color, fontWeight = FontWeight.Bold,
                                style = MiuixTheme.textStyles.footnote1,
                                modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
                        }
                    }
                    HorizontalDivider(color = quoteColor.copy(alpha = 0.2f))
                    block.rows.forEach { row ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp)) {
                            for (ci in 0 until cols) {
                                MdText(mathLive, rememberInline(row.getOrElse(ci) { "" }, linkColor, codeBg, errorColor, linkHandler), color = color,
                                    style = MiuixTheme.textStyles.footnote1,
                                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
                            }
                        }
                    }
                }
                is MdBlock.Para -> MdText(mathLive, rememberInline(block.text, linkColor, codeBg, errorColor, linkHandler), color = color,
                    style = MiuixTheme.textStyles.body1)
                is MdBlock.Math -> MathBlock(block, color, codeBg, mathLive)
                is MdBlock.Image -> MdImage(block.alt, block.url, images, linkColor, onClick = { linkHandler(block.url) })
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

internal sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Bullet(val text: String, val indent: Int) : MdBlock
    data class Task(val checked: Boolean, val text: String, val indent: Int) : MdBlock
    data class Numbered(val num: Int, val text: String, val indent: Int) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val text: String) : MdBlock
    data object Rule : MdBlock
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MdBlock
    data class Para(val text: String) : MdBlock
    /** 块级公式；[raw] 为含定界符的原文。 */
    data class Math(val tex: String, val raw: String) : MdBlock
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

internal fun parseBlocks(text: String): List<MdBlock> {
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
            else -> i = parseParaWithMath(lines, i, out)
        }
        i++
    }
    return out
}

// 块级公式开头 $$ / \[（不含 \\[ 与 \$）
private val displayOpenRe = Regex("""(?<!\\)(\$\$|\\\[)""")

private val inlineCodeRe = Regex("`[^`]*`")

/** 解析段落行并切出其中的块级公式，返回最后消费的行号；未闭合的定界符按普通文字处理。 */
private fun parseParaWithMath(lines: List<String>, start: Int, out: MutableList<MdBlock>): Int {
    var i = start
    var rest = lines[i].trim()
    if (rest.length > MAX_MATH_LINE) { out.add(MdBlock.Para(rest)); return i }
    while (true) {
        val code = inlineCodeRe.findAll(rest).map { it.range }.toList()
        val m = displayOpenRe.findAll(rest).firstOrNull { o -> code.none { o.range.first in it } }
        if (m == null) {
            if (rest.isNotBlank()) out.add(MdBlock.Para(rest))
            return i
        }
        val open = m.value
        val close = if (open == "$$") "$$" else "\\]"
        val sb = StringBuilder()
        var body = rest.substring(m.range.last + 1)
        var idx = body.indexOf(close)
        var j = i
        // 跨行找结束定界符，遇空行、代码围栏、标题即放弃
        while (idx < 0 && j + 1 < lines.size && !lines[j + 1].isMathStop()) {
            sb.append(body).append('\n')
            j++
            body = lines[j]
            idx = body.indexOf(close)
        }
        if (idx < 0) {
            out.add(MdBlock.Para(rest))
            return i
        }
        i = j
        val before = rest.substring(0, m.range.first).trim()
        if (before.isNotEmpty()) out.add(MdBlock.Para(before))
        sb.append(body, 0, idx)
        val tex = sb.toString().trim()
        if (tex.isNotEmpty()) out.add(MdBlock.Math(tex, open + sb + close))
        rest = body.substring(idx + close.length).trim()
    }
}

private fun String.isMathStop(): Boolean = trimStart().let { it.isBlank() || it.startsWith("```") || headingRe.matches(it) }

// 顺序即优先级：图片 | ***粗斜*** | **粗** | ~~删除~~ | `码` | *斜* | _斜_ | [文字](链接) | 行内公式
private const val INLINE_BASE =
    """!\[([^\]]*)]\(([^)]+)\)""" +          // 1 img-alt, 2 img-url
        """|\*\*\*(.+?)\*\*\*""" +           // 3 bold-italic
        """|\*\*(.+?)\*\*""" +               // 4 bold
        """|~~(.+?)~~""" +                   // 5 strike
        """|`([^`]+)`""" +                   // 6 code
        """|\*(.+?)\*""" +                   // 7 italic
        """|_(.+?)_""" +                     // 8 italic
        """|\[([^\]]+)]\(([^)]+)\)"""       // 9 link-text, 10 link-url
private val inlineNoMathRe = Regex(INLINE_BASE)
private val inlineRe = Regex(
    INLINE_BASE +
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
    inlineMarkdown(s, linkColor, codeBg, errorColor, onLink)
}

internal fun inlineMarkdown(
    s: String, linkColor: Color, codeBg: Color, errorColor: Color, onLink: (String) -> Unit,
    depth: Int = 0,
): AnnotatedString = buildAnnotatedString {
    if (depth >= 16) { append(s); return@buildAnnotatedString }
    fun nested(text: String) = inlineMarkdown(text, linkColor, codeBg, errorColor, onLink, depth + 1)
    var last = 0
    for (m in (if (s.length > MAX_MATH_LINE) inlineNoMathRe else inlineRe).findAll(s)) {
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
            g.size > 11 && (g[11].isNotEmpty() || g[12].isNotEmpty()) -> {
                val tex = g[11].ifEmpty { g[12] }.trim()
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) {
                    withAnnotation(TEX_TAG, tex) { appendInlineContent(TEX_ID_PREFIX + tex, m.value) }
                }
            }
            g[3].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) { append(nested(g[3])) }
            g[4].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(nested(g[4])) }
            g[5].isNotEmpty() -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(nested(g[5])) }
            g[6].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(" ${g[6]} ") }
            g[7].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(nested(g[7])) }
            g[8].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(nested(g[8])) }
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

private const val TEX_TAG = "agent-tex"
private const val TEX_ID_PREFIX = "tex:"
/** 文本静止多久后排版公式。 */
private const val MATH_SETTLE_MS = 600L
/** 超长的行不识别公式（扫描最坏平方级）。 */
private const val MAX_MATH_LINE = 10_000
/** 超长公式不排版，直接显示源码。 */
private const val MAX_TEX_LENGTH = 2000
/** Android 用 ICU 正则：未转义的 } 会抛 PatternSyntaxException，必须写成 \} */
private val envRe = Regex("""\\(begin|end)\{([^}]*)\}""")

/** 行内公式源码（去重）。 */
internal fun inlineTexOf(s: AnnotatedString): List<String> =
    s.getStringAnnotations(TEX_TAG, 0, s.length).map { it.item }.distinct()

/** 长度合适、环境配对、无错误诊断且不含未知命令 / 错误节点时才可排版。 */
internal fun texParses(tex: String): Boolean = tex.isNotBlank() && tex.length <= MAX_TEX_LENGTH && envBalanced(tex) && runCatching {
    val r = LatexParser().parseWithDiagnostics(tex)
    r.errors.isEmpty() && !r.document.hasUnrenderable()
}.getOrDefault(false)

private fun envBalanced(tex: String): Boolean {
    val stack = ArrayList<String>()
    for (m in envRe.findAll(tex)) {
        val name = m.groupValues[2]
        if (m.groupValues[1] == "begin") stack.add(name)
        else if (stack.removeLastOrNull() != name) return false
    }
    return stack.isEmpty()
}

private fun LatexNode.hasUnrenderable(depth: Int = 0): Boolean =
    this is LatexNode.Command || this is LatexNode.Error ||
        (depth < 64 && children().any { it.hasUnrenderable(depth + 1) })

private fun mathConfig(color: Color, fontSize: TextUnit) = LatexConfig(
    fontSize = fontSize,
    theme = LatexTheme.light(color = color, backgroundColor = Color.Transparent),
)

/** 行内 Markdown 文本，行内公式经 inlineContent 排版。 */
@Composable
private fun MdText(
    live: Boolean,
    text: AnnotatedString,
    color: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
    textDecoration: TextDecoration? = null,
) {
    val texes = remember(text) { inlineTexOf(text) }
    if (texes.isEmpty()) {
        Text(text, color = color, style = style, fontWeight = fontWeight,
            textDecoration = textDecoration, modifier = modifier)
        return
    }
    val config = remember(color, style.fontSize) { mathConfig(color, style.fontSize) }
    val measurer = rememberLatexMeasurer(config)
    val dark = LocalIsDarkTheme.current
    val content: Map<String, InlineTextContent> = remember(texes, measurer, dark, live) {
        if (!live) emptyMap() else texes.mapNotNull { tex ->
            if (!texParses(tex)) null
            else runCatching { measurer.inlineContent(tex, config, dark) }.getOrNull()
                ?.let { TEX_ID_PREFIX + tex to it }
        }.toMap()
    }
    // 缺 content 的公式显示 alternateText（源码）
    BasicText(
        text,
        modifier = modifier,
        style = style.merge(color = color, fontWeight = fontWeight, textDecoration = textDecoration),
        inlineContent = content,
    )
}

@Composable
private fun MathBlock(block: MdBlock.Math, color: Color, codeBg: Color, live: Boolean) {
    val fontSize = MiuixTheme.textStyles.body1.fontSize
    val config = remember(color, fontSize) { mathConfig(color, fontSize * 1.1f) }
    val measurer = rememberLatexMeasurer(config)
    val dark = LocalIsDarkTheme.current
    val ok = remember(block.tex, measurer, dark, live) {
        live && texParses(block.tex) &&
            runCatching { measurer.measure(block.tex, config, dark) != null }.getOrDefault(false)
    }
    if (ok) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val minW = maxWidth
            Box(
                Modifier.horizontalScroll(rememberScrollState()).widthIn(min = minW).padding(vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Latex(block.tex, config = config, isDarkTheme = dark)
            }
        }
    } else {
        Box(
            Modifier.fillMaxWidth()
                .background(codeBg, RoundedCornerShape(6.dp))
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Text(block.raw, color = color, style = MiuixTheme.textStyles.footnote1,
                fontFamily = FontFamily.Monospace)
        }
    }
}

// 回复里的图片。做法照搬教师证件照（FacultyPhotoLoader）：`:app` 那份是 OkHttp + BitmapFactory
// 自己加载，按屏宽采样解码、内存 LRU 缓存；只认 https，且单张不超过 8MB（图片地址是模型给的、
// 可能来自搜到的网页，不能让它随手拉一个巨型文件或明文地址）。
//
// 那一段搬去了 `:app` 的 `AndroidAgentImages` —— `:core` 编不出 okhttp（它只有 jvm / android 变体）。
// 屏这一侧只认 [AgentImageLoader] 那几个动作，默认 [AgentImageLoader.None]：加载不出来就退成
// 一行可点的链接（下面 [MdImage] 里本来就有这条退路）。

@Composable
private fun MdImage(alt: String, url: String, images: AgentImageLoader, linkColor: Color, onClick: () -> Unit) {
    var bitmap by remember(url) { mutableStateOf(images.cached(url)) }
    var failed by remember(url) { mutableStateOf(images.hasFailed(url)) }
    // 外链图片点了才加载：网页、资料里藏的指令能让模型把数据拼进图片地址，自动加载就等于自动发出去
    var allowed by remember(url) { mutableStateOf(bitmap != null) }
    LaunchedEffect(url, allowed) {
        if (allowed && bitmap == null && !failed) {
            bitmap = images.load(url)
            failed = bitmap == null
        }
    }
    val bmp = bitmap
    when {
        !allowed -> Text(
            "🖼 ${alt.ifBlank { "图片" }} · ${url.substringAfter("://", "").substringBefore('/').substringBefore('?').substringAfterLast('@').ifBlank { "外部网站" }}（点一下加载）",
            color = linkColor,
            style = MiuixTheme.textStyles.body2,
            modifier = Modifier.clickable { allowed = true },
        )
        bmp != null -> androidx.compose.foundation.Image(
            bitmap = bmp,
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

