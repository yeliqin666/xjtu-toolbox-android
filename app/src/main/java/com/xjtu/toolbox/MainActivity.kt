package com.xjtu.toolbox

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.xjtu.toolbox.util.safeParseJsonObject
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.withLock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateDp
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.drawWithContent

import top.yukonga.miuix.kmp.utils.SinkFeedback
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Badge
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationBarDisplayMode
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleClip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.xjtu.toolbox.auth.*
import com.xjtu.toolbox.attendance.AttendanceScreen
import com.xjtu.toolbox.emptyroom.EmptyRoomScreen
import com.xjtu.toolbox.jwapp.JwappScoreScreen
import com.xjtu.toolbox.schedule.ScheduleScreen
import com.xjtu.toolbox.notification.NotificationScreen
import com.xjtu.toolbox.library.LibraryScreen
import com.xjtu.toolbox.judge.JudgeScreen
import com.xjtu.toolbox.score.ScoreReportScreen
import com.xjtu.toolbox.ui.theme.XJTUToolBoxTheme
import com.xjtu.toolbox.ui.theme.serviceColor
import com.xjtu.toolbox.ui.settings.SettingsScreen
import com.xjtu.toolbox.ui.components.AppCardColor
import com.xjtu.toolbox.ui.components.ExpressiveIcon
import com.xjtu.toolbox.agent.AgentPendingPrompt
import com.xjtu.toolbox.agent.AgentRuntimeHooks
import com.xjtu.toolbox.util.CredentialStore
import com.xjtu.toolbox.util.DeepLinkRouter
import com.xjtu.toolbox.widget.CampusCardWidgetUpdater
import com.xjtu.toolbox.widget.ScheduleWidgetUpdater
import com.xjtu.toolbox.home.AppServices
import com.xjtu.toolbox.home.GlobalSearchScreen
import com.xjtu.toolbox.home.ServiceCategory
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
        fun compareVersionStrings(v1: String, v2: String): Int {
            val parts1 = v1.split(".").map { it.toIntOrNull() ?: 0 }
            val parts2 = v2.split(".").map { it.toIntOrNull() ?: 0 }
            val maxLen = maxOf(parts1.size, parts2.size)
            for (i in 0 until maxLen) {
                val p1 = parts1.getOrElse(i) { 0 }
                val p2 = parts2.getOrElse(i) { 0 }
                if (p1 != p2) return p1.compareTo(p2)
            }
            return 0
        }
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

        // ── 后台 Session 保活：只设置一次 provider，循环本身根据用户开关在登录后启动 ──
        com.xjtu.toolbox.auth.SessionKeepAlive.setProvider {
            com.xjtu.toolbox.auth.SessionKeepAlive.KeepAliveSnapshot(
                logins = emptyList(),
                vpnClient = null
            )
        }
        // 启动循环（内部会读 KeepAlivePrefs.isEnabled，未开启则直接跳过）
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
    const val LOGIN = "login/{loginType}/{target}"
    const val EMPTY_ROOM = "empty_room"
    const val NOTIFICATION = "notification"
    const val ATTENDANCE = "attendance"
    const val POSTGRADUATE_ATTENDANCE = "postgraduate_attendance"
    const val SCHEDULE = "schedule"
    const val JUDGE = "judge"
    const val JWAPP_SCORE = "jwapp_score"
    const val YWTB = "ywtb"
    const val LIBRARY = "library"
    const val CAMPUS_CARD = "campus_card"
    const val SCORE_REPORT = "score_report"
    const val PAYMENT_CODE = "payment_code"
    const val COUPON = "coupon"
    const val TRANSCRIPT = "transcript"
    const val VENUE = "venue"
    const val CLASS_REPLAY = "class_replay"
    const val LMS = "lms"
    const val JIAOCAI = "jiaocai"
    const val JIAOCAI1 = "jiaocai1"
    const val JIAOCAI1_READER = "jiaocai1_reader/{ssno}?title={title}"
    const val SCHOOL_COURSE = "school_course"
    const val SCHOOL_CALENDAR = "school_calendar"
    const val YELLOW_PAGE = "yellow_page"
    const val MOBILE_JIAODA = "mobile_jiaoda"
    const val FITNESS = "fitness"
    const val VIDEO_PLAYER = "video_player/{activityId}"
    const val DOWNLOAD_MANAGER = "download_manager"
    const val BROWSER = "browser?url={url}"
    const val SETTINGS = "settings"
    const val ACCOUNTS = "accounts"
    const val WEBVPN_CONVERTER = "webvpn_converter"
    const val AGENT = "agent"
    const val FEEDBACK = "feedback"
    const val JIAOXIAOZHI = "jiaoxiaozhi"
    const val FACULTY = "faculty"
    const val ICLASSFACE = "iclassface"

    fun login(type: LoginType, target: String) = "login/${type.name}/$target"
    fun browser(url: String = "") = "browser?url=${java.net.URLEncoder.encode(url, "UTF-8")}"
    fun videoPlayer(activityId: Int) = "video_player/$activityId"
    fun jiaocai1Reader(ssno: String, title: String = "") =
        "jiaocai1_reader/$ssno?title=${java.net.URLEncoder.encode(title, "UTF-8")}"
}

/** shortcut / 搜索 / 深链进功能页时，对应要先登录的站点。null = 无需登录可直达。 */
fun loginTypeForRoute(route: String): LoginType? = when (route) {
    Routes.ATTENDANCE -> LoginType.ATTENDANCE
    Routes.POSTGRADUATE_ATTENDANCE -> LoginType.POSTGRADUATE_ATTENDANCE
    Routes.LIBRARY -> LoginType.LIBRARY
    Routes.CAMPUS_CARD, Routes.PAYMENT_CODE -> LoginType.CAMPUS_CARD
    Routes.JWAPP_SCORE -> LoginType.JWAPP
    Routes.SCORE_REPORT, Routes.JUDGE, Routes.SCHOOL_COURSE, Routes.EMPTY_ROOM, Routes.SCHEDULE -> LoginType.JWXT
    Routes.TRANSCRIPT -> LoginType.DZPZ
    Routes.VENUE -> LoginType.VENUE
    Routes.CLASS_REPLAY -> LoginType.CLASS
    Routes.LMS -> LoginType.LMS
    Routes.JIAOCAI, Routes.JIAOCAI1 -> LoginType.JIAOCAI
    Routes.COUPON -> LoginType.COUPON
    Routes.MOBILE_JIAODA -> LoginType.SUPER_APP
    Routes.FITNESS -> LoginType.FITNESS
    Routes.JIAOXIAOZHI -> LoginType.JIAOXIAOZHI
    Routes.ICLASSFACE -> LoginType.ICLASSFACE
    else -> if (route.startsWith("jiaocai1_reader")) LoginType.JIAOCAI else null
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
    TOOLS("学辅", Icons.Filled.MenuBook, Icons.Outlined.MenuBook),
    PROFILE("我的", Icons.Filled.Person, Icons.Outlined.Person)
}

// ── 登录状态 ──────────────────────────────

class AppLoginState : com.xjtu.toolbox.account.AppLoginStateHolder {
    override var activeUsername by mutableStateOf("")
    // [已移除] 16 个 *Login 缓存字段（attendanceLogin / jwxtLogin / ywtbLogin / …）。
    // 业务全部迁到 SessionManager + SiteSession 后，它们只剩「= null」的清理路径，
    // 没有任何赋值点——纯死状态，而且是 mutableStateOf，每次清理都白白触发一轮重组。
    // 会话真相唯一来源：sessionManager.getSite(siteKey)。

    // [已移除] persistentCookieJar / vpnCookieJar：cookie 存储唯一归属 SessionManager 的两个 backend。

    /**
     * 账号切换/新增/删除的一次性通知。MainActivity 的 LaunchedEffect 监听 → Snackbar，
     * mutableStateOf 由 Compose 自动重组；UI 取出后立即重置为 null（消费即焚）。
     */
    override var switchNotice by mutableStateOf<String?>(null)
    override fun consumeSwitchNotice(): String? {
        val n = switchNotice
        switchNotice = null
        return n
    }

    /** 新会话架构入口；由 [AppLoginStateViewModel] 创建时注入。 */
    var sessionManager: com.xjtu.toolbox.auth.SessionManager? = null

    // [已移除] sharedClient / clientInitMutex / mfaSerialMutex：
    // 携带 TGC 的共享 client 现在就是 SessionManager 各 backend 的 client；
    // 「TGC 建立前排队、避免各自弹 MFA」也已由 CasSiteSession 的 TGC 引导锁 +
    // SessionManager.askMfaCode 的 mfaMutex 承担，无需在 UI 层再维护一份。

    init {
        // 密码失效熔断接入 CAS 闸门：熔断中 XJTULogin/casAuthenticate 一律拒绝提交凭据
        val weakSelf = java.lang.ref.WeakReference(this)
        com.xjtu.toolbox.auth.CasGate.passwordLatch = {
            weakSelf.get()?.passwordInvalidatedLatch == true
        }
    }

    // ── 密码全局失效熔断 ──────────────────────────────────────────
    // 任一子系统确认凭据无效时设置，后续 autoLogin 立即短路返回，
    // 避免对同一错密并行重试触发服务端风控。用户更新凭据后自动清除。
    var passwordInvalidatedLatch by mutableStateOf(false)
        private set
    var passwordInvalidatedSiteName by mutableStateOf("")
        private set
    var passwordInvalidatedDialogVisible by mutableStateOf(false)

    /** 子系统检测到明确凭据无效时调用。重复调用幂等。 */
    fun reportPasswordInvalidated(siteName: String) {
        if (passwordInvalidatedLatch) return
        passwordInvalidatedLatch = true
        passwordInvalidatedSiteName = siteName
        passwordInvalidatedDialogVisible = true
        android.util.Log.w("AppLoginState", "password invalidated by site=$siteName")
    }

    /** 仅在响应消息含明确凭据无效关键字时为 true，避免把网络故障误判成密码错。 */
    private fun isPasswordError(result: com.xjtu.toolbox.auth.LoginResult): Boolean {
        if (result.state != com.xjtu.toolbox.auth.LoginState.FAIL) return false
        val msg = result.message
        return msg.contains("用户名或密码", ignoreCase = true) ||
                msg.contains("密码错误", ignoreCase = true) ||
                msg.contains("账号或密码", ignoreCase = true) ||
                msg.contains("401")
    }

    // [已移除] sharedConnectionPool：连接池现由 SessionBackend 持有（每 backend 一个，
    // 8 连接 / 5 分钟 keep-alive），最后一个使用者 doLoginWebVpn 已随 WebVPN 统一而删除。

    // ── WebVPN：唯一真相是 SessionManager 的 WEBVPN backend ──────────────
    // 该 backend 自带 WebVpnInterceptor 与 cookies_webvpn_<账号> jar，
    // 业务站点（SiteSession）与浏览器路径共用同一份网关会话，不再各认证一次。

    private val webVpnBackend: com.xjtu.toolbox.auth.SessionBackend?
        get() = sessionManager?.backend(com.xjtu.toolbox.auth.AccessMode.WEBVPN)

    internal val webVpnClientOrNull: okhttp3.OkHttpClient?
        get() = webVpnBackend?.takeIf { it.webvpnSelfLoggedIn }?.client

    fun clearVpnClient() {
        webVpnBackend?.let { b ->
            b.cookieJar.clearForDomain("webvpn.xjtu.edu.cn")
            b.cookieJar.clearForDomain(".webvpn.xjtu.edu.cn")
            b.cookieJar.flushToDisk()
            b.webvpnSelfLoggedIn = false
        }
    }

    /**
     * 校验当前 webvpn session 是否仍然有效（cookie 没过期、wengine_vpn_ticket 仍被认）。
     * 发轻量 HEAD 到 webvpn 主页，若被重定向到 cas_login 即视为失效。
     *
     * 失效时会自动 [clearVpnClient]，让调用方走 [loginWebVpn] 重建（含可能的 MFA dialog）。
     * 校园网下没有 vpnClient 时直接返回 false，调用方决定是否需要切到 webvpn 模式。
     */
    suspend fun checkWebVpnSessionAlive(): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val client = webVpnClientOrNull ?: return@withContext false
        try {
            val req = okhttp3.Request.Builder()
                .url(com.xjtu.toolbox.util.WebVpnUtil.WEBVPN_LOGIN_URL)
                .get()
                .build()
            // 不跟随重定向，看 Location header
            val noRedirect = client.newBuilder()
                .followRedirects(false).followSslRedirects(false).build()
            noRedirect.newCall(req).execute().use { r ->
                val loc = r.header("Location") ?: ""
                val bodyPreview = runCatching { r.peekBody(8192).string() }.getOrDefault("")
                val redirectedToCas = "cas_login" in loc || "/cas/login" in loc || "login.xjtu.edu.cn" in loc
                val authPage = com.xjtu.toolbox.auth.XJTULogin.isAuthFailureResponse(bodyPreview)
                val resourcePage = "西安交通大学WebVPN" in bodyPreview || "资源站点" in bodyPreview
                val alive = (r.code in 200..299 && !authPage) ||
                    (r.code in 300..399 && !redirectedToCas) ||
                    resourcePage
                if (!alive) {
                    android.util.Log.w("WebVPN", "checkWebVpnSessionAlive: session stale (code=${r.code}, loc=$loc, authPage=$authPage), clearing vpnClient")
                    clearVpnClient()
                }
                alive
            }
        } catch (e: Exception) {
            android.util.Log.w("WebVPN", "checkWebVpnSessionAlive: exception ${e.message}, treating as alive (avoid false-negative on transient error)")
            true  // 网络抖动时不清，下次自然重试
        }
    }

    /**
     * 清除所有子系统会话（不动 cookies），用于 access mode 切换。
     * 现由 SessionManager 统一处理——[com.xjtu.toolbox.auth.SessionManager.onNetworkChanged]
     * 已对每个 site 调用 invalidateLogin，这里只兜住 sessionManager 尚未注入的早期调用。
     */
    fun clearAllCachedLogins() {
        sessionManager?.invalidateAllSites()
    }

    /**
     * Screen 内部捕获 [AuthExpiredException] 时调用：清掉 cached login + 让 nav 自动重新进入。
     * 用户表现为：返回首页 → 简短 loading → 自动回到原页面。
     */
    fun markStaleAndRetry(type: LoginType, route: String) {
        android.util.Log.w("AppLoginState", "markStaleAndRetry($type, $route)")
        clearLogin(type)
        pendingRetry = type to route
    }
    var pendingRetry by mutableStateOf<Pair<LoginType, String>?>(null)

    /**
     * 网络环境（access mode）切换时调用：清旧 cached login + vpnClient，
     * 同步通知 SessionManager 切换 active backend（两边 cookies 保留以便快速切回）。
     */
    suspend fun onNetworkChanged(): Boolean {
        val prev = isOnCampus
        campusDetectTime = 0L
        val now = detectCampusNetwork()
        isOnCampus = now
        sessionManager?.onNetworkChanged(
            if (now) com.xjtu.toolbox.auth.AccessMode.NORMAL
            else com.xjtu.toolbox.auth.AccessMode.WEBVPN
        )
        if (prev != null && prev != now) {
            android.util.Log.w("AppLoginState", "Access mode changed: $prev → $now")
            clearAllCachedLogins()
            clearVpnClient()
            return true
        }
        return false
    }
    var isOnCampus by mutableStateOf<Boolean?>(null)   // null=未检测, true=校内, false=校外
    // [已移除] webVpnLoggedIn：网关登录态改读 SessionBackend.webvpnSelfLoggedIn，避免两处状态漂移。

    // 网络检测结果缓存（10 分钟）
    private var campusDetectTime: Long = 0L
    private val CAMPUS_CACHE_MS = 10 * 60 * 1000L

    // 设备指纹 ID（首次登录时生成，后续系统复用以避免 MFA 重复验证）
    @Volatile internal var firstVisitorId: String? = null

    // RSA 公钥缓存
    @Volatile internal var cachedRsaKey: String? = null

    // 一网通办个人信息（登录后自动获取，在"我的"页面展示）
    override var ywtbUserInfo by mutableStateOf<com.xjtu.toolbox.ywtb.UserInfo?>(null)

    // 校园卡缓存刷新版本：余额/最近消费落盘后递增，驱动首页智能卡片重读缓存。
    var campusCardCacheVersion by mutableIntStateOf(0)

    // 缓存的昵称（从 CredentialStore 恢复，YWTB 加载前即可显示）
    override var cachedNickname by mutableStateOf<String?>(null)

    // 当前激活账号 ID（= 学号 / 手机号）。多账号隔离的根键。
    override var accountId by mutableStateOf("")

    /**
     * 清空全部「内存中」的会话/身份状态（不动磁盘命名空间数据）。
     * 切换账号前调用，确保旧账号的 ywtbUserInfo/nickname/cached login 不会泄露给新账号 UI。
     */
    override fun clearInMemorySessionState() {
        activeUsername = ""
        savedUsername = ""; savedPassword = ""
        sessionManager?.invalidateAllSites()
        // 网关登录态随 backend 走：切账号时 reconfigureForAccount 会整体换掉 backends，
        // 这里额外置一次，覆盖「尚未 reconfigure 就先清内存态」的调用顺序。
        webVpnBackend?.webvpnSelfLoggedIn = false
        isOnCampus = null
        campusDetectTime = 0L
        ywtbUserInfo = null
        firstVisitorId = null
        cachedRsaKey = null
        cachedNickname = null
        accountId = ""
        passwordInvalidatedLatch = false
        passwordInvalidatedSiteName = ""
        passwordInvalidatedDialogVisible = false
        com.xjtu.toolbox.pay.PaymentCodeApi.clearCachedJwt()
        campusCardCacheVersion++  // 触发首页校园卡卡片重读（切到新账号命名空间缓存）
    }

    /**
     * 从一个 [com.xjtu.toolbox.account.Account] 载入身份到内存（切换账号或启动恢复时用）。
     * 仅设置内存态；磁盘 cookies 由 SessionManager.reconfigureForAccount 处理。
     */
    override fun loadIdentityFromAccount(account: com.xjtu.toolbox.account.Account) {
        accountId = account.accountId
        savedUsername = account.accountId
        savedPassword = account.password
        activeUsername = account.accountId
        accountType = account.accountType
        cachedNickname = account.nickname
        firstVisitorId = account.fpVisitorId
        cachedRsaKey = account.rsaPublicKey
        com.xjtu.toolbox.account.AccountContext.activeAccountId = account.accountId
        sessionManager?.let {
            it.setCredentials(account.accountId, account.password)
            it.accountType = selectedCasAccountType()
            it.fpVisitorId = account.fpVisitorId
            it.cachedRsaKey = account.rsaPublicKey
        }
    }

    // CredentialStore 引用
    private var credentialStoreRef: CredentialStore? = null

    // 保存的凭据（内存中），用于自动登录其他系统
    override var savedUsername: String = ""
    override var savedPassword: String = ""
    override var accountType by mutableStateOf(com.xjtu.toolbox.auth.AccountType.UNDERGRADUATE)

    val hasCredentials: Boolean get() = savedUsername.isNotEmpty() && savedPassword.isNotEmpty()
    val isLoggedIn: Boolean get() = activeUsername.isNotEmpty()

    private fun selectedCasAccountType(): XJTULogin.AccountType {
        // 优先用内存中当前账号的 accountType（多账号隔离后每个账号各自持有），
        // 仅在尚未载入时回退到 CredentialStore 旧单值。
        val currentAccountType = if (accountId.isNotEmpty()) accountType else (credentialStoreRef?.accountType ?: accountType)
        return if (currentAccountType == com.xjtu.toolbox.auth.AccountType.POSTGRADUATE) {
            XJTULogin.AccountType.POSTGRADUATE
        } else {
            XJTULogin.AccountType.UNDERGRADUATE
        }
    }

    fun saveCredentials(username: String, password: String) {
        // 凭据变更视为用户已知晓并响应，清除密码失效熔断
        val credentialsChanged = (username != savedUsername || password != savedPassword)
        savedUsername = username
        savedPassword = password
        activeUsername = username
        if (credentialsChanged && passwordInvalidatedLatch) {
            passwordInvalidatedLatch = false
            passwordInvalidatedSiteName = ""
            passwordInvalidatedDialogVisible = false
            android.util.Log.i("AppLoginState", "credentials updated, password latch cleared")
        }
        sessionManager?.let {
            it.setCredentials(username, password)
            it.accountType = selectedCasAccountType()
        }
    }

    /** 从 EncryptedSharedPreferences 恢复凭据和缓存 */
    fun restoreCredentials(store: CredentialStore) {
        credentialStoreRef = store
        val creds = store.load() ?: return
        savedUsername = creds.first
        savedPassword = creds.second
        // 恢复 activeUsername → isLoggedIn 为 true，离线冷启动也显示欢迎称呼
        if (savedUsername.isNotEmpty()) activeUsername = savedUsername
        // 恢复持久化的 fpVisitorId（保持设备一致性，避免 MFA）
        firstVisitorId = store.loadFpVisitorId()
        // 恢复 RSA 公钥缓存（24h 有效期）
        cachedRsaKey = store.loadRsaPublicKey()
        // 恢复缓存昵称（欢迎卡片秒显示）
        cachedNickname = store.loadNickname()
        accountType = store.accountType
        // 同步至新会话架构
        sessionManager?.let {
            it.setCredentials(savedUsername, savedPassword)
            it.accountType = selectedCasAccountType()
            it.fpVisitorId = firstVisitorId
            it.cachedRsaKey = cachedRsaKey
        }
    }

    /** 持久化凭据和缓存到 EncryptedSharedPreferences */
    fun persistCredentials(store: CredentialStore) {
        if (hasCredentials) store.save(savedUsername, savedPassword)
        firstVisitorId?.let { store.saveFpVisitorId(it) }
        cachedRsaKey?.let { store.saveRsaPublicKey(it) }
    }

    /**
     * 携带 CAS TGC 的共享 client —— 现在就是 SessionManager 直连 backend 的 client。
     * （旧的 sharedClient 字段已删除：它只在「校外别名成 vpnClient」时被赋值，首次永远为 null。）
     */
    fun getSharedClient(): okhttp3.OkHttpClient? =
        sessionManager?.backend(com.xjtu.toolbox.auth.AccessMode.NORMAL)?.client

    /** 清除指定子系统的会话（用于 reAuth 失败后强制 full login）。 */
    fun clearLogin(type: LoginType) {
        sessionManager?.getSiteOrNull(type.siteKey())?.invalidateLogin()
    }

    /**
     * 单次探测校园网（向本科考勤系统 bkkq 发一个 HEAD，3 秒超时）。
     * 不更新缓存、不读缓存，纯函数式。
     *
     * 探测点选考勤系统而非教务：护网结束后教务（jwxt）已公网直连，校外也能访问，
     * 探测恒为 true 无法区分内外网；考勤系统 bkkq 仍仅校内可直连，校外需 WebVPN，
     * 因此用它判定「是否可直连校内系统」。返回任意 <500 响应即视为可达。
     * 对齐上游 XJTUToolBox：改用考勤系统作为校内外检测网址。
     */
    private suspend fun probeCampusOnce(): Boolean = try {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val testClient = okhttp3.OkHttpClient.Builder()
                .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(false)
                .build()
            val request = okhttp3.Request.Builder()
                .url("http://bkkq.xjtu.edu.cn")
                .head()
                .build()
            testClient.newCall(request).execute().use { it.code < 500 }
        }
    } catch (_: Exception) { false }

    /**
     * 检测是否在校园网内（带 10 分钟缓存）。
     *
     * 波动保护：探测结果若与缓存不同，再做一次确认（间隔 1.5 秒），两次一致才算 mode 变化。
     * 这样可以避免：网络刚切换/信号瞬间抖动导致的误判（一次失败 ≠ 真的校外）。
     *
     * 手动模式短路：用户在「设置 → 网络 → 连接模式」选了「强制直连」/「强制 WebVPN」时，
     * 跳过探测直接返回对应结果——过去这个设置项只写入 [CredentialStore]，从未被读取，
     * 用户选了「强制 WebVPN」实际什么都不会发生，是纯粹的假开关。
     */
    suspend fun detectCampusNetwork(): Boolean {
        when (credentialStoreRef?.networkMode) {
            CredentialStore.NETWORK_DIRECT -> return true
            CredentialStore.NETWORK_VPN -> return false
            else -> {} // 自动检测：走下面的真实探测逻辑
        }
        // 缓存有效期内直接返回
        val cached = isOnCampus
        if (cached != null && System.currentTimeMillis() - campusDetectTime < CAMPUS_CACHE_MS) {
            android.util.Log.d("Campus", "detectCampus: using cached result=$cached (age=${(System.currentTimeMillis() - campusDetectTime) / 1000}s)")
            return cached
        }
        val first = probeCampusOnce()
        // 第一次探测结果与缓存不同 → 二次确认避免瞬时波动误判
        val result = if (cached != null && cached != first) {
            android.util.Log.d("Campus", "detectCampus: first probe disagrees with cache ($cached→$first), confirming...")
            kotlinx.coroutines.delay(1500L)
            val second = probeCampusOnce()
            if (second != first) {
                android.util.Log.d("Campus", "detectCampus: second probe $second != first $first, treating as transient, keeping cached=$cached")
                cached  // 两次不一致，认为是瞬时波动，保留旧值
            } else {
                android.util.Log.d("Campus", "detectCampus: confirmed change to $second")
                second
            }
        } else {
            first
        }
        android.util.Log.d("Campus", "detectCampus: final result=$result (bkkq reachable=$first)")
        campusDetectTime = System.currentTimeMillis()
        return result
    }

    /**
     * WebVPN 网关登录（校外接入）。
     *
     * 【已统一】此前这里维护着**第二套**网关会话：自建 webVpnRewriteClient + vpnCookieJar
     *（物理文件 `xjtu_cookies`），与 SessionManager 的 WEBVPN backend（`cookies_webvpn_<账号>`）
     * 各认证一次、各存一份 cookie。校外用户因此要过两次网关认证，可能被要求两次 MFA；
     * 且两边谁都看不见对方的 TGC，SSO 免密路径互相作废。
     *
     * 现在浏览器路径与业务路径共用同一个 WEBVPN backend：
     * - 网关认证 → [com.xjtu.toolbox.auth.SessionManager.ensureWebVpnLogin]（内含 backend.loginLock
     *   串行、密码熔断、登录冷却、统一的 App 内 MFA 弹窗）
     * - TGC 免密 → 该 backend 的 jar 里若已有 TGC，XJTULogin.init 直接 SSO 直通，一次密码都不提交
     */
    suspend fun loginWebVpn(): Boolean {
        val mgr = sessionManager ?: run { android.util.Log.w("WebVPN", "No sessionManager"); return false }
        if (!hasCredentials) { android.util.Log.w("WebVPN", "No credentials"); return false }
        if (webVpnClientOrNull != null) { android.util.Log.d("WebVPN", "Already logged in"); return true }
        if (passwordInvalidatedLatch) { android.util.Log.d("WebVPN", "halted by password latch"); return false }
        return try {
            mgr.ensureWebVpnLogin()
            val ok = webVpnClientOrNull != null
            android.util.Log.d("WebVPN", "loginWebVpn via SessionManager: ok=$ok")
            ok
        } catch (e: Exception) {
            android.util.Log.w("WebVPN", "loginWebVpn failed: ${e.message}")
            false
        }
    }


    /**
     * 兼容兜底登出。多账号架构下请优先用 [com.xjtu.toolbox.account.AccountManager.logoutCurrent]，
     * 它会额外切换到 default 命名空间并清 AccountStore 激活指针。此方法仅在无 AccountManager 引用时使用。
     */
    fun logout(store: CredentialStore? = null) {
        // 停止后台保活循环
        com.xjtu.toolbox.auth.SessionKeepAlive.stop()
        // 清当前账号命名空间的 cookies（SessionManager 的 backends 已绑定当前账号 jar）
        runCatching {
            sessionManager?.backend(com.xjtu.toolbox.auth.AccessMode.NORMAL)?.clearAuth()
            sessionManager?.backend(com.xjtu.toolbox.auth.AccessMode.WEBVPN)?.clearAuth()
        }
        clearInMemorySessionState()
        com.xjtu.toolbox.account.AccountContext.activeAccountId = null
        // 兼容：清旧单值凭据（迁移期向后兼容）
        store?.clear()
    }
}

