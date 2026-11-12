package com.xjtu.toolbox.home

import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.settings.HomeLayoutRevision
import com.xjtu.toolbox.settings.SettingsStore
import com.xjtu.toolbox.ui.components.LocalEnterAnimations
import com.xjtu.toolbox.ui.glass.CouiTopBarScrollBehavior

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
 *
 * **切换布局只换头卡以下**（2026-11）：滚动位置提升到这里，两屏共用一份；切换那一下
 * 关掉入场动画（[LocalEnterAnimations]），顶栏、刊头与头卡不再重演一遍淡入上浮。
 */
@Composable
fun HomeScreen(
    host: HomeHost,
    modifier: Modifier = Modifier,
    scrollBehavior: CouiTopBarScrollBehavior? = null,
    /** 悬浮底栏的总占位高度：底栏浮在内容之上，页面末尾得自己留出来。 */
    extraBottomPadding: Dp = 0.dp,
    /** 页面顶边到内容的留白（状态栏，玻璃风格下还有壳的顶栏）。 */
    contentTopPadding: Dp = 0.dp,
    /** 壳把整页上移的量（见 `ShellPageInsets.contentTopShift`）；模糊带按它补回屏幕顶边。 */
    contentTopShift: () -> Dp = { 0.dp },
    /** 顶栏右上角那一排（`:app` 是搜索、通知）。null = 这一端没有顶栏。 */
    topActions: (@Composable () -> Unit)? = null,
) {
    val store = remember { SettingsStore() }
    val layout = remember(HomeLayoutRevision.current) { store.homeLayout }
    // 两套布局共用一份滚动位置：切换功能区样式时页面不跳回顶部，只有头卡以下的内容换掉。
    val scrollState = rememberScrollState()
    // 布局切过来的第一帧关掉入场动画：否则整页（含顶栏、刊头、头卡）会重演一次淡入上浮。
    var previousLayout by remember { mutableStateOf(layout) }
    val switchingLayout = layout != previousLayout
    LaunchedEffect(layout) { previousLayout = layout }
    CompositionLocalProvider(LocalEnterAnimations provides !switchingLayout) {
        if (layout == SettingsStore.HOME_LAYOUT_CLASSIC) {
            HomeClassicScreen(
                host = host,
                modifier = modifier,
                scrollBehavior = scrollBehavior,
                extraBottomPadding = extraBottomPadding,
                contentTopPadding = contentTopPadding,
                contentTopShift = contentTopShift,
                topActions = topActions,
                scrollState = scrollState,
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
                scrollState = scrollState,
            )
        }
    }
}
