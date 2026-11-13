package com.xjtu.toolbox.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.xjtu.toolbox.ui.adaptive.readableWidth
import com.xjtu.toolbox.ui.glass.LocalTabBottomPadding

/**
 * 页面级切换条的底部宿主。现在切换条一律放在顶栏下沿（见 [TABS_AT_BOTTOM]），这里只在开关打开时才画；
 * 画时悬浮在页面底部、由 COUI 分类胶囊自己取样。
 *
 * [glass] 是滑块时代的页面采样层，切成胶囊后不再使用，保留只为不动调用方。
 *
 * 用法：把整块正文（带 `glassSource` 的那一层）放进 [content]，切换条画在采样层外面。
 * 正文里用 `tabContentPadding` 的列表会自动在底部让出切换条的高度（同 App 底栏的做法）。
 *
 * 页面被放到底栏上当 tab 时，底下已经有一排 App 底栏，再叠一排胶囊很难看：这时这里不画，
 * 由顶栏的 `bottomContent` 里的 [DockedPageTabs] 把切换条放回顶栏下沿。
 */
@Composable
fun PageBottomTabsHost(
    tabs: List<String>,
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    glass: Backdrop?,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    content: @Composable () -> Unit,
) {
    val outer = LocalTabBottomPadding.current
    val show = TABS_AT_BOTTOM && visible && outer <= 0.dp
    Box(modifier) {
        CompositionLocalProvider(
            LocalTabBottomPadding provides if (show) outer + PageBottomTabsReserve else outer,
            content = content,
        )
        if (show) {
            AppSegmentedTabs(
                tabs = tabs,
                selectedTabIndex = selectedTabIndex,
                onTabSelected = onTabSelected,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .readableWidth(),
                backdrop = glass,
            )
        }
    }
}

/**
 * 顶栏下沿的切换条：挂进顶栏的 `bottomContent`，即一排 COUI 分类胶囊，和顶栏下沿同一套玻璃取样。
 * [TABS_AT_BOTTOM] 打开时只在页面放到 App 底栏上（底部已有一排 dock）才画，其余时候切换条在页面底部。
 */
@Composable
fun DockedPageTabs(
    tabs: List<String>,
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    visible: Boolean = true,
) {
    if (!visible || (TABS_AT_BOTTOM && LocalTabBottomPadding.current <= 0.dp)) return
    CompositionLocalProvider(com.xjtu.toolbox.ui.glass.LocalOnGlassBar provides true) {
        AppSegmentedTabs(
            tabs = tabs,
            selectedTabIndex = selectedTabIndex,
            onTabSelected = onTabSelected,
            modifier = Modifier.readableWidth(),
        )
    }
}

/** 切换条连上下边距占的高度，列表底部按它留白。 */
val PageBottomTabsReserve = 64.dp

/**
 * 页面级切换条放哪：false = 一律在顶栏下沿（现在的做法）；true = 悬浮在页面底部（放到 App 底栏上的页除外）。
 * 两种都试过，留一个开关，改主意时不用再动各页面。
 */
const val TABS_AT_BOTTOM = false
