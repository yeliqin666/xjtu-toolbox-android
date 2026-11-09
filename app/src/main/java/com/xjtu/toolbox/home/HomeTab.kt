package com.xjtu.toolbox.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.auth.AppLoginState
import com.xjtu.toolbox.bulletin.Bulletin
import com.xjtu.toolbox.nav.AppRoute
import top.yukonga.miuix.kmp.basic.ScrollBehavior

/** 首页在 `:app` 的宿主：画法在 `:core` 的 [HomeScreen]，这里只接 [HomeHost] 并挂主页设置抽屉。 */
@Composable
internal fun HomeTab(
    loginState: AppLoginState,
    isRestoring: Boolean = false,
    /** 打开某个功能，登录拦截等由 [com.xjtu.toolbox.main.AppRouter.open] 处理。 */
    onNavigate: (AppRoute) -> Unit,
    onNavigateToProfile: () -> Unit = {},
    onNavigateToCourses: () -> Unit = {},
    scrollBehavior: ScrollBehavior? = null,
    /** 悬浮底栏的总占位高度（= MainScreen 的 floatingBarReserve）：底栏浮在内容之上，页面末尾得自己留出来。 */
    extraBottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
    showQuickActions: Boolean = true,
    bulletins: List<Bulletin> = emptyList(),
    onBulletinTap: (Bulletin) -> Unit = {},
    onBulletinDismiss: (Bulletin) -> Unit = {},
    /** 玻璃顶栏的高度：内容铺到顶栏下面，这段留白放进滚动内容里。经典风格为 0。 */
    contentTopPadding: androidx.compose.ui.unit.Dp = 0.dp,
    /** 壳把整页上移的量（见 `ShellPageInsets.contentTopShift`）；首页模糊带按它补回屏幕顶边。 */
    contentTopShift: () -> androidx.compose.ui.unit.Dp = { 0.dp },
    /** 顶栏最左边那个按钮（「功能按钮统一右对齐」关掉时的扫码）。 */
    topLeading: (@Composable () -> Unit)? = null,
    topActions: (@Composable () -> Unit)? = null,
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
    // `:app` 自己画首页顶栏圆钮，所以抽屉里带「顶栏排法」那一行
    com.xjtu.toolbox.settings.HomeSettingsSheet(
        home.showSettingsSheet.value,
        onDismiss = { home.showSettingsSheet.value = false },
        showTopBarStyle = true,
    )
    HomeScreen(
        host = home.host,
        scrollBehavior = scrollBehavior,
        extraBottomPadding = extraBottomPadding,
        contentTopPadding = contentTopPadding,
        contentTopShift = contentTopShift,
        topLeading = topLeading,
        topActions = topActions,
    )
}
