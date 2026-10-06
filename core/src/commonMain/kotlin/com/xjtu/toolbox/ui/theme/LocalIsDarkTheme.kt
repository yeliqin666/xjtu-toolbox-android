package com.xjtu.toolbox.ui.theme

import androidx.compose.runtime.compositionLocalOf

/**
 * 当前是不是深色主题。**单独一个文件**待在 commonMain，而不是跟着 `Theme.kt`：
 * 那边要用 `android.app.Activity` + `WindowCompat` 去设置状态栏（Android 专属），
 * 但这个开关本身是纯 Compose 的，共享组件（`ExpressiveSurface` 的卡片底色）要读它。
 *
 * 值仍由 :app 的 `XJTUToolBoxTheme` provide（它知道系统的深色模式），这里只放声明。
 */
val LocalIsDarkTheme = compositionLocalOf { false }
