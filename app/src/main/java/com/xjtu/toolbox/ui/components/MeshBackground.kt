package com.xjtu.toolbox.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.MeshGradientPainter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlin.math.cos
import kotlin.math.sin

/**
 * 可复用的 Mesh 渐变背景（plan2 §10.1/§10.2）。
 *
 * 用 [androidx.compose.ui.graphics.MeshGradientPainter]（ui 1.12.0 自带，minSdk 31
 * 下走 `drawVertices`，不需要 API 34）画一张矩形顶点网格：四角和四边上的顶点固定
 * 在容器边框上，保证渐变铺满、不露出底色；只让不挨着边框的「内部顶点」随时间缓慢
 * 漂移，做出流体一样的质感。[lightVertexColors]、[darkVertexColors] 是两套独立调好
 * 的顶点颜色——深色模式不是把浅色简单调暗（那样会发浑）。
 *
 * 页面不在前台（Activity 未处于 RESUMED）时自动停住漂移；[animated] 为 false 时
 * 也保持静止，比如调用方想要一张不动的渐变，或者「界面风格：经典」不想要动画。
 *
 * @param lightVertexColors 浅色顶点颜色，按行列排列，至少 2x2（各行长度必须一致）。
 * @param darkVertexColors 深色顶点颜色，形状必须和 [lightVertexColors] 完全一致。
 * @param periodMillis 一整圈漂移的周期，默认 12 秒（不少于 10 秒，太快会像跑马灯）。
 */
@Composable
fun MeshBackground(
    modifier: Modifier = Modifier,
    lightVertexColors: List<List<Color>>,
    darkVertexColors: List<List<Color>>,
    periodMillis: Int = 12000,
    animated: Boolean = true,
) {
    val dark = com.xjtu.toolbox.ui.theme.LocalIsDarkTheme.current
    val vertexColors = if (dark) darkVertexColors else lightVertexColors
    val gridRows = vertexColors.size
    val gridColumns = vertexColors.firstOrNull()?.size ?: 0
    require(gridRows >= 2 && gridColumns >= 2 && vertexColors.all { it.size == gridColumns }) {
        "MeshBackground 需要一个至少 2x2 的矩形顶点网格，且 light/dark 两套颜色形状要一致"
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()
    val shouldAnimate = animated && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)

    val infiniteTransition = rememberInfiniteTransition(label = "meshBackground")
    // 注意这里拿的是 State 本身，不用 `by` 在组合阶段读它。
    val phaseState = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = periodMillis, easing = LinearEasing),
        ),
        label = "meshBackgroundPhase",
    )
    val animateState = rememberUpdatedState(shouldAnimate)
    val sizeState = remember { mutableStateOf(IntSize.Zero) }

    // 画笔只建一次，相位和尺寸都放到绘制阶段去读。
    //
    // MeshGradientPainter 每次 onDraw 都会重新执行一遍下面这个 block（先 configure 再 draw，
    // 已用 javap 核对 ui-android 1.12.0），所以在 block 里读 State，变化只会让这一块重绘，
    // 不会重组，也不会分配新对象。以前是用 `by` 在组合阶段读相位、按相位 remember 画笔：
    // 余额卡以屏幕刷新率一直重组、每帧新建一个画笔，停着不动也在跑，把整页滑动拖卡。
    val painter = remember(gridRows, gridColumns, vertexColors) {
        MeshGradientPainter(gridRows - 1, gridColumns - 1) {
            val size = sizeState.value
            val width = size.width.toFloat()
            val height = size.height.toFloat()
            val phase = if (animateState.value) phaseState.value else 0f
            // 内部顶点漂移的幅度：容器较短边的 12%，太大会把顶点甩出边界露底色。
            val driftAmplitude = minOf(width, height) * 0.12f
            for (row in 0 until gridRows) {
                for (col in 0 until gridColumns) {
                    val baseX = width * col / (gridColumns - 1)
                    val baseY = height * row / (gridRows - 1)
                    val isInterior = row in 1 until gridRows - 1 && col in 1 until gridColumns - 1
                    val position = if (isInterior) {
                        // 每个内部顶点错开相位，避免所有顶点同步漂移显得呆板。
                        val seed = (row * gridColumns + col).toFloat()
                        Offset(
                            baseX + driftAmplitude * cos(phase + seed),
                            baseY + driftAmplitude * sin(phase * 1.3f + seed),
                        )
                    } else {
                        Offset(baseX, baseY)
                    }
                    setVertex(row, col, position = position, color = vertexColors[row][col])
                }
            }
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { sizeState.value = it }
            .paint(painter),
    )
}
