package com.xjtu.toolbox.main

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import com.xjtu.toolbox.auth.AppLoginState
import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.auth.LoginType
import com.xjtu.toolbox.auth.ensureSite
import com.xjtu.toolbox.auth.siteKey
import com.xjtu.toolbox.data.CredentialStore
import com.xjtu.toolbox.nav.AppNavigator
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.nav.ExpandOrigins
import com.xjtu.toolbox.nav.appRouteOf
import com.xjtu.toolbox.nav.maintenanceRoutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.SnackbarHostState

/** 主界面选中的底栏 tab，放在导航根部，跳转时可直接切。 */
@Stable
class MainTabState(initial: BottomTab) {
    var selected by mutableStateOf(initial)

    companion object {
        val Saver = Saver<MainTabState, Int>(
            save = { it.selected.ordinal },
            restore = { MainTabState(BottomTab.entries[it]) },
        )
    }
}

/**
 * 全应用唯一的跳转入口 [open]。按路由自己的声明处理：维护中提示、按需自动登录
 * （失败时可离线的页面照样打开）、没网提示；日程/屁岱切 tab，付款码开覆盖层，其余压栈。
 * 提示在主界面走 Snackbar，在子页走 Toast。
 */
@Stable
class AppRouter(
    private val navigator: AppNavigator,
    private val tabs: MainTabState,
    private val loginState: AppLoginState,
    private val credentialStore: CredentialStore,
    private val context: Context,
    private val scope: CoroutineScope,
) {
    val messages = SnackbarHostState()

    /** 正在显示的付款码覆盖层。 */
    var showPaymentCode by mutableStateOf(false)

    /** 自动登录进行中的提示文字；null = 不在登录。 */
    var autoLoginMessage by mutableStateOf<String?>(null)
        private set
    private var autoLoginJob: Job? = null

    /** 按字符串 ID 打开（深链、通知、屁岱给的建议）。认不出来的写日志、原地不动。 */
    fun open(id: String) {
        val route = appRouteOf(id)
        if (route == null) {
            Log.w(TAG, "未知路由，忽略：$id")
            return
        }
        open(route)
    }

    fun open(route: AppRoute) {
        maintenanceRoutes[route]?.let { label ->
            notify("$label 学校系统维护中，暂不可用")
            return
        }
        val type = route.loginType
        when {
            type != null -> openWithLogin(route, type)
            route.needsNetwork && !isOnline() -> notify("该功能需要联网使用，请检查网络连接")
            else -> go(route)
        }
    }

    /** 切到底栏 [tab]，顺带退回主界面。 */
    fun selectTab(tab: BottomTab) {
        tabs.selected = tab
        if (navigator.backStack.size > 1) {
            ExpandOrigins.clear() // 首页格子不在原处了，返回动画别再往那儿缩
            navigator.popUntil { it == AppRoute.Main }
        }
    }

    fun back() {
        navigator.popBackStack()
    }

    fun cancelAutoLogin() {
        autoLoginJob?.cancel()
        autoLoginJob = null
        autoLoginMessage = null
    }

    /** 真正落地：tab、覆盖层还是压栈。 */
    private fun go(route: AppRoute) {
        when (route) {
            AppRoute.Main -> selectTab(tabs.selected)
            AppRoute.Schedule -> selectTab(BottomTab.COURSES)
            AppRoute.Agent -> selectTab(BottomTab.PIDAI)
            AppRoute.PaymentCode -> showPaymentCode = true
            else -> navigator.navigate(route)
        }
    }

    private fun openWithLogin(route: AppRoute, type: LoginType) {
        runCatching { credentialStore.recordRecentSite(type.siteKey()) } // 冷启动据此预热 SSO
        if (!isOnline()) {
            if (route.offlineCapable) {
                go(route)
                notify("无网络连接，展示已缓存数据")
            } else {
                notify("该功能需要联网使用，请检查网络连接")
            }
            return
        }
        if (loginState.sessionManager?.getSiteOrNull(type.siteKey())?.hasLogin == true) {
            go(route)
            return
        }
        if (!loginState.hasCredentials) {
            notify("请先登录后使用${type.label}")
            return
        }
        cancelAutoLogin()
        autoLoginJob = scope.launch { autoLogin(route, type, retried = false) }
    }

    private suspend fun autoLogin(route: AppRoute, type: LoginType, retried: Boolean) {
        autoLoginMessage = if (retried) "正在重新登录${type.label}…" else "正在连接${type.label}…"
        try {
            val site = withTimeoutOrNull(autoLoginTimeoutMs(type)) {
                // 用户在等：豁免站点失败冷却
                loginState.sessionManager?.ensureSite(type, userInitiated = true)
            }
            autoLoginMessage = null
            when {
                site != null -> go(route)
                retried -> notify("${type.label}暂未就绪")
                route.offlineCapable -> {
                    go(route)
                    notify("${type.label}暂未连通，展示已缓存数据")
                }
                else -> notify("${type.label}连接超时，请稍后重试")
            }
        } catch (e: CancellationException) {
            autoLoginMessage = null
            throw e
        } catch (e: Exception) {
            autoLoginMessage = null
            Log.e(TAG, "ensureSite($type) failed for ${route.id}", e)
            when {
                retried -> notify("${type.label}暂未就绪")
                // 登录态失效：清站点会话再完整登一次
                e is AuthExpiredException -> {
                    Log.w(TAG, "AuthExpired for $type, retrying full SiteSession login")
                    loginState.sessionManager?.getSiteOrNull(type.siteKey())?.invalidateLogin()
                    autoLogin(route, type, retried = true)
                }
                route.offlineCapable -> {
                    go(route)
                    notify("网络不佳，展示已缓存数据")
                }
                else -> {
                    val detail = e.message?.take(40)?.takeIf { it.isNotBlank() }
                    notify(
                        when (e) {
                            is java.io.IOException -> detail ?: "网络不佳，请检查网络连接"
                            else -> detail ?: "${type.label}暂未就绪"
                        }
                    )
                }
            }
        }
    }

    private fun notify(message: String) {
        if (navigator.backStack.size <= 1) {
            scope.launch { messages.showSnackbar(message, duration = SnackbarDuration.Short) }
        } else {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        return cm.getNetworkCapabilities(network)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    private companion object {
        const val TAG = "AppRouter"

        /** 多跳 OAuth 链路（加餐券、体测、考勤）明显更慢。 */
        fun autoLoginTimeoutMs(type: LoginType): Long = when (type) {
            LoginType.COUPON, LoginType.FITNESS, LoginType.ATTENDANCE -> 180_000L
            else -> 60_000L
        }
    }
}
