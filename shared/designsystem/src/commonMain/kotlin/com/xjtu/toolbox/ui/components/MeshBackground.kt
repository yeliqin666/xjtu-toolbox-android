package com.xjtu.toolbox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.xjtu.toolbox.ui.theme.LocalIsDarkTheme

/** 所在页面此刻是否真的显示着。 */
val LocalPageVisible = compositionLocalOf { true }

/** 可复用的 Mesh 渐变背景。 */
@Composable
fun MeshBackground(
    modifier: Modifier = Modifier,
    lightVertexColors: List<List<Color>>,
    darkVertexColors: List<List<Color>>,
    periodMillis: Int = 12000,
    animated: Boolean = true,
    runForMillis: Long = Long.MAX_VALUE,
) {
    val dark = LocalIsDarkTheme.current
    val vertexColors = if (dark) darkVertexColors else lightVertexColors
    val gridRows = vertexColors.size
    val gridColumns = vertexColors.firstOrNull()?.size ?: 0
    require(gridRows >= 2 && gridColumns >= 2 && vertexColors.all { it.size == gridColumns }) {
        "MeshBackground 需要一个至少 2x2 的矩形顶点网格，且 light/dark 两套颜色形状要一致"
    }
    PlatformMeshBackground(
        modifier = modifier,
        vertexColors = vertexColors,
        periodMillis = periodMillis,
        animated = animated,
        runForMillis = runForMillis,
    )
}

/** 平台切口本体：Android 走着色器/静止网格，其余端走 [FallbackMeshBackground]。 */
@Composable
internal expect fun PlatformMeshBackground(
    modifier: Modifier,
    vertexColors: List<List<Color>>,
    periodMillis: Int,
    animated: Boolean,
    runForMillis: Long,
)

/** 非 Android 端的降级：把顶点颜色压平成一个线性渐变。 */
@Composable
internal fun FallbackMeshBackground(modifier: Modifier, vertexColors: List<List<Color>>) {
    val flat = vertexColors.flatten()
    val brush = remember(flat) {
        if (flat.size >= 2) Brush.linearGradient(flat) else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
    }
    Box(modifier.fillMaxSize().background(brush))
}
