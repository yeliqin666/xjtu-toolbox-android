package com.xjtu.toolbox.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.auth.AppLoginState
import com.xjtu.toolbox.auth.LoginType
import com.xjtu.toolbox.auth.siteKey
import com.xjtu.toolbox.bulletin.Bulletin
import com.xjtu.toolbox.data.CredentialStore
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.platform.BackHandler
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

// ══════════════════════════════════════════
//  Tab 1 — 首页（`:app` 这一端：宿主面 + 那一层弹窗）
// ══════════════════════════════════════════

/**
 * 首页在 `:app` 的宿主：画法在 `:core` 的 [HomeScreen]，这里只接 [HomeHost] 并挂「子系统连接状态」弹窗（它要读 `AppLoginState`）。
 */
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
    homeTheme: String = CredentialStore.THEME_CARD,
    showQuickActions: Boolean = true,
    bulletins: List<Bulletin> = emptyList(),
    onBulletinTap: (Bulletin) -> Unit = {},
    onBulletinDismiss: (Bulletin) -> Unit = {},
    /** 玻璃顶栏的高度：内容铺到顶栏下面，这段留白放进滚动内容里。经典风格为 0。 */
    contentTopPadding: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val home = rememberAppHome(
        loginState = loginState,
        isRestoring = isRestoring,
        theme = homeTheme,
        showQuickActions = showQuickActions,
        bulletinItems = bulletins,
        onNavigate = onNavigate,
        onNavigateToProfile = onNavigateToProfile,
        onNavigateToCourses = onNavigateToCourses,
        onBulletinTap = onBulletinTap,
        onBulletinDismiss = onBulletinDismiss,
    )
    SubsystemStatusSheet(loginState, home.showStatusSheet)
    HomeScreen(
        host = home.host,
        scrollBehavior = scrollBehavior,
        extraBottomPadding = extraBottomPadding,
        contentTopPadding = contentTopPadding,
    )
}

private val STATUS_GREEN = Color(0xFF2E7D32)

/** 子系统连接明细。原来挂在首页那排小胶囊上，现在由 Hero 的状态行打开。 */
@Composable
private fun SubsystemStatusSheet(loginState: AppLoginState, show: androidx.compose.runtime.MutableState<Boolean>) {
    if (!show.value) return
    BackHandler { show.value = false }
    OverlayBottomSheet(
        show = show.value,
        title = "子系统连接状态",
        onDismissRequest = { show.value = false }
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding()
                .heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState())   // 子系统较多，弹窗内容需要可滚动。
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            LoginType.entries.forEach { t ->
                val ready = loginState.sessionManager?.getSiteOrNull(t.siteKey())?.hasLogin == true
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val statusColor = if (ready) STATUS_GREEN else MiuixTheme.colorScheme.onSurfaceVariantSummary
                    Icon(
                        if (ready) Icons.Default.CheckCircle else Icons.Default.RemoveCircleOutline,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.label, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium)
                        Text(t.description, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                    Text(
                        if (ready) "已连接" else "未登录",
                        style = MiuixTheme.textStyles.footnote1,
                        color = statusColor
                    )
                }
            }
        }
    }
}