/**
 * 全局 AppLoginState 入口。Screen 通过 `LocalAppLoginState.current` 拿到 loginState，
 * 用于在 catch AuthExpiredException 时调用 `markStaleAndRetry(type)` 静默重新登录。
 *
 * 由 AppNavigation 顶层 CompositionLocalProvider 提供。
 */
val LocalAppLoginState = staticCompositionLocalOf<AppLoginState> {
    error("LocalAppLoginState not provided. Wrap with CompositionLocalProvider in AppNavigation.")
}

// ── ViewModel：状态不因 Configuration Change（旋转 / 深色切换）而丢失 ──

class AppLoginStateViewModel(application: android.app.Application) : androidx.lifecycle.AndroidViewModel(application) {
    val loginState = AppLoginState()
    val credentialStore = CredentialStore(application)
    val accountStore = com.xjtu.toolbox.account.AccountStore(application)
    // [已移除] persistentCookieJar / vpnCookieJar（物理文件 xjtu_cookies）：
    // 它们是旧体系的 cookie 存储，唯一的使用者 doLoginWebVpn 已随 WebVPN 统一而删除。
    // cookies 现在只有一处：SessionManager 的 cookies_normal_<账号> / cookies_webvpn_<账号>。

    /** 新会话架构入口：双 backend、SiteSession 注册中心、MFA 状态机宿主。 */
    val sessionManager = com.xjtu.toolbox.auth.SessionManager(application)

    /** 多账号编排器。 */
    val accountManager = com.xjtu.toolbox.account.AccountManager(application, accountStore, credentialStore)

    init {
        // 注入会话管家（无需 LaunchedEffect，ViewModel 创建时即完成）
        loginState.sessionManager = sessionManager
        // 注册所有业务子系统
        with(sessionManager) {
            register(com.xjtu.toolbox.auth.JwxtSession())
            register(com.xjtu.toolbox.auth.JwappSession())
            register(com.xjtu.toolbox.auth.YwtbSession())
            register(com.xjtu.toolbox.auth.LibrarySession())
            register(com.xjtu.toolbox.auth.LmsSession())
            register(com.xjtu.toolbox.auth.ClassSession())
            register(com.xjtu.toolbox.auth.JiaocaiSession())
            register(com.xjtu.toolbox.auth.CouponSession())
            register(com.xjtu.toolbox.auth.DzpzSession())
            register(com.xjtu.toolbox.auth.VenueSession())
            register(com.xjtu.toolbox.auth.GmisSession())
            register(com.xjtu.toolbox.auth.GsteSession())
            register(com.xjtu.toolbox.auth.AttendanceSession(isPostgraduate = false))
            register(com.xjtu.toolbox.auth.AttendanceSession(isPostgraduate = true))
            register(com.xjtu.toolbox.auth.CampusCardSession())
            register(com.xjtu.toolbox.auth.SuperAppSession())
            register(com.xjtu.toolbox.auth.FitnessSession())
            register(com.xjtu.toolbox.auth.IclassfaceSession())
            register(com.xjtu.toolbox.auth.HelloSession())
            register(com.xjtu.toolbox.jiaoxiaozhi.JiaoxiaozhiSiteSession())
        }
        // 绑定 AccountManager 到 sessionManager + loginState
        accountManager.sessionManager = sessionManager
        accountManager.holder = loginState

        // 保活接回新架构：每轮对已登录站点做免密 SSO 续期（静默，撞 MFA 即退出）。
        // 旧的 LoginProvider 只返回空列表，循环等于空转，会话该凉还是凉。
        com.xjtu.toolbox.auth.SessionKeepAlive.sessionRefresher = {
            sessionManager.refreshLoggedInSites()
        }

        // 一次性迁移旧单账号数据 → 首个 Account 命名空间
        val migrated = com.xjtu.toolbox.account.AccountMigration
            .runIfNeeded(application, accountStore, credentialStore)

        // 恢复激活账号身份（从 AccountStore，而非旧 CredentialStore 单值）
        // 先恢复身份，再补 fp，确保 ensureStableFpVisitorId 能正确读到当前 accountId 并同步内存态。
        restoreActiveAccount(migrated)

        // 设备指纹：仅当激活账号仍缺 fpVisitorId 时基于设备 + accountId 派生一个稳定值
        ensureStableFpVisitorId(migrated)
    }

    private fun restoreActiveAccount(migrated: com.xjtu.toolbox.account.Account?) {
        val active = migrated ?: accountStore.activeAccount()
        if (active != null) {
            // 用当前账号命名空间重建 backends（复用其磁盘 cookies）
            val suffix = "_" + active.accountId.replace(Regex("[^a-zA-Z0-9]"), "_")
            sessionManager.reconfigureForAccount(suffix)
            loginState.loadIdentityFromAccount(active)
        } else {
            // 无账号：保持默认 backends（匿名 _default），等用户登录
            com.xjtu.toolbox.account.AccountContext.activeAccountId = null
        }
    }

    private fun ensureStableFpVisitorId(migrated: com.xjtu.toolbox.account.Account?) {
        // 多账号模式下 fpVisitorId 存在 Account 记录里，按账号独立。
        // 仅当某账号缺 fp 时基于设备派生一个；迁移路径已在 AccountMigration 把旧 fp 带入。
        val active = migrated ?: accountStore.activeAccount() ?: return
        if (!active.fpVisitorId.isNullOrBlank()) return
        val androidId = try {
            android.provider.Settings.Secure.getString(
                getApplication<android.app.Application>().contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            ) ?: ""
        } catch (_: Exception) { "" }
        val seed = "android|${android.os.Build.MANUFACTURER}|${android.os.Build.MODEL}|$androidId|${active.accountId}"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(seed.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
        accountStore.update(active.accountId) { it.copy(fpVisitorId = hash) }
        // 同步到当前内存态
        if (loginState.accountId == active.accountId) {
            loginState.firstVisitorId = hash
            sessionManager.fpVisitorId = hash
        }
        android.util.Log.d("FpVisitorId", "stable fp generated for account=${active.accountId}")
    }
}

private suspend fun refreshCampusCardCache(
    context: android.content.Context,
    site: com.xjtu.toolbox.auth.SiteSession
): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val appContext = context.applicationContext
    val api = com.xjtu.toolbox.card.CampusCardApi(site)
    val info = api.getCardInfo()
    val (_, recentTx) = api.getTransactions(page = 1, pageSize = 50)
    val todayStr = java.time.LocalDate.now().toString()
    val todaySpend = recentTx
        .filter { tx -> tx.time.startsWith(todayStr) && tx.amount < 0 }
        .sumOf { tx -> -tx.amount }
    val todayBreakfast = recentTx.filter { tx ->
        tx.time.startsWith(todayStr) && tx.amount < 0 &&
            tx.time.substringAfter(" ").substringBefore(":").toIntOrNull()?.let { h -> h in 5..10 } == true
    }.sumOf { tx -> -tx.amount }
    val todayLunch = recentTx.filter { tx ->
        tx.time.startsWith(todayStr) && tx.amount < 0 &&
            tx.time.substringAfter(" ").substringBefore(":").toIntOrNull()?.let { h -> h in 11..14 } == true
    }.sumOf { tx -> -tx.amount }
    val todayDinner = recentTx.filter { tx ->
        tx.time.startsWith(todayStr) && tx.amount < 0 &&
            tx.time.substringAfter(" ").substringBefore(":").toIntOrNull()?.let { h -> h in 17..21 } == true
    }.sumOf { tx -> -tx.amount }

