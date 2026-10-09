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

/**
 * 所在页面此刻是否真的显示着。主界面切走的 tab 仍留在组合里（只是透明度为 0），
 * Activity 也还在前台，只看生命周期的话切到日程 tab 后首页的渐变照样每秒刷 30 次。
 * MainScreen 按 tab 是否选中提供这个值。
 */
val LocalPageVisible = compositionLocalOf { true }

/**
 * 可复用的 Mesh 渐变背景（plan2 §10.1/§10.2）：一张矩形顶点网格，四角和四边上的顶点固定在
 * 容器边框上，只让内部顶点随时间缓慢漂移，做出流体一样的质感。
 *
 * ## 平台切口
 *
 * Android 13+ 用 AGSL 着色器（`android.graphics.RuntimeShader`）按像素算颜色；Android 12 退回
 * `MeshGradientPainter` 画静止渐变。两者都是 **Android 专属 API**（RuntimeShader 在别的端根本
 * 不存在），所以实现整体留在 `androidMain`，commonMain 只留这个切口 + 一个降级：
 * jvm / wasm 用顶点颜色的线性渐变代替 —— 语义等价（都是一层会随主题变色的底色），只是不流动。
 *
 * 顶点网格与 light/dark 的选取、以及「至少 2x2」的校验是纯逻辑，放在这里三端共享。
 *
 * @param lightVertexColors 浅色顶点颜色，按行列排列，至少 2x2（各行长度必须一致）。
 * @param darkVertexColors 深色顶点颜色，形状必须和 [lightVertexColors] 完全一致。
 * @param periodMillis 一整圈漂移的周期，默认 12 秒（不少于 10 秒，太快会像跑马灯）。
 * @param runForMillis 每次开始显示后只流动这么久就停在当前相位；页面重新显示时再流动一轮。
 *   上面压着玻璃顶栏/底栏时要设：渐变每变一帧，玻璃都要重新取样模糊，静止的页面也一直占着 GPU。
 */
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

/**
 * 非 Android 端的降级：把顶点颜色压平成一个线性渐变。它不会流动，也没有网格细分，
 * 但颜色与明暗切换（[LocalIsDarkTheme]）仍然一致，压在上面的文字对比度不变。
 */
@Composable
internal fun FallbackMeshBackground(modifier: Modifier, vertexColors: List<List<Color>>) {
    val flat = vertexColors.flatten()
    val brush = remember(flat) {
        if (flat.size >= 2) Brush.linearGradient(flat) else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
    }
    Box(modifier.fillMaxSize().background(brush))
}
