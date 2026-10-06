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

/**
 * 整页加载用的形状形变动画（PR X，计划 §15）。
 *
 * 在圆角三角 → 圆角方形 → 圆（近似，用高边数 + 全圆角多边形代替，graphics-shapes 没有专门的
 * 圆工厂函数）之间循环形变，只替换 `LoadingState` 的整页转圈；行内小转圈、下拉刷新、按钮里
 * 的加载状态都不碰（那些地方 miuix 自带的 CircularProgressIndicator 已经够用，形变不适合
 * 塞进小尺寸场景）。
 *
 * ## 为什么是切口（expect/actual）而不是直接实现
 *
 * 真形变靠 `androidx.graphics.shapes` 的 [Morph] 完成，而**它没有 KMP 发布**（只有 Android
 * 变体）。所以：
 *   - **Android**：实现逐字留在 `androidMain`（与搬迁前 :app 的行为完全一致）。
 *   - **jvm / wasm**：用 [FallbackMorphingLoader] 的圆环旋转降级 —— 语义等价（都在表达
 *     「正在加载」），只是没有形变。这是交接文档 §4 那类「平台切口」的又一例：切口粒度是
 *     **能力**（一个加载指示器），不是文件。
 *
 * 默认值写在**这个有函数体的包装**上，而不是 expect 上 —— expect 的默认参数是 Compose
 * 默认表达式（`MiuixTheme.colorScheme.primary` 是 @Composable 取值），把包装留在 commonMain
 * 可以少一层编译器边界。
 */
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

/**
 * 平台切口本体：Android 走 graphics-shapes 真形变；其余端走圆环降级。
 *
 * `internal`：这是实现细节，调用方只该看到上面那个带默认值的 [MorphingLoader]。
 */
@Composable
internal expect fun PlatformMorphingLoader(
    modifier: Modifier,
    size: Dp,
    color: Color,
    morphDurationMs: Int,
    pauseDurationMs: Int,
)

/**
 * 非 Android 端的降级加载指示：单色圆环旋转，节奏与形变版接近（1.1s 一圈）。
 *
 * 只用 common 的 Compose 绘图原语（`drawArc`），所以 jvm 与 wasm 可以共用这一份实现，
 * 各自的 actual 只是一行转发。
 */
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

/**
 * 纯逻辑部分，抽出来单独测：把 `rememberInfiniteTransition` 吐出来的连续 phase（0 到
 * [segmentCount] 之间线性递增）换算成「停在哪一段」「这一段里的形变进度」。
 *
 * phase 的小数部分是「段内进度」：前 [morphFraction] 那一小段做真正的形变（0→1），
 * 剩下的时间钳在 1f，也就是停在目标形状上 —— 停顿不需要单独的状态机，全靠这个换算。
 */
internal fun morphPhaseToSegment(phase: Float, segmentCount: Int, morphFraction: Float): Pair<Int, Float> {
    val segment = phase.toInt().coerceIn(0, segmentCount - 1)
    val withinSegment = phase - segment
    val safeFraction = morphFraction.coerceAtLeast(0.0001f)
    val progress = (withinSegment / safeFraction).coerceIn(0f, 1f)
    return segment to progress
}
