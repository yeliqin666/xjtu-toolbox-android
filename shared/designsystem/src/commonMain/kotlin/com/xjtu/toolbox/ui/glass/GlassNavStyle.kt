package com.xjtu.toolbox.ui.glass

import com.xjtu.toolbox.settings.SettingsStore

/**
 * 界面风格设置（`SettingsStore.navBarStyle`）→ 共享壳的 `AppShell(glassStyle = …)`，桌面与 web 共用。
 *
 * 写成「玻璃」才是玻璃，认不出的值按经典。以下两种情况也按经典：
 * - 宽屏：壳走侧栏，玻璃底栏不会出现，传 true 只会白挂一层整窗采样；
 * - 平台画不出玻璃底栏（[couiGlassBarSupported] 为 false，即非 Android）：`GlassBottomTabs` 什么都不画，
 *   经典底栏又只在非玻璃时画，窄窗口就一格 dock 都没有了。
 */
fun glassNavStyle(navBarStyle: String, isWide: Boolean): Boolean =
    navBarStyle == SettingsStore.NAV_STYLE_FLOATING && !isWide && couiGlassBarSupported
