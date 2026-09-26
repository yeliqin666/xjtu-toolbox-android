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
    // ViewModel 保证登录状态跨 Configuration Change 存活
    val viewModel: AppLoginStateViewModel = viewModel()
    val loginState = viewModel.loginState
    val credentialStore = viewModel.credentialStore
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 返回栈的类型参数必须显式写成父类型 AppRoute：只写 rememberNavBackStack(AppRoute.Main)
    // 会推断成 AppRoute.Main，推入别的页面以后，切到后台存状态时序列化失败（miuix-nav 文档特别提醒）。
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
    // 不在这里直接打开：付款码、校园卡等要登录的页面在会话未就绪时会立刻退回。
    // 等凭据恢复（restoreGateReady）以后再交给 router.open。
    var pendingLaunchRoute by remember { mutableStateOf<AppRoute?>(null) }
    LaunchedEffect(initialRoute) {
        val route = initialRoute ?: return@LaunchedEffect
        if (route == AppRoute.Schedule) router.selectTab(BottomTab.COURSES)
        if (route != AppRoute.Main) pendingLaunchRoute = route
        onInitialRouteConsumed()
    }

    // ── 登录恢复（Splash 只做启动，登录在主界面后台进行） ──
    val restore = rememberSessionRestore(loginState, onReady)
    LaunchedEffect(pendingLaunchRoute, restore.gateReady) {
        if (!restore.gateReady) return@LaunchedEffect
        val route = pendingLaunchRoute ?: return@LaunchedEffect
        pendingLaunchRoute = null
        router.open(route)
    }

    // 页面里抛 AuthExpiredException 时（见 handleAuthExpired）：页面已经自己退回，
    // 这里等退场动画走完再重新打开它，途中按需登录（含 MFA），对用户透明。
    LaunchedEffect(loginState.pendingRetry) {
        val route = loginState.pendingRetry ?: return@LaunchedEffect
        loginState.pendingRetry = null
        delay(200)
        router.open(route)
    }

    // 拿到一网通办的姓名就缓存下来（下次启动秒显示），并同步到当前账号的记录（多账号隔离）
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

    // 首次登录 / 切账号后 isOnCampus 可能仍是 null（启动探测被没凭据跳过，
    // 网络回调又要求已登录）。徽标空着时补探一次，有缓存则立刻返回。
    LaunchedEffect(loginState.isLoggedIn, loginState.accountId) {
        if (!loginState.isLoggedIn || loginState.isOnCampus != null) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            runCatching { loginState.ensureCampusDetected() }
                .onFailure { Log.w("Campus", "login campus detect failed", it) }
        }
    }

    // ── 用户协议（首次启动或协议更新后强制展示，同意前不渲染主界面） ──
    var eulaAccepted by remember { mutableStateOf(credentialStore.isEulaAccepted()) }
    if (!eulaAccepted) {
        EulaScreen(onAccept = {
            credentialStore.acceptEula()
            eulaAccepted = true
        })
        return
    }

    // ── 首启：直接把新用户送到登录页 ──
    // 功能介绍由首页承担——它本来就是功能总览。等 gateReady 是为了让凭据与会话状态先落定，避免误判成新用户。
    LaunchedEffect(restore.gateReady) {
        if (!restore.gateReady || !OnboardingStore.needsFirstRunLogin(context)) return@LaunchedEffect
        OnboardingStore.markDone(context)
        if (!loginState.hasCredentials) router.open(AppRoute.Accounts)
    }

    val notices = rememberLaunchNotices(credentialStore)
    LaunchNoticeDialogs(notices)

    // WebVPN 转换页「用 WebVPN 打开」：即使 vpnClient 不为 null，会话也可能在后台失效。
    // 直接打开浏览器会让 webvpn 网页提示用户输账号密码（甚至要 MFA），违反「App 内完成认证」约定，
    // 所以先探活，失效则走 loginWebVpn（含 App 内 MFA 弹窗），成功后再开浏览器。
    var webVpnJob by remember { mutableStateOf<Job?>(null) }
    val openWithWebVpn: (String) -> Unit = { url ->
        webVpnJob?.cancel()
        webVpnJob = scope.launch {
            val ok = loginState.checkWebVpnSessionAlive() || loginState.loginWebVpn()
            if (ok && loginState.webVpnClientOrNull != null) router.open(AppRoute.Browser(url))
        }
    }

    // 宽屏判断在导航根部算一次向下提供（见 ui/WindowSize.kt）：各页面若各算各的，
    // 同一帧里可能得出不一致的结论（侧栏认为宽屏、内容区认为窄屏），布局就会错位。
    // 界面风格（玻璃 / 经典）给二级页的玻璃顶栏用（ui/glass/GlassTopBar.kt）。
    val navStyle by AppearanceSettings.get(context).navBarStyle.collectAsStateWithLifecycle()
    CompositionLocalProvider(
        LocalAppLoginState provides loginState,
        LocalIsWideLayout provides calculateIsWideLayout(),
        LocalGlassStyle provides (navStyle == CredentialStore.NAV_STYLE_FLOATING),
    ) {
        // MFA 短信验证弹窗全应用只挂这一处：WindowDialog 自带窗口，不依赖页面 Scaffold，
        // 放在导航外层才能覆盖所有子页面触发的重认证，见 MfaDialogHost 注释。
        MfaDialogHost(loginState.sessionManager)

        // 注意：不要在这里套一层 Scaffold 来给 overlay 弹窗提供宿主。miuix 的 ScaffoldLayout
        // 内部是 SubcomposeLayout，套在这里等于把整棵导航树塞进一个 subcompose 槽，测量条件变化时
        // 内容会被丢弃重建，页面里 rememberCoroutineScope 拿到的 scope 随之失效（真机表现为日程页
        // ForgottenCoroutineScopeException，已验证并回退）。弹窗要写进各自页面 Scaffold 的 content 里。
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
            // 独立窗口、不限宽度，盖在当前页上面。付款码复用校园卡登录（ncard JWT 访问 /berserker-app/authCode）
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

/** 「自动登录中」。挂在导航外层的独立窗口里，从子页面发起的跳转也看得见。 */
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
            MorphingLoader() // 整页加载统一用形变加载器
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

    /** 凭据与会话状态已经落定：待打开的启动路由、首启引导都等它。 */
    var gateReady by mutableStateOf(false)

    private var lastWarmupAt = 0L

    /**
     * 后台预热登录：只做最低限度的「SSO 建立」。
     *
     * 2026-05 以前一股脑登 11 个子系统，触发 11 次 mfa/detect，服务端会风控
     * （即便 trustAgent="true" 也常被反复 MFA）。现在：
     * - 先直连教务建立 CAS TGC 共享 cookie；
     * - 此后各站点登录是**纯 SSO 免密跳转**，只预热「上次用过的几个」，串行 + 静默
     *   （撞 MFA 即退出，不弹窗不发短信）。一次密码都不提交，只覆盖用户真正会用的少数几个。
     */
    fun warmup(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastWarmupAt < 60_000L) return
        lastWarmupAt = now
        scope.launch(Dispatchers.IO) {
            try {
                runCatching { loginState.sessionManager?.ensureSite(LoginType.JWXT) }
                // 已下线的站点（如移除的课程回放）会一直占着「最近」名额，顺手清掉
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
     * 有凭据且尚未建立任何登录会话 → 后台恢复。
     *
     * 启动期只做教务一道探针（课表是用户最常用的核心功能）。教务通过 Safety Verify 后，
     * CAS 服务端会话即被标记为可信，其余子系统在用户进入对应页面时按需登录，多走 SSO，不再触发 MFA。
     * 不再预热校园卡余额、一网通办姓名——冷启动除教务外没有任何额外的主动认证。
     */
    suspend fun run() {
        // 注意：isLoggedIn 可能仅因 username 已设而为 true，但实际登录实例为 0
        if (loginState.hasCredentials && (loginState.sessionManager?.activeSiteCount ?: 0) == 0) {
            isRestoring = true
            withContext(Dispatchers.IO) {
                // 主动探测一次网络环境。isOnCampus / AccessMode 只有系统网络回调触发时才会更新——
                // 冷启动时网络早已稳定、回调不来，就会一直停在初始值，不反映实际所在的网络。
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
        // 等首帧实际绘制到屏幕后再解除 Splash（避免白屏闪烁）
        suspendCancellableCoroutine { cont -> view.post { cont.resume(Unit) } }
        // 强制刷新桌面小组件（修复升级后旧实例点击行为滞后，需要重建才能生效的问题）
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

/**
 * 从日程 tab 启动时提前把教务登上。失败了日程页自己会再登、会给出错误态，所以这里把异常吞掉：
 * 教务偶尔整体返回 404/5xx，ensureSite 抛 IOException，冲出 LaunchedEffect 就是主线程闪退
 * （4.9.6 线上崩溃「教务系统 登录失败：目标服务返回错误（HTTP 404）」就是这里）。
 */
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
 * 从后台回来时刷新校园卡缓存（只用已有会话，一分钟最多一次）。
 *
 * 不再于 ON_RESUME 逐站点 ensureLogin 探活：它与 SessionKeepAlive（10 分钟周期）和
 * executeWithReAuth（请求级自愈）三重冗余，而且每次回到前台串行 N 个网络往返、持有各站点
 * loginLock，用户此刻点进任何功能页都要排队等它——是全局加载缓慢的主因之一。
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
 * 网络变化监听：默认网络（WiFi / 蜂窝 / VPN）切换、能力变化、链路属性变化时重探校园网。
 * 不拦截每一次 HTTP——那会把探针打爆，只听 ConnectivityManager 的默认网络。
 *
 * - **3 秒防抖**：等网络真正稳定（detectCampusNetwork 自身还有一次间隔 1.5s 的二次确认）；
 * - **默认网络一变就强制重探**：不走 10 分钟缓存；未登录也更新徽标；
 * - **已登录且访问方式真变了**：清旧会话，当前页面标记失效并重新打开。
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
