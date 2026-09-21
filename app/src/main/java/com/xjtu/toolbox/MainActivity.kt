package com.xjtu.toolbox

import android.content.Intent
import android.os.Bundle
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.xjtu.toolbox.nav.AppNavigator
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.nav.ExpandOrigins
import com.xjtu.toolbox.nav.expandFromOrigin
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavTransition
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import com.xjtu.toolbox.auth.*
import com.xjtu.toolbox.emptyroom.EmptyRoomScreen
import com.xjtu.toolbox.jwapp.JwappScoreScreen
import com.xjtu.toolbox.notification.NotificationScreen
import com.xjtu.toolbox.library.LibraryScreen
import com.xjtu.toolbox.judge.JudgeScreen
import com.xjtu.toolbox.score.ScoreReportScreen
import com.xjtu.toolbox.ui.theme.XJTUToolBoxTheme
import com.xjtu.toolbox.ui.settings.SettingsScreen
import com.xjtu.toolbox.bulletin.Bulletin
import com.xjtu.toolbox.bulletin.BulletinApi
import com.xjtu.toolbox.bulletin.BulletinLevel
import com.xjtu.toolbox.bulletin.BulletinRules
import com.xjtu.toolbox.bulletin.BulletinStore
import com.xjtu.toolbox.agent.AgentPendingPrompt
import com.xjtu.toolbox.agent.AgentRuntimeHooks
import com.xjtu.toolbox.util.CredentialStore
import com.xjtu.toolbox.util.DeepLinkRouter
import com.xjtu.toolbox.widget.CampusCardWidgetUpdater
import com.xjtu.toolbox.widget.ScheduleWidgetUpdater
import com.xjtu.toolbox.onboarding.OnboardingStore
import com.xjtu.toolbox.settings.FeedbackScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch


