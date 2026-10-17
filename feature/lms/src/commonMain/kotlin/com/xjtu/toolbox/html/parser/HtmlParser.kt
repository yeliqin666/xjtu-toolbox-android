package com.xjtu.toolbox.html.parser

import androidx.compose.ui.graphics.Color
import com.xjtu.toolbox.html.model.*
import com.xjtu.toolbox.html.dom.HtmlDom
import com.xjtu.toolbox.html.dom.HtmlElement as Element
import com.xjtu.toolbox.html.dom.HtmlNode as Node
import com.xjtu.toolbox.html.dom.HtmlText as TextNode

object HtmlParser {

    private val BLOCK_TAGS = setOf(
        "p", "div", "h1", "h2", "h3", "h4", "h5", "h6",
        "blockquote", "pre", "ul", "ol", "li", "hr", "table", "section", "article"
    )

    fun parse(
        html: String,
        style: HtmlStyle? = null,
        onLinkClick: ((url: String) -> Unit)? = null,
    ): HtmlDocument {
        return parse(
            html = html,
            linkColor = style?.linkColor ?: Color.Unspecified,
            codeBgColor = style?.codeBackgroundColor ?: Color.Unspecified,
            onLinkClick = onLinkClick,
        )
    }

    fun parse(
        html: String,
        linkColor: Color = Color.Unspecified,
        codeBgColor: Color = Color.Unspecified,
        onLinkClick: ((url: String) -> Unit)? = null,
    ): HtmlDocument {
        if (html.isBlank()) return HtmlDocument(emptyList())

        // 纯 Kotlin 小 DOM 代替 jsoup（commonMain 要跑在 wasm 上），行为见 [HtmlDom]
        val body = HtmlDom.parseBody(html)
        val blocks = mutableListOf<HtmlBlock>()
        val pendingInlines = mutableListOf<Node>()

        fun flushInlines() {
            if (pendingInlines.isNotEmpty()) {
                val p = parseParagraphFromNodes(pendingInlines, linkColor, codeBgColor, onLinkClick)
                if (p != null) {
                    blocks.add(p)
                }
                pendingInlines.clear()
            }
        }

        for (child in body.childNodes()) {
            if (child is Element && child.normalName() in BLOCK_TAGS) {
                flushInlines()
                val parsed = parseBlockElement(child, linkColor, codeBgColor, onLinkClick)
                if (parsed != null) {
                    blocks.add(parsed)
                }
            } else {
                // 顶层文本节点或行内节点
                pendingInlines.add(child)
            }
        }
        flushInlines()

        return HtmlDocument(blocks)
    }

    private fun parseBlockElement(
        element: Element,
        linkColor: Color,
        codeBgColor: Color,
        onLinkClick: ((url: String) -> Unit)?,
    ): HtmlBlock? {
        val tag = element.normalName()
        return when (tag) {
            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                val level = tag[1].digitToInt()
                val text = HtmlInlineParser.buildAnnotatedString(element.childNodes(), linkColor, codeBgColor, onLinkClick)
                if (text.isNotBlank()) HtmlBlock.Heading(level, text) else null
            }
            "pre" -> {
                val codeEl = element.selectFirst("code") ?: element
                val codeText = codeEl.wholeText().trimEnd()
                if (codeText.isNotEmpty()) HtmlBlock.CodeBlock(codeText) else null
            }
            "blockquote" -> {
                val innerBlocks = mutableListOf<HtmlBlock>()
                val childNodes = element.childNodes()
                val inlineBuffer = mutableListOf<Node>()

                fun flushBuffer() {
                    if (inlineBuffer.isNotEmpty()) {
                        parseParagraphFromNodes(inlineBuffer, linkColor, codeBgColor, onLinkClick)?.let {
                            innerBlocks.add(it)
                        }
                        inlineBuffer.clear()
                    }
                }

                for (child in childNodes) {
                    if (child is Element && child.normalName() in BLOCK_TAGS) {
                        flushBuffer()
                        parseBlockElement(child, linkColor, codeBgColor, onLinkClick)?.let { innerBlocks.add(it) }
                    } else {
                        inlineBuffer.add(child)
                    }
                }
                flushBuffer()

                if (innerBlocks.isNotEmpty()) HtmlBlock.BlockQuote(innerBlocks) else null
            }
            "ul", "ol" -> {
                val items = mutableListOf<HtmlBlock.Paragraph>()
                for (child in element.children()) {
                    if (child.normalName() == "li") {
                        val p = parseParagraphFromNodes(child.childNodes(), linkColor, codeBgColor, onLinkClick)
                        if (p != null) items.add(p)
                    }
                }
                if (items.isNotEmpty()) HtmlBlock.ListBlock(ordered = (tag == "ol"), items = items) else null
            }
            "hr" -> HtmlBlock.HorizontalRule
            "p", "div", "section", "article" -> {
                parseParagraphFromNodes(element.childNodes(), linkColor, codeBgColor, onLinkClick)
            }
            else -> {
                parseParagraphFromNodes(element.childNodes(), linkColor, codeBgColor, onLinkClick)
            }
        }
    }

    /**
     * 将一组节点解析为包含 Text 与 Image 分片的 Paragraph
     */
    private fun parseParagraphFromNodes(
        nodes: List<Node>,
        linkColor: Color,
        codeBgColor: Color,
        onLinkClick: ((url: String) -> Unit)?,
    ): HtmlBlock.Paragraph? {
        val parts = mutableListOf<ParagraphPart>()
        val inlineBuffer = mutableListOf<Node>()

        fun flushText() {
            if (inlineBuffer.isNotEmpty()) {
                val annotated = HtmlInlineParser.buildAnnotatedString(inlineBuffer, linkColor, codeBgColor, onLinkClick)
                // 仅保留有可见字符或明确换行的文本
                if (annotated.text.isNotBlank() || '\n' in annotated.text) {
                    parts.add(ParagraphPart.Text(annotated))
                }
                inlineBuffer.clear()
            }
        }

        fun collectNodes(targetNodes: List<Node>) {
            for (node in targetNodes) {
                if (node is Element) {
                    val tag = node.normalName()
                    if (tag == "img") {
                        val src = node.attr("src").trim()
                        if (src.isNotEmpty()) {
                            flushText()
                            val alt = node.attr("alt").takeIf { it.isNotBlank() }
                            parts.add(ParagraphPart.Image(url = src, alt = alt))
                        }
                    } else if (tag in BLOCK_TAGS && tag != "p") {
                        // 嵌套块元素（如 div 内套 div），递归展开
                        collectNodes(node.childNodes())
                    } else {
                        inlineBuffer.add(node)
                    }
                } else if (node is TextNode) {
                    inlineBuffer.add(node)
                }
            }
        }

        collectNodes(nodes)
        flushText()

        if (parts.isEmpty()) return null

        val isPureImage = parts.size == 1 && parts.first() is ParagraphPart.Image
        return HtmlBlock.Paragraph(parts = parts, isPureImage = isPureImage)
    }
}
