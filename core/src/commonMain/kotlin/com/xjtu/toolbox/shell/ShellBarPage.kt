package com.xjtu.toolbox.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.ui.glass.LocalCouiPageBackdrop
import com.xjtu.toolbox.ui.glass.rememberCouiPageBackdrop

/**
 * 一页和它自己的顶栏：两者同在页面容器里，切页、放大、淡出时是一个整体，也不必各算一份变换。
 *
 * 玻璃风格下内容铺到顶栏下面，并按这一页见过的最高顶栏留白；顶栏折叠多少就把内容上移多少，
 * 这样折叠时内容不用逐帧重组。顶栏采样的是这一页自己的内容层。
 * 经典风格下内容排在顶栏之下；[ownTopBar] 的页例外，两种风格都从屏幕顶边画起（见 [ShellTab.drawsOwnTopBar]）。
 * 没有顶栏时 [statusTop] 就是顶部要让出的高度。
 *
 * 顶栏与页面在同一个组合里（不用子组合）：页面才能随 [ShellTabHost] 分段预组合，不必等测量时一次做完。
 * 留白在组合里读 [BarHeights.stable]，页面记下自己组合时用的是哪个值（[BarHeights.composed]）。
 * 量到的顶栏高与它不同（第一次量、顶栏后来变高）时，这一帧把页面整体错开差值来摆，内容位置照样对；
 * 下一帧页面按新值重组，错开归零。预估取 [seed]（壳里上一页量到的顶栏高）。
 *
 * 玻璃风格下这一页正文同时录一份 COUI 采样源（Android 才有），通过 [LocalCouiPageBackdrop]
 * 交给这一页的顶栏画渐变模糊；经典风格不录也不提供，顶栏保持实色。
 */
@Composable
internal fun ShellBarPage(
    glass: Boolean,
    ownTopBar: Boolean,
    statusTop: Dp,
    bar: (@Composable () -> Unit)?,
    seed: BarHeightSeed,
    page: @Composable (contentTop: Dp, contentShift: () -> Dp) -> Unit,
) {
    val density = LocalDensity.current
    // 这一页正文的 COUI 采样源；非 Android 平台、经典风格（顶栏实色）都不建
    val couiPageBackdrop = if (glass) rememberCouiPageBackdrop() else null
    val couiGlass = glass && bar != null
    val statusTopPx = with(density) { statusTop.roundToPx() }
    // 没有顶栏时留白就是状态栏高度，组合时就是准的；有顶栏时先按壳里记住的估
    val heights = remember { BarHeights(if (bar != null) maxOf(seed.withBar, statusTopPx) else statusTopPx) }
    val underTop = glass || ownTopBar
    val pageModifier = Modifier
        .fillMaxSize()
        .then(if (couiGlass) couiPageBackdrop?.recordInto(Modifier) ?: Modifier else Modifier)
    val shift: () -> Dp = { with(density) { (heights.stable - heights.actual).toDp() } }

    Layout(
        contents = listOf(
            {
                Box(pageModifier) {
                    if (underTop) {
                        val top = heights.stable
                        heights.composed = top
                        page(with(density) { top.toDp() }, shift)
                    } else {
                        page(0.dp) { 0.dp }
                    }
                }
            },
            {
                if (bar != null) {
                    CompositionLocalProvider(
                        LocalCouiPageBackdrop provides if (couiGlass) couiPageBackdrop else null,
                    ) {
                        bar()
                    }
                }
            },
        ),
        modifier = Modifier.fillMaxSize(),
    ) { (pageMeasurables, barMeasurables), constraints ->
        val barPlaceables = barMeasurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val actual = maxOf(barPlaceables.maxOfOrNull { it.height } ?: 0, statusTopPx)
        heights.actual = actual
        // 第一次以量到的为准（预估可能偏高也可能偏低），之后只记见过的最高
        val stable = if (heights.measured) maxOf(heights.stable, actual) else actual
        heights.stable = stable
        heights.measured = true
        if (bar != null) seed.withBar = stable

        val pageTop = if (underTop) actual - stable else actual
        val pageHeight = (constraints.maxHeight - pageTop).coerceAtLeast(0)
        val pagePlaceables = pageMeasurables.map { it.measure(constraints.copy(minHeight = pageHeight, maxHeight = pageHeight)) }

        layout(constraints.maxWidth, constraints.maxHeight) {
            // 页面这一帧还是按旧留白组合的：整体错开差值，内容落在该在的位置。
            // 在摆放阶段读：页面按新值重组时写 composed，这里随之重摆、错开归零
            val lag = if (underTop) stable - heights.composed else 0
            pagePlaceables.forEach { it.place(0, pageTop + lag) }
            barPlaceables.forEach { it.place(0, 0) }
        }
    }
}

/** 顶栏实际高度（只在布局阶段读）与这一页见过的最高值（px；页面留白在组合里读它）。 */
private class BarHeights(initialStable: Int) {
    var actual by mutableIntStateOf(0)
    var stable by mutableIntStateOf(initialStable)

    /** 页面最近一次组合时用的留白（px）：组合里写、摆放阶段读（组合里不读，不会自触发重组）。 */
    var composed by mutableIntStateOf(initialStable)
    var measured = false
}

/** 壳里上一页量到的顶栏高（px）：新页面组合时拿它估留白。各页签顶栏基本同高，估中了就不必空一帧。 */
internal class BarHeightSeed {
    var withBar = 0
}
