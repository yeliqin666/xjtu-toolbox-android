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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 整页加载用的形状形变动画（PR X，计划 §15）。
 *
 * 在圆角三角 → 圆角方形 → 圆（近似，用高边数+全圆角多边形代替，graphics-shapes 没有专门的
 * 圆工厂函数）之间循环形变，只替换 [LoadingState] 的整页转圈；行内小转圈、下拉刷新、按钮里
 * 的加载状态都不碰（那些地方 miuix 自带的 CircularProgressIndicator 已经够用，形变不适合
 * 塞进小尺寸场景）。
 *
 * graphics-shapes 1.1.0 只能在多边形之间形变，形变本身通过 [Morph] 完成，画到屏幕上要先转成
 * `android.graphics.Path`（[toPath]）再转成 Compose 的 Path（[asComposePath]）——这个库不依赖
 * Compose，没有直接产出 Compose Path 的 API。
 *
 * 节奏刻意放慢：每段形变 [morphDurationMs]（默认 280ms，符合计划"不超过 300ms"的要求），
 * 中间停在当前形状上 [pauseDurationMs]，不要变成"屏幕上有个东西一直在扭"。
 */
@Composable
fun MorphingLoader(
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    color: Color = MiuixTheme.colorScheme.primary,
    morphDurationMs: Int = 280,
    pauseDurationMs: Int = 520,
) {
    // 三个目标形状，各自先 normalized() 一下（把中心/尺度对齐，减少形变时的漂移感）。
    val shapes = remember {
        listOf(
            RoundedPolygon(numVertices = 3, rounding = CornerRounding(0.22f)),
            RoundedPolygon(numVertices = 4, rounding = CornerRounding(0.32f)),
            RoundedPolygon(numVertices = 10, rounding = CornerRounding(1f)),
        ).map { it.normalized() }
    }
    // 相邻形状两两一个 Morph：0→1、1→2、2→0（三段循环回到起点）。
    val morphs = remember(shapes) {
        shapes.indices.map { i -> Morph(shapes[i], shapes[(i + 1) % shapes.size]) }
    }
    val segmentMs = morphDurationMs + pauseDurationMs
    val totalMs = segmentMs * morphs.size
    val morphFraction = (morphDurationMs.toFloat() / segmentMs).coerceIn(0f, 1f)

    val transition = rememberInfiniteTransition(label = "morphing_loader")
    // 拿 State 本身，只在下面 Canvas 的绘制阶段读：加载期间只重画这块画布，不每帧重组
    val phaseState = transition.animateFloat(
        initialValue = 0f,
        targetValue = morphs.size.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = totalMs, easing = LinearEasing),
        ),
        label = "morphing_loader_phase",
    )

    val androidPath = remember { android.graphics.Path() }
    Canvas(modifier = modifier.size(size)) {
        val (segment, morphProgress) = morphPhaseToSegment(phaseState.value, morphs.size, morphFraction)
        androidPath.rewind()
        morphs[segment].toPath(progress = morphProgress, path = androidPath)
        val path = androidPath.asComposePath()
        // RoundedPolygon 坐标系是以原点为中心、半径约 1 的单位圆内；缩放到画布时留一点边距。
        val radiusPx = this.size.minDimension / 2f * 0.82f
        withTransform({
            translate(left = this.size.width / 2f, top = this.size.height / 2f)
            scale(scaleX = radiusPx, scaleY = radiusPx, pivot = Offset.Zero)
        }) {
            drawPath(path = path, color = color)
        }
    }
}

/**
 * 纯逻辑部分，抽出来单独测：把 `rememberInfiniteTransition` 吐出来的连续 phase（0 到
 * [segmentCount] 之间线性递增）换算成"停在哪一段""这一段里的形变进度"。
 *
 * phase 的小数部分是"段内进度"：前 [morphFraction] 那一小段做真正的形变（0→1），
 * 剩下的时间钳在 1f，也就是停在目标形状上——停顿不需要单独的状态机，全靠这个换算。
 */
internal fun morphPhaseToSegment(phase: Float, segmentCount: Int, morphFraction: Float): Pair<Int, Float> {
    val segment = phase.toInt().coerceIn(0, segmentCount - 1)
    val withinSegment = phase - segment
    val safeFraction = morphFraction.coerceAtLeast(0.0001f)
    val progress = (withinSegment / safeFraction).coerceIn(0f, 1f)
    return segment to progress
}
