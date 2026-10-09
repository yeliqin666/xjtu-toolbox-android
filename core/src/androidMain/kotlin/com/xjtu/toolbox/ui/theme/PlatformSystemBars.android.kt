package com.xjtu.toolbox.ui.theme

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Android 侧的真实现 —— 与搬迁前 :app `Theme.kt` 里那段 SideEffect **逐字一致**：
 * 从 `LocalView` 拿到承载它的 `Activity.window`，把状态栏 / 导航栏图标切成与主题相反的明暗。
 */
@Composable
actual fun PlatformSystemBars(darkTheme: Boolean) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !darkTheme
            insetsController.isAppearanceLightNavigationBars = !darkTheme
        }
    }
}
