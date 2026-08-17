package com.xjtu.toolbox.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * Material 3 WindowSizeClass 的最小版（不引 androidx.compose.material3.windowSizeClass 依赖）。
 *
 * 断点（dp）：
 * - Compact  < 600   手机竖屏
 * - Medium   600-839 手机横屏 / 折叠屏
 * - Expanded ≥ 840   平板 / 桌面 / 折叠屏展开
 *
 * 单方向（width），符合 Material 3 WindowSizeClass 简化范围；要做横屏 / 折叠屏精细适配再补 height。
 */
@Immutable
enum class WindowSize { Compact, Medium, Expanded }

@Composable
fun currentWindowSize(): WindowSize {
    val w = LocalConfiguration.current.screenWidthDp
    return when {
        w >= 840 -> WindowSize.Expanded
        w >= 600 -> WindowSize.Medium
        else -> WindowSize.Compact
    }
}