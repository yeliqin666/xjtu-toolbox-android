package com.xjtu.toolbox.html

import androidx.compose.ui.graphics.Color
import com.xjtu.toolbox.html.model.HtmlBlock
import com.xjtu.toolbox.html.model.ParagraphPart
import com.xjtu.toolbox.html.model.cssInvertHueRotate180
import com.xjtu.toolbox.html.model.cssInvertHueRotateColorMatrix
import com.xjtu.toolbox.html.parser.HtmlParser
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 暗色模式的「明亮模式配色 + invert(100%) hue-rotate(180deg)」这条换算。
 *
 * 判据不是「看着像」，而是**与 CSS 规范的像素结果逐位相同**：下面 [cssReference] 照着
 * Filter Effects 1 的矩阵实现，用来当独立对照——两边从同一套公式出发会一起错，所以参考实现
 * 单独按 `invert → hue-rotate` 两步写，不做代数化简。
 */
class HtmlDarkInvertTest {

    /** CSS `filter: invert(100%) hue-rotate(180deg)` 的两步直译（规范矩阵，不改写）。 */
    private fun cssReference(c: Color): Triple<Float, Float, Float> {
        // 第一步 invert(100%)：c' = 1 - c
        var r = 1f - c.red
        var g = 1f - c.green
        var b = 1f - c.blue
        // 第二步 hue-rotate(180deg)：cos=-1、sin=0 代进规范矩阵
        val m = listOf(
            listOf(-0.574f, 1.430f, 0.144f),
            listOf(0.426f, 0.430f, 0.144f),
            listOf(0.426f, 1.430f, -0.856f),
        )
        val nr = m[0][0] * r + m[0][1] * g + m[0][2] * b
        val ng = m[1][0] * r + m[1][1] * g + m[1][2] * b
        val nb = m[2][0] * r + m[2][1] * g + m[2][2] * b
        return Triple(nr.coerceIn(0f, 1f), ng.coerceIn(0f, 1f), nb.coerceIn(0f, 1f))
    }

    private fun assertSame(expected: Triple<Float, Float, Float>, actual: Color, label: String) {
        // 容差要覆盖两层误差：① 参考实现走纯 float、生产代码的结果又被 Color 量化回 8 位
        // （最多半个色阶）；② 浮点乘加的尾差。实测最大偏差约 0.0015，这里给到 2.5 个色阶，
        // 既不放过真正的系数错误（那是 0.07 量级），也不会因端上的浮点差异踩线。
        val tol = 2.5f / 255f
        assertTrue(abs(expected.first - actual.red) <= tol, "$label 红：期望 ${expected.first}，实际 ${actual.red}")
        assertTrue(abs(expected.second - actual.green) <= tol, "$label 绿：期望 ${expected.second}，实际 ${actual.green}")
        assertTrue(abs(expected.third - actual.blue) <= tol, "$label 蓝：期望 ${expected.third}，实际 ${actual.blue}")
    }

    @Test
    fun testMatchesCssFilterForKeyColors() {
        val samples = listOf(
            Color(0xFF000000), // 黑 → 白：上游写死 #000 的文字在暗色下必须变白
            Color(0xFFFFFFFF), // 白 → 黑：白底必须变深底
            Color(0xFF808080), // 中性灰 → 灰（不变）
            Color(0xFFFF0000), // 纯红 → 亮红
            Color(0xFF00FF00), // 纯绿 → 绿
            Color(0xFF0000FF), // 纯蓝 → 亮蓝
            Color(0xFF1E88E5), // 链接蓝
            Color(0xFF1A1A1A), // 正文墨色
        )
        samples.forEach { assertSame(cssReference(it), cssInvertHueRotate180(it), it.toString()) }
    }

    /**
     * 关键性质：黑白对调。这条单独钉，因为它就是本次要解决的「暗色下公式/正文显示成白色」。
     */
    @Test
    fun testBlackBecomesWhiteAndWhiteBecomesBlack() {
        val black = cssInvertHueRotate180(Color(0xFF000000))
        assertEquals(1f, black.red, 0.01f)
        assertEquals(1f, black.green, 0.01f)
        assertEquals(1f, black.blue, 0.01f)

        val white = cssInvertHueRotate180(Color(0xFFFFFFFF))
        assertEquals(0f, white.red, 0.01f)
        assertEquals(0f, white.green, 0.01f)
        assertEquals(0f, white.blue, 0.01f)
    }

    /** alpha 不动（CSS 滤镜同样只改颜色通道）。 */
    @Test
    fun testAlphaIsPreserved() {
        val semi = Color(0xFF000000).copy(alpha = 0.5f)
        // 容差取一个 8 位色阶：sRGB 的 Color 把 alpha 存成 0..255 的字节，0.5 落成 127 或 128
        // （0.498 / 0.502），比 0.001 松、比 1/255 紧，正好只容许这一个量化误差。
        assertEquals(0.5f, cssInvertHueRotate180(semi).alpha, 1f / 255f)
        // 全透明的样式位不该被动（它表示「没有这个样式」）
        assertEquals(Color.Unspecified, cssInvertHueRotate180(Color.Unspecified))
    }

