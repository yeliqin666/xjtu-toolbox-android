package com.xjtu.toolbox.ui.glass

import kotlin.math.PI
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [GlassRimMath.intensity] 是「不采样版轮廓高光」唯一的纯逻辑：它必须与库里
 * `DefaultHighlightShaderString` 的公式逐字一致，否则两块玻璃的光感会分成两套。
 *
 * 角度用「屏幕度」：0 = 向右、90 = 向下、180 = 向左、270 = 向上；缺省光向是 45°。
 */
class GlassRimMathTest {

    private fun cos45() = cos((PI / 4).toFloat())

    @Test
    fun `45度光下四条直边等亮`() {
        // 直边的外法线朝右 / 下 / 左 / 上，与光向的夹角都是 45°（或 135°，取绝对值后一样）
        val expected = cos45()
        listOf(0f, 90f, 180f, 270f).forEach { normal ->
            assertEquals(expected, GlassRimMath.intensity(normal, 45f, 1f), 1e-4f, "法线 $normal")
        }
    }

    @Test
    fun `45 度光下亮的是对角，暗的是另一对`() {
        // 「光感」的全部来源：法线与光向同向（或恰好反向）的那一对圆角最亮，
        // 与之垂直的那一对归零。反向也亮是 shader 里那个 abs 带来的，见下一条用例
        assertEquals(1f, GlassRimMath.intensity(45f, 45f, 1f), 1e-4f)
        assertEquals(1f, GlassRimMath.intensity(225f, 45f, 1f), 1e-4f)
        assertEquals(0f, GlassRimMath.intensity(135f, 45f, 1f), 1e-4f)
        assertEquals(0f, GlassRimMath.intensity(315f, 45f, 1f), 1e-4f)
    }

    @Test
    fun `光向转 90 度亮暗互换，转 180 度观感不变`() {
        // 库里的强度带 abs（`pow(abs(dot), falloff)`）：只有这样整圈轮廓才「亮一对、暗一对」，
        // 而不是只剩半边有高光。副作用是光向差 180° 时分布一模一样，
        // 只有差 90° 才会把亮暗两对换过来——所以别在这里断言「背着光的那一侧归零」。
        val light = 45f
        // 差 180°：不变
        assertEquals(
            GlassRimMath.intensity(0f, light, 1f),
            GlassRimMath.intensity(0f, light + 180f, 1f),
            1e-4f,
        )
        // 差 90°：原本最亮的对角变成最暗，原本最暗的变成最亮
        assertEquals(1f, GlassRimMath.intensity(45f, light, 1f), 1e-4f)
        assertEquals(0f, GlassRimMath.intensity(45f, light + 90f, 1f), 1e-4f)
        assertEquals(0f, GlassRimMath.intensity(135f, light, 1f), 1e-4f)
        assertEquals(1f, GlassRimMath.intensity(135f, light + 90f, 1f), 1e-4f)
    }

    @Test
    fun `falloff 越大越聚光，弱侧掉得更快`() {
        // 直边在 45° 光下的强度 = cos45^falloff：falloff 1 → 0.707，2 → 0.5，4 → 0.25
        assertEquals(cos45(), GlassRimMath.intensity(0f, 45f, 1f), 1e-4f)
        assertEquals(0.5f, GlassRimMath.intensity(0f, 45f, 2f), 1e-4f)
        assertEquals(0.25f, GlassRimMath.intensity(0f, 45f, 4f), 1e-4f)
        // 强侧永远是 1.0，与 falloff 无关
        assertEquals(1f, GlassRimMath.intensity(45f, 45f, 4f), 1e-4f)
    }

    @Test
    fun `负的 falloff 按不衰减处理，不出 NaN 或无穷`() {
        // 库里 pow(0, 负数) = 无穷，画出来是一圈纯白；这里按 1 处理，别把 alpha 乘成 NaN
        assertEquals(1f, GlassRimMath.intensity(135f, 45f, -1f), 1e-4f)
    }

    @Test
    fun `结果是 0 到 1 之间的有限值`() {
        val values = listOf(
            GlassRimMath.intensity(0f, 45f, 1f),
            GlassRimMath.intensity(123f, 45f, 1f),
            GlassRimMath.intensity(359f, 45f, 1f),
            GlassRimMath.intensity(45f, 400f, 1f),
        )
        values.forEach { assertTrue(it in 0f..1f, "越界：$it") }
    }
}
