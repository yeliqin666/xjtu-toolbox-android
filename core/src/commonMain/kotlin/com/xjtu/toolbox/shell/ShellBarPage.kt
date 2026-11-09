package com.xjtu.toolbox.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

private enum class BarPageSlot { Bar, Page }

/**
 * 一页和它自己的顶栏：两者同在页面容器里，切页、放大、淡出时是一个整体，也不必各算一份变换。
 *
 * 玻璃风格下内容铺到顶栏下面，并按这一页见过的最高顶栏留白；顶栏折叠多少就把内容上移多少，
 * 这样折叠时内容不用逐帧重组。顶栏采样的是这一页自己的内容层。
 * 经典风格下内容排在顶栏之下。没有顶栏时 [statusTop] 就是顶部要让出的高度。
 */
@Composable
internal fun ShellBarPage(
    glass: Boolean,
    statusTop: Dp,
    bar: (@Composable (LayerBackdrop) -> Unit)?,
    page: @Composable (contentTop: Dp, contentShift: () -> Dp) -> Unit,
) {
    val density = LocalDensity.current
    val backdrop = rememberLayerBackdrop(cache = glass && bar != null)
    val heights = remember { BarHeights() }
    val pageModifier = if (glass && bar != null) Modifier.fillMaxSize().layerBackdrop(backdrop) else Modifier.fillMaxSize()
    val shift: () -> Dp = { with(density) { (heights.stable - heights.actual).toDp() } }

    SubcomposeLayout(Modifier.fillMaxSize()) { constraints ->
        val barPlaceables = if (bar != null) {
            subcompose(BarPageSlot.Bar) { bar(backdrop) }
                .map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        } else {
            emptyList()
        }
        val actual = maxOf(barPlaceables.maxOfOrNull { it.height } ?: 0, statusTop.roundToPx())
        heights.actual = actual
        if (actual > heights.stable) heights.stable = actual

        val pageTop = if (glass) actual - heights.stable else actual
        val pageHeight = (constraints.maxHeight - pageTop).coerceAtLeast(0)
        val pagePlaceables = subcompose(BarPageSlot.Page) {
            Box(pageModifier) {
                if (glass) page(with(density) { heights.stable.toDp() }, shift) else page(0.dp) { 0.dp }
            }
        }.map { it.measure(constraints.copy(minHeight = pageHeight, maxHeight = pageHeight)) }

        layout(constraints.maxWidth, constraints.maxHeight) {
            pagePlaceables.forEach { it.place(0, pageTop) }
            barPlaceables.forEach { it.place(0, 0) }
        }
    }
}

/** 顶栏实际高度（只在布局阶段读）与这一页见过的最高值（px）。 */
private class BarHeights {
    var actual by mutableIntStateOf(0)
    var stable by mutableIntStateOf(0)
}
