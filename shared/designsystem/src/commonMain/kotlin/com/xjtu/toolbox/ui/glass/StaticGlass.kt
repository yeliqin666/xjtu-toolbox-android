package com.xjtu.toolbox.ui.glass

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

/**
 * **不采样版的液态玻璃**：把 `drawBackdrop` 里那些**不靠采样也画得出**的效果单独补回来。
 *
 * 背景上没有可糊、可折的细节时（纯色 / 渐变，或壁纸被糊成一片色块），`drawBackdrop` 那一次
 * 整块卡面的离屏采样采到的只是一片纯色底——`blur()` 与 `lens()` 已经关掉了，剩下的
 * `vibrancy()` 在这种底上也看不出任何变化。但它**同时**还负责三样与采样无关的观感：
 * 顶部打光（`Highlight`）、轮廓高光、以及那圈同色环境光投影。少了这三样，卡片就从
 * 「一块浮起来的玻璃」退成「一张平涂的纸」——2026-11 维护者指出的正是这个。
 *
 * 所以这里把这三样用纯绘制的方式补齐，与 `drawBackdrop` 那一版**同一组观感参数**
 * （投影照抄 [floatingGlassShadow] 的半径 / 偏移 / 混色 / 透明度），切换背景时不会
 * 「忽然换了个材质」。
 *
 * 调用方自己负责 `clip` 与底色，本文件只补光影。
 */

/**
 * 顶部打光：玻璃被上方光源照亮的那一道白，往下迅速衰减。
 *
 * 对应 `drawBackdrop` 的 `highlight`——库里那层是一圈带方向的描边光，卡片不大，一道自上而下的
 * 渐变肉眼几乎看不出差别，却完全不需要采样。
 */
fun glassSheen(isDark: Boolean): Brush = Brush.verticalGradient(
    0f to Color.White.copy(alpha = if (isDark) 0.14f else 0.30f),
    0.45f to Color.White.copy(alpha = if (isDark) 0.04f else 0.08f),
    1f to Color.Transparent,
)

/**
 * 轮廓高光的浓度（喂给调用方自己的 `squircleBorder` / `border`）：浅色更明显，深色只留一线。
 *
 * 对应 `drawBackdrop` 的 `highlight` 里那圈亮边；与卡片原来的 `hairline` 是同一个意图，
 * 只是取值统一收到这里，让「不采样版」和「采样版」看不出接缝。
 */
fun glassHairline(isDark: Boolean): Color = Color.White.copy(alpha = if (isDark) 0.18f else 0.66f)

/**
 * 环境光投影：**同一组参数**照抄 [floatingGlassShadow]（半径 28dp、下移 10dp、黑色掺 40%
 * 主题色、浅色 16% / 深色 45% 不透明），只是换成点击即画的 `Modifier.dropShadow`。
 */
fun Modifier.staticGlassShadow(
    accent: Color,
    isDark: Boolean,
    shape: Shape,
): Modifier = dropShadow(shape) {
    radius = 28.dp.toPx()
    offset = Offset(0f, 10.dp.toPx())
    color = lerp(Color.Black, accent, 0.4f)
    alpha = if (isDark) 0.45f else 0.16f
}
