package com.xjtu.toolbox.ui.theme

import androidx.compose.runtime.Composable

/**
 * 平台能力：**系统栏外观**（状态栏 / 导航栏图标随主题明暗）。
 *
 * 这是「把 :app 的既有实现按能力切开」而不是新造 API 的又一处：
 * Android 侧读 `Activity.window` + `WindowCompat.getInsetsController`，与搬迁前逐字一致；
 * jvm（桌面窗口）与 wasmJs（浏览器）没有「系统栏图标明暗」这个概念，空实现即可，
 * 不需要在 UI 上做任何降级。
 */
@Composable
expect fun PlatformSystemBars(darkTheme: Boolean)
