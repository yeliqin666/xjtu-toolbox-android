package com.xjtu.toolbox.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * 主页设置：功能区布局、卡片材质、背景与顶栏排法。设置页那一行和首页编辑态的「设置」打开的是同一个抽屉。
 * [showTopBarStyle]：只有自己画首页顶栏圆钮的端（`:app`）才有「顶栏排法」。
 */
@Composable
fun HomeSettingsSheet(show: Boolean, onDismiss: () -> Unit, showTopBarStyle: Boolean = false) {
    val store = remember { SettingsStore() }
    OverlayBottomSheet(show = show, title = "主页设置", onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            val revision = HomeLayoutRevision.current
            val layoutKeys = listOf(SettingsStore.HOME_LAYOUT_CLASSIC, SettingsStore.HOME_LAYOUT_WIDGETS)
            var homeLayout by remember(revision) { mutableStateOf(store.homeLayout) }
            OverlayDropdownPreference(
                title = "首页功能区",
                summary = if (homeLayout == SettingsStore.HOME_LAYOUT_WIDGETS) "长按编辑，拖右下角调整大小" else null,
                items = listOf("经典分类卡", "小组件"),
                selectedIndex = layoutKeys.indexOf(homeLayout).coerceAtLeast(0),
                onSelectedIndexChange = { idx ->
                    homeLayout = layoutKeys[idx]
                    store.homeLayout = homeLayout
                    HomeLayoutRevision.bump()
                },
            )
            if (homeLayout == SettingsStore.HOME_LAYOUT_WIDGETS) {
                var liquid by remember(revision) { mutableStateOf(store.homeLiquidGlass) }
                SwitchPreference(
                    title = "液态玻璃",
                    summary = "小组件带边缘折射与高光，较耗电；纯色背景下不生效",
                    checked = liquid,
                    onCheckedChange = {
                        liquid = it
                        store.homeLiquidGlass = it
                        HomeLayoutRevision.bump()
                    },
                )
            }
            HomeBackgroundPreference(store, startAction = {})
            if (showTopBarStyle) {
                var right by remember(revision) { mutableStateOf(store.homeTopActionsRight) }
                SwitchPreference(
                    title = "顶栏按钮统一靠右",
                    summary = if (right) "扫码、搜索、消息都在右上角" else "扫码在左上角，标题居中",
                    checked = right,
                    onCheckedChange = {
                        right = it
                        store.homeTopActionsRight = it
                        HomeLayoutRevision.bump()
                    },
                )
            }
        }
    }
}

/** 设置页那一行的摘要：「小组件 · 主题色渐变」。 */
internal fun homeSettingsSummary(store: SettingsStore): String = listOf(
    if (store.homeLayout == SettingsStore.HOME_LAYOUT_WIDGETS) "小组件" else "经典分类卡",
    when (store.homeBackground) { "solid" -> "主题色"; "image" -> "自定义图片"; else -> "主题色渐变" },
).joinToString(" · ")
