package com.xjtu.toolbox.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp

/**
 * Material 3 WindowSizeClass 的最小版（不引 androidx.compose.material3.windowSizeClass 依赖）。
 *
 * 断点（dp）：
 * - Compact  < 600   手机竖屏
 * - Medium   600-839 手机横屏 / 折叠屏
 * - Expanded ≥ 840   平板 / 桌面 / 折叠屏展开
 *
 * 数据源是 **窗口**（`LocalWindowInfo.containerSize`）而不是
 * `LocalConfiguration.screenWidthDp`：后者在系统分屏 / 自由小窗下仍然报整屏宽度，
 * 于是小窗里的应用会按平板排版。miuix 官方示例
 * （`example/shared/.../utils/AdaptiveUtils.kt`）也是这个做法。
 */
@Immutable
enum class WindowSize { Compact, Medium, Expanded }

@Composable
fun currentWindowSize(): WindowSize {
    val widthDp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    return when {
        widthDp >= 840.dp -> WindowSize.Expanded
        widthDp >= 600.dp -> WindowSize.Medium
        else -> WindowSize.Compact
    }
}

/**
 * 是否使用宽屏布局（侧栏 + 分屏）。与 miuix 示例 `shouldShowSplitPane()` 同规则：
 * 宽 ≥ 840dp，或宽 ≥ 600dp 且接近方形/横置（高宽比 < 1.2）。
 *
 * 比旧的「`screenWidthDp >= 840`」宽松：手机横屏、折叠屏内屏也会进宽屏分支，这是有意的。
 *
 * 不要直接调它做布局判断，读 [LocalIsWideLayout]——那一份在导航根部算好后向下提供，
 * 保证同一帧内所有页面拿到同一个结论。
 */
@Composable
fun calculateIsWideLayout(): Boolean {
    val windowInfo = LocalWindowInfo.current
    return with(LocalDensity.current) {
        val widthDp = windowInfo.containerSize.width.toDp()
        val heightDp = windowInfo.containerSize.height.toDp()
        val ratio = heightDp / widthDp
        widthDp >= 840.dp || (widthDp >= 600.dp && ratio < 1.2f)
    }
}

/**
 * 当前是否走宽屏布局。由 `AppNavigation` 顶层用 [calculateIsWideLayout] 算一次后提供。
 *
 * 默认值 false：没被提供时（预览、单测）一律按手机排版，不会误判成平板。
 */
val LocalIsWideLayout = compositionLocalOf { false }

/** 语法糖：`isWideLayout()` 等价于读 [LocalIsWideLayout]。 */
@Composable
fun isWideLayout(): Boolean = LocalIsWideLayout.current
