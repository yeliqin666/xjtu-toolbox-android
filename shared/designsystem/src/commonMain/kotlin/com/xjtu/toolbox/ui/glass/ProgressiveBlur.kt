package com.xjtu.toolbox.ui.glass

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur

/**
 * 顶栏的无边界渐变模糊：**一次模糊 + 一条顶上不透明、下边透明的渐变遮罩**，
 * 再盖一层从顶到底淡出的表面色。只画不量：不改顶栏尺寸，内容排版和留白一概不受影响。
 *
 * 这里以前是 useplanes 的 progressive blur——七层不同半径的模糊叠着跑，每层用自己的渐变遮罩只留一段，
 * 于是「越靠顶边越糊」。代价是每帧七次全屏宽的离屏采样 + 七次高斯模糊，二级页里没有比它更贵的。
 * 2026-11 改成现在这个做法（维护者提的）：只做**一次** `blur()`（库里的 `blur()` 在 Android 上就是
 * `RenderEffect.createBlurEffect`），再用一条竖向渐变把结果按「顶边满强度、底边归零」压掉。
 * 「模糊权重从顶到底递减」这条曲线，七层 ramp 用七个半径拼，这里用一个半径乘一条 alpha 曲线拼，
 * 开销是原来的 1/7；采样层再按一半分辨率录（`downsample = 0.5f`），像素数又降到 1/4。
 * 嫌不够糊就调 [PROGRESSIVE_BLUR_RADIUS] 这一个值。
 *
 * [backdrop] 先垫一层不透明表面色再采样，否则模糊把透明当黑色，字周围会出脏黑边。
 */
@Composable
fun Modifier.progressiveBlurBar(backdrop: Backdrop, tint: Color, titled: Boolean = true): Modifier {
    val surfaceDraw: DrawScope.() -> Unit = remember(tint) { { drawRect(tint.copy(alpha = 1f)) } }
    val sampled = rememberCombinedBackdrop(rememberCanvasBackdrop(surfaceDraw), backdrop)
    val layers = remember(sampled, tint, titled) {
        Modifier.drawBackdrop(
            backdrop = sampled,
            shape = { BandShape },
            effects = { blur(PROGRESSIVE_BLUR_RADIUS.toPx(), TileMode.Decal) },
            downsample = 0.5f,
            highlight = null,
            shadow = null,
            // 遮罩必须画在本层自己的采样层里：onDrawFront 在内容之后，会把标题一起裁掉
            onDrawBackdrop = { drawBackdrop ->
                drawBackdrop()
                drawRect(ProgressiveBlurMask, blendMode = BlendMode.DstIn)
            },
        ).then(
            Modifier.drawWithCache {
                // 模糊之上、标题之下，到底边淡到 0。有标题的顶栏上半段多压一点表面色保证字清楚；
                // 只有圆钮的（首页）从顶到底线性淡出
                val wash = if (titled) Brush.verticalGradient(
                    0f to tint.copy(alpha = (tint.alpha + 0.2f).coerceAtMost(1f)),
                    0.7f to tint,
                    1f to tint.copy(alpha = 0f),
                ) else Brush.verticalGradient(0f to tint, 1f to tint.copy(alpha = 0f))
                onDrawBehind { drawRect(wash) }
            },
        )
    }
    return this then layers
}

/** 直角写成 RoundedCornerShape(0.dp)：RectangleShape 开折射会闪退。 */
private val BandShape = RoundedCornerShape(0.dp)

/**
 * 这条带唯一的模糊半径。原来的七层 ramp 从 0.75dp 一直到 18dp；单层取一个中偏强的值，
 * 「越靠顶边越糊」那段改由 [ProgressiveBlurMask] 的 alpha 曲线表达。
 */
private val PROGRESSIVE_BLUR_RADIUS = 12.dp

/**
 * 模糊的竖向遮罩：顶边完全不透明、底边透明。
 *
 * 中间多停两档（0.55 处还是 85%、0.85 处降到 25%）再收尾，让「糊→清」的过渡更靠下、
 * 上半段更像一层厚霜；纯两点线性的话整条带会显得上下都不够糊。
 */
private val ProgressiveBlurMask: Brush = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = 1f),
    0.55f to Color.Black.copy(alpha = 0.85f),
    0.85f to Color.Black.copy(alpha = 0.25f),
    1f to Color.Black.copy(alpha = 0f),
)
