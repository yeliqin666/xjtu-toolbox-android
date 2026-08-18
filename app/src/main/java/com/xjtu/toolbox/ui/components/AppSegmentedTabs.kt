package com.xjtu.toolbox.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.TabRowDefaults
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 页面级分段选择。缩进的 `surfaceContainer` 托盘托住 [TabRowWithContour]，
 * 避免通栏白板切顶栏，也避免轨道默认 `surface` 叠在页面灰上隐形。
 *
 * 已经在白卡片内部时用 [embedded]：不再外套托盘，只给组件自带轨道一层浅底。
 */
@Composable
fun AppSegmentedTabs(
    tabs: List<String>,
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    embedded: Boolean = false,
) {
    val row: @Composable () -> Unit = {
        TabRowWithContour(
            tabs = tabs,
            selectedTabIndex = selectedTabIndex,
            onTabSelected = onTabSelected,
            colors = if (embedded) {
                TabRowDefaults.tabRowColors(
                    backgroundColor = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.06f),
                )
            } else {
                TabRowDefaults.tabRowColors()
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
    if (embedded) {
        androidx.compose.foundation.layout.Box(
            modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) { row() }
    } else {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            shape = RoundedCornerShape(14.dp),
            color = MiuixTheme.colorScheme.surfaceContainer,
        ) { row() }
    }
}
