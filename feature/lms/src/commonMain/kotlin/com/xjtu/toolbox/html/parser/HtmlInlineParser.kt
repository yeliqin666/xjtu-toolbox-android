package com.xjtu.toolbox.html.parser

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em
import com.xjtu.toolbox.html.dom.HtmlElement as Element
import com.xjtu.toolbox.html.dom.HtmlNode as Node
import com.xjtu.toolbox.html.dom.HtmlText as TextNode

internal object HtmlInlineParser {

    fun buildAnnotatedString(
        nodes: List<Node>,
        linkColor: Color = Color.Unspecified,
        codeBgColor: Color = Color.Unspecified,
        onLinkClick: ((url: String) -> Unit)? = null,
    ): AnnotatedString {
        val builder = AnnotatedString.Builder()
        for (node in nodes) {
            appendNode(builder, node, linkColor, codeBgColor, onLinkClick)
        }
        return builder.toAnnotatedString()
    }

    private fun appendNode(
        builder: AnnotatedString.Builder,
        node: Node,
        linkColor: Color,
        codeBgColor: Color,
        onLinkClick: ((url: String) -> Unit)?,
    ) {
        when (node) {
            is TextNode -> {
                val text = node.text()
                if (text.isNotEmpty()) {
                    builder.append(text)
                }
            }
            is Element -> {
                val tag = node.normalName()
                if (tag == "br") {
                    builder.append('\n')
                    return
                }

                var pushedStyleCount = 0
                var pushedLink = false

                // 1. 标签对应的基础样式
                when (tag) {
                    "b", "strong" -> {
                        builder.pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                        pushedStyleCount++
                    }
                    "i", "em" -> {
                        builder.pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                        pushedStyleCount++
                    }
                    "u", "ins" -> {
                        builder.pushStyle(SpanStyle(textDecoration = TextDecoration.Underline))
                        pushedStyleCount++
                    }
                    "s", "del", "strike" -> {
                        builder.pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                        pushedStyleCount++
                    }
                    "code" -> {
                        builder.pushStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                background = if (codeBgColor != Color.Unspecified) codeBgColor else Color(0x1F888888),
                            )
                        )
                        pushedStyleCount++
                    }
                    "sub" -> {
                        builder.pushStyle(SpanStyle(baselineShift = BaselineShift.Subscript, fontSize = 0.8.em))
                        pushedStyleCount++
                    }
                    "sup" -> {
                        builder.pushStyle(SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = 0.8.em))
                        pushedStyleCount++
                    }
                    "a" -> {
                        val href = node.attr("href").trim()
                        if (href.isNotEmpty()) {
                            val linkStyles = TextLinkStyles(
                                style = SpanStyle(
                                    color = if (linkColor != Color.Unspecified) linkColor else Color(0xFF1E88E5),
                                    textDecoration = TextDecoration.Underline
                                )
                            )
                            val listener = onLinkClick?.let { callback ->
                                LinkInteractionListener { callback(href) }
                            }
                            builder.pushLink(
                                LinkAnnotation.Url(
                                    url = href,
                                    styles = linkStyles,
                                    linkInteractionListener = listener,
                                )
                            )
                            pushedLink = true
                        }
                    }
                }

                // 2. 内联 CSS style 属性解析
                val inlineCss = HtmlCssParser.parseStyle(node.attr("style"))
                if (inlineCss != null) {
                    builder.pushStyle(inlineCss)
                    pushedStyleCount++
                }

                // 递归子节点
                for (child in node.childNodes()) {
                    appendNode(builder, child, linkColor, codeBgColor, onLinkClick)
                }

                // 出栈
                repeat(pushedStyleCount) {
                    builder.pop()
                }
                if (pushedLink) {
                    builder.pop()
                }
            }
        }
    }
}
