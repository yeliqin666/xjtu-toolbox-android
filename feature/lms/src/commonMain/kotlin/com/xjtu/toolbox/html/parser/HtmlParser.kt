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

    /**
     * 按样式解析 —— 屏上的入口。
     *
     * 暗色模式不需要调用方再传开关：样式自己是反过色的那一份就说明当前是深色，行内写死的颜色
     * 必须跟着一起换算（见 [HtmlStyle.isDarkInverted]）。
     */
    fun parse(
        html: String,
        style: HtmlStyle? = null,
        onLinkClick: ((url: String) -> Unit)? = null,
    ): HtmlDocument = parseInternal(
        html = html,
        linkColor = style?.linkColor ?: Color.Unspecified,
        codeBgColor = style?.codeBackgroundColor ?: Color.Unspecified,
        onLinkClick = onLinkClick,
        invertInlineColors = style?.isDarkInverted == true,
    )

    /**
     * 无样式版本（只要文本 / 图片的语义树时用）。
     *
     * 这个重载的**参数表保持不变**（名字与位置都不动）：它同时被旧调用方与测试使用，多一个
     * 默认参数会把 `parse(html)` 这种「全默认」调法推向重载歧义。
     */
    fun parse(
        html: String,
        linkColor: Color = Color.Unspecified,
        codeBgColor: Color = Color.Unspecified,
        onLinkClick: ((url: String) -> Unit)? = null,
    ): HtmlDocument = parseInternal(
        html = html,
        linkColor = linkColor,
        codeBgColor = codeBgColor,
        onLinkClick = onLinkClick,
        invertInlineColors = false,
    )

    /**
     * 带反色开关的入口，给测试与需要显式控制的调用方。
     *
     * 它不会与上面两个重载冲突：[invertInlineColors] **没有默认值**，所以「只传 html」时这个
     * 候选不适用，不会制造歧义。
     */
    internal fun parse(
        html: String,
        invertInlineColors: Boolean,
    ): HtmlDocument = parseInternal(
        html = html,
        linkColor = Color.Unspecified,
        codeBgColor = Color.Unspecified,
        onLinkClick = null,
        invertInlineColors = invertInlineColors,
    )

    private fun parseInternal(
        html: String,
        linkColor: Color,
        codeBgColor: Color,
        onLinkClick: ((url: String) -> Unit)?,
        invertInlineColors: Boolean,
    ): HtmlDocument {
        if (html.isBlank()) return HtmlDocument(emptyList())

        // 纯 Kotlin 小 DOM 代替 jsoup（commonMain 要跑在 wasm 上），行为见 [HtmlDom]
        val body = HtmlDom.parseBody(html)
        val blocks = mutableListOf<HtmlBlock>()
        val pendingInlines = mutableListOf<Node>()

        fun flushInlines() {
            if (pendingInlines.isNotEmpty()) {
                val p = parseParagraphFromNodes(pendingInlines, linkColor, codeBgColor, onLinkClick, invertInlineColors)
                if (p != null) {
                    blocks.add(p)
                }
                pendingInlines.clear()
            }
        }

        for (child in body.childNodes()) {
            if (child is Element && child.normalName() in BLOCK_TAGS) {
                flushInlines()
                val parsed = parseBlockElement(child, linkColor, codeBgColor, onLinkClick, invertInlineColors)
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
        invertInlineColors: Boolean,
    ): HtmlBlock? {
        val tag = element.normalName()
        return when (tag) {
            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                val level = tag[1].digitToInt()
                val text = HtmlInlineParser.buildAnnotatedString(
                    element.childNodes(), linkColor, codeBgColor, onLinkClick, invertInlineColors,
                )
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
                        parseParagraphFromNodes(inlineBuffer, linkColor, codeBgColor, onLinkClick, invertInlineColors)?.let {
                            innerBlocks.add(it)
                        }
                        inlineBuffer.clear()
                    }
                }

                for (child in childNodes) {
                    if (child is Element && child.normalName() in BLOCK_TAGS) {
                        flushBuffer()
                        parseBlockElement(child, linkColor, codeBgColor, onLinkClick, invertInlineColors)?.let { innerBlocks.add(it) }
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
                        val p = parseParagraphFromNodes(child.childNodes(), linkColor, codeBgColor, onLinkClick, invertInlineColors)
                        if (p != null) items.add(p)
                    }
                }
                if (items.isNotEmpty()) HtmlBlock.ListBlock(ordered = (tag == "ol"), items = items) else null
            }
            "hr" -> HtmlBlock.HorizontalRule
            "p", "div", "section", "article" -> {
                parseParagraphFromNodes(element.childNodes(), linkColor, codeBgColor, onLinkClick, invertInlineColors)
            }
            else -> {
                parseParagraphFromNodes(element.childNodes(), linkColor, codeBgColor, onLinkClick, invertInlineColors)
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
        invertInlineColors: Boolean,
    ): HtmlBlock.Paragraph? {
        val parts = mutableListOf<ParagraphPart>()
        val inlineBuffer = mutableListOf<Node>()

        fun flushText() {
            if (inlineBuffer.isNotEmpty()) {
                val annotated = HtmlInlineParser.buildAnnotatedString(
                    inlineBuffer, linkColor, codeBgColor, onLinkClick, invertInlineColors,
                )
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
