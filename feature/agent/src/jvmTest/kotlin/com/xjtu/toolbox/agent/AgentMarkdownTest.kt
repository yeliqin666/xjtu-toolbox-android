package com.xjtu.toolbox.agent

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AgentMarkdownTest {
    private val d = "$"
    private fun render(text: String) = inlineMarkdown(text, Color.Blue, Color.Gray, Color.Red, {})
    private fun maths(text: String) = parseBlocks(text).filterIsInstance<MdBlock.Math>()

    @Test fun formulaInsideBoldKeepsBothMathAndBold() {
        val result = render("公式 **${d}\\frac{1}{2} + x^2${d}**")
        assertEquals("公式 ${d}\\frac{1}{2} + x^2${d}", result.text)
        assertEquals(listOf("\\frac{1}{2} + x^2"), inlineTexOf(result))
        assertTrue(result.spanStyles.any { it.item.fontWeight == FontWeight.Bold && it.start == 3 && it.end == result.length })
    }

    @Test fun parenthesizedMathInsideBoldItalicRenders() {
        val result = render("***\\(\\alpha \\leq x^2\\)***")
        assertEquals("\\(\\alpha \\leq x^2\\)", result.text)
        assertEquals(listOf("\\alpha \\leq x^2"), inlineTexOf(result))
        assertTrue(result.spanStyles.any { it.item.fontWeight == FontWeight.Bold && it.item.fontStyle == FontStyle.Italic })
    }

    @Test fun codeAndCurrencyInsideBoldStayLiteral() {
        val code = render("**`${d}x^2${d}`**")
        assertEquals(" ${d}x^2${d} ", code.text)
        assertTrue(code.spanStyles.any { it.item.fontFamily == FontFamily.Monospace })
        assertTrue(inlineTexOf(code).isEmpty())
        val money = render("**金额 ${d}5 和 ${d}10**")
        assertEquals("金额 ${d}5 和 ${d}10", money.text)
        assertTrue(inlineTexOf(money).isEmpty())
    }

    @Test fun dollarAmountsAreNotMath() {
        assertTrue(inlineTexOf(render("书 ${d}5，饭 ${d} 10，共 ${d}15 元")).isEmpty())
        assertTrue(maths("书 ${d}5，饭 ${d}10").isEmpty())
    }

    @Test fun allFourDelimiters() {
        assertEquals(listOf("a^2", "b_1"), inlineTexOf(render("设 ${d}a^2${d} 与 \\(b_1\\)")))
        assertEquals(listOf("x+y", "\\sqrt{2}"), maths("${d}${d}x+y${d}${d}\n\\[\\sqrt{2}\\]").map { it.tex })
    }

    @Test fun displayMathInMiddleOfParagraph() {
        val blocks = parseBlocks("由 ${d}${d}E=mc^2${d}${d} 可知能量")
        assertEquals(3, blocks.size)
        assertEquals("由", (blocks[0] as MdBlock.Para).text)
        val m = assertIs<MdBlock.Math>(blocks[1])
        assertEquals("E=mc^2", m.tex)
        assertEquals("${d}${d}E=mc^2${d}${d}", m.raw)
        assertEquals("可知能量", (blocks[2] as MdBlock.Para).text)
    }

    @Test fun multiLineAlignedKeepsLineBreaks() {
        val src = "于是\n${d}${d}\n\\begin{aligned}\na &= b + c \\\\[2pt]\n  &= d\n\\end{aligned}\n${d}${d}\n结束"
        val blocks = parseBlocks(src)
        val m = maths(src).single()
        assertTrue(m.tex.contains("\\\\[2pt]\n"), m.tex)
        assertTrue(m.tex.lines().size >= 4, m.tex)
        assertTrue(texParses(m.tex))
        assertEquals("结束", (blocks.last() as MdBlock.Para).text)
    }

    @Test fun matrixAndCasesParse() {
        assertTrue(texParses("\\begin{pmatrix} 1 & 2 \\\\ 3 & 4 \\end{pmatrix}"))
        assertTrue(texParses("f(x)=\\begin{cases} x, & x>0 \\\\ 0, & \\text{否则} \\end{cases}"))
        val m = maths("\\[\n\\begin{bmatrix}1&0\\\\0&1\\end{bmatrix}\n\\]").single()
        assertTrue(m.tex.startsWith("\\begin{bmatrix}"))
    }

    @Test fun parseFailureFallsBackToRaw() {
        assertFalse(texParses("\\notarealcommand{x}"))
        assertFalse(texParses(""))
        assertFalse(texParses("\\frac{1}{"))
        assertTrue(texParses("\\int_0^1 x\\,dx = \\tfrac{1}{2}"))
        val blocks = parseBlocks("看 ${d}${d}\\frac{1}{2}\n还没写完\n\n## 标题\n- 列表\n```\ncode\n```")
        assertTrue(blocks.none { it is MdBlock.Math })
        assertEquals("看 ${d}${d}\\frac{1}{2}", (blocks[0] as MdBlock.Para).text)
        assertIs<MdBlock.Heading>(blocks.first { it !is MdBlock.Para })
        assertTrue(blocks.any { it is MdBlock.Bullet } && blocks.any { it is MdBlock.Code })
    }

    @Test fun displayDelimiterInsideInlineCodeIsText() {
        assertTrue(maths("用 `${d}${d}` 包裹公式，或 `echo ${d}${d}`").isEmpty())
        assertEquals(listOf("x"), maths("`${d}${d}` 之后 ${d}${d}x${d}${d}").map { it.tex })
    }

    @Test fun mismatchedEnvironmentFallsBack() {
        assertFalse(texParses("\\begin{aligned} a \\end{cases}"))
    }

    @Test fun veryLongFormulaFallsBack() {
        assertFalse(texParses("x+".repeat(1500) + "x"))
    }

    @Test fun veryLongLineSkipsMath() {
        val line = "${d}${d}x${d}${d} " + "\\(a\\) ".repeat(2000)
        assertTrue(maths(line).isEmpty())
        assertTrue(inlineTexOf(inlineMarkdown(line, Color.Blue, Color.Gray, Color.Red, {})).isEmpty())
    }
}
