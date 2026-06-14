package com.xjtu.toolbox.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
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
 * 代码块 ```、引用 >、有序/无序列表（含缩进嵌套）、分隔线 ---。
 */
@Composable
fun MarkdownText(text: String, color: Color, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseBlocks(text) }
    val linkColor = MiuixTheme.colorScheme.primary
    val codeBg = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val quoteColor = MiuixTheme.colorScheme.onSurfaceVariantSummary

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    inline(block.text, linkColor, codeBg),
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
                    Text(inline(block.text, linkColor, codeBg), color = color,
                        style = MiuixTheme.textStyles.body1, modifier = Modifier.weight(1f))
                }
                is MdBlock.Task -> ListRow(block.indent, if (block.checked) "☑" else "☐") {
                    Text(
                        inline(block.text, linkColor, codeBg),
                        color = if (block.checked) MiuixTheme.colorScheme.onSurfaceVariantSummary else color,
                        style = MiuixTheme.textStyles.body1,
                        textDecoration = if (block.checked) TextDecoration.LineThrough else null,
                        modifier = Modifier.weight(1f)
                    )
                }
                is MdBlock.Numbered -> ListRow(block.indent, "${block.num}.") {
                    Text(inline(block.text, linkColor, codeBg), color = color,
                        style = MiuixTheme.textStyles.body1, modifier = Modifier.weight(1f))
                }
                is MdBlock.Quote -> Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight()
                        .background(quoteColor.copy(alpha = 0.5f), RoundedCornerShape(2.dp)))
                    Spacer(Modifier.width(8.dp))
                    Text(inline(block.text, linkColor, codeBg), color = quoteColor,
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
                            Text(inline(h, linkColor, codeBg), color = color, fontWeight = FontWeight.Bold,
                                style = MiuixTheme.textStyles.footnote1,
                                modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
                        }
                    }
                    HorizontalDivider(color = quoteColor.copy(alpha = 0.2f))
                    block.rows.forEach { row ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp)) {
                            for (ci in 0 until cols) {
                                Text(inline(row.getOrElse(ci) { "" }, linkColor, codeBg), color = color,
                                    style = MiuixTheme.textStyles.footnote1,
                                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
                            }
                        }
                    }
                }
                is MdBlock.Para -> Text(inline(block.text, linkColor, codeBg), color = color,
                    style = MiuixTheme.textStyles.body1)
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
}

private fun isTableSep(line: String): Boolean =
    line.contains("-") && line.replace(Regex("""[\s|:-]"""), "").isEmpty()

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

// 顺序即优先级：图片 | ***粗斜*** | **粗** | ~~删除~~ | `码` | *斜* | _斜_ | [文字](链接)
private val inlineRe = Regex(
    """!\[([^\]]*)]\(([^)]+)\)""" +          // 1 img-alt, 2 img-url
        """|\*\*\*(.+?)\*\*\*""" +           // 3 bold-italic
        """|\*\*(.+?)\*\*""" +               // 4 bold
        """|~~(.+?)~~""" +                   // 5 strike
        """|`([^`]+)`""" +                   // 6 code
        """|\*(.+?)\*""" +                   // 7 italic
        """|_(.+?)_""" +                     // 8 italic
        """|\[([^\]]+)]\(([^)]+)\)"""        // 9 link-text, 10 link-url
)

private fun inline(s: String, linkColor: Color, codeBg: Color): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in inlineRe.findAll(s)) {
        if (m.range.first > last) append(s.substring(last, m.range.first))
        val g = m.groupValues
        when {
            g[2].isNotEmpty() -> withStyle(SpanStyle(color = linkColor)) {
                append("🖼 ${g[1].ifBlank { "图片" }}")
            }
            g[3].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) { append(g[3]) }
            g[4].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(g[4]) }
            g[5].isNotEmpty() -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(g[5]) }
            g[6].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(" ${g[6]} ") }
            g[7].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[7]) }
            g[8].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[8]) }
            g[9].isNotEmpty() -> withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) { append(g[9]) }
        }
        last = m.range.last + 1
    }
    if (last < s.length) append(s.substring(last))
}
