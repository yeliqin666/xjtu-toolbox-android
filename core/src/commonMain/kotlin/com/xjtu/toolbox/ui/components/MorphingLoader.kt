package com.xjtu.toolbox.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 整页加载用的形状形变动画。 */
@Composable
fun MorphingLoader(
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    color: Color = MiuixTheme.colorScheme.primary,
    morphDurationMs: Int = 280,
    pauseDurationMs: Int = 520,
) {
    PlatformMorphingLoader(
        modifier = modifier,
        size = size,
        color = color,
        morphDurationMs = morphDurationMs,
        pauseDurationMs = pauseDurationMs,
    )
}

/** 平台切口本体：Android 走 graphics-shapes 真形变；其余端走圆环降级。 */
@Composable
internal expect fun PlatformMorphingLoader(
    modifier: Modifier,
    size: Dp,
    color: Color,
    morphDurationMs: Int,
    pauseDurationMs: Int,
)

/** 非 Android 端的降级加载指示：单色圆环旋转，节奏与形变版接近（1.1s 一圈）。 */
@Composable
internal fun FallbackMorphingLoader(
    modifier: Modifier,
    size: Dp,
    color: Color,
) {
    val transition = rememberInfiniteTransition(label = "morphing_loader_fallback")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
        ),
        label = "morphing_loader_fallback_angle",
    )
    Canvas(modifier = modifier.size(size)) {
        val strokeWidth = this.size.minDimension * 0.11f
        drawArc(
            color = color,
            startAngle = angle,
            sweepAngle = 280f,
            useCenter = false,
            topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f),
            size = Size(this.size.width - strokeWidth, this.size.height - strokeWidth),
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
        )
    }
}

/** 把连续 phase 换算成「停在哪一段」「这一段里的形变进度」。 */
internal fun morphPhaseToSegment(phase: Float, segmentCount: Int, morphFraction: Float): Pair<Int, Float> {
    val segment = phase.toInt().coerceIn(0, segmentCount - 1)
    val withinSegment = phase - segment
    val safeFraction = morphFraction.coerceAtLeast(0.0001f)
    val progress = (withinSegment / safeFraction).coerceIn(0f, 1f)
    return segment to progress
}
