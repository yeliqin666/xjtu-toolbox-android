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
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 轻量 Markdown 渲染——覆盖 LLM 回复里最常见的语法，无需引入第三方库：
 * 标题(#~######)、无序/有序列表、代码块(```)、行内 **粗体** / *斜体* / `代码` / [文字](链接)。
 * 不追求 CommonMark 完整性；复杂排版降级为普通文本即可。
 */
@Composable
fun MarkdownText(text: String, color: Color, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseBlocks(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    parseInline(block.text),
                    color = color,
                    fontWeight = FontWeight.Bold,
                    style = when (block.level) {
                        1 -> MiuixTheme.textStyles.title2
                        2 -> MiuixTheme.textStyles.subtitle
                        else -> MiuixTheme.textStyles.body1
                    }
                )
                is MdBlock.Bullet -> Row {
                    Text("•  ", color = color, style = MiuixTheme.textStyles.body1)
                    Text(parseInline(block.text), color = color, style = MiuixTheme.textStyles.body1,
                        modifier = Modifier.weight(1f))
                }
                is MdBlock.Numbered -> Row {
                    Text("${block.num}. ", color = color, style = MiuixTheme.textStyles.body1)
                    Text(parseInline(block.text), color = color, style = MiuixTheme.textStyles.body1,
                        modifier = Modifier.weight(1f))
                }
                is MdBlock.Code -> Surface_CodeBlock(block.text)
                is MdBlock.Para -> Text(parseInline(block.text), color = color,
                    style = MiuixTheme.textStyles.body1)
            }
        }
    }
}

@Composable
private fun Surface_CodeBlock(code: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(MiuixTheme.colorScheme.surface, RoundedCornerShape(8.dp))
            .horizontalScroll(rememberScrollState())
            .padding(10.dp)
    ) {
        Text(code, color = MiuixTheme.colorScheme.onSurface,
            style = MiuixTheme.textStyles.footnote1, fontFamily = FontFamily.Monospace)
    }
}

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Bullet(val text: String) : MdBlock
    data class Numbered(val num: Int, val text: String) : MdBlock
    data class Code(val text: String) : MdBlock
    data class Para(val text: String) : MdBlock
}

private val headingRe = Regex("""^(#{1,6})\s+(.*)""")
private val bulletRe = Regex("""^[-*+]\s+(.*)""")
private val numberedRe = Regex("""^(\d+)\.\s+(.*)""")

private fun parseBlocks(text: String): List<MdBlock> {
    val out = ArrayList<MdBlock>()
    val lines = text.replace("\r\n", "\n").split("\n")
    var i = 0
    while (i < lines.size) {
        val raw = lines[i]
        val line = raw.trimStart()
        when {
            line.startsWith("```") -> {
                val sb = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    sb.appendLine(lines[i]); i++
                }
                out.add(MdBlock.Code(sb.toString().trimEnd('\n')))
            }
            headingRe.matches(line) -> {
                val m = headingRe.find(line)!!
                out.add(MdBlock.Heading(m.groupValues[1].length, m.groupValues[2]))
            }
            bulletRe.matches(line) -> out.add(MdBlock.Bullet(bulletRe.find(line)!!.groupValues[1]))
            numberedRe.matches(line) -> {
                val m = numberedRe.find(line)!!
                out.add(MdBlock.Numbered(m.groupValues[1].toIntOrNull() ?: 1, m.groupValues[2]))
            }
            line.isBlank() -> { /* 段间空行，由 spacedBy 体现 */ }
            else -> out.add(MdBlock.Para(raw))
        }
        i++
    }
    return out
}

// **粗体** | `代码` | *斜体* | [文字](链接)，非嵌套；足够覆盖 LLM 输出。
private val inlineRe = Regex("""\*\*([^*]+)\*\*|`([^`]+)`|\*([^*]+)\*|\[([^\]]+)]\(([^)]+)\)""")

private fun parseInline(s: String): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in inlineRe.findAll(s)) {
        if (m.range.first > last) append(s.substring(last, m.range.first))
        val (bold, code, italic, linkText) = listOf(
            m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4]
        )
        when {
            bold.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
            code.isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(code) }
            italic.isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(italic) }
            linkText.isNotEmpty() -> withStyle(
                SpanStyle(textDecoration = TextDecoration.Underline)
            ) { append(linkText) }
        }
        last = m.range.last + 1
    }
    if (last < s.length) append(s.substring(last))
}
