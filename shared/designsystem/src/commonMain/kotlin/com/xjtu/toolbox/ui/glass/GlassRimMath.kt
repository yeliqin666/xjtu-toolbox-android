package com.xjtu.toolbox.ui.glass

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow

/**
 * 液态玻璃「轮廓高光」的纯计算部分，从 [staticGlassSurface]（`StaticGlass.kt`）里抽出来方便写单测。
 *
 * 库里（`com.kyant.backdrop`）那圈高光**不是一条等亮度的描边**：`HighlightStyle.Default`
 * 走 `DefaultHighlightShaderString`，逐像素算
 * `intensity = pow(abs(dot(外法线, 光向)), falloff)`，再拿它去乘描边色。所以同一块玻璃上
 * 四条直边等亮（45° 光下都是 cos45 ≈ 0.707）、四个圆角里正对光的那一对亮到 1.0、
 * 背着光的那一对渐隐到 0——「光感」就是从这圈强弱变化来的。
 *
 * 采样版由库自己画；不采样版没有 runtime shader 可用，只能按同一条公式把轮廓切成
 * 「四段直边 + 四段圆角」分别上色（见 `StaticGlass.kt` 的 `GlassRim`），所以公式必须是同一份，
 * 改库或换高光参数时两边一起变。
 */
object GlassRimMath {

    /**
     * 轮廓上某一点的高光强度，取值 `0..1`。
     *
     * @param normalAngleDegrees 该点的**外法线**方向，屏幕度：0 = 向右（+x），顺时针为正
     *   （屏幕坐标 y 向下，所以 90° 是向下、270° 是向上）。直边是常量，圆角里随角度连续变化。
     * @param lightAngleDegrees 光来的方向，同样用屏幕度；就是
     *   `HighlightStyle.Default.angle`（缺省 45°，即从左上角打向右下角）。
     * @param falloff `HighlightStyle.Default.falloff`：越大越「聚光」（弱侧掉得更快），1 是线性。
     */
    fun intensity(
        normalAngleDegrees: Float,
        lightAngleDegrees: Float,
        falloff: Float,
    ): Float {
        // falloff < 0 在库里是 pow(0, 负数) = 无穷大，画出来是一圈纯白；这里按「不衰减」处理
        if (falloff < 0f) return 1f
        val d = abs(cos((normalAngleDegrees - lightAngleDegrees) * DEGREES_TO_RADIANS))
        return if (falloff == 1f) d else d.pow(falloff)
    }

    private val DEGREES_TO_RADIANS = (PI / 180.0).toFloat()
}
