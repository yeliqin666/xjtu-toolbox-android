package com.xjtu.toolbox.main

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xjtu.toolbox.auth.AppLoginState
import com.xjtu.toolbox.auth.AppLoginStateViewModel
import com.xjtu.toolbox.auth.LocalAppLoginState
import com.xjtu.toolbox.auth.LoginType
import com.xjtu.toolbox.auth.MfaDialogHost
import com.xjtu.toolbox.auth.ensureSite
import com.xjtu.toolbox.card.refreshCampusCardCache
import com.xjtu.toolbox.data.AppearanceSettings
import com.xjtu.toolbox.data.CredentialStore
import com.xjtu.toolbox.legal.EulaScreen
import com.xjtu.toolbox.nav.AppNavHost
import com.xjtu.toolbox.nav.AppNavigator
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.onboarding.OnboardingStore
import com.xjtu.toolbox.pay.PaymentCodeDialog
import com.xjtu.toolbox.ui.LocalIsWideLayout
import com.xjtu.toolbox.ui.calculateIsWideLayout
import com.xjtu.toolbox.ui.components.MorphingLoader
import com.xjtu.toolbox.ui.glass.LocalGlassStyle
import com.xjtu.toolbox.widget.CampusCardWidgetUpdater
import com.xjtu.toolbox.widget.NoticeWidgetUpdater
import com.xjtu.toolbox.widget.ScheduleWidgetUpdater
import com.xjtu.toolbox.nav.AwaitSite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.window.WindowDialog
import kotlin.coroutines.resume

/**
 * 应用的根：导航、启动时的登录恢复、前后台与网络变化、协议与公告。
 *
 * @param initialRoute 启动意图要打开的页面（深链、快捷方式、通知、小组件），消费后回调 [onInitialRouteConsumed]
 * @param initialTab 启动意图要切到的 tab
 * @param onReady 首帧画出后调用，SplashScreen 据此消失
 */
