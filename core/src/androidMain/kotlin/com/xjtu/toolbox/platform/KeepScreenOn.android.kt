package com.xjtu.toolbox.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * Android 侧真实现 —— 与搬迁前 :app `BlocksScreen` 里那段
 * `DisposableEffect(paused, over) { view.keepScreenOn = ...; onDispose { view.keepScreenOn = false } }`
 * 逐字同构：key 变化时先恢复 false 再按新值设置，离开组合时恢复 false。
 */
@Composable
actual fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}
