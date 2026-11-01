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

/** 主页设置：功能区布局与背景。设置页那一行和首页编辑态的「设置」打开的是同一个抽屉。 */
@Composable
fun HomeSettingsSheet(show: Boolean, onDismiss: () -> Unit) {
    val store = remember { SettingsStore() }
    OverlayBottomSheet(show = show, title = "主页设置", onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            val layoutKeys = listOf(SettingsStore.HOME_LAYOUT_CLASSIC, SettingsStore.HOME_LAYOUT_WIDGETS)
            var homeLayout by remember(HomeLayoutRevision.current) { mutableStateOf(store.homeLayout) }
            OverlayDropdownPreference(
                title = "首页功能区",
                summary = if (homeLayout == SettingsStore.HOME_LAYOUT_WIDGETS) "长按编辑，拖右下角调整大小" else null,
                items = listOf("分类卡", "小组件"),
                selectedIndex = layoutKeys.indexOf(homeLayout).coerceAtLeast(0),
                onSelectedIndexChange = { idx ->
                    homeLayout = layoutKeys[idx]
                    store.homeLayout = homeLayout
                    HomeLayoutRevision.bump()
                },
            )
            HomeBackgroundPreference(store, startAction = {})
        }
    }
}

/** 设置页那一行的摘要：「小组件 · 主题色渐变」。 */
internal fun homeSettingsSummary(store: SettingsStore): String = listOf(
    if (store.homeLayout == SettingsStore.HOME_LAYOUT_WIDGETS) "小组件" else "分类卡",
    when (store.homeBackground) { "solid" -> "主题色"; "image" -> "自定义图片"; else -> "主题色渐变" },
).joinToString(" · ")