class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_LAUNCH_ROUTE = "extra_launch_route"
        const val EXTRA_LAUNCH_TAB = "extra_launch_tab"
        const val EXTRA_LAUNCH_PROMPT = "extra_launch_prompt"

        /** 版本号比较函数：v1 > v2 返回正数，v1 == v2 返回 0，v1 < v2 返回负数 */
        fun compareVersionStrings(v1: String, v2: String): Int =
            BulletinRules.compareVersions(v1, v2)
    }

    /** 标记应用是否准备好（登录恢复完成后为 true），供 SplashScreen 决定何时消失 */
    var isAppReady = false
    private val launchRouteState = mutableStateOf<String?>(null)
    private val launchTabState = mutableStateOf<String?>(null)
    private val darkModeOverrideState = mutableStateOf("system")
    private val dynamicColorState = mutableStateOf(false)
    private val deepLinkPrompt = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        splash.setKeepOnScreenCondition { !isAppReady }
        super.onCreate(savedInstanceState)
        // 深链优先于 EXTRA_LAUNCH_ROUTE；二者都未设置则交给 navController 自己的默认路由
        val deepLink = DeepLinkRouter.resolve(intent)
        val launchRoute = deepLink?.route ?: intent?.getStringExtra(EXTRA_LAUNCH_ROUTE)
        deepLinkPrompt.value = deepLink?.prompt ?: intent?.getStringExtra(EXTRA_LAUNCH_PROMPT)
        val launchTab = intent?.getStringExtra(EXTRA_LAUNCH_TAB)
        launchRouteState.value = launchRoute
        launchTabState.value = launchTab ?: if (launchRoute == Routes.SCHEDULE) BottomTab.COURSES.name else null
        enableEdgeToEdge()
        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        darkModeOverrideState.value = prefs.getString("dark_mode", "system") ?: "system"
        dynamicColorState.value = prefs.getBoolean("dynamic_color", false)
        // 屁岱形象（形状/颜色）在首帧前读入，免得底栏先闪一下默认圆再跳到用户选的样子
        com.xjtu.toolbox.agent.PidaiAppearanceHost.load(this)

        // 后台保活：循环读 KeepAlivePrefs，真正续期走 sessionRefresher。
        com.xjtu.toolbox.auth.SessionKeepAlive.start(this)
        // Agent 改深色模式时即时刷新主题（CredentialStore 写 pref 不会触发重组）
        com.xjtu.toolbox.agent.AgentRuntimeHooks.applyDarkMode = { mode ->
            darkModeOverrideState.value = mode
        }
        com.xjtu.toolbox.agent.AgentRuntimeHooks.applyDynamicColor = { enabled ->
            dynamicColorState.value = enabled
        }
        // 深链里的 prompt 一次性塞给屁岱（AgentScreen consume 后自动发，再消费即焚）
        deepLinkPrompt.value?.let {
            AgentPendingPrompt.set(it)
            deepLinkPrompt.value = null
        }
        setContent {
            XJTUToolBoxTheme(
                darkModeOverride = darkModeOverrideState.value,
                dynamicColor = dynamicColorState.value,
            ) {
                AppNavigation(
                    initialRoute = launchRouteState.value,
                    onInitialRouteConsumed = { launchRouteState.value = null },
                    initialTab = launchTabState.value,
                    onInitialTabConsumed = { launchTabState.value = null },
                    onReady = { isAppReady = true },
                    onDarkModeChanged = { mode ->
                        darkModeOverrideState.value = mode
                        prefs.edit().putString("dark_mode", mode).apply()
                    },
                    onDynamicColorChanged = { enabled ->
                        dynamicColorState.value = enabled
                        prefs.edit().putBoolean("dynamic_color", enabled).apply()
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val deepLink = DeepLinkRouter.resolve(intent)
        val launchRoute = deepLink?.route ?: intent.getStringExtra(EXTRA_LAUNCH_ROUTE)
        val launchPrompt = deepLink?.prompt ?: intent.getStringExtra(EXTRA_LAUNCH_PROMPT)
        deepLinkPrompt.value = launchPrompt
        val launchTab = intent.getStringExtra(EXTRA_LAUNCH_TAB)
        launchRouteState.value = launchRoute
        launchTabState.value = launchTab ?: if (launchRoute == Routes.SCHEDULE) BottomTab.COURSES.name else null
        launchPrompt?.let { AgentPendingPrompt.set(it) }
    }
}

// ── 路由 ──────────────────────────────────

object Routes {
    const val MAIN = "main"
    const val EMPTY_ROOM = "empty_room"
    const val NOTIFICATION = "notification"
    const val NEW_ATTENDANCE = "new_attendance"
    const val SCHEDULE = "schedule"
    const val JUDGE = "judge"
    const val JWAPP_SCORE = "jwapp_score"
    const val LIBRARY = "library"
    const val CAMPUS_CARD = "campus_card"
    const val SCORE_REPORT = "score_report"
    const val PAYMENT_CODE = "payment_code"
    const val COUPON = "coupon"
    const val TRANSCRIPT = "transcript"
    const val VENUE = "venue"
    const val LMS = "lms"

    /** 直接落到思源学堂的某门课。courseId 是 LMS 自己的课程 ID。 */
    fun lmsCourse(courseId: Int) = "lms?courseId=$courseId"
    const val JIAOCAI = "jiaocai"
    const val JIAOCAI1 = "jiaocai1"
    const val JIAOCAI1_READER = "jiaocai1_reader/{ssno}?title={title}"
    const val SCHOOL_COURSE = "school_course"
    const val SCHOOL_CALENDAR = "school_calendar"
    const val YELLOW_PAGE = "yellow_page"
    const val FITNESS = "fitness"
    const val DOWNLOAD_MANAGER = "download_manager"
    const val BROWSER = "browser?url={url}"
    const val SETTINGS = "settings"
    const val ACCOUNTS = "accounts"
    const val WEBVPN_CONVERTER = "webvpn_converter"
    const val AGENT = "agent"
    const val FEEDBACK = "feedback"
    const val FACULTY = "faculty"
    const val ICLASSFACE = "iclassface"

    // ── 小游戏 ──
    // GAMES 是合集页，各游戏自己一条路由：合集页只是最常见的入口，
    // 不该是唯一入口——全局搜索搜「五子棋」应该能直接进去，而不是先落到合集页。
    const val GAMES = "games"
    const val GAME_MERGE = "game_merge"
    const val GAME_2048 = "game_2048"
    const val GAME_GOMOKU = "game_gomoku"
    const val GAME_GO = "game_go"
    const val GAME_XIANGQI = "game_xiangqi"
    // 路由字符串沿用 #72 删掉之前的 "schedule_match"：它是服务表里的键，
    // 改掉的话老用户固定在首页的入口会对不上。
    const val MATCH = "schedule_match"

    fun browser(url: String = "") = "browser?url=${java.net.URLEncoder.encode(url, "UTF-8")}"

    fun jiaocai1Reader(ssno: String, title: String = "") =
        "jiaocai1_reader/$ssno?title=${java.net.URLEncoder.encode(title, "UTF-8")}"
}

/** shortcut / 搜索 / 深链进功能页时，对应要先登录的站点。null = 无需登录可直达。 */
fun loginTypeForRoute(route: String): LoginType? = when (route) {
    Routes.NEW_ATTENDANCE -> LoginType.NEW_ATTENDANCE
    Routes.LIBRARY -> LoginType.LIBRARY
    Routes.CAMPUS_CARD, Routes.PAYMENT_CODE -> LoginType.CAMPUS_CARD
    Routes.JWAPP_SCORE -> LoginType.JWAPP
    Routes.SCORE_REPORT, Routes.JUDGE, Routes.SCHOOL_COURSE, Routes.EMPTY_ROOM, Routes.SCHEDULE -> LoginType.JWXT
    Routes.TRANSCRIPT -> LoginType.DZPZ
    Routes.VENUE -> LoginType.VENUE
    Routes.LMS -> LoginType.LMS
    Routes.JIAOCAI, Routes.JIAOCAI1 -> LoginType.JIAOCAI
    Routes.COUPON -> LoginType.COUPON
    Routes.FITNESS -> LoginType.FITNESS
    Routes.ICLASSFACE -> LoginType.ICLASSFACE
    else -> when {
        route.startsWith("jiaocai1_reader") -> LoginType.JIAOCAI
        route.startsWith("lms?") -> LoginType.LMS
        else -> null
    }
}

// ── 维护中（学校系统）服务清单 ────────────────────────────
// 命中 → 入口处直接提示，不触发任何登录或界面跳转，保护账号免遭批量 401。
val maintenanceRoutes: Set<String> = setOf(
)
val maintenanceLabels: Map<String, String> = mapOf(
    Routes.LIBRARY to "图书馆座位预约",
    Routes.JUDGE to "本科评教",
)

// ── 底部导航项 ────────────────────────────

enum class BottomTab(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    HOME("首页", Icons.Filled.Home, Icons.Outlined.Home),
    COURSES("日程", Icons.Filled.CalendarMonth, Icons.Outlined.CalendarMonth),

    /**
     * 屁岱。它是**正经的 0 级页**，不是 push 出来的子页——底栏常驻、有自己的返回语义，
     * 和其他四个 tab 完全对等。
     *
     * 但它在底栏里不走 NavigationBarItem：渲染成一颗会动的机器人（见 PidaiNavButton），
     * 所以下面这两个 icon 其实用不上，仅为满足枚举形状。位置固定在正中，
     * 前后各两个标签——这是它区别于其他 tab 的全部理由。
     */
    PIDAI("屁岱", Icons.Default.SmartToy, Icons.Default.SmartToy),
    TOOLS("学辅", Icons.Filled.MenuBook, Icons.Outlined.MenuBook),
    PROFILE("我的", Icons.Filled.Person, Icons.Outlined.Person)
}

/** 悬浮底栏胶囊本体的最小高度，对齐 miuix FloatingNavigationBar 的 defaultMinSize。 */
internal val FLOATING_BAR_HEIGHT = 52.dp

// ── 主导航 ────────────────────────────────

@Composable
fun AppNavigation(
    initialRoute: String? = null,
    onInitialRouteConsumed: () -> Unit = {},
    initialTab: String? = null,
    onInitialTabConsumed: () -> Unit = {},
    onReady: () -> Unit = {},
    onDarkModeChanged: (String) -> Unit = {},
    onDynamicColorChanged: (Boolean) -> Unit = {},
) {
    // 返回栈的类型参数必须显式写成父类型 AppRoute：只写 rememberNavBackStack(AppRoute.Main)
    // 会推断成 AppRoute.Main，推入别的页面以后，切到后台存状态时序列化失败（miuix-nav 文档特别提醒）。
    val backStack = rememberNavBackStack<AppRoute>(AppRoute.Main)
    // 付款码原来是一个对话框目的地，盖在当前页上面；miuix-nav 没有对话框目的地，改在导航层外面显示
    var showPaymentCode by remember { mutableStateOf(false) }
    val navController = remember(backStack) { AppNavigator(backStack) { showPaymentCode = true } }
    // 首页格子 → 功能页的放大转场（PR V）。全屏时的圆角对齐屏幕的物理圆角，
    // 每种页面一份，缓存起来：转场对象每次重组都换新的话，页面的元数据也跟着变
    val screenCornerPx = with(androidx.compose.ui.platform.LocalDensity.current) { rememberNavSystemCornerRadius().toPx() }
    val expandTransitions = remember(screenCornerPx) { mutableMapOf<kotlin.reflect.KClass<out AppRoute>, NavTransition>() }
    fun expand(type: kotlin.reflect.KClass<out AppRoute>): NavTransition =
        expandTransitions.getOrPut(type) { expandFromOrigin(type, screenCornerPx) }
    // [VM] ViewModel 保证状态跨 Configuration Change 存活
    val viewModel: AppLoginStateViewModel = viewModel()
    val loginState = viewModel.loginState
    val credentialStore = viewModel.credentialStore
    val context = LocalContext.current
    val mainScope = rememberCoroutineScope()
    var pendingMainTab by remember { mutableStateOf(initialTab) }
    var pendingLaunchRoute by remember { mutableStateOf<String?>(null) }
    var homeTheme by remember { mutableStateOf(credentialStore.homeTheme) }
    var showQuickActions by remember { mutableStateOf(credentialStore.showQuickActions) }
    DisposableEffect(Unit) {
        AgentRuntimeHooks.applyHomeTheme = { v -> homeTheme = v }
        AgentRuntimeHooks.applyShowQuickActions = { v -> showQuickActions = v }
        onDispose {
            AgentRuntimeHooks.applyHomeTheme = null
            AgentRuntimeHooks.applyShowQuickActions = null
        }
    }

    // WebVPN 转换页：用户点击"用 WebVPN 打开"但 vpnClient 未就绪时，挂起此 URL，
    // 启动 loginWebVpn（必要时含 MFA），登录成功后再 navigate(browser(url))。
    val webVpnPendingBrowserUrl = remember { mutableStateOf<String?>(null) }
    val webVpnLoadingState = remember { mutableStateOf(false) }
    LaunchedEffect(webVpnPendingBrowserUrl.value) {
        val url = webVpnPendingBrowserUrl.value ?: return@LaunchedEffect
        webVpnLoadingState.value = true
        try {
            // [可靠性] 即使 vpnClient 不为 null，session 也可能在后台变 stale（cookie 过期或被服务端登出）。
            // 直接打开浏览器会让 webvpn 网页提示用户输账号密码（甚至要 MFA），违反「App 内完成认证」约定。
            // 改为先 checkWebVpnSessionAlive：失效则自动 clearVpnClient，再走 loginWebVpn（含 App 内 MFA dialog）。
            val alive = loginState.checkWebVpnSessionAlive()
            val ok = alive || loginState.loginWebVpn()
            if (ok && loginState.webVpnClientOrNull != null) {
                navController.navigate(Routes.browser(url))
            }
        } finally {
            webVpnLoadingState.value = false
            webVpnPendingBrowserUrl.value = null
        }
    }

    fun navigateToMainTab(tab: BottomTab) {
        pendingMainTab = tab.name
        // 首页马上要切到别的 tab，原来那一格不在原处了，返回动画不能再往那儿缩
        ExpandOrigins.clear()
        navController.popUntil { it == AppRoute.Main }
    }

    LaunchedEffect(initialTab) {
        if (!initialTab.isNullOrBlank()) {
            pendingMainTab = initialTab
        }
    }

    LaunchedEffect(initialRoute) {
        val route = initialRoute
        if (route.isNullOrBlank() || route == Routes.MAIN) {
            onInitialRouteConsumed()
            return@LaunchedEffect
        }

        // 不在这里直接 navigate：付款码/校园卡等目的地会在会话未就绪时立刻 pop。
        // 交给 MainScreen 等凭据恢复后再走 navigateWithLogin。
        if (route == Routes.SCHEDULE) {
            navigateToMainTab(BottomTab.COURSES)
        }
        pendingLaunchRoute = route
        onInitialRouteConsumed()
    }

    LaunchedEffect(initialTab) {
        if (initialTab != BottomTab.COURSES.name) return@LaunchedEffect
        if (loginState.sessionManager?.getSiteOrNull("jwxt")?.hasLogin == true || !loginState.hasCredentials) return@LaunchedEffect

        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        val isOnline = cm?.activeNetwork != null &&
                cm.getNetworkCapabilities(cm.activeNetwork)
                    ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        if (!isOnline) return@LaunchedEffect

        kotlinx.coroutines.withTimeoutOrNull(10_000L) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                loginState.sessionManager?.ensureSite(LoginType.JWXT)
            }
        }
    }

    // 当 YWTB 用户信息获取到时，缓存全名（下次启动秒显示）
    LaunchedEffect(loginState.ywtbUserInfo) {
        val name = loginState.ywtbUserInfo?.userName
        if (!name.isNullOrBlank()) {
            loginState.cachedNickname = name
            credentialStore.saveNickname(name)
            // 同步到当前账号的 AccountStore 记录（多账号隔离）
            val aid = loginState.accountId
            if (aid.isNotEmpty()) viewModel.accountManager.updateNickname(aid, name)
        }
    }

    // Lifecycle Observer：App 从后台恢复时 proactive 刷新即将过期的 token
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    val lifecycleScope = rememberCoroutineScope()
    val lastCampusCardResumeRefresh = remember { mutableLongStateOf(0L) }
    val lastLoginWarmupAt = remember { mutableLongStateOf(0L) }

    /**
     * 后台预热登录：只做最低限度的"SSO 建立"。
     *
     * 策略变更（2026-05）：之前一股脑登 11 个子系统，触发 11 次 mfa/detect，
     * 服务端会风控（即便 trustAgent="true" 也常被反复 MFA）。
     * 现改为：
     * - JWXT：直连建立 CAS TGC 共享 cookie，让用户进入首页即可看到日程
     * - 其余子系统（JWAPP/YWTB/LMS/...）改为用户进入对应 Screen 时由
     *   navigateWithLogin 按需触发，省去启动时的 11 次同时登录冲击。
     */
    fun startBackgroundLoginWarmup(
        scope: kotlinx.coroutines.CoroutineScope,
        force: Boolean = false
    ) {
        val now = System.currentTimeMillis()
        if (!force && now - lastLoginWarmupAt.longValue < 60_000L) return
        lastLoginWarmupAt.longValue = now

        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                android.util.Log.d("Warmup", "ensureSite(JWXT) direct to establish SSO")
                runCatching { loginState.sessionManager?.ensureSite(LoginType.JWXT) }

                // Phase 2：TGC 已由上面这次登录建立，此后各站点登录是**纯 SSO 免密跳转**。
                // 只预热「上次用过的几个」，串行 + 静默（撞 MFA 即退出，不弹窗不发短信）。
                // 与 2026-05 那次被风控的做法的区别：那次是一股脑 11 个站点各自提交密码
                //（11 次 mfa/detect）；这里一次密码都不提交，且只覆盖用户真正会用的少数几个。
                // 已下线的站点（如移除的 class 课程回放）会一直占着「最近」名额，顺手清掉。
                val stored = credentialStore.recentSiteKeys
                val recent = stored.filter { loginState.sessionManager?.getSiteOrNull(it) != null }
                if (recent.size != stored.size) credentialStore.recentSiteKeys = recent
                if (recent.isNotEmpty()) {
                    android.util.Log.d("Warmup", "prewarm recent sites: $recent")
                    runCatching { loginState.sessionManager?.prewarmSites(recent) }
                }
                android.util.Log.d("Warmup", "Warmup done: activeSites=${loginState.sessionManager?.activeSiteKeys}")
            } catch (e: Exception) {
                android.util.Log.w("Warmup", "background login warmup failed: ${e.message}")
            }
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && loginState.isLoggedIn) {
                val now = System.currentTimeMillis()
                // [policy] 不再于 ON_RESUME 逐站点 ensureLogin 探活：
                // 该轮询与 SessionKeepAlive（10 分钟周期）和 executeWithReAuth（请求级自愈）
                // 三重冗余，且每次 resume 串行 N 个网络往返、持有各站点 loginLock，
                // 用户此刻点进任何功能页都要排队等它 —— 是全局加载缓慢的主因之一。
                val shouldRefreshCampusCard = now - lastCampusCardResumeRefresh.longValue >= 60_000L
                if (shouldRefreshCampusCard) lastCampusCardResumeRefresh.longValue = now
                if (!shouldRefreshCampusCard) return@LifecycleEventObserver
                lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        if (shouldRefreshCampusCard) {
                            loginState.sessionManager?.getSiteOrNull("campus_card")?.takeIf { it.hasLogin }?.let { cardSite ->
                                android.util.Log.d("Lifecycle", "ON_RESUME: refreshing campus card cache (cached session only)")
                                try {
                                    refreshCampusCardCache(context, cardSite)
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        loginState.campusCardCacheVersion++
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.w("Lifecycle", "ON_RESUME: 校园卡缓存刷新失败: ${e.message}")
                                }
                            }
                        }
                        // [policy] ON_RESUME 不再触发 startBackgroundLoginWarmup，
                        // 仅维持已存活 session（上方 reAuthenticate 心跳），不主动登录新子系统。
                    } catch (e: Exception) {
                        android.util.Log.w("Lifecycle", "ON_RESUME: token refresh failed: ${e.message}")
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 网络变化监听：默认网络（WiFi / 蜂窝 / VPN）切换、能力变化、链路属性变化时重探。
    // 不是拦截每一次 HTTP——那会把探针打爆。只听 ConnectivityManager 的默认网络。
    //
    // - **3 秒防抖**：等网络真正稳定
    // - **二次确认**：detectCampusNetwork 自身已做二次确认（间隔 1.5s）
    // - **默认网络一变就强制重探**：不走 10 分钟缓存；未登录也更新徽标
    // - **已登录且 mode 真变**：清旧会话，当前业务页 markStaleAndRetry
    val networkScope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        val connectivityManager = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        var networkCheckJob: kotlinx.coroutines.Job? = null
        val routeToLoginType: (String) -> LoginType? = { loginTypeForRoute(it) }
        fun trigger(reason: String) {
            networkCheckJob?.cancel()
            networkCheckJob = networkScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                kotlinx.coroutines.delay(3000L)
                try {
                    android.util.Log.d("Network", "Network changed ($reason), re-evaluating access mode after 3s settle")
                    // 系统默认网络变了就必须重探，不能走 10 分钟缓存。
                    val modeChanged = loginState.onNetworkChanged()
                    if (loginState.isLoggedIn && modeChanged) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            val currentRoute = navController.currentId
                            val activeType = currentRoute?.let(routeToLoginType)
                            if (activeType != null && currentRoute != null) {
                                android.util.Log.d("Network", "Mode changed while on $currentRoute → markStaleAndRetry($activeType)")
                                loginState.markStaleAndRetry(activeType, currentRoute)
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("Network", "Network callback error: ${e.message}")
                }
            }
        }
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) { trigger("onAvailable") }
            override fun onLost(network: android.net.Network) { trigger("onLost") }
            override fun onCapabilitiesChanged(
                network: android.net.Network,
                caps: android.net.NetworkCapabilities,
            ) {
                trigger("onCapabilitiesChanged")
            }
            override fun onLinkPropertiesChanged(
                network: android.net.Network,
                properties: android.net.LinkProperties,
            ) {
                trigger("onLinkPropertiesChanged")
            }
        }
        // 默认网络：用户实际在用的那条（切 WiFi/蜂窝/VPN 都会到）
        runCatching { connectivityManager?.registerDefaultNetworkCallback(callback) }
            .onFailure { android.util.Log.w("Network", "registerDefaultNetworkCallback failed: ${it.message}") }
        onDispose {
            try { connectivityManager?.unregisterNetworkCallback(callback) } catch (_: Exception) {}
        }
    }

    // 恢复凭据并自动初始化（Splash 只做启动，登录在主界面后台进行）
    var isRestoring by remember { mutableStateOf(false) }
    var restoreGateReady by remember { mutableStateOf(false) }
    var restoreStep by remember { mutableStateOf("") }
    val restoreScope = rememberCoroutineScope()
    val view = LocalView.current
    LaunchedEffect(Unit) {
        // 等待首帧实际绘制到屏幕后再解除 Splash（避免白屏闪烁）
        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
            view.post { cont.resume(Unit, null) }
        }

        // 强制刷新桌面小组件（修复升级后旧实例点击行为滞后，需要重建才能生效的问题）
        runCatching {
            ScheduleWidgetUpdater.requestUpdate(context, resetToToday = false)
            CampusCardWidgetUpdater.requestUpdate(context)
            com.xjtu.toolbox.widget.NoticeWidgetUpdater.requestUpdate(context)
        }

        onReady()

        // 有凭据且尚未建立任何登录会话 → 启动后台恢复
        // 注意：isLoggedIn 可能仅因 username 已设而为 true，但实际登录实例为 0
        if (loginState.hasCredentials && (loginState.sessionManager?.activeSiteCount ?: 0) == 0) {
            isRestoring = true
            // Phase 0: 主动探测一次网络环境。[isOnCampus]/[AccessMode] 只有系统的
            // ConnectivityManager.NetworkCallback（onAvailable/onLost/onCapabilitiesChanged）
            // 触发时才会更新——若冷启动时网络连接早已稳定、没有 fire 这些回调，
            // isOnCampus 会一直停在初始值 null、AccessMode 停在硬编码默认值 NORMAL，
            // 完全不反映用户实际所在的网络环境。必须在这里主动探测一次兜底。
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    loginState.ensureCampusDetected()
                } catch (e: Exception) {
                    android.util.Log.w("Restore", "Phase0 网络探测失败", e)
                }
            }
            // Phase 1: 直连恢复 JWXT（串行，避免竞争）
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val startTime = System.currentTimeMillis()
                    android.util.Log.d("Restore", "Phase1 开始...")

                    // [policy] 启动期只做 JWXT 一道探针（课表是用户最常用的核心功能）。
                    // 一旦 JWXT 通过 Safety Verify，CAS server-side session 标记可信，
                    // 其余子系统（ncard/lms/dzpz/...）后续 lazy 登录可直接 SSO 复用，不再触发 MFA。
                    // 其余子系统**不在启动期预登**，由用户进入对应 Screen 时按需触发 autoLogin。
                    restoreStep = "正在认证..."
                    val jwxt = loginState.sessionManager?.ensureSite(LoginType.JWXT)
                    android.util.Log.d("Restore", "Phase1 完成 ${System.currentTimeMillis() - startTime}ms: JWXT=${jwxt != null}, campus=${loginState.isOnCampus}")
                } catch (e: Exception) {
                    android.util.Log.e("Restore", "Phase1 恢复失败", e)
                }
            }
            isRestoring = false  // Phase1 完成，立即隐藏 banner

            // [policy] 启动期 Phase2 已全部移除：JWXT 已在 Phase1 完成，其余子系统不预登：
            // - 不再预热 CAMPUS_CARD 刷余额（用户进校园卡页面再登）；
            // - 不再预热 YWTB 拉 userInfo（YWTB userInfo 由用户进入「我的」时按需加载）。
            // 这样冷启动除 JWXT 核心探针外，无任何额外主动认证 → 不会触发额外 MFA。
            // 其余系统的 ensureLogin 由各 Screen 的 LaunchedEffect 按需触发，
            // 因 JWXT Safety Verify 后 CAS session 已可信，后续 OAuth 授权多走 SSO 不再 MFA。
        }
        restoreGateReady = true
    }

    // 首次登录 / 切账号后 isOnCampus 可能仍是 null（启动探测被没凭据跳过，
    // 网络回调又要求已登录）。徽标空着时补探一次，有缓存则立刻返回。
    LaunchedEffect(loginState.isLoggedIn, loginState.accountId) {
        if (!loginState.isLoggedIn) return@LaunchedEffect
        if (loginState.isOnCampus != null) return@LaunchedEffect
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { loginState.ensureCampusDetected() }
                .onFailure { android.util.Log.w("Campus", "login campus detect failed", it) }
        }
    }

    // ── 用户协议弹窗（首次启动或未签署时强制展示） ──
    var eulaAccepted by remember { mutableStateOf(credentialStore.isEulaAccepted()) }
    if (!eulaAccepted) {
        EulaScreen(onAccept = {
            credentialStore.acceptEula()
            eulaAccepted = true
        })
        return  // 未同意协议前阻止渲染主界面
    }

    // ── 首启引导：直接把新用户送到登录页 ──
    // 4.6 前这里是三屏轮播（功能介绍 / 隐私声明 / 去登录）。砍掉的理由：
    // 前两屏在刚签完 EULA 之后重复且无动作，唯一的真实动作就是最后一屏的「去登录」；
    // 功能介绍由首页承担——它本来就是功能总览。
    // 等 restoreGateReady 是为了让凭据与会话状态先落定，避免误判成新用户。
    LaunchedEffect(restoreGateReady) {
        if (!restoreGateReady) return@LaunchedEffect
        if (!OnboardingStore.needsFirstRunLogin(context)) return@LaunchedEffect
        OnboardingStore.markDone(context)
        if (!loginState.hasCredentials) {
            navController.navigate(Routes.ACCOUNTS)
        }
    }

    val previousRunVersion = remember { credentialStore.lastRunVersion }
    LaunchedEffect(Unit) {
        if (credentialStore.lastRunVersion != BuildConfig.VERSION_NAME) {
            credentialStore.lastRunVersion = BuildConfig.VERSION_NAME
        }
    }

    // ── 本地 What's New 弹窗：堆叠展示自上次已见之后的全部新版本 ──
    val pendingChangelog = remember(previousRunVersion) {
        val baseline = credentialStore.lastSeenChangelogVersion ?: previousRunVersion
        com.xjtu.toolbox.util.AppChangelog.since(baseline)
    }
    // 全新安装没有「上一版」可言，给第一次打开的人看更新公告是噪音。
    // 这里不写 lastSeenChangelogVersion，所以下次真正升级时照常提示。
    val isFreshInstall = previousRunVersion == null
    val showUpdateNotice = remember {
        mutableStateOf(pendingChangelog.isNotEmpty() && !isFreshInstall)
    }

    // ── 启动必检：公告 + 更新（不再看「启动时检查更新」开关）──
    val bulletinStore = remember { BulletinStore(context) }
    var heroBulletins by remember { mutableStateOf<List<Bulletin>>(emptyList()) }
    var pendingUpdate by remember { mutableStateOf<com.xjtu.toolbox.util.AppUpdateInfo?>(null) }
    var launchDialogBulletin by remember { mutableStateOf<Bulletin?>(null) }
    var bulletinCheckFinished by remember { mutableStateOf(false) }
    val showBulletinDialog = remember { mutableStateOf(false) }
    val autoUpdateCheckDone = remember { mutableStateOf(false) }
    var autoUpdateVersion by remember { mutableStateOf("") }
    var autoUpdateBody by remember { mutableStateOf("") }
    var autoUpdateDownloadUrl by remember { mutableStateOf("") }
    var autoUpdateReleaseUrl by remember { mutableStateOf("") }
    var autoUpdateChannelKey by remember { mutableStateOf("") }
    var autoUpdateChannel by remember { mutableStateOf("") }
    var autoUpdateIsPreview by remember { mutableStateOf(false) }
    val showAutoUpdateDialog = remember { mutableStateOf(false) }

    fun applyHeroBulletin(
        remote: List<Bulletin>,
        update: com.xjtu.toolbox.util.AppUpdateInfo?,
    ) {
        val synthetic = update?.let { info ->
            val syn = BulletinRules.syntheticUpdate(info.version, info.channel)
            if (credentialStore.isUpdateNoticeSeen(syn.id)) null else syn
        }
        heroBulletins = BulletinRules.visible(
            items = remote + listOfNotNull(synthetic),
            now = java.time.Instant.now(),
            currentVersion = BuildConfig.VERSION_NAME,
            currentVersionCode = BuildConfig.VERSION_CODE,
            dismissedIds = bulletinStore.dismissedIds,
            ackedIds = bulletinStore.ackedIds,
            snoozedIds = BulletinStore.sessionSnoozedIds(),
        )
    }

    fun presentUpdate(update: com.xjtu.toolbox.util.AppUpdateInfo) {
        pendingUpdate = update
        autoUpdateVersion = update.version
        autoUpdateBody = update.notes
        autoUpdateDownloadUrl = update.downloadUrl
        autoUpdateReleaseUrl = update.releaseUrl
        autoUpdateChannelKey = update.channel
        autoUpdateChannel = update.channelLabel
        autoUpdateIsPreview = update.isPreview
        showAutoUpdateDialog.value = true
    }

    fun openPendingUpdate(fallbackUrl: String? = null) {
        val ready = pendingUpdate
        if (ready != null) {
            presentUpdate(ready)
            return
        }
        mainScope.launch {
            val result = runCatching {
                // 强制更新是公告指定要升到正式版，不能把开了预览开关的人引到预览版上。
                com.xjtu.toolbox.util.AppUpdater.fetchLatest(credentialStore.updateChannel)
            }
            result.fold(
                onSuccess = { update ->
                    presentUpdate(update)
                },
                onFailure = {
                    val page = fallbackUrl
                        ?: com.xjtu.toolbox.util.AppUpdater.releasesPageUrl(credentialStore.updateChannel)
                    val opened = runCatching {
                        context.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(page),
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }.isSuccess
                    if (!opened) {
                        android.widget.Toast.makeText(
                            context,
                            "检查更新失败：${it.message}",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            )
        }
    }

    fun dismissHeroBulletin(bulletin: Bulletin) {
        when {
            bulletin.synthesized -> credentialStore.markUpdateNoticeSeen(bulletin.id)
            bulletin.level == BulletinLevel.CRITICAL -> bulletinStore.ack(bulletin.id)
            bulletin.level == BulletinLevel.FORCE_UPDATE -> bulletinStore.snooze(bulletin.id)
            else -> bulletinStore.dismiss(bulletin.id)
        }
        applyHeroBulletin(bulletinStore.peekCached(), pendingUpdate)
    }

    fun onHeroBulletinTap(bulletin: Bulletin) {
        if (bulletin.isPoll) {
            launchDialogBulletin = bulletin
            showBulletinDialog.value = true
            return
        }
        if (bulletin.level == BulletinLevel.FORCE_UPDATE ||
            bulletin.level == BulletinLevel.UPDATE ||
            bulletin.synthesized
        ) {
            openPendingUpdate(bulletin.url)
            return
        }
        val url = bulletin.url
        if (!url.isNullOrBlank()) {
            runCatching {
                context.startActivity(
                    android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(url),
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        if (autoUpdateCheckDone.value) return@LaunchedEffect
        autoUpdateCheckDone.value = true
        applyHeroBulletin(bulletinStore.peekCached(), null)

        val fetched = runCatching { BulletinApi.fetch() }.getOrNull()
        if (fetched != null) bulletinStore.cachedJson = fetched.rawJson
        val remoteItems = fetched?.items ?: bulletinStore.peekCached()

        var update: com.xjtu.toolbox.util.AppUpdateInfo? = null
        val now = System.currentTimeMillis()
        if (now - credentialStore.lastAutoUpdateCheckAt >= com.xjtu.toolbox.util.AppUpdater.AUTO_CHECK_INTERVAL_MS) {
            try {
                update = com.xjtu.toolbox.util.AppUpdater.check(
                    channel = credentialStore.updateChannel,
                    includePreview = credentialStore.receivePreviewUpdates,
                    rolloutId = credentialStore.rolloutId,
                )
                credentialStore.lastAutoUpdateCheckAt = System.currentTimeMillis()
            } catch (e: Exception) {
                android.util.Log.w("AppUpdater", "startup update check failed", e)
            }
        }
        if (update != null) pendingUpdate = update
        applyHeroBulletin(remoteItems, update)
        val picked = BulletinRules.pickLaunchDialog(heroBulletins)
        if (picked != null) {
            launchDialogBulletin = picked
            showBulletinDialog.value = true
        } else if (update != null &&
            !credentialStore.isUpdateNoticeSeen("auto_${update.channel}_${update.version}")
        ) {
            presentUpdate(update)
        }
        bulletinCheckFinished = true
    }

    // WindowBottomSheet 同时只能稳妥挂一个。冷启动若强制更新和重要通知各弹一层，
    // 后开的会把先开的顶掉。首页堆叠条照常全显示，框只弹优先级最高的那条。
    val showingBulletinDialog = showBulletinDialog.value && launchDialogBulletin != null
    when {
        showingBulletinDialog -> {
            val dialogBulletin = launchDialogBulletin!!
            BulletinLaunchDialog(
                bulletin = dialogBulletin,
                show = showBulletinDialog,
                onDismiss = {
                    if (dialogBulletin.level == BulletinLevel.FORCE_UPDATE) {
                        bulletinStore.snooze(dialogBulletin.id)
                        applyHeroBulletin(bulletinStore.peekCached(), pendingUpdate)
                    }
                    showBulletinDialog.value = false
                    launchDialogBulletin = null
                },
                onPrimary = {
                    showBulletinDialog.value = false
                    launchDialogBulletin = null
                    if (dialogBulletin.level == BulletinLevel.FORCE_UPDATE) {
                        openPendingUpdate(dialogBulletin.url)
                    }
                },
                onSubmitPoll = { selectedOptions ->
                    // 跟 FeedbackPromptSheet 一个原则：失败静默丢弃，不为这一下额外打扰用户。
                    // 本地先标记已答，不等网络结果——避免提交失败时反复重弹同一条投票。
                    mainScope.launch {
                        runCatching {
                            com.xjtu.toolbox.feedback.FeedbackApi.submit(
                                ticket = com.xjtu.toolbox.feedback.FeedbackStore.newTicket(),
                                category = "投票·${dialogBulletin.id}",
                                content = selectedOptions.joinToString("、"),
                                contact = "",
                                anonId = com.xjtu.toolbox.feedback.FeedbackStore.anonId(context),
                            )
                        }
                    }
                    bulletinStore.ack(dialogBulletin.id)
                    applyHeroBulletin(bulletinStore.peekCached(), pendingUpdate)
                    showBulletinDialog.value = false
                    launchDialogBulletin = null
                },
            )
        }
        showAutoUpdateDialog.value -> {
            AutoUpdateDialog(
                version = autoUpdateVersion,
                body = autoUpdateBody,
                downloadUrl = autoUpdateDownloadUrl,
                releaseUrl = autoUpdateReleaseUrl,
                channelLabel = autoUpdateChannel,
                isPreview = autoUpdateIsPreview,
                onDismiss = {
                    credentialStore.markUpdateNoticeSeen("auto_${autoUpdateChannelKey}_${autoUpdateVersion}")
                    showAutoUpdateDialog.value = false
                    applyHeroBulletin(bulletinStore.peekCached(), pendingUpdate)
                }
            )
        }
        bulletinCheckFinished && showUpdateNotice.value -> {
            UpdateNoticeDialog(
                entries = pendingChangelog,
                show = showUpdateNotice,
                fromVersion = previousRunVersion?.takeIf { it != BuildConfig.VERSION_NAME },
                onDismiss = {
                    credentialStore.lastSeenChangelogVersion = BuildConfig.VERSION_NAME
                    showUpdateNotice.value = false
                }
            )
        }
    }

    // 宽屏判断在导航根部算一次向下提供（见 ui/WindowSize.kt）：各页面若各算各的，
    // 同一帧里可能得出不一致的结论（侧栏认为宽屏、内容区认为窄屏），布局就会错位。
    CompositionLocalProvider(
        LocalAppLoginState provides loginState,
        com.xjtu.toolbox.ui.LocalIsWideLayout provides com.xjtu.toolbox.ui.calculateIsWideLayout(),
    ) {
    // MFA 短信验证弹窗全应用只挂这一处：WindowDialog 自带窗口，不依赖页面 Scaffold，
    // 放在 NavHost 外层才能覆盖所有子页面触发的重认证，见 MfaDialogHost 注释。
    com.xjtu.toolbox.auth.MfaDialogHost(loginState.sessionManager)
    // 注意：不要在这里套一层 Scaffold 来给 overlay 弹窗提供宿主。
    //
    // 背景：miuix 0.9.3 起 OverlayDialog/OverlayBottomSheet/OverlayListPopup 默认
    // `renderInRootScaffold = true`，需要 Scaffold 提供 LocalDialogStates 才会被渲染，
    // 所以"在根部套一层 Scaffold"看起来能一次性修好全项目的弹窗。**实际不行**：
    // miuix 的 ScaffoldLayout 内部是 SubcomposeLayout，套在这里等于把整棵导航树塞进
    // 一个 subcompose 槽，测量条件变化时内容会被丢弃重建，页面里 rememberCoroutineScope
    // 拿到的 scope 随之失效——真机表现为日程页
    // `ForgottenCoroutineScopeException: rememberCoroutineScope left the composition`，
    // 课表、学期列表全部拉不到。已验证并回退。
    //
    // 正确做法是把弹窗写进**各自页面 Scaffold 的 content 里**（miuix 的预期用法），
    // 而不是与 Scaffold 平级放在页面函数体顶层。
    // 转场、跟手侧滑返回、系统预测式返回、圆角裁剪和变暗都用 miuix-nav 的默认值（plan2 §12.4 第 6 条）：
    // 先在真机上看过再决定要不要调，不要自己再写一套。
    NavDisplay(
        backStack = backStack,
        onBack = { navController.popBackStack() },
    ) {

        entry<AppRoute.Main> {
            val mainScope = rememberCoroutineScope()
            MainScreen(
                navController = navController,
                loginState = loginState,
                credentialStore = credentialStore,
                accountManager = viewModel.accountManager,
                isRestoring = isRestoring,
                restoreStep = restoreStep,
                restoreGateReady = restoreGateReady,
                pendingTab = pendingMainTab,
                onPendingTabConsumed = {
                    pendingMainTab = null
                    onInitialTabConsumed()
                },
                pendingLaunchRoute = pendingLaunchRoute,
                onPendingLaunchConsumed = { pendingLaunchRoute = null },
                onWarmupRequest = { startBackgroundLoginWarmup(mainScope, force = true) },
                homeTheme = homeTheme,
                showQuickActions = showQuickActions,
                heroBulletins = heroBulletins,
                onHeroBulletinTap = ::onHeroBulletinTap,
                onHeroBulletinDismiss = ::dismissHeroBulletin,
            )
        }

        entry<AppRoute.EmptyRoom>(transition = expand(AppRoute.EmptyRoom::class)) {
            val direct = loginState.sessionManager?.getSiteOrNull("jwxt")?.client
            EmptyRoomScreen(
                onBack = { navController.popBackStack() },
                directClient = direct,
            )
        }
        entry<AppRoute.Notification>(transition = expand(AppRoute.Notification::class)) {
            NotificationScreen(
                onBack = { navController.popBackStack() },
                onNavigate = {
                    if (it == Routes.SCHEDULE) {
                        navigateToMainTab(BottomTab.COURSES)
                    } else {
                        navController.navigate(it)
                    }
                }
            )
        }
        entry<AppRoute.NewAttendance>(transition = expand(AppRoute.NewAttendance::class)) {
            loginState.sessionManager?.getSiteOrNull("new_attendance")?.let {
                com.xjtu.toolbox.newattendance.NewAttendanceScreen(
                    site = it,
                    onBack = { navController.popBackStack() },
                    onOpenIclassface = {
                        // 做法照搬成绩页的 onOpenReport：已登录直接进，否则先登录再进。
                        // 多包一层 try/catch：ensureSite 失败时提示一句，不闪退
                        if (loginState.sessionManager?.getSiteOrNull("iclassface")?.hasLogin == true) navController.navigate(Routes.ICLASSFACE)
                        else mainScope.launch {
                            try {
                                val site = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    loginState.sessionManager?.ensureSite(LoginType.ICLASSFACE)
                                }
                                if (site != null) navController.navigate(Routes.ICLASSFACE)
                            } catch (e: Exception) {
                                android.widget.Toast.makeText(context, "打开快速考勤流水失败：${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                )
            } ?: LaunchedEffect(Unit) { navController.popBackStack() }
        }
        entry<AppRoute.Schedule> {
            LaunchedEffect(Unit) {
                navigateToMainTab(BottomTab.COURSES)
            }
        }
        entry<AppRoute.JwappScore>(transition = expand(AppRoute.JwappScore::class)) {
            JwappScoreScreen(
                site = loginState.sessionManager?.getSiteOrNull("jwapp"),
                jwxtSite = loginState.sessionManager?.getSiteOrNull("jwxt"),
                studentId = loginState.activeUsername,
                onBack = { navController.popBackStack() },
                onOpenReport = {
                    // 成绩报表需 JWXT 登录：已登录直接进，否则走 JWXT 登录后再跳报表
                    if (loginState.sessionManager?.getSiteOrNull("jwxt")?.hasLogin == true) navController.navigate(Routes.SCORE_REPORT)
                    else mainScope.launch {
                        val site = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            loginState.sessionManager?.ensureSite(LoginType.JWXT)
                        }
                        if (site != null) navController.navigate(Routes.SCORE_REPORT)
                    }
                }
            )
        }
        entry<AppRoute.Judge>(transition = expand(AppRoute.Judge::class)) {
            loginState.sessionManager?.getSiteOrNull("jwxt")?.let { JudgeScreen(site = it, username = loginState.activeUsername, onBack = { navController.popBackStack() }) } ?: LaunchedEffect(Unit) { navController.popBackStack() }
        }
        entry<AppRoute.Library>(transition = expand(AppRoute.Library::class)) {
            loginState.sessionManager?.getSiteOrNull("library")?.let { LibraryScreen(site = it, onBack = { navController.popBackStack() }) } ?: LaunchedEffect(Unit) { navController.popBackStack() }
        }
        entry<AppRoute.CampusCard>(transition = expand(AppRoute.CampusCard::class)) {
            var cardSite by remember { mutableStateOf(loginState.sessionManager?.getSiteOrNull("campus_card")) }
            val readyCard = cardSite
            if (readyCard != null) {
                com.xjtu.toolbox.card.CampusCardScreen(site = readyCard, onBack = { navController.popBackStack() })
            } else {
                LaunchedEffect(Unit) {
                    repeat(12) {
                        kotlinx.coroutines.delay(120)
                        val found = loginState.sessionManager?.getSiteOrNull("campus_card")
                        if (found != null) {
                            cardSite = found
                            return@LaunchedEffect
                        }
                    }
                    navController.popBackStack()
                }
            }
        }
        entry<AppRoute.Coupon>(transition = expand(AppRoute.Coupon::class)) {
            loginState.sessionManager?.getSiteOrNull("coupon")?.let { com.xjtu.toolbox.coupon.CouponScreen(site = it, onBack = { navController.popBackStack() }) } ?: LaunchedEffect(Unit) { navController.popBackStack() }
        }
        entry<AppRoute.ScoreReport>(transition = expand(AppRoute.ScoreReport::class)) {
            loginState.sessionManager?.getSiteOrNull("jwxt")?.let { ScoreReportScreen(site = it, studentId = loginState.activeUsername, onBack = { navController.popBackStack() }) } ?: LaunchedEffect(Unit) { navController.popBackStack() }
        }
        entry<AppRoute.Transcript>(transition = expand(AppRoute.Transcript::class)) {
            loginState.sessionManager?.getSiteOrNull("dzpz")?.let { com.xjtu.toolbox.dzpz.TranscriptScreen(site = it, onBack = { navController.popBackStack() }) } ?: LaunchedEffect(Unit) { navController.popBackStack() }
        }
        entry<AppRoute.Venue>(transition = expand(AppRoute.Venue::class)) {
            loginState.sessionManager?.getSiteOrNull("venue")?.let {
                com.xjtu.toolbox.venue.VenueScreen(
                    site = it,
                    credentialStore = credentialStore,
                    onBack = { navController.popBackStack() }
                )
            } ?: LaunchedEffect(Unit) { navController.popBackStack() }
        }
        entry<AppRoute.DownloadManager>(transition = expand(AppRoute.DownloadManager::class)) {
            com.xjtu.toolbox.media.DownloadManagerScreen(
                onBack = { navController.popBackStack() }
            )
        }
        entry<AppRoute.Lms>(transition = expand(AppRoute.Lms::class)) { entry ->
            loginState.sessionManager?.getSiteOrNull("lms")?.let { site ->
                com.xjtu.toolbox.lms.LmsScreen(
                    site = site,
                    onBack = { navController.popBackStack() },
                    initialCourseId = entry.courseId,
                )
            } ?: LaunchedEffect(Unit) { navController.popBackStack() }
        }
        entry<AppRoute.Jiaocai>(transition = expand(AppRoute.Jiaocai::class)) {
            loginState.sessionManager?.getSiteOrNull("jiaocai")?.let {
                com.xjtu.toolbox.jiaocai.JiaocaiScreen(
                    site = it,
                    onBack = { navController.popBackStack() },
                    onOpenFullText = { ssno, title ->
                        navController.navigate(Routes.jiaocai1Reader(ssno, title))
                    },
                )
            } ?: LaunchedEffect(Unit) { navController.popBackStack() }
        }
        entry<AppRoute.Jiaocai1>(transition = expand(AppRoute.Jiaocai1::class)) {
            // 全文库只认 IP、不做 CAS，借 jiaocai 会话是为了拿它的 OkHttp 客户端
            loginState.sessionManager?.getSiteOrNull("jiaocai")?.let {
                com.xjtu.toolbox.jiaocai1.Jiaocai1Screen(
                    site = it,
                    onBack = { navController.popBackStack() },
                    onOpenBook = { ssno, title ->
                        navController.navigate(Routes.jiaocai1Reader(ssno, title))
                    },
                )
            } ?: LaunchedEffect(Unit) { navController.popBackStack() }
        }
        // 阅读器横向翻页，关掉页内侧滑返回免得抢手势；系统返回手势不受影响
        entry<AppRoute.Jiaocai1Reader>(transition = expand(AppRoute.Jiaocai1Reader::class), swipeDismiss = NavSwipeDirection.None) { reader ->
            val ssno = reader.ssno
            val title = reader.title
            loginState.sessionManager?.getSiteOrNull("jiaocai")?.let {
                com.xjtu.toolbox.jiaocai1.Jiaocai1ReaderScreen(
                    site = it,
                    ssno = ssno,
                    fallbackTitle = title,
                    onBack = { navController.popBackStack() },
                )
            } ?: LaunchedEffect(Unit) { navController.popBackStack() }
        }
        entry<AppRoute.SchoolCourse>(transition = expand(AppRoute.SchoolCourse::class)) {
            com.xjtu.toolbox.schedule.SchoolCourseScreen(
                site = loginState.sessionManager?.getSiteOrNull("jwxt"),
                onBack = { navController.popBackStack() }
            )
        }
        entry<AppRoute.SchoolCalendar>(transition = expand(AppRoute.SchoolCalendar::class)) {
            com.xjtu.toolbox.calendar.SchoolCalendarScreen(
                onBack = { navController.popBackStack() }
            )
        }
        entry<AppRoute.YellowPage>(transition = expand(AppRoute.YellowPage::class)) {
            com.xjtu.toolbox.yellowpage.YellowPageScreen(
                onBack = { navController.popBackStack() }
            )
        }
        entry<AppRoute.Fitness>(transition = expand(AppRoute.Fitness::class)) {
            loginState.sessionManager?.getSiteOrNull("fitness")?.let {
                com.xjtu.toolbox.fitness.FitnessScreen(
                    site = it,
                    onBack = { navController.popBackStack() }
                )
            } ?: LaunchedEffect(Unit) { navController.popBackStack() }
        }
        entry<AppRoute.Iclassface>(transition = expand(AppRoute.Iclassface::class)) {
            loginState.sessionManager?.getSiteOrNull("iclassface")?.let {
                com.xjtu.toolbox.iclassface.IclassfaceScreen(
                    site = it,
                    onBack = { navController.popBackStack() }
                )
            } ?: LaunchedEffect(Unit) { navController.popBackStack() }
        }
        // WebView 里常有横向滚动，关掉页内侧滑返回；系统返回手势不受影响
        entry<AppRoute.Browser>(transition = expand(AppRoute.Browser::class), swipeDismiss = NavSwipeDirection.None) { browser ->
            val url = browser.url
            val browserSite = loginState.sessionManager?.getSiteOrNull(siteKeyForBrowserUrl(url))
                ?: loginState.sessionManager?.getSiteOrNull("jwxt")
            val host = runCatching { android.net.Uri.parse(url).host?.lowercase() }.getOrNull()
            com.xjtu.toolbox.browser.BrowserScreen(
                initialUrl = url,
                site = browserSite,
                cookieClient = if (url.contains("webvpn.xjtu.edu.cn", ignoreCase = true)) {
                    loginState.webVpnClientOrNull
                } else {
                    null
                },
                extraCookieDomains = listOfNotNull(host),
                onBack = { navController.popBackStack() }
            )
        }

        // ── 设置页 ──
        entry<AppRoute.Settings>(transition = expand(AppRoute.Settings::class)) {
            SettingsScreen(
                credentialStore = credentialStore,
                onBack = { navController.popBackStack() },
                onNavBarStyleChanged = { /* NavBar 风格变化通过 MainScreen 内部状态处理 */ },
                onDarkModeChanged = onDarkModeChanged,
                onDynamicColorChanged = onDynamicColorChanged,
                onDefaultTabChanged = { /* 下次启动生效 */ },
                homeTheme = homeTheme,
                onHomeThemeChanged = { v ->
                    homeTheme = v
                    credentialStore.homeTheme = v
                },
                showQuickActions = showQuickActions,
                onShowQuickActionsChanged = { v ->
                    showQuickActions = v
                    credentialStore.showQuickActions = v
                },
            )
        }

        // ── 用户反馈 ──
        entry<AppRoute.Feedback>(transition = expand(AppRoute.Feedback::class)) {
            FeedbackScreen(
                onBack = { navController.popBackStack() }
            )
        }

        // ── 教师主页检索 ──
        // 无需登录：faculty.xjtu.edu.cn 与 gr.xjtu.edu.cn 都是公开站点，
        // 因此这里不接 SessionManager，也不做 ensureSite。
        entry<AppRoute.Faculty>(transition = expand(AppRoute.Faculty::class)) {
            com.xjtu.toolbox.faculty.FacultyScreen(
                onBack = { navController.popBackStack() },
                onOpenUrl = { url -> navController.navigate(Routes.browser(url)) },
            )
        }

        // ── 小游戏合集 ──
        //
        // 各游戏自己的路由在下面单独注册，合集页只是最常见的那个入口：
        // 全局搜索搜「五子棋」应该能直接进去，而不是先落到合集页再点一次。
        entry<AppRoute.Games>(transition = expand(AppRoute.Games::class)) {
            com.xjtu.toolbox.game.GamesScreen(
                onBack = { navController.popBackStack() },
                onNavigate = { route -> navController.navigate(route) },
            )
        }

        entry<AppRoute.Game2048>(transition = expand(AppRoute.Game2048::class)) {
            com.xjtu.toolbox.game.g2048.Gpa2048Screen(onBack = { navController.popBackStack() })
        }

        entry<AppRoute.GameMerge>(transition = expand(AppRoute.GameMerge::class), swipeDismiss = NavSwipeDirection.None) {
            com.xjtu.toolbox.game.merge.MergeGameScreen(onBack = { navController.popBackStack() })
        }

        entry<AppRoute.GameGomoku>(transition = expand(AppRoute.GameGomoku::class), swipeDismiss = NavSwipeDirection.None) {
            com.xjtu.toolbox.game.gomoku.GomokuScreen(onBack = { navController.popBackStack() })
        }

        entry<AppRoute.GameGo>(transition = expand(AppRoute.GameGo::class), swipeDismiss = NavSwipeDirection.None) {
            com.xjtu.toolbox.game.go.GoScreen(onBack = { navController.popBackStack() })
        }

        entry<AppRoute.GameXiangqi>(transition = expand(AppRoute.GameXiangqi::class), swipeDismiss = NavSwipeDirection.None) {
            com.xjtu.toolbox.game.xiangqi.XiangqiScreen(onBack = { navController.popBackStack() })
        }

        entry<AppRoute.Match>(transition = expand(AppRoute.Match::class)) {
            // 不在 loginTypeForRoute 里：全程读本地缓存，不碰任何校园系统。
            com.xjtu.toolbox.social.MatchScreen(onBack = { navController.popBackStack() })
        }

        // ── 账号管理页 ──
        entry<AppRoute.Accounts>(transition = expand(AppRoute.Accounts::class)) {
            com.xjtu.toolbox.account.AccountManagerScreen(
                accountManager = viewModel.accountManager,
                loginState = loginState,
                onBack = { navController.popBackStack() }
            )
        }

        // ── WebVPN 网址互转 ──
        entry<AppRoute.WebVpnConverter>(transition = expand(AppRoute.WebVpnConverter::class)) {
            com.xjtu.toolbox.webvpn.WebVpnConverterScreen(
                isWebVpnReady = loginState.webVpnClientOrNull != null,
                onBack = { navController.popBackStack() },
                onOpenWithWebVpn = onOpenWithWebVpn@{ vpnUrl ->
                    // [policy] 一律走 pending 路径：LaunchedEffect 内会
                    //   1. checkWebVpnSessionAlive 校验 vpnClient 是否仍有效（防 stale 直接打开浏览器要求用户网页输密码）
                    //   2. 失效则 loginWebVpn（含 App 内 MFA dialog，若需要）
                    //   3. 成功后 navigate browser
                    // 校园网下用户也能用此入口（webvpn 链路本身可达），登录成功后浏览器内即可访问 vpnUrl。
                    // 不在这里先 popBackStack：若登录失败，用户应留在转换页看到状态，而不是被踢回 App 首页。
                    webVpnPendingBrowserUrl.value = vpnUrl
                }
            )
        }
        // [已移除] composable(Routes.AGENT)。屁岱升级成底栏 0 级 tab 后，
        // 再留一条 push 路由就会出现"带返回箭头的子页"和"tab"两副面孔，
        // 返回行为还不一致。所有指向 AGENT 的入口（深链、快捷方式、全局搜索、
        // 首页服务列表、提醒气泡）统一由 navigateToTarget 转成切 tab。
    }

    if (showPaymentCode) {
        // 和原来的对话框目的地一样：独立窗口、不限宽度，盖在当前页上面。
        // 付款码必须在校园卡登录后使用（复用 ncard JWT 访问 /berserker-app/authCode）
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showPaymentCode = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            var cardSite by remember { mutableStateOf(loginState.sessionManager?.getSiteOrNull("campus_card")) }
            val readyCard = cardSite
            if (readyCard != null) {
                com.xjtu.toolbox.pay.PaymentCodeDialog(site = readyCard) { showPaymentCode = false }
            } else {
                LaunchedEffect(Unit) {
                    repeat(12) {
                        kotlinx.coroutines.delay(120)
                        val found = loginState.sessionManager?.getSiteOrNull("campus_card")
                        if (found != null) {
                            cardSite = found
                            return@LaunchedEffect
                        }
                    }
                    showPaymentCode = false
                }
            }
        }
    }
    }  // CompositionLocalProvider
}

private fun siteKeyForBrowserUrl(url: String): String {
    val host = runCatching { android.net.Uri.parse(url).host?.lowercase().orEmpty() }
        .getOrDefault("")
    return when {
        "tyxylp.xjtu.edu.cn" in host -> "fitness"
        "rg.lib.xjtu.edu.cn" in host -> "library"
        "jwapp.xjtu.edu.cn" in host -> "jwapp"
        "ywtb.xjtu.edu.cn" in host -> "ywtb"
        "ncard.xjtu.edu.cn" in host -> "campus_card"
        "bkkq.xjtu.edu.cn" in host -> "attendance"
        "kq.xjtu.edu.cn" in host -> "new_attendance"
        "lms.xjtu.edu.cn" in host -> "lms"
        else -> "jwxt"
    }
}
