package com.xjtu.toolbox

import android.content.Intent
import android.os.Bundle
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
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
import com.xjtu.toolbox.ui.settings.SettingsScreen
import com.xjtu.toolbox.ui.components.AmbientGlow
import com.xjtu.toolbox.ui.components.ExpressiveIcon
import com.xjtu.toolbox.ui.components.ExpressivePanel
import com.xjtu.toolbox.util.CredentialStore
import com.xjtu.toolbox.widget.CampusCardWidgetUpdater
import com.xjtu.toolbox.widget.ScheduleWidgetUpdater
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_LAUNCH_ROUTE = "extra_launch_route"
        const val EXTRA_LAUNCH_TAB = "extra_launch_tab"

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

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        splash.setKeepOnScreenCondition { !isAppReady }
        super.onCreate(savedInstanceState)
        val launchRoute = intent?.getStringExtra(EXTRA_LAUNCH_ROUTE)
        val launchTab = intent?.getStringExtra(EXTRA_LAUNCH_TAB)
        launchRouteState.value = launchRoute
        launchTabState.value = launchTab ?: if (launchRoute == Routes.SCHEDULE) BottomTab.COURSES.name else null
        enableEdgeToEdge()
        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        darkModeOverrideState.value = prefs.getString("dark_mode", "system") ?: "system"

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
        setContent {
            XJTUToolBoxTheme(darkModeOverride = darkModeOverrideState.value) {
                AppNavigation(
                    initialRoute = launchRouteState.value,
                    onInitialRouteConsumed = { launchRouteState.value = null },
                    initialTab = launchTabState.value,
                    onInitialTabConsumed = { launchTabState.value = null },
                    onReady = { isAppReady = true },
                    onDarkModeChanged = { mode ->
                        darkModeOverrideState.value = mode
                        prefs.edit().putString("dark_mode", mode).apply()
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val launchRoute = intent.getStringExtra(EXTRA_LAUNCH_ROUTE)
        val launchTab = intent.getStringExtra(EXTRA_LAUNCH_TAB)
        launchRouteState.value = launchRoute
        launchTabState.value = launchTab ?: if (launchRoute == Routes.SCHEDULE) BottomTab.COURSES.name else null
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
    const val SCHOOL_COURSE = "school_course"
    const val SCHOOL_CALENDAR = "school_calendar"
    const val YELLOW_PAGE = "yellow_page"
    const val MOBILE_JIAODA = "mobile_jiaoda"
    const val FITNESS = "fitness"
    const val VIDEO_PLAYER = "video_player/{activityId}"
    const val DOWNLOAD_MANAGER = "download_manager"
    const val BROWSER = "browser?url={url}"
    const val SETTINGS = "settings"
    const val WEBVPN_CONVERTER = "webvpn_converter"
    const val AGENT = "agent"
    const val JIAOXIAOZHI = "jiaoxiaozhi"

    fun login(type: LoginType, target: String) = "login/${type.name}/$target"
    fun browser(url: String = "") = "browser?url=${java.net.URLEncoder.encode(url, "UTF-8")}"
    fun videoPlayer(activityId: Int) = "video_player/$activityId"
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

class AppLoginState {
    var activeUsername by mutableStateOf("")
    var attendanceLogin by mutableStateOf<AttendanceLogin?>(null)
    var postgraduateAttendanceLogin by mutableStateOf<AttendanceLogin?>(null)
    var jwxtLogin by mutableStateOf<JwxtLogin?>(null)
    var jwappLogin by mutableStateOf<JwappLogin?>(null)
    var ywtbLogin by mutableStateOf<YwtbLogin?>(null)
    var libraryLogin by mutableStateOf<LibraryLogin?>(null)
    var campusCardLogin by mutableStateOf<CampusCardLogin?>(null)
    var dzpzLogin by mutableStateOf<com.xjtu.toolbox.auth.DzpzLogin?>(null)
    var venueLogin by mutableStateOf<com.xjtu.toolbox.auth.VenueLogin?>(null)
    var classLogin by mutableStateOf<com.xjtu.toolbox.classreplay.ClassLogin?>(null)
    var lmsLogin by mutableStateOf<com.xjtu.toolbox.lms.LmsLogin?>(null)
    var jiaocaiLogin by mutableStateOf<com.xjtu.toolbox.jiaocai.JiaocaiLogin?>(null)
    var couponLogin by mutableStateOf<CouponLogin?>(null)
    var superAppLogin by mutableStateOf<SuperAppLogin?>(null)
    var fitnessLogin by mutableStateOf<com.xjtu.toolbox.fitness.FitnessLogin?>(null)
    var jiaoxiaozhiLogin by mutableStateOf<com.xjtu.toolbox.jiaoxiaozhi.JiaoxiaozhiLogin?>(null)

    // 持久化 CookieJar（由 ViewModel 注入；普通直连和 WebVPN 各持一个实例，cookie 物理隔离）
    var persistentCookieJar: com.xjtu.toolbox.util.PersistentCookieJar? = null
    var vpnCookieJar: com.xjtu.toolbox.util.PersistentCookieJar? = null

    /** 新会话架构入口；由 [AppLoginStateViewModel] 创建时注入。 */
    var sessionManager: com.xjtu.toolbox.auth.SessionManager? = null

    // SSO 共享 client（携带 CAS TGC cookie）
    @Volatile private var sharedClient: okhttp3.OkHttpClient? = null
    private val clientInitMutex = kotlinx.coroutines.sync.Mutex()

    // CAS TGC 未建立前所有 autoLogin 排队，避免多个并行登录各自弹 MFA
    private val mfaSerialMutex = kotlinx.coroutines.sync.Mutex()

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

    // 全局共享连接池：所有子系统复用 TLS 连接，避免重复握手
    private val sharedConnectionPool = okhttp3.ConnectionPool(5, 30, java.util.concurrent.TimeUnit.SECONDS)

    // WebVPN client（sharedClient + WebVpnInterceptor）
    @Volatile private var vpnClient: okhttp3.OkHttpClient? = null
    internal val webVpnClientOrNull: okhttp3.OkHttpClient? get() = vpnClient

    fun clearVpnClient() {
        vpnCookieJar?.clearForDomain("webvpn.xjtu.edu.cn")
        vpnCookieJar?.clearForDomain(".webvpn.xjtu.edu.cn")
        vpnCookieJar?.flushToDisk()
        vpnClient = null
        webVpnLoggedIn = false
    }

    /**
     * 校验当前 webvpn session 是否仍然有效（cookie 没过期、wengine_vpn_ticket 仍被认）。
     * 发轻量 HEAD 到 webvpn 主页，若被重定向到 cas_login 即视为失效。
     *
     * 失效时会自动 [clearVpnClient]，让调用方走 [loginWebVpn] 重建（含可能的 MFA dialog）。
     * 校园网下没有 vpnClient 时直接返回 false，调用方决定是否需要切到 webvpn 模式。
     */
    suspend fun checkWebVpnSessionAlive(): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val client = vpnClient ?: return@withContext false
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

    /** 清除所有子系统 cached login（不动 sharedClient/cookies），用于 access mode 切换 */
    fun clearAllCachedLogins() {
        attendanceLogin = null
        postgraduateAttendanceLogin = null
        jwxtLogin = null
        jwappLogin = null
        ywtbLogin = null
        libraryLogin = null
        campusCardLogin = null
        dzpzLogin = null
        venueLogin = null
        classLogin = null
        lmsLogin = null
        jiaocaiLogin = null
        couponLogin = null
        superAppLogin = null
        fitnessLogin = null
        jiaoxiaozhiLogin = null
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
    @Volatile private var webVpnLoggedIn = false

    // 网络检测结果缓存（10 分钟）
    private var campusDetectTime: Long = 0L
    private val CAMPUS_CACHE_MS = 10 * 60 * 1000L

    // 设备指纹 ID（首次登录时生成，后续系统复用以避免 MFA 重复验证）
    @Volatile private var firstVisitorId: String? = null

    // RSA 公钥缓存
    @Volatile private var cachedRsaKey: String? = null

    // 一网通办个人信息（登录后自动获取，在"我的"页面展示）
    var ywtbUserInfo by mutableStateOf<com.xjtu.toolbox.ywtb.UserInfo?>(null)

    // 校园卡缓存刷新版本：余额/最近消费落盘后递增，驱动首页智能卡片重读缓存。
    var campusCardCacheVersion by mutableIntStateOf(0)

    // 缓存的昵称（从 CredentialStore 恢复，YWTB 加载前即可显示）
    var cachedNickname by mutableStateOf<String?>(null)

    // CredentialStore 引用
    private var credentialStoreRef: CredentialStore? = null

    // 保存的凭据（内存中），用于自动登录其他系统
    var savedUsername: String = ""
        private set
    var savedPassword: String = ""
        private set
    var accountType: com.xjtu.toolbox.auth.AccountType = com.xjtu.toolbox.auth.AccountType.UNDERGRADUATE
        private set

    val hasCredentials: Boolean get() = savedUsername.isNotEmpty() && savedPassword.isNotEmpty()
    val isLoggedIn: Boolean get() = activeUsername.isNotEmpty()

    private fun selectedCasAccountType(): XJTULogin.AccountType {
        val currentAccountType = credentialStoreRef?.accountType ?: accountType
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

    /** 供 WebVPN SSO 读取共享 CAS client。 */
    fun getSharedClient(): okhttp3.OkHttpClient? = sharedClient

    /** 清除指定类型的登录缓存（用于 reAuth 失败后强制 full login） */
    fun clearLogin(type: LoginType) {
        when (type) {
            LoginType.ATTENDANCE -> attendanceLogin = null
            LoginType.POSTGRADUATE_ATTENDANCE -> postgraduateAttendanceLogin = null
            LoginType.JWXT -> jwxtLogin = null
            LoginType.JWAPP -> jwappLogin = null
            LoginType.YWTB -> ywtbLogin = null
            LoginType.LIBRARY -> libraryLogin = null
            LoginType.CAMPUS_CARD -> campusCardLogin = null
            LoginType.DZPZ -> dzpzLogin = null
            LoginType.VENUE -> venueLogin = null
            LoginType.CLASS -> classLogin = null
            LoginType.LMS -> lmsLogin = null
            LoginType.JIAOCAI -> jiaocaiLogin = null
            LoginType.COUPON -> couponLogin = null
            LoginType.SUPER_APP -> superAppLogin = null
            LoginType.FITNESS -> fitnessLogin = null
            LoginType.JIAOXIAOZHI -> jiaoxiaozhiLogin = null
        }
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
     */
    suspend fun detectCampusNetwork(): Boolean {
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
     * WebVPN 登录（校外自动：认证 WebVPN 自身，获取代理 cookie）
     *
     * 优先走 TGC SSO 免登（复用 sharedClient 的 CAS cookie），
     * 绕开新版 CAS 的 MFA 手机验证码。
     * 降级时回退到完整表单登录。
     */
    /**
     * 顶层 WebVPN 登录入口：加锁 + 等 MFA 完成。供 UI / Restore / SessionKeepAlive 等调用。
     * autoLoginInner 内部应改用 doLoginWebVpn() 无锁版避免死锁。
     */
    suspend fun loginWebVpn(): Boolean {
        if (!hasCredentials) { android.util.Log.w("WebVPN", "No credentials"); return false }
        if (webVpnLoggedIn && vpnClient != null) { android.util.Log.d("WebVPN", "Already logged in"); return true }
        if (passwordInvalidatedLatch) { android.util.Log.d("WebVPN", "halted by password latch"); return false }
        return mfaSerialMutex.withLock {
            if (webVpnLoggedIn && vpnClient != null) return@withLock true
            val ok = doLoginWebVpn()
            ok && webVpnLoggedIn && vpnClient != null
        }
    }

    /**
     * 核心 WebVPN 登录实现（无锁版）。
     * 不要直接调用，请通过 loginWebVpn() 进入。autoLoginInner 内部因可能已持锁
     * 而调用此版本避免重入死锁。
     */
    private suspend fun doLoginWebVpn(): Boolean {
        if (webVpnLoggedIn && vpnClient != null) return true
        return try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                // ── 方案1: TGC SSO 免登（优先） ──
                // sharedClient 已有 JWXT 登录后的 CAS TGC cookie，
                // 直接 GET WebVPN 登录入口，CAS 看到 TGC 会 302 带回 ST ticket，
                // WebVPN 验证 ticket 后下发 session cookie，全程无表单 → 绕开 MFA
                val ssoClient = getSharedClient()
                if (ssoClient != null) {
                    try {
                        android.util.Log.d("WebVPN", "Attempting TGC SSO via sharedClient...")
                        val ssoRequest = okhttp3.Request.Builder()
                            .url(com.xjtu.toolbox.util.WebVpnUtil.WEBVPN_LOGIN_URL)
                            .header("User-Agent", "Mozilla/5.0")
                            .build()
                        val ssoResponse = ssoClient.newCall(ssoRequest).execute()
                        val finalUrl = ssoResponse.request.url.toString()
                        val bodySnippet = ssoResponse.body?.string()?.take(500) ?: ""
                        android.util.Log.d("WebVPN", "SSO final URL: $finalUrl")
                        // 判断成功：最终 URL 在 webvpn 域且不含 cas_login，且未回退到 CAS 表单
                        val ssoSuccess = finalUrl.contains("webvpn.xjtu.edu.cn") &&
                            !finalUrl.contains("cas_login") &&
                            !bodySnippet.contains("id=\"execution\"")
                        if (ssoSuccess) {
                            android.util.Log.d("WebVPN", "TGC SSO SUCCESS — bypassed CAS form and MFA")
                            vpnClient = ssoClient.newBuilder()
                                .addInterceptor(com.xjtu.toolbox.util.WebVpnInterceptor())
                                .build()
                            webVpnLoggedIn = true
                            if (isOnCampus == false) sharedClient = vpnClient
                            return@withContext true
                        }
                        android.util.Log.d("WebVPN", "TGC SSO failed (ended on CAS login page), falling back to full login")
                    } catch (e: Exception) {
                        android.util.Log.w("WebVPN", "TGC SSO attempt failed: ${e.message}, falling back")
                    }
                }

                // ── 方案2: 完整表单登录（降级） ──
                // [关键] 必须用「带 WebVpnInterceptor 的 client」让登录链路走 webvpn 反向代理
                // 否则校外环境下 XJTULogin 内部访问 login.xjtu.edu.cn（CAS）会直连超时（公网解析为内网IP）
                vpnCookieJar?.clearForDomain("webvpn.xjtu.edu.cn")
                vpnCookieJar?.clearForDomain(".webvpn.xjtu.edu.cn")
                vpnCookieJar?.flushToDisk()
                android.util.Log.d("WebVPN", "Creating XJTULogin for WebVPN URL with WebVpnInterceptor")
                val webVpnRewriteClient = okhttp3.OkHttpClient.Builder()
                    .addInterceptor(okhttp3.brotli.BrotliInterceptor)
                    .addInterceptor(com.xjtu.toolbox.util.WebVpnInterceptor())
                    .cookieJar(vpnCookieJar ?: okhttp3.JavaNetCookieJar(java.net.CookieManager().apply {
                        setCookiePolicy(java.net.CookiePolicy.ACCEPT_ALL)
                    }))
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .connectionPool(sharedConnectionPool)
                    .build()
                val login = XJTULogin(
                    com.xjtu.toolbox.util.WebVpnUtil.WEBVPN_LOGIN_URL,
                    existingClient = webVpnRewriteClient,  // 关键：带 WebVpnInterceptor
                    visitorId = firstVisitorId,
                    cookieJar = vpnCookieJar
                )
                android.util.Log.d("WebVPN", "XJTULogin created, hasLogin=${login.hasLogin}, calling login()...")
                val result = login.login(savedUsername, savedPassword)
                android.util.Log.d("WebVPN", "login() returned: state=${result.state}, msg=${result.message}")
                if (result.state == LoginState.SUCCESS) {
                    // login.client 已是带 WebVpnInterceptor 的 client，直接复用
                    vpnClient = login.client
                    webVpnLoggedIn = true
                    // [关键] 校外让 sharedClient 也指向 vpnClient：
                    // webvpn 反向代理会把上游响应的 set-cookie domain 改写为 webvpn.xjtu.edu.cn，
                    // TGC 等实际写在 webvpn 域；sharedClient 直连 xjtu.edu.cn 那个域查不到。
                    // 让 sharedClient = vpnClient 后，所有业务调用都走 webvpn 代理，SSO 能成功。
                    if (isOnCampus == false) sharedClient = vpnClient
                    android.util.Log.d("WebVPN", "WebVPN login SUCCESS, vpnClient = webVpnRewriteClient (sharedClient aliased)")
                    true
                } else if (result.state == LoginState.REQUIRE_ACCOUNT_CHOICE) {
                    // 处理多身份账号选择
                    android.util.Log.d("WebVPN", "Account choice required, selecting configured account type")
                    val finalResult = login.login(accountType = selectedCasAccountType())
                    android.util.Log.d("WebVPN", "Account choice result: state=${finalResult.state}")
                    if (finalResult.state == LoginState.SUCCESS) {
                        vpnClient = login.client
                        webVpnLoggedIn = true
                        if (isOnCampus == false) sharedClient = vpnClient
                        true
                    } else false
                } else if (result.state == LoginState.REQUIRE_MFA) {
                    android.util.Log.w("WebVPN", "WebVPN MFA triggered, delegating to SessionManager")
                    val ctx = result.mfaContext ?: return@withContext false
                    if (ctx.flow == com.xjtu.toolbox.auth.MFAFlow.MFA_DETECT) {
                        try {
                            ctx.sendVerifyCode()
                        } catch (e: Exception) {
                            android.util.Log.w("WebVPN", "send MFA code failed: ${e.message}")
                            return@withContext false
                        }
                    }
                    val mgr = sessionManager ?: return@withContext false
                    val code = mgr.askMfaCode("webvpn", "WebVPN（校外接入）", ctx)
                        ?: return@withContext false
                    try {
                        ctx.verifyCode(code)
                    } catch (e: Exception) {
                        android.util.Log.w("WebVPN", "MFA verify failed: ${e.message}")
                        return@withContext false
                    }
                    var postMfa = login.login()
                    if (postMfa.state == LoginState.REQUIRE_ACCOUNT_CHOICE) {
                        postMfa = login.login(accountType = selectedCasAccountType())
                    }
                    if (postMfa.state == LoginState.SUCCESS) {
                        vpnClient = login.client
                        webVpnLoggedIn = true
                        if (isOnCampus == false) sharedClient = vpnClient
                        android.util.Log.d("WebVPN", "WebVPN login SUCCESS via SessionManager MFA")
                        true
                    } else {
                        android.util.Log.w("WebVPN", "WebVPN post-MFA failed: ${postMfa.state} ${postMfa.message}")
                        false
                    }
                } else {
                    android.util.Log.w("WebVPN", "WebVPN login returned non-SUCCESS state: ${result.state}")
                    false
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("WebVPN", "WebVPN login exception", e)
            false
        }
    }

    fun logout(store: CredentialStore? = null) {
        // 停止后台保活循环
        com.xjtu.toolbox.auth.SessionKeepAlive.stop()
        activeUsername = ""
        savedUsername = ""; savedPassword = ""
        attendanceLogin = null; postgraduateAttendanceLogin = null; jwxtLogin = null; jwappLogin = null
        ywtbLogin = null; libraryLogin = null; campusCardLogin = null; jiaocaiLogin = null; couponLogin = null
        superAppLogin = null; fitnessLogin = null; jiaoxiaozhiLogin = null
        sharedClient = null
        vpnClient = null
        webVpnLoggedIn = false
        isOnCampus = null
        campusDetectTime = 0L
        ywtbUserInfo = null
        firstVisitorId = null
        cachedRsaKey = null
        persistentCookieJar?.clear()
        vpnCookieJar?.clear()
        com.xjtu.toolbox.pay.PaymentCodeApi.clearCachedJwt()
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
    val persistentCookieJar = com.xjtu.toolbox.util.PersistentCookieJar(application)
    // [关键] vpnCookieJar 直接复用 persistentCookieJar：
    // WebVpnInterceptor 豁免 login.xjtu.edu.cn → vpnClient 登录得到的 TGC 写在 login.xjtu.edu.cn 域（公网）
    // sharedClient 共用同一 jar → 校外通过 vpnClient 登录后，sharedClient 访问 pay/login 等公网域也能 SSO。
    val vpnCookieJar: com.xjtu.toolbox.util.PersistentCookieJar = persistentCookieJar

    /** 新会话架构入口：双 backend、SiteSession 注册中心、MFA 状态机宿主。 */
    val sessionManager = com.xjtu.toolbox.auth.SessionManager(application)

    init {
        // 注入持久化组件（无需 LaunchedEffect，ViewModel 创建时即完成）
        loginState.persistentCookieJar = persistentCookieJar
        loginState.vpnCookieJar = vpnCookieJar
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
            register(com.xjtu.toolbox.jiaoxiaozhi.JiaoxiaozhiSiteSession())
        }
        // 设备指纹：基于 ANDROID_ID 派生（硬件级稳定，不会每次启动重生成），
        // 持久化到 EncryptedSharedPreferences。即便首次登录失败也不会因为
        // 重新生成 fp 让服务端把每次都当新设备 → trustAgent 才能真正生效。
        ensureStableFpVisitorId()
        // 恢复凭据（同步执行，确保 Compose 首帧读取到正确状态）
        loginState.restoreCredentials(credentialStore)
    }

    private fun ensureStableFpVisitorId() {
        val existing = credentialStore.loadFpVisitorId()
        if (!existing.isNullOrEmpty()) return
        val androidId = try {
            android.provider.Settings.Secure.getString(
                getApplication<android.app.Application>().contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            ) ?: ""
        } catch (_: Exception) { "" }
        val seed = "android|${android.os.Build.MANUFACTURER}|${android.os.Build.MODEL}|$androidId"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(seed.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
        credentialStore.saveFpVisitorId(hash)
        android.util.Log.d("FpVisitorId", "stable fp generated and persisted")
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

    appContext.getSharedPreferences("campus_card", 0).edit()
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
    onDarkModeChanged: (String) -> Unit = {}
) {
    val navController = rememberNavController()
    // [VM] ViewModel 保证状态跨 Configuration Change 存活
    val viewModel: AppLoginStateViewModel = viewModel()
    val loginState = viewModel.loginState
    val credentialStore = viewModel.credentialStore
    val context = LocalContext.current
    val mainScope = rememberCoroutineScope()
    var pendingMainTab by remember { mutableStateOf(initialTab) }

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

        if (route == Routes.SCHEDULE) {
            navigateToMainTab(BottomTab.COURSES)
        } else {
            navController.navigate(route) { launchSingleTop = true }
        }
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
        }
    }

    // Lifecycle Observer：App 从后台恢复时 proactive 刷新即将过期的 token
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    val lifecycleScope = rememberCoroutineScope()
    val lastResumeRefresh = remember { mutableLongStateOf(0L) }
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

                // Phase 2 全部移除：剩余 10 个子系统由 navigateWithLogin 按需触发，
                // 既不打扰用户，也不会一次性触发 N 次 mfa/detect 引发服务端风控。
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
                val shouldRefreshSessions = now - lastResumeRefresh.longValue >= 30_000L
                val shouldRefreshCampusCard = now - lastCampusCardResumeRefresh.longValue >= 5_000L
                if (shouldRefreshSessions) lastResumeRefresh.longValue = now
                if (shouldRefreshCampusCard) lastCampusCardResumeRefresh.longValue = now
                if (!shouldRefreshSessions && !shouldRefreshCampusCard) return@LifecycleEventObserver
                lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        if (shouldRefreshSessions) {
                            loginState.sessionManager?.activeSiteKeys?.forEach { key ->
                                val site = loginState.sessionManager?.getSiteOrNull(key) ?: return@forEach
                                val creds = loginState.sessionManager?.credentials ?: return@forEach
                                runCatching { site.ensureLogin(creds.first, creds.second) }
                            }
                        }
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
        val routeToLoginType: (String) -> LoginType? = { route ->
            when (route) {
                Routes.ATTENDANCE -> LoginType.ATTENDANCE
                Routes.POSTGRADUATE_ATTENDANCE -> LoginType.POSTGRADUATE_ATTENDANCE
                Routes.LIBRARY -> LoginType.LIBRARY
                Routes.CAMPUS_CARD -> LoginType.CAMPUS_CARD
                Routes.JWAPP_SCORE -> LoginType.JWAPP
                Routes.SCORE_REPORT -> LoginType.JWXT
                Routes.JUDGE -> LoginType.JWXT
                Routes.SCHOOL_COURSE -> LoginType.JWXT
                Routes.TRANSCRIPT -> LoginType.DZPZ
                Routes.VENUE -> LoginType.VENUE
                Routes.CLASS_REPLAY -> LoginType.CLASS
                Routes.LMS -> LoginType.LMS
                Routes.JIAOCAI -> LoginType.JIAOCAI
                Routes.COUPON -> LoginType.COUPON
                Routes.MOBILE_JIAODA -> LoginType.SUPER_APP
                Routes.FITNESS -> LoginType.FITNESS
                Routes.JIAOXIAOZHI -> LoginType.JIAOXIAOZHI
                Routes.SCHEDULE -> LoginType.JWXT
                else -> null
            }
        }
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
        }

        onReady()

        // 有凭据且尚未建立任何登录会话 → 启动后台恢复
        // 注意：isLoggedIn 可能仅因 username 已设而为 true，但实际登录实例为 0
        if (loginState.hasCredentials && (loginState.sessionManager?.activeSiteCount ?: 0) == 0) {
            isRestoring = true
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
    val showUpdateNotice = remember { mutableStateOf(pendingChangelog.isNotEmpty()) }
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

    LaunchedEffect(credentialStore.autoCheckUpdate) {
        if (autoUpdateCheckDone.value) return@LaunchedEffect
        if (!credentialStore.autoCheckUpdate) return@LaunchedEffect
        val now = System.currentTimeMillis()
        if (now - credentialStore.lastAutoUpdateCheckAt < com.xjtu.toolbox.util.AppUpdater.AUTO_CHECK_INTERVAL_MS) {
            return@LaunchedEffect
        }
        autoUpdateCheckDone.value = true
        credentialStore.lastAutoUpdateCheckAt = now
        try {
            val update = com.xjtu.toolbox.util.AppUpdater.check(credentialStore.updateChannel)
                ?: return@LaunchedEffect
            if (credentialStore.isUpdateNoticeSeen("auto_${update.channel}_${update.version}")) return@LaunchedEffect
            autoUpdateVersion = update.version
            autoUpdateBody = update.notes
            autoUpdateDownloadUrl = update.downloadUrl
            autoUpdateReleaseUrl = update.releaseUrl
            autoUpdateChannelKey = update.channel
            autoUpdateChannel = update.channelLabel
            showAutoUpdateDialog.value = true
        } catch (_: Exception) {
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
                isRestoring = isRestoring,
                restoreStep = restoreStep,
                pendingTab = pendingMainTab,
                onPendingTabConsumed = {
                    pendingMainTab = null
                    onInitialTabConsumed()
                },
                onWarmupRequest = { startBackgroundLoginWarmup(mainScope, force = true) }
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
            loginState.sessionManager?.getSiteOrNull("campus_card")?.let { com.xjtu.toolbox.card.CampusCardScreen(site = it, onBack = { navController.popBackStack() }) } ?: LaunchedEffect(Unit) { navController.popBackStack() }
        }
        composable(Routes.COUPON) {
            loginState.sessionManager?.getSiteOrNull("coupon")?.let { com.xjtu.toolbox.coupon.CouponScreen(site = it, onBack = { navController.popBackStack() }) } ?: LaunchedEffect(Unit) { navController.popBackStack() }
        }
        dialog(
            Routes.PAYMENT_CODE,
            dialogProperties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            // 付款码必须在校园卡登录后使用（复用 ncard JWT 访问 /berserker-app/authCode）
            loginState.sessionManager?.getSiteOrNull("campus_card")?.let { cardSite ->
                com.xjtu.toolbox.pay.PaymentCodeDialog(site = cardSite) { navController.popBackStack() }
            } ?: LaunchedEffect(Unit) { navController.popBackStack() }
        }
        composable(Routes.SCORE_REPORT) {
            loginState.sessionManager?.getSiteOrNull("jwxt")?.let { ScoreReportScreen(site = it, studentId = loginState.activeUsername, onBack = { navController.popBackStack() }) } ?: LaunchedEffect(Unit) { navController.popBackStack() }
        }
        composable(Routes.TRANSCRIPT) {
            loginState.sessionManager?.getSiteOrNull("dzpz")?.let { com.xjtu.toolbox.dzpz.TranscriptScreen(site = it, onBack = { navController.popBackStack() }) } ?: LaunchedEffect(Unit) { navController.popBackStack() }
        }
        composable(Routes.VENUE) {
            loginState.sessionManager?.getSiteOrNull("venue")?.let { com.xjtu.toolbox.venue.VenueScreen(site = it, onBack = { navController.popBackStack() }) } ?: LaunchedEffect(Unit) { navController.popBackStack() }
        }
        composable(Routes.CLASS_REPLAY) {
            loginState.sessionManager?.getSiteOrNull("class")?.let { classSite ->
                val context = androidx.compose.ui.platform.LocalContext.current
                com.xjtu.toolbox.classreplay.ClassScreen(
                    site = classSite,
                    onBack = { navController.popBackStack() },
                    onPlayReplay = { activityId ->
                        navController.navigate(Routes.videoPlayer(activityId))
                    },
                    onDownloadReplay = { activityIds ->
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
                                        val videos = detail.replayVideos.mapNotNull { video ->
                                            val realUrl = com.xjtu.toolbox.classreplay.resolveVideoUrl(classSite, video.url)
                                            realUrl?.let { video to it }
                                        }
                                        
                                        if (videos.isNotEmpty()) {
                                            downloadManager.enqueueDownloads(
                                                courseName = courseName,
                                                activityTitle = detail.title,
                                                activityId = activityId,
                                                videos = videos,
                                                audioSource = "instructor"
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
                com.xjtu.toolbox.jiaocai.JiaocaiScreen(site = it, onBack = { navController.popBackStack() })
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
                onDefaultTabChanged = { /* 下次启动生效 */ },
                onOpenDownloads = { navController.navigate(Routes.DOWNLOAD_MANAGER) }
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
    isRestoring: Boolean = false,
    restoreStep: String = "",
    pendingTab: String? = null,
    onPendingTabConsumed: () -> Unit = {},
    onWarmupRequest: () -> Unit = {}
) {
    // 读取设置的默认 Tab
    val defaultTabOrdinal = remember {
        val saved = credentialStore.defaultTab
        BottomTab.entries.indexOfFirst { it.name == saved }.coerceAtLeast(0)
    }
    var selectedTabOrdinal by rememberSaveable { mutableIntStateOf(defaultTabOrdinal) }
    val selectedTab = BottomTab.entries[selectedTabOrdinal.coerceIn(0, BottomTab.entries.size - 1)]

    // 底栏风格
    var navBarStyle by remember { mutableStateOf(credentialStore.navBarStyle) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var lastBackPressTime by remember { mutableLongStateOf(0L) }

    LaunchedEffect(pendingTab) {
        val tabName = pendingTab ?: return@LaunchedEffect
        val matched = BottomTab.entries.firstOrNull { it.name == tabName }
        if (matched != null) {
            selectedTabOrdinal = matched.ordinal
        }
        onPendingTabConsumed()
    }

    BackHandler {
        if (selectedTab != BottomTab.HOME) {
            selectedTabOrdinal = BottomTab.HOME.ordinal
        } else {
            val now = System.currentTimeMillis()
            if (now - lastBackPressTime < 2000) {
                // 2秒内连按两次返回 → 彻底退出
                (context as? android.app.Activity)?.finishAffinity()
            } else {
                lastBackPressTime = now
                android.widget.Toast.makeText(context, "再按一次返回退出", android.widget.Toast.LENGTH_SHORT).show()
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
                else -> 25_000L
            }
            autoLoginJob?.cancel() // 取消旧的登录任务，避免竞态
            autoLoginJob = scope.launch {
                try {
                    val result = kotlinx.coroutines.withTimeoutOrNull(autoLoginTimeoutMs) {
                        loginState.sessionManager?.ensureSite(type)
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
                                val r2 = kotlinx.coroutines.withTimeoutOrNull(autoLoginTimeoutMs) { loginState.sessionManager?.ensureSite(type) }
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
                            label = tab.label
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
                            label = tab.label
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
            top.yukonga.miuix.kmp.basic.NavigationRail(
                color = MiuixTheme.colorScheme.surface,
                mode = top.yukonga.miuix.kmp.basic.NavigationRailDisplayMode.IconAndText
            ) {
                BottomTab.entries.forEach { tab ->
                    top.yukonga.miuix.kmp.basic.NavigationRailItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTabOrdinal = tab.ordinal },
                        icon = if (selectedTab == tab) tab.selectedIcon else tab.unselectedIcon,
                        label = tab.label
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
                                        navBarStyle = navBarStyle
                                    )
                                    BottomTab.COURSES -> CoursesTab(loginState, ::navigateWithLogin, onNavigateWithNetCheck, scrollBehavior = coursesScrollBehavior, navBarStyle = navBarStyle, onSubtitleChange = { courseSubtitle = it }, onActionsChange = { courseHeaderActions = it }, onBottomContentChange = { courseHeaderBottomContent = it })
                                    BottomTab.TOOLS -> ToolsTab(loginState, ::navigateWithLogin, onNavigateWithNetCheck, scrollBehavior = toolsScrollBehavior, navBarStyle = navBarStyle)
                                    BottomTab.PROFILE -> ProfileTab(
                                        loginState,
                                        ::navigateWithLogin,
                                        credentialStore,
                                        scrollBehavior = profileScrollBehavior,
                                        onNavigateToDownloads = { navController.navigate(Routes.DOWNLOAD_MANAGER) },
                                        onNavigateToSettings = { navController.navigate(Routes.SETTINGS) { launchSingleTop = true } },
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
                            Button(
                                onClick = {
                                    if (codeInput.length != 6) { err = "请输入6位验证码"; return@Button }
                                    verifying = true; err = null
                                    if (!req.submit(codeInput)) {
                                        err = "提交失败"
                                        verifying = false
                                    }
                                },
                                enabled = !verifying,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (verifying) {
                                    CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(if (verifying) "验证中…" else "验证并登录")
                            }
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
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        TextButton(
                            text = "稍后",
                            onClick = { loginState.passwordInvalidatedDialogVisible = false },
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = {
                                loginState.passwordInvalidatedDialogVisible = false
                                navController.navigate(Routes.SETTINGS) {
                                    launchSingleTop = true
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("去更新密码")
                        }
                    }
                }
            }
        }
        }
        }
    }
}
// ══════════════════════════════════════════
//  Tab 1 — 首页
// ══════════════════════════════════════════

@Composable
private fun HomeHero(
    greetingName: String,
    dateLabel: String,
    isLoggedIn: Boolean,
    isFocusLoaded: Boolean,
    reminder: ScheduleReminderInfo?,
    balance: Float,
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
    val blue = androidx.compose.ui.graphics.Color(0xFF315FD4)
    val violet = androidx.compose.ui.graphics.Color(0xFF7357D8)

    // 重点信息决定整卡点击去向：有课→日程；无课有余额→校园卡；否则→个人页
    val heroClick = when {
        !isLoggedIn -> onOpenProfile
        reminder != null -> onOpenCourses
        balance >= 0f -> onOpenCard
        else -> onOpenProfile
    }

    ExpressivePanel(
        modifier = Modifier.fillMaxWidth().heightIn(min = 168.dp),
        accent = blue,
        cornerRadius = 28.dp,
        onClick = heroClick,
    ) {
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.linearGradient(
                        listOf(
                            blue.copy(alpha = 0.16f),
                            violet.copy(alpha = 0.08f),
                            MiuixTheme.colorScheme.surface.copy(alpha = 0.05f),
                        ),
                    ),
                ),
        )
        AmbientGlow(
            color = androidx.compose.ui.graphics.Color(0xFF40B8FF),
            modifier = Modifier.align(Alignment.TopEnd).offset(x = 36.dp, y = (-40).dp),
            size = 190.dp,
        )
        Image(
            painter = painterResource(R.drawable.home_campus_hero),
            contentDescription = "兴庆校区主楼",
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(132.dp)
                .offset(x = 8.dp, y = 8.dp),
            contentScale = ContentScale.Fit,
        )
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 20.dp, top = 18.dp, bottom = 18.dp, end = 116.dp),
        ) {
            Text(
                dateLabel,
                style = MiuixTheme.textStyles.footnote1,
                color = blue,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (greetingName.isBlank()) greeting else "$greeting，$greetingName",
                style = MiuixTheme.textStyles.title3,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            // ── 重点信息区：下一节课 > 余额 > 引导文案，全部单行防换行 ──
            when {
                !isLoggedIn -> {
                    HeroFocusLine(
                        icon = Icons.Default.AccountCircle,
                        primary = "登录以使用全部功能",
                        secondary = "课表、余额和通知会显示在这里",
                        accent = blue,
                    )
                }
                !isFocusLoaded -> {
                    HeroFocusLine(
                        icon = Icons.Default.Schedule,
                        primary = "正在读取今日安排…",
                        secondary = null,
                        accent = blue,
                    )
                }
                reminder != null -> {
                    val now = java.time.LocalDateTime.now()
                    val minutesUntil = java.time.Duration.between(now, reminder.startAt)
                        .toMinutes().coerceAtLeast(0)
                    val dayLabel = formatScheduleReminderDateLabel(
                        reminder.startAt.toLocalDate(), now.toLocalDate()
                    )
                    val startLabel = formatMinuteClock(reminder.startAt.hour * 60 + reminder.startAt.minute)
                    val detail = buildString {
                        append("$dayLabel $startLabel · ${reminder.name}")
                        if (reminder.location.isNotBlank()) append(" · ${reminder.location}")
                    }
                    HeroFocusLine(
                        icon = Icons.Default.Schedule,
                        primary = formatScheduleReminderEta(minutesUntil),
                        secondary = detail,
                        accent = blue,
                    )
                }
                balance >= 0f -> {
                    HeroFocusLine(
                        icon = Icons.Default.CreditCard,
                        primary = "校园卡余额 ¥${"%.2f".format(balance)}",
                        secondary = "未来两周暂无日程",
                        accent = blue,
                    )
                }
                else -> {
                    HeroFocusLine(
                        icon = Icons.Default.EventNote,
                        primary = "未来两周暂无日程",
                        secondary = "点击查看个人主页",
                        accent = blue,
                    )
                }
            }
            // 有课时余额退居次要位置：重点信息行下方的小胶囊，独立点击进校园卡
            if (isLoggedIn && reminder != null && balance >= 0f) {
                Spacer(Modifier.height(10.dp))
                val isLowBalance = balance <= 30f
                val pillColor = if (isLowBalance) MiuixTheme.colorScheme.error else blue
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = pillColor.copy(alpha = 0.10f),
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = SinkFeedback(),
                        onClick = onOpenCard
                    )
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CreditCard, null, Modifier.size(13.dp), tint = pillColor)
                        Spacer(Modifier.width(5.dp))
                        Text(
                            if (isLowBalance) "余额 ¥${"%.2f".format(balance)} · 该充值了"
                            else "余额 ¥${"%.2f".format(balance)}",
                            style = MiuixTheme.textStyles.footnote2,
                            color = pillColor,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** Hero 卡内的重点信息行：图标徽章 + 主行（粗体单行）+ 可选次行（单行省略）。 */
@Composable
private fun HeroFocusLine(
    icon: ImageVector,
    primary: String,
    secondary: String?,
    accent: androidx.compose.ui.graphics.Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ExpressiveIcon(icon = icon, color = accent, size = 36.dp, iconSize = 18.dp)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                primary,
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (secondary != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    secondary,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
    navBarStyle: String = "floating"
) {
    // ── 仪表盘数据：下一节日程 + 校园卡余额缓存（供 Hero 重点信息区使用）──
    val heroContext = LocalContext.current
    var scheduleReminderState by remember { mutableStateOf<ScheduleReminderInfo?>(null) }
    var isScheduleReminderLoaded by remember { mutableStateOf(false) }
    val cardPrefs = remember { heroContext.getSharedPreferences("campus_card", 0) }
    var cachedBalance by remember { mutableStateOf(cardPrefs.getFloat("card_balance_cache", -1f)) }
    LaunchedEffect(loginState.campusCardCacheVersion) {
        cachedBalance = cardPrefs.getFloat("card_balance_cache", -1f)
    }
    LaunchedEffect(Unit) {
        val loadedReminder = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val dataCache = com.xjtu.toolbox.util.DataCache(heroContext)
                val gson = com.google.gson.Gson()
                val termListJson = dataCache.get("schedule_term_list", Long.MAX_VALUE)
                val termList = if (termListJson != null) {
                    gson.fromJson(termListJson, Array<String>::class.java)?.toList() ?: emptyList()
                } else emptyList<String>()
                val termCode = termList.firstOrNull() ?: return@withContext null
                val apiCourses = com.xjtu.toolbox.schedule.ScheduleCache
                    .readOptimizedCourses(dataCache, gson, termCode, Long.MAX_VALUE)
                    ?: com.xjtu.toolbox.schedule.ScheduleCache
                        .readRawCourses(dataCache, gson, termCode, Long.MAX_VALUE)
                    ?: emptyList()
                val customCourses = try {
                    com.xjtu.toolbox.util.AppDatabase.getInstance(heroContext)
                        .customCourseDao().getByTerm(termCode)
                        .map { it.toCourseItem() }
                } catch (_: Exception) { emptyList() }
                val allSchedules = apiCourses + customCourses
                val startDateJson = dataCache.get("start_date_$termCode", Long.MAX_VALUE)
                val startDateStr = if (startDateJson != null) gson.fromJson(startDateJson, String::class.java) else null
                val startDate = if (!startDateStr.isNullOrBlank()) runCatching { java.time.LocalDate.parse(startDateStr) }.getOrNull() else null
                if (startDate == null) {
                    return@withContext null
                }
                val holidayDates = try {
                    com.xjtu.toolbox.schedule.HolidayApi.getHolidayDates(heroContext)
                } catch (_: Exception) {
                    emptyMap()
                }

                val today = java.time.LocalDate.now()
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
                        return@withContext ScheduleReminderInfo(
                            name = schedule.name,
                            location = schedule.location,
                            startAt = startAt,
                            endAt = endAt
                        )
                    }
                }
                null
            } catch (_: Exception) {
                null
            }
        }
        scheduleReminderState = loadedReminder
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
                isLoggedIn = loginState.isLoggedIn,
                isFocusLoaded = isScheduleReminderLoaded,
                reminder = scheduleReminderState,
                balance = cachedBalance,
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

        // ── Zone B: 常用功能（按频率自适应的 4 个大图标，无卡片背景）──
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                "常用功能",
                style = MiuixTheme.textStyles.headline1,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
            )
            val colorGreen = androidx.compose.ui.graphics.Color(0xFF2E7D32)
            val colorOrange = androidx.compose.ui.graphics.Color(0xFFE65100)
            val colorPurple = androidx.compose.ui.graphics.Color(0xFF7B1FA2)
            val colorTeal = androidx.compose.ui.graphics.Color(0xFF00796B)
            val colorAmber = androidx.compose.ui.graphics.Color(0xFFF9A825)
            val colorIndigo = androidx.compose.ui.graphics.Color(0xFF283593)
            val ctxQuick = LocalContext.current
            // 快捷入口候选（根据使用频率动态选 top-4，首次用默认顺序）
            data class Quick(val key: String, val icon: ImageVector, val label: String, val color: androidx.compose.ui.graphics.Color, val onClick: () -> Unit)
            val quickPool = listOf(
                Quick(Routes.CAMPUS_CARD, Icons.Default.CreditCard, "校园卡", colorGreen) { onNavigateWithLogin(Routes.CAMPUS_CARD, LoginType.CAMPUS_CARD) },
                Quick(Routes.EMPTY_ROOM, Icons.Default.LocationOn, "空闲教室", colorIndigo) { onNavigateWithLogin(Routes.EMPTY_ROOM, LoginType.JWXT) },
                Quick(Routes.PAYMENT_CODE, Icons.Default.QrCode, "付款码", colorTeal) { onNavigateWithLogin(Routes.PAYMENT_CODE, LoginType.CAMPUS_CARD) },
                Quick(Routes.NOTIFICATION, Icons.Default.Notifications, "通知", colorOrange) { onNavigate(Routes.NOTIFICATION) },
                Quick(Routes.JWAPP_SCORE, Icons.Default.Assessment, "成绩", colorPurple) { onNavigateWithLogin(Routes.JWAPP_SCORE, LoginType.JWAPP) },
                Quick(Routes.COUPON, Icons.Default.Restaurant, "加餐券", colorAmber) { onNavigateWithLogin(Routes.COUPON, LoginType.COUPON) },
                Quick(Routes.LIBRARY, Icons.Default.Chair, "图书馆", colorOrange) { onNavigateWithLogin(Routes.LIBRARY, LoginType.LIBRARY) },
                Quick(Routes.LMS, Icons.Default.School, "思源", colorIndigo) { onNavigateWithLogin(Routes.LMS, LoginType.LMS) },
                Quick(Routes.AGENT, Icons.Default.SmartToy, "问屁岱", colorTeal) { onNavigate(Routes.AGENT) },
                Quick(Routes.JIAOXIAOZHI, Icons.Default.AutoAwesome, "交晓智", colorPurple) {
                    onNavigateWithLogin(Routes.JIAOXIAOZHI, LoginType.JIAOXIAOZHI)
                }
            )
            val quickKeys = remember(quickPool) {
                com.xjtu.toolbox.util.ServiceUsageTracker.topKeys(
                    ctxQuick,
                    quickPool.map { it.key },
                    n = 4,
                    fallback = listOf(Routes.CAMPUS_CARD, Routes.EMPTY_ROOM, Routes.AGENT, Routes.NOTIFICATION)
                )
            }
            val quickShown = quickKeys.mapNotNull { k -> quickPool.find { it.key == k } }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                quickShown.forEach { q ->
                    HomeQuickAction(q.icon, q.label, q.color) {
                        com.xjtu.toolbox.util.ServiceUsageTracker.record(ctxQuick, q.key)
                        q.onClick()
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── Zone C: 场景入口（3 张横向特色卡：上课 / 校园生活 / 学业）──
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                "场景入口",
                style = MiuixTheme.textStyles.headline1,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
            )
        }
        run {
            val ctxScene = LocalContext.current
            fun go(key: String, action: () -> Unit): () -> Unit = {
                com.xjtu.toolbox.util.ServiceUsageTracker.record(ctxScene, key)
                action()
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                HomeSceneCard(
                    title = "问屁岱",
                    subtitle = "校园助手、工具调用与交晓智",
                    icon = Icons.Default.SmartToy,
                    accent = androidx.compose.ui.graphics.Color(0xFF00695C),
                    entries = listOf(
                        "问屁岱" to go(Routes.AGENT) { onNavigate(Routes.AGENT) },
                        "交晓智" to go(Routes.JIAOXIAOZHI) { onNavigateWithLogin(Routes.JIAOXIAOZHI, LoginType.JIAOXIAOZHI) },
                    )
                )
                HomeSceneCard(
                    title = "上课",
                    subtitle = "课表、自习与课程内容",
                    icon = Icons.Default.School,
                    accent = androidx.compose.ui.graphics.Color(0xFF315FD4),
                    entries = listOf(
                        "日程" to go(Routes.SCHEDULE) { onNavigateToCourses() },
                        "空闲教室" to go(Routes.EMPTY_ROOM) { onNavigateWithLogin(Routes.EMPTY_ROOM, LoginType.JWXT) },
                        "思源" to go(Routes.LMS) { onNavigateWithLogin(Routes.LMS, LoginType.LMS) },
                    )
                )
                HomeSceneCard(
                    title = "校园生活",
                    subtitle = "校园支付与生活服务",
                    icon = Icons.Default.Restaurant,
                    accent = androidx.compose.ui.graphics.Color(0xFF2E7D32),
                    entries = listOf(
                        "校园卡" to go(Routes.CAMPUS_CARD) { onNavigateWithLogin(Routes.CAMPUS_CARD, LoginType.CAMPUS_CARD) },
                        "付款码" to go(Routes.PAYMENT_CODE) { onNavigateWithLogin(Routes.PAYMENT_CODE, LoginType.CAMPUS_CARD) },
                        "加餐券" to go(Routes.COUPON) { onNavigateWithLogin(Routes.COUPON, LoginType.COUPON) },
                    )
                )
                HomeSceneCard(
                    title = "学业",
                    subtitle = "成绩、评教和教材",
                    icon = Icons.Default.Assessment,
                    accent = androidx.compose.ui.graphics.Color(0xFF7B1FA2),
                    entries = listOf(
                        "成绩" to go(Routes.JWAPP_SCORE) { onNavigateWithLogin(Routes.JWAPP_SCORE, LoginType.JWAPP) },
                        "评教" to go(Routes.JUDGE) { onNavigateWithLogin(Routes.JUDGE, LoginType.JWXT) },
                        "教材" to go(Routes.JIAOCAI) { onNavigateWithLogin(Routes.JIAOCAI, LoginType.JIAOCAI) },
                    )
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── Zone D: 更多服务（纯图标宫格：无副标题、无卡片背景，低频工具集中于此）──
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                "更多服务",
                style = MiuixTheme.textStyles.headline1,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )
            val ctx = LocalContext.current
            data class MoreSvc(
                val key: String,
                val icon: ImageVector,
                val title: String,
                val color: androidx.compose.ui.graphics.Color,
                val onClick: () -> Unit
            )
            val moreServices = listOf(
                MoreSvc(Routes.LIBRARY, Icons.Default.Chair, "图书馆", androidx.compose.ui.graphics.Color(0xFFE65100)) { onNavigateWithLogin(Routes.LIBRARY, LoginType.LIBRARY) },
                MoreSvc(Routes.NOTIFICATION, Icons.Default.Notifications, "通知公告", MiuixTheme.colorScheme.error) { onNavigate(Routes.NOTIFICATION) },
                MoreSvc(Routes.ATTENDANCE, Icons.Default.DateRange, "考勤", androidx.compose.ui.graphics.Color(0xFF4E342E)) { onNavigateWithLogin(Routes.ATTENDANCE, LoginType.ATTENDANCE) },
                MoreSvc(Routes.POSTGRADUATE_ATTENDANCE, Icons.Default.DateRange, "研考勤", androidx.compose.ui.graphics.Color(0xFF4E342E)) { onNavigateWithLogin(Routes.POSTGRADUATE_ATTENDANCE, LoginType.POSTGRADUATE_ATTENDANCE) },
                MoreSvc(Routes.TRANSCRIPT, Icons.Default.Description, "成绩单", androidx.compose.ui.graphics.Color(0xFF283593)) { onNavigateWithLogin(Routes.TRANSCRIPT, LoginType.DZPZ) },
                MoreSvc(Routes.VENUE, Icons.Default.Place, "场馆预订", androidx.compose.ui.graphics.Color(0xFF00838F)) { onNavigateWithLogin(Routes.VENUE, LoginType.VENUE) },
                MoreSvc(Routes.CLASS_REPLAY, Icons.Default.OndemandVideo, "课程回放", androidx.compose.ui.graphics.Color(0xFF512DA8)) { onNavigateWithLogin(Routes.CLASS_REPLAY, LoginType.CLASS) },
                MoreSvc(Routes.SCHOOL_COURSE, Icons.Default.TravelExplore, "课程查询", androidx.compose.ui.graphics.Color(0xFF00838F)) { onNavigateWithLogin(Routes.SCHOOL_COURSE, LoginType.JWXT) },
                MoreSvc(Routes.SCHOOL_CALENDAR, Icons.Default.EventNote, "校历", androidx.compose.ui.graphics.Color(0xFF00796B)) { onNavigate(Routes.SCHOOL_CALENDAR) },
                MoreSvc(Routes.YELLOW_PAGE, Icons.Default.ContactPhone, "校园黄页", androidx.compose.ui.graphics.Color(0xFF1565C0)) { onNavigate(Routes.YELLOW_PAGE) },
                MoreSvc(Routes.MOBILE_JIAODA, Icons.Default.PhoneAndroid, "移动交大", androidx.compose.ui.graphics.Color(0xFF005BAC)) { onNavigateWithLogin(Routes.MOBILE_JIAODA, LoginType.SUPER_APP) },
                MoreSvc(Routes.FITNESS, Icons.Default.DirectionsRun, "体测查询", androidx.compose.ui.graphics.Color(0xFF00897B)) { onNavigateWithLogin(Routes.FITNESS, LoginType.FITNESS) },
                MoreSvc(Routes.WEBVPN_CONVERTER, Icons.Default.VpnKey, "WebVPN", androidx.compose.ui.graphics.Color(0xFF4E342E)) { onNavigate(Routes.WEBVPN_CONVERTER) },
                MoreSvc(Routes.AGENT, Icons.Default.SmartToy, "屁岱", androidx.compose.ui.graphics.Color(0xFF00695C)) { onNavigate(Routes.AGENT) },
                MoreSvc(Routes.JIAOXIAOZHI, Icons.Default.AutoAwesome, "交晓智", androidx.compose.ui.graphics.Color(0xFF6750A4)) {
                    onNavigateWithLogin(Routes.JIAOXIAOZHI, LoginType.JIAOXIAOZHI)
                }
            )
            moreServices.chunked(4).forEach { rowItems ->
                Row(Modifier.fillMaxWidth()) {
                    rowItems.forEach { svc ->
                        HomeGridItem(
                            icon = svc.icon,
                            label = svc.title,
                            color = svc.color,
                            modifier = Modifier.weight(1f)
                        ) {
                            com.xjtu.toolbox.util.ServiceUsageTracker.record(ctx, svc.key)
                            svc.onClick()
                        }
                    }
                    repeat(4 - rowItems.size) { Spacer(Modifier.weight(1f)) }
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
    onBottomContentChange: ((@Composable () -> Unit)?) -> Unit = {}
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
            contentBottomPadding = bottomReserve
        )
    }
}

// ══════════════════════════════════════════
//  Tab 3 — 仲英学辅资料站（搭建中）
// ══════════════════════════════════════════

@Composable
private fun ToolsTab(
    loginState: AppLoginState,
    onNavigateWithLogin: (String, LoginType) -> Unit,
    onNavigate: (String) -> Unit,
    scrollBehavior: ScrollBehavior? = null,
    navBarStyle: String = "floating"
) {
    Column(
        Modifier
            .fillMaxSize()
            .then(if (scrollBehavior != null) Modifier.nestedScroll(scrollBehavior.nestedScrollConnection) else Modifier)
            .overScrollVertical()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))
        Icon(
            Icons.Default.Construction,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp)
        )
        Spacer(Modifier.height(20.dp))
        Text(
            "仲英学辅资料站",
            style = MiuixTheme.textStyles.title2,
            fontWeight = FontWeight.Bold,
            color = MiuixTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "搭建中",
            style = MiuixTheme.textStyles.subtitle,
            color = MiuixTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(20.dp))
        Text(
            "本页将汇集仲英书院学辅资料、复习指南、课程笔记等内容，敬请期待。",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        if (navBarStyle == "floating") Spacer(Modifier.height(96.dp))
    }
}

// ══════════════════════════════════════════
//  Tab 4 — 我的（含统一登录）
// ══════════════════════════════════════════

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
    scrollBehavior: ScrollBehavior? = null,
    onNavigateToDownloads: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
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

    // Srun 校园网首次配置弹窗状态
    val showSrunSetupSheet = remember { mutableStateOf(false) }
    var srunSetupUsername by remember { mutableStateOf("") }
    var srunSetupPassword by remember { mutableStateOf("") }
    var srunSetupSaving by remember { mutableStateOf(false) }
    var srunSetupHint by remember { mutableStateOf<String?>(null) }

    // 智能登录：JWXT→核心登录→YWTB后台
    fun loginAllSystems(user: String, pwd: String) {
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
                    }
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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            credentialStore.srunSetupAsked = true
                            credentialStore.srunAutoLoginEnabled = false
                            showSrunSetupSheet.value = false
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            color = MiuixTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Text("暂不开启", color = MiuixTheme.colorScheme.onSecondaryContainer)
                    }
                    Button(
                        onClick = {
                            if (srunSetupUsername.isBlank() || srunSetupPassword.isBlank()) {
                                srunSetupHint = "请填写账号和密码（或选择跳过）"
                                return@Button
                            }
                            srunSetupSaving = true
                            credentialStore.saveSrunCredentials(srunSetupUsername.trim(), srunSetupPassword)
                            credentialStore.srunAutoLoginEnabled = true
                            credentialStore.srunSetupAsked = true
                            srunSetupSaving = false
                            showSrunSetupSheet.value = false
                        },
                        enabled = !srunSetupSaving,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (srunSetupSaving) "保存中..." else "启用并保存")
                    }
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
            modifier = Modifier.fillMaxWidth(),
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
                            if (loginState.isLoggedIn) {
                                val initial = (loginState.ywtbUserInfo?.userName ?: loginState.cachedNickname ?: loginState.activeUsername).take(1)
                                Text(initial, color = MiuixTheme.colorScheme.onPrimary, style = MiuixTheme.textStyles.title2, fontWeight = FontWeight.Bold)
                            } else {
                                Icon(Icons.Outlined.Person, null, Modifier.size(36.dp), tint = MiuixTheme.colorScheme.onPrimary)
                            }
                        }
                    }
                    Spacer(Modifier.width(20.dp))
                    Column {
                        if (loginState.isLoggedIn) {
                            Text(
                                loginState.ywtbUserInfo?.userName ?: loginState.cachedNickname ?: loginState.activeUsername,
                                style = MiuixTheme.textStyles.title2,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(2.dp))
                            // 学号
                            Text(
                                loginState.activeUsername,
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                            // 身份 · 院系（YWTB）
                            val subtitle = loginState.ywtbUserInfo?.let { info ->
                                "${info.identityTypeName} · ${info.organizationName}"
                            }
                            subtitle?.let {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    it,
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        } else {
                            Text("XJTU 工具箱", style = MiuixTheme.textStyles.title2, fontWeight = FontWeight.Bold)
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

                // 学期 / 教学周 信息（使用 JWXT site）
                loginState.sessionManager?.getSiteOrNull("jwxt")?.let { jwxtSite ->
                    var currentWeekText by remember { mutableStateOf<String?>(null) }
                    var termText by remember { mutableStateOf<String?>(null) }
                    var schoolYear by remember { mutableStateOf<String?>(null) }
                    var countdownText by remember { mutableStateOf<String?>(null) }
                    LaunchedEffect(jwxtSite) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            try {
                                val api = com.xjtu.toolbox.schedule.ScheduleApi(jwxtSite)
                                val termCode = api.getCurrentTerm()    // e.g. "2025-2026-2"
                                val parts = termCode.split("-")
                                if (parts.size >= 2) schoolYear = "${parts[0]}-${parts[1]} 学年"
                                termText = when (parts.getOrNull(2)) {
                                    "1" -> "第一学期"
                                    "2" -> "第二学期"
                                    else -> termCode
                                }
                                try {
                                    val startDate = api.getStartOfTerm(termCode)
                                    val today = java.time.LocalDate.now()
                                    val daysBetween = java.time.temporal.ChronoUnit.DAYS.between(startDate, today)
                                    val rawWeek = ((daysBetween / 7) + 1).toInt()
                                    val totalWeeks = 20
                                    when {
                                        rawWeek in 1..totalWeeks -> currentWeekText = "第${rawWeek}周"
                                        rawWeek > totalWeeks -> {
                                            currentWeekText = null
                                            val m = java.time.LocalDate.now().monthValue
                                            termText = if (m in 7..8) "暑假" else "假期"
                                        }
                                        else -> {
                                            // 未开学 → 计算倒计时
                                            currentWeekText = null
                                            val daysUntil = java.time.temporal.ChronoUnit.DAYS.between(today, startDate)
                                            countdownText = if (daysUntil > 0) "距开学还有 ${daysUntil} 天" else "即将开学"
                                        }
                                    }
                                } catch (_: Exception) { }
                            } catch (e: Exception) {
                                val m = java.time.LocalDate.now().monthValue
                                termText = if (m in 2..7) "第二学期" else "第一学期"
                            }
                        }
                    }
                    if (currentWeekText != null || termText != null || schoolYear != null) {
                        Spacer(Modifier.height(12.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            cornerRadius = 20.dp,
                            colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant)
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(20.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.CalendarMonth, null, Modifier.size(20.dp), tint = MiuixTheme.colorScheme.primaryVariant)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    schoolYear?.let {
                                        Text(it, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium)
                                    }
                                    termText?.let { Text(it, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
                                    countdownText?.let {
                                        Text(it, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.primaryVariant, fontWeight = FontWeight.Medium)
                                    }
                                }
                                currentWeekText?.let {
                                    Surface(shape = RoundedCornerShape(6.dp), color = MiuixTheme.colorScheme.primaryVariant.copy(alpha = 0.12f)) {
                                        Text(it, Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.primaryVariant, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ━━ 下载记录入口卡片 ━━
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
                            Text("下载记录", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium)
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

                // ━━ 设置入口 + 退出登录 ━━
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 20.dp,
                    colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant)
                ) {
                    Column {
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
                                summary = "退出登录后大量功能将不可用，同时清除所有缓存。确定要退出吗？",
                                onDismissRequest = { showLogoutDialog.value = false }
                            ) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    TextButton(
                                        text = "取消",
                                        onClick = { showLogoutDialog.value = false },
                                        modifier = Modifier.weight(1f)
                                    )
                                    Button(
                                        onClick = {
                                            showLogoutDialog.value = false
                                            loginState.logout(credentialStore)
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) { Text("退出登录") }
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

@Composable
private fun HomeQuickAction(icon: ImageVector, label: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = SinkFeedback(),
                onClick = onClick
            )
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        ExpressiveIcon(icon = icon, color = color)
        Spacer(Modifier.height(8.dp))
        Text(label, style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Medium)
    }
}

/**
 * 场景入口特色卡：标题 + 一句话场景描述 + 最多 3 个子入口胶囊。
 * 整卡点击进入第一个入口，胶囊各自可点。
 */
@Composable
private fun HomeSceneCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: androidx.compose.ui.graphics.Color,
    entries: List<Pair<String, () -> Unit>>,
) {
    ExpressivePanel(
        modifier = Modifier.width(236.dp),
        accent = accent,
        cornerRadius = 24.dp,
        onClick = entries.firstOrNull()?.second,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ExpressiveIcon(icon = icon, color = accent, size = 38.dp, iconSize = 20.dp)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(title, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Bold)
                    Text(
                        subtitle,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                entries.forEach { (label, action) ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = accent.copy(alpha = 0.10f),
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = SinkFeedback(),
                            onClick = action
                        )
                    ) {
                        Text(
                            label,
                            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MiuixTheme.textStyles.footnote1,
                            color = accent,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
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
            .clip(RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = SinkFeedback(),
                onClick = onClick
            )
            .padding(vertical = 10.dp)
    ) {
        ExpressiveIcon(icon = icon, color = color, size = 46.dp, iconSize = 23.dp)
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            style = MiuixTheme.textStyles.footnote1,
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
    OverlayBottomSheet(
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
            Spacer(Modifier.height(20.dp))
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("知道了")
            }
            Spacer(Modifier.height(16.dp))
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

    OverlayBottomSheet(
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
        else -> "jwxt"
    }
}
