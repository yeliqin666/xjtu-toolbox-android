package com.xjtu.toolbox.ui.glass

import com.xjtu.toolbox.settings.SettingsStore

/**
 * 界面风格设置（`SettingsStore.navBarStyle`，键 `nav_bar_style`）→ 共享壳的 `AppShell(glassStyle = …)`。
 *
 * 三端同一个键、同一个判据（`== NAV_STYLE_FLOATING`）：写成「玻璃」才是玻璃，认不出的值按经典处理
 * （`SettingsStore.navBarStyle` 只在键缺失时兜底成玻璃，手改坏的值不猜）。`:app` 是
 * `AppRoot` 里直接 `navStyle == NAV_STYLE_FLOATING`；桌面与 web 走这里，免得两份判据漂开。
 *
 * **宽屏一律不给玻璃**：宽屏时壳的形态是侧栏（`shellForm` → `ShellForm.RAIL`），玻璃底栏根本不会出现；
 * 此时若还传 true，壳会给整个内容区挂一层 `Modifier.layerBackdrop`（每帧多录一整窗的采样层），
 * 却没有东西去采样它 —— 白付代价、视觉零变化。宽屏按经典走，与不接玻璃之前逐像素相同。
 *
 * 注意这里只管**底栏**：顶栏要玻璃还得同时有 `LocalGlassStyle` 与 `LocalAppBackdrop`，
 * 而 backdrop 只有壳内部（`:app` 的 `MainScreen` / `ShellHandles.contentBackdrop`）拿得到。
 */
fun glassNavStyle(navBarStyle: String, isWide: Boolean): Boolean =
    navBarStyle == SettingsStore.NAV_STYLE_FLOATING && !isWide
