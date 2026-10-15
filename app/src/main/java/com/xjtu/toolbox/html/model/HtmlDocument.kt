package com.xjtu.toolbox.html.model

import androidx.compose.ui.text.AnnotatedString

/**
 * HTML 解析后的中间语义文档树
 */
data class HtmlDocument(
    val blocks: List<HtmlBlock>,
)

/**
 * 块级元素抽象
 */
sealed interface HtmlBlock {
    /** 标题 h1 ~ h6 */
    data class Heading(val level: Int, val text: AnnotatedString) : HtmlBlock

    /**
     * 段落：内部支持图文交替分片
     */
    data class Paragraph(
        val parts: List<ParagraphPart>,
        val isPureImage: Boolean = false,
    ) : HtmlBlock

    /** 独立代码块 <pre><code> */
    data class CodeBlock(val code: String, val language: String? = null) : HtmlBlock

    /** 块引用 <blockquote> */
    data class BlockQuote(val blocks: List<HtmlBlock>) : HtmlBlock

    /** 列表 <ul> / <ol> */
    data class ListBlock(val ordered: Boolean, val items: List<HtmlBlock.Paragraph>) : HtmlBlock

    /** 分割线 <hr> */
    data object HorizontalRule : HtmlBlock

    /** 独立图片 (在 <p> 外的独立 <img>) */
    data class ImageBlock(val url: String, val alt: String? = null) : HtmlBlock
}

/**
 * 段落内部的分片
 */
sealed interface ParagraphPart {
    /** 富文本文字片段（包含粗体、斜体、代码、超链接等） */
    data class Text(val text: AnnotatedString) : ParagraphPart

    /** 嵌入在段落中的响应式插图 */
    data class Image(val url: String, val alt: String? = null) : ParagraphPart
}
