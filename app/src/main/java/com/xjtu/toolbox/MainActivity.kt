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
import androidx.compose.foundation.background
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
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
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
            val vm = androidx.lifecycle.ViewModelProvider(this)[AppLoginStateViewModel::class.java]
            val s = vm.loginState
            val logins = listOfNotNull(
                s.jwxtLogin, s.jwappLogin, s.ywtbLogin, s.attendanceLogin, s.postgraduateAttendanceLogin,
                s.dzpzLogin, s.venueLogin, s.classLogin, s.lmsLogin, s.couponLogin
            )
            com.xjtu.toolbox.auth.SessionKeepAlive.KeepAliveSnapshot(
                logins = logins,
                vpnClient = s.webVpnClientOrNull
            )
        }
        // 启动循环（内部会读 KeepAlivePrefs.isEnabled，未开启则直接跳过）
        com.xjtu.toolbox.auth.SessionKeepAlive.start(this)
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
    const val NEO_COURSE = "neo_course"
    const val JIAOCAI = "jiaocai"
    const val SCHOOL_COURSE = "school_course"
    const val SCHOOL_CALENDAR = "school_calendar"
    const val VIDEO_PLAYER = "video_player/{activityId}"
    const val DOWNLOAD_MANAGER = "download_manager"
    const val BROWSER = "browser?url={url}"
    const val SETTINGS = "settings"
    const val WEBVPN_CONVERTER = "webvpn_converter"

    fun login(type: LoginType, target: String) = "login/${type.name}/$target"
    fun browser(url: String = "") = "browser?url=${java.net.URLEncoder.encode(url, "UTF-8")}"
    fun videoPlayer(activityId: Int) = "video_player/$activityId"
}

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

    // 持久化 CookieJar（由外部传入，整个 App 共享一个实例）
    var persistentCookieJar: com.xjtu.toolbox.util.PersistentCookieJar? = null

    // [B2] WebVPN 持久化 CookieJar（独立实例，校外冷启动可复用 VPN session）
    var vpnCookieJar: com.xjtu.toolbox.util.PersistentCookieJar? = null

    /** 新会话架构入口；由 [AppLoginStateViewModel] 在创建时注入。 */
    var sessionManager: com.xjtu.toolbox.auth.SessionManager? = null

    // SSO: 共享的 OkHttpClient（携带 CAS TGC cookie），实现一次登录、所有系统自动认证
    @Volatile private var sharedClient: okhttp3.OkHttpClient? = null

    // 并发保护：多个 autoLogin 并行时，保证 sharedClient 只初始化一次
    private val clientInitMutex = kotlinx.coroutines.sync.Mutex()

    // [MFA] MFA 串行化锁
    // 任何「可能触发 MFA」的入口（autoLogin / loginWebVpn）启动时都要持有此锁，
    // 让 pendingMfaLogin 不会被并行覆盖、UI 不会接连弹多个 MFA 对话框。
    // 用 Mutex.holdsLock(this) 实现重入：autoLogin 内部调用 loginWebVpn 不会死锁。
    private val mfaSerialMutex = kotlinx.coroutines.sync.Mutex()
    /** 是否已有任意子系统登录成功（即 sharedClient 已持有 CAS TGC，可以 SSO） */
    val ssoEstablished: Boolean get() = loginCount > 0
    /** 用户最近一次取消 MFA 的时戳（毫秒）。为避免后台 warmup 反复触发 MFA。 */
    @Volatile var mfaCancelledAt: Long = 0L

    /** 用户处于 MFA 冷却期且尚无任何成功登录 → 暂时不触发任何 autoLogin。 */
    val isInMfaCooldown: Boolean
        get() = !ssoEstablished && System.currentTimeMillis() - mfaCancelledAt < MFA_COOLDOWN_MS

    companion object {
        /** 用户取消 MFA 后的冷却期：仅阻止后台 warmup 反复触发 MFA。
         *  用户主动点击功能（navigateWithLogin / loginWebVpn 主动调用）会传 `force=true` 无视此冷却。 */
        const val MFA_COOLDOWN_MS = 60_000L  // 60 秒
    }

    // ── 密码全局失效熔断 ──────────────────────────────────────────
    // 任一子系统确认凭据无效时设置；所有后续 autoLogin 将立即短路返回，
    // 避免对同一错密的并行重试被服务端连续 401 触发风控封号。
    // 用户在登录界面重新输入凭据后自动清除。
    var passwordInvalidatedLatch by mutableStateOf(false)
        private set
    /** 首次确认凭据失效的子系统名，UI 弹窗展示用。 */
    var passwordInvalidatedSiteName by mutableStateOf("")
        private set
    /** UI 弹窗显隐控制。 */
    var passwordInvalidatedDialogVisible by mutableStateOf(false)

    /** 子系统检测到明确凭据无效时调用。重复调用幂等。 */
    fun reportPasswordInvalidated(siteName: String) {
        if (passwordInvalidatedLatch) return
        passwordInvalidatedLatch = true
        passwordInvalidatedSiteName = siteName
        passwordInvalidatedDialogVisible = true
        android.util.Log.w("AppLoginState", "password invalidated by site=$siteName")
    }

    /** 仅在明确 401 或登录失败响应消息含"用户名或密码错误"等关键字时返回 true，避免误判。 */
    private fun isPasswordError(result: com.xjtu.toolbox.auth.LoginResult): Boolean {
        if (result.state != com.xjtu.toolbox.auth.LoginState.FAIL) return false
        val msg = result.message
        return msg.contains("用户名或密码", ignoreCase = true) ||
                msg.contains("密码错误", ignoreCase = true) ||
                msg.contains("账号或密码", ignoreCase = true) ||
                msg.contains("401")
    }

    // [CP] 全局共享连接池：所有子系统复用 TLS 连接，避免重复握手（~800ms→~50ms）
    private val sharedConnectionPool = okhttp3.ConnectionPool(5, 30, java.util.concurrent.TimeUnit.SECONDS)

    // WebVPN: 校外自动模式
    // vpnClient = sharedClient + WebVpnInterceptor（仅用于内部服务）
    @Volatile private var vpnClient: okhttp3.OkHttpClient? = null
    /** 只读访问 vpnClient（供后台保活/外部健康检查使用） */
    internal val webVpnClientOrNull: okhttp3.OkHttpClient? get() = vpnClient

    /** 清理 VPN client（网络切换时调用，下次需要时由 loginWebVpn 重建） */
    fun clearVpnClient() {
        vpnClient = null
        webVpnLoggedIn = false
    }

    /**
     * 清除所有子系统的 cached login 实例（不动 sharedClient/cookies）。
     * 用途：当 access mode（校内/校外）切换时，原 cached login 持有的 client 引用已不合法
     * （可能是旧的 vpnClient 或 sharedClient），必须丢弃让下次 autoLogin 用新 mode 的 client 重建。
     */
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
        // 不动 ywtbUserInfo / nsaProfile：这些是缓存的展示数据，下次登录后会自动刷新
    }

    /**
     * 标记某子系统的 cached login 已经失效，并请求 navigation 层自动重新进入。
     *
     * 当 Screen 内部 API 抛出 AuthExpiredException 时调用：
     * 1. 立即清掉 cache（避免下次进入仍用旧实例）
     * 2. 记录 pendingRetry；AppNavigation 监听后 popBackStack 并重新 navigateWithLogin
     *    （走完整 autoLogin + 必要时 MFA + 重新进入 Screen）
     *
     * 整个过程对用户表现为：返回首页 → 简短 loading → 自动回到原页面，可直接使用。
     *
     * @param type 要重新登录的子系统
     * @param route 重新进入的目标路由（如 Routes.CAMPUS_CARD）
     */
    fun markStaleAndRetry(type: LoginType, route: String) {
        android.util.Log.w("AppLoginState", "markStaleAndRetry($type, $route): clearing cache and asking nav to re-login")
        clearLogin(type)
        pendingRetry = type to route
    }

    /** Screen catch AuthExpiredException 时设置；AppNavigation 监听后自动重新登录该 type 并重新进入 route。 */
    var pendingRetry by mutableStateOf<Pair<LoginType, String>?>(null)

    /** stale retry 的原因（让 autoLoginSheet 显示更准确文案）：null=Token失效，NETWORK_CHANGED=网络切换 */
    var pendingRetryReason by mutableStateOf<RetryReason?>(null)
    enum class RetryReason { TOKEN_EXPIRED, NETWORK_CHANGED }

    /**
     * 网络环境（access mode）发生变化时调用。波动稳健策略：
     *
     * 1. **双重确认** — detectCampusNetwork 已内置探测确认机制（间隔 1.5s 二次探测一致才认变化），
     *    单次网络抖动不会被误判为 mode 切换。
     *
     * 2. **清掉 client 不合法的 cached login** — mode 变化后，旧 cached login 持有的 client
     *    引用（vpnClient/sharedClient）多半已不可用，下次直接调用会卡几十秒超时。
     *    主动清掉这些 cached login，下次访问时 autoLogin 用新 mode 重建。
     *
     * 3. **立即重建 vpnClient（如果切到校外）** — 清 vpnClient，让 loginWebVpn 重新建立；
     *    background warmup 启动后会预热关键子系统。
     *
     * 4. **保留 sharedClient 实例**（不清），仅它持有的 cookie 在新 mode 下可能无效，
     *    新 autoLogin 会重新写入新 cookie。
     *
     * @return 是否实际发生了 mode 变化
     */
    suspend fun onNetworkChanged(): Boolean {
        val prev = isOnCampus
        campusDetectTime = 0L
        val now = detectCampusNetwork()
        isOnCampus = now
        if (prev != null && prev != now) {
            android.util.Log.w("AppLoginState", "Access mode changed: $prev → $now")
            // 旧架构：丢弃 cached login + vpnClient（仍保留以便业务过渡期使用）
            clearAllCachedLogins()
            clearVpnClient()
            // 新架构：通知 SessionManager 切换 active backend；两边 cookies 保留以便快速切回
            sessionManager?.onNetworkChanged(
                if (now == true) com.xjtu.toolbox.auth.AccessMode.NORMAL
                else com.xjtu.toolbox.auth.AccessMode.WEBVPN
            )
            return true
        }
        return false
    }
    var isOnCampus by mutableStateOf<Boolean?>(null)   // null=未检测, true=校内, false=校外
    @Volatile private var webVpnLoggedIn = false

    // [N1] 网络检测结果缓存（10 分钟有效）
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

    // NSA 学工系统数据（照片、学院+专业）
    var nsaProfile by mutableStateOf<com.xjtu.toolbox.nsa.NsaStudentProfile?>(null)
    var nsaPhotoBytes by mutableStateOf<ByteArray?>(null)
    @Volatile var nsaDataLoaded = false

    // [F1] CredentialStore 引用（cache() 时即时持久化）
    private var credentialStoreRef: CredentialStore? = null

    // 保存的凭据（内存中），用于自动登录其他系统
    var savedUsername: String = ""
        private set
    var savedPassword: String = ""
        private set

    val hasCredentials: Boolean get() = savedUsername.isNotEmpty() && savedPassword.isNotEmpty()
    val isLoggedIn: Boolean get() = activeUsername.isNotEmpty()

    val loginCount: Int
        get() = listOfNotNull(
            attendanceLogin, postgraduateAttendanceLogin, jwxtLogin, jwappLogin, ywtbLogin, libraryLogin, campusCardLogin, dzpzLogin, venueLogin, classLogin, lmsLogin, jiaocaiLogin, couponLogin
        ).size

    // ── autoLogin / loginWebVpn / loginJwxtWithDetails 期间触发 MFA 时的挂起信号（由 UI 层观察并弹出验证对话框）──
    var pendingMfaLogin by mutableStateOf<XJTULogin?>(null)
    var pendingMfaTarget by mutableStateOf<String?>(null)
    var pendingMfaType by mutableStateOf<LoginType?>(null)
    /**
     * MFA 验证码通过后的接续逻辑（在 IO 线程执行）。
     * - 为 null 时走默认路径：[finishLoginAfterMfa]，按 login 类型 cache。
     * - 不为 null 时由设置 MFA 信号的代码定义：例如 WebVPN MFA 完成后建立 vpnClient
     *   并继续 JWXT 登录；JWAPP 401 重登 MFA 完成后恢复 token 等。
     * 返回值约定：null 代表成功；非空字符串为可展示给用户的错误描述。
     */
    @Volatile var pendingMfaContinuation: (suspend (XJTULogin) -> String?)? = null

    /** 是否为「登录阶段」URL 就在校内（需走 WebVPN 才能认证）的服务。
     *  - ATTENDANCE/POSTGRADUATE_ATTENDANCE：OAuth 入口虽公网，但最终 redirect 回 bkkq/yjskq（校内）
     *  - JWXT：loginUrl = jwxt.xjtu.edu.cn（校内）
     *  - LIBRARY：loginUrl = rg.lib.xjtu.edu.cn:8086（校内）
     *  其余服务的 CAS 认证入口都是公网可达的 login.xjtu.edu.cn / org.xjtu.edu.cn / 各自的公网域名，
     *  仅 API 调用阶段才需要校内网络（在 composable 层面按需注入 vpnClient）。 */
    private fun isInternalService(type: LoginType): Boolean = type in setOf(
        LoginType.ATTENDANCE, LoginType.POSTGRADUATE_ATTENDANCE,
        LoginType.LIBRARY, LoginType.JWXT
    )

    /** 是否为「API 调用阶段」需要校内网络的服务（CAS 认证阶段公网可达）。
     *  由 composable 路由层面在校外时为这些服务注入 vpnClient 以保证 API 通畅。 */
    fun isApiNeedsCampus(type: LoginType): Boolean = type in setOf(
        LoginType.JWAPP, LoginType.VENUE
    )

    fun saveCredentials(username: String, password: String) {
        // 凭据变更视为用户已知晓并响应，清除密码失效熔断
        val credentialsChanged = (username != savedUsername || password != savedPassword)
        savedUsername = username
        savedPassword = password
        if (credentialsChanged && passwordInvalidatedLatch) {
            passwordInvalidatedLatch = false
            passwordInvalidatedSiteName = ""
            passwordInvalidatedDialogVisible = false
            android.util.Log.i("AppLoginState", "credentials updated, password latch cleared")
        }
        sessionManager?.setCredentials(username, password)
    }

    /** 从 EncryptedSharedPreferences 恢复凭据和缓存 */
    fun restoreCredentials(store: CredentialStore) {
        credentialStoreRef = store  // [F1] 保存引用，cache() 时即时持久化
        val creds = store.load() ?: return
        savedUsername = creds.first
        savedPassword = creds.second
        // [OFF-1] 恢复 activeUsername → isLoggedIn 为 true，离线冷启动也显示欢迎称呼
        if (savedUsername.isNotEmpty()) activeUsername = savedUsername
        // 恢复持久化的 fpVisitorId（保持设备一致性，避免 MFA）
        firstVisitorId = store.loadFpVisitorId()
        // 恢复 RSA 公钥缓存（24h 有效期）
        cachedRsaKey = store.loadRsaPublicKey()
        // 恢复缓存昵称（欢迎卡片秒显示）
        cachedNickname = store.loadNickname()
        // 同步至新会话架构
        sessionManager?.let {
            it.setCredentials(savedUsername, savedPassword)
            it.fpVisitorId = firstVisitorId
            it.cachedRsaKey = cachedRsaKey
        }
        // 恢复 NSA 个人信息缓存（首次登录后持久化，冷启动秒显示）
        store.loadNsaProfile()?.let { json ->
            com.xjtu.toolbox.nsa.NsaStudentProfile.fromJson(json)?.let { profile ->
                nsaProfile = profile
                // 只有含详情字段才视为完整缓存；否则 Phase2 会重新拉取
                nsaDataLoaded = profile.details.isNotEmpty()
                android.util.Log.d("Restore", "NSA profile from cache: ${profile.name}, details=${profile.details.size}, complete=$nsaDataLoaded")
            }
        }
        store.loadNsaPhoto()?.let { bytes ->
            if (bytes.size > 100) {
                nsaPhotoBytes = bytes
                android.util.Log.d("Restore", "NSA photo from cache: ${bytes.size}B")
            }
        }
    }

    /** 持久化凭据和缓存到 EncryptedSharedPreferences */
    fun persistCredentials(store: CredentialStore) {
        if (hasCredentials) store.save(savedUsername, savedPassword)
        firstVisitorId?.let { store.saveFpVisitorId(it) }
        cachedRsaKey?.let { store.saveRsaPublicKey(it) }
    }

    /**
     * [G1] 获取已缓存的登录实例（纯内存，零网络调用，不会 ANR）
     * Token 过期时直接返回 null → 由 navigateWithLogin → autoLogin (IO) 处理刷新
     */
    fun getCached(type: LoginType): XJTULogin? {
        val login = when (type) {
            LoginType.ATTENDANCE -> attendanceLogin
            LoginType.POSTGRADUATE_ATTENDANCE -> postgraduateAttendanceLogin
            LoginType.JWXT -> jwxtLogin
            LoginType.JWAPP -> jwappLogin
            LoginType.YWTB -> ywtbLogin
            LoginType.LIBRARY -> libraryLogin
            LoginType.CAMPUS_CARD -> campusCardLogin
            LoginType.DZPZ -> dzpzLogin
            LoginType.VENUE -> venueLogin
            LoginType.CLASS -> classLogin
            LoginType.LMS -> lmsLogin
            LoginType.JIAOCAI -> jiaocaiLogin
            LoginType.COUPON -> couponLogin
        } ?: return null

        // Token-based 系统：仅检查有效性，不做任何网络请求
        when (login) {
            is JwappLogin -> {
                if (!login.isTokenValid()) {
                    android.util.Log.d("AppLoginState", "getCached(JWAPP): token expired, returning null (reAuth deferred to autoLogin)")
                    // 不清空缓存：autoLogin 会先尝试 reAuth
                    return null
                }
            }
            is YwtbLogin -> {
                if (!login.isTokenValid()) {
                    android.util.Log.d("AppLoginState", "getCached(YWTB): token expired, returning null (reAuth deferred to autoLogin)")
                    return null
                }
            }
            is CouponLogin -> {
                if (!login.isTokenValid()) {
                    android.util.Log.d("AppLoginState", "getCached(COUPON): token expired, returning null (reAuth deferred to autoLogin)")
                    return null
                }
            }
            // AttendanceLogin 已有 executeWithReAuth，不在此处检查
            // Cookie-based 系统（Jwxt/Library/CampusCard）依赖 PersistentCookieJar
        }
        return login
    }

    /** 供 LoginScreen 等外部组件读取 SSO 上下文 */
    fun getSharedClient(): okhttp3.OkHttpClient? = sharedClient
    fun getVisitorId(): String? = firstVisitorId
    fun getCachedRsaKey(): String? = cachedRsaKey

    /**
     * 业务 API 调用应优先使用此方法获取 client。
     * - 校外（isOnCampus == false）：返回 vpnClient（已带 WebVpnInterceptor），所有 .xjtu.edu.cn 域名经 webvpn 代理访问
     * - 校内或网络未知：返回 sharedClient（直连）
     * - 校外但 vpnClient 还未建立时降级到 sharedClient（虽然多半不可达，但避免 NPE）
     */
    fun getActiveClient(): okhttp3.OkHttpClient? {
        return if (isOnCampus == false) (vpnClient ?: sharedClient) else sharedClient
    }

    fun cache(login: XJTULogin, username: String) {
        activeUsername = username
        // 更新 SSO 共享状态（手动登录后也能被后续 autoLogin 复用）
        if (sharedClient == null) sharedClient = login.client
        if (firstVisitorId == null) firstVisitorId = login.fpVisitorId
        if (cachedRsaKey == null) cachedRsaKey = login.getRsaPublicKey()
        when (login) {
            is AttendanceLogin -> {
                if (login.isPostgraduate) postgraduateAttendanceLogin = login
                else attendanceLogin = login
            }
            is JwxtLogin -> jwxtLogin = login
            is JwappLogin -> jwappLogin = login
            is YwtbLogin -> ywtbLogin = login
            is LibraryLogin -> libraryLogin = login
            is CampusCardLogin -> campusCardLogin = login
            is com.xjtu.toolbox.auth.DzpzLogin -> dzpzLogin = login
            is com.xjtu.toolbox.auth.VenueLogin -> venueLogin = login
            is com.xjtu.toolbox.classreplay.ClassLogin -> classLogin = login
            is com.xjtu.toolbox.lms.LmsLogin -> lmsLogin = login
            is com.xjtu.toolbox.jiaocai.JiaocaiLogin -> jiaocaiLogin = login
            is CouponLogin -> couponLogin = login
        }
        // [F1] 立即持久化关键状态（防止进程被杀后丢失）
        credentialStoreRef?.let { store ->
            if (hasCredentials) store.save(savedUsername, savedPassword)
            firstVisitorId?.let { store.saveFpVisitorId(it) }
            cachedRsaKey?.let { store.saveRsaPublicKey(it) }
        }
    }

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
        }
    }

    /**
     * 单次探测校园网（仅向 jwxt 域发一个 HEAD，3 秒超时）。
     * 不更新缓存、不读缓存，纯函数式。
     */
    private suspend fun probeCampusOnce(): Boolean = try {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val testClient = okhttp3.OkHttpClient.Builder()
                .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(false)
                .build()
            val request = okhttp3.Request.Builder()
                .url("https://jwxt.xjtu.edu.cn/")
                .head()
                .build()
            testClient.newCall(request).execute().use { true }
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
        android.util.Log.d("Campus", "detectCampus: final result=$result (jwxt reachable=$first)")
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
        if (!ssoEstablished && System.currentTimeMillis() - mfaCancelledAt < MFA_COOLDOWN_MS) {
            android.util.Log.d("WebVPN", "skipped due to recent MFA cancel (no SSO yet)")
            return false
        }
        return mfaSerialMutex.withLock {
            if (webVpnLoggedIn && vpnClient != null) return@withLock true
            val ok = doLoginWebVpn()
            if (!ok && pendingMfaLogin != null && pendingMfaTarget == "_WEBVPN_") {
                waitForPendingMfaCompletion(timeoutMs = 5 * 60_000L)
            }
            webVpnLoggedIn && vpnClient != null
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
                    android.util.Log.d("WebVPN", "Account choice required, selecting UNDERGRADUATE")
                    val finalResult = login.login(accountType = XJTULogin.AccountType.UNDERGRADUATE)
                    android.util.Log.d("WebVPN", "Account choice result: state=${finalResult.state}")
                    if (finalResult.state == LoginState.SUCCESS) {
                        vpnClient = login.client
                        webVpnLoggedIn = true
                        if (isOnCampus == false) sharedClient = vpnClient
                        true
                    } else false
                } else if (result.state == LoginState.REQUIRE_MFA) {
                    // MFA 触发：把 login + continuation 抛给 UI 层弹窗
                    // 用户输入验证码后，continuation 完成 webvpn 登录并建立 vpnClient
                    android.util.Log.w("WebVPN", "WebVPN MFA triggered, signaling UI for verification")
                    pendingMfaLogin = login
                    pendingMfaTarget = "_WEBVPN_"
                    pendingMfaType = null  // 不绑定具体子系统：MFA 完成后会按 pendingMfaTarget 决定后续动作
                    pendingMfaContinuation = { mfaLogin ->
                        // MFA verifyCode 通过后，继续 webvpn 登录拿到 webvpn session cookie
                        try {
                            val r = mfaLogin.login()
                            android.util.Log.d("WebVPN", "Post-MFA login state=${r.state}")
                            if (r.state == LoginState.SUCCESS) {
                                vpnClient = mfaLogin.client
                                webVpnLoggedIn = true
                                if (isOnCampus == false) sharedClient = vpnClient
                                android.util.Log.d("WebVPN", "WebVPN login SUCCESS via MFA (sharedClient aliased)")
                                null
                            } else if (r.state == LoginState.REQUIRE_ACCOUNT_CHOICE) {
                                val r2 = mfaLogin.login(accountType = XJTULogin.AccountType.UNDERGRADUATE)
                                if (r2.state == LoginState.SUCCESS) {
                                    vpnClient = mfaLogin.client
                                    webVpnLoggedIn = true
                                    if (isOnCampus == false) sharedClient = vpnClient
                                    null
                                } else "WebVPN 登录失败：${r2.message.ifEmpty { "未知错误" }}"
                            } else {
                                "WebVPN 登录失败：${r.message.ifEmpty { "未知错误" }}"
                            }
                        } catch (e: Exception) {
                            "WebVPN 登录异常：${e.message}"
                        }
                    }
                    // 立即返回 false：调用方知道 webvpn 还未就绪，UI 流会处理 MFA
                    false
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

    /**
     * 自动登录指定系统（使用保存的凭据）
     * [G1] token 过期时先在 IO 线程尝试 reAuth，失败再 full login
     * [E1] 网络异常自动重试（最多 2 次，指数退避 1s→3s）
     * [CP] 使用全局共享 ConnectionPool
     * @return 登录成功的 XJTULogin 实例，失败返回 null
     */
    suspend fun autoLogin(type: LoginType, force: Boolean = false): XJTULogin? {
        if (!hasCredentials) return null
        // 密码已确认无效，停止后续登录请求；用户更新凭据后熔断会自动清除
        if (passwordInvalidatedLatch) {
            android.util.Log.d("AppLoginState", "autoLogin($type): halted by password latch ($passwordInvalidatedSiteName)")
            return null
        }
        getCached(type)?.let { return it }
        // 用户取消 MFA 后短暂冷却（仅对 background warmup 起效，用户主动点击 force=true 跳过）。
        // 已经有 TGC（ssoEstablished）的话即便取消过 MFA 也能继续——后续登录走 SSO 不会再 MFA。
        if (!force && !ssoEstablished && System.currentTimeMillis() - mfaCancelledAt < MFA_COOLDOWN_MS) {
            android.util.Log.d("AppLoginState", "autoLogin($type): skipped due to recent MFA cancel (no SSO yet, no force)")
            return null
        }

        // [MFA] 首次登录串行化：CAS TGC 还没建立时所有 autoLogin 排队，避免多个并行登录各自弹 MFA
        // 一旦有任意子系统登录成功（ssoEstablished），sharedClient 持有 TGC，后续 autoLogin 走 SSO，
        // 不会再触发 MFA → 释放锁，自由并行。
        // 注：Kotlin Mutex 不是可重入的，由 inner 调用的 doLoginWebVpn() 不再获取锁避免死锁。
        if (!ssoEstablished) {
            return mfaSerialMutex.withLock {
                // [双重检查] 等锁期间别人可能已经完成首次登录、登录了同一 type、或用户已取消 MFA。
                // 这是关键：多个并行 autoLogin 在等锁时都已经通过了第一次 mfaCancelledAt 检查；
                // 拿到锁后必须重新检查，否则前一个 MFA 取消的状态对后续等锁者无效，
                // 会导致连续触发新 MFA 弹窗（实测 bug）。
                getCached(type)?.let { return@withLock it }
                if (!force && !ssoEstablished && System.currentTimeMillis() - mfaCancelledAt < MFA_COOLDOWN_MS) {
                    android.util.Log.d("AppLoginState", "autoLogin($type): skipped in lock (recent MFA cancel, no force)")
                    return@withLock null
                }
                var result = autoLoginInner(type)
                // 如果触发了 MFA，必须在锁内等 UI 完成 MFA 后再释放，
                // 否则后续 autoLogin 会立刻进入 → 又触发并行 MFA → 多个对话框叠出。
                if (result == null && pendingMfaLogin != null) {
                    waitForPendingMfaCompletion(timeoutMs = 5 * 60_000L)
                    // MFA 完成后，UI 的 finishLoginAfterMfa 已把 当前 login 写入对应缓存。
                    // 注意：如果这次是 WebVPN 的 MFA（pendingMfaTarget=_WEBVPN_），那只是 vpnClient
                    // 建立完成，本 type 的子系统登录还没做 → 用新建立的 vpnClient 重新走一遍 inner。
                    getCached(type)?.let { return@withLock it }
                    if (vpnClient != null) {
                        android.util.Log.d("AppLoginState", "autoLogin($type): WebVPN ready post-MFA, retrying inner")
                        result = autoLoginInner(type)
                    }
                }
                result
            }
        }
        return autoLoginInner(type)
    }

    /** 等待 UI 完成当前 MFA 验证（pendingMfaLogin 变为 null）；超时则返回。 */
    private suspend fun waitForPendingMfaCompletion(timeoutMs: Long) {
        val start = System.currentTimeMillis()
        while (pendingMfaLogin != null && System.currentTimeMillis() - start < timeoutMs) {
            kotlinx.coroutines.delay(300)
        }
    }

    private suspend fun autoLoginInner(type: LoginType): XJTULogin? {
        // 此处再次保护，防止上层已经持有缓存（不会进入这里，仅冗余安全）
        getCached(type)?.let { return it }

        // [G1] Token 过期的系统：先在 IO 线程尝试轻量 reAuth（SSO/casAuthenticate）
        val existingLogin = when (type) {
            LoginType.JWAPP -> jwappLogin
            LoginType.YWTB -> ywtbLogin
            LoginType.ATTENDANCE -> attendanceLogin
            LoginType.POSTGRADUATE_ATTENDANCE -> postgraduateAttendanceLogin
            LoginType.JWXT -> jwxtLogin
            LoginType.DZPZ -> dzpzLogin
            LoginType.VENUE -> venueLogin
            LoginType.CLASS -> classLogin
            LoginType.LMS -> lmsLogin
            LoginType.COUPON -> couponLogin
            else -> null
        }
        if (existingLogin != null) {
            val reAuthOk = try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    when (existingLogin) {
                        is JwappLogin -> existingLogin.reAuthenticate()
                        is YwtbLogin -> existingLogin.reAuthenticate()
                        is AttendanceLogin -> existingLogin.reAuthenticate()
                        is JwxtLogin -> existingLogin.reAuthenticate()
                        is com.xjtu.toolbox.auth.DzpzLogin -> existingLogin.reAuthenticate()
                        is com.xjtu.toolbox.auth.VenueLogin -> existingLogin.reAuthenticate()
                        is com.xjtu.toolbox.classreplay.ClassLogin -> existingLogin.reAuthenticate()
                        is com.xjtu.toolbox.lms.LmsLogin -> existingLogin.reAuthenticate()
                        is CouponLogin -> existingLogin.reAuthenticate()
                        else -> false
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("AppLoginState", "autoLogin($type): reAuth exception: ${e.message}")
                false
            }
            if (reAuthOk) {
                android.util.Log.d("AppLoginState", "autoLogin($type): reAuth success, skipping full login")
                return existingLogin
            }
            // reAuth 失败，准备 full login。这里 *不要* 清空 existingLogin，
            // 否则 loginCount 立刻归零会让首页徽标错误显示"离线模式"。
            // full login 成功后 cache() 会用新 login 覆盖；失败时旧实例仍可作为缓存兜底。
            android.util.Log.d("AppLoginState", "autoLogin($type): reAuth failed, will attempt full login (cache preserved)")
        }

        // 统一 access mode：先确定本次访问走 NORMAL 还是 WEBVPN。
        // 校外 → 所有子系统都走 vpnClient（带 WebVpnInterceptor、走 webvpn 反向代理）；
        // 校内 → 所有子系统走 sharedClient 直连。
        // 这样 cookies 全部在同一域（原始 xjtu 域 / webvpn 域），避免 TGC 在 webvpn
        // 域但 sharedClient 直连 login.xjtu.edu.cn 查不到的 cookie 隔离问题。
        // 注：wrapper 可能已持 mfaSerialMutex，调用无锁版 doLoginWebVpn() 避免死锁。
        if (isOnCampus == null) isOnCampus = detectCampusNetwork()
        if (isOnCampus == false && vpnClient == null) doLoginWebVpn()

        val useWebVpn = isOnCampus == false
        val clientForLogin = if (useWebVpn) {
            vpnClient ?: return null  // WebVPN 没建好（如 MFA 还在进行），让 wrapper 处理
        } else {
            // [CP] 使用共享连接池创建/复用 sharedClient
            clientInitMutex.withLock {
                sharedClient ?: persistentCookieJar?.let { jar ->
                    okhttp3.OkHttpClient.Builder()
                        .addInterceptor(okhttp3.brotli.BrotliInterceptor)
                        .cookieJar(jar)
                        .connectionPool(sharedConnectionPool)
                        .followRedirects(true)
                        .followSslRedirects(true)
                        .connectTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
                        .writeTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                        .also { sharedClient = it }
                }
            }
        }

        val visitorId = sharedClient?.let { firstVisitorId } ?: firstVisitorId

        // [E1] 带重试的登录（区分可重试 IOException 和不可重试的认证失败）
        var lastException: Exception? = null
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            attemptsLoop@ for (attempt in 0..2) {
                try {
                    val login = type.createLogin(clientForLogin, visitorId, useWebVpn, cachedRsaKey)
                    val result = login.login(savedUsername, savedPassword)
                    if (result.state == LoginState.SUCCESS) {
                        if (sharedClient == null) sharedClient = login.client
                        if (firstVisitorId == null) firstVisitorId = login.fpVisitorId
                        if (cachedRsaKey == null) cachedRsaKey = login.getRsaPublicKey()
                        cache(login, savedUsername)
                        return@withContext login
                    } else if (result.state == LoginState.REQUIRE_ACCOUNT_CHOICE) {
                        val accountType = if (type == LoginType.POSTGRADUATE_ATTENDANCE)
                            XJTULogin.AccountType.POSTGRADUATE else XJTULogin.AccountType.UNDERGRADUATE
                        val finalResult = login.login(accountType = accountType)
                        if (finalResult.state == LoginState.SUCCESS) {
                            if (sharedClient == null) sharedClient = login.client
                            if (firstVisitorId == null) firstVisitorId = login.fpVisitorId
                            if (cachedRsaKey == null) cachedRsaKey = login.getRsaPublicKey()
                            cache(login, savedUsername)
                            return@withContext login
                        }
                    }
                    // MFA 触发：设置挂起信号，让 UI 弹出验证对话框
                    if (result.state == LoginState.REQUIRE_MFA) {
                        // 关键：已建立 SSO 后再触发 MFA，说明 sharedClient 的 TGC 没被 CAS 识别（cookie 域问题/失效）。
                        // 此时不能再弹 MFA（用户已经在主动登录的子系统弹过一次），否则会出现"一串 MFA 弹窗"叠出 bug。
                        // 直接视为本次 SSO 失败 → 跳出重试，下面 fallback 也会跳过，最终返回 null 让上层报错或走自然 retry。
                        if (ssoEstablished) {
                            android.util.Log.w("AppLoginState", "autoLogin($type): REQUIRE_MFA after SSO established, treating as SSO failure (NOT showing MFA dialog)")
                            lastException = RuntimeException("SSO 已建立但子系统仍要 MFA，跳过避免连环弹窗")
                            break@attemptsLoop
                        }
                        android.util.Log.w("AppLoginState", "autoLogin($type): REQUIRE_MFA, setting pending signal")
                        pendingMfaLogin = login
                        pendingMfaType = type
                        return@withContext null
                    }
                    // 认证失败：本次循环不可重试
                    android.util.Log.w("AppLoginState", "autoLogin($type): auth failed state=${result.state} msg=${result.message}")
                    // 明确凭据无效 → 触发全局熔断，跳过 fallback（fallback 也会撞同一错密）
                    if (isPasswordError(result)) {
                        reportPasswordInvalidated(siteName = type.name)
                        lastException = RuntimeException("password invalidated: ${result.message}")
                        return@withContext null
                    }
                    lastException = RuntimeException("auth failed: ${result.message}")
                    break@attemptsLoop
                } catch (e: java.io.IOException) {
                    lastException = e
                    android.util.Log.w("AppLoginState", "autoLogin($type): attempt ${attempt + 1}/3 IOException: ${e.message}")
                    // RSA 公钥相关失败 → 清除缓存，下次重试用新 key
                    if (e.message?.contains("RSA", ignoreCase = true) == true ||
                        e.message?.contains("公钥", ignoreCase = true) == true) {
                        cachedRsaKey = null
                    }
                    if (attempt < 2) kotlinx.coroutines.delay(if (attempt == 0) 1000L else 3000L)
                } catch (e: Exception) {
                    android.util.Log.e("AppLoginState", "autoLogin($type): exception on attempt ${attempt + 1}/3", e)
                    lastException = e
                    // RSA 公钥解析失败 → 清除缓存以便下次登录可刷新
                    if (e.message?.contains("RSA", ignoreCase = true) == true ||
                        e.message?.contains("公钥", ignoreCase = true) == true ||
                        e.cause is java.security.spec.InvalidKeySpecException) {
                        cachedRsaKey = null
                        android.util.Log.w("AppLoginState", "autoLogin: RSA 公钥失败，已清除缓存")
                        if (attempt < 2) {
                            kotlinx.coroutines.delay(if (attempt == 0) 1000L else 3000L)
                        } else {
                            break@attemptsLoop
                        }
                    } else {
                        // 其他异常（包括 TGC 失败、CAS 错误页等）：跳出重试，进入 fallback 兜底
                        break@attemptsLoop
                    }
                }
            }
            android.util.Log.w("AppLoginState", "autoLogin($type): all 3 attempts failed: ${lastException?.message}")
            // [FALLBACK] WebVPN 兜底：仅在校外（或网络环境未知）时触发
            // 校园网下：webvpn.xjtu.edu.cn 同样位于校园网，绕代理只增加延时不能解决问题，
            //          直连还失败说明是子系统本身故障，应直接报错让用户重试。
            // 校外：sharedClient 直连校内域名必失败，必须通过 webvpn 代理 → fallback 是核心兜底。
            val shouldFallback = !useWebVpn && hasCredentials &&
                isOnCampus != true &&
                lastException !is RuntimeException // 排除"密码错误"等明确认证失败
            if (shouldFallback) {
                android.util.Log.w("AppLoginState", "autoLogin($type): direct failed, fallback to WebVPN")
                if (vpnClient == null) doLoginWebVpn()  // 无锁版，避免与 wrapper 持锁冲突
                val vpnFallbackClient = vpnClient
                if (vpnFallbackClient != null) {
                    try {
                        val login = type.createLogin(vpnFallbackClient, visitorId, true, cachedRsaKey)
                        val result = login.login(savedUsername, savedPassword)
                        val finalResult = if (result.state == LoginState.REQUIRE_ACCOUNT_CHOICE) {
                            val accountType = if (type == LoginType.POSTGRADUATE_ATTENDANCE)
                                XJTULogin.AccountType.POSTGRADUATE else XJTULogin.AccountType.UNDERGRADUATE
                            login.login(accountType = accountType)
                        } else result
                        if (finalResult.state == LoginState.SUCCESS) {
                            if (firstVisitorId == null) firstVisitorId = login.fpVisitorId
                            if (cachedRsaKey == null) cachedRsaKey = login.getRsaPublicKey()
                            cache(login, savedUsername)
                            // fallback 成功也意味着此刻应该在校外或直连不通，更新状态
                            if (isOnCampus == true) {
                                isOnCampus = false
                                campusDetectTime = 0L
                            }
                            android.util.Log.d("AppLoginState", "autoLogin($type): WebVPN fallback SUCCESS")
                            return@withContext login
                        } else if (finalResult.state == LoginState.REQUIRE_MFA) {
                            if (ssoEstablished) {
                                android.util.Log.w("AppLoginState", "autoLogin($type): WebVPN fallback REQUIRE_MFA after SSO, skipping (avoid stacked MFA dialogs)")
                            } else {
                                android.util.Log.w("AppLoginState", "autoLogin($type): WebVPN fallback MFA, signaling UI")
                                pendingMfaLogin = login
                                pendingMfaType = type
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("AppLoginState", "autoLogin($type): WebVPN fallback also failed: ${e.message}")
                    }
                }
            }
            null
        }
    }

    /**
     * JWXT 登录（带详细错误信息），专供手动登录流使用
     * 返回: Triple(login实例, 错误信息, 是否需要MFA)
     * - 成功: (login, null, false)
     * - MFA: (login, null, true) — 需要先完成 MFA 再调 finishLoginAfterMfa()
     * - 失败: (null, errorMsg, false)
     */
    suspend fun loginJwxtWithDetails(user: String, pwd: String): Triple<XJTULogin?, String?, Boolean> {
        return try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                // 确保首次登录时 sharedClient 使用 PersistentCookieJar + 共享连接池
                if (sharedClient == null) {
                    persistentCookieJar?.let { jar ->
                        sharedClient = okhttp3.OkHttpClient.Builder()
                            .addInterceptor(okhttp3.brotli.BrotliInterceptor)
                            .cookieJar(jar)
                            .connectionPool(sharedConnectionPool)
                            .followRedirects(true).followSslRedirects(true)
                            .connectTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
                            .readTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
                            .writeTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
                            .build()
                    }
                }
                // [N2] JWXT loginUrl = jwxt.xjtu.edu.cn（校内），校外必须先建 WebVPN
                saveCredentials(user, pwd)  // 让 loginWebVpn 能读取凭据
                if (isOnCampus == null) {
                    isOnCampus = detectCampusNetwork()
                }
                val clientForLogin = if (isOnCampus == false) {
                    if (vpnClient == null) doLoginWebVpn()  // 无锁版
                    if (vpnClient == null) {
                        // WebVPN 未就绪：可能在等 MFA，也可能真失败
                        // 通过 pendingMfaLogin 区分
                        if (pendingMfaLogin != null) {
                            // WebVPN 触发了 MFA，UI 会弹窗，这里返回让用户先验证
                            return@withContext Triple<XJTULogin?, String?, Boolean>(
                                null, "校外环境需先完成 WebVPN 两步验证，已为你弹出验证码窗口", false
                            )
                        }
                        return@withContext Triple<XJTULogin?, String?, Boolean>(
                            null, "无法建立 WebVPN 连接，请检查网络后重试", false
                        )
                    }
                    vpnClient
                } else {
                    sharedClient
                }
                val login = LoginType.JWXT.createLogin(clientForLogin, firstVisitorId, false, cachedRsaKey)
                val result = login.login(user, pwd)
                android.util.Log.d("Login", "JWXT login result: state=${result.state}, msg=${result.message}")
                when (result.state) {
                    LoginState.SUCCESS -> {
                        if (sharedClient == null) sharedClient = login.client
                        if (firstVisitorId == null) firstVisitorId = login.fpVisitorId
                        if (cachedRsaKey == null) cachedRsaKey = login.getRsaPublicKey()
                        cache(login, user)
                        Triple(login, null, false)
                    }
                    LoginState.REQUIRE_ACCOUNT_CHOICE -> {
                        val finalResult = login.login(accountType = XJTULogin.AccountType.UNDERGRADUATE)
                        if (finalResult.state == LoginState.SUCCESS) {
                            if (sharedClient == null) sharedClient = login.client
                            if (firstVisitorId == null) firstVisitorId = login.fpVisitorId
                            cache(login, user)
                            Triple(login, null, false)
                        } else {
                            Triple(null, finalResult.message.ifEmpty { "账号选择失败" }, false)
                        }
                    }
                    LoginState.REQUIRE_MFA -> Triple(login, null, true)
                    LoginState.REQUIRE_CAPTCHA -> Triple(null, "登录频繁，需要验证码，请稍后重试", false)
                    LoginState.FAIL -> Triple(null, result.message.ifEmpty { "登录失败" }, false)
                }
            }
        } catch (e: java.io.IOException) {
            android.util.Log.e("Login", "JWXT login IO error", e)
            Triple(null, "网络超时，请检查网络连接后重试", false)
        } catch (e: Exception) {
            android.util.Log.e("Login", "JWXT login error", e)
            Triple(null, "登录异常: ${e.message}", false)
        }
    }

    /**
     * MFA 验证完成后继续登录
     */
    suspend fun finishLoginAfterMfa(login: XJTULogin, user: String): String? {
        return try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val result = login.login()  // MFA 已验证，重新调用 login 完成认证
                android.util.Log.d("Login", "Post-MFA login: state=${result.state}, msg=${result.message}")
                if (result.state == LoginState.SUCCESS) {
                    if (sharedClient == null) sharedClient = login.client
                    if (firstVisitorId == null) firstVisitorId = login.fpVisitorId
                    if (cachedRsaKey == null) cachedRsaKey = login.getRsaPublicKey()
                    cache(login, user)
                    null // 成功
                } else if (result.state == LoginState.REQUIRE_ACCOUNT_CHOICE) {
                    val finalResult = login.login(accountType = XJTULogin.AccountType.UNDERGRADUATE)
                    if (finalResult.state == LoginState.SUCCESS) {
                        if (sharedClient == null) sharedClient = login.client
                        if (firstVisitorId == null) firstVisitorId = login.fpVisitorId
                        if (cachedRsaKey == null) cachedRsaKey = login.getRsaPublicKey()
                        cache(login, user)
                        null
                    } else {
                        finalResult.message.ifEmpty { "账号选择失败" }
                    }
                } else {
                    result.message.ifEmpty { "MFA 后登录失败" }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("Login", "Post-MFA login error", e)
            "MFA 后登录异常: ${e.message}"
        }
    }

    fun logout(store: CredentialStore? = null) {
        // 停止后台保活循环
        com.xjtu.toolbox.auth.SessionKeepAlive.stop()
        activeUsername = ""
        savedUsername = ""; savedPassword = ""
        attendanceLogin = null; postgraduateAttendanceLogin = null; jwxtLogin = null; jwappLogin = null
        ywtbLogin = null; libraryLogin = null; campusCardLogin = null; jiaocaiLogin = null; couponLogin = null
        sharedClient = null
        vpnClient = null
        webVpnLoggedIn = false
        isOnCampus = null
        campusDetectTime = 0L  // [N1] 清除网络检测缓存
        ywtbUserInfo = null
        nsaProfile = null; nsaPhotoBytes = null; nsaDataLoaded = false
        com.xjtu.toolbox.nsa.NsaApi.clearSession()
        store?.clearNsaCache()  // 清除持久化的 NSA 个人信息和照片
        firstVisitorId = null
        cachedRsaKey = null
        persistentCookieJar?.clear()
        vpnCookieJar?.clear()  // [B2] 清除 WebVPN 持久化 cookies
        com.xjtu.toolbox.pay.PaymentCodeApi.clearCachedJwt()  // 清除付款码 JWT 缓存
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
        }
        // 恢复凭据（同步执行，确保 Compose 首帧读取到正确状态）
        loginState.restoreCredentials(credentialStore)
    }
}

