package com.xjtu.toolbox.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.settings.HomeLayoutRevision
import com.xjtu.toolbox.settings.SettingsStore
import top.yukonga.miuix.kmp.basic.ScrollBehavior

/**
 * 首页入口：按「主页设置 → 首页功能区」选中的那一套分派。
 *
 * 三套布局并存（需求：dev 上已经发过的那两套不能丢，本分支的卡片组是第三种）：
 *
 * - [SettingsStore.HOME_LAYOUT_CLASSIC]：经典分类卡 → [HomeLegacyScreen]
 * - [SettingsStore.HOME_LAYOUT_WIDGETS]：小组件 → [HomeLegacyScreen]（同一个屏内部的另一条分支）
 * - [SettingsStore.HOME_LAYOUT_CARDS]：卡片组 → [HomeCardGroupScreen]（本分支新写的实现）
 *
 * 两套屏的参数表逐字相同，所以这里只做一件事：读一次落盘的布局键，然后整屏转交。
 * 分派放在这里而不是塞进任何一个屏里，是因为两边都要「回到自己那套」时不必互相知道对方存在。
 */
@Composable
fun HomeScreen(
    host: HomeHost,
    modifier: Modifier = Modifier,
    scrollBehavior: ScrollBehavior? = null,
    /** 悬浮底栏的总占位高度：底栏浮在内容之上，页面末尾得自己留出来。 */
    extraBottomPadding: Dp = 0.dp,
    /** 玻璃顶栏的高度：内容铺到顶栏下面，这段留白放进滚动内容里。经典风格为 0。 */
    contentTopPadding: Dp = 0.dp,
    /** 壳把整页上移的量（见 `ShellPageInsets.contentTopShift`）；模糊带按它补回屏幕顶边。 */
    contentTopShift: () -> Dp = { 0.dp },
    /** 顶栏最左边的按钮（`:app` 是「扫一扫」）；null = 这一端没有。 */
    topLeading: (@Composable () -> Unit)? = null,
    /** 顶栏右侧那一排（`:app` 是「搜索」+「通知」）。null = 这一端没有顶栏。 */
    topActions: (@Composable () -> Unit)? = null,
) {
    val store = remember { SettingsStore() }
    val layout = remember(HomeLayoutRevision.current) { store.homeLayout }
    if (layout == SettingsStore.HOME_LAYOUT_CARDS) {
        HomeCardGroupScreen(
            host = host,
            modifier = modifier,
            scrollBehavior = scrollBehavior,
            extraBottomPadding = extraBottomPadding,
            contentTopPadding = contentTopPadding,
            contentTopShift = contentTopShift,
            topLeading = topLeading,
            topActions = topActions,
        )
    } else {
        HomeLegacyScreen(
            host = host,
            modifier = modifier,
            scrollBehavior = scrollBehavior,
            extraBottomPadding = extraBottomPadding,
            contentTopPadding = contentTopPadding,
            contentTopShift = contentTopShift,
            topLeading = topLeading,
            topActions = topActions,
        )
    }
}