@Composable
fun AppRoot(
    initialRoute: AppRoute?,
    onInitialRouteConsumed: () -> Unit,
    initialTab: BottomTab?,
    onInitialTabConsumed: () -> Unit,
    onReady: () -> Unit,
) {
    val viewModel: AppLoginStateViewModel = viewModel()
    val loginState = viewModel.loginState
    val credentialStore = viewModel.credentialStore
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 类型参数必须写父类型，否则推断成 AppRoute.Main，存盘时序列化失败
    val backStack = rememberNavBackStack<AppRoute>(AppRoute.Main)
    val navigator = remember(backStack) { AppNavigator(backStack) }
    val tabs = rememberSaveable(saver = MainTabState.Saver) {
        MainTabState(BottomTab.entries.firstOrNull { it.name == credentialStore.defaultTab } ?: BottomTab.HOME)
    }
    val router = remember(navigator, tabs) {
        AppRouter(navigator, tabs, loginState, credentialStore, context.applicationContext, scope)
    }

    // ── 启动意图 ──
    LaunchedEffect(initialTab) {
        initialTab ?: return@LaunchedEffect
        router.selectTab(initialTab)
        onInitialTabConsumed()
        if (initialTab == BottomTab.COURSES) prewarmJwxt(context, loginState)
    }
    // 等登录恢复完再打开：要登录的页面在会话未就绪时会立刻退回
    var pendingLaunchRoute by remember { mutableStateOf<AppRoute?>(null) }
    LaunchedEffect(initialRoute) {
        val route = initialRoute ?: return@LaunchedEffect
        if (route == AppRoute.Schedule) router.selectTab(BottomTab.COURSES)
        if (route != AppRoute.Main) pendingLaunchRoute = route
        onInitialRouteConsumed()
    }

    val restore = rememberSessionRestore(loginState, onReady)
    LaunchedEffect(pendingLaunchRoute, restore.gateReady) {
        if (!restore.gateReady) return@LaunchedEffect
        val route = pendingLaunchRoute ?: return@LaunchedEffect
        pendingLaunchRoute = null
        router.open(route)
    }

    // 页面登录过期已自行退回（handleAuthExpired），等退场动画走完重新打开它
    LaunchedEffect(loginState.pendingRetry) {
        val route = loginState.pendingRetry ?: return@LaunchedEffect
        loginState.pendingRetry = null
        delay(200)
        router.open(route)
    }

    // 缓存一网通办姓名，下次启动秒显示
    LaunchedEffect(loginState.ywtbUserInfo) {
        val name = loginState.ywtbUserInfo?.userName
        if (name.isNullOrBlank()) return@LaunchedEffect
        loginState.cachedNickname = name
        credentialStore.saveNickname(name)
        val id = loginState.accountId
        if (id.isNotEmpty()) viewModel.accountManager.updateNickname(id, name)
    }

    CampusCardResumeRefresh(loginState)
    NetworkChangeWatcher(loginState, navigator)

    // 首次登录 / 切账号后校园网状态可能还没探过，补探一次
    LaunchedEffect(loginState.isLoggedIn, loginState.accountId) {
        if (!loginState.isLoggedIn || loginState.isOnCampus != null) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            runCatching { loginState.ensureCampusDetected() }
                .onFailure { Log.w("Campus", "login campus detect failed", it) }
        }
    }

    // 用户协议：同意前不渲染主界面
    var eulaAccepted by remember { mutableStateOf(credentialStore.isEulaAccepted()) }
    if (!eulaAccepted) {
        EulaScreen(onAccept = {
            credentialStore.acceptEula()
            eulaAccepted = true
        })
        return
    }

    // 首启直接送新用户去登录；等 gateReady 免得把老用户误判成新用户
    LaunchedEffect(restore.gateReady) {
        if (!restore.gateReady || !OnboardingStore.needsFirstRunLogin(context)) return@LaunchedEffect
        OnboardingStore.markDone(context)
        if (!loginState.hasCredentials) router.open(AppRoute.Accounts)
    }

    val notices = rememberLaunchNotices(credentialStore)
    LaunchNoticeDialogs(notices)

    // 「用 WebVPN 打开」：先探活，失效就在 App 内重登，免得网页里让用户输密码
    var webVpnJob by remember { mutableStateOf<Job?>(null) }
    val openWithWebVpn: (String) -> Unit = { url ->
        webVpnJob?.cancel()
        webVpnJob = scope.launch {
            val ok = loginState.checkWebVpnSessionAlive() || loginState.loginWebVpn()
            if (ok && loginState.webVpnClientOrNull != null) router.open(AppRoute.Browser(url))
        }
    }

    // 宽屏判断在根部算一次往下传，保证同一帧各处结论一致
    val navStyle by AppearanceSettings.get(context).navBarStyle.collectAsStateWithLifecycle()
    CompositionLocalProvider(
        LocalAppLoginState provides loginState,
        LocalIsWideLayout provides calculateIsWideLayout(),
        LocalGlassStyle provides (navStyle == CredentialStore.NAV_STYLE_FLOATING),
    ) {
        // 全应用唯一的 MFA 弹窗，放导航外层才能覆盖所有页面
        MfaDialogHost(loginState.sessionManager)

        // 别在这里套 Scaffold 给弹窗当宿主：SubcomposeLayout 会让整棵导航树被丢弃重建
        AppNavHost(
            backStack = backStack,
            router = router,
            loginState = loginState,
            credentialStore = credentialStore,
            accountManager = viewModel.accountManager,
            onOpenWithWebVpn = openWithWebVpn,
        ) {
            MainScreen(
                router = router,
                tabs = tabs,
                loginState = loginState,
                credentialStore = credentialStore,
                accountManager = viewModel.accountManager,
                isRestoring = restore.isRestoring,
                restoreStep = restore.step,
                onWarmupRequest = { restore.warmup(force = true) },
                heroBulletins = notices.heroBulletins,
                onHeroBulletinTap = notices::onHeroTap,
                onHeroBulletinDismiss = notices::dismissHero,
            )
        }

        AutoLoginDialog(router)

        if (router.showPaymentCode) {
            Dialog(
                onDismissRequest = { router.showPaymentCode = false },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                AwaitSite(loginState, "campus_card", onTimeout = { router.showPaymentCode = false }) {
                    PaymentCodeDialog(site = it) { router.showPaymentCode = false }
                }
            }
        }
    }
}