private suspend fun refreshCampusCardCache(
    context: android.content.Context,
    login: CampusCardLogin
): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val appContext = context.applicationContext
    val api = com.xjtu.toolbox.card.CampusCardApi(login)
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
    var pendingMainTab by remember { mutableStateOf(initialTab) }

    // WebVPN 转换页：用户点击"用 WebVPN 打开"但 vpnClient 未就绪时，挂起此 URL，
    // 启动 loginWebVpn（必要时含 MFA），登录成功后再 navigate(browser(url))。
    val webVpnPendingBrowserUrl = remember { mutableStateOf<String?>(null) }
    val webVpnLoadingState = remember { mutableStateOf(false) }
    LaunchedEffect(webVpnPendingBrowserUrl.value) {
        val url = webVpnPendingBrowserUrl.value ?: return@LaunchedEffect
        webVpnLoadingState.value = true
        try {
            // 若已就绪直接进，否则触发 loginWebVpn（MFA 弹窗由全局监听处理）
            val ok = if (loginState.webVpnClientOrNull != null) true
                    else loginState.loginWebVpn()
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
        if (loginState.jwxtLogin != null || !loginState.hasCredentials) return@LaunchedEffect

        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        val isOnline = cm?.activeNetwork != null &&
                cm.getNetworkCapabilities(cm.activeNetwork)
                    ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        if (!isOnline) return@LaunchedEffect

        kotlinx.coroutines.withTimeoutOrNull(10_000L) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                loginState.autoLogin(LoginType.JWXT)
            }
        }
    }

    // 当 YWTB 用户信息获取到时，缓存全名（下次启动秒显示，作为 nsaProfile?.name 的 fallback）
    LaunchedEffect(loginState.ywtbUserInfo) {
        val name = loginState.ywtbUserInfo?.userName
        if (!name.isNullOrBlank()) {
            loginState.cachedNickname = name
            credentialStore.saveNickname(name)
        }
    }

    // [C1] Lifecycle Observer：App 从后台恢复时 proactive 刷新即将过期的 token
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    val lifecycleScope = rememberCoroutineScope()
    val lastResumeRefresh = remember { mutableLongStateOf(0L) }
    val lastCampusCardResumeRefresh = remember { mutableLongStateOf(0L) }
    val lastLoginWarmupAt = remember { mutableLongStateOf(0L) }

    /**
     * 后台并行登录所有子系统（"循环互相往复"策略）。
     *
     * 设计原则：
     * 1. **校外先建 vpnClient**：vpnClient 建立后所有子系统通过 webvpn 反向代理走，
     *    cookies 全在 webvpn 域，跨域问题最小，SSO 命中率最高。
     * 2. **JWXT 永远第一**：入口最简（直 CAS service），TGC 命中率最高。
     *    JWXT 一旦成功 → sharedClient 持有 TGC → ssoEstablished=true →
     *    所有后续子系统并行 autoLogin 全部走 SSO（不再进 mfaSerialMutex）。
     * 3. **校园卡最后**：链路 5 跳最长（ncard → openplatform → CAS → ticket → plat），
     *    没有 TGC 时必然 MFA。让它在 JWXT 建立 SSO 之后才尝试，从而最大概率走 SSO。
     * 4. **错峰并行**：Phase 2 内子系统 150ms 错峰，避免 RSA 公钥同时请求拥塞。
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
                // [Phase 0] 校外优先建立 vpnClient
                // vpnClient 持久 cookies（PersistentCookieJar 复用）→ 后续子系统全部走 webvpn 反向代理，
                // cookies 共享在同一 jar，SSO 链路稳定。
                if (loginState.isOnCampus == false && loginState.webVpnClientOrNull == null) {
                    android.util.Log.d("Warmup", "Phase 0: building vpnClient (off-campus)")
                    runCatching { loginState.loginWebVpn() }
                }

                // [Phase 1] JWXT 第一个登录（建立 SSO 锚点）
                // 入口直 CAS service，单跳即拿 TGC，是最稳的"开荒"子系统。
                // 成功后 ssoEstablished=true → 后续子系统都走 SSO 无 MFA。
                android.util.Log.d("Warmup", "Phase 1: autoLogin(JWXT) to establish SSO")
                val jwxtLogin = loginState.autoLogin(LoginType.JWXT)
                if (jwxtLogin == null && !loginState.ssoEstablished) {
                    // JWXT 失败且无其他登录 → 整体没希望，放弃（避免空转）
                    android.util.Log.w("Warmup", "Phase 1 JWXT failed and no other SSO, abort warmup")
                    return@launch
                }

                // [Phase 2] 其他子系统并行（ssoEstablished 后基本都走 SSO 无 MFA）
                // 顺序按"用户高频使用 + 链路稳健度"排：
                // - YWTB/JWAPP：高频，入口简单
                // - LMS/CLASS：常用，OAuth + CAS
                // - LIBRARY/ATTENDANCE：CAS service
                // - DZPZ/VENUE/JIAOCAI/COUPON：OAuth
                // - CAMPUS_CARD：放最后（链路最长，万一 SSO 失败也不影响其他）
                android.util.Log.d("Warmup", "Phase 2: parallel autoLogin for remaining subsystems")
                kotlinx.coroutines.supervisorScope {
                    val others = listOf(
                        LoginType.YWTB,
                        LoginType.JWAPP,
                        LoginType.LMS,
                        LoginType.CLASS,
                        LoginType.LIBRARY,
                        LoginType.ATTENDANCE,
                        LoginType.DZPZ,
                        LoginType.VENUE,
                        LoginType.JIAOCAI,
                        LoginType.COUPON,
                        LoginType.CAMPUS_CARD  // 链路最长，放最后
                    )
                    others.forEachIndexed { index, type ->
                        launch {
                            // 错峰 150ms：避免 RSA 公钥/login.xjtu 端同时高并发
                            if (index > 0) kotlinx.coroutines.delay(index * 150L)
                            runCatching { loginState.autoLogin(type) }
                        }
                    }

                    // 付款码预热
                    launch {
                        kotlinx.coroutines.delay(1200L)
                        loginState.getActiveClient()?.let { client ->
                            runCatching { com.xjtu.toolbox.pay.PaymentCodeApi(client).authenticate() }
                        }
                    }
                }
                android.util.Log.d("Warmup", "Warmup done: ${loginState.loginCount} subsystems online")
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
                            // JWAPP: proactive token 刷新
                            loginState.jwappLogin?.let { jwapp ->
                                if (!jwapp.isTokenValid()) {
                                    android.util.Log.d("Lifecycle", "ON_RESUME: JWAPP token expired, refreshing...")
                                    jwapp.reAuthenticate()
                                }
                            }
                            // YWTB: proactive token 刷新
                            loginState.ywtbLogin?.let { ywtb ->
                                if (!ywtb.isTokenValid()) {
                                    android.util.Log.d("Lifecycle", "ON_RESUME: YWTB token expired, refreshing...")
                                    ywtb.reAuthenticate()
                                }
                            }
                            // JWXT: proactive session 心跳（cookie-based，轻量重认证）
                            loginState.jwxtLogin?.let { jwxt ->
                                android.util.Log.d("Lifecycle", "ON_RESUME: JWXT session heartbeat")
                                jwxt.reAuthenticate()
                            }
                        }
                        if (shouldRefreshCampusCard) {
                            val campusCardLogin = loginState.campusCardLogin
                                ?: if (loginState.hasCredentials) loginState.autoLogin(LoginType.CAMPUS_CARD) as? CampusCardLogin else null
                            campusCardLogin?.let { cardLogin ->
                                android.util.Log.d("Lifecycle", "ON_RESUME: refreshing campus card cache")
                                refreshCampusCardCache(context, cardLogin)
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    loginState.campusCardCacheVersion++
                                }
                            }
                        }
                        startBackgroundLoginWarmup(lifecycleScope)
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
                        // 当前正在 active Screen 内 → 触发 markStaleAndRetry，让 nav 自动重新登录并重新进入
                        // 这样用户切换网络时正在使用的 Screen 会无缝重新加载，而不是看到一闪而过的离线/错误
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            val currentRoute = navController.currentBackStackEntry?.destination?.route
                            val activeType = currentRoute?.let(routeToLoginType)
                            if (activeType != null && currentRoute != null) {
                                android.util.Log.d("Network", "Mode changed while on $currentRoute → markStaleAndRetry($activeType)")
                                loginState.pendingRetryReason = AppLoginState.RetryReason.NETWORK_CHANGED
                                loginState.markStaleAndRetry(activeType, currentRoute)
                            }
                        }
                        // 模式变化 → 后台预热新 mode 的关键子系统（不阻塞 UI）
                        // autoLogin 内部已串行化 + 复用 SSO，单次 MFA 即可
                        startBackgroundLoginWarmup(networkScope, force = true)
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
        // 注意：isLoggedIn 可能因 [OFF-1] 已为 true（仅设了 username），但实际登录实例为 0
        if (loginState.hasCredentials && loginState.loginCount == 0) {
            isRestoring = true
            // Phase 1: 先网络检测 → 校外则建 VPN → 再登 JWXT（串行，避免竞争）
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val startTime = System.currentTimeMillis()
                    android.util.Log.d("Restore", "Phase1 开始...")
                    restoreStep = "检测网络..."
                    loginState.isOnCampus = loginState.detectCampusNetwork()
                    android.util.Log.d("Restore", "Phase1 网络: campus=${loginState.isOnCampus} (${System.currentTimeMillis() - startTime}ms)")

                    // 校外时先建 VPN（ATTENDANCE/LIBRARY 等 login 阶段需要）
                    if (loginState.isOnCampus == false) {
                        restoreStep = "连接 VPN..."
                        loginState.loginWebVpn()
                        android.util.Log.d("Restore", "Phase1 VPN: ok=${loginState.webVpnClientOrNull != null} (${System.currentTimeMillis() - startTime}ms)")
                    }

                    restoreStep = "正在认证..."
                    val jwxt = loginState.autoLogin(LoginType.JWXT)
                    android.util.Log.d("Restore", "Phase1 完成 ${System.currentTimeMillis() - startTime}ms: JWXT=${jwxt != null}, campus=${loginState.isOnCampus}")
                } catch (e: Exception) {
                    android.util.Log.e("Restore", "Phase1 恢复失败", e)
                }
            }
            isRestoring = false  // Phase1 完成，立即隐藏 banner

            // Phase 2+3: 后台 warmup（统一调度，JWXT-first 已在 Phase 1 完成）+ NSA/付款码预热
            restoreScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val startTime = System.currentTimeMillis()
                    kotlinx.coroutines.coroutineScope {
                        // 委托 warmup 统一调度其余所有子系统（含校园卡，链路最后）。
                        // force=true 跳过 60s 节流；内部已保证 JWXT-first + ssoEstablished 后并行 SSO。
                        launch { startBackgroundLoginWarmup(this@launch, force = true) }

                        // 校园卡缓存刷新（warmup 内 autoLogin(CAMPUS_CARD) 成功后异步刷）
                        launch {
                            kotlinx.coroutines.delay(2000L)  // 等 warmup 进展
                            val cardLogin = loginState.campusCardLogin ?: return@launch
                            try {
                                refreshCampusCardCache(context, cardLogin)
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    loginState.campusCardCacheVersion++
                                }
                            } catch (e: Exception) {
                                android.util.Log.w("Restore", "校园卡缓存刷新失败: ${e.message}")
                            }
                        }
                        // NSA 学工系统（使用 OAuth2，趁 TGC 新鲜立即登录）
                        // 已从缓存恢复时跳过网络请求（个人信息不变，无需每次重新获取）
                        launch {
                            if (loginState.nsaDataLoaded) {
                                android.util.Log.d("Restore", "NSA: 已从缓存恢复，跳过网络请求")
                                return@launch
                            }
                            try {
                                val client = loginState.getSharedClient() ?: return@launch
                                val casRefresher: () -> Boolean = {
                                    loginState.jwxtLogin?.reAuthenticate() ?: false
                                }
                                val nsaApi = com.xjtu.toolbox.nsa.NsaApi(client, casRefresher)
                                if (nsaApi.ensureSession()) {
                                    var details = emptyList<Pair<String, String>>()
                                    kotlinx.coroutines.coroutineScope {
                                        launch { loginState.nsaProfile = nsaApi.getProfile() }
                                        launch { loginState.nsaPhotoBytes = nsaApi.getStudentPhoto() }
                                        launch { details = nsaApi.getPersonalDetails() }
                                    }
                                    // 合并详情到 profile
                                    if (details.isNotEmpty() && loginState.nsaProfile != null) {
                                        loginState.nsaProfile = loginState.nsaProfile!!.copy(details = details)
                                    }
                                    loginState.nsaDataLoaded = true
                                    // 持久化到 CredentialStore（后续冷启动直接从缓存读取）
                                    loginState.nsaProfile?.let { p ->
                                        credentialStore.saveNsaProfile(p.toJson())
                                    }
                                    loginState.nsaPhotoBytes?.let { bytes ->
                                        credentialStore.saveNsaPhoto(bytes)
                                    }
                                    android.util.Log.d("Restore", "NSA 完成: profile=${loginState.nsaProfile != null}, details=${loginState.nsaProfile?.details?.size}, photo=${loginState.nsaPhotoBytes?.size}B")
                                } else {
                                    android.util.Log.w("Restore", "NSA session 建立失败")
                                }
                            } catch (e: Exception) {
                                android.util.Log.w("Restore", "NSA 加载失败: ${e.message}")
                            }
                        }
                        launch {
                            try {
                                loginState.getActiveClient()?.let { client ->
                                    android.util.Log.d("Restore", "预热付款码 JWT...")
                                    com.xjtu.toolbox.pay.PaymentCodeApi(client).authenticate()
                                    android.util.Log.d("Restore", "付款码 JWT 预热完成")
                                }
                            } catch (e: Exception) {
                                android.util.Log.w("Restore", "付款码 JWT 预热失败: ${e.message}")
                            }
                        }

                    }
                    android.util.Log.d("Restore", "全部子系统贯通完成: ${loginState.loginCount} 个系统")
                    loginState.persistCredentials(credentialStore)
                    if (loginState.ywtbLogin != null && loginState.ywtbUserInfo == null) {
                        val api = com.xjtu.toolbox.ywtb.YwtbApi(loginState.ywtbLogin!!)
                        loginState.ywtbUserInfo = api.getUserInfo()
                    }

                    // ── Post-login 个人信息合并（从多数据源提取字段填充 nsaProfile.details） ──
                    if (loginState.nsaProfile != null && loginState.nsaProfile!!.details.isEmpty()) {
                        try {
                            val details = mutableListOf<Pair<String, String>>()

                            // 1) 从考勤系统 JWT 提取学生信息
                            loginState.attendanceLogin?.authToken?.let { jwt ->
                                try {
                                    val parts = jwt.split(".")
                                    if (parts.size >= 2) {
                                        val payload = android.util.Base64.decode(
                                            parts[1],
                                            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
                                        )
                                        val json = String(payload, Charsets.UTF_8).safeParseJsonObject()
                                        val student = json.getAsJsonObject("student")
                                        if (student != null) {
                                            student.get("sex")?.let { s ->
                                                val sex = try { s.asInt } catch (_: Exception) { s.asString.toIntOrNull() }
                                                if (sex == 1) details.add("性别" to "男")
                                                else if (sex == 2) details.add("性别" to "女")
                                            }
                                            student.get("grade")?.let { g ->
                                                val grade = try { g.asInt.toString() } catch (_: Exception) { g.asString }
                                                if (grade.isNotEmpty()) details.add("年级" to "${grade}级")
                                            }
                                            student.get("campusName")?.asString?.let { c ->
                                                if (c.isNotEmpty()) details.add("校区" to c)
                                            }
                                            student.get("departmentName")?.asString?.let { d ->
                                                if (d.isNotEmpty()) details.add("院系" to d)
                                            }
                                            student.get("cardId")?.asString?.let { id ->
                                                if (id.isNotEmpty()) details.add("一卡通" to id)
                                            }
                                        }
                                        android.util.Log.d("Restore", "JWT 解析: student=${student != null}, fields=${details.size}")
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.w("Restore", "JWT 解析失败: ${e.message}")
                                }
                            }

                            // 2) 从一网通办获取身份类型
                            loginState.ywtbUserInfo?.let { info ->
                                if (info.identityTypeName.isNotEmpty()) {
                                    details.add("身份" to info.identityTypeName)
                                }
                            }

                            if (details.isNotEmpty()) {
                                loginState.nsaProfile = loginState.nsaProfile!!.copy(details = details)
                                loginState.nsaDataLoaded = true
                                credentialStore.saveNsaProfile(loginState.nsaProfile!!.toJson())
                                android.util.Log.d("Restore", "个人信息合并完成: ${details.size} 个字段 → ${details.map { it.first }}")
                            }
                        } catch (e: Exception) {
                            android.util.Log.w("Restore", "个人信息合并失败: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("Restore", "后台贯通失败", e)
                }
            }
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

    // ── 本地 What's New 弹窗：堆叠展示自上次已见之后的全部新版本 ──
    val pendingChangelog = remember {
        com.xjtu.toolbox.util.AppChangelog.since(credentialStore.lastSeenChangelogVersion)
    }
    val showUpdateNotice = remember { mutableStateOf(pendingChangelog.isNotEmpty()) }
    if (showUpdateNotice.value) {
        UpdateNoticeDialog(
            entries = pendingChangelog,
            show = showUpdateNotice,
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
    val showAutoUpdateDialog = remember { mutableStateOf(false) }

    LaunchedEffect(credentialStore.autoCheckUpdate) {
        if (autoUpdateCheckDone.value) return@LaunchedEffect
        if (!credentialStore.autoCheckUpdate) return@LaunchedEffect
        autoUpdateCheckDone.value = true
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val releasesUrl = if (credentialStore.updateChannel == "beta") {
                    "https://gitee.com/api/v5/repos/yeliqin666/xjtu-toolbox-android/releases?per_page=5"
                } else {
                    "https://gitee.com/api/v5/repos/yeliqin666/xjtu-toolbox-android/releases/latest"
                }
                val req = okhttp3.Request.Builder()
                    .url(releasesUrl)
                    .header("Accept", "application/json")
                    .build()
                val resp = client.newCall(req).execute()
                if (!resp.isSuccessful) return@withContext
                val body = resp.body?.string() ?: return@withContext
                val json = com.google.gson.JsonParser.parseString(body)

                val latestObj = if (credentialStore.updateChannel == "beta") {
                    val arr = json.asJsonArray
                    if (arr.size() > 0) arr[0].asJsonObject else return@withContext
                } else {
                    json.asJsonObject
                }
                val tagName = latestObj.get("tag_name")?.asString ?: return@withContext
                val latestVersion = tagName.removePrefix("v")
                val versionComparison = MainActivity.compareVersionStrings(BuildConfig.VERSION_NAME, latestVersion)
                if (versionComparison >= 0) return@withContext

                val releaseBody = latestObj.get("body")?.asString ?: ""
                val htmlUrl = latestObj.get("html_url")?.asString ?: ""
                var downloadUrl = ""
                val assets = latestObj.getAsJsonArray("assets")
                if (assets != null && assets.size() > 0) {
                    downloadUrl = assets[0].asJsonObject.get("browser_download_url")?.asString ?: ""
                }
                if (downloadUrl.isEmpty()) {
                    downloadUrl = "https://gitee.com/yeliqin666/xjtu-toolbox-android/releases/download/v${latestVersion}/app-release.apk"
                }

                if (credentialStore.isUpdateNoticeSeen("auto_$latestVersion")) return@withContext

                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    autoUpdateVersion = latestVersion
                    autoUpdateBody = releaseBody
                    autoUpdateDownloadUrl = downloadUrl
                    autoUpdateReleaseUrl = htmlUrl
                    showAutoUpdateDialog.value = true
                }
            } catch (_: Exception) { }
        }
    }

    // ── 自动更新弹窗 ──
    if (showAutoUpdateDialog.value) {
        AutoUpdateDialog(
            version = autoUpdateVersion,
            body = autoUpdateBody,
            downloadUrl = autoUpdateDownloadUrl,
            releaseUrl = autoUpdateReleaseUrl,
            onDismiss = {
                credentialStore.markUpdateNoticeSeen("auto_${autoUpdateVersion}")
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

        composable(
            route = Routes.LOGIN,
            arguments = listOf(
                navArgument("loginType") { type = NavType.StringType },
                navArgument("target") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val typeName = backStackEntry.arguments?.getString("loginType") ?: "ATTENDANCE"
            val target = backStackEntry.arguments?.getString("target") ?: Routes.MAIN
            val loginType = LoginType.valueOf(typeName)
            LoginScreen(
                loginType = loginType,
                existingClient = loginState.getSharedClient(),
                visitorId = loginState.getVisitorId(),
                cachedRsaKey = loginState.getCachedRsaKey(),
                onLoginSuccess = { login, username, password ->
                    loginState.saveCredentials(username, password)
                    loginState.persistCredentials(credentialStore)
                    loginState.cache(login, username)
                    navController.popBackStack()
                    if (target == Routes.SCHEDULE) {
                        navigateToMainTab(BottomTab.COURSES)
                    } else {
                        navController.navigate(target) { launchSingleTop = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.EMPTY_ROOM) {
            // 直查需要一个已通过 JWXT 认证的 OkHttpClient：
            // 校外优先用 vpnClient（已含 WebVpnInterceptor），校内用 jwxtLogin.client
            val direct = if (loginState.isOnCampus == false) loginState.webVpnClientOrNull
                else loginState.jwxtLogin?.client
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
            loginState.attendanceLogin?.let { AttendanceScreen(login = it, onBack = { navController.popBackStack() }) } ?: LaunchedEffect(Unit) { navController.popBackStack() }
        }
        composable(Routes.POSTGRADUATE_ATTENDANCE) {
            loginState.postgraduateAttendanceLogin?.let { AttendanceScreen(login = it, onBack = { navController.popBackStack() }) } ?: LaunchedEffect(Unit) { navController.popBackStack() }
        }
        composable(Routes.SCHEDULE) {
            LaunchedEffect(Unit) {
                navigateToMainTab(BottomTab.COURSES)
            }
        }
        composable(Routes.JWAPP_SCORE) {
            JwappScoreScreen(login = loginState.jwappLogin, jwxtLogin = loginState.jwxtLogin, studentId = loginState.activeUsername, onBack = { navController.popBackStack() })
        }
        composable(Routes.JUDGE) {
            loginState.jwxtLogin?.let { JudgeScreen(login = it, username = loginState.activeUsername, onBack = { navController.popBackStack() }) } ?: LaunchedEffect(Unit) { navController.popBackStack() }
        }
        composable(Routes.LIBRARY) {
            loginState.libraryLogin?.let { LibraryScreen(login = it, onBack = { navController.popBackStack() }) } ?: LaunchedEffect(Unit) { navController.popBackStack() }
        }
        composable(Routes.CAMPUS_CARD) {
            loginState.campusCardLogin?.let { com.xjtu.toolbox.card.CampusCardScreen(login = it, onBack = { navController.popBackStack() }) } ?: LaunchedEffect(Unit) { navController.popBackStack() }
        }
        composable(Routes.COUPON) {
            loginState.couponLogin?.let { com.xjtu.toolbox.coupon.CouponScreen(login = it, onBack = { navController.popBackStack() }) } ?: LaunchedEffect(Unit) { navController.popBackStack() }
        }
        dialog(
            Routes.PAYMENT_CODE,
            dialogProperties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            val client = loginState.getActiveClient()
            if (client != null) {
                com.xjtu.toolbox.pay.PaymentCodeDialog(client = client, onDismiss = { navController.popBackStack() })
            } else {
                LaunchedEffect(Unit) { navController.popBackStack() }
            }
        }
        composable(Routes.SCORE_REPORT) {
            loginState.jwxtLogin?.let { ScoreReportScreen(login = it, studentId = loginState.activeUsername, onBack = { navController.popBackStack() }) } ?: LaunchedEffect(Unit) { navController.popBackStack() }
        }
        composable(Routes.TRANSCRIPT) {
            loginState.dzpzLogin?.let { com.xjtu.toolbox.dzpz.TranscriptScreen(login = it, onBack = { navController.popBackStack() }) } ?: LaunchedEffect(Unit) { navController.popBackStack() }
        }
        composable(Routes.VENUE) {
            loginState.venueLogin?.let { com.xjtu.toolbox.venue.VenueScreen(login = it, onBack = { navController.popBackStack() }) } ?: LaunchedEffect(Unit) { navController.popBackStack() }
        }
        composable(Routes.CLASS_REPLAY) {
            loginState.classLogin?.let { classLogin ->
                val context = androidx.compose.ui.platform.LocalContext.current
                com.xjtu.toolbox.classreplay.ClassScreen(
                    login = classLogin,
                    onBack = { navController.popBackStack() },
                    onPlayReplay = { login, activityId ->
                        navController.navigate(Routes.videoPlayer(activityId))
                    },
                    onDownloadReplay = { login, activityIds ->
                        // 启动下载流程
                        val appContext = context.applicationContext
                        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
                        scope.launch {
                            try {
                                val downloadManager = com.xjtu.toolbox.classreplay.DownloadManager.getInstance(appContext)
                                
                                // 获取课程名称和回放详情
                                val activities = activityIds.mapNotNull { id ->
                                    try {
                                        val detail = com.xjtu.toolbox.classreplay.fetchReplayDetail(login, id)
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
                                            val realUrl = com.xjtu.toolbox.classreplay.resolveVideoUrl(login, video.url)
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
            loginState.lmsLogin?.let { lmsLogin ->
                com.xjtu.toolbox.lms.LmsScreen(
                    login = lmsLogin,
                    onBack = { navController.popBackStack() }
                )
            } ?: LaunchedEffect(Unit) { navController.popBackStack() }
        }
        composable(Routes.NEO_COURSE) {
            val neoSession = remember { com.xjtu.toolbox.neo.NeoSession(context) }
            com.xjtu.toolbox.neo.NeoScreen(
                session = neoSession,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.JIAOCAI) {
            loginState.jiaocaiLogin?.let {
                com.xjtu.toolbox.jiaocai.JiaocaiScreen(login = it, onBack = { navController.popBackStack() })
            } ?: LaunchedEffect(Unit) { navController.popBackStack() }
        }
        composable(Routes.SCHOOL_COURSE) {
            com.xjtu.toolbox.schedule.SchoolCourseScreen(
                login = loginState.jwxtLogin,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.SCHOOL_CALENDAR) {
            com.xjtu.toolbox.calendar.SchoolCalendarScreen(
                login = loginState.jwxtLogin,
                onBack = { navController.popBackStack() }
            )
        }
        composable(
            Routes.VIDEO_PLAYER,
            arguments = listOf(navArgument("activityId") { type = NavType.IntType })
        ) { backStackEntry ->
            val activityId = backStackEntry.arguments?.getInt("activityId") ?: 0
            loginState.classLogin?.let { classLogin ->
                com.xjtu.toolbox.classreplay.VideoPlayerScreen(
                    login = classLogin,
                    activityId = activityId,
                    onBack = { navController.popBackStack() }
                )
            } ?: LaunchedEffect(Unit) { navController.popBackStack() }
        }
        composable(Routes.BROWSER,
            arguments = listOf(navArgument("url") { type = NavType.StringType; defaultValue = "" })
        ) { backStackEntry ->
            val url = try { java.net.URLDecoder.decode(backStackEntry.arguments?.getString("url") ?: "", "UTF-8") } catch (_: Exception) { "" }
            com.xjtu.toolbox.browser.BrowserScreen(
                initialUrl = url,
                login = loginState.jwxtLogin,
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
                onDefaultTabChanged = { /* 下次启动生效 */ }
            )
        }

        // ── WebVPN 网址互转 ──
        composable(Routes.WEBVPN_CONVERTER) {
            com.xjtu.toolbox.webvpn.WebVpnConverterScreen(
                isWebVpnReady = loginState.webVpnClientOrNull != null,
                onBack = { navController.popBackStack() },
                onOpenWithWebVpn = onOpenWithWebVpn@{ vpnUrl ->
                    // 若 vpnClient 已就绪 → 直接打开浏览器（cookieJar 共享，WebView 自动同步 webvpn session）
                    if (loginState.webVpnClientOrNull != null) {
                        navController.navigate(Routes.browser(vpnUrl))
                        return@onOpenWithWebVpn
                    }
                    // vpnClient 未就绪：先回到主屏（让全局 MFA Dialog 可见），再触发 WebVPN 登录
                    navController.popBackStack()
                    webVpnPendingBrowserUrl.value = vpnUrl
                }
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

    // [H1] Snackbar 状态（登录失败分类提示）
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
        // 快速网络检测（ConnectivityManager，瞬时，不阻塞）
        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        val isOnline = cm?.activeNetwork != null && cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

        // 离线可用的路由（有本地缓存支持）
        val offlineCapableRoutes = setOf(Routes.SCHEDULE, Routes.JWAPP_SCORE)

        // ── 断网处理（优先于所有登录检查）──
        if (!isOnline) {
            if (target in offlineCapableRoutes) {
                navigateToTarget(target)
                scope.launch { snackbarHostState.showSnackbar("当前无网络，进入离线模式", duration = SnackbarDuration.Short) }
            } else {
                scope.launch { snackbarHostState.showSnackbar("该功能需要联网使用，请检查网络连接", duration = SnackbarDuration.Short) }
            }
            return
        }

        // ── 在线：有缓存 login → 直接进入 ──
        if (loginState.getCached(type) != null) {
            navigateToTarget(target)
        } else if (loginState.hasCredentials) {
            // 用户主动点击：永远允许立即登录（即使刚才取消过 MFA），由用户自己决定再次取消还是验证。
            // 冷却仅作用于后台 warmup（autoLogin 默认 force=false）。
            // 有保存的凭据，尝试自动登录
            showAutoLoginSheet.value = true
            autoLoginMessage = when (loginState.pendingRetryReason) {
                AppLoginState.RetryReason.NETWORK_CHANGED -> "网络环境已切换，正在重新连接${type.label}..."
                AppLoginState.RetryReason.TOKEN_EXPIRED -> "登录已过期，正在重新登录${type.label}..."
                null -> "正在自动登录${type.label}..."
            }
            loginState.pendingRetryReason = null  // 一次性消费
            val autoLoginTimeoutMs = if (type == LoginType.COUPON) 180_000L else 25_000L
            autoLoginJob?.cancel() // 取消旧的登录任务，避免竞态
            autoLoginJob = scope.launch {
                try {
                    val result = kotlinx.coroutines.withTimeoutOrNull(autoLoginTimeoutMs) {
                        loginState.autoLogin(type, force = true)
                    }
                    showAutoLoginSheet.value = false
                    autoLoginJob = null
                    if (result != null) {
                        navigateToTarget(target)
                    } else if (loginState.pendingMfaLogin != null) {
                        // autoLogin 触发了 MFA → 记录导航目标，UI 层会自动弹出 MFA 对话框
                        loginState.pendingMfaTarget = target
                    } else {
                        // autoLogin 返回 null 且没有 pendingMfa：可能是网络不通 / 密码错误 / 服务故障
                        // 不再展示「受限请连 WebVPN/校园网」这种迷惑提示，autoLogin 内部已按网络环境自动 fallback
                        if (target in offlineCapableRoutes) {
                            navigateToTarget(target)
                            scope.launch {
                                snackbarHostState.showSnackbar("无法连接到${type.label}，已进入离线模式", duration = SnackbarDuration.Short)
                            }
                        } else {
                            scope.launch {
                                snackbarHostState.showSnackbar("无法连接到${type.label}，请检查网络或稍后重试", duration = SnackbarDuration.Short)
                            }
                        }
                    }
                } catch (e: Exception) {
                    showAutoLoginSheet.value = false
                    autoLoginJob = null
                    // 登录态失效（reAuth 失败）→ 清缓存 + 重新 autoLogin（CAS 触发 MFA 时会自动弹窗）
                    if (e is AuthExpiredException) {
                        android.util.Log.w("Login", "AuthExpired for $type, clearing cache and retrying full login")
                        loginState.clearLogin(type)
                        autoLoginMessage = "正在重新登录${type.label}..."
                        showAutoLoginSheet.value = true
                        autoLoginJob = scope.launch {
                            try {
                                val r2 = kotlinx.coroutines.withTimeoutOrNull(autoLoginTimeoutMs) { loginState.autoLogin(type) }
                                showAutoLoginSheet.value = false
                                autoLoginJob = null
                                if (r2 != null) {
                                    navigateToTarget(target)
                                } else if (loginState.pendingMfaLogin != null) {
                                    loginState.pendingMfaTarget = target
                                } else {
                                    scope.launch { snackbarHostState.showSnackbar("${type.label}登录失败，请稍后重试", duration = SnackbarDuration.Short) }
                                }
                            } catch (e2: Exception) {
                                showAutoLoginSheet.value = false
                                autoLoginJob = null
                                scope.launch { snackbarHostState.showSnackbar("${type.label}登录失败：${e2.message}", duration = SnackbarDuration.Short) }
                            }
                        }
                        return@launch
                    }
                    // 离线可用路由降级
                    if (target in offlineCapableRoutes) {
                        navigateToTarget(target)
                        val msg = "网络不佳，进入离线模式"
                        scope.launch { snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short) }
                    } else {
                        val msg = when (e) {
                            is java.io.IOException -> "网络不佳，请检查网络连接"
                            else -> "${type.label}登录失败，请稍后重试"
                        }
                        scope.launch { snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short) }
                        selectedTabOrdinal = BottomTab.PROFILE.ordinal
                    }
                }
            }
        } else {
            // 没有凭据，切换到"我的"标签页让用户登录
            selectedTabOrdinal = BottomTab.PROFILE.ordinal
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

    // 液态玻璃 backdrop（miuix-blur）
    val backdrop = rememberLayerBackdrop()

    // 大屏适配：宽度 ≥ 840dp（Material expanded breakpoint，平板/桌面）启用侧边 NavigationRail
    // 手机横屏/折叠屏内屏（600-839dp）继续用底栏，保留液态玻璃观感
    val isWideScreen = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 840

    // HOME 大标题
    val homeGreeting = if (loginState.isLoggedIn) {
        val name = loginState.nsaProfile?.name ?: loginState.ywtbUserInfo?.userName ?: loginState.cachedNickname
        if (!name.isNullOrBlank()) "你好, $name" else "你好"
    } else "岱宗盒子"

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
                    BottomTab.HOME -> homeGreeting
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
                    color = androidx.compose.ui.graphics.Color.Transparent,
                    modifier = Modifier.textureBlur(
                        backdrop = backdrop,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                        blurRadius = 36f
                    ),
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
                .layerBackdrop(backdrop)
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
            val networkRequiredRoutes = setOf(Routes.EMPTY_ROOM, Routes.NOTIFICATION)
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

            // ── 全局 MFA 两步验证对话框（autoLogin 触发）──
            val globalMfaLogin = loginState.pendingMfaLogin
            val showGlobalMfa = remember { mutableStateOf(false) }
            var globalMfaPhone by remember { mutableStateOf("") }
            var globalMfaCode by remember { mutableStateOf("") }
            var globalMfaSending by remember { mutableStateOf(false) }
            var globalMfaVerifying by remember { mutableStateOf(false) }
            var globalMfaError by remember { mutableStateOf<String?>(null) }
            var globalMfaCodeSent by remember { mutableStateOf(false) }

            LaunchedEffect(globalMfaLogin) {
                if (globalMfaLogin != null) {
                    // 自动获取手机号并弹出对话框
                    try {
                        val phone = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            globalMfaLogin.mfaContext!!.getPhoneNumber()
                        }
                        globalMfaPhone = phone
                        globalMfaCode = ""
                        globalMfaCodeSent = false
                        globalMfaError = null
                        showGlobalMfa.value = true
                    } catch (e: Exception) {
                        snackbarHostState.showSnackbar("获取验证手机号失败: ${e.message}", duration = SnackbarDuration.Short)
                        loginState.pendingMfaLogin = null
                        loginState.pendingMfaTarget = null
                        loginState.pendingMfaType = null
                        loginState.pendingMfaContinuation = null
                    }
                } else {
                    showGlobalMfa.value = false
                }
            }

            if (globalMfaLogin != null && showGlobalMfa.value) {
                // MFA 弹窗使用 Miuix SuperDialog：内置 imePadding 抗键盘弹飞、大屏自适应，
                // 整体风格与 app 其它弹窗保持一致。
                val mfaSourceLabel = remember(loginState.pendingMfaTarget, loginState.pendingMfaType) {
                    when {
                        loginState.pendingMfaTarget == "_WEBVPN_" -> "WebVPN（校外接入）"
                        loginState.pendingMfaType != null -> loginState.pendingMfaType?.label ?: "统一身份认证"
                        else -> "统一身份认证"
                    }
                }
                fun cancelMfa() {
                    showGlobalMfa.value = false
                    loginState.pendingMfaLogin = null
                    loginState.pendingMfaTarget = null
                    loginState.pendingMfaType = null
                    loginState.pendingMfaContinuation = null
                    loginState.mfaCancelledAt = System.currentTimeMillis()
                }
                BackHandler(enabled = true) { cancelMfa() }
                OverlayDialog(
                    show = showGlobalMfa.value,
                    title = "两步验证",
                    summary = "登录「$mfaSourceLabel」需要短信验证码",
                    onDismissRequest = { cancelMfa() }
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .imePadding(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            "验证码将发送至 $globalMfaPhone",
                            style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                        // 发送验证码按钮
                        if (!globalMfaCodeSent) {
                            Button(
                                onClick = {
                                    globalMfaSending = true
                                    globalMfaError = null
                                    scope.launch {
                                        try {
                                            val ctx = globalMfaLogin.mfaContext ?: run { globalMfaError = "MFA 上下文丢失"; return@launch }
                                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                ctx.sendVerifyCode()
                                            }
                                            globalMfaCodeSent = true
                                        } catch (e: Exception) {
                                            globalMfaError = "发送失败: ${e.message}"
                                        }
                                        globalMfaSending = false
                                    }
                                },
                                enabled = !globalMfaSending,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (globalMfaSending) {
                                    CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(if (globalMfaSending) "发送中..." else "发送验证码")
                            }
                        }
                        // 验证码输入
                        if (globalMfaCodeSent) {
                            TextField(
                                value = globalMfaCode,
                                onValueChange = { globalMfaCode = it.take(6); globalMfaError = null },
                                label = "6位验证码",
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                        }
                        globalMfaError?.let {
                            Text(it, color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.footnote1)
                        }
                        // 验证并登录
                        if (globalMfaCodeSent) {
                            Button(
                                onClick = {
                                    if (globalMfaCode.length != 6) {
                                        globalMfaError = "请输入6位验证码"
                                        return@Button
                                    }
                                    globalMfaVerifying = true
                                    globalMfaError = null
                                    scope.launch {
                                        try {
                                            val login = globalMfaLogin
                                            val mfa = login.mfaContext ?: run { globalMfaError = "MFA 上下文丢失"; globalMfaVerifying = false; return@launch }
                                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                mfa.verifyCode(globalMfaCode)
                                            }
                                            // MFA 验证通过，根据 continuation 决定后续动作
                                            val continuation = loginState.pendingMfaContinuation
                                            val target = loginState.pendingMfaTarget
                                            val error = if (continuation != null) {
                                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                    continuation(login)
                                                }
                                            } else {
                                                val user = loginState.activeUsername
                                                loginState.finishLoginAfterMfa(login, user)
                                            }
                                            if (error != null) {
                                                globalMfaError = error
                                                globalMfaVerifying = false
                                                return@launch
                                            }
                                            // 成功：关闭对话框
                                            globalMfaVerifying = false
                                            showGlobalMfa.value = false
                                            loginState.pendingMfaLogin = null
                                            loginState.pendingMfaTarget = null
                                            loginState.pendingMfaType = null
                                            loginState.pendingMfaContinuation = null
                                            // WebVPN MFA 完成后：vpnClient 已在 continuation 内建立，下次访问自动用
                                            if (target == "_WEBVPN_") {
                                                snackbarHostState.showSnackbar("WebVPN 已就绪，可以正常使用了", duration = SnackbarDuration.Short)
                                            } else if (target != null) {
                                                navigateToTarget(target)
                                            }
                                        } catch (e: Exception) {
                                            globalMfaError = e.message ?: "验证失败"
                                            globalMfaVerifying = false
                                        }
                                    }
                                },
                                enabled = !globalMfaVerifying,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (globalMfaVerifying) {
                                    CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(if (globalMfaVerifying) "验证中..." else "验证并登录")
                            }
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
                .padding(horizontal = 20.dp)
                .padding(top = 8.dp)
        ) {
            // 日期行
            val today = java.time.LocalDate.now()
            val weekDay = today.dayOfWeek.getDisplayName(
                java.time.format.TextStyle.FULL, java.util.Locale.CHINESE
            )
            Text(
                "${today.monthValue}月${today.dayOfMonth}日  $weekDay",
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            // 状态 or 登录提示
            if (!loginState.isLoggedIn) {
                Spacer(Modifier.height(12.dp))
                Button(onClick = onNavigateToProfile) {
                    Icon(Icons.Default.AccountCircle, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("登录以使用全部功能")
                }
            } else {
                Spacer(Modifier.height(8.dp))
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
                    val sessionColor = if (loginState.loginCount > 0)
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
                            val visibleTypes = remember {
                                listOf(LoginType.JWXT, LoginType.JWAPP, LoginType.LMS, LoginType.CLASS,
                                    LoginType.YWTB, LoginType.LIBRARY, LoginType.CAMPUS_CARD, LoginType.VENUE,
                                    LoginType.ATTENDANCE, LoginType.DZPZ, LoginType.JIAOCAI, LoginType.COUPON)
                            }
                            val ok = visibleTypes.count { loginState.getCached(it) != null }
                            Text(
                                when {
                                    isRestoring -> "正在连接…"
                                    ok > 0 -> "$ok / ${visibleTypes.size} 已就绪"
                                    else -> "离线模式"
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
                            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                                val types = listOf(
                                    LoginType.JWXT, LoginType.JWAPP, LoginType.LMS, LoginType.CLASS,
                                    LoginType.YWTB, LoginType.LIBRARY, LoginType.CAMPUS_CARD, LoginType.VENUE,
                                    LoginType.ATTENDANCE, LoginType.POSTGRADUATE_ATTENDANCE, LoginType.DZPZ,
                                    LoginType.JIAOCAI, LoginType.COUPON
                                )
                                types.forEach { t ->
                                    val cached = loginState.getCached(t)
                                    Row(
                                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val statusColor = if (cached != null) androidx.compose.ui.graphics.Color(0xFF2E7D32)
                                        else MiuixTheme.colorScheme.onSurfaceVariantSummary
                                        Icon(
                                            if (cached != null) Icons.Default.CheckCircle else Icons.Default.RemoveCircleOutline,
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
                                            if (cached != null) "已连接" else "未登录",
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

        // ── Zone B: Quick Actions ──
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                "快捷入口",
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
                Quick(Routes.EMPTY_ROOM, Icons.Default.LocationOn, "空闲教室", colorIndigo) { onNavigate(Routes.EMPTY_ROOM) },
                Quick(Routes.PAYMENT_CODE, Icons.Default.QrCode, "付款码", colorTeal) { onNavigateWithLogin(Routes.PAYMENT_CODE, LoginType.JWXT) },
                Quick(Routes.NOTIFICATION, Icons.Default.Notifications, "通知", colorOrange) { onNavigate(Routes.NOTIFICATION) },
                Quick(Routes.JWAPP_SCORE, Icons.Default.Assessment, "成绩", colorPurple) { onNavigateWithLogin(Routes.JWAPP_SCORE, LoginType.JWAPP) },
                Quick(Routes.COUPON, Icons.Default.Restaurant, "加餐券", colorAmber) { onNavigateWithLogin(Routes.COUPON, LoginType.COUPON) },
                Quick(Routes.LIBRARY, Icons.Default.Chair, "图书馆", colorOrange) { onNavigateWithLogin(Routes.LIBRARY, LoginType.LIBRARY) },
                Quick(Routes.LMS, Icons.Default.School, "思源", colorIndigo) { onNavigateWithLogin(Routes.LMS, LoginType.LMS) }
            )
            val quickKeys = remember(quickPool) {
                com.xjtu.toolbox.util.ServiceUsageTracker.topKeys(
                    ctxQuick,
                    quickPool.map { it.key },
                    n = 4,
                    fallback = listOf(Routes.CAMPUS_CARD, Routes.EMPTY_ROOM, Routes.PAYMENT_CODE, Routes.NOTIFICATION)
                )
            }
            val quickShown = quickKeys.mapNotNull { k -> quickPool.find { it.key == k } }
            Card(Modifier.fillMaxWidth(), cornerRadius = 18.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 14.dp),
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
        }

        Spacer(Modifier.height(24.dp))

        // ── Zone B+: 智能卡片 ──
        run {
            val context = LocalContext.current
            val isSummer = com.xjtu.toolbox.util.XjtuTime.isSummerTime()
            // 日程提醒数据（合并教务课程与自定义日程）
            var scheduleReminderState by remember { mutableStateOf<ScheduleReminderInfo?>(null) }
            var isScheduleReminderLoaded by remember { mutableStateOf(false) }
            // 缓存余额数据（从 SharedPreferences 读取）
            val cardPrefs = remember { context.getSharedPreferences("campus_card", 0) }
            var cachedBalance by remember { mutableStateOf(cardPrefs.getFloat("card_balance_cache", -1f)) }
            var cachedName by remember { mutableStateOf(cardPrefs.getString("card_name_cache", "") ?: "") }
            var cachedRecentTx by remember {
                val json = cardPrefs.getString("card_recent_tx_cache", null)
                mutableStateOf(if (json != null) runCatching {
                    com.google.gson.Gson().fromJson(json, Array<com.xjtu.toolbox.card.Transaction>::class.java)?.toList()
                }.getOrNull() ?: emptyList() else emptyList())
            }
            var isRefreshingCard by remember { mutableStateOf(false) }

            fun reloadCampusCardCache() {
                cachedBalance = cardPrefs.getFloat("card_balance_cache", -1f)
                cachedName = cardPrefs.getString("card_name_cache", "") ?: ""
                val json = cardPrefs.getString("card_recent_tx_cache", null)
                cachedRecentTx = if (json != null) runCatching {
                    com.google.gson.Gson().fromJson(json, Array<com.xjtu.toolbox.card.Transaction>::class.java)?.toList()
                }.getOrNull() ?: emptyList() else emptyList()
            }

            LaunchedEffect(loginState.campusCardCacheVersion) {
                reloadCampusCardCache()
            }

            LaunchedEffect(Unit) {
                val loadedReminder = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val dataCache = com.xjtu.toolbox.util.DataCache(context)
                        val gson = com.google.gson.Gson()
                        // 获取学期列表（按最新的来）
                        val termListJson = dataCache.get("schedule_term_list", Long.MAX_VALUE)
                        val termList = if (termListJson != null) {
                            gson.fromJson(termListJson, Array<String>::class.java)?.toList() ?: emptyList()
                        } else emptyList<String>()
                        val termCode = termList.firstOrNull() ?: return@withContext null
                        // 获取 API 课程
                        val apiCourses = com.xjtu.toolbox.schedule.ScheduleCache
                            .readOptimizedCourses(dataCache, gson, termCode, Long.MAX_VALUE)
                            ?: com.xjtu.toolbox.schedule.ScheduleCache
                                .readRawCourses(dataCache, gson, termCode, Long.MAX_VALUE)
                            ?: emptyList()
                        // 获取自定义课程（Room DB）
                        val customCourses = try {
                            com.xjtu.toolbox.util.AppDatabase.getInstance(context)
                                .customCourseDao().getByTerm(termCode)
                                .map { it.toCourseItem() }
                        } catch (_: Exception) { emptyList() }
                        val allSchedules = apiCourses + customCourses
                        // 获取学期开始日期
                        val startDateJson = dataCache.get("start_date_$termCode", Long.MAX_VALUE)
                        val startDateStr = if (startDateJson != null) gson.fromJson(startDateJson, String::class.java) else null
                        val startDate = if (!startDateStr.isNullOrBlank()) runCatching { java.time.LocalDate.parse(startDateStr) }.getOrNull() else null
                        if (startDate == null) {
                            return@withContext null
                        }

                        // 获取节假日信息
                        val holidayDates = try {
                            com.xjtu.toolbox.schedule.HolidayApi.getHolidayDates(context)
                        } catch (_: Exception) {
                            emptyMap()
                        }

                        val today = java.time.LocalDate.now()
                        val nowDateTime = java.time.LocalDateTime.now()
                        for (offset in 0..14) {
                            val targetDate = today.plusDays(offset.toLong())
                            if (holidayDates.containsKey(targetDate)) continue // 如果是节假日，直接跳过当天日程

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
                                .sortedBy { it.resolveStartMinute(isSummer) ?: Int.MAX_VALUE }
                            for (schedule in daySchedules) {
                                val startMinute = schedule.resolveStartMinute(isSummer) ?: continue
                                val safeStartMinute = startMinute.coerceIn(0, (24 * 60) - 1)
                                val startAt = targetDate.atTime(safeStartMinute / 60, safeStartMinute % 60)
                                if (!startAt.isAfter(nowDateTime)) continue

                                val endMinute = schedule.resolveEndMinute(isSummer)
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

            Column(Modifier.padding(horizontal = 16.dp)) {
                // ═══ 日程提醒智能卡片 ═══
                Card(
                    onClick = {
                        onNavigateToCourses()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 20.dp,
                    colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant),
                    pressFeedbackType = PressFeedbackType.Sink
                ) {
                    val scheduleReminder = scheduleReminderState
                    val currentDateTime = java.time.LocalDateTime.now()
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.NotificationsActive, null, Modifier.size(16.dp), tint = androidx.compose.ui.graphics.Color(0xFFE65100))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "日程提醒",
                                style = MiuixTheme.textStyles.footnote1,
                                fontWeight = FontWeight.Bold,
                                color = androidx.compose.ui.graphics.Color(0xFFE65100),
                                modifier = Modifier.weight(1f)
                            )
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(16.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f))
                        }

                        when {
                            !isScheduleReminderLoaded -> {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "正在读取日程...",
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            }

                            scheduleReminder != null -> {
                                val minutesUntil = java.time.Duration.between(currentDateTime, scheduleReminder.startAt)
                                    .toMinutes()
                                    .coerceAtLeast(0)
                                val dayLabel = formatScheduleReminderDateLabel(
                                    scheduleReminder.startAt.toLocalDate(),
                                    currentDateTime.toLocalDate()
                                )
                                val startMinuteOfDay = scheduleReminder.startAt.hour * 60 + scheduleReminder.startAt.minute
                                val startLabel = formatMinuteClock(startMinuteOfDay)
                                val endLabel = scheduleReminder.endAt?.let { formatMinuteClock(it.hour * 60 + it.minute) }
                                val timeLabel = if (endLabel != null) "$startLabel-$endLabel" else startLabel

                                Spacer(Modifier.height(8.dp))
                                Text(
                                    formatScheduleReminderEta(minutesUntil),
                                    style = MiuixTheme.textStyles.body1,
                                    fontWeight = FontWeight.Bold,
                                    color = MiuixTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "$dayLabel $timeLabel · ${scheduleReminder.name}",
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (scheduleReminder.location.isNotBlank()) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        scheduleReminder.location,
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            else -> {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "未来两周没有新的日程安排",
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                // ═══ 校园卡智能卡片 ═══
                if (cachedBalance >= 0f || loginState.campusCardLogin != null) {
                    val scope = rememberCoroutineScope()
                    val isLowBalance = cachedBalance in 0f..30f
                    Card(
                        onClick = { onNavigateWithLogin(Routes.CAMPUS_CARD, LoginType.CAMPUS_CARD) },
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 20.dp,
                        colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant),
                        pressFeedbackType = PressFeedbackType.Sink
                    ) {
                        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                            // 头部：标题 + 刷新
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CreditCard, null, Modifier.size(16.dp), tint = if (isLowBalance) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.primary)
                                Spacer(Modifier.width(6.dp))
                                Text("校园卡", style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Bold, color = if (isLowBalance) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                                if (isLowBalance) {
                                    Surface(shape = RoundedCornerShape(4.dp), color = MiuixTheme.colorScheme.errorContainer) {
                                        Text("余额不足", Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.error, fontSize = 10.sp)
                                    }
                                    Spacer(Modifier.width(8.dp))
                                }
                                // 刷新按钮
                                IconButton(
                                    onClick = {
                                        val login = loginState.campusCardLogin ?: return@IconButton
                                        isRefreshingCard = true
                                        scope.launch {
                                            try {
                                                refreshCampusCardCache(context, login)
                                                loginState.campusCardCacheVersion++
                                                reloadCampusCardCache()
                                            } catch (_: Exception) {}
                                            isRefreshingCard = false
                                        }
                                    },
                                    enabled = !isRefreshingCard && loginState.campusCardLogin != null,
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    if (isRefreshingCard) CircularProgressIndicator(size = 14.dp, strokeWidth = 2.dp)
                                    else Icon(Icons.Default.Refresh, "刷新", Modifier.size(16.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                }
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(16.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f))
                            }
                            // 余额显示
                            if (cachedBalance >= 0f) {
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text("¥", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Bold, color = if (isLowBalance) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurface)
                                    Spacer(Modifier.width(2.dp))
                                    Text(
                                        "%.2f".format(cachedBalance),
                                        style = MiuixTheme.textStyles.title1,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isLowBalance) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurface
                                    )
                                    if (cachedName.isNotBlank()) {
                                        Spacer(Modifier.weight(1f))
                                        Text(cachedName, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                    }
                                }
                            }
                            // 最近消费
                            if (cachedRecentTx.isNotEmpty()) {
                                Spacer(Modifier.height(10.dp))
                                HorizontalDivider(color = MiuixTheme.colorScheme.outline.copy(alpha = 0.1f))
                                Spacer(Modifier.height(8.dp))
                                cachedRecentTx.take(3).forEach { tx ->
                                    Row(
                                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(tx.merchant.ifBlank { tx.type }, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurface, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        val amtStr = if (tx.amount < 0) "-¥${"%.2f".format(-tx.amount)}" else "+¥${"%.2f".format(tx.amount)}"
                                        Text(amtStr, style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Medium, color = if (tx.amount < 0) MiuixTheme.colorScheme.onSurface else androidx.compose.ui.graphics.Color(0xFF2E7D32))
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                "全部服务",
                style = MiuixTheme.textStyles.headline1,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
            )

            val svcGreen = androidx.compose.ui.graphics.Color(0xFF2E7D32)
            val svcOrange = androidx.compose.ui.graphics.Color(0xFFE65100)
            val svcPurple = androidx.compose.ui.graphics.Color(0xFF7B1FA2)
            val svcTeal = androidx.compose.ui.graphics.Color(0xFF00796B)
            val svcIndigo = androidx.compose.ui.graphics.Color(0xFF283593)
            val svcBrown = androidx.compose.ui.graphics.Color(0xFF4E342E)
            val svcCyan = androidx.compose.ui.graphics.Color(0xFF00838F)
            val svcPink = androidx.compose.ui.graphics.Color(0xFFC2185B)
            val svcDeepPurple = androidx.compose.ui.graphics.Color(0xFF512DA8)
            val svcLime = androidx.compose.ui.graphics.Color(0xFF5D8C2A)
            val ctx = LocalContext.current
            // 全部服务定义（key, icon, title, subtitle, color, hint, onClick）
            data class Svc(
                val key: String,
                val icon: ImageVector,
                val title: String,
                val subtitle: String,
                val color: androidx.compose.ui.graphics.Color,
                val hint: String,
                val onClick: () -> Unit
            )
            val services = listOf(
                Svc(Routes.CAMPUS_CARD, Icons.Default.CreditCard, "校园卡", "账单 · 洞察", svcGreen, "消费智能分析") { onNavigateWithLogin(Routes.CAMPUS_CARD, LoginType.CAMPUS_CARD) },
                Svc(Routes.SCHEDULE, Icons.Default.CalendarMonth, "日程", "我的日程", svcIndigo, "课表教材与课程") { onNavigateToCourses() },
                Svc(Routes.JWAPP_SCORE, Icons.Default.Assessment, "成绩查询", "成绩 · GPA", svcPurple, "未评教也可查") { onNavigateWithLogin(Routes.JWAPP_SCORE, LoginType.JWAPP) },
                Svc(Routes.PAYMENT_CODE, Icons.Default.QrCode, "付款码", "校园支付", svcTeal, "快速刷新") { onNavigateWithLogin(Routes.PAYMENT_CODE, LoginType.JWXT) },
                Svc(Routes.COUPON, Icons.Default.Restaurant, "加餐券", "电子券", svcLime, "餐券领取与管理") { onNavigateWithLogin(Routes.COUPON, LoginType.COUPON) },
                Svc(Routes.ATTENDANCE, Icons.Default.DateRange, "考勤查询", "出勤记录", svcBrown, "出勤情况分析") { onNavigateWithLogin(Routes.ATTENDANCE, LoginType.ATTENDANCE) },
                Svc(Routes.POSTGRADUATE_ATTENDANCE, Icons.Default.DateRange, "研究生考勤", "研究生出勤", svcBrown, "研究生考勤查询") { onNavigateWithLogin(Routes.POSTGRADUATE_ATTENDANCE, LoginType.POSTGRADUATE_ATTENDANCE) },
                Svc(Routes.TRANSCRIPT, Icons.Default.Description, "电子成绩单", "下载 · 签章", svcIndigo, "下载签章成绩单") { onNavigateWithLogin(Routes.TRANSCRIPT, LoginType.DZPZ) },
                Svc(Routes.JUDGE, Icons.Default.RateReview, "本科评教", "评教系统", svcPink, "支持一键评教") { onNavigateWithLogin(Routes.JUDGE, LoginType.JWXT) },
                Svc(Routes.LIBRARY, Icons.Default.Chair, "图书馆", "座位预约", svcOrange, "智能座位推荐") { onNavigateWithLogin(Routes.LIBRARY, LoginType.LIBRARY) },
                Svc(Routes.EMPTY_ROOM, Icons.Default.LocationOn, "空闲教室", "教室查询", svcPurple, "智能洞察空教室") { onNavigate(Routes.EMPTY_ROOM) },
                Svc(Routes.NOTIFICATION, Icons.Default.Notifications, "通知公告", "校园通知", MiuixTheme.colorScheme.error, "聚合多源通知") { onNavigate(Routes.NOTIFICATION) },
                Svc(Routes.VENUE, Icons.Default.Place, "场馆预订", "运动场地", svcCyan, "预约快人一步") { onNavigateWithLogin(Routes.VENUE, LoginType.VENUE) },
                Svc(Routes.CLASS_REPLAY, Icons.Default.OndemandVideo, "课程回放", "Class 录播", svcDeepPurple, "备用回放入口") { onNavigateWithLogin(Routes.CLASS_REPLAY, LoginType.CLASS) },
                Svc(Routes.LMS, Icons.Default.School, "思源学堂", "课件 · 作业", svcIndigo, "回放课件与作业") { onNavigateWithLogin(Routes.LMS, LoginType.LMS) },
                Svc(Routes.SCHOOL_COURSE, Icons.Default.TravelExplore, "课程查询", "全校课程", svcCyan, "选课先踩点") { onNavigateWithLogin(Routes.SCHOOL_COURSE, LoginType.JWXT) },
                Svc(Routes.SCHOOL_CALENDAR, Icons.Default.EventNote, "校历", "学期 · 周次", svcTeal, "看看多少假期") { onNavigate(Routes.SCHOOL_CALENDAR) },
                Svc(Routes.NEO_COURSE, Icons.Default.Star, "拔尖课程", "NeoSchool", svcPurple, "钱院线上课程") { onNavigate(Routes.NEO_COURSE) },
                Svc(Routes.JIAOCAI, Icons.Default.MenuBook, "教材中心", "在线阅览", svcTeal, "查询与阅读") { onNavigateWithLogin(Routes.JIAOCAI, LoginType.JIAOCAI) },
                Svc(Routes.WEBVPN_CONVERTER, Icons.Default.VpnKey, "WebVPN 转换", "校外访问", svcBrown, "网址互转 + 一键访问") { onNavigate(Routes.WEBVPN_CONVERTER) }
            )
            // 按使用习惯+随机决定哪些卡显示 hint（首次使用兜底默认集合）
            val highlightSet = remember(services) {
                com.xjtu.toolbox.util.ServiceUsageTracker.highlightSet(
                    ctx,
                    services.map { it.key },
                    topN = 5,
                    randomCount = 2,
                    defaultHighlights = setOf(Routes.CAMPUS_CARD, Routes.SCHEDULE, Routes.JWAPP_SCORE, Routes.LMS, Routes.LIBRARY)
                )
            }
            // 重排：高卡(含 hint)与普通卡交错入序，确保左右两列高度均匀错落
            val orderedServices = remember(services, highlightSet) {
                val (high, low) = services.partition { it.key in highlightSet }
                buildList {
                    val maxLen = maxOf(high.size, low.size)
                    for (i in 0 until maxLen) {
                        if (i < high.size) add(high[i])
                        if (i < low.size) add(low[i])
                    }
                }
            }
            // 真瀑布流：贪心放最短列；交错排序后两列高度自然均衡
            StaggeredFlow(columns = 2, spacing = 10.dp, modifier = Modifier.fillMaxWidth()) {
                orderedServices.forEach { svc ->
                    HomeServiceCard(
                        svc.icon, svc.title, svc.subtitle, svc.color,
                        hint = if (svc.key in highlightSet) svc.hint else null
                    ) {
                        com.xjtu.toolbox.util.ServiceUsageTracker.record(ctx, svc.key)
                        svc.onClick()
                    }
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
            login = loginState.jwxtLogin,
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

    // MFA 两步验证状态
    var mfaLogin by remember { mutableStateOf<XJTULogin?>(null) }
    var mfaPhone by remember { mutableStateOf("") }
    var mfaCode by remember { mutableStateOf("") }
    var mfaSending by remember { mutableStateOf(false) }
    var mfaVerifying by remember { mutableStateOf(false) }
    var mfaError by remember { mutableStateOf<String?>(null) }
    var mfaCodeSent by remember { mutableStateOf(false) }

    // Srun 校园网首次配置弹窗状态
    val showSrunSetupSheet = remember { mutableStateOf(false) }
    var srunSetupUsername by remember { mutableStateOf("") }
    var srunSetupPassword by remember { mutableStateOf("") }
    var srunSetupSaving by remember { mutableStateOf(false) }
    var srunSetupHint by remember { mutableStateOf<String?>(null) }

    // MFA 完成后继续登录流程
    fun finishMfaAndLogin() {
        scope.launch {
            mfaVerifying = true
            mfaError = null
            try {
                val login = mfaLogin ?: return@launch
                // 验证码校验
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    login.mfaContext!!.verifyCode(mfaCode)
                }
                // MFA 通过，继续登录
                val error = loginState.finishLoginAfterMfa(login, username)
                if (error != null) {
                    mfaError = error
                    mfaVerifying = false
                    return@launch
                }
                // 登录成功
                mfaVerifying = false
                mfaLogin = null  // 关闭 MFA 对话框
                mfaCode = ""

                // WebVPN + 后台并行贯通所有子系统（委托 warmup 统一调度，覆盖所有 LoginType）
                if (loginState.isOnCampus == false) loginState.loginWebVpn()
                loginState.persistCredentials(credentialStore)
                onWarmupRequest()
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        // YWTB 个人信息（warmup 内 autoLogin(YWTB) 完成后异步取）
                        kotlinx.coroutines.delay(1500L)
                        if (loginState.ywtbLogin != null && loginState.ywtbUserInfo == null) {
                            val api = com.xjtu.toolbox.ywtb.YwtbApi(loginState.ywtbLogin!!)
                            loginState.ywtbUserInfo = api.getUserInfo()
                        }
                    } catch (_: Exception) { }
                }
            } catch (e: Exception) {
                mfaError = e.message ?: "验证失败"
                mfaVerifying = false
            }
        }
    }

    // 智能登录：JWXT→核心登录→YWTB后台
    fun loginAllSystems(user: String, pwd: String) {
        isLoggingIn = true
        loginError = null
        loginProgress = 0f
        loginState.saveCredentials(user, pwd)

        scope.launch {
            val startMs = System.currentTimeMillis()

            // ── Phase 1: JWXT 直接登录 + 网络检测 并行 ──
            loginStage = "认证中..."
            loginProgress = 0.1f
            var jwxtLogin: XJTULogin? = null
            var jwxtError: String? = null
            var needsMfa = false
            try {
                kotlinx.coroutines.coroutineScope {
                    val jwxtDeferred = async(kotlinx.coroutines.Dispatchers.IO) {
                        loginState.loginJwxtWithDetails(user, pwd)
                    }
                    val networkDeferred = async(kotlinx.coroutines.Dispatchers.IO) {
                        loginState.detectCampusNetwork()
                    }
                    val (login, error, mfa) = jwxtDeferred.await()
                    jwxtLogin = login
                    jwxtError = error
                    needsMfa = mfa
                    loginState.isOnCampus = networkDeferred.await()
                }
            } catch (e: Exception) {
                // coroutineScope 内部异常（如网络检测失败），不应终止整个登录
                if (jwxtLogin == null && jwxtError == null) {
                    jwxtError = "登录异常: ${e.message}"
                }
            }
            loginProgress = 0.5f

            // ── MFA 需要：弹出两步验证对话框 ──
            if (needsMfa && jwxtLogin != null) {
                isLoggingIn = false
                loginStage = ""
                // 获取手机号
                try {
                    val phone = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        jwxtLogin!!.mfaContext!!.getPhoneNumber()
                    }
                    mfaLogin = jwxtLogin
                    mfaPhone = phone
                    mfaCode = ""
                    mfaCodeSent = false
                    mfaError = null
                } catch (e: Exception) {
                    loginError = "获取 MFA 手机号失败: ${e.message}"
                }
                return@launch
            }

            if (jwxtLogin == null) {
                isLoggingIn = false
                loginError = jwxtError ?: "登录失败"
                // 不要 logout — 避免网络临时故障清空已保存的凭据
                return@launch
            }

            // ── Phase 2: WebVPN（校外时连接）──
            if (loginState.isOnCampus == false) {
                loginStage = "连接 VPN..."
                loginState.loginWebVpn()
            }
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

            // ── 后台: 并行贯通所有子系统（委托 warmup 统一调度，含校园卡/LMS/CLASS/DZPZ/VENUE/JIAOCAI）──
            onWarmupRequest()
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    kotlinx.coroutines.delay(1500L)  // 等 warmup 内 autoLogin(YWTB) 进展
                    if (loginState.ywtbLogin != null && loginState.ywtbUserInfo == null) {
                        val api = com.xjtu.toolbox.ywtb.YwtbApi(loginState.ywtbLogin!!)
                        loginState.ywtbUserInfo = api.getUserInfo()
                    }
                } catch (_: Exception) { }
            }
        }
    }

    // ── MFA 两步验证对话框（OverlayDialog 抗键盘弹飞）──
    val showMfaDialog = remember { mutableStateOf(false) }
    LaunchedEffect(mfaLogin) { showMfaDialog.value = mfaLogin != null }
    if (mfaLogin != null) {
        BackHandler(enabled = showMfaDialog.value) {
            showMfaDialog.value = false
            mfaLogin = null
            mfaCode = ""
            mfaError = null
        }
        OverlayDialog(
            show = showMfaDialog.value,
            title = "两步验证",
            summary = "短信验证码已发送至 $mfaPhone",
            onDismissRequest = {
                showMfaDialog.value = false
                mfaLogin = null
                mfaCode = ""
                mfaError = null
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
            ) {

                // 发送验证码按钮
                if (!mfaCodeSent) {
                    Button(
                        onClick = {
                            mfaSending = true
                            mfaError = null
                            scope.launch {
                                try {
                                    val login = mfaLogin ?: run { mfaError = "MFA 会话丢失"; return@launch }
                                    val ctx = login.mfaContext ?: run { mfaError = "MFA 上下文丢失"; return@launch }
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        ctx.sendVerifyCode()
                                    }
                                    mfaCodeSent = true
                                } catch (e: Exception) {
                                    mfaError = "发送失败: ${e.message}"
                                }
                                mfaSending = false
                            }
                        },
                        enabled = !mfaSending,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (mfaSending) {
                            CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (mfaSending) "发送中..." else "发送验证码")
                    }
                } else {
                    // 验证码输入
                    TextField(
                        value = mfaCode,
                        onValueChange = { mfaCode = it; mfaError = null },
                        label = "验证码",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    if (mfaError != null) {
                        Text(mfaError!!, color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.footnote1, modifier = Modifier.padding(start = 4.dp, top = 2.dp))
                    }
                }

                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(
                        text = "取消",
                        onClick = {
                            showMfaDialog.value = false
                            mfaLogin = null
                            mfaCode = ""
                            mfaError = null
                        },
                        modifier = Modifier.weight(1f)
                    )
                    if (mfaCodeSent) {
                        Button(
                            onClick = { finishMfaAndLogin() },
                            enabled = mfaCode.length >= 4 && !mfaVerifying,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (mfaVerifying) {
                                CircularProgressIndicator(size = 16.dp, strokeWidth = 2.dp)
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(if (mfaVerifying) "验证中..." else "验证并登录")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
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
                    TextButton(
                        text = "暂不开启",
                        onClick = {
                            credentialStore.srunSetupAsked = true
                            credentialStore.srunAutoLoginEnabled = false
                            showSrunSetupSheet.value = false
                        },
                        modifier = Modifier.weight(1f)
                    )
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

    // ── NSA 数据（已在 Phase2 自动登录中加载 或 从缓存恢复，此处仅做 fallback） ──
    LaunchedEffect(loginState.isLoggedIn) {
        if (loginState.isLoggedIn && !loginState.nsaDataLoaded && loginState.getSharedClient() != null) {
            // Phase2 可能还没跑完，等一下
            kotlinx.coroutines.delay(3000)
            if (loginState.nsaDataLoaded) return@LaunchedEffect
            // Phase2 未完成或失败 → fallback 尝试
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val client = loginState.getSharedClient() ?: return@withContext
                    val casRefresher: () -> Boolean = { loginState.jwxtLogin?.reAuthenticate() ?: false }
                    val nsaApi = com.xjtu.toolbox.nsa.NsaApi(client, casRefresher)
                    if (!nsaApi.ensureSession()) return@withContext
                    var details = emptyList<Pair<String, String>>()
                    kotlinx.coroutines.coroutineScope {
                        launch { loginState.nsaProfile = nsaApi.getProfile() }
                        launch { loginState.nsaPhotoBytes = nsaApi.getStudentPhoto() }
                        launch { details = nsaApi.getPersonalDetails() }
                    }
                    // 合并详情
                    if (details.isNotEmpty() && loginState.nsaProfile != null) {
                        loginState.nsaProfile = loginState.nsaProfile!!.copy(details = details)
                    }
                    loginState.nsaDataLoaded = true
                    // 持久化
                    loginState.nsaProfile?.let { p -> credentialStore.saveNsaProfile(p.toJson()) }
                    loginState.nsaPhotoBytes?.let { bytes -> credentialStore.saveNsaPhoto(bytes) }

                    // 如 NSA 详情为空 → JWT + YWTB fallback
                    if (loginState.nsaProfile != null && loginState.nsaProfile!!.details.isEmpty()) {
                        val fallbackDetails = mutableListOf<Pair<String, String>>()
                        loginState.attendanceLogin?.authToken?.let { jwt ->
                            try {
                                val parts = jwt.split(".")
                                if (parts.size >= 2) {
                                    val payload = android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
                                    val jwtJson = String(payload, Charsets.UTF_8).safeParseJsonObject()
                                    val student = jwtJson.getAsJsonObject("student")
                                    if (student != null) {
                                        student.get("sex")?.let { s ->
                                            val sex = try { s.asInt } catch (_: Exception) { s.asString.toIntOrNull() }
                                            if (sex == 1) fallbackDetails.add("性别" to "男") else if (sex == 2) fallbackDetails.add("性别" to "女")
                                        }
                                        student.get("grade")?.let { g ->
                                            val grade = try { g.asInt.toString() } catch (_: Exception) { g.asString }
                                            if (grade.isNotEmpty()) fallbackDetails.add("年级" to "${grade}级")
                                        }
                                        student.get("campusName")?.asString?.let { if (it.isNotEmpty()) fallbackDetails.add("校区" to it) }
                                        student.get("departmentName")?.asString?.let { if (it.isNotEmpty()) fallbackDetails.add("院系" to it) }
                                        student.get("cardId")?.asString?.let { if (it.isNotEmpty()) fallbackDetails.add("一卡通" to it) }
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                        loginState.ywtbUserInfo?.let { info ->
                            if (info.identityTypeName.isNotEmpty()) fallbackDetails.add("身份" to info.identityTypeName)
                        }
                        if (fallbackDetails.isNotEmpty()) {
                            loginState.nsaProfile = loginState.nsaProfile!!.copy(details = fallbackDetails)
                            credentialStore.saveNsaProfile(loginState.nsaProfile!!.toJson())
                        }
                    }

                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e
                } catch (_: Exception) { }
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
                    // Avatar — 优先显示 NSA 照片，否则首字母
                    Surface(
                        modifier = Modifier.size(72.dp),
                        shape = CircleShape,
                        color = MiuixTheme.colorScheme.primary
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            val photoBytes = loginState.nsaPhotoBytes
                            if (photoBytes != null && loginState.isLoggedIn) {
                                val bitmap = remember(photoBytes) {
                                    android.graphics.BitmapFactory.decodeByteArray(photoBytes, 0, photoBytes.size)
                                }
                                if (bitmap != null) {
                                    androidx.compose.foundation.Image(
                                        bitmap = bitmap.asImageBitmap(),
                                        contentDescription = "头像",
                                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                    )
                                } else {
                                    // 解码失败，显示首字母
                                    val initial = (loginState.nsaProfile?.name ?: loginState.ywtbUserInfo?.userName ?: loginState.activeUsername).take(1)
                                    Text(initial, color = MiuixTheme.colorScheme.onPrimary, style = MiuixTheme.textStyles.title2, fontWeight = FontWeight.Bold)
                                }
                            } else if (loginState.isLoggedIn) {
                                val initial = (loginState.nsaProfile?.name ?: loginState.ywtbUserInfo?.userName ?: loginState.activeUsername).take(1)
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
                                loginState.nsaProfile?.name ?: loginState.ywtbUserInfo?.userName ?: loginState.activeUsername,
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
                            // 学院 · 专业（优先 NSA，回退 YWTB）
                            val subtitle = loginState.nsaProfile?.let { p ->
                                if (p.college.isNotEmpty() && p.major.isNotEmpty()) "${p.college} · ${p.major}"
                                else p.college.ifEmpty { p.major }
                            } ?: loginState.ywtbUserInfo?.let { info ->
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

            // YWTB 用户信息加载（保留原逻辑）
            if (loginState.ywtbLogin != null && loginState.ywtbUserInfo == null) {
                LaunchedEffect(loginState.ywtbLogin) {
                    try {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            val api = com.xjtu.toolbox.ywtb.YwtbApi(loginState.ywtbLogin!!)
                            loginState.ywtbUserInfo = api.getUserInfo()
                        }
                    } catch (_: Exception) { }
                }
            }

            Column(Modifier.padding(horizontal = 20.dp)) {

                // 学期 / 教学周 信息（使用 JWXT，Phase1 即可用，比 YWTB 快 ~3s）
                if (loginState.jwxtLogin != null) {
                    var currentWeekText by remember { mutableStateOf<String?>(null) }
                    var termText by remember { mutableStateOf<String?>(null) }
                    var schoolYear by remember { mutableStateOf<String?>(null) }
                    var countdownText by remember { mutableStateOf<String?>(null) }
                    LaunchedEffect(loginState.jwxtLogin) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            try {
                                val api = com.xjtu.toolbox.schedule.ScheduleApi(loginState.jwxtLogin!!)
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

                // ━━ 个人详情卡片（默认折叠，点击展开） ━━
                val nsaDetails = loginState.nsaProfile?.details
                val isNsaLoading = loginState.isLoggedIn && loginState.nsaProfile == null
                val hasNsaDetails = !nsaDetails.isNullOrEmpty()
                if (hasNsaDetails || isNsaLoading) {
                    Spacer(Modifier.height(12.dp))
                    var detailsExpanded by remember { mutableStateOf(false) }
                    Card(
                        onClick = { if (hasNsaDetails) detailsExpanded = !detailsExpanded },
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 20.dp,
                        pressFeedbackType = PressFeedbackType.Sink,
                        colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(Modifier.padding(20.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.Badge, null, Modifier.size(18.dp), tint = MiuixTheme.colorScheme.primary)
                                    Spacer(Modifier.width(8.dp))
                                    Text("个人信息", style = MiuixTheme.textStyles.subtitle, fontWeight = FontWeight.Bold)
                                }
                                if (isNsaLoading) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(size = 14.dp, strokeWidth = 2.dp)
                                        Spacer(Modifier.width(6.dp))
                                        Text("加载中…", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.outline)
                                    }
                                } else if (hasNsaDetails) {
                                    Icon(
                                        if (detailsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = if (detailsExpanded) "收起" else "展开",
                                        Modifier.size(20.dp),
                                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    )
                                }
                            }
                            AnimatedVisibility(visible = detailsExpanded && hasNsaDetails) {
                                Column {
                                    Spacer(Modifier.height(12.dp))
                                    nsaDetails!!.forEachIndexed { idx, (label, value) ->
                                        if (idx > 0) {
                                            HorizontalDivider(
                                                Modifier.padding(vertical = 6.dp),
                                                color = MiuixTheme.colorScheme.outline.copy(alpha = 0.3f)
                                            )
                                        }
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                label,
                                                style = MiuixTheme.textStyles.body2,
                                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                                modifier = Modifier.widthIn(min = 56.dp)
                                            )
                                            Text(
                                                value,
                                                style = MiuixTheme.textStyles.body1,
                                                textAlign = TextAlign.End,
                                                modifier = Modifier.weight(1f).padding(start = 12.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ━━ 下载记录入口卡片 ━━
                var downloadStats by remember { mutableStateOf<com.xjtu.toolbox.classreplay.DownloadManager.DownloadStats?>(null) }
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val downloadManager = com.xjtu.toolbox.classreplay.DownloadManager.getInstance(context)
                        downloadStats = downloadManager.getDownloadStats()
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
            .padding(8.dp)
    ) {
        Surface(
            modifier = Modifier.size(56.dp),
            shape = RoundedCornerShape(16.dp),
            color = color.copy(alpha = 0.18f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = color, modifier = Modifier.size(28.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(label, style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun HomeServiceCard(
    icon: ImageVector, title: String, subtitle: String,
    iconColor: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier,
    hint: String? = null,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        cornerRadius = 16.dp,
        pressFeedbackType = PressFeedbackType.Sink,
        colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(iconColor.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = iconColor, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MiuixTheme.textStyles.subtitle, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(subtitle, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (hint != null) {
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(iconColor.copy(alpha = 0.10f))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(hint, style = MiuixTheme.textStyles.footnote1, color = iconColor, maxLines = 2)
                }
            }
        }
    }
}

/** 真瀑布流布局 — 可嵌入 verticalScroll，依内容高度自动错落到最短列 */
@Composable
private fun StaggeredFlow(
    columns: Int = 2,
    spacing: androidx.compose.ui.unit.Dp = 10.dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    androidx.compose.ui.layout.Layout(content = content, modifier = modifier) { measurables, constraints ->
        val spacingPx = spacing.roundToPx()
        val colWidth = ((constraints.maxWidth - spacingPx * (columns - 1)) / columns).coerceAtLeast(0)
        val itemConstraints = constraints.copy(minWidth = colWidth, maxWidth = colWidth)
        val colHeights = IntArray(columns)
        data class Pos(val p: androidx.compose.ui.layout.Placeable, val x: Int, val y: Int)
        val placements = measurables.map { m ->
            var target = 0
            for (i in 1 until columns) if (colHeights[i] < colHeights[target]) target = i
            val p = m.measure(itemConstraints)
            val x = target * (colWidth + spacingPx)
            val y = colHeights[target]
            colHeights[target] += p.height + spacingPx
            Pos(p, x, y)
        }
        val total = (colHeights.maxOrNull() ?: 0).coerceAtLeast(0)
        layout(constraints.maxWidth, total) {
            placements.forEach { it.p.place(it.x, it.y) }
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
                append("本应用通过 HTTPS 协议访问学校公开的各业务系统接口获取数据，所有网络请求均在您的设备上发起。您的账号凭据（用户名和密码）仅加密存储在本地设备中，不会上传至任何第三方服务器。")
                pushStyle(boldStyle); append("本应用不收集、不存储、不上载任何用户隐私信息至开发者或第三方。"); pop()
            }
        ),
        EulaSection(
            "三、免责声明",
            androidx.compose.ui.text.buildAnnotatedString {
                append("1. 本应用按「按原样」（AS IS）提供，开发者不对其准确性、完整性、可用性或适用性作任何明示或暗示的保证。\n2. 因使用本应用导致的任何直接或间接损失（包括但不限于数据丢失、账号异常、学业影响等），开发者不承担任何责任。\n3. 若学校系统接口变更导致功能异常，开发者将尽力修复但不保证时效。\n4. 本应用可能因学校政策调整而需要停止服务，届时将提前告知用户。")
            }
        ),
        EulaSection(
            "四、合规声明",
            androidx.compose.ui.text.buildAnnotatedString {
                append("1. 本应用仅供西安交通大学在校师生个人学习和生活使用，严禁用于任何商业用途。\n2. ")
                pushStyle(boldStyle); append("本应用不提供抢选、抢课、刷分等牟利功能。"); pop()
                append("\n3. ")
                pushStyle(boldStyle); append("本应用不接入支付、退款等金额交易功能。"); pop()
                append("\n4. 使用者应遵守学校各系统的使用规定和信息安全管理条例。\n5. 严禁利用本应用进行恶意请求、数据爬取、接口滥用等行为。违者应自行承担相应法律责任。")
            }
        ),
        EulaSection(
            "五、知识产权",
            androidx.compose.ui.text.buildAnnotatedString {
                append("本应用源代码基于 MIT 协议开源，感谢相关项目的启发。所访问的各业务系统之数据、接口及商标均归西安交通大学及相关权利方所有。")
            }
        ),
        EulaSection(
            "六、条款变更",
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
private fun AutoUpdateDialog(
    version: String,
    body: String,
    downloadUrl: String,
    releaseUrl: String,
    onDismiss: () -> Unit
) {
    val show = remember { mutableStateOf(true) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }

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
            // Release body（changelog）
            if (body.isNotBlank()) {
                val lines = body.split("\n").filter { it.isNotBlank() }
                lines.forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("##")) {
                        Text(
                            trimmed.removePrefix("##").trim(),
                            style = MiuixTheme.textStyles.subtitle,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    } else if (trimmed.startsWith("-") || trimmed.startsWith("*")) {
                        Row(Modifier.padding(vertical = 2.dp)) {
                            Text("•", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.outline)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                trimmed.removePrefix("-").removePrefix("*").trim(),
                                style = MiuixTheme.textStyles.body2,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    } else if (trimmed.isNotEmpty()) {
                        Text(trimmed, style = MiuixTheme.textStyles.body2)
                    }
                }
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
                            isDownloading = true
                            downloadProgress = 0f
                            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                try {
                                    val dlClient = okhttp3.OkHttpClient.Builder()
                                        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                                        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                                        .build()
                                    val dlReq = okhttp3.Request.Builder().url(downloadUrl).build()
                                    val dlResp = dlClient.newCall(dlReq).execute()
                                    val dlBody = dlResp.body ?: throw Exception("空响应")
                                    val contentLength = dlBody.contentLength()
                                    val apkFile = java.io.File(context.cacheDir, "update.apk")
                                    dlBody.byteStream().use { input ->
                                        apkFile.outputStream().use { output ->
                                            val buffer = ByteArray(8192)
                                            var downloaded = 0L
                                            var count: Int
                                            while (input.read(buffer).also { count = it } != -1) {
                                                output.write(buffer, 0, count)
                                                downloaded += count
                                                if (contentLength > 0) {
                                                    downloadProgress = downloaded.toFloat() / contentLength.toFloat()
                                                }
                                            }
                                        }
                                    }
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        isDownloading = false
                                        show.value = false
                                        onDismiss()
                                        val apkUri = androidx.core.content.FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.fileprovider",
                                            apkFile
                                        )
                                        val installIntent = Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(apkUri, "application/vnd.android.package-archive")
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        context.startActivity(installIntent)
                                    }
                                } catch (e: Exception) {
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        isDownloading = false
                                        android.widget.Toast.makeText(context, "下载失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Download, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("下载更新")
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
