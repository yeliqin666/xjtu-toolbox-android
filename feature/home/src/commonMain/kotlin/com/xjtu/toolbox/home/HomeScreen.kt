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
 * 只有两套，因为**原来那套「小组件」已经并进卡片组**——两边本来就是同一个功能的两代，
 * 合成一套之后位置模型/拖拽/分组用卡片组的，材质、一镜到底、付款码这些能力从小组件那边
 * 搬了过来（见 [HomeCardGroupScreen] 的类头）：
 *
 * - [SettingsStore.HOME_LAYOUT_CLASSIC]：经典分类卡（回落）→ [HomeClassicScreen]
 * - [SettingsStore.HOME_LAYOUT_CARDS]：卡片组 → [HomeCardGroupScreen]
 *
 * 两套屏的参数表逐字相同，所以这里只做一件事：读一次落盘的布局键，然后整屏转交。
 */
@Composable
fun HomeScreen(
    host: HomeHost,
    modifier: Modifier = Modifier,
    scrollBehavior: ScrollBehavior? = null,
    /** 悬浮底栏的总占位高度：底栏浮在内容之上，页面末尾得自己留出来。 */
    extraBottomPadding: Dp = 0.dp,
    /** 页面顶边到内容的留白（状态栏，玻璃风格下还有壳的顶栏）。 */
    contentTopPadding: Dp = 0.dp,
    /** 壳把整页上移的量（见 `ShellPageInsets.contentTopShift`）；模糊带按它补回屏幕顶边。 */
    contentTopShift: () -> Dp = { 0.dp },
    /** 顶栏右上角那一排（`:app` 是扫码、搜索、通知）。null = 这一端没有顶栏。 */
    topActions: (@Composable () -> Unit)? = null,
) {
    val store = remember { SettingsStore() }
    val layout = remember(HomeLayoutRevision.current) { store.homeLayout }
    if (layout == SettingsStore.HOME_LAYOUT_CLASSIC) {
        HomeClassicScreen(
            host = host,
            modifier = modifier,
            scrollBehavior = scrollBehavior,
            extraBottomPadding = extraBottomPadding,
            contentTopPadding = contentTopPadding,
            contentTopShift = contentTopShift,
            topActions = topActions,
        )
    } else {
        HomeCardGroupScreen(
            host = host,
            modifier = modifier,
            scrollBehavior = scrollBehavior,
            extraBottomPadding = extraBottomPadding,
            contentTopPadding = contentTopPadding,
            contentTopShift = contentTopShift,
            topActions = topActions,
        )
    }
}
