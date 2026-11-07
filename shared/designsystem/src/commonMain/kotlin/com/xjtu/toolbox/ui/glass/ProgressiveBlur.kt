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
 * 顶栏的无边界渐变模糊：**一层模糊 + 一条整带高度的线性淡出遮罩**，再盖一层从顶到底淡出的表面色。
 * 只画不量：不改顶栏尺寸，内容排版和留白一概不受影响。
 *
 * 历史与取舍（2026-11，四次真机反馈）：最初是 useplanes 的 progressive blur——七层半径
 * 0.75→18dp 叠着跑，每层用一条渐变只留一段，于是「越靠顶边越糊」，代价是每帧七次全屏宽的
 * 离屏采样 + 七次高斯模糊。之后试过三种省法：①把采样层录成半分辨率再放大画回去——`RenderEffect`
 * 与 runtime shader 在**设备空间**求值，层录小并不会让特效少算，只平白多一次重采样，真机上
 * 反而爆炸；②一层 12dp + 折线遮罩，大面积大半径并不便宜；③两级阶梯（10dp / 3dp，交界处
 * 互相淡出）——开销下来了，但两段半径的交界真机上看得出硬边。
 *
 * 现在这个是**一层 + 一条纯线性淡出**：全带同一个半径，只有遮罩透明度从顶边 1 线性降到 0，
 * 半径没有交界、线性渐变也没有折点，过渡是一整条不间断的「霜 → 清」。半径与面积都最小，
 * 全分辨率、零画布缩放。
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
            effects = { blur(BandBlurRadius.toPx(), TileMode.Decal) },
            highlight = null,
            shadow = null,
            // 遮罩必须画在本层自己的采样层里：onDrawFront 在内容之后，会把标题一起裁掉
            onDrawBackdrop = { drawBackdrop ->
                drawBackdrop()
                drawRect(BandBlurMask, blendMode = BlendMode.DstIn)
            },
        ) then Modifier.drawWithCache {
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

/** 全带同一个半径，靠遮罩往下淡出来收边，所以不存在两段半径的交界。 */
private val BandBlurRadius = 8.dp

/**
 * 竖向遮罩：顶边全留、底边归零（0 = 带的顶边，1 = 底边）。
 * 只有两个 stop 的纯线性渐变——折点为零，也就不会在任何高度上冒出一条横线。
 */
private val BandBlurMask = blurMask(0f to 1f, 1f to 0f)

/** 把「位置 → alpha」的几档折成一条竖向渐变（0 = 带的顶边，1 = 底边）。 */
private fun blurMask(vararg stops: Pair<Float, Float>): Brush = Brush.verticalGradient(
    *stops.map { (position, alpha) -> position to Color.Black.copy(alpha = alpha) }.toTypedArray(),
)
