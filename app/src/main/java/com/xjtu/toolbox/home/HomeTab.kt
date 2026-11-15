package com.xjtu.toolbox.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.auth.AppLoginState
import com.xjtu.toolbox.bulletin.Bulletin
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.ui.glass.CouiTopBarScrollBehavior

/** 首页在 `:app` 的宿主：画法在 `:core` 的 [HomeScreen]，这里只接 [HomeHost] 并挂主页设置抽屉。 */
@Composable
internal fun HomeTab(
    loginState: AppLoginState,
    isRestoring: Boolean = false,
    /** 打开某个功能，登录拦截等由 [com.xjtu.toolbox.main.AppRouter.open] 处理。 */
    onNavigate: (AppRoute) -> Unit,
    onNavigateToProfile: () -> Unit = {},
    onNavigateToCourses: () -> Unit = {},
    scrollBehavior: CouiTopBarScrollBehavior? = null,
    /** 悬浮底栏的总占位高度（= MainScreen 的 floatingBarReserve）：底栏浮在内容之上，页面末尾得自己留出来。 */
    extraBottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
    showQuickActions: Boolean = true,
    bulletins: List<Bulletin> = emptyList(),
    onBulletinTap: (Bulletin) -> Unit = {},
    onBulletinDismiss: (Bulletin) -> Unit = {},
    /** 页面顶边到内容的留白（状态栏，玻璃风格下还有壳的顶栏）。 */
    contentTopPadding: androidx.compose.ui.unit.Dp = 0.dp,
    /** 壳把整页上移的量（见 `ShellPageInsets.contentTopShift`）；首页模糊带按它补回屏幕顶边。 */
    contentTopShift: () -> androidx.compose.ui.unit.Dp = { 0.dp },
    topActions: (@Composable () -> Unit)? = null,
    /** 顶栏中段的可展开搜索（`:app` 是 COUI 搜索组件）；null = 没有。 */
    topBarSearch: (@Composable () -> Unit)? = null,
    /** 搜索展开态与搜索结果内容层（`:app` 提供）；null = 没有。 */
    searching: Boolean = false,
    searchContent: (@Composable (topPadding: androidx.compose.ui.unit.Dp) -> Unit)? = null,
) {
    val home = rememberAppHome(
        loginState = loginState,
        isRestoring = isRestoring,
        showQuickActions = showQuickActions,
        bulletinItems = bulletins,
        onNavigate = onNavigate,
        onNavigateToProfile = onNavigateToProfile,
        onNavigateToCourses = onNavigateToCourses,
        onBulletinTap = onBulletinTap,
        onBulletinDismiss = onBulletinDismiss,
    )
    com.xjtu.toolbox.settings.HomeSettingsSheet(
        home.showSettingsSheet.value,
        onDismiss = { home.showSettingsSheet.value = false },
    )
    HomeScreen(
        host = home.host,
        scrollBehavior = scrollBehavior,
        extraBottomPadding = extraBottomPadding,
        contentTopPadding = contentTopPadding,
        contentTopShift = contentTopShift,
        topActions = topActions,
        topBarSearch = topBarSearch,
        searching = searching,
        searchContent = searchContent,
    )
}