    /**
     * 行内写死的颜色要跟着换算：这是 issue 309「半黑半白」的直接回归测试。
     *
     * 同一段里，正文跟随主题（已换算），`<span style="color:#000">` 若原样保留就会是纯黑，
     * 在深色页面上等于看不见/半黑半白。
     */
    @Test
    fun testInlineHardcodedColorIsInvertedInDarkMode() {
        val html = "<p>普通文字<span style=\"color:#000000\">写死黑字</span></p>"

        // 明亮模式：原样
        val light = HtmlParser.parse(html).blocks.single() as HtmlBlock.Paragraph
        val lightText = (light.parts.single() as ParagraphPart.Text).text
        val lightSpan = lightText.spanStyles.first { it.start == 4 }.item
        assertEquals(Color(0xFF000000), lightSpan.color)

        // 暗色模式：写死的黑变成白
        val dark = HtmlParser.parse(html, invertInlineColors = true).blocks.single() as HtmlBlock.Paragraph
        val darkText = (dark.parts.single() as ParagraphPart.Text).text
        val darkSpan = darkText.spanStyles.first { it.start == 4 }.item
        assertTrue(darkSpan.color.red > 0.9f, "深色模式下写死的黑字应变成白，实际 ${darkSpan.color}")
        assertTrue(darkSpan.color.green > 0.9f)
        assertTrue(darkSpan.color.blue > 0.9f)
    }

    /** 背景色也要换算，否则「白底黑字」反了字、没反底，变成白底白字。 */
    @Test
    fun testInlineBackgroundIsInvertedToo() {
        val html = "<p><span style=\"background-color:#FFFFFF;color:#000000\">高亮</span></p>"
        val dark = HtmlParser.parse(html, invertInlineColors = true).blocks.single() as HtmlBlock.Paragraph
        val span = (dark.parts.single() as ParagraphPart.Text).text.spanStyles.first().item
        // 字变白、底变黑：仍然可读
        assertTrue(span.color.red > 0.9f, "字应为白，实际 ${span.color}")
        assertTrue(span.background.red < 0.1f, "底应为黑，实际 ${span.background}")
    }

    /**
     * 图片用的颜色矩阵必须与文字用的逐色公式得出同一个结果 —— 否则「图里的公式」和
     * 「正文」会各自反成不同颜色（比如图偏白、字偏灰）。
     *
     * 矩阵按 `ColorMatrix` 的约定作用在 0..255 的通道上（第 5 列是平移量），所以这里也按
     * 0..255 算一遍，再与逐色公式（0..1）比，容差 1 个色阶。
     */
    @Test
    fun testColorMatrixMatchesScalarFormula() {
        val m = cssInvertHueRotateColorMatrix().values
        fun viaMatrix(r: Int, g: Int, b: Int): Triple<Float, Float, Float> {
            val rf = r.toFloat()
            val gf = g.toFloat()
            val bf = b.toFloat()
            // 输入按矩阵自己的量纲（0..255）喂进去，平移列 m[i + 4] 本身就是 255 这个量级的
            // 常数、直接相加即可 —— 再乘一次 255 会把它放大到 65025，结果全部饱和成 255。
            fun row(i: Int): Float =
                (m[i] * rf + m[i + 1] * gf + m[i + 2] * bf + m[i + 4]).coerceIn(0f, 255f)
            return Triple(row(0), row(5), row(10))
        }
        // 中性灰 + 三原色：既覆盖「补色」性质，也能发现矩阵系数抄错行
        val samples = listOf(
            Triple(0, 0, 0), Triple(128, 128, 128), Triple(255, 255, 255),
            Triple(255, 0, 0), Triple(0, 255, 0), Triple(0, 0, 255),
        )
        for ((r, g, b) in samples) {
            // 生产代码的逐色公式吃 0..1 的 float 通道；这里传同样的分量做对照
            val scalar = cssInvertHueRotate180(Color(r / 255f, g / 255f, b / 255f, 1f))
            val matrix = viaMatrix(r, g, b)
            assertTrue(
                abs(matrix.first - scalar.red * 255f) <= 1f,
                "($r,$g,$b) 红通道：矩阵 ${matrix.first} vs 逐色 ${scalar.red * 255f}",
            )
            assertTrue(
                abs(matrix.second - scalar.green * 255f) <= 1f,
                "($r,$g,$b) 绿通道：矩阵 ${matrix.second} vs 逐色 ${scalar.green * 255f}",
            )
            assertTrue(
                abs(matrix.third - scalar.blue * 255f) <= 1f,
                "($r,$g,$b) 蓝通道：矩阵 ${matrix.third} vs 逐色 ${scalar.blue * 255f}",
            )
        }
    }
}