    com.xjtu.toolbox.card.CampusCardCache.cardPrefs(appContext).edit()
        .putFloat("card_balance_cache", info.balance.toFloat())
        .putString("card_name_cache", info.name)
        .putLong("card_cache_time", System.currentTimeMillis())
        .putString("card_recent_tx_cache", com.google.gson.Gson().toJson(recentTx.take(5)))
        .putFloat("card_today_spend_cache", todaySpend.toFloat())
        .putFloat("card_today_breakfast_cache", todayBreakfast.toFloat())
        .putFloat("card_today_lunch_cache", todayLunch.toFloat())
        .putFloat("card_today_dinner_cache", todayDinner.toFloat())
        .apply()
    CampusCardWidgetUpdater.requestUpdate(appContext)
    true
}

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
    val navController = rememberNavController()
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
        navController.navigate(Routes.MAIN) {
            launchSingleTop = true
            popUpTo(Routes.MAIN) { inclusive = false }
        }
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
                val recent = credentialStore.recentSiteKeys
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

    // 网络变化监听：WiFi / 移动数据 / 校园网切换时重新探测 access mode、后台预热新 mode 的 session。
    //
    // 波动稳健策略：
    // - **3 秒防抖**：等网络真正稳定，避免短时间内反复回调触发探测
    // - **二次确认**：detectCampusNetwork 自身已做二次确认（间隔 1.5s），单次失败不算 mode 变化
    // - **主动清失效缓存**：mode 真变化时清掉所有 cached login + vpnClient（旧 client 已不可用）
    // - **当前 Screen 自动重登**：用户正在使用某需登录 Screen 时，触发 markStaleAndRetry
    //   → nav 自动 popBack + 重新进入，全程对用户透明（仅显示一闪而过的 autoLoginSheet）
    // - **后台预热**：mode 真变化时启动 background warmup，预热其它子系统
    val networkScope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        val connectivityManager = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        var networkCheckJob: kotlinx.coroutines.Job? = null
        // Route → LoginType 映射（仅含需要登录的 Screen 路由）
        val routeToLoginType: (String) -> LoginType? = { loginTypeForRoute(it) }
        fun trigger(reason: String) {
            if (!loginState.isLoggedIn) return
            networkCheckJob?.cancel()
            networkCheckJob = networkScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                kotlinx.coroutines.delay(3000L)  // 3 秒防抖：等网络真正稳定
                try {
                    android.util.Log.d("Network", "Network changed ($reason), re-evaluating access mode after 3s settle")
                    val modeChanged = loginState.onNetworkChanged()
                    if (modeChanged) {
                        // 当前正在 active Screen 内 → 触发 markStaleAndRetry，让 nav 自动重新登录并重新进入。
                        // 这样用户切换网络时正在使用的 Screen 会无缝重新加载，而不是看到一闪而过的离线/错误。
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            val currentRoute = navController.currentBackStackEntry?.destination?.route
                            val activeType = currentRoute?.let(routeToLoginType)
                            if (activeType != null && currentRoute != null) {
                                android.util.Log.d("Network", "Mode changed while on $currentRoute → markStaleAndRetry($activeType)")
                                loginState.markStaleAndRetry(activeType, currentRoute)
                            }
                        }
                        // [policy] 不再 startBackgroundLoginWarmup：网络抖动时主动 autoLogin 会触发
                        // webvpn re-login → MFA detect 弹窗，违反「用户主动才认证」约定。
                        // 用户进入对应 Screen 时按需 autoLogin 即可。
                    }
                } catch (e: Exception) {
                    android.util.Log.w("Network", "Network callback error: ${e.message}")
                }
            }
        }
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) { trigger("onAvailable") }
            override fun onLost(network: android.net.Network) { trigger("onLost") }
            override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) {
                // WiFi 切换、VPN 启停等也会 fire 这个事件
                trigger("onCapabilitiesChanged")
            }
        }
        val networkRequest = android.net.NetworkRequest.Builder()
            .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager?.registerNetworkCallback(networkRequest, callback)
        onDispose {
            try { connectivityManager?.unregisterNetworkCallback(callback) } catch (_: Exception) {}
        }
    }

    // ── Srun 校园网（XJTU_STU）自动登录管理器 ──
    DisposableEffect(Unit) {
        val mgr = com.xjtu.toolbox.srun.SrunAutoLoginManager(
            context = context,
            credentialStore = credentialStore,
            onResult = { result ->
                val msg = when (result) {
                    is com.xjtu.toolbox.srun.SrunAutoLoginManager.Result.Success ->
                        "校园网已自动登录"
                    is com.xjtu.toolbox.srun.SrunAutoLoginManager.Result.Failed ->
                        "校园网自动登录失败：${result.message}"
                    else -> null
                }
                msg?.let {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
        mgr.register()
        onDispose { mgr.unregister() }
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
                    loginState.onNetworkChanged()
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
            navController.navigate(Routes.ACCOUNTS) { launchSingleTop = true }
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
    if (showUpdateNotice.value) {
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

    // ── 启动时自动检查更新（根据用户设置） ──
    val autoUpdateCheckDone = remember { mutableStateOf(false) }
    // 自动更新弹窗状态
    var autoUpdateVersion by remember { mutableStateOf("") }
    var autoUpdateBody by remember { mutableStateOf("") }
    var autoUpdateDownloadUrl by remember { mutableStateOf("") }
    var autoUpdateReleaseUrl by remember { mutableStateOf("") }
    var autoUpdateChannelKey by remember { mutableStateOf("") }
    var autoUpdateChannel by remember { mutableStateOf("") }
    val showAutoUpdateDialog = remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (autoUpdateCheckDone.value) return@LaunchedEffect
        if (!credentialStore.autoCheckUpdate) return@LaunchedEffect
        val now = System.currentTimeMillis()
        if (now - credentialStore.lastAutoUpdateCheckAt < com.xjtu.toolbox.util.AppUpdater.AUTO_CHECK_INTERVAL_MS) {
            return@LaunchedEffect
        }
        autoUpdateCheckDone.value = true
        try {
            val update = com.xjtu.toolbox.util.AppUpdater.check(credentialStore.updateChannel)
            // 只有请求成功才记冷却：失败下次冷启动还会再试，避免「看起来像没检查」。
            credentialStore.lastAutoUpdateCheckAt = System.currentTimeMillis()
            if (update == null) return@LaunchedEffect
            if (credentialStore.isUpdateNoticeSeen("auto_${update.channel}_${update.version}")) return@LaunchedEffect
            autoUpdateVersion = update.version
            autoUpdateBody = update.notes
            autoUpdateDownloadUrl = update.downloadUrl
            autoUpdateReleaseUrl = update.releaseUrl
            autoUpdateChannelKey = update.channel
            autoUpdateChannel = update.channelLabel
            showAutoUpdateDialog.value = true
        } catch (e: Exception) {
            android.util.Log.w("AppUpdater", "startup update check failed", e)
        }
    }

    // ── 自动更新弹窗 ──
    if (showAutoUpdateDialog.value) {
        AutoUpdateDialog(
            version = autoUpdateVersion,
            body = autoUpdateBody,
            downloadUrl = autoUpdateDownloadUrl,
            releaseUrl = autoUpdateReleaseUrl,
            channelLabel = autoUpdateChannel,
            onDismiss = {
                credentialStore.markUpdateNoticeSeen("auto_${autoUpdateChannelKey}_${autoUpdateVersion}")
                showAutoUpdateDialog.value = false
            }
        )
    }

    CompositionLocalProvider(LocalAppLoginState provides loginState) {
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
    NavHost(
        navController = navController,
        startDestination = Routes.MAIN,
        enterTransition = {
            // 正向进入：从右侧滑入
            slideInHorizontally(spring(dampingRatio = 0.86f, stiffness = 500f)) { it } +
            fadeIn(animationSpec = spring(dampingRatio = 0.86f, stiffness = 500f))
        },
        exitTransition = {
            // 正向退出：旧页面向左推移并轻微淡出
            slideOutHorizontally(spring(dampingRatio = 0.86f, stiffness = 500f)) { -it / 4 } +
            fadeOut(animationSpec = spring(dampingRatio = 0.86f, stiffness = 500f), targetAlpha = 0.5f)
        },
        popEnterTransition = {
            // 返回进入：上一页从左侧恢复
            slideInHorizontally(spring(dampingRatio = 0.86f, stiffness = 500f)) { -it / 4 } +
            fadeIn(animationSpec = spring(dampingRatio = 0.86f, stiffness = 500f), initialAlpha = 0.5f)
        },
        popExitTransition = {
            // 返回退出：当前页向右滑出，不含 fadeOut（避免手势拖拽时淡化）
            slideOutHorizontally(spring(dampingRatio = 0.86f, stiffness = 500f)) { it }
        }
    ) {

        composable(Routes.MAIN) {
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
                showQuickActions = showQuickActions
            )
        }

        composable(Routes.EMPTY_ROOM) {
            val direct = loginState.sessionManager?.getSiteOrNull("jwxt")?.client
            EmptyRoomScreen(
                onBack = { navController.popBackStack() },
                directClient = direct,
            )
        }
        composable(Routes.NOTIFICATION) {
            NotificationScreen(
                onBack = { navController.popBackStack() },
                onNavigate = {
                    if (it == Routes.SCHEDULE) {
                        navigateToMainTab(BottomTab.COURSES)
                    } else {
                        navController.navigate(it) { launchSingleTop = true }
                    }
                }
            )
        }
        composable(Routes.ATTENDANCE) {
            loginState.sessionManager?.getSiteOrNull("attendance")?.let { AttendanceScreen(site = it, onBack = { navController.popBackStack() }) } ?: LaunchedEffect(Unit) { navController.popBackStack() }
        }
        composable(Routes.POSTGRADUATE_ATTENDANCE) {
            loginState.sessionManager?.getSiteOrNull("pg_attendance")?.let { AttendanceScreen(site = it, onBack = { navController.popBackStack() }) } ?: LaunchedEffect(Unit) { navController.popBackStack() }
        }
        composable(Routes.SCHEDULE) {
            LaunchedEffect(Unit) {
                navigateToMainTab(BottomTab.COURSES)
            }
        }
        composable(Routes.JWAPP_SCORE) {
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
        composable(Routes.JUDGE) {
            loginState.sessionManager?.getSiteOrNull("jwxt")?.let { JudgeScreen(site = it, username = loginState.activeUsername, onBack = { navController.popBackStack() }) } ?: LaunchedEffect(Unit) { navController.popBackStack() }
        }
        composable(Routes.LIBRARY) {
            loginState.sessionManager?.getSiteOrNull("library")?.let { LibraryScreen(site = it, onBack = { navController.popBackStack() }) } ?: LaunchedEffect(Unit) { navController.popBackStack() }
        }
        composable(Routes.CAMPUS_CARD) {
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
        composable(Routes.COUPON) {
            loginState.sessionManager?.getSiteOrNull("coupon")?.let { com.xjtu.toolbox.coupon.CouponScreen(site = it, onBack = { navController.popBackStack() }) } ?: LaunchedEffect(Unit) { navController.popBackStack() }
        }
        dialog(
            Routes.PAYMENT_CODE,
            dialogProperties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            // 付款码必须在校园卡登录后使用（复用 ncard JWT 访问 /berserker-app/authCode）
            var cardSite by remember { mutableStateOf(loginState.sessionManager?.getSiteOrNull("campus_card")) }
            val readyCard = cardSite
            if (readyCard != null) {
                com.xjtu.toolbox.pay.PaymentCodeDialog(site = readyCard) { navController.popBackStack() }
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
        composable(Routes.SCORE_REPORT) {
            loginState.sessionManager?.getSiteOrNull("jwxt")?.let { ScoreReportScreen(site = it, studentId = loginState.activeUsername, onBack = { navController.popBackStack() }) } ?: LaunchedEffect(Unit) { navController.popBackStack() }
        }
        composable(Routes.TRANSCRIPT) {
            loginState.sessionManager?.getSiteOrNull("dzpz")?.let { com.xjtu.toolbox.dzpz.TranscriptScreen(site = it, onBack = { navController.popBackStack() }) } ?: LaunchedEffect(Unit) { navController.popBackStack() }
        }
        composable(Routes.VENUE) {
            loginState.sessionManager?.getSiteOrNull("venue")?.let {
                com.xjtu.toolbox.venue.VenueScreen(
                    site = it,
                    credentialStore = credentialStore,
                    onBack = { navController.popBackStack() }
                )
            } ?: LaunchedEffect(Unit) { navController.popBackStack() }
        }
        composable(Routes.CLASS_REPLAY) {
            loginState.sessionManager?.getSiteOrNull("class")?.let { classSite ->
                val context = androidx.compose.ui.platform.LocalContext.current
                com.xjtu.toolbox.classreplay.ClassScreen(
                    site = classSite,
                    onBack = { navController.popBackStack() },
                    onDownloadReplay = { activityIds, videoSources ->
                        // 启动下载流程
                        val appContext = context.applicationContext
                        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
                        scope.launch {
                            try {
                                val downloadManager = com.xjtu.toolbox.classreplay.DownloadManager.getInstance(appContext)
                                
                                // 获取课程名称和回放详情
                                val activities = activityIds.mapNotNull { id ->
                                    try {
                                        val detail = com.xjtu.toolbox.classreplay.fetchReplayDetail(classSite, id)
                                        detail?.let { id to it }
                                    } catch (e: Exception) {
                                        android.util.Log.e("MainActivity", "Failed to fetch detail for $id", e)
                                        null
                                    }
                                }
                                
                                val courseName = "课程回放"
                                
                                // 为每个活动创建下载任务
                                for ((activityId, detail) in activities) {
                                    if (detail.replayVideos.isNotEmpty()) {
                                        // 只下用户勾选的机位。videoSources 为空时才退回全部，
                                        // 避免上游万一没传导致一个都下不到。
                                        val wanted = detail.replayVideos.filter {
                                            videoSources.isEmpty() || it.cameraType in videoSources
                                        }
                                        val videos = wanted.mapNotNull { video ->
                                            val realUrl = com.xjtu.toolbox.classreplay.resolveVideoUrl(classSite, video.url)
                                            realUrl?.let {
                                                com.xjtu.toolbox.classreplay.DownloadManager.DownloadItem(
                                                    cameraType = video.cameraType,
                                                    url = it,
                                                )
                                            }
                                        }
                                        
                                        if (videos.isNotEmpty()) {
                                            downloadManager.enqueueDownloads(
                                                courseName = courseName,
                                                activityTitle = detail.title,
                                                activityId = activityId,
                                                videos = videos
                                            )
                                        }
                                    }
                                }
                                
                                // 显示提示
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    android.widget.Toast.makeText(
                                        appContext,
                                        "已开始下载 ${activities.size} 个回放",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("MainActivity", "Download error", e)
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    android.widget.Toast.makeText(
                                        context.applicationContext,
                                        "下载失败: ${e.message}",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    }
                )
            } ?: LaunchedEffect(Unit) { navController.popBackStack() }
        }
        composable(Routes.DOWNLOAD_MANAGER) {
            com.xjtu.toolbox.classreplay.DownloadManagerScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.LMS) {
            loginState.sessionManager?.getSiteOrNull("lms")?.let { site ->
                com.xjtu.toolbox.lms.LmsScreen(
                    site = site,
                    onBack = { navController.popBackStack() }
                )
            } ?: LaunchedEffect(Unit) { navController.popBackStack() }
        }
        composable(Routes.JIAOCAI) {
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
        composable(Routes.JIAOCAI1) {
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
        composable(
            Routes.JIAOCAI1_READER,
            arguments = listOf(
                navArgument("ssno") { type = NavType.StringType },
                navArgument("title") { type = NavType.StringType; defaultValue = "" },
            )
        ) { backStackEntry ->
            val ssno = backStackEntry.arguments?.getString("ssno").orEmpty()
            val title = try {
                java.net.URLDecoder.decode(backStackEntry.arguments?.getString("title") ?: "", "UTF-8")
            } catch (_: Exception) {
                backStackEntry.arguments?.getString("title").orEmpty()
            }
            loginState.sessionManager?.getSiteOrNull("jiaocai")?.let {
                com.xjtu.toolbox.jiaocai1.Jiaocai1ReaderScreen(
                    site = it,
                    ssno = ssno,
                    fallbackTitle = title,
                    onBack = { navController.popBackStack() },
                )
            } ?: LaunchedEffect(Unit) { navController.popBackStack() }
        }
        composable(Routes.SCHOOL_COURSE) {
            com.xjtu.toolbox.schedule.SchoolCourseScreen(
                site = loginState.sessionManager?.getSiteOrNull("jwxt"),
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.SCHOOL_CALENDAR) {
            com.xjtu.toolbox.calendar.SchoolCalendarScreen(
                site = loginState.sessionManager?.getSiteOrNull("jwxt"),
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.YELLOW_PAGE) {
            com.xjtu.toolbox.yellowpage.YellowPageScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.MOBILE_JIAODA) {
            loginState.sessionManager?.getSiteOrNull("super_app")?.let {
                com.xjtu.toolbox.superapp.MobileJiaodaScreen(
                    site = it,
                    onClose = { navController.popBackStack() }
                )
            } ?: LaunchedEffect(Unit) { navController.popBackStack() }
        }
        composable(Routes.FITNESS) {
            loginState.sessionManager?.getSiteOrNull("fitness")?.let {
                com.xjtu.toolbox.fitness.FitnessScreen(
                    site = it,
                    onBack = { navController.popBackStack() }
                )
            } ?: LaunchedEffect(Unit) { navController.popBackStack() }
        }
        composable(Routes.ICLASSFACE) {
            loginState.sessionManager?.getSiteOrNull("iclassface")?.let {
                com.xjtu.toolbox.iclassface.IclassfaceScreen(
                    site = it,
                    onBack = { navController.popBackStack() }
                )
            } ?: LaunchedEffect(Unit) { navController.popBackStack() }
        }
        composable(Routes.JIAOXIAOZHI) {
            com.xjtu.toolbox.jiaoxiaozhi.JiaoxiaozhiScreen(
                onBack = { navController.popBackStack() },
                onOpenLink = { url -> navController.navigate(Routes.browser(url)) }
            )
        }
        composable(
            Routes.VIDEO_PLAYER,
            arguments = listOf(navArgument("activityId") { type = NavType.IntType })
        ) { backStackEntry ->
            val activityId = backStackEntry.arguments?.getInt("activityId") ?: 0
            loginState.sessionManager?.getSiteOrNull("class")?.let { classSite ->
                com.xjtu.toolbox.classreplay.VideoPlayerScreen(
                    site = classSite,
                    activityId = activityId,
                    onBack = { navController.popBackStack() }
                )
            } ?: LaunchedEffect(Unit) { navController.popBackStack() }
        }
        composable(Routes.BROWSER,
            arguments = listOf(navArgument("url") { type = NavType.StringType; defaultValue = "" })
        ) { backStackEntry ->
            val url = try { java.net.URLDecoder.decode(backStackEntry.arguments?.getString("url") ?: "", "UTF-8") } catch (_: Exception) { "" }
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
        composable(Routes.SETTINGS) {
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
                onOpenFeedback = { navController.navigate(Routes.FEEDBACK) },
                onAccountTypeChanged = { type ->
                    loginState.accountType = type
                    loginState.sessionManager?.accountType =
                        if (type == com.xjtu.toolbox.auth.AccountType.POSTGRADUATE) {
                            com.xjtu.toolbox.auth.XJTULogin.AccountType.POSTGRADUATE
                        } else {
                            com.xjtu.toolbox.auth.XJTULogin.AccountType.UNDERGRADUATE
                        }
                    val id = loginState.accountId
                    if (id.isNotEmpty()) {
                        val store = com.xjtu.toolbox.account.AccountStore(context)
                        store.get(id)?.let { store.upsert(it.copy(accountType = type)) }
                    }
                }
            )
        }

        // ── 用户反馈 ──
        composable(Routes.FEEDBACK) {
            FeedbackScreen(
                onBack = { navController.popBackStack() }
            )
        }

        // ── 教师主页检索 ──
        // 无需登录：faculty.xjtu.edu.cn 与 gr.xjtu.edu.cn 都是公开站点，
        // 因此这里不接 SessionManager，也不做 ensureSite。
        composable(Routes.FACULTY) {
            com.xjtu.toolbox.faculty.FacultyScreen(
                onBack = { navController.popBackStack() },
                onOpenUrl = { url -> navController.navigate(Routes.browser(url)) },
            )
        }

        // ── 账号管理页 ──
        composable(Routes.ACCOUNTS) {
            com.xjtu.toolbox.account.AccountManagerScreen(
                accountManager = viewModel.accountManager,
                loginState = loginState,
                onBack = { navController.popBackStack() }
            )
        }

        // ── WebVPN 网址互转 ──
        composable(Routes.WEBVPN_CONVERTER) {
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
        composable(Routes.AGENT) {
            com.xjtu.toolbox.agent.AgentScreen(
                onBack = { navController.popBackStack() },
                onNavigate = { route -> navController.navigate(route) { launchSingleTop = true } }
            )
        }
    }
    }  // CompositionLocalProvider
}

// ── 主屏幕（底部导航栏）──────────────────

@Composable
private fun MainScreen(
    navController: NavHostController,
    loginState: AppLoginState,
    credentialStore: CredentialStore,
    accountManager: com.xjtu.toolbox.account.AccountManager,
    isRestoring: Boolean = false,
    restoreStep: String = "",
    restoreGateReady: Boolean = true,
    pendingTab: String? = null,
    onPendingTabConsumed: () -> Unit = {},
    pendingLaunchRoute: String? = null,
    onPendingLaunchConsumed: () -> Unit = {},
    onWarmupRequest: () -> Unit = {},
    homeTheme: String = CredentialStore.THEME_CARD,
    showQuickActions: Boolean = true,
) {
    // 读取设置的默认 Tab
    val defaultTabOrdinal = remember {
        val saved = credentialStore.defaultTab
        BottomTab.entries.indexOfFirst { it.name == saved }.coerceAtLeast(0)
    }
    var selectedTabOrdinal by rememberSaveable { mutableIntStateOf(defaultTabOrdinal) }
    val selectedTab = BottomTab.entries[selectedTabOrdinal.coerceIn(0, BottomTab.entries.size - 1)]
    val navAccountCount = remember(loginState.accountId) { accountManager.accountList().size }

    // 底栏风格
    var navBarStyle by remember { mutableStateOf(credentialStore.navBarStyle) }
    DisposableEffect(Unit) {
        AgentRuntimeHooks.applyNavBarStyle = { v -> navBarStyle = v }
        onDispose { AgentRuntimeHooks.applyNavBarStyle = null }
    }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var lastBackPressTime by remember { mutableLongStateOf(0L) }
    var showGlobalSearch by remember { mutableStateOf(false) }

    LaunchedEffect(pendingTab) {
        val tabName = pendingTab ?: return@LaunchedEffect
        val matched = BottomTab.entries.firstOrNull { it.name == tabName }
        if (matched != null) {
            selectedTabOrdinal = matched.ordinal
        }
        onPendingTabConsumed()
    }

    BackHandler {
        when {
            showGlobalSearch -> showGlobalSearch = false
            selectedTab != BottomTab.HOME -> selectedTabOrdinal = BottomTab.HOME.ordinal
            else -> {
                val now = System.currentTimeMillis()
                if (now - lastBackPressTime < 2000) {
                    (context as? android.app.Activity)?.finishAffinity()
                } else {
                    lastBackPressTime = now
                    android.widget.Toast.makeText(context, "再按一次返回退出", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // 自动登录状态
    val showAutoLoginSheet = remember { mutableStateOf(false) }
    var autoLoginMessage by remember { mutableStateOf("") }
    var autoLoginJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }

    fun switchToTab(tab: BottomTab) {
        selectedTabOrdinal = tab.ordinal
    }

    fun navigateToTarget(target: String) {
        if (target == Routes.SCHEDULE) {
            switchToTab(BottomTab.COURSES)
        } else {
            navController.navigate(target) { launchSingleTop = true }
        }
    }

    fun navigateWithLogin(target: String, type: LoginType) {
        // 记录使用轨迹：下次冷启动据此做免密 SSO 预热（见 startBackgroundLoginWarmup）
        runCatching { credentialStore.recordRecentSite(type.siteKey()) }
        // 维护中的服务：直接提示，不进入页面也不触发登录，避免无谓的认证压力
        if (target in maintenanceRoutes) {
            val label = maintenanceLabels[target] ?: type.label
            scope.launch { snackbarHostState.showSnackbar("$label 学校系统维护中，暂不可用", duration = SnackbarDuration.Short) }
            return
        }
        // 快速网络检测（ConnectivityManager，瞬时，不阻塞）
        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        val isOnline = cm?.activeNetwork != null && cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

        // 离线可用的路由（有本地缓存支持）
        val offlineCapableRoutes = setOf(Routes.SCHEDULE, Routes.JWAPP_SCORE)

        // ── 断网处理（优先于所有登录检查）──
        if (!isOnline) {
            if (target in offlineCapableRoutes) {
                navigateToTarget(target)
                scope.launch { snackbarHostState.showSnackbar("无网络连接，展示已缓存数据", duration = SnackbarDuration.Short) }
            } else {
                scope.launch { snackbarHostState.showSnackbar("该功能需要联网使用，请检查网络连接", duration = SnackbarDuration.Short) }
            }
            return
        }

        fun siteReady(t: LoginType): Boolean =
            loginState.sessionManager?.getSiteOrNull(t.siteKey())?.hasLogin == true

        val forceEnsureOnEnter = type == LoginType.SUPER_APP || type == LoginType.JIAOXIAOZHI
        if (siteReady(type) && !forceEnsureOnEnter) {
            navigateToTarget(target)
        } else if (loginState.hasCredentials) {
            // 用户主动点击：永远允许立即登录（即使刚才取消过 MFA），由用户自己决定再次取消还是验证。
            // 有保存的凭据，尝试自动登录
            showAutoLoginSheet.value = true
            autoLoginMessage = "正在连接${type.label}…"
            val autoLoginTimeoutMs = when (type) {
                LoginType.COUPON,
                LoginType.SUPER_APP,
                LoginType.FITNESS,
                LoginType.JIAOXIAOZHI -> 180_000L
                // 场馆/电子凭证等走「CAS OAuth → org 中转 → 业务站」多跳链路，
                // 叠加 CasGate 限频与 WebVPN 改写后 25s 常不够用，超时即表现为"打不开"。
                else -> 60_000L
            }
            autoLoginJob?.cancel() // 取消旧的登录任务，避免竞态
            autoLoginJob = scope.launch {
                try {
                    val result = kotlinx.coroutines.withTimeoutOrNull(autoLoginTimeoutMs) {
                        // 用户正在等这个页面：豁免站点失败冷却，别让"点了没反应"发生
                        loginState.sessionManager?.ensureSite(type, userInitiated = true)
                    }
                    showAutoLoginSheet.value = false
                    autoLoginJob = null
                    if (result != null) {
                        navigateToTarget(target)
                    } else {
                        // 登录未完成：可能是网络不通 / 密码错误 / 服务故障。
                        // 不再展示「受限请连 WebVPN/校园网」这种迷惑提示，SessionManager 已按网络环境处理。
                        if (target in offlineCapableRoutes) {
                            navigateToTarget(target)
                            scope.launch {
                                snackbarHostState.showSnackbar("${type.label}暂未连通，展示已缓存数据", duration = SnackbarDuration.Short)
                            }
                        } else {
                            scope.launch {
                                snackbarHostState.showSnackbar("${type.label}连接超时，请稍后重试", duration = SnackbarDuration.Short)
                            }
                        }
                    }
                } catch (e: Exception) {
                    showAutoLoginSheet.value = false
                    autoLoginJob = null
                    android.util.Log.e("Login", "ensureSite($type) failed for $target", e)
                    // 登录态失效（reAuth 失败）→ 清站点会话 + 重新 ensureSite（CAS 触发 MFA 时会自动弹窗）
                    if (e is AuthExpiredException) {
                        android.util.Log.w("Login", "AuthExpired for $type, retrying full SiteSession login")
                        loginState.sessionManager?.getSiteOrNull(type.siteKey())?.invalidateLogin()
                        autoLoginMessage = "正在重新登录${type.label}..."
                        showAutoLoginSheet.value = true
                        autoLoginJob = scope.launch {
                            try {
                                val r2 = kotlinx.coroutines.withTimeoutOrNull(autoLoginTimeoutMs) { loginState.sessionManager?.ensureSite(type, userInitiated = true) }
                                showAutoLoginSheet.value = false
                                autoLoginJob = null
                                if (r2 != null) {
                                    navigateToTarget(target)
                                } else {
                                    scope.launch { snackbarHostState.showSnackbar("${type.label}暂未就绪", duration = SnackbarDuration.Short) }
                                }
                            } catch (e2: Exception) {
                                showAutoLoginSheet.value = false
                                autoLoginJob = null
                                scope.launch { snackbarHostState.showSnackbar("${type.label}暂未就绪", duration = SnackbarDuration.Short) }
                            }
                        }
                        return@launch
                    }
                    // 离线可用路由降级
                    if (target in offlineCapableRoutes) {
                        navigateToTarget(target)
                        scope.launch { snackbarHostState.showSnackbar("网络不佳，展示已缓存数据", duration = SnackbarDuration.Short) }
                    } else {
                        val detail = e.message?.take(40)?.takeIf { it.isNotBlank() }
                        val msg = when (e) {
                            is java.io.IOException -> detail ?: "网络不佳，请检查网络连接"
                            else -> detail ?: "${type.label}暂未就绪"
                        }
                        scope.launch { snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short) }
                    }
                }
            }
        } else {
            scope.launch {
                snackbarHostState.showSnackbar("请先登录后使用${type.label}", duration = SnackbarDuration.Short)
            }
        }
    }

    LaunchedEffect(pendingLaunchRoute, restoreGateReady) {
        if (!restoreGateReady) return@LaunchedEffect
        val route = pendingLaunchRoute ?: return@LaunchedEffect
        onPendingLaunchConsumed()
        val type = loginTypeForRoute(route)
        if (type != null) {
            navigateWithLogin(route, type)
        } else {
            navigateToTarget(route)
        }
    }

    // 监听 Screen 内 API 抛 AuthExpiredException 时设置的 pendingRetry：
    // 自动 popBackStack + 重新 navigateWithLogin（含必要的 MFA），整个过程对用户透明。
    LaunchedEffect(loginState.pendingRetry) {
        val req = loginState.pendingRetry ?: return@LaunchedEffect
        loginState.pendingRetry = null
        val (type, route) = req
        // 当前 Screen 已抛异常退出（Screen 内会主动 onBack），navController 应在 main 上层。
        // 给一个短暂 delay 让 popBackStack 动画完成，避免与 navigateWithLogin 抢导航。
        kotlinx.coroutines.delay(200)
        navigateWithLogin(route, type)
    }

    // ── 各 Tab 独立的滚动折叠状态 ──
    val homeScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val coursesScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val toolsScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val profileScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

    // 大屏适配：宽度 ≥ 840dp（Material expanded breakpoint，平板/桌面）启用侧边 NavigationRail
    // 手机横屏/折叠屏内屏（600-839dp）继续用底栏
    val isWideScreen = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 840

    // COURSES tab 副标题 + actions slot + bottomContent slot
    var courseSubtitle by remember { mutableStateOf("") }
    var courseHeaderActions by remember { mutableStateOf<(@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)?>(null) }
    var courseHeaderBottomContent by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = when (selectedTab) {
                    BottomTab.HOME -> "岱宗盒子"
                    BottomTab.COURSES -> "日程"
                    BottomTab.TOOLS -> "仲英学辅资料站"
                    BottomTab.PROFILE -> "我的"
                },
                largeTitle = when (selectedTab) {
                    BottomTab.HOME -> "岱宗盒子"
                    BottomTab.COURSES -> "日程"
                    BottomTab.TOOLS -> "仲英学辅资料站"
                    BottomTab.PROFILE -> "我的"
                },
                subtitle = if (selectedTab == BottomTab.COURSES) courseSubtitle else "",
                scrollBehavior = when (selectedTab) {
                    BottomTab.HOME -> homeScrollBehavior
                    BottomTab.COURSES -> coursesScrollBehavior
                    BottomTab.TOOLS -> toolsScrollBehavior
                    BottomTab.PROFILE -> profileScrollBehavior
                },
                actions = {
                    if (selectedTab == BottomTab.COURSES) {
                        courseHeaderActions?.invoke(this)
                    }
                    // 首页全局搜索入口
                    if (selectedTab == BottomTab.HOME) {
                        IconButton(onClick = { showGlobalSearch = true }) {
                            Icon(
                                androidx.compose.material.icons.Icons.Default.Search,
                                contentDescription = "搜索",
                                tint = MiuixTheme.colorScheme.onSurface
                            )
                        }
                    }
                },
                bottomContent = {
                    if (selectedTab == BottomTab.COURSES) {
                        courseHeaderBottomContent?.invoke()
                    }
                }
            )
        },
        bottomBar = if (!isWideScreen && navBarStyle == "classic") {
            {
                NavigationBar(
                    mode = NavigationBarDisplayMode.IconAndText
                ) {
                    BottomTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = selectedTab == tab,
                            onClick = { selectedTabOrdinal = tab.ordinal },
                            icon = if (selectedTab == tab) tab.selectedIcon else tab.unselectedIcon,
                            label = tab.label,
                            badge = bottomTabBadge(tab, loginState.isLoggedIn, navAccountCount)
                        )
                    }
                }
            }
        } else {
            {}
        },
        floatingToolbar = if (!isWideScreen && navBarStyle == "floating") {
            {
                FloatingNavigationBar(
                    color = MiuixTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(50)),
                ) {
                    BottomTab.entries.forEach { tab ->
                        FloatingNavigationBarItem(
                            selected = selectedTab == tab,
                            onClick = { selectedTabOrdinal = tab.ordinal },
                            icon = if (selectedTab == tab) tab.selectedIcon else tab.unselectedIcon,
                            label = tab.label,
                            badge = bottomTabBadge(tab, loginState.isLoggedIn, navAccountCount)
                        )
                    }
                }
            }
        } else {
            {}
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
        ) {
        androidx.compose.foundation.layout.Row(Modifier.fillMaxSize().padding(padding)) {
        if (isWideScreen) {
            // miuix 0.9.3 起 NavigationRail 去掉了 mode 参数，改为传 state 获得可展开侧栏：
            // 收起态为图标+小字，展开态为「图标 + 文字」横向排布，顶部自带展开/收起按钮。
            // 平板/折叠屏展开后主标签一眼可读，状态经 rememberSaveable 跨旋转与进程重建保留。
            val railState = top.yukonga.miuix.kmp.basic.rememberNavigationRailState()
            top.yukonga.miuix.kmp.basic.NavigationRail(
                color = MiuixTheme.colorScheme.surface,
                state = railState,
                expandContentDescription = "展开导航栏",
                collapseContentDescription = "收起导航栏"
            ) {
                BottomTab.entries.forEach { tab ->
                    top.yukonga.miuix.kmp.basic.NavigationRailItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTabOrdinal = tab.ordinal },
                        icon = if (selectedTab == tab) tab.selectedIcon else tab.unselectedIcon,
                        label = tab.label,
                        badge = bottomTabBadge(tab, loginState.isLoggedIn, navAccountCount)
                    )
                }
            }
        }
        Box(Modifier.fillMaxSize()) {
            // 需要联网的无登录路由（空闲教室、通知公告等纯网络功能）
            val networkRequiredRoutes = setOf(
                Routes.EMPTY_ROOM,
                Routes.NOTIFICATION,
                Routes.YELLOW_PAGE,
                Routes.AGENT
            )
            val onNavigateWithNetCheck: (String) -> Unit = { route ->
                if (route == Routes.SCHEDULE) {
                    switchToTab(BottomTab.COURSES)
                } else if (route in networkRequiredRoutes) {
                    val cm2 = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                    val online = cm2?.activeNetwork != null && cm2.getNetworkCapabilities(cm2.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                    if (online) {
                        navController.navigate(route) { launchSingleTop = true }
                    } else {
                        scope.launch { snackbarHostState.showSnackbar("该功能需要联网使用，请检查网络连接", duration = SnackbarDuration.Short) }
                    }
                } else {
                    navController.navigate(route) { launchSingleTop = true }
                }
            }
            var composedTabs by remember { mutableStateOf(setOf(selectedTab)) }
            LaunchedEffect(selectedTab) { composedTabs = composedTabs + selectedTab }
            var previousTabOrdinal by rememberSaveable { mutableIntStateOf(selectedTabOrdinal) }
            val tabSwitchDirection = when {
                selectedTabOrdinal > previousTabOrdinal -> 1
                selectedTabOrdinal < previousTabOrdinal -> -1
                else -> 0
            }
            LaunchedEffect(selectedTabOrdinal) {
                kotlinx.coroutines.delay(280)
                previousTabOrdinal = selectedTabOrdinal
            }
            val tabSlideDistance = with(androidx.compose.ui.platform.LocalDensity.current) { 28.dp.toPx() }
            Box(Modifier.fillMaxSize()) {
                BottomTab.entries.forEach { tab ->
                    key(tab) {
                        if (tab in composedTabs) {
                            val isActive = selectedTab == tab
                            val tabAlpha by animateFloatAsState(
                                targetValue = if (isActive) 1f else 0f,
                                animationSpec = tween(if (isActive) 240 else 180),
                                label = "tabAlpha"
                            )
                            val tabOffset by animateFloatAsState(
                                targetValue = when {
                                    isActive -> 0f
                                    tabSwitchDirection == 0 -> 0f
                                    tab.ordinal < selectedTabOrdinal -> -tabSlideDistance
                                    else -> tabSlideDistance
                                },
                                animationSpec = tween(260),
                                label = "tabOffset"
                            )
                            val tabScale by animateFloatAsState(
                                targetValue = if (isActive) 1f else 0.985f,
                                animationSpec = tween(260),
                                label = "tabScale"
                            )
                            Box(
                                Modifier.fillMaxSize()
                                    .zIndex(if (isActive) 1f else 0f)
                                    .graphicsLayer {
                                        alpha = tabAlpha
                                        translationX = tabOffset
                                        scaleX = tabScale
                                        scaleY = tabScale
                                    }
                                    .pointerInput(isActive) {
                                        if (!isActive) {
                                            awaitPointerEventScope {
                                                while (true) {
                                                    awaitPointerEvent(PointerEventPass.Initial)
                                                        .changes.forEach { it.consume() }
                                                }
                                            }
                                        }
                                    }
                            ) {
                                when (tab) {
                                    BottomTab.HOME -> HomeTab(
                                        loginState,
                                        isRestoring = isRestoring,
                                        onNavigate = onNavigateWithNetCheck,
                                        onNavigateWithLogin = ::navigateWithLogin,
                                        onNavigateToProfile = { selectedTabOrdinal = BottomTab.PROFILE.ordinal },
                                        onNavigateToCourses = { selectedTabOrdinal = BottomTab.COURSES.ordinal },
                                        scrollBehavior = homeScrollBehavior,
                                        navBarStyle = navBarStyle,
                                        homeTheme = homeTheme,
                                        showQuickActions = showQuickActions
                                    )
                                    BottomTab.COURSES -> CoursesTab(loginState, ::navigateWithLogin, onNavigateWithNetCheck, scrollBehavior = coursesScrollBehavior, navBarStyle = navBarStyle, onSubtitleChange = { courseSubtitle = it }, onActionsChange = { courseHeaderActions = it }, onBottomContentChange = { courseHeaderBottomContent = it })
                                    BottomTab.TOOLS -> ToolsTab(loginState, ::navigateWithLogin, onNavigateWithNetCheck, scrollBehavior = toolsScrollBehavior, navBarStyle = navBarStyle)
                                    BottomTab.PROFILE -> ProfileTab(
                                        loginState,
                                        ::navigateWithLogin,
                                        credentialStore,
                                        accountManager,
                                        scrollBehavior = profileScrollBehavior,
                                        onNavigateToDownloads = { navController.navigate(Routes.DOWNLOAD_MANAGER) },
                                        onNavigateToSettings = { navController.navigate(Routes.SETTINGS) { launchSingleTop = true } },
                                        onNavigateToAccounts = { navController.navigate(com.xjtu.toolbox.Routes.ACCOUNTS) { launchSingleTop = true } },
                                        navBarStyle = navBarStyle,
                                        onWarmupRequest = onWarmupRequest
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 登录恢复非阻塞提示条（底部，不遮挡欢迎卡片）
            androidx.compose.animation.AnimatedVisibility(
                visible = isRestoring,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MiuixTheme.colorScheme.secondaryContainer
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(size = 16.dp, strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            restoreStep.ifEmpty { "正在恢复登录..." },
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }

            // 自动登录弹窗（OverlayDialog 风格统一）
            BackHandler(enabled = showAutoLoginSheet.value) {
                autoLoginJob?.cancel()
                showAutoLoginSheet.value = false
                autoLoginJob = null
            }
            OverlayDialog(
                show = showAutoLoginSheet.value,
                title = "自动登录中",
                summary = autoLoginMessage,
                onDismissRequest = {
                    autoLoginJob?.cancel()
                    showAutoLoginSheet.value = false
                    autoLoginJob = null
                }
            ) {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator()
                    TextButton(
                        text = "取消",
                        onClick = {
                            autoLoginJob?.cancel()
                            showAutoLoginSheet.value = false
                            autoLoginJob = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // ── 新会话架构 MFA 对话框（来自 SessionManager.askMfaCode）──
            val sessionMfaState = loginState.sessionManager?.activeMfaRequest?.collectAsState()
            sessionMfaState?.value?.let { req ->
                var phone by remember(req) { mutableStateOf("") }
                var codeInput by remember(req) { mutableStateOf("") }
                var sending by remember(req) { mutableStateOf(false) }
                var codeSent by remember(req) { mutableStateOf(false) }
                var verifying by remember(req) { mutableStateOf(false) }
                var err by remember(req) { mutableStateOf<String?>(null) }
                LaunchedEffect(req) {
                    sending = true
                    try {
                        phone = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            req.mfaContext.getPhoneNumber()
                        }
                        codeSent = true
                    } catch (e: Exception) {
                        err = "获取验证手机号失败：${e.message}"
                    }
                    sending = false
                }
                BackHandler(enabled = true) { req.cancel() }
                OverlayDialog(
                    show = true,
                    title = "两步验证",
                    summary = "登录「${req.siteName}」需要短信验证码",
                    onDismissRequest = { req.cancel() }
                ) {
                    Column(
                        Modifier.fillMaxWidth().imePadding(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            if (phone.isNotEmpty()) "验证码已发送至 $phone" else "正在获取手机号…",
                            style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        if (codeSent) {
                            TextField(
                                value = codeInput,
                                onValueChange = { codeInput = it.take(6); err = null },
                                label = "6位验证码",
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                        }
                        err?.let {
                            Text(it, color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.footnote1)
                        }
                        if (codeSent) {
                            TextButton(
                                text = if (verifying) "验证中…" else "验证并登录",
                                onClick = {
                                    if (codeInput.length != 6) { err = "请输入6位验证码"; return@TextButton }
                                    verifying = true; err = null
                                    if (!req.submit(codeInput)) {
                                        err = "提交失败"
                                        verifying = false
                                    }
                                },
                                enabled = !verifying,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.textButtonColorsPrimary()
                            )
                        } else if (sending) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("准备中…", style = MiuixTheme.textStyles.body1)
                            }
                        }
                    }
                }
            }

            // ── 密码失效弹窗 ─────────────────────────────────────────
            if (loginState.passwordInvalidatedDialogVisible) {
                BackHandler(enabled = true) { loginState.passwordInvalidatedDialogVisible = false }
                OverlayDialog(
                    show = true,
                    title = "登录密码可能已变更",
                    summary = "「${loginState.passwordInvalidatedSiteName}」登录失败，已暂停其他系统的自动登录以保护账号。请在设置中更新密码。",
                    onDismissRequest = { loginState.passwordInvalidatedDialogVisible = false }
                ) {
                    Row(Modifier.fillMaxWidth()) {
                        TextButton(
                            text = "稍后",
                            onClick = { loginState.passwordInvalidatedDialogVisible = false },
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(20.dp))
                        TextButton(
                            text = "去更新密码",
                            onClick = {
                                loginState.passwordInvalidatedDialogVisible = false
                                navController.navigate(Routes.SETTINGS) {
                                    launchSingleTop = true
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                    }
                }
            }
        }
        }
        }

    // 全局搜索覆盖层（跨 tab 共用同一个浮层，渲染优先级高于普通导航）
    if (showGlobalSearch) {
        GlobalSearchScreen(
            onBack = { showGlobalSearch = false },
            onNavigate = { route ->
                showGlobalSearch = false
                val type = loginTypeForRoute(route)
                if (type != null) navigateWithLogin(route, type)
                else navigateToTarget(route)
            },
            onAskAgent = { prompt ->
                showGlobalSearch = false
                AgentPendingPrompt.set(prompt)
                navController.navigate(Routes.AGENT) { launchSingleTop = true }
            },
            accountType = loginState.accountType,
        )
    }
}
}

private fun bottomTabBadge(
    tab: BottomTab,
    isLoggedIn: Boolean,
    accountCount: Int,
): (@Composable () -> Unit)? {
    if (tab != BottomTab.PROFILE) return null
    return when {
        accountCount > 1 -> {
            {
                Badge {
                    Text(accountCount.coerceAtMost(99).toString())
                }
            }
        }
        !isLoggedIn -> {
            { Badge() }
        }
        else -> null
    }
}

// ══════════════════════════════════════════
//  Tab 1 — 首页
// ══════════════════════════════════════════

@Composable
private fun HomeHero(
    greetingName: String,
    dateLabel: String,
    weekNumber: Int,
    isLoggedIn: Boolean,
    isFocusLoaded: Boolean,
    reminder: ScheduleReminderInfo?,
    balance: Float,
    todaySpend: Float,
    onOpenCourses: () -> Unit,
    onOpenCard: () -> Unit,
    onOpenProfile: () -> Unit,
) {
    val hour = java.time.LocalTime.now().hour
    val greeting = when (hour) {
        in 5..10 -> "早上好"
        in 11..13 -> "中午好"
        in 14..17 -> "下午好"
        else -> "晚上好"
    }
    val headline = if (greetingName.isBlank()) greeting else "$greeting，$greetingName"
    val meta = buildString {
        append(dateLabel)
        if (weekNumber in 1..25) append(" · 第${weekNumber}周")
    }
    val primary = MiuixTheme.colorScheme.primary
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val artSize = 128.dp

    val courseTitle: String
    val courseDetail: String?
    when {
        !isLoggedIn -> {
            courseTitle = "登录后查看课表和余额"
            courseDetail = "课表、校园卡会显示在这里"
        }
        !isFocusLoaded -> {
            courseTitle = "正在读取今日安排…"
            courseDetail = null
        }
        reminder != null -> {
            val now = java.time.LocalDateTime.now()
            val minutesUntil = java.time.Duration.between(now, reminder.startAt)
                .toMinutes().coerceAtLeast(0)
            val dayLabel = formatScheduleReminderDateLabel(
                reminder.startAt.toLocalDate(), now.toLocalDate()
            )
            val startLabel = formatMinuteClock(reminder.startAt.hour * 60 + reminder.startAt.minute)
            val endLabel = reminder.endAt?.let {
                formatMinuteClock(it.hour * 60 + it.minute)
            }
            val timePart = if (endLabel != null) "$startLabel–$endLabel" else startLabel
            courseTitle = reminder.name
            courseDetail = buildString {
                append(formatScheduleReminderEta(minutesUntil))
                if (dayLabel != "今天") append(" · $dayLabel")
                append(" · $timePart")
                if (reminder.location.isNotBlank()) append(" · ${reminder.location}")
            }
        }
        else -> {
            courseTitle = "未来两周暂无日程"
            courseDetail = "打开课表看看"
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .squircleClip(CARD_RADIUS)
            .background(AppCardColor),
    ) {
        Box(Modifier.matchParentSize()) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 56.dp, y = (-64).dp)
                    .size(260.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(primary.copy(alpha = 0.20f), Color.Transparent),
                        ),
                    ),
            )
            Image(
                painter = painterResource(R.drawable.home_campus_hero),
                contentDescription = "兴庆校区主楼",
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 8.dp, bottom = 12.dp)
                    .size(artSize),
                contentScale = ContentScale.Fit,
            )
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.linearGradient(
                            listOf(
                                primary.copy(alpha = 0.14f),
                                primary.copy(alpha = 0.04f),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, top = 22.dp, bottom = 22.dp, end = artSize + 8.dp),
        ) {
            Text(
                meta,
                style = MiuixTheme.textStyles.footnote1,
                color = primary,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                headline,
                style = MiuixTheme.textStyles.title3,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(18.dp))
            Column(
                Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = SinkFeedback(),
                    onClick = if (isLoggedIn) onOpenCourses else onOpenProfile,
                ),
            ) {
                Text(
                    courseTitle,
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (courseDetail != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        courseDetail,
                        style = MiuixTheme.textStyles.footnote1,
                        color = muted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (isLoggedIn) {
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = SinkFeedback(),
                        onClick = onOpenCard,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val balanceText = if (balance >= 0f) "¥${"%.2f".format(balance)}" else "—"
                    val spendText = if (todaySpend >= 0f) "¥${"%.2f".format(todaySpend)}" else "—"
                    Text("余额 ", style = MiuixTheme.textStyles.footnote1, color = muted)
                    Text(
                        balanceText,
                        style = MiuixTheme.textStyles.footnote1,
                        fontWeight = FontWeight.Bold,
                        color = if (balance in 0f..30f) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurface,
                    )
                    Text("  ·  今日 ", style = MiuixTheme.textStyles.footnote1, color = muted)
                    Text(
                        spendText,
                        style = MiuixTheme.textStyles.footnote1,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}


@Composable
private fun HomeTab(
    loginState: AppLoginState,
    isRestoring: Boolean = false,
    onNavigate: (String) -> Unit,
    onNavigateWithLogin: (String, LoginType) -> Unit,
    onNavigateToProfile: () -> Unit = {},
    onNavigateToCourses: () -> Unit = {},
    scrollBehavior: ScrollBehavior? = null,
    navBarStyle: String = "floating",
    homeTheme: String = CredentialStore.THEME_CARD,
    showQuickActions: Boolean = true,
) {
    // ── 仪表盘数据：下一节日程 + 校园卡余额缓存（供 Hero 重点信息区使用）──
    val heroContext = LocalContext.current
    var scheduleReminderState by remember { mutableStateOf<ScheduleReminderInfo?>(null) }
    var isScheduleReminderLoaded by remember { mutableStateOf(false) }
    var currentWeekNumber by remember { mutableIntStateOf(0) }
    val cardPrefs = remember(com.xjtu.toolbox.account.AccountContext.activeAccountId) {
        com.xjtu.toolbox.card.CampusCardCache.cardPrefs(heroContext)
    }
    var cachedBalance by remember { mutableStateOf(cardPrefs.getFloat("card_balance_cache", -1f)) }
    var cachedTodaySpend by remember { mutableStateOf(cardPrefs.getFloat("card_today_spend_cache", -1f)) }
    LaunchedEffect(loginState.campusCardCacheVersion) {
        cachedBalance = cardPrefs.getFloat("card_balance_cache", -1f)
        cachedTodaySpend = cardPrefs.getFloat("card_today_spend_cache", -1f)
    }
    LaunchedEffect(loginState.accountId) {
        if (loginState.accountId.isEmpty()) return@LaunchedEffect
        // 账号切换：先清旧账号的提醒与校园卡缓存内存态，再从新账号命名空间重读
        isScheduleReminderLoaded = false
        scheduleReminderState = null
        currentWeekNumber = 0
        cachedBalance = cardPrefs.getFloat("card_balance_cache", -1f)
        cachedTodaySpend = cardPrefs.getFloat("card_today_spend_cache", -1f)
        val loadedFocus = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val dataCache = com.xjtu.toolbox.util.DataCache(heroContext)
                val gson = com.google.gson.Gson()
                val termListJson = dataCache.get("schedule_term_list", Long.MAX_VALUE)
                val termList = if (termListJson != null) {
                    gson.fromJson(termListJson, Array<String>::class.java)?.toList() ?: emptyList()
                } else emptyList<String>()
                val termCode = termList.firstOrNull() ?: return@withContext Pair(null, 0)
                val apiCourses = com.xjtu.toolbox.schedule.ScheduleCache
                    .readOptimizedCourses(dataCache, gson, termCode, Long.MAX_VALUE)
                    ?: com.xjtu.toolbox.schedule.ScheduleCache
                        .readRawCourses(dataCache, gson, termCode, Long.MAX_VALUE)
                    ?: emptyList()
                val customCourses = try {
                    com.xjtu.toolbox.util.AppDatabase.getInstance(heroContext)
                        .customCourseDao().getByTerm(com.xjtu.toolbox.account.AccountContext.activeAccountId ?: "", termCode)
                        .map { it.toCourseItem() }
                } catch (_: Exception) { emptyList() }
                val allSchedules = apiCourses + customCourses
                val startDateJson = dataCache.get("start_date_$termCode", Long.MAX_VALUE)
                val startDateStr = if (startDateJson != null) gson.fromJson(startDateJson, String::class.java) else null
                val startDate = if (!startDateStr.isNullOrBlank()) runCatching { java.time.LocalDate.parse(startDateStr) }.getOrNull() else null
                val today = java.time.LocalDate.now()
                val weekNumber = if (startDate != null) {
                    ((java.time.temporal.ChronoUnit.DAYS.between(startDate, today) / 7) + 1).toInt()
                        .takeIf { it in 1..25 } ?: 0
                } else {
                    0
                }
                if (startDate == null) {
                    return@withContext Pair(null, weekNumber)
                }
                val holidayDates = try {
                    com.xjtu.toolbox.schedule.HolidayApi.getHolidayDates(heroContext)
                } catch (_: Exception) {
                    emptyMap()
                }

                val nowDateTime = java.time.LocalDateTime.now()
                for (offset in 0..14) {
                    val targetDate = today.plusDays(offset.toLong())
                    if (holidayDates.containsKey(targetDate)) continue

                    val targetWeek = ((java.time.temporal.ChronoUnit.DAYS.between(startDate, targetDate) / 7) + 1).toInt()
                    if (targetWeek <= 0) continue
                    val daySchedules = allSchedules
                        .filter { it.dayOfWeek == targetDate.dayOfWeek.value && it.isInWeek(targetWeek) }
                        .map {
                            ScheduleReminderCourseInfo(
                                name = it.courseName,
                                location = it.location,
                                startSection = it.startSection,
                                endSection = it.endSection,
                                startMinuteOfDay = it.startMinuteOfDay,
                                endMinuteOfDay = it.endMinuteOfDay
                            )
                        }
                        .sortedBy {
                            it.resolveStartMinute(
                                com.xjtu.toolbox.util.XjtuTime.isSummerTime(targetDate.monthValue)
                            ) ?: Int.MAX_VALUE
                        }
                    for (schedule in daySchedules) {
                        val targetIsSummer = com.xjtu.toolbox.util.XjtuTime.isSummerTime(targetDate.monthValue)
                        val startMinute = schedule.resolveStartMinute(targetIsSummer) ?: continue
                        val safeStartMinute = startMinute.coerceIn(0, (24 * 60) - 1)
                        val startAt = targetDate.atTime(safeStartMinute / 60, safeStartMinute % 60)
                        if (!startAt.isAfter(nowDateTime)) continue

                        val endMinute = schedule.resolveEndMinute(targetIsSummer)
                        val endAt = endMinute?.let { minuteOfDay ->
                            when {
                                minuteOfDay >= 24 * 60 -> targetDate.plusDays(1).atStartOfDay()
                                minuteOfDay >= 0 -> targetDate.atTime(minuteOfDay / 60, minuteOfDay % 60)
                                else -> null
                            }
                        }
                        return@withContext Pair(
                            ScheduleReminderInfo(
                                name = schedule.name,
                                location = schedule.location,
                                startAt = startAt,
                                endAt = endAt
                            ),
                            weekNumber,
                        )
                    }
                }
                Pair(null, weekNumber)
            } catch (_: Exception) {
                Pair(null, 0)
            }
        }
        scheduleReminderState = loadedFocus.first
        currentWeekNumber = loadedFocus.second
        isScheduleReminderLoaded = true
    }

    Column(
        Modifier
            .fillMaxSize()
            .then(if (scrollBehavior != null) Modifier.nestedScroll(scrollBehavior.nestedScrollConnection) else Modifier)
            .overScrollVertical()
            .verticalScroll(rememberScrollState())
    ) {
        // ── Zone A: 状态信息行（日期 + 系统状态，大标题已移至 TopAppBar）──
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp)
        ) {
            val today = java.time.LocalDate.now()
            val weekDay = today.dayOfWeek.getDisplayName(
                java.time.format.TextStyle.FULL, java.util.Locale.CHINESE
            )
            HomeHero(
                greetingName = loginState.cachedNickname.orEmpty()
                    .ifBlank { loginState.ywtbUserInfo?.userName.orEmpty() }
                    .ifBlank { loginState.activeUsername },
                dateLabel = "${today.monthValue}月${today.dayOfMonth}日 · $weekDay",
                weekNumber = currentWeekNumber,
                isLoggedIn = loginState.isLoggedIn,
                isFocusLoaded = isScheduleReminderLoaded,
                reminder = scheduleReminderState,
                balance = cachedBalance,
                todaySpend = cachedTodaySpend,
                onOpenCourses = onNavigateToCourses,
                onOpenCard = { onNavigateWithLogin(Routes.CAMPUS_CARD, LoginType.CAMPUS_CARD) },
                onOpenProfile = onNavigateToProfile,
            )
            if (loginState.isLoggedIn) {
                Spacer(Modifier.height(10.dp))
                // 网络环境徽标 + 会话数
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (netLabel, netColor) = when (loginState.isOnCampus) {
                        true -> "校园网" to androidx.compose.ui.graphics.Color(0xFF2E7D32)
                        false -> "校外 · WebVPN" to androidx.compose.ui.graphics.Color(0xFF1565C0)
                        null -> "网络检测中" to MiuixTheme.colorScheme.onSurfaceVariantSummary
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = netColor.copy(alpha = 0.12f)
                    ) {
                        Row(
                            Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (loginState.isOnCampus == false) Icons.Default.VpnKey else Icons.Default.Wifi,
                                contentDescription = null,
                                tint = netColor,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                netLabel,
                                style = MiuixTheme.textStyles.footnote2,
                                color = netColor,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    val sessionColor = if ((loginState.sessionManager?.activeSiteCount ?: 0) > 0)
                        androidx.compose.ui.graphics.Color(0xFF2E7D32)
                    else MiuixTheme.colorScheme.onSurfaceVariantSummary
                    val showStatusSheet = remember { mutableStateOf(false) }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = sessionColor.copy(alpha = 0.12f),
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = SinkFeedback()
                        ) { showStatusSheet.value = true }
                    ) {
                        Row(
                            Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = sessionColor,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            // 用全集，避免手写列表漏项与两处不一致
                            val visibleTypes = remember { LoginType.entries.toList() }
                            fun isReady(type: LoginType): Boolean =
                                loginState.sessionManager?.getSiteOrNull(type.siteKey())?.hasLogin == true
                            val ok = visibleTypes.count { isReady(it) }
                            Text(
                                when {
                                    isRestoring -> "正在连接…"
                                    ok > 0 -> "$ok / ${visibleTypes.size} 已就绪"
                                    else -> "未连接"
                                },
                                style = MiuixTheme.textStyles.footnote2,
                                color = sessionColor,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    if (showStatusSheet.value) {
                        BackHandler { showStatusSheet.value = false }
                        OverlayBottomSheet(
                            show = showStatusSheet.value,
                            title = "子系统连接状态",
                            onDismissRequest = { showStatusSheet.value = false }
                        ) {
                            Column(
                                Modifier.fillMaxWidth().navigationBarsPadding()
                                    .heightIn(max = 460.dp)
                                    .verticalScroll(rememberScrollState())   // 子系统较多，弹窗内容需要可滚动。
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                val types = LoginType.entries.toList()
                                types.forEach { t ->
                                    val ready = loginState.sessionManager?.getSiteOrNull(t.siteKey())?.hasLogin == true
                                    Row(
                                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val statusColor = if (ready) androidx.compose.ui.graphics.Color(0xFF2E7D32)
                                        else MiuixTheme.colorScheme.onSurfaceVariantSummary
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
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── 服务分类宫格 ──
        // 分类是数据的一部分，不再是注释 + subList(0,7) 这种靠列表顺序的魔法下标：
        // 那种写法一旦在中间插入服务，后面所有分组会静默错位。
        data class MoreSvc(
            val key: String,
            val icon: ImageVector,
            val title: String,
            val color: androidx.compose.ui.graphics.Color,
            val category: ServiceCategory,
            val onClick: () -> Unit
        )
        val ctx = LocalContext.current
        val homeIcons = mapOf(
            Routes.SCHEDULE to Icons.Default.CalendarMonth,
            Routes.EMPTY_ROOM to Icons.Default.MeetingRoom,
            Routes.LMS to Icons.Default.School,
            Routes.CLASS_REPLAY to Icons.Default.OndemandVideo,
            Routes.SCHOOL_COURSE to Icons.Default.TravelExplore,
            Routes.ATTENDANCE to Icons.Default.EventAvailable,
            Routes.POSTGRADUATE_ATTENDANCE to Icons.AutoMirrored.Filled.FactCheck,
            Routes.ICLASSFACE to Icons.Default.Face,
            Routes.JWAPP_SCORE to Icons.Default.Assessment,
            Routes.JUDGE to Icons.Default.RateReview,
            Routes.JIAOCAI to Icons.AutoMirrored.Filled.MenuBook,
            Routes.JIAOCAI1 to Icons.AutoMirrored.Filled.LibraryBooks,
            Routes.LIBRARY to Icons.Default.Chair,
            Routes.TRANSCRIPT to Icons.Default.Description,
            Routes.NOTIFICATION to Icons.Default.Notifications,
            Routes.FACULTY to Icons.Default.PersonSearch,
            Routes.CAMPUS_CARD to Icons.Default.CreditCard,
            Routes.PAYMENT_CODE to Icons.Default.QrCode,
            Routes.COUPON to Icons.Default.Restaurant,
            Routes.SCHOOL_CALENDAR to Icons.AutoMirrored.Filled.EventNote,
            Routes.VENUE to Icons.Default.Stadium,
            Routes.FITNESS to Icons.AutoMirrored.Filled.DirectionsRun,
            Routes.YELLOW_PAGE to Icons.Default.ContactPhone,
            Routes.WEBVPN_CONVERTER to Icons.Default.VpnKey,
            Routes.MOBILE_JIAODA to Icons.Default.PhoneAndroid,
            Routes.JIAOXIAOZHI to Icons.Default.AutoAwesome,
            Routes.AGENT to Icons.Default.SmartToy,
        )
        val allServices = AppServices.homeFor(loginState.accountType).map { svc ->
            MoreSvc(
                key = svc.route,
                icon = homeIcons[svc.route] ?: Icons.Default.Apps,
                title = svc.title,
                color = com.xjtu.toolbox.ui.theme.legacyColor(svc.route),
                category = svc.category,
                onClick = {
                    when (svc.route) {
                        Routes.SCHEDULE -> onNavigateToCourses()
                        else -> {
                            val login = loginTypeForRoute(svc.route)
                            if (login != null) onNavigateWithLogin(svc.route, login)
                            else onNavigate(svc.route)
                        }
                    }
                },
            )
        }
        fun servicesByKeys(keys: List<String>): List<MoreSvc> =
            keys.mapNotNull { key -> allServices.firstOrNull { it.key == key } }

        fun trackedAction(service: MoreSvc): () -> Unit = {
            com.xjtu.toolbox.util.ServiceUsageTracker.record(ctx, service.key)
            service.onClick()
        }

        val iconColorByKey = mutableMapOf<String, androidx.compose.ui.graphics.Color>()
        for (index in allServices.indices) {
            val service = allServices[index]
            iconColorByKey[service.key] = serviceColor(index, allServices.size)
        }

        fun coloredForIconTheme(service: MoreSvc): MoreSvc {
            return service.copy(color = iconColorByKey[service.key] ?: service.color)
        }

        // 两个主题共用的分类视觉标识
        val categoryIcon = mapOf(
            ServiceCategory.CLASS to Icons.Default.School,
            ServiceCategory.STUDY to Icons.Default.Assessment,
            ServiceCategory.LIFE to Icons.Default.Restaurant,
            ServiceCategory.TOOL to Icons.Default.SmartToy,
        )
        val categoryAccentKey = mapOf(
            ServiceCategory.CLASS to Routes.SCHEDULE,
            ServiceCategory.STUDY to Routes.JWAPP_SCORE,
            ServiceCategory.LIFE to Routes.CAMPUS_CARD,
            ServiceCategory.TOOL to Routes.AGENT,
        )

        when (homeTheme) {
            CredentialStore.THEME_ICON -> {
                // 图标主题 = 分类卡（超椭圆 + 主色渐变 + 细描边）+ 卡内 4 列密集宫格。
                // 追求"一屏尽收、认图标找功能"，所以格子小、排布规整。
                //
                // 收藏夹已彻底删除：长按固定会把项目从当前分类"搬"到页面最上方的收藏夹，
                // 用户长按"常用功能"里的东西时体验是"东西突然跑到别的地方去了"，混乱。
                // 现在只保留自动识别的常用功能（按使用频率算），完全不支持手动移动/固定。
                val categories = ServiceCategory.entries.mapNotNull { category ->
                    val items = allServices
                        .filter { it.category == category }
                        .map { coloredForIconTheme(it) }
                    if (items.isEmpty()) null else category to items
                }
                categories.forEachIndexed { index, (category, items) ->
                    HomeCategoryCard(
                        title = category.title,
                        subtitle = category.subtitle,
                        icon = categoryIcon.getValue(category),
                        accent = com.xjtu.toolbox.ui.theme.legacyColor(categoryAccentKey.getValue(category)),
                        rows = items.map { svc ->
                            HomeServiceRow(svc.key, svc.icon, svc.title, svc.color, trackedAction(svc))
                        },
                    )
                    if (index != categories.lastIndex) Spacer(Modifier.height(16.dp))
                }
            }
            else -> {
                val usedKeys = mutableSetOf<String>()

                // ── 屁岱主动提醒 ──
                // 数据全部取自本地缓存与 Hero 已算好的日程，不为提醒额外发请求。
                var proactiveMessage by remember {
                    mutableStateOf<com.xjtu.toolbox.agent.ProactiveMessage?>(null)
                }

                val quickCandidateKeys = listOf(
                    Routes.CAMPUS_CARD,
                    Routes.EMPTY_ROOM,
                    Routes.PAYMENT_CODE,
                    Routes.NOTIFICATION,
                    Routes.JWAPP_SCORE,
                    Routes.COUPON,
                    Routes.LIBRARY,
                    Routes.LMS,
                    Routes.AGENT,
                    Routes.JIAOXIAOZHI,
                ).filterNot { it in usedKeys }
                val quickKeys = if (showQuickActions && quickCandidateKeys.isNotEmpty()) {
                    remember(quickCandidateKeys) {
                        // 屁岱**钉死在第 0 位**：它是主动提醒气泡的锚点，位置必须稳定，
                        // 否则气泡会跟着频率排序左右横跳。做法是先把它从频率候选里剔除，
                        // 再单独插到最前——直接参与排序的话，用得少时会被挤掉。
                        val rest = com.xjtu.toolbox.util.ServiceUsageTracker.topKeys(
                            ctx,
                            quickCandidateKeys.filterNot { it == Routes.AGENT },
                            n = 3,
                            fallback = listOf(Routes.CAMPUS_CARD, Routes.EMPTY_ROOM, Routes.NOTIFICATION)
                                .filter { it in quickCandidateKeys } + quickCandidateKeys
                        ).filter { it in quickCandidateKeys && it != Routes.AGENT }.distinct().take(3)
                        listOf(Routes.AGENT) + rest
                    }
                } else {
                    emptyList()
                }
                val quickShown = servicesByKeys(quickKeys)
                if (quickShown.isNotEmpty()) {
                    // 不再把快捷入口从下方分类里剔除：「常用功能」是**额外**多一个入口，
                    // 不是把功能搬走。原来会 usedKeys += 之后在分类里过滤掉，
                    // 表现为"某个功能从它所属的分类里凭空消失了"，找不到。
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        HomeSectionHeader("常用功能", Modifier.padding(start = 4.dp, bottom = 12.dp))
                        Box(Modifier.fillMaxWidth()) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .squircleClip(CARD_RADIUS)
                                    .background(AppCardColor)
                                    .padding(vertical = 10.dp),
                            ) {
                                quickShown.forEach { service ->
                                    HomeQuickAction(
                                        service.icon,
                                        service.title,
                                        service.color,
                                        onClick = trackedAction(service),
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                            // 主动提醒气泡：锚在第 0 个入口（钉死的屁岱）正上方，尖角朝下。
                            //
                            // 气泡左对齐，由**尖角位置**去对准图标，而不是移动整个气泡。
                            // 居中到第 0 格会溢出：那格中心距左边仅约 1/8 屏宽，装不下 260dp。
                            // 尖角横向偏移按锚点真实中心算：SpaceEvenly 下第 i 格中心为
                            // 宽度 × (2i+1) / 2n，第 0 格即 宽度 / 2n。
                            // 这样加减快捷入口或换屏幕宽度都不会跑偏。
                            proactiveMessage?.let { msg ->
                                BoxWithConstraints(
                                    Modifier.fillMaxWidth().offset(y = (-52).dp),
                                    contentAlignment = Alignment.TopStart
                                ) {
                                    val anchorCenter = maxWidth / (2 * quickShown.size)
                                    com.xjtu.toolbox.agent.ProactiveBubbleView(
                                        message = msg,
                                        onOpen = {
                                            com.xjtu.toolbox.agent.ProactiveRules.markUseful(ctx, msg.id)
                                            com.xjtu.toolbox.agent.AgentPendingPrompt.set(msg.prompt)
                                            proactiveMessage = null
                                            onNavigate(Routes.AGENT)
                                        },
                                        onDismiss = {
                                            com.xjtu.toolbox.agent.ProactiveRules.markDismissed(ctx, msg.id)
                                            proactiveMessage = null
                                        },
                                        onTimeout = { proactiveMessage = null },
                                        arrowFromStart = anchorCenter,
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }

                // 卡片主题 = Bento（便当盒）不规则网格。
                //
                // 与图标主题的区别必须是**结构性**的，不能只是"给宫格套个壳"——那样两个主题
                // 只剩几列之差，等于没有区别。这里每个分类的首项占一块 2 列宽的大瓷砖
                // （大图标 + 名称，主色实心渐变），旁边竖排两块小的，剩下的走 3 列常规块，
                // 由此产生大小错落的节奏；而图标主题是严格等分的密集宫格。
                //
                // 纯布局实现，没有引第三方组件：Bento 的观感来自尺寸对比与留白节奏，
                // 不是某个控件，为它引依赖只会徒增体积和版本耦合。
                //
                // 分类不再各自成卡，改由标题分隔——瓷砖本身就是卡，再套一层就是"卡中卡"。
                // 各功能的当前状态。全部读**本地缓存**，首页不发任何网络请求
                // （详见 HomeStats）。拿不到就是 null，该功能退回纯入口。
                val statsCtx = LocalContext.current

                // 循环评估：只在冷启动算一次的话，回到首页、余额变化、临近上课都不会再触发，
                // 表现就是"冒过一次以后再也不冒了"。真正的节流交给 pick() 里的冷却判断。
                LaunchedEffect(loginState.accountId, loginState.isLoggedIn) {
                    if (!loginState.isLoggedIn) return@LaunchedEffect
                    kotlinx.coroutines.delay(com.xjtu.toolbox.agent.ProactiveRules.FIRST_DELAY_MS)
                    while (true) {
                    val minutes = scheduleReminderState?.let {
                        java.time.Duration.between(java.time.LocalDateTime.now(), it.startAt).toMinutes()
                    }
                    // 成绩与通知由 HomeStatsRefresher 抓取后留下游标，这里只读不抓——
                    // 「一次抓取、两处消费」，气泡不为自己额外发请求。
                    val pendingScores = com.xjtu.toolbox.home.HomeStats.pendingNewScores(statsCtx)
                    val unseenNotice = com.xjtu.toolbox.home.HomeStats.unseenNoticeTitle(statsCtx)
                    val msg = com.xjtu.toolbox.agent.ProactiveRules.pick(
                        ctx = statsCtx,
                        balance = cachedBalance.takeIf { it >= 0f }?.toDouble(),
                        nextCourseName = scheduleReminderState?.name,
                        minutesToClass = minutes,
                        newGradeCount = pendingScores,
                        latestNotice = unseenNotice,
                    )
                    android.util.Log.d(
                        "Proactive",
                        "evaluate: loggedIn=${loginState.isLoggedIn} balance=$cachedBalance " +
                            "nextCourse=${scheduleReminderState?.name} minutes=$minutes " +
                            "newScores=$pendingScores notice=${unseenNotice?.take(12)} -> ${msg?.text ?: "无"}"
                    )
                    if (msg != null && proactiveMessage == null) {
                        com.xjtu.toolbox.agent.ProactiveRules.markShown(statsCtx, msg.id)
                        // 冒过就消费掉，避免同一条反复提醒。冷却只管"多久不再说"，
                        // 不负责"这件事已经说过了"——两者混用会导致冷却一过又推一遍旧消息。
                        when (msg.id) {
                            "grade" -> com.xjtu.toolbox.home.HomeStats.setPendingNewScores(statsCtx, 0)
                            "notice" -> com.xjtu.toolbox.home.HomeStats.clearUnseenNotice(statsCtx)
                        }
                        proactiveMessage = msg
                    }
                    kotlinx.coroutines.delay(com.xjtu.toolbox.agent.ProactiveRules.EVAL_INTERVAL_MS)
                    }
                }
                var homeStats by remember { mutableStateOf<Map<String, com.xjtu.toolbox.home.HomeStat>>(emptyMap()) }
                LaunchedEffect(loginState.accountId, loginState.campusCardCacheVersion) {
                    val term = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching {
                            val dc = com.xjtu.toolbox.util.DataCache(statsCtx)
                            dc.get("schedule_term_list", Long.MAX_VALUE)?.let { j ->
                                com.google.gson.Gson().fromJson(j, Array<String>::class.java)?.firstOrNull()
                            }
                        }.getOrNull()
                    }
                    homeStats = com.xjtu.toolbox.home.HomeStats.collect(statsCtx, term)
                    // 再跑一轮主动刷新：只拉过期的源，源之间串行留间隔（见 HomeStatsRefresher），
                    // 拉完重新读一次缓存把新值显示出来。全程静默，失败不打扰用户。
                    com.xjtu.toolbox.home.HomeStatsRefresher.refreshDue(
                        statsCtx,
                        loginState.sessionManager,
                        loginState.accountType,
                    )
                    homeStats = com.xjtu.toolbox.home.HomeStats.collect(statsCtx, term)
                }

                val statOf: (String) -> Pair<String, String?>? = { key ->
                    when (key) {
                        // 下节课用 Hero 区已算好的那份，避免重复解析课表
                        Routes.SCHEDULE -> scheduleReminderState?.let { r ->
                            val eta = java.time.Duration.between(java.time.LocalDateTime.now(), r.startAt).toMinutes()
                            r.name to buildString {
                                append(formatMinuteClock(r.startAt.hour * 60 + r.startAt.minute))
                                if (r.location.isNotBlank()) append(" · ${r.location}")
                                if (eta > 0) append(" · ${formatScheduleReminderEta(eta)}")
                            }
                        }
                        // 日程没有下节课时，退回最近一场考试（HomeStats 把它挂在同一个 key 下）
                        else -> homeStats[key]?.let { it.value to it.detail }
                    }
                }

                val categoryCards = ServiceCategory.entries.mapNotNull { category ->
                    val items = allServices.filter { it.category == category }
                    if (items.isEmpty()) null else category to items
                }
                categoryCards.forEachIndexed { index, (category, items) ->
                    val rows = items.map { svc ->
                        val stat = statOf(svc.key)
                        HomeServiceRow(
                            key = svc.key,
                            icon = svc.icon,
                            title = svc.title,
                            color = svc.color,
                            onClick = trackedAction(svc),
                            stat = stat?.first,
                            statDetail = stat?.second,
                        )
                    }
                    HomeSceneCard(
                        title = category.title,
                        subtitle = category.subtitle,
                        icon = categoryIcon.getValue(category),
                        accent = com.xjtu.toolbox.ui.theme.legacyColor(categoryAccentKey.getValue(category)),
                        rows = rows,
                    )
                    if (index != categoryCards.lastIndex) Spacer(Modifier.height(14.dp))
                }
            }
        }

        if (navBarStyle == "floating") Spacer(Modifier.height(96.dp))
    }
}

// ══════════════════════════════════════════
//  Tab 2 — 日程
// ══════════════════════════════════════════

@Composable
private fun CoursesTab(
    loginState: AppLoginState,
    onNavigateWithLogin: (String, LoginType) -> Unit,
    onNavigate: (String) -> Unit = {},
    scrollBehavior: ScrollBehavior? = null,
    navBarStyle: String = "floating",
    onSubtitleChange: (String) -> Unit = {},
    onActionsChange: ((@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)?) -> Unit = {},
    onBottomContentChange: ((@Composable () -> Unit)?) -> Unit = {},
) {
    val bottomReserve = if (navBarStyle == "floating") 96.dp else 0.dp
    Box(
        Modifier
            .fillMaxSize()
            .then(if (scrollBehavior != null) Modifier.nestedScroll(scrollBehavior.nestedScrollConnection) else Modifier)
    ) {
        ScheduleScreen(
            site = loginState.sessionManager?.getSiteOrNull("jwxt"),
            studentId = loginState.activeUsername,
            onBack = {},
            showTopBar = false,
            onSubtitleChange = onSubtitleChange,
            onActionsChange = onActionsChange,
            onBottomContentChange = onBottomContentChange,
            contentBottomPadding = bottomReserve,
        )
    }
}

// ══════════════════════════════════════════
//  Tab 3 — 仲英学辅资料站（zyxf.top）
// ══════════════════════════════════════════

private const val ZYXF_URL = "https://zyxf.top"

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ToolsTab(
    loginState: AppLoginState,
    onNavigateWithLogin: (String, LoginType) -> Unit,
    onNavigate: (String) -> Unit,
    scrollBehavior: ScrollBehavior? = null,
    navBarStyle: String = "floating"
) {
    var webViewRef by remember { mutableStateOf<android.webkit.WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var isPageLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    val dlScope = rememberCoroutineScope()

    // blob: 下载桥。
    //
    // 资料站用 JS 把文件读进内存后 URL.createObjectURL() 生成 blob: 地址触发下载。
    // blob: 仅在该页面的 JS 上下文有效，App 侧 HTTP 客户端无法访问
    // （OkHttp 会抛 "expected url scheme http or https but was blob"）。
    // 只能由页面内的 JS 读成 base64 回传，App 侧解码落盘。
    val blobBridge = remember(ctx) {
        object {
            @android.webkit.JavascriptInterface
            fun onBlob(base64: String, fileName: String) {
                dlScope.launch {
                    val saved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching {
                            // base64 形如 data:application/pdf;base64,xxxx —— MIME 也能顺便取到
                            val mime = base64.substringAfter("data:", "")
                                .substringBefore(";", "")
                            val bytes = android.util.Base64.decode(
                                base64.substringAfter(","),
                                android.util.Base64.DEFAULT
                            )
                            val uri = com.xjtu.toolbox.lms.LmsDownloadStore.saveBytes(
                                context = ctx,
                                fileName = fileName.ifBlank { "download_${System.currentTimeMillis()}" },
                                mimeType = mime,
                                bytes = bytes,
                                category = com.xjtu.toolbox.lms.LmsDownloadStore.CATEGORY_ZYXF,
                            )
                            uri to bytes.size
                        }.getOrElse { e ->
                            android.util.Log.e("ToolsTab", "blob save failed", e)
                            null to 0
                        }
                    }
                    val (uri, size) = saved
                    android.widget.Toast.makeText(
                        ctx,
                        if (uri != null) "已保存 $fileName（${size / 1024} KB）" else "保存失败",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    // 站内有历史时，系统返回键先回退网页，避免直接退出 App
    BackHandler(enabled = canGoBack) { webViewRef?.goBack() }

    Box(
        Modifier
            .fillMaxSize()
            .padding(bottom = if (navBarStyle == "floating") 88.dp else 0.dp)
    ) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { ctx ->
                android.webkit.WebView(ctx).apply {
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    webViewClient = object : android.webkit.WebViewClient() {
                        // 诊断 + 兜底：把每次导航请求打出来。
                        // 如果点下载后这里出现了一个像文件的 URL 而 DownloadListener 没触发，
                        // 说明服务端没给 Content-Disposition，WebView 把它当页面导航了 ——
                        // 这种情况按扩展名判断并自己接管下载。
                        override fun shouldOverrideUrlLoading(
                            view: android.webkit.WebView?,
                            request: android.webkit.WebResourceRequest?
                        ): Boolean {
                            val u = request?.url?.toString() ?: return false
                            android.util.Log.d("ToolsTab", "navigate: $u")
                            // 不按扩展名判断是否为下载：资料站格式无法穷举
                            // （.md/.tex/.caj/.tar.gz，甚至没有扩展名），且是否为附件取决于
                            // 服务器响应头而非 URL 形状。
                            // 交给 WebView 请求即可，附件类响应会走下面的 DownloadListener。
                            return false
                        }

                        override fun onPageStarted(view: android.webkit.WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            isPageLoading = true
                            loadError = null
                        }
                        override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                            isPageLoading = false
                            canGoBack = view?.canGoBack() == true

                            // 挂钩 a.click()：JS 造 <a href=blob:> 再 click() 这条路径
                            // 不一定触发 DownloadListener（取决于 WebView 版本与 download 属性），
                            // 不拦就点了没反应。是 blob 链接就读成 base64 交给桥。
                            view?.evaluateJavascript(
                                """
                                (function() {
                                  if (window.__xjtuBlobHooked) return;
                                  window.__xjtuBlobHooked = true;
                                  var origClick = HTMLAnchorElement.prototype.click;
                                  HTMLAnchorElement.prototype.click = function() {
                                    try {
                                      var h = this.href || '';
                                      if (h.indexOf('blob:') === 0) {
                                        var nm = this.getAttribute('download') || 'download';
                                        var xhr = new XMLHttpRequest();
                                        xhr.open('GET', h, true);
                                        xhr.responseType = 'blob';
                                        xhr.onload = function() {
                                          var r = new FileReader();
                                          r.onloadend = function() { XJTUBlobBridge.onBlob(r.result, nm); };
                                          r.readAsDataURL(xhr.response);
                                        };
                                        xhr.send();
                                        return;
                                      }
                                    } catch (e) {}
                                    return origClick.apply(this, arguments);
                                  };
                                })();
                                """.trimIndent(),
                                null
                            )
                        }
                        override fun onReceivedError(
                            view: android.webkit.WebView?,
                            request: android.webkit.WebResourceRequest?,
                            error: android.webkit.WebResourceError?
                        ) {
                            // 只把主文档失败视为页面错误，子资源失败忽略
                            if (request?.isForMainFrame == true) {
                                isPageLoading = false
                                loadError = "无法连接 zyxf.top，请检查网络后重试"
                            }
                        }
                    }
                    // 允许 JS 开新窗口，否则 target="_blank" 的下载链接会被直接吞掉
                    settings.setSupportMultipleWindows(true)
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onProgressChanged(view: android.webkit.WebView?, newProgress: Int) {
                            progress = newProgress
                        }

                        // 资料站的下载链接不少是 target="_blank"。开了 setSupportMultipleWindows 后
                        // 这类点击会走到这里，如果不处理就**什么都不会发生**（比不开还糟）。
                        // 这里不真开新窗口，而是把目标 URL 交回当前 WebView：
                        // 是文件就触发 DownloadListener，是页面就正常导航。
                        override fun onCreateWindow(
                            view: android.webkit.WebView?,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: android.os.Message?
                        ): Boolean {
                            val transport = resultMsg?.obj as? android.webkit.WebView.WebViewTransport
                                ?: return false
                            val tmp = android.webkit.WebView(view!!.context)
                            tmp.webViewClient = object : android.webkit.WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    v: android.webkit.WebView?,
                                    req: android.webkit.WebResourceRequest?
                                ): Boolean {
                                    req?.url?.toString()?.let { view.loadUrl(it) }
                                    tmp.destroy()
                                    return true
                                }
                            }
                            transport.webView = tmp
                            resultMsg.sendToTarget()
                            return true
                        }
                    }
                    // 资料站下载落到公共下载目录并登记进 LmsDownloadStore，与成绩单、
                    // 思源课件同处，在下载管理页作为独立分区显示。
                    // 不走 classreplay 的 DownloadManager：那套面向回放视频（断点续传、
                    // 并发限流、按 camera/audio 分轨），文件类下载塞进去会让分类失真。
                    addJavascriptInterface(blobBridge, "XJTUBlobBridge")
                    setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                        android.util.Log.d(
                            "ToolsTab",
                            "onDownloadStart: url=$url mime=$mimeType disposition=$contentDisposition"
                        )
                        val name = runCatching {
                            android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)
                        }.getOrNull().orEmpty()

                        // blob: 走 JS 桥读内容，不能交给 OkHttp（scheme 不支持，会抛
                        // "expected url scheme http or https but was blob"）
                        if (url.startsWith("blob:")) {
                            val js = """
                                (function() {
                                  var xhr = new XMLHttpRequest();
                                  xhr.open('GET', '$url', true);
                                  xhr.responseType = 'blob';
                                  xhr.onload = function() {
                                    var r = new FileReader();
                                    r.onloadend = function() {
                                      XJTUBlobBridge.onBlob(r.result, ${'"'}${name.ifBlank { "download" }}${'"'});
                                    };
                                    r.readAsDataURL(xhr.response);
                                  };
                                  xhr.onerror = function() { XJTUBlobBridge.onBlob('', ''); };
                                  xhr.send();
                                })();
                            """.trimIndent()
                            webViewRef?.evaluateJavascript(js, null)
                            return@setDownloadListener
                        }
                        // 普通 http(s) 下载：落到公共 Downloads/XJTUToolBox（与成绩单、思源课件同处），
                        // 走 MediaStore 不需要存储权限，系统文件管理器可见、卸载不丢。
                        val cookie = runCatching {
                            android.webkit.CookieManager.getInstance().getCookie(url)
                        }.getOrNull()
                        dlScope.launch {
                            android.widget.Toast.makeText(
                                ctx, "开始下载 ${name.ifBlank { "文件" }}", android.widget.Toast.LENGTH_SHORT
                            ).show()
                            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                com.xjtu.toolbox.zyxf.ZyxfDownloader.download(
                                    context = ctx,
                                    url = url,
                                    fallbackName = name,
                                    userAgent = userAgent,
                                    cookie = cookie,
                                )
                            }
                            android.widget.Toast.makeText(
                                ctx,
                                if (ok != null) "已保存 $ok" else "下载失败，可长按链接用浏览器打开",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    loadUrl(ZYXF_URL)
                    webViewRef = this
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 顶部加载进度线
        if (isPageLoading && loadError == null) {
            LinearProgressIndicator(
                progress = (progress / 100f).coerceIn(0.05f, 1f),
                modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                height = 2.dp
            )
        }

        // 主文档加载失败的兜底页
        loadError?.let { msg ->
            Column(
                Modifier
                    .fillMaxSize()
                    .background(MiuixTheme.colorScheme.background)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Default.CloudOff,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(56.dp)
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "仲英学辅资料站",
                    style = MiuixTheme.textStyles.title3,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    msg,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(Modifier.height(20.dp))
                Button(onClick = {
                    loadError = null
                    isPageLoading = true
                    webViewRef?.loadUrl(ZYXF_URL)
                }) { Text("重新加载") }
            }
        }
    }
}

// ══════════════════════════════════════════
//  Tab 4 — 我的（含统一登录）
// ══════════════════════════════════════════

/**
 * 学籍档案卡。数据来自 hello.xjtu.edu.cn，字段缺失时整行不渲染——
 * 宁可少一行，也不要出现"专业：—"这种占位。
 */
@Composable
private fun ProfileInfoCard(p: com.xjtu.toolbox.hello.HelloProfile) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val summary = p.professionName.ifBlank { p.departmentName }.ifBlank { p.className }
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 20.dp,
        colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = SinkFeedback(),
                    ) { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                HomeSectionHeader("学籍信息", Modifier.weight(1f))
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(22.dp)
                )
            }
            if (!expanded && summary.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    summary,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(Modifier.height(10.dp))

                    val rows = buildList {
                        p.departmentName.takeIf { it.isNotBlank() }?.let { add("学院" to it) }
                        p.academyName.takeIf { it.isNotBlank() }?.let { add("书院" to it) }
                        p.professionName.takeIf { it.isNotBlank() }?.let { add("专业" to it) }
                        p.className.takeIf { it.isNotBlank() }?.let { add("班级" to it) }
                        p.campusName.takeIf { it.isNotBlank() }?.let { add("校区" to it) }
                        if (p.grade > 0) {
                            val len = if (p.schoolingLen > 0) "（学制 ${p.schoolingLen} 年）" else ""
                            add("年级" to "${p.grade} 级$len")
                        }
                        p.enterSchoolDate.takeIf { it.isNotBlank() }?.let { add("入学" to it) }
                        p.cardId.takeIf { it.isNotBlank() }?.let { add("校园卡号" to it) }
                    }
                    rows.forEachIndexed { index, (label, value) ->
                        if (index > 0) Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth()) {
                            Text(
                                label,
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.width(64.dp)
                            )
                            Text(
                                value,
                                style = MiuixTheme.textStyles.body2,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    if (p.hasMentor()) {
                        Spacer(Modifier.height(14.dp))
                        HorizontalDivider(color = MiuixTheme.colorScheme.dividerLine)
                        Spacer(Modifier.height(12.dp))
                        listOfNotNull(
                            p.counselorName.takeIf { it.isNotBlank() }
                                ?.let { Triple("辅导员", it, p.counselorPhone) },
                            p.classTeacherName.takeIf { it.isNotBlank() }
                                ?.let { Triple("班主任", it, p.classTeacherPhone) },
                        ).forEachIndexed { index, (label, name, phone) ->
                            if (index > 0) Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    label,
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.width(64.dp)
                                )
                                Text(name, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium)
                                if (phone.isNotBlank()) {
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        phone,
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = MiuixTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                        p.counselorOffice.takeIf { it.isNotBlank() }?.let {
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth()) {
                                Text(
                                    "办公室",
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.width(64.dp)
                                )
                                Text(it, style = MiuixTheme.textStyles.body2, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** "我的"页卡片的下压暗叠层按压反馈，替代 SinkFeedback 收缩动画 */
@Composable
private fun Modifier.pressOverlay(
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    return this
        .clickable(interactionSource = interactionSource, indication = null, enabled = enabled, onClick = onClick)
        .drawWithContent {
            drawContent()
            if (isPressed) drawRect(color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.07f))
        }
}

@Composable
private fun ProfileTab(
    loginState: AppLoginState,
    onNavigateWithLogin: (String, LoginType) -> Unit,
    credentialStore: CredentialStore,
    accountManager: com.xjtu.toolbox.account.AccountManager,
    scrollBehavior: ScrollBehavior? = null,
    onNavigateToDownloads: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToAccounts: () -> Unit = {},
    navBarStyle: String = "floating",
    onWarmupRequest: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()

    // 登录表单状态
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isLoggingIn by remember { mutableStateOf(false) }
    var loginError by remember { mutableStateOf<String?>(null) }
    var loginProgress by remember { mutableStateOf(0f) }   // 0.0 ~ 1.0
    var loginStage by remember { mutableStateOf("") }       // 当前步骤描述

    // ── 个人档案（hello.xjtu.edu.cn）──
    // 缓存优先：进页面先把磁盘上的读出来立刻渲染，再按新鲜度决定要不要静默刷新，
    // 全程不阻塞 UI，也不显示加载态——拿不到就退回原有的 YWTB 基础信息。
    val ctx = LocalContext.current
    var helloProfile by remember {
        mutableStateOf(com.xjtu.toolbox.hello.HelloProfileStore.cached(ctx))
    }
    var helloAvatar by remember {
        mutableStateOf(com.xjtu.toolbox.hello.HelloProfileStore.cachedAvatar(ctx))
    }
    LaunchedEffect(loginState.isLoggedIn, loginState.activeUsername) {
        if (!loginState.isLoggedIn) {
            helloProfile = null
            helloAvatar = null
            return@LaunchedEffect
        }
        // 切账号后缓存目录随之变化，这里重新读一次本账号的
        helloProfile = com.xjtu.toolbox.hello.HelloProfileStore.cached(ctx)
        helloAvatar = com.xjtu.toolbox.hello.HelloProfileStore.cachedAvatar(ctx)
        com.xjtu.toolbox.hello.HelloProfileStore
            .ensure(ctx, loginState.sessionManager)
            ?.let {
                helloProfile = it
                helloAvatar = com.xjtu.toolbox.hello.HelloProfileStore.cachedAvatar(ctx)
            }
    }

    // Srun 校园网首次配置弹窗状态
    val showSrunSetupSheet = remember { mutableStateOf(false) }
    var srunSetupUsername by remember { mutableStateOf("") }
    var srunSetupPassword by remember { mutableStateOf("") }
    var srunSetupSaving by remember { mutableStateOf(false) }
    var srunSetupHint by remember { mutableStateOf<String?>(null) }

    // 智能登录：JWXT→核心登录→YWTB后台
    fun loginAllSystems(user: String, pwd: String) {
        val user = user.trim()
        isLoggingIn = true
        loginError = null
        loginProgress = 0f
        loginState.saveCredentials(user, pwd)

        scope.launch {
            val startMs = System.currentTimeMillis()

            loginStage = "认证中..."
            loginProgress = 0.1f
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    loginState.sessionManager?.ensureSite(LoginType.JWXT)
                }
            } catch (e: Exception) {
                isLoggingIn = false
                loginError = "登录异常: ${e.message}"
                return@launch
            }

            loginProgress = 0.8f

            loginProgress = 0.8f

            // ── 完成核心登录 ──
            loginProgress = 1f
            isLoggingIn = false
            // 落库到 AccountStore（多账号架构），同时兼容旧 CredentialStore 单值
            accountManager.persistCurrentLogin(user, pwd, loginState.accountType)
            loginState.persistCredentials(credentialStore)

            // ── 首次登录后引导用户配置 Srun 校园网自动登录 ──
            if (!credentialStore.srunSetupAsked) {
                showSrunSetupSheet.value = true
                // 默认填入主账号 + @stu 作为 Srun 用户名提示
                srunSetupUsername = if (user.contains("@")) user else "$user@stu"
            }

            // ── 后台: 仅预热必要 SSO，其余子系统由用户进入时按需登录 ──
            onWarmupRequest()
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val ywtbSite = loginState.sessionManager?.ensureSite(LoginType.YWTB)
                    if (ywtbSite != null && loginState.ywtbUserInfo == null) {
                        loginState.ywtbUserInfo = com.xjtu.toolbox.ywtb.YwtbApi(ywtbSite).getUserInfo()
                        // YWTB 拿到昵称后回写到 AccountStore
                        loginState.ywtbUserInfo?.userName?.let { name ->
                            accountManager.updateNickname(user, name)
                        }
                    }
                } catch (_: Exception) { }
                // 首次登录抓一次个人档案 + 头像并落盘，之后"我的"页直接读缓存。
                // 失败不影响登录流程：档案是锦上添花，YWTB 那份基础信息仍在。
                try {
                    helloProfile = com.xjtu.toolbox.hello.HelloProfileStore
                        .ensure(ctx, loginState.sessionManager, force = true)
                    helloAvatar = com.xjtu.toolbox.hello.HelloProfileStore.cachedAvatar(ctx)
                } catch (_: Exception) { }
            }
        }
    }

    // ── Srun 校园网（XJTU_STU）首次配置弹窗（OverlayDialog 抗键盘弹飞）──
    if (showSrunSetupSheet.value) {
        OverlayDialog(
            show = showSrunSetupSheet.value,
            title = "校园网自动登录",
            summary = "连接校园 WiFi 时自动帮你登录 Srun 网关，免去每次手动认证。",
            onDismissRequest = {
                showSrunSetupSheet.value = false
                credentialStore.srunSetupAsked = true
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextField(
                    value = srunSetupUsername,
                    onValueChange = { srunSetupUsername = it; srunSetupHint = null },
                    label = "校园网账号（含 @stu/@xjtu 后缀）",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                TextField(
                    value = srunSetupPassword,
                    onValueChange = { srunSetupPassword = it; srunSetupHint = null },
                    label = "校园网密码",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
                srunSetupHint?.let {
                    Text(it, color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.footnote1)
                }
                Text(
                    "凭据使用 Android Keystore 加密存储；仅当连接到 XJTU_STU 时本机自动发起登录。" +
                        "可在「设置 → 校园网自动登录」中随时修改或关闭。",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Row(Modifier.fillMaxWidth()) {
                    TextButton(
                        text = "暂不开启",
                        onClick = {
                            credentialStore.srunSetupAsked = true
                            credentialStore.srunAutoLoginEnabled = false
                            showSrunSetupSheet.value = false
                        },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(20.dp))
                    TextButton(
                        text = if (srunSetupSaving) "保存中..." else "启用并保存",
                        onClick = {
                            if (srunSetupUsername.isBlank() || srunSetupPassword.isBlank()) {
                                srunSetupHint = "请填写账号和密码（或选择跳过）"
                                return@TextButton
                            }
                            srunSetupSaving = true
                            credentialStore.saveSrunCredentials(srunSetupUsername.trim(), srunSetupPassword)
                            credentialStore.srunAutoLoginEnabled = true
                            credentialStore.srunSetupAsked = true
                            srunSetupSaving = false
                            showSrunSetupSheet.value = false
                        },
                        enabled = !srunSetupSaving,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        }
    }

    // ── UI ──
    Column(
        Modifier
            .fillMaxSize()
            .then(if (scrollBehavior != null) Modifier.nestedScroll(scrollBehavior.nestedScrollConnection) else Modifier)
            .overScrollVertical()
            .verticalScroll(rememberScrollState())
    ) {
        // ━━ Hero Header ━━
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (loginState.isLoggedIn) Modifier.clickable { onNavigateToAccounts() } else Modifier),
            color = MiuixTheme.colorScheme.surface
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MiuixTheme.colorScheme.surface,
                                MiuixTheme.colorScheme.primary.copy(alpha = 0.08f),
                                MiuixTheme.colorScheme.surface
                            )
                        )
                    )
                    .padding(horizontal = 24.dp, vertical = 36.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Avatar：登录后显示姓名首字母，未登录显示通用 Icon
                    Surface(
                        modifier = Modifier.size(72.dp),
                        shape = CircleShape,
                        color = MiuixTheme.colorScheme.primary
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            val avatar = helloAvatar
                            when {
                                // 学工系统的证件照。拿不到就退回姓名首字母，绝不留空。
                                loginState.isLoggedIn && avatar != null -> Image(
                                    bitmap = avatar.asImageBitmap(),
                                    contentDescription = "头像",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                )
                                loginState.isLoggedIn -> {
                                    val initial = (helloProfile?.name ?: loginState.ywtbUserInfo?.userName ?: loginState.cachedNickname ?: loginState.activeUsername).take(1)
                                    Text(initial, color = MiuixTheme.colorScheme.onPrimary, style = MiuixTheme.textStyles.title2, fontWeight = FontWeight.Bold)
                                }
                                else -> Icon(Icons.Outlined.Person, null, Modifier.size(36.dp), tint = MiuixTheme.colorScheme.onPrimary)
                            }
                        }
                    }
                    Spacer(Modifier.width(20.dp))
                    Column {
                        if (loginState.isLoggedIn) {
                            Text(
                                helloProfile?.name?.takeIf { it.isNotBlank() }
                                    ?: loginState.ywtbUserInfo?.userName ?: loginState.cachedNickname ?: loginState.activeUsername,
                                style = MiuixTheme.textStyles.title2,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(2.dp))
                            // 学号。班级/专业放在下面学籍卡里，这里不再重复。
                            Text(
                                loginState.activeUsername,
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        } else {
                            Text("岱宗盒子", style = MiuixTheme.textStyles.title2, fontWeight = FontWeight.Bold)
                            Text("登录以使用全部功能", style = MiuixTheme.textStyles.body1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                    }
                }
            }
        }

        // ━━ 未登录 → 登录表单 ━━
        if (!loginState.isLoggedIn) {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(8.dp))

                // 登录表单
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 20.dp,
                    colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant)
                ) {
                    Column(Modifier.padding(24.dp)) {
                        Text(
                            "统一身份认证",
                            style = MiuixTheme.textStyles.headline1,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "CAS 统一认证登录",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )

                        Spacer(Modifier.height(20.dp))

                        TextField(
                            value = username,
                            onValueChange = { username = it; loginError = null },
                            label = "学号 / 手机号",
                            singleLine = true,
                            enabled = !isLoggingIn,
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                        )
                        Spacer(Modifier.height(12.dp))
                        var passwordVisible by remember { mutableStateOf(false) }
                        TextField(
                            value = password,
                            onValueChange = { password = it; loginError = null },
                            label = "密码",
                            singleLine = true,
                            enabled = !isLoggingIn,
                            modifier = Modifier.fillMaxWidth(),
                            visualTransformation = if (passwordVisible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingIcon = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(
                                        if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                        contentDescription = if (passwordVisible) "隐藏密码" else "显示密码"
                                    )
                                }
                            }
                        )
                        if (loginError != null) {
                            Spacer(Modifier.height(4.dp))
                            Text(loginError!!, color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.footnote1, modifier = Modifier.padding(start = 4.dp))
                        }

                        Spacer(Modifier.height(20.dp))

                        // 登录按钮 + 进度
                        Button(
                            onClick = {
                                if (username.isBlank() || password.isBlank()) {
                                    loginError = "请输入学号和密码"
                                    return@Button
                                }
                                loginAllSystems(username, password)
                            },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            enabled = !isLoggingIn
                        ) {
                            if (isLoggingIn) {
                                CircularProgressIndicator(
                                    size = 20.dp,
                                    colors = ProgressIndicatorDefaults.progressIndicatorColors(
                                        foregroundColor = MiuixTheme.colorScheme.onPrimary
                                    ),
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(loginStage)
                            } else {
                                Text("登录", style = MiuixTheme.textStyles.subtitle)
                            }
                        }

                        // 进度条
                        if (isLoggingIn) {
                            Spacer(Modifier.height(16.dp))
                            val animatedProgress by animateFloatAsState(
                                targetValue = loginProgress,
                                animationSpec = spring(dampingRatio = 0.85f, stiffness = 500f),
                                label = "loginProgress"
                            )
                            LinearProgressIndicator(
                                progress = animatedProgress,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ProgressIndicatorDefaults.progressIndicatorColors(
                                    backgroundColor = MiuixTheme.colorScheme.surfaceVariant
                                )
                            )
                        }

                        Spacer(Modifier.height(12.dp))
                        Text(
                            "密码仅用于本地加密后发送至学校 CAS 服务器",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.6f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        // ━━ 已登录 → 在校信息 + 辅导员 + 系统状态 ━━
        if (loginState.isLoggedIn) {
            val context = LocalContext.current

            LaunchedEffect(loginState.hasCredentials) {
                if (loginState.ywtbUserInfo != null) return@LaunchedEffect
                if (!loginState.hasCredentials) return@LaunchedEffect
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val ywtbSite = runCatching { loginState.sessionManager?.ensureSite(LoginType.YWTB) }.getOrNull()
                    if (ywtbSite != null && loginState.ywtbUserInfo == null) {
                        runCatching {
                            loginState.ywtbUserInfo = com.xjtu.toolbox.ywtb.YwtbApi(ywtbSite).getUserInfo()
                        }
                    }
                }
            }

            Column(Modifier.padding(horizontal = 20.dp)) {

                // 学籍档案（hello.xjtu.edu.cn）。缓存优先，没有就整块不渲染。
                helloProfile?.takeIf { it.hasContent() }?.let { p ->
                    ProfileInfoCard(p)
                }

                Spacer(Modifier.height(12.dp))

                // ━━ 下载管理入口卡片 ━━
                var downloadStats by remember { mutableStateOf<com.xjtu.toolbox.classreplay.DownloadManager.DownloadStats?>(null) }
                var lmsDownloadCount by remember { mutableIntStateOf(0) }
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val downloadManager = com.xjtu.toolbox.classreplay.DownloadManager.getInstance(context)
                        downloadStats = downloadManager.getDownloadStats()
                        lmsDownloadCount = com.xjtu.toolbox.lms.LmsDownloadStore.getAll(context).size
                    }
                }
                Card(
                    onClick = onNavigateToDownloads,
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 20.dp,
                    pressFeedbackType = PressFeedbackType.Sink,
                    colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Download, null, Modifier.size(20.dp), tint = MiuixTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("下载管理", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium)
                            val stats = downloadStats
                            if (stats != null) {
                                val statsText = buildString {
                                    if (stats.downloadingCount > 0) append("${stats.downloadingCount}个下载中")
                                    if (stats.completedCount > 0) {
                                        if (isNotEmpty()) append(" · ")
                                        append("${stats.completedCount}个已完成")
                                    }
                                    if (lmsDownloadCount > 0) {
                                        if (isNotEmpty()) append(" · ")
                                        append("${lmsDownloadCount}个课件")
                                    }
                                    if (isEmpty()) append("暂无下载")
                                }
                                Text(statsText, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            } else {
                                Text("查看下载进度和记录", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            }
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ━━ 账号管理 + 设置 + 退出登录 ━━
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 20.dp,
                    colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant)
                ) {
                    Column {
                        // 账号管理入口行
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .pressOverlay { onNavigateToAccounts() }
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(shape = CircleShape, color = MiuixTheme.colorScheme.primary.copy(alpha = 0.1f), modifier = Modifier.size(36.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Person, null, Modifier.size(18.dp), tint = MiuixTheme.colorScheme.primary)
                                }
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text("账号管理", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium)
                                val cnt = accountManager.accountCount()
                                if (cnt > 0) {
                                    Text(
                                        "已保存 $cnt 个账号",
                                        style = MiuixTheme.textStyles.footnote2,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    )
                                }
                            }
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f))
                        }

                        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MiuixTheme.colorScheme.outline.copy(alpha = 0.3f))

                        // 设置入口行
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .pressOverlay { onNavigateToSettings() }
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(shape = CircleShape, color = MiuixTheme.colorScheme.primary.copy(alpha = 0.1f), modifier = Modifier.size(36.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Settings, null, Modifier.size(18.dp), tint = MiuixTheme.colorScheme.primary)
                                }
                            }
                            Spacer(Modifier.width(14.dp))
                            Text("设置", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f))
                        }

                        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MiuixTheme.colorScheme.outline.copy(alpha = 0.3f))

                        // 退出登录行
                        val showLogoutDialog = remember { mutableStateOf(false) }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .pressOverlay { showLogoutDialog.value = true }
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(shape = CircleShape, color = MiuixTheme.colorScheme.onError.copy(alpha = 0.5f), modifier = Modifier.size(36.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.AutoMirrored.Filled.Logout, null, Modifier.size(18.dp), tint = MiuixTheme.colorScheme.error)
                                }
                            }
                            Spacer(Modifier.width(14.dp))
                            Text("退出登录", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.error, modifier = Modifier.weight(1f))
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f))
                        }

                        if (showLogoutDialog.value) {
                            BackHandler { showLogoutDialog.value = false }
                            OverlayDialog(
                                show = showLogoutDialog.value,
                                title = "确认退出",
                                summary = "退出当前账号的登录，清除其会话 Cookie。账号记录与本地缓存保留，下次可在「账号管理」快速切回。",
                                onDismissRequest = { showLogoutDialog.value = false }
                            ) {
                                Row(Modifier.fillMaxWidth()) {
                                    TextButton(
                                        text = "取消",
                                        onClick = { showLogoutDialog.value = false },
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(Modifier.width(20.dp))
                                    TextButton(
                                        text = "退出登录",
                                        onClick = {
                                            showLogoutDialog.value = false
                                            scope.launch { accountManager.logoutCurrent() }
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.textButtonColors(
                                            textColor = MiuixTheme.colorScheme.error
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (navBarStyle == "floating") Spacer(Modifier.height(96.dp))
    }
}

@Composable
private fun StatusListItem(title: String, subtitle: String, isActive: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 状态指示点
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(
                    if (isActive) MiuixTheme.colorScheme.primary
                    else MiuixTheme.colorScheme.outline
                )
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = if (isActive) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
                    else MiuixTheme.colorScheme.surfaceVariant
        ) {
            Text(
                if (isActive) "已连接" else "离线",
                Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                style = MiuixTheme.textStyles.footnote1,
                color = if (isActive) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
    }
}

// ══════════════════════════════════════════
//  通用组件
// ══════════════════════════════════════════

/** HomeTab 日程提醒卡片用的轻量数据类 */
private data class ScheduleReminderCourseInfo(
    val name: String,
    val location: String,
    val startSection: Int,
    val endSection: Int,
    val startMinuteOfDay: Int = -1,
    val endMinuteOfDay: Int = -1
)

private data class ScheduleReminderInfo(
    val name: String,
    val location: String,
    val startAt: java.time.LocalDateTime,
    val endAt: java.time.LocalDateTime?
)

private fun ScheduleReminderCourseInfo.resolveStartMinute(isSummer: Boolean): Int? {
    if (startMinuteOfDay in 0 until (24 * 60)) return startMinuteOfDay
    val startTime = com.xjtu.toolbox.util.XjtuTime.getClassTime(startSection, isSummer)?.start ?: return null
    return startTime.hour * 60 + startTime.minute
}

private fun ScheduleReminderCourseInfo.resolveEndMinute(isSummer: Boolean): Int? {
    if (endMinuteOfDay in 1..(24 * 60)) return endMinuteOfDay
    val endTime = com.xjtu.toolbox.util.XjtuTime.getClassTime(endSection, isSummer)?.end ?: return null
    return endTime.hour * 60 + endTime.minute
}

private fun formatMinuteClock(minuteOfDay: Int): String {
    return when {
        minuteOfDay >= 24 * 60 -> "24:00"
        minuteOfDay < 0 -> "00:00"
        else -> "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)
    }
}

private fun formatScheduleReminderEta(minutesUntil: Long): String {
    if (minutesUntil <= 0) return "即将开始"
    if (minutesUntil < 60) return "${minutesUntil}分钟后"

    val hours = minutesUntil / 60
    val remainMinutes = minutesUntil % 60
    if (hours < 24) {
        return if (remainMinutes == 0L) "${hours}小时后" else "${hours}小时${remainMinutes}分钟后"
    }

    val days = hours / 24
    val remainHours = hours % 24
    return if (remainHours == 0L) "${days}天后" else "${days}天${remainHours}小时后"
}

private fun formatScheduleReminderDateLabel(targetDate: java.time.LocalDate, today: java.time.LocalDate): String {
    val delta = java.time.temporal.ChronoUnit.DAYS.between(today, targetDate).toInt()
    return when (delta) {
        0 -> "今天"
        1 -> "明天"
        2 -> "后天"
        in 3..6 -> when (targetDate.dayOfWeek.value) {
            1 -> "周一"
            2 -> "周二"
            3 -> "周三"
            4 -> "周四"
            5 -> "周五"
            6 -> "周六"
            else -> "周日"
        }
        else -> "${targetDate.monthValue}月${targetDate.dayOfMonth}日"
    }
}

/**
 * 两个主题共用的服务条目数据。
 *
 * [stat] / [statDetail] 是卡片主题的核心：**有实时状态可展示的服务才配大卡**。
 * 图标主题忽略这两个字段——它的定位是等分入口宫格。
 */
private data class HomeServiceRow(
    val key: String,
    val icon: ImageVector,
    val title: String,
    val color: androidx.compose.ui.graphics.Color,
    val onClick: () -> Unit,
    /** 主数据，大字号展示，如「¥42.50」「高等数学」。null 表示没有可展示的状态。 */
    val stat: String? = null,
    /** 辅助说明，小字，如「08:00 · 主楼A-203 · 还有2小时」。 */
    val statDetail: String? = null,
) {
    val hasStat: Boolean get() = !stat.isNullOrBlank()
}

/**
 * 卡片主题的分类卡。
 *
 * 设计取向是**克制**。避免大面积光晕与 2 列超大瓷砖：光晕盖过标题、瓷砖占半屏宽会
 * 把图标撑大、留白空洞，一屏装不下几个功能。具体做法：
 * - 去掉光晕，分类主色只留标题左侧一根 3dp 竖条，够做区分又不喧宾夺主；
 * - 3 列紧凑瓷砖，图标 38dp，一屏能完整看到一个分类；
 * - 瓷砖底色用中性的 surfaceVariant 而不是分类主色染色，避免整卡花花绿绿；
 * - 圆角、内边距整体收一档（28→24、18→14）。
 *
 * 与图标主题的区别仍然立得住：图标主题是**无卡片的 4 列通栏宫格**，这里是**成卡分组
 * 的 3 列**，卡片边界 + 分类标题 + 副标题承担信息层级。
 */
@Composable
private fun HomeCategoryCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: androidx.compose.ui.graphics.Color,
    rows: List<HomeServiceRow>,
) {
    val isDark = isSystemInDarkTheme()
    // 分类主色的对角渐变，浓度压得很低——它是"氛围"，不是"色块"。
    // 深色模式下同样的 alpha 会显脏，所以两套值。
    val tint = if (isDark) 0.16f else 0.10f
    val fill = Brush.linearGradient(
        listOf(
            accent.copy(alpha = tint),
            accent.copy(alpha = tint * 0.25f),
            AppCardColor
        )
    )
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            // squircleBackground 只接受纯色，渐变要走 clip + background(brush)
            .squircleClip(CARD_RADIUS)
            .background(fill)
            // 细边框是精致感的关键。之前整卡没有任何描边，边界全靠底色差，
            // 在浅色主题下几乎看不出卡在哪儿，就显得"糊成一片"。
            .squircleBorder(1.dp, accent.copy(alpha = if (isDark) 0.28f else 0.20f), CARD_RADIUS)
            .padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(30.dp)
                    .squircleBackground(accent.copy(alpha = if (isDark) 0.28f else 0.16f), 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MiuixTheme.textStyles.headline1, fontWeight = FontWeight.Bold)
                Text(
                    subtitle,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        rows.chunked(4).forEach { group ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                group.forEach { row ->
                    HomeServiceTile(row, Modifier.weight(1f))
                }
                repeat(4 - group.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

/**
 * 分类卡里的瓷砖：图标 + 名称，**无独立背景块**。
 *
 * 关键取舍：不给瓷砖单独的底色。之前每个瓷砖都是一个小圆角色块，整体是"卡片里再套
 * 一堆小卡片"，这种嵌套容器正是廉价感的来源。现在瓷砖直接落在分类卡的渐变底上，
 * 卡片本身承担唯一的容器角色，层级干净。按压反馈由 SinkFeedback 提供，不需要底色来提示可点。
 */
@Composable
private fun HomeServiceTile(
    row: HomeServiceRow,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = SinkFeedback(),
                onClick = row.onClick
            )
            .padding(horizontal = 2.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ExpressiveIcon(icon = row.icon, color = row.color, size = 40.dp, iconSize = 21.dp)
        Spacer(Modifier.height(7.dp))
        Text(
            row.title,
            style = MiuixTheme.textStyles.footnote1,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 分类卡圆角。超椭圆下这个值可以给得比普通圆角更大而不显得"胀"。 */
private val CARD_RADIUS = 26.dp

// ══════════════════════════════════════════
//  卡片主题：场景大卡
// ══════════════════════════════════════════

/**
 * 场景大卡：一张卡装下一整类，卡内是**双列数据排版**。
 *
 * 与图标主题的分工：
 * - 图标主题 = 彩色宫格 + 主色渐变卡，回答「有哪些功能」；
 * - 卡片主题 = **中性卡 + 数据排版**，回答「这一块现在怎么样」。
 *
 * 视觉上刻意不用渐变。渐变已经是图标主题的语言，两边都铺一层同样的主色渐变，
 * 换来的只是"看起来一样"。这里改成：卡片本身中性，主色只出现在**左缘一条书脊**
 * 和**数值文字**上——颜色少而准，信息才立得住。
 *
 * 条目既不是方块也不是胶囊，而是"名称在上、数值在下"的数据格：没有任何背景块，
 * 卡片是唯一容器。有数据的格子数值用主色加粗，没数据的只剩一行淡名称，
 * 一眼就能扫出哪里有事。
 */
@Composable
private fun HomeSceneCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: androidx.compose.ui.graphics.Color,
    rows: List<HomeServiceRow>,
) {
    if (rows.isEmpty()) return
    // 有状态的排前面：卡片打开就先看见"有事"的部分
    val ordered = rows.sortedByDescending { it.hasStat }
    val liveCount = rows.count { it.hasStat }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .squircleClip(CARD_RADIUS)
            .background(AppCardColor)
            .height(IntrinsicSize.Min)
    ) {
        // 书脊：整卡左缘一条主色，替代整片渐变做分类识别
        Box(
            Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(accent)
        )
        Column(Modifier.weight(1f).padding(start = 15.dp, end = 15.dp, top = 14.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(7.dp))
                Text(title, style = MiuixTheme.textStyles.title4, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(
                    if (liveCount > 0) "$liveCount 条更新" else subtitle,
                    style = MiuixTheme.textStyles.footnote1,
                    color = if (liveCount > 0) accent else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontWeight = if (liveCount > 0) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(12.dp))
            ordered.chunked(2).forEachIndexed { i, pair ->
                if (i > 0) Spacer(Modifier.height(2.dp))
                Row(Modifier.fillMaxWidth()) {
                    pair.forEach { ServiceStatCell(it, accent, Modifier.weight(1f)) }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * 数据格：名称（弱）在上，数值（主色加粗）在下，无背景块。
 * 没有数据时只留名称一行并压低不透明度，让"有事的"自然浮出来。
 */
@Composable
private fun ServiceStatCell(
    row: HomeServiceRow,
    accent: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = SinkFeedback(),
                onClick = row.onClick
            )
            .padding(vertical = 7.dp, horizontal = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                row.icon,
                contentDescription = null,
                tint = if (row.hasStat) row.color else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(5.dp))
            Text(
                row.title,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (row.hasStat) {
            Spacer(Modifier.height(2.dp))
            Text(
                row.stat.orEmpty(),
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Bold,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            row.statDetail?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** 主页小节标题：主色强调条 + 粗体标题，全页统一。 */
@Composable
private fun HomeSectionHeader(title: String, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Box(
            Modifier
                .width(4.dp)
                .height(15.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MiuixTheme.colorScheme.primary)
        )
        Spacer(Modifier.width(8.dp))
        Text(title, style = MiuixTheme.textStyles.headline1, fontWeight = FontWeight.Bold)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeQuickAction(
    icon: ImageVector,
    label: String,
    color: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = SinkFeedback(),
                onClick = onClick
            )
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        ExpressiveIcon(icon = icon, color = color)
        Spacer(Modifier.height(8.dp))
        Text(label, style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Medium)
    }
}

/** 更多服务宫格项：纯图标 + 标签，无背景无副标题。 */
@Composable
private fun HomeGridItem(
    icon: ImageVector,
    label: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .heightIn(min = 86.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = SinkFeedback(),
                onClick = onClick
            )
            .padding(horizontal = 4.dp, vertical = 10.dp)
    ) {
        ExpressiveIcon(icon = icon, color = color, size = 46.dp, iconSize = 23.dp)
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            style = MiuixTheme.textStyles.footnote1,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun HomeServiceTile(
    icon: ImageVector, title: String, subtitle: String,
    iconColor: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = SinkFeedback(),
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ExpressiveIcon(
            icon = icon,
            color = iconColor,
            size = 42.dp,
            iconSize = 22.dp,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                subtitle,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MiuixTheme.textStyles.subtitle, color = MiuixTheme.colorScheme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
}

@Composable
private fun ServiceCard(icon: ImageVector, title: String, description: String, loggedIn: Boolean, iconColor: androidx.compose.ui.graphics.Color = MiuixTheme.colorScheme.primary, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        cornerRadius = 24.dp,
        pressFeedbackType = PressFeedbackType.Sink
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(iconColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = iconColor, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MiuixTheme.textStyles.subtitle, fontWeight = FontWeight.Bold)
                Text(description, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (loggedIn) {
                Surface(shape = RoundedCornerShape(8.dp), color = MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)) {
                    Text("已登录", Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.primary)
                }
            } else {
                Icon(Icons.Default.ChevronRight, null, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }
}

// ══════════════════════════════════════════
//  用户协议弹窗
// ══════════════════════════════════════════

@Composable
private fun EulaScreen(onAccept: () -> Unit) {
    val scrollState = rememberScrollState()
    var canAccept by remember { mutableStateOf(false) }
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

    // 滚动到底部才可同意
    LaunchedEffect(scrollState.value, scrollState.maxValue) {
        if (scrollState.maxValue > 0 && scrollState.value >= scrollState.maxValue - 50) {
            canAccept = true
        }
    }

    val boldStyle = androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.primary)

    // 条款数据 —— title + AnnotatedString body（关键语句加粗）
    data class EulaSection(val title: String, val body: androidx.compose.ui.text.AnnotatedString)
    val sections = listOf(
        EulaSection(
            "一、应用性质",
            androidx.compose.ui.text.buildAnnotatedString {
                append("本应用（「岱宗盒子」）是西安交通大学学生自主开发的非官方校园工具，")
                pushStyle(boldStyle); append("完全开源、无毒无害"); pop()
                append("，通过模拟浏览器行为访问学校现有的 Web 服务接口，为学生提供统一便捷的校园信息查询体验。本应用不隶属于、不代表西安交通大学或其任何部门。")
            }
        ),
        EulaSection(
            "二、数据来源与使用",
            androidx.compose.ui.text.buildAnnotatedString {
                append("本应用通过 HTTPS 协议访问学校各业务系统接口获取数据，校园系统请求均在您的设备上发起。您的账号凭据（用户名和密码）仅加密存储在本地设备中，不会上传至开发者服务器。")
                pushStyle(boldStyle); append("请勿将账号、验证码、API Key 等敏感信息交给不可信来源。"); pop()
            }
        ),
        EulaSection(
            "三、AI 与第三方服务",
            androidx.compose.ui.text.buildAnnotatedString {
                append("屁岱等 AI 功能由用户自行配置模型服务与 API Key。使用这些功能时，您的问题、上下文、工具查询结果、上传附件摘要等内容可能会发送给您选择的模型服务商或中转服务。")
                pushStyle(boldStyle); append("请优先选择可信服务商，妥善保管 API Key，避免提交不希望第三方处理的个人信息。"); pop()
                append("学校交晓智服务由上游系统处理，本应用仅提供原生入口与会话封装，回答内容仅供参考。")
            }
        ),
        EulaSection(
            "四、本地文件与下载",
            androidx.compose.ui.text.buildAnnotatedString {
                append("成绩单、课件、作业附件等下载内容会按系统规则保存到本机下载目录或应用私有目录。保存到公共下载目录的文件可能被文件管理器、备份软件或具备相应权限的其他应用读取。请自行管理、删除或转移包含个人信息的文件。")
            }
        ),
        EulaSection(
            "五、免责声明",
            androidx.compose.ui.text.buildAnnotatedString {
                append("1. 本应用按「按原样」（AS IS）提供，开发者不对其准确性、完整性、可用性或适用性作任何明示或暗示的保证。\n2. 因使用本应用导致的任何直接或间接损失（包括但不限于数据丢失、账号异常、学业影响等），开发者不承担任何责任。\n3. 若学校系统接口变更导致功能异常，开发者将尽力修复但不保证时效。\n4. 本应用可能因学校政策调整而需要停止服务，届时将提前告知用户。")
            }
        ),
        EulaSection(
            "六、合规声明",
            androidx.compose.ui.text.buildAnnotatedString {
                append("1. 本应用仅供西安交通大学在校师生个人学习和生活使用，严禁用于任何商业用途。\n2. ")
                pushStyle(boldStyle); append("本应用不提供抢选、抢课、刷分等牟利功能。"); pop()
                append("\n3. ")
                pushStyle(boldStyle); append("本应用不接入支付、退款等金额交易功能。"); pop()
                append("\n4. 使用者应遵守学校各系统的使用规定和信息安全管理条例。\n5. 本应用会尽量复用会话并限制异常重试，但严禁利用本应用进行恶意请求、批量爬取、接口滥用等行为。违者应自行承担相应责任。")
            }
        ),
        EulaSection(
            "七、知识产权",
            androidx.compose.ui.text.buildAnnotatedString {
                append("本应用源代码基于 MIT 协议开源，感谢相关项目的启发。所访问的各业务系统之数据、接口及商标均归西安交通大学及相关权利方所有。")
            }
        ),
        EulaSection(
            "八、条款变更",
            androidx.compose.ui.text.buildAnnotatedString {
                append("开发者保留随时修改本协议的权利。更新后的协议将在新版本发布时生效，继续使用本应用即视为接受修改后的条款。")
            }
        )
    )

    Scaffold(
        topBar = {
            top.yukonga.miuix.kmp.basic.TopAppBar(
                title = "用户协议与免责声明",
                largeTitle = "用户协议与免责声明",
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                "请仔细阅读以下条款。继续使用本应用即表示您同意以下全部内容。",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            Spacer(Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(16.dp)) {
                    sections.forEachIndexed { idx, section ->
                        if (idx > 0) Spacer(Modifier.height(12.dp))
                        Text(section.title, style = MiuixTheme.textStyles.subtitle, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.primary)
                        Spacer(Modifier.height(4.dp))
                        Text(section.body, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurface, lineHeight = 20.sp)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            if (!canAccept) {
                Text(
                    "↓ 请阅读至底部后同意",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
            }

            Button(
                onClick = onAccept,
                enabled = canAccept,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("我已阅读并同意")
            }

            Spacer(Modifier.height(32.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

// ══════════════════════════════════════════
//  本地 What's New 弹窗 —— 堆叠展示版
// ══════════════════════════════════════════

@Composable
private fun UpdateNoticeDialog(
    entries: List<Pair<String, com.xjtu.toolbox.util.VersionChangelog>>,
    show: MutableState<Boolean>,
    fromVersion: String? = null,
    onDismiss: () -> Unit
) {
    if (entries.isEmpty()) return
    BackHandler(enabled = show.value) { onDismiss() }
    val title = if (entries.size == 1) {
        "岱宗盒子 v${entries.first().first}"
    } else {
        "岱宗盒子 v${entries.first().first}（含 ${entries.size} 次更新）"
    }
    // 用 WindowBottomSheet 而不是 OverlayBottomSheet：本弹窗由 AppNavigation 直接调用，
    // 那一层**没有任何 Scaffold**（NavHost 也在同层），而 Overlay* 要靠 Scaffold 提供的
    // LocalDialogStates 宿主才会被渲染 —— 否则注册进空列表，静默不显示。
    // 后果是「发现新版本」和「更新说明」用户根本看不到。改用自带独立 Window 的变体。
    WindowBottomSheet(
        show = show.value,
        title = title,
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            if (!fromVersion.isNullOrBlank()) {
                Text(
                    "从 v$fromVersion 升级到 v${BuildConfig.VERSION_NAME}，下面是这次跨版本包含的新内容。",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.height(12.dp))
            }
            entries.forEachIndexed { index, (version, changelog) ->
                if (index > 0) {
                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = MiuixTheme.colorScheme.outline.copy(alpha = 0.25f))
                    Spacer(Modifier.height(14.dp))
                }
                Text(
                    text = "v$version",
                    style = MiuixTheme.textStyles.subtitle,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                changelog.items.forEach { (emoji, text) ->
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Text(emoji, style = MiuixTheme.textStyles.body1)
                        Spacer(Modifier.width(8.dp))
                        Text(text, style = MiuixTheme.textStyles.body2, modifier = Modifier.weight(1f))
                    }
                }
                if (changelog.issues.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "已知问题",
                        style = MiuixTheme.textStyles.body2,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(4.dp))
                    changelog.issues.forEach { issue ->
                        Row(Modifier.padding(vertical = 2.dp)) {
                            Text("•", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.outline)
                            Spacer(Modifier.width(6.dp))
                            Text(issue, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            TextButton(text = "知道了", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

// ══════════════════════════════════════════
//  自动更新弹窗（启动时后台检查到新版本时弹出）
// ══════════════════════════════════════════

@Composable
fun AutoUpdateDialog(
    version: String,
    body: String,
    downloadUrl: String,
    releaseUrl: String,
    channelLabel: String = "",
    onDismiss: () -> Unit
) {
    val show = remember { mutableStateOf(true) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var downloadedApk by remember { mutableStateOf<java.io.File?>(null) }

    BackHandler(enabled = show.value) {
        show.value = false
        onDismiss()
    }

    // 用 WindowBottomSheet 而不是 OverlayBottomSheet：本弹窗由 AppNavigation 直接调用，
    // 那一层**没有任何 Scaffold**（NavHost 也在同层），而 Overlay* 要靠 Scaffold 提供的
    // LocalDialogStates 宿主才会被渲染 —— 否则注册进空列表，静默不显示。
    // 后果是「发现新版本」和「更新说明」用户根本看不到。改用自带独立 Window 的变体。
    WindowBottomSheet(
        show = show.value,
        title = "发现新版本 v$version",
        onDismissRequest = {
            show.value = false
            onDismiss()
        }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            if (channelLabel.isNotBlank()) {
                Text(
                    "来源：$channelLabel",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.height(8.dp))
            }
            // Release body（Markdown changelog）
            if (body.isNotBlank()) {
                MarkdownReleaseNotes(body)
            }

            Spacer(Modifier.height(16.dp))

            // 下载按钮
            if (downloadUrl.isNotEmpty()) {
                if (isDownloading) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            progress = downloadProgress,
                            size = 20.dp,
                            strokeWidth = 2.dp,
                            colors = ProgressIndicatorDefaults.progressIndicatorColors(foregroundColor = MiuixTheme.colorScheme.primary)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${(downloadProgress * 100).toInt()}%",
                            style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.primary
                        )
                    }
                } else {
                    Button(
                        onClick = {
                            val readyApk = downloadedApk
                            if (readyApk != null && readyApk.exists()) {
                                if (com.xjtu.toolbox.util.AppUpdater.canInstallPackages(context)) {
                                    com.xjtu.toolbox.util.AppUpdater.install(context, readyApk)
                                    show.value = false
                                    onDismiss()
                                } else {
                                    com.xjtu.toolbox.util.AppUpdater.requestInstallPermission(context)
                                    android.widget.Toast.makeText(
                                        context,
                                        "允许安装后，返回并点击“继续安装”",
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                }
                                return@Button
                            }
                            isDownloading = true
                            downloadProgress = 0f
                            scope.launch {
                                try {
                                    val apkFile = com.xjtu.toolbox.util.AppUpdater.download(
                                        context,
                                        com.xjtu.toolbox.util.AppUpdateInfo(
                                            version = version,
                                            notes = body,
                                            downloadUrl = downloadUrl,
                                            releaseUrl = releaseUrl
                                        )
                                    ) { progress ->
                                        scope.launch { downloadProgress = progress }
                                    }
                                    downloadedApk = apkFile
                                    isDownloading = false
                                    if (com.xjtu.toolbox.util.AppUpdater.canInstallPackages(context)) {
                                        com.xjtu.toolbox.util.AppUpdater.install(context, apkFile)
                                        show.value = false
                                        onDismiss()
                                    } else {
                                        com.xjtu.toolbox.util.AppUpdater.requestInstallPermission(context)
                                        android.widget.Toast.makeText(
                                            context,
                                            "允许安装后，返回并点击“继续安装”",
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    }
                                } catch (e: Exception) {
                                    isDownloading = false
                                    android.widget.Toast.makeText(
                                        context,
                                        "更新失败：${e.message}",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Download, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (downloadedApk != null) "继续安装" else "下载并安装")
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            TextButton(
                text = "稍后提醒",
                onClick = {
                    show.value = false
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

@Composable
private fun MarkdownReleaseNotes(markdown: String) {
    markdown.lineSequence().forEach { rawLine ->
        val line = rawLine.trim()
        if (line.isEmpty() || line == "---") return@forEach
        val headingLevel = line.takeWhile { it == '#' }.length
        val bullet = line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ")
        val quote = line.startsWith("> ")
        val cleaned = line
            .removePrefix("#".repeat(headingLevel)).trim()
            .removePrefix("- ").removePrefix("* ").removePrefix("+ ")
            .removePrefix("> ")
            .replace(Regex("""!\[([^\]]*)]\([^)]+\)"""), "$1")
            .replace(Regex("""\[([^\]]+)]\([^)]+\)"""), "$1")
            .replace("**", "")
            .replace("__", "")
            .replace("`", "")

        when {
            headingLevel > 0 -> Text(
                cleaned,
                style = if (headingLevel <= 2) MiuixTheme.textStyles.subtitle else MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 10.dp, bottom = 3.dp)
            )
            bullet -> Row(Modifier.padding(vertical = 3.dp)) {
                Text("•", color = MiuixTheme.colorScheme.primary)
                Spacer(Modifier.width(7.dp))
                Text(cleaned, style = MiuixTheme.textStyles.body2, modifier = Modifier.weight(1f))
            }
            quote -> Surface(
                shape = RoundedCornerShape(10.dp),
                color = MiuixTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
            ) {
                Text(
                    cleaned,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
                )
            }
            else -> Text(
                cleaned,
                style = MiuixTheme.textStyles.body2,
                modifier = Modifier.padding(vertical = 2.dp)
            )
        }
    }
}

private fun siteKeyForBrowserUrl(url: String): String {
    val host = runCatching { android.net.Uri.parse(url).host?.lowercase().orEmpty() }
        .getOrDefault("")
    return when {
        "assistant.xjtu.edu.cn" in host -> "jiaoxiaozhi"
        "superapp.xjtu.edu.cn" in host ||
            "transaction-service.xjtu.edu.cn" in host ||
            "message-service.xjtu.edu.cn" in host ||
            "reservation-service.xjtu.edu.cn" in host -> "super_app"
        "tyxylp.xjtu.edu.cn" in host -> "fitness"
        "rg.lib.xjtu.edu.cn" in host -> "library"
        "jwapp.xjtu.edu.cn" in host -> "jwapp"
        "ywtb.xjtu.edu.cn" in host -> "ywtb"
        "ncard.xjtu.edu.cn" in host -> "campus_card"
        "bkkq.xjtu.edu.cn" in host -> "attendance"
        "lms.xjtu.edu.cn" in host -> "lms"
        else -> "jwxt"
    }
}
