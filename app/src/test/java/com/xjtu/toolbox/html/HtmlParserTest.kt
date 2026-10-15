package com.xjtu.toolbox.html

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.xjtu.toolbox.html.model.HtmlBlock
import com.xjtu.toolbox.html.model.ParagraphPart
import com.xjtu.toolbox.html.parser.HtmlCssParser
import com.xjtu.toolbox.html.parser.HtmlParser
import org.junit.Assert.*
import org.junit.Test

class HtmlParserTest {

    @Test
    fun testInlineFormatting() {
        val html = "<p><b>粗体</b><i>斜体</i><u>下划线</u><s>删除线</s><code>code</code><a href=\"https://xjtu.edu.cn\">链接</a></p>"
        val doc = HtmlParser.parse(html)

        assertEquals(1, doc.blocks.size)
        val p = doc.blocks[0] as HtmlBlock.Paragraph
        assertEquals(1, p.parts.size)

        val textPart = p.parts[0] as ParagraphPart.Text
        val annotated = textPart.text
        assertEquals("粗体斜体下划线删除线code链接", annotated.text)

        // 验证各样式的 SpanStyle
        val styles = annotated.spanStyles
        assertTrue("应包含粗体", styles.any { it.item.fontWeight == FontWeight.Bold })
        assertTrue("应包含斜体", styles.any { it.item.fontStyle == FontStyle.Italic })
        assertTrue("应包含下划线", styles.any { it.item.textDecoration == TextDecoration.Underline })
        assertTrue("应包含删除线", styles.any { it.item.textDecoration == TextDecoration.LineThrough })
        assertTrue("应包含等宽代码", styles.any { it.item.fontFamily == FontFamily.Monospace })

        // 验证超链接注解
        val linkAnnotations = annotated.getLinkAnnotations(0, annotated.length)
        assertEquals(1, linkAnnotations.size)
        val link = linkAnnotations[0].item as LinkAnnotation.Url
        assertEquals("https://xjtu.edu.cn", link.url)
    }

    @Test
    fun testParagraphImageChunking() {
        val html = "<p>前置说明：<img src=\"https://example.com/step1.png\" alt=\"步骤一\">接下来继续操作。</p>"
        val doc = HtmlParser.parse(html)

        assertEquals(1, doc.blocks.size)
        val p = doc.blocks[0] as HtmlBlock.Paragraph
        assertEquals(3, p.parts.size)
        assertFalse(p.isPureImage)

        // Part 1: 前置文字
        val part1 = p.parts[0] as ParagraphPart.Text
        assertEquals("前置说明：", part1.text.text)

        // Part 2: 嵌入图片
        val part2 = p.parts[1] as ParagraphPart.Image
        assertEquals("https://example.com/step1.png", part2.url)
        assertEquals("步骤一", part2.alt)

        // Part 3: 后置文字
        val part3 = p.parts[2] as ParagraphPart.Text
        assertEquals("接下来继续操作。", part3.text.text)
    }

    @Test
    fun testPureImageParagraph() {
        val html = "<p><img src=\"https://example.com/banner.png\"></p>"
        val doc = HtmlParser.parse(html)

        assertEquals(1, doc.blocks.size)
        val p = doc.blocks[0] as HtmlBlock.Paragraph
        assertTrue("应标记为纯图片段落", p.isPureImage)
        assertEquals(1, p.parts.size)
        val img = p.parts[0] as ParagraphPart.Image
        assertEquals("https://example.com/banner.png", img.url)
    }

    @Test
    fun testHeadingsAndCodeBlock() {
        val html = """
            <h1>主标题</h1>
            <h2>二级标题</h2>
            <pre><code>val a = 1
val b = 2</code></pre>
        """.trimIndent()
        val doc = HtmlParser.parse(html)

        assertEquals(3, doc.blocks.size)
        val h1 = doc.blocks[0] as HtmlBlock.Heading
        assertEquals(1, h1.level)
        assertEquals("主标题", h1.text.text)

        val h2 = doc.blocks[1] as HtmlBlock.Heading
        assertEquals(2, h2.level)
        assertEquals("二级标题", h2.text.text)

        val code = doc.blocks[2] as HtmlBlock.CodeBlock
        assertEquals("val a = 1\nval b = 2", code.code)
    }

    @Test
    fun testBlockQuoteAndLists() {
        val html = """
            <blockquote><p>这是引用</p></blockquote>
            <ul>
                <li>第一项</li>
                <li>第二项</li>
            </ul>
            <ol>
                <li>步骤 1</li>
                <li>步骤 2</li>
            </ol>
        """.trimIndent()
        val doc = HtmlParser.parse(html)

        assertEquals(3, doc.blocks.size)
        val quote = doc.blocks[0] as HtmlBlock.BlockQuote
        assertEquals(1, quote.blocks.size)
        val quoteP = quote.blocks[0] as HtmlBlock.Paragraph
        assertEquals("这是引用", (quoteP.parts[0] as ParagraphPart.Text).text.text)

        val ul = doc.blocks[1] as HtmlBlock.ListBlock
        assertFalse(ul.ordered)
        assertEquals(2, ul.items.size)
        assertEquals("第一项", (ul.items[0].parts[0] as ParagraphPart.Text).text.text)

        val ol = doc.blocks[2] as HtmlBlock.ListBlock
        assertTrue(ol.ordered)
        assertEquals(2, ol.items.size)
        assertEquals("步骤 1", (ol.items[0].parts[0] as ParagraphPart.Text).text.text)
    }

    @Test
    fun testCssParser() {
        val style = HtmlCssParser.parseStyle("color: #ff0000; font-weight: bold; text-decoration: underline")
        assertNotNull(style)
        assertEquals(Color(0xFFFF0000), style!!.color)
        assertEquals(FontWeight.Bold, style.fontWeight)
        assertEquals(TextDecoration.Underline, style.textDecoration)

        val rgbColor = HtmlCssParser.parseColor("rgb(0, 128, 255)")
        assertNotNull(rgbColor)
        assertEquals(Color(0, 128, 255), rgbColor)

        val namedColor = HtmlCssParser.parseColor("red")
        assertNotNull(namedColor)
        assertEquals(Color(0xFFE53935), namedColor)
    }
}
