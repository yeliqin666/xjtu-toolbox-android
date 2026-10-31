package com.xjtu.toolbox.html.model

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.text.SpanStyle

/**
 * CSS `filter: invert(100%) hue-rotate(180deg)` 的换算，分两种用法：
 *
 * - 纯色（文字、底色、行内写的 `color`）→ [cssInvertHueRotate180]，逐色算；
 * - 位图（HTML 里的 `<img>`，比如转成图片的公式、文档截图）→ [cssInvertHueRotateColorMatrix]，
 *   交给 `ColorFilter.colorMatrix` 逐像素算。
 *
 * 为什么文字用逐色换算而不是也给整个子树挂滤镜：Compose 跨端没有一层能挂在任意 composable 上的
 * 像素滤镜（Android 的 `RenderEffect` 要 API 31+，`:desktop` / `:web` 根本没有）。文字的颜色都是
 * 我们自己给的单色，逐色算与 CSS 逐像素算的结果完全一致；图片只能用 `ColorFilter` 逐像素算。
 *
 * 两个滤镜可以合并成一次线性运算。`invert(100%)` 是 `c' = 1 - c`；`hue-rotate(180deg)` 在
 * sRGB 空间是无平移项的 3×3 线性变换，且它每一行系数之和为 1（白色转完仍是白色），于是
 *
 *     hueRotate(invert(c)) = M(1 - c) = M(1) - M(c) = 1 - M(c)
 *
 * 也就是说「先反色再转色相」等价于「对原色做一次 M 再取补」，不需要两步。下面三行就是
 * θ=180° 时 M 的三行系数（cos=-1、sin=0），外层的 `1 -` 就是那步取补。系数已与
 * Filter Effects 1 的规范矩阵逐位核对（见 `HtmlDarkInvertTest`）。
 *
 * 结果按 CSS 的规矩钳到 `[0,1]`，alpha 原样保留（CSS 滤镜也不动 alpha）。
 */
internal fun cssInvertHueRotate180(color: Color): Color {
    if (color == Color.Unspecified || color.alpha <= 0f) return color
    val r = color.red
    val g = color.green
    val b = color.blue
    // 三行分别对应 R / G / B，系数是 hue-rotate(180deg) 矩阵，取补在最后一步。
    val outR = 1f - (-0.574f * r + 1.430f * g + 0.144f * b)
    val outG = 1f - (0.426f * r + 0.430f * g + 0.144f * b)
    val outB = 1f - (0.426f * r + 1.430f * g - 0.856f * b)
    return Color(
        red = outR.coerceIn(0f, 1f),
        green = outG.coerceIn(0f, 1f),
        blue = outB.coerceIn(0f, 1f),
        alpha = color.alpha,
    )
}

/**
 * 同一套滤镜的 4×5 颜色矩阵（给图片用）。
 *
 * 上面的逐色公式是「1 - M(c)」，展开成矩阵就是 `-M` 再加一个平移项「1」（纯白）。`ColorMatrix`
 * 的矩阵作用在 0..255 的通道上、第 5 列是平移量，所以平移写 255 而不是 1（这是该 API 在
 * Android 与 Skia 两侧共用的约定）。alpha 行保持原值（末列 0）。
 *
 * 与 [cssInvertHueRotate180] 的一致性由 `HtmlDarkInvertTest` 逐通道比对（阈值 1 个色阶）。
 */
internal fun cssInvertHueRotateColorMatrix(): ColorMatrix = ColorMatrix().apply {
    // 第 1 行（R 输出）：-(hue-rotate 第 1 行) 乘上各通道 + 255
    values[0] = 0.574f
    values[1] = -1.430f
    values[2] = -0.144f
    values[4] = 255f
    // 第 2 行（G 输出）
    values[5] = -0.426f
    values[6] = -0.430f
    values[7] = -0.144f
    values[9] = 255f
    // 第 3 行（B 输出）
    values[10] = -0.426f
    values[11] = -1.430f
    values[12] = 0.856f
    values[14] = 255f
}

/**
 * 把一段行内样式的前景/背景色换算成暗色下的样子。
 *
 * 行内 `color` / `background-color` 是上游 HTML 自己写死的（`#000` 那一类正是 issue 309
 * 「半黑半白」的来源）：正文跟随主题、写死的颜色不跟随，两边在暗色下就对不上。所以解析出来的
 * span 也必须走同一条换算，否则又会出现两套色。
 */
internal fun SpanStyle.cssInvertHueRotate180(): SpanStyle = copy(
    color = if (color == Color.Unspecified) color else cssInvertHueRotate180(color),
    background = if (background == Color.Unspecified) background else cssInvertHueRotate180(background),
)