/** 「自动登录中」，独立窗口，子页面也看得见。 */
@Composable
private fun AutoLoginDialog(router: AppRouter) {
    val message = router.autoLoginMessage ?: return
    WindowDialog(
        show = true,
        title = "自动登录中",
        summary = message,
        onDismissRequest = router::cancelAutoLogin,
    ) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MorphingLoader()
            TextButton(text = "取消", onClick = router::cancelAutoLogin, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** 启动时的登录恢复状态。 */
private class SessionRestore(
    private val loginState: AppLoginState,
    private val credentialStore: CredentialStore,
    private val scope: CoroutineScope,
) {
    var isRestoring by mutableStateOf(false)
    var step by mutableStateOf("")

    /** 登录恢复已落定。 */
    var gateReady by mutableStateOf(false)

    private var lastWarmupAt = 0L

    /**
     * 后台预热：先登教务建立 CAS 会话，再对最近用过的几个站点做静默 SSO（不提交密码、撞 MFA 即停）。
     * 不要一次登全部站点，服务端会风控。
     */
    fun warmup(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastWarmupAt < 60_000L) return
        lastWarmupAt = now
        scope.launch(Dispatchers.IO) {
            try {
                runCatching { loginState.sessionManager?.ensureSite(LoginType.JWXT) }
                // 清掉已下线的站点
                val stored = credentialStore.recentSiteKeys
                val recent = stored.filter { loginState.sessionManager?.getSiteOrNull(it) != null }
                if (recent.size != stored.size) credentialStore.recentSiteKeys = recent
                if (recent.isNotEmpty()) {
                    Log.d("Warmup", "prewarm recent sites: $recent")
                    runCatching { loginState.sessionManager?.prewarmSites(recent) }
                }
                Log.d("Warmup", "Warmup done: activeSites=${loginState.sessionManager?.activeSiteKeys}")
            } catch (e: Exception) {
                Log.w("Warmup", "background login warmup failed: ${e.message}")
            }
        }
    }

    /**
     * 有凭据但还没有任何会话时，后台只登教务一个站点；其余站点进页面时按需走 SSO，
     * 冷启动不做别的主动认证。
     */
    suspend fun run() {
        if (loginState.hasCredentials && (loginState.sessionManager?.activeSiteCount ?: 0) == 0) {
            isRestoring = true
            withContext(Dispatchers.IO) {
                // 冷启动时网络回调可能不来，主动探一次校园网
                try {
                    loginState.ensureCampusDetected()
                } catch (e: Exception) {
                    Log.w("Restore", "网络探测失败", e)
                }
                try {
                    val startTime = System.currentTimeMillis()
                    step = "正在认证..."
                    val jwxt = loginState.sessionManager?.ensureSite(LoginType.JWXT)
                    Log.d("Restore", "完成 ${System.currentTimeMillis() - startTime}ms: JWXT=${jwxt != null}, campus=${loginState.isOnCampus}")
                } catch (e: Exception) {
                    Log.e("Restore", "恢复失败", e)
                }
            }
            isRestoring = false
        }
        gateReady = true
    }
}

@Composable
private fun rememberSessionRestore(loginState: AppLoginState, onReady: () -> Unit): SessionRestore {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val restore = remember { SessionRestore(loginState, CredentialStore(context), scope) }
    LaunchedEffect(Unit) {
        // 首帧真正画出后再撤 Splash，免得白屏一闪
        suspendCancellableCoroutine { cont -> view.post { cont.resume(Unit) } }
        // 升级后旧的小组件实例点击行为滞后，重建一次
        runCatching {
            ScheduleWidgetUpdater.requestUpdate(context, resetToToday = false)
            CampusCardWidgetUpdater.requestUpdate(context)
            NoticeWidgetUpdater.requestUpdate(context)
        }
        onReady()
        restore.run()
    }
    return restore
}

/** 从日程 tab 启动时提前登教务。失败交给日程页处理，异常必须吞掉，否则主线程闪退。 */
private suspend fun prewarmJwxt(context: Context, loginState: AppLoginState) {
    if (loginState.sessionManager?.getSiteOrNull("jwxt")?.hasLogin == true || !loginState.hasCredentials) return
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    val online = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    if (!online) return
    try {
        withTimeoutOrNull(10_000L) {
            withContext(Dispatchers.IO) { loginState.sessionManager?.ensureSite(LoginType.JWXT) }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("Startup", "预登录教务失败，交给日程页处理: ${e.message}")
    }
}

/**
 * 回到前台时刷新校园卡缓存（只用已有会话，一分钟最多一次）。
 * 不在这里逐站点探活：保活已由 SessionKeepAlive 和请求级重登负责，探活会占住登录锁拖慢所有页面。
 */
@Composable
private fun CampusCardResumeRefresh(loginState: AppLoginState) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val lastRefresh = remember { mutableLongStateOf(0L) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME || !loginState.isLoggedIn) return@LifecycleEventObserver
            val now = System.currentTimeMillis()
            if (now - lastRefresh.longValue < 60_000L) return@LifecycleEventObserver
            lastRefresh.longValue = now
            val cardSite = loginState.sessionManager?.getSiteOrNull("campus_card")?.takeIf { it.hasLogin }
                ?: return@LifecycleEventObserver
            scope.launch {
                try {
                    refreshCampusCardCache(context, cardSite)
                    loginState.campusCardCacheVersion++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("Lifecycle", "ON_RESUME: 校园卡缓存刷新失败: ${e.message}")
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

/**
 * 默认网络变化时（3 秒防抖）重探校园网；访问方式真变了且在要登录的页面上，就让该页重新打开。
 */
@Composable
private fun NetworkChangeWatcher(loginState: AppLoginState, navigator: AppNavigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        var job: Job? = null
        fun trigger(reason: String) {
            job?.cancel()
            job = scope.launch {
                delay(3000L)
                try {
                    Log.d("Network", "Network changed ($reason), re-evaluating access mode after 3s settle")
                    val modeChanged = withContext(Dispatchers.IO) { loginState.onNetworkChanged() }
                    val current = navigator.current
                    if (loginState.isLoggedIn && modeChanged && current.loginType != null) {
                        Log.d("Network", "Mode changed while on ${current.id} → markStaleAndRetry")
                        loginState.markStaleAndRetry(current)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("Network", "Network callback error: ${e.message}")
                }
            }
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = trigger("onAvailable")
            override fun onLost(network: Network) = trigger("onLost")
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                trigger("onCapabilitiesChanged")
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) =
                trigger("onLinkPropertiesChanged")
        }
        runCatching { cm?.registerDefaultNetworkCallback(callback) }
            .onFailure { Log.w("Network", "registerDefaultNetworkCallback failed: ${it.message}") }
        onDispose { runCatching { cm?.unregisterNetworkCallback(callback) } }
    }
}
