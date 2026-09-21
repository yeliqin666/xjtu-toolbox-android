package com.xjtu.toolbox.ui.components

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import androidx.compose.ui.FrameRateCategory
import androidx.compose.ui.Modifier
import androidx.compose.ui.preferredFrameRate
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
 * 现在 Android 13+ 的 3x3 网格用 AGSL 着色器按像素算颜色：CPU 每帧只算中心顶点的一个偏移、
 * 作为 uniform 传进去，其余全在 GPU 上，满帧动画几乎没有开销。Android 12 没有 RuntimeShader
 * （以及非 3x3 的网格），退回 MeshGradientPainter 画一张**静止**的渐变。
 *
 * 两条路都给自己一个独立图层（graphicsLayer）：页面滚动时只挪位置，不重画内容。
 *
 * 页面不在前台（Activity 未处于 RESUMED）或 [animated] 为 false 时保持静止。
 *
 * @param lightVertexColors 浅色顶点颜色，按行列排列，至少 2x2（各行长度必须一致）。
 * @param darkVertexColors 深色顶点颜色，形状必须和 [lightVertexColors] 完全一致。
 * @param periodMillis 一整圈漂移的周期，默认 12 秒（不少于 10 秒，太快会像跑马灯）。
 */
private const val TWO_PI = (2 * Math.PI).toFloat()
private const val MESH_FRAME_MS = 33L

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
    // 着色器只做 3x3（现在唯一的用法就是 3x3）；别的形状走静止的 MeshGradientPainter
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && gridRows == 3 && gridColumns == 3) {
        val lifecycleOwner = LocalLifecycleOwner.current
        val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()
        val running = animated && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
        // 相位按约 30 帧/秒推进，用定时器而不是无限动画：无限动画每个 vsync 都改一次值，
        // 首页什么都不动时整页也按 120Hz 一直重画（现在还连带玻璃顶栏跟着重新采样）。
        // 一圈 12 秒的慢漂移，30 帧/秒肉眼看不出差别。
        val phaseState = remember { mutableFloatStateOf(0f) }
        if (running) {
            LaunchedEffect(periodMillis) {
                val start = System.nanoTime() - (phaseState.floatValue / TWO_PI * periodMillis * 1_000_000L).toLong()
                while (true) {
                    val elapsedMs = (System.nanoTime() - start) / 1_000_000L
                    phaseState.floatValue = (elapsedMs % periodMillis) / periodMillis.toFloat() * TWO_PI
                    delay(MESH_FRAME_MS)
                }
            }
        }
        ShaderMesh(
            modifier = modifier,
            colors = vertexColors,
            // 只在绘制阶段读相位：动画只触发这一小块图层重画，不重组
            phase = { phaseState.floatValue },
        )
    } else {
        StaticMesh(modifier, vertexColors)
    }
}

/**
 * 3x3 网格的 Mesh 渐变：先按中心顶点的漂移把坐标平滑地扭一下（扭曲量在四边为 0，
 * 所以边框上的顶点纹丝不动、四周不露底色），再在网格里做平滑的双线性插值。
 * 每个像素只取 4 个颜色，没有循环、没有 pow，全程单精度。
 *
 * 上一版用「反距离加权」：每个像素对 9 个顶点求幂，平板上 GPU 每帧多花约 6ms；
 * 顶点附近的权重用半精度存会溢出，卡面上出现亮点，中心还有一圈聚光灯似的光斑，不像 Mesh 渐变。
 * SkSL 不允许用算出来的下标取 uniform 数组，所以按所在的四个象限分支取色。
 */
private const val MESH_AGSL = """
uniform float2 size;
uniform float2 drift;
uniform half4 c00; uniform half4 c01; uniform half4 c02;
uniform half4 c10; uniform half4 c11; uniform half4 c12;
uniform half4 c20; uniform half4 c21; uniform half4 c22;

half4 main(float2 coord) {
    float2 uv = coord / size;
    // 中心鼓、四边为 0 的扭曲：中心顶点移到 0.5 + drift，边框不动
    float bump = sin(3.14159265 * uv.x) * sin(3.14159265 * uv.y);
    float2 q = clamp(uv - drift * bump, 0.0, 1.0) * 2.0;
    float2 f = fract(q);
    if (q.x >= 2.0) { f.x = 1.0; }
    if (q.y >= 2.0) { f.y = 1.0; }
    f = f * f * (3.0 - 2.0 * f);
    bool right = q.x >= 1.0;
    bool bottom = q.y >= 1.0;
    half4 a; half4 b; half4 c; half4 d;
    if (!bottom && !right) { a = c00; b = c01; c = c10; d = c11; }
    else if (!bottom) { a = c01; b = c02; c = c11; d = c12; }
    else if (!right) { a = c10; b = c11; c = c20; d = c21; }
    else { a = c11; b = c12; c = c21; d = c22; }
    return mix(mix(a, b, half(f.x)), mix(c, d, half(f.x)), half(f.y));
}
"""

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun ShaderMesh(
    modifier: Modifier,
    colors: List<List<Color>>,
    phase: () -> Float,
) {
    val shader = remember { RuntimeShader(MESH_AGSL) }
    val brush = remember(shader) { ShaderBrush(shader) }
    Box(
        modifier
            .fillMaxSize()
            // 声明这块只要 60Hz：12 秒一圈的缓慢漂移用不着 120Hz。页面上只要有东西在连续动，
            // 渲染线程每帧都得把整个窗口重新合成一遍（平板实测 GPU 每帧约 5ms）；
            // 支持自适应刷新率的屏幕会据此把整屏降到 60Hz，省下的是整屏的合成，而不只是这张卡
            .preferredFrameRate(FrameRateCategory.Normal)
            // 独立图层：页面滚动只挪这一层的位置，不触发重画
            .graphicsLayer()
            .drawWithCache {
                shader.setFloatUniform("size", size.width, size.height)
                // RuntimeShader 的颜色逐个传（颜色都不透明，不涉及预乘）
                for (row in 0..2) for (col in 0..2) {
                    val c = colors[row][col]
                    shader.setFloatUniform("c$row$col", c.red, c.green, c.blue, c.alpha)
                }
                onDrawBehind {
                    val p = phase()
                    // 中心顶点漂移：幅度是短边的 12%，横竖两个方向频率错开，走的是一条李萨如曲线而不是来回直线
                    val amp = 0.12f * minOf(size.width, size.height)
                    shader.setFloatUniform("drift", amp * cos(p) / size.width, amp * sin(p * 1.3f) / size.height)
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
