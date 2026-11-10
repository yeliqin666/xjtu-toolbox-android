package com.xjtu.toolbox.ui.glass.coui

import kotlin.math.max

/** 原厂 Drawable 的 EDGE_STYLE_0/2 与 SHADOW_STYLE_0 映射，尺寸为完整像素。 */
internal fun shadowEdgeArray(width: Float, height: Float, density: Float, lineWidthPx: Float, lineAlpha: Float,
    control: Boolean): FloatArray {
    val scale = max(1f, density) * 0.34f
    val widthUnits = max(1f, width) / scale
    val heightUnits = max(1f, height) / scale
    val near = floatArrayOf(2f, if (control) 6f else 8f)
    val far = floatArrayOf(2f, if (control) 3f else 2f)
    val denominator = max(near[0] + near[1], far[0] + far[1]).coerceAtLeast(1e-4f)
    val factor = (if (lineWidthPx.isFinite() && lineWidthPx > 0f) lineWidthPx else denominator) / denominator
    val out = FloatArray(16)
    out[0] = 1f
    out[1] = 1f
    out[2] = 1f
    out[3] = max(near[0] * factor, 2f)
    out[4] = max(near[1] * factor, 2f)
    out[5] = max(far[0] * factor, 2f)
    out[6] = max(far[1] * factor, 2f)
    out[7] = 0.7f * lineAlpha
    out[8] = (if (control) 1f else 0.6f) * lineAlpha
    val (nearSolid, nearFade) = rectLineFadeForSize(
        if (control) 0.2f else 0.18f, if (control) 0.4f else 0.35f,
        if (control) 400f else 600f,
        if (control) 100f else 200f,
        widthUnits, heightUnits,
    )
    val (farSolid, farFade) = rectLineFadeForSize(
        if (control) 0.15f else 0.25f, if (control) 0.35f else 0.25f,
        if (control) 400f else 600f,
        if (control) 100f else 200f,
        widthUnits, heightUnits,
    )
    out[9] = nearSolid
    out[10] = nearFade
    out[11] = farSolid
    out[12] = farFade
    out[4] += out[3]
    out[6] += out[5]
    out[10] += out[9]
    out[12] += out[11]
    out[13] = 0f
    out[14] = 4f
    out[15] = 0.8f
    return out
}

/** InnerShadowStyleMapper.toShadowArray16 的 SHADOW_STYLE_0; 明暗只差 fadeAlphaIn。 */
internal fun shadowGlowArray(width: Float, height: Float, density: Float, alpha: Float): FloatArray {
    val scale = max(1f, density) * 0.34f
    val widthUnits = max(1f, width) / scale
    val heightUnits = max(1f, height) / scale
    val out = FloatArray(16)
    out[0] = 1f
    out[1] = 1f
    out[2] = 1f
    out[3] = alpha
    out[4] = 0f
    out[5] = 0f
    out[6] = 120f * scale
    out[7] = 0f
    out[8] = 20f * scale
    out[9] = (400f * (1.3f - 1f)) / widthUnits + 1f
    out[10] = (100f * (1.15f - 1f)) / heightUnits + 1f
    out[11] = 4f
    out[12] = 0.9f
    out[13] = 20f * scale
    out[14] = 4f
    out[15] = 0.7f
    return out
}
