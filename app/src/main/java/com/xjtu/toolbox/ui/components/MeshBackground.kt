package com.xjtu.toolbox.ui.components

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.MeshGradientPainter
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlin.math.cos
import kotlin.math.sin

/**
 * 可复用的 Mesh 渐变背景（plan2 §10.1/§10.2）：一张矩形顶点网格，四角和四边上的顶点固定在
 * 容器边框上，只让内部顶点随时间缓慢漂移，做出流体一样的质感。
 *
 * ## 为什么 Android 13+ 走着色器
 *
 * 最初用 [MeshGradientPainter]：它每画一次都要在 CPU 上把整张网格细分成三角形，
 * 平板上一帧 8ms 多（120Hz 下一帧的预算只有 8.3ms，gfxinfo 实测）。漂移动画要求每帧重画，
 * 于是 UI 线程被它一直占满；页面滚动时父级重画子项，它又被重新细分一遍。
 *
 * 现在 Android 13+ 用 AGSL 着色器按像素算颜色：顶点位置每帧在 CPU 上只算 [MAX_POINTS] 个坐标、
 * 作为 uniform 传进去，其余全在 GPU 上，满帧动画几乎没有开销。Android 12 没有 RuntimeShader，
 * 退回 MeshGradientPainter 画一张**静止**的渐变。
 *
 * 两条路都给自己一个独立图层（graphicsLayer）：页面滚动时只挪位置，不重画内容。
 *
 * 页面不在前台（Activity 未处于 RESUMED）或 [animated] 为 false 时保持静止。
 *
 * @param lightVertexColors 浅色顶点颜色，按行列排列，至少 2x2（各行长度必须一致），最多 [MAX_POINTS] 个。
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
    require(gridRows * gridColumns <= MAX_POINTS) { "MeshBackground 最多支持 $MAX_POINTS 个顶点" }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val lifecycleOwner = LocalLifecycleOwner.current
        val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()
        val animateState = rememberUpdatedState(animated && lifecycleState.isAtLeast(Lifecycle.State.RESUMED))
        val phaseState = rememberInfiniteTransition(label = "meshBackground").animateFloat(
            initialValue = 0f,
            targetValue = (2 * Math.PI).toFloat(),
            animationSpec = infiniteRepeatable(tween(durationMillis = periodMillis, easing = LinearEasing)),
            label = "meshBackgroundPhase",
        )
        ShaderMesh(
            modifier = modifier,
            colors = vertexColors,
            // 只在绘制阶段读相位：动画只触发这一小块图层重画，不重组
            phase = { if (animateState.value) phaseState.value else 0f },
        )
    } else {
        StaticMesh(modifier, vertexColors)
    }
}

/** 顶点上限：4x4。着色器里的数组是定长的。 */
private const val MAX_POINTS = 16

/**
 * 按像素做「反距离加权」插值：离哪个顶点近，就更像哪个顶点的颜色。顶点几乎贴着时
 * 权重趋于无穷，颜色就等于那个顶点，边界上的顶点固定在边框上，所以四周不会露底色。
 * 指数取 3：比 2 更「团」，接近 Mesh 渐变那种一块一块柔和过渡的观感。
 */
private const val MESH_AGSL = """
uniform float2 size;
uniform int count;
uniform float2 points[16];
uniform half4 colors[16];

half4 main(float2 coord) {
    float2 uv = coord / size;
    half4 acc = half4(0.0);
    float wsum = 0.0;
    for (int i = 0; i < 16; i++) {
        if (i >= count) { break; }
        float2 d = (uv - points[i]) * float2(size.x / size.y, 1.0);
        float w = 1.0 / pow(dot(d, d) + 0.0004, 1.5);
        acc += colors[i] * half(w);
        wsum += w;
    }
    return acc / half(wsum);
}
"""

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun ShaderMesh(
    modifier: Modifier,
    colors: List<List<Color>>,
    phase: () -> Float,
) {
    val rows = colors.size
    val cols = colors.first().size
    val count = rows * cols
    val shader = remember { RuntimeShader(MESH_AGSL) }
    val brush = remember(shader) { ShaderBrush(shader) }
    // 坐标数组复用，每帧只改里面的数，不分配
    val points = remember(count) { FloatArray(MAX_POINTS * 2) }
    // RuntimeShader 没有传颜色数组的接口，拆成 RGBA 浮点数组传（颜色都不透明，不涉及预乘）
    val flatColors = remember(colors) {
        FloatArray(MAX_POINTS * 4).also { out ->
            colors.flatten().forEachIndexed { i, c ->
                out[i * 4] = c.red; out[i * 4 + 1] = c.green; out[i * 4 + 2] = c.blue; out[i * 4 + 3] = c.alpha
            }
        }
    }
    Box(
        modifier
            .fillMaxSize()
            // 独立图层：页面滚动只挪这一层的位置，不触发重画
            .graphicsLayer()
            .drawWithCache {
                shader.setFloatUniform("size", size.width, size.height)
                shader.setIntUniform("count", count)
                shader.setFloatUniform("colors", flatColors)
                onDrawBehind {
                    val p = phase()
                    for (row in 0 until rows) {
                        for (col in 0 until cols) {
                            val i = row * cols + col
                            var x = col / (cols - 1f)
                            var y = row / (rows - 1f)
                            if (row in 1 until rows - 1 && col in 1 until cols - 1) {
                                // 内部顶点漂移：幅度是短边的 12%，每个顶点错开相位，避免同步漂移显得呆板
                                val seed = i.toFloat()
                                val amp = 0.12f * minOf(size.width, size.height)
                                x += amp * cos(p + seed) / size.width
                                y += amp * sin(p * 1.3f + seed) / size.height
                            }
                            points[i * 2] = x
                            points[i * 2 + 1] = y
                        }
                    }
                    shader.setFloatUniform("points", points)
                    drawRect(brush)
                }
            },
    )
}

/**
 * Android 12：没有 RuntimeShader，画一张静止的 Mesh 渐变。
 * 顶点位置是像素坐标，尺寸在绘制时读（onSizeChanged 写、block 里读），不引起重组；
 * 独立图层保证它只在尺寸或颜色变化时才重新细分一次。
 */
@Composable
private fun StaticMesh(modifier: Modifier, colors: List<List<Color>>) {
    val rows = colors.size
    val cols = colors.first().size
    val sizeState = remember { androidx.compose.runtime.mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val painter = remember(colors) {
        MeshGradientPainter(rows - 1, cols - 1) {
            val w = sizeState.value.width.toFloat()
            val h = sizeState.value.height.toFloat()
            for (row in 0 until rows) {
                for (col in 0 until cols) {
                    setVertex(row, col, position = Offset(w * col / (cols - 1), h * row / (rows - 1)), color = colors[row][col])
                }
            }
        }
    }
    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { sizeState.value = it }
            .graphicsLayer()
            .paint(painter),
    )
}
