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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur

/**
 * 顶栏的无边界渐变模糊：**两级阶梯模糊 + 各自的竖向渐变遮罩**，再盖一层从顶到底淡出的表面色。
 * 只画不量：不改顶栏尺寸，内容排版和留白一概不受影响。
 *
 * 历史与取舍（2026-11，三次真机反馈）：最初是 useplanes 的 progressive blur——七层半径
 * 0.75→18dp 叠着跑，每层用一条渐变只留一段，于是「越靠顶边越糊」，代价是每帧七次全屏宽的
 * 离屏采样 + 七次高斯模糊。之后试过两种省法，**真机上都更慢，已撤**：①把采样层录成半分辨率
 * 再放大画回去——`RenderEffect` 与 runtime shader 在设备空间求值，层录小并不会让特效少算，
 * 只平白多一次重采样；②收成一层 12dp + 一条「顶上不透明、下边透明」的遮罩——把 7 段折线压成
 * 1 段，而大面积大半径模糊并不比七层小半径便宜。
 *
 * 现在抽成**两级**：上半段 10dp、下半段 3dp，两条遮罩在交界处互相淡入淡出，既没有硬边、
 * 又保住「越靠顶边越糊」这条曲线；开销 2/7，全分辨率、零画布缩放。想再省就把
 * [ProgressiveBlurSteps] 并成一级。
 *
 * [backdrop] 先垫一层不透明表面色再采样，否则模糊把透明当黑色，字周围会出脏黑边。
 */
@Composable
fun Modifier.progressiveBlurBar(backdrop: Backdrop, tint: Color, titled: Boolean = true): Modifier {
    val surfaceDraw: DrawScope.() -> Unit = remember(tint) { { drawRect(tint.copy(alpha = 1f)) } }
    val sampled = rememberCombinedBackdrop(rememberCanvasBackdrop(surfaceDraw), backdrop)
    val layers = remember(sampled, tint, titled) {
        ProgressiveBlurSteps.fold(Modifier as Modifier) { acc, step ->
            acc then Modifier.drawBackdrop(
                backdrop = sampled,
                shape = { BandShape },
                effects = { blur(step.radius.toPx(), TileMode.Decal) },
                highlight = null,
                shadow = null,
                // 遮罩必须画在本层自己的采样层里：onDrawFront 在内容之后，会把标题一起裁掉
                onDrawBackdrop = { drawBackdrop ->
                    drawBackdrop()
                    drawRect(step.mask, blendMode = BlendMode.DstIn)
                },
            )
        } then Modifier.drawWithCache {
            // 模糊之上、标题之下，到底边淡到 0。有标题的顶栏上半段多压一点表面色保证字清楚；
            // 只有圆钮的（首页）从顶到底线性淡出
            val wash = if (titled) Brush.verticalGradient(
                0f to tint.copy(alpha = (tint.alpha + 0.2f).coerceAtMost(1f)),
                0.7f to tint,
                1f to tint.copy(alpha = 0f),
            ) else Brush.verticalGradient(0f to tint, 1f to tint.copy(alpha = 0f))
            onDrawBehind { drawRect(wash) }
        }
    }
    return this then layers
}

/** 直角写成 RoundedCornerShape(0.dp)：RectangleShape 开折射会闪退。 */
private val BandShape = RoundedCornerShape(0.dp)

/** 一级模糊：半径 + 只留它那一段的竖向遮罩。 */
private class ProgressiveBlurStep(val radius: Dp, val mask: Brush)

/**
 * 两级阶梯（2026-11）。上面那段强、下面那段弱，交界（0.42→0.55 一带）两条遮罩互相淡出。
 * 全分辨率、零缩放——不要再往这里塞「录小一点再放大」那种省法，真机上只会更慢。
 */
private val ProgressiveBlurSteps = listOf(
    ProgressiveBlurStep(10.dp, blurMask(0f to 1f, 0.42f to 1f, 0.78f to 0f)),
    ProgressiveBlurStep(3.dp, blurMask(0.30f to 0f, 0.55f to 1f, 0.85f to 1f, 1f to 0f)),
)

/** 把「位置 → alpha」的几档折成一条竖向渐变（0 = 带的顶边，1 = 底边）。 */
private fun blurMask(vararg stops: Pair<Float, Float>): Brush = Brush.verticalGradient(
    *stops.map { (position, alpha) -> position to Color.Black.copy(alpha = alpha) }.toTypedArray(),
)
