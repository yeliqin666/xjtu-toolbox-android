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
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath

/**
 * Android 侧的真形变实现 —— 与搬迁前 :app 的 `MorphingLoader` **逐字一致**（只把默认值
 * 从函数签名挪到了 commonMain 的包装上，Kotlin 规则：actual 不允许带默认值）。
 *
 * graphics-shapes 1.1.0 只能在多边形之间形变，形变本身通过 [Morph] 完成，画到屏幕上要先
 * 转成 `android.graphics.Path`（[toPath]）再转成 Compose 的 Path（[asComposePath]）——
 * 这个库不依赖 Compose，没有直接产出 Compose Path 的 API。也正因为 `android.graphics.Path`
 * 与这个库的 Android 专属变体，实现只能待在 androidMain。
 *
 * 节奏刻意放慢：每段形变 [morphDurationMs]（默认 280ms，符合计划「不超过 300ms」的要求），
 * 中间停在当前形状上 [pauseDurationMs]，不要变成「屏幕上有个东西一直在扭」。
 */
@Composable
internal actual fun PlatformMorphingLoader(
    modifier: Modifier,
    size: Dp,
    color: Color,
    morphDurationMs: Int,
    pauseDurationMs: Int,
) {
    // 三个目标形状，各自先 normalized() 一下（包围盒对齐，减少形变时的漂移感）。
    // normalized() 之后形状落在 (0,0)→(1,1) 的单位正方形里，而不是以原点为中心。
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
        // 把单位正方形铺到画布中央、边长留一点边距
        val side = this.size.minDimension * 0.82f
        withTransform({
            translate(left = (this.size.width - side) / 2f, top = (this.size.height - side) / 2f)
            scale(scaleX = side, scaleY = side, pivot = Offset.Zero)
        }) {
            drawPath(path = path, color = color)
        }
    }
}
