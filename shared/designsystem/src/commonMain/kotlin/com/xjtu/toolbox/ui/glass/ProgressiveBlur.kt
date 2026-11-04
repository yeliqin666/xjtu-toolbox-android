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
 * 顶栏的无边界渐变模糊（useplanes 的 progressive blur）：七层不同半径的模糊叠在一起，
 * 每层用一条渐变遮罩只留一段，顶边最强、底边归零，再盖一层从顶到底淡出的表面色。
 * 只画不量：不改顶栏尺寸，内容排版和留白一概不受影响。
 *
 * [backdrop] 先垫一层不透明表面色再采样，否则模糊把透明当黑色，字周围会出脏黑边。
 */
@Composable
fun Modifier.progressiveBlurBar(backdrop: Backdrop, tint: Color, titled: Boolean = true): Modifier {
    val surfaceDraw: DrawScope.() -> Unit = remember(tint) { { drawRect(tint.copy(alpha = 1f)) } }
    val sampled = rememberCombinedBackdrop(rememberCanvasBackdrop(surfaceDraw), backdrop)
    val layers = remember(sampled, tint, titled) {
        ProgressiveBlurRadii.indices.fold(Modifier as Modifier) { acc, index ->
            acc then Modifier.drawBackdrop(
                backdrop = sampled,
                shape = { BandShape },
                effects = { blur(ProgressiveBlurRadii[index].dp.toPx(), TileMode.Decal) },
                highlight = null,
                shadow = null,
                // 遮罩必须画在本层自己的背景图层里：onDrawFront 在内容之后，会把标题和后面几层一起裁掉
                onDrawBackdrop = { drawBackdrop ->
                    drawBackdrop()
                    drawRect(ProgressiveBlurMasks[index], blendMode = BlendMode.DstIn)
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

/** 七层模糊半径（useplanes 默认 ramp × 1.5），从底边到顶边。 */
private val ProgressiveBlurRadii = listOf(0.75f, 1.5f, 3f, 4.5f, 7.5f, 12f, 18f)

/** 层与层错开 12.5%，每层是「透明 → 不透明 → 不透明 → 透明」的一条带。 */
private const val PROGRESSIVE_BLUR_STEP = 0.125f

/** 按从底边往上数的位置生成：第 0 层在底边附近淡出，第 6 层在顶边满强度。 */
private val ProgressiveBlurMasks: List<Brush> = ProgressiveBlurRadii.indices.map { index ->
    val stops = listOf(
        (1f - (index + 3) * PROGRESSIVE_BLUR_STEP) to 0f,
        (1f - (index + 2) * PROGRESSIVE_BLUR_STEP) to 1f,
        (1f - (index + 1) * PROGRESSIVE_BLUR_STEP) to 1f,
        (1f - index * PROGRESSIVE_BLUR_STEP) to 0f,
    ).clipToUnitRange()
    Brush.verticalGradient(*stops.map { (position, alpha) -> position to Color.Black.copy(alpha = alpha) }.toTypedArray())
}

/** 把越出 [0, 1] 的渐变端点裁掉，并在边界上按原斜率补一个端点。 */
private fun List<Pair<Float, Float>>.clipToUnitRange(): List<Pair<Float, Float>> {
    fun alphaAt(position: Float): Float {
        val before = lastOrNull { it.first <= position } ?: first()
        val after = firstOrNull { it.first >= position } ?: last()
        if (before.first == after.first) return after.second
        val t = (position - before.first) / (after.first - before.first)
        return before.second + (after.second - before.second) * t
    }

    val visible = filter { it.first in 0f..1f }.toMutableList()
    if (first().first > 0f) visible.add(0, 0f to alphaAt(0f))
    if (last().first < 1f) visible.add(1f to alphaAt(1f))
    return visible.sortedBy { it.first }
}
