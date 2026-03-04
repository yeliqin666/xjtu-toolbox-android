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
import top.yukonga.miuix.kmp.extra.SuperBottomSheet
import top.yukonga.miuix.kmp.extra.SuperDialog
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
import top.yukonga.miuix.kmp.basic.NavigationDisplayMode
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.rememberHazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
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
import com.xjtu.toolbox.util.CredentialStore
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_LAUNCH_ROUTE = "extra_launch_route"
    }

    /** 标记应用是否准备好（登录恢复完成后为 true），供 SplashScreen 决定何时消失 */
    var isAppReady = false
    private val launchRouteState = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        splash.setKeepOnScreenCondition { !isAppReady }
        super.onCreate(savedInstanceState)
        launchRouteState.value = intent?.getStringExtra(EXTRA_LAUNCH_ROUTE)
        enableEdgeToEdge()
        setContent {
            XJTUToolBoxTheme {
                AppNavigation(
                    initialRoute = launchRouteState.value,
                    onInitialRouteConsumed = { launchRouteState.value = null },
                    onReady = { isAppReady = true }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchRouteState.value = intent.getStringExtra(EXTRA_LAUNCH_ROUTE)
    }
}

// ── 路由 ──────────────────────────────────

object Routes {
    const val MAIN = "main"
    const val LOGIN = "login/{loginType}/{target}"
    const val EMPTY_ROOM = "empty_room"
    const val NOTIFICATION = "notification"
    const val ATTENDANCE = "attendance"
    const val SCHEDULE = "schedule"
    const val JUDGE = "judge"
    const val JWAPP_SCORE = "jwapp_score"
    const val YWTB = "ywtb"
    const val LIBRARY = "library"
    const val CAMPUS_CARD = "campus_card"
    const val SCORE_REPORT = "score_report"
    const val PAYMENT_CODE = "payment_code"
    const val TRANSCRIPT = "transcript"
    const val VENUE = "venue"
    const val CLASS_REPLAY = "class_replay"
    const val LMS = "lms"
    const val SCHOOL_COURSE = "school_course"
    const val VIDEO_PLAYER = "video_player/{activityId}"
    const val BROWSER = "browser?url={url}"

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
    ACADEMIC("教务", Icons.Filled.School, Icons.Outlined.School),
    TOOLS("工具", Icons.Filled.Build, Icons.Outlined.Build),
    PROFILE("我的", Icons.Filled.Person, Icons.Outlined.Person)
}

// ── 登录状态 ──────────────────────────────

class AppLoginState {
    var activeUsername by mutableStateOf("")
    var attendanceLogin by mutableStateOf<AttendanceLogin?>(null)
    var jwxtLogin by mutableStateOf<JwxtLogin?>(null)
    var jwappLogin by mutableStateOf<JwappLogin?>(null)
    var ywtbLogin by mutableStateOf<YwtbLogin?>(null)
    var libraryLogin by mutableStateOf<LibraryLogin?>(null)
    var campusCardLogin by mutableStateOf<CampusCardLogin?>(null)
    var dzpzLogin by mutableStateOf<com.xjtu.toolbox.auth.DzpzLogin?>(null)
    var venueLogin by mutableStateOf<com.xjtu.toolbox.auth.VenueLogin?>(null)
    var classLogin by mutableStateOf<com.xjtu.toolbox.classreplay.ClassLogin?>(null)
    var lmsLogin by mutableStateOf<com.xjtu.toolbox.lms.LmsLogin?>(null)

    // 持久化 CookieJar（由外部传入，整个 App 共享一个实例）
    var persistentCookieJar: com.xjtu.toolbox.util.PersistentCookieJar? = null

    // [B2] WebVPN 持久化 CookieJar（独立实例，校外冷启动可复用 VPN session）
    var vpnCookieJar: com.xjtu.toolbox.util.PersistentCookieJar? = null

    // SSO: 共享的 OkHttpClient（携带 CAS TGC cookie），实现一次登录、所有系统自动认证
    @Volatile private var sharedClient: okhttp3.OkHttpClient? = null

    // 并发保护：多个 autoLogin 并行时，保证 sharedClient 只初始化一次
    private val clientInitMutex = kotlinx.coroutines.sync.Mutex()

    // [CP] 全局共享连接池：所有子系统复用 TLS 连接，避免重复握手（~800ms→~50ms）
    private val sharedConnectionPool = okhttp3.ConnectionPool(5, 30, java.util.concurrent.TimeUnit.SECONDS)

    // WebVPN: 校外自动模式
    // vpnClient = sharedClient + WebVpnInterceptor（仅用于内部服务）
    @Volatile private var vpnClient: okhttp3.OkHttpClient? = null
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
            attendanceLogin, jwxtLogin, jwappLogin, ywtbLogin, libraryLogin, campusCardLogin, dzpzLogin, venueLogin, classLogin, lmsLogin
        ).size

    /** 是否为需要校内网络（WebVPN）的服务 */
    private fun isInternalService(type: LoginType): Boolean =
        type == LoginType.ATTENDANCE || type == LoginType.LIBRARY

    fun saveCredentials(username: String, password: String) {
        savedUsername = username
        savedPassword = password
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
            LoginType.JWXT -> jwxtLogin
            LoginType.JWAPP -> jwappLogin
            LoginType.YWTB -> ywtbLogin
            LoginType.LIBRARY -> libraryLogin
            LoginType.CAMPUS_CARD -> campusCardLogin
            LoginType.DZPZ -> dzpzLogin
            LoginType.VENUE -> venueLogin
            LoginType.CLASS -> classLogin
            LoginType.LMS -> lmsLogin
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
            // AttendanceLogin 已有 executeWithReAuth，不在此处检查
            // Cookie-based 系统（Jwxt/Library/CampusCard）依赖 PersistentCookieJar
        }
        return login
    }

    /** 供 LoginScreen 等外部组件读取 SSO 上下文 */
    fun getSharedClient(): okhttp3.OkHttpClient? = sharedClient
    fun getVisitorId(): String? = firstVisitorId
    fun getCachedRsaKey(): String? = cachedRsaKey

    fun cache(login: XJTULogin, username: String) {
        activeUsername = username
        // 更新 SSO 共享状态（手动登录后也能被后续 autoLogin 复用）
        if (sharedClient == null) sharedClient = login.client
        if (firstVisitorId == null) firstVisitorId = login.fpVisitorId
        if (cachedRsaKey == null) cachedRsaKey = login.getRsaPublicKey()
        when (login) {
            is AttendanceLogin -> attendanceLogin = login
            is JwxtLogin -> jwxtLogin = login
            is JwappLogin -> jwappLogin = login
            is YwtbLogin -> ywtbLogin = login
            is LibraryLogin -> libraryLogin = login
            is CampusCardLogin -> campusCardLogin = login
            is com.xjtu.toolbox.auth.DzpzLogin -> dzpzLogin = login
            is com.xjtu.toolbox.auth.VenueLogin -> venueLogin = login
            is com.xjtu.toolbox.classreplay.ClassLogin -> classLogin = login
            is com.xjtu.toolbox.lms.LmsLogin -> lmsLogin = login
        }
        // [F1] 立即持久化关键状态（防止进程被杀后丢失）
        credentialStoreRef?.let { store ->
            if (hasCredentials) store.save(savedUsername, savedPassword)
            firstVisitorId?.let { store.saveFpVisitorId(it) }
            cachedRsaKey?.let { store.saveRsaPublicKey(it) }
        }
    }

    /**
     * [N1] 检测是否在校园网内（带 10 分钟缓存，避免重复探测）
     */
    suspend fun detectCampusNetwork(): Boolean {
        // 缓存有效期内直接返回
        val cached = isOnCampus
        if (cached != null && System.currentTimeMillis() - campusDetectTime < CAMPUS_CACHE_MS) {
            android.util.Log.d("Campus", "detectCampus: using cached result=$cached (age=${(System.currentTimeMillis() - campusDetectTime) / 1000}s)")
            return cached
        }
        return try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val testClient = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                    .followRedirects(false)
                    .build()
                val request = okhttp3.Request.Builder()
                    .url("http://bkkq.xjtu.edu.cn/")
                    .head()
                    .build()
                val response = testClient.newCall(request).execute()
                response.close()
                android.util.Log.d("Campus", "detectCampus: connected to bkkq → ON CAMPUS")
                true // 能连上，说明在校内
            }
        } catch (e: Exception) {
            android.util.Log.d("Campus", "detectCampus: cannot reach bkkq → OFF CAMPUS (${e.javaClass.simpleName})")
            false // 连不上，说明在校外
        }.also {
            campusDetectTime = System.currentTimeMillis()
        }
    }

    /**
     * WebVPN 登录（校外自动：认证 WebVPN 自身，获取代理 cookie）
     * 与 Python 版 webvpn_login 保持一致：使用全新 client（不复用 sharedClient）
     * Python 版在登录前 cookies.clear()，这里通过创建新 client 实现等价效果
     * 成功后创建 vpnClient（基于新 client + WebVPN 拦截器）
     */
    suspend fun loginWebVpn(): Boolean {
        if (!hasCredentials) { android.util.Log.w("WebVPN", "No credentials"); return false }
        if (webVpnLoggedIn && vpnClient != null) { android.util.Log.d("WebVPN", "Already logged in"); return true }
        android.util.Log.d("WebVPN", "Starting WebVPN login, firstVisitorId=$firstVisitorId")
        return try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                // [B2] 使用持久化 vpnCookieJar，校外冷启动可复用 VPN session
                // 如果 vpnCookieJar 有有效 session，CAS SSO 会直接跳过登录
                android.util.Log.d("WebVPN", "Creating XJTULogin for WebVPN URL")
                val login = XJTULogin(
                    com.xjtu.toolbox.util.WebVpnUtil.WEBVPN_LOGIN_URL,
                    existingClient = null,
                    visitorId = firstVisitorId,  // 复用 visitorId 减少 MFA
                    cookieJar = vpnCookieJar  // [B2] 持久化 WebVPN cookies
                )
                android.util.Log.d("WebVPN", "XJTULogin created, hasLogin=${login.hasLogin}, calling login()...")
                val result = login.login(savedUsername, savedPassword)
                android.util.Log.d("WebVPN", "login() returned: state=${result.state}, msg=${result.message}")
                if (result.state == LoginState.SUCCESS) {
                    // 基于 WebVPN 登录后的客户端（含 WebVPN cookie），追加拦截器
                    // 关键：使用 addInterceptor（Application 级），不是 addNetworkInterceptor
                    // Application Interceptor 在 BridgeInterceptor（Cookie 添加）之前执行
                    // 这样 URL 先被转换为 webvpn.xjtu.edu.cn 域名
                    // 然后 BridgeInterceptor 才查找该域名的 cookie → 正确发送 WebVPN session
                    vpnClient = login.client.newBuilder()
                        .addInterceptor(com.xjtu.toolbox.util.WebVpnInterceptor())
                        .build()
                    webVpnLoggedIn = true
                    android.util.Log.d("WebVPN", "WebVPN login SUCCESS, vpnClient created (Application Interceptor)")
                    true
                } else if (result.state == LoginState.REQUIRE_ACCOUNT_CHOICE) {
                    // 处理多身份账号选择
                    android.util.Log.d("WebVPN", "Account choice required, selecting UNDERGRADUATE")
                    val finalResult = login.login(accountType = XJTULogin.AccountType.UNDERGRADUATE)
                    android.util.Log.d("WebVPN", "Account choice result: state=${finalResult.state}")
                    if (finalResult.state == LoginState.SUCCESS) {
                        vpnClient = login.client.newBuilder()
                            .addInterceptor(com.xjtu.toolbox.util.WebVpnInterceptor())
                            .build()
                        webVpnLoggedIn = true
                        true
                    } else false
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
    suspend fun autoLogin(type: LoginType): XJTULogin? {
        if (!hasCredentials) return null
        getCached(type)?.let { return it }

        // [G1] Token 过期的系统：先在 IO 线程尝试轻量 reAuth（SSO/casAuthenticate）
        val existingLogin = when (type) {
            LoginType.JWAPP -> jwappLogin
            LoginType.YWTB -> ywtbLogin
            LoginType.ATTENDANCE -> attendanceLogin
            LoginType.JWXT -> jwxtLogin
            LoginType.DZPZ -> dzpzLogin
            LoginType.VENUE -> venueLogin
            LoginType.CLASS -> classLogin
            LoginType.LMS -> lmsLogin
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
            // reAuth 失败，清空缓存准备 full login
            android.util.Log.d("AppLoginState", "autoLogin($type): reAuth failed, clearing cache for full login")
            when (type) {
                LoginType.JWAPP -> jwappLogin = null
                LoginType.YWTB -> ywtbLogin = null
                LoginType.ATTENDANCE -> attendanceLogin = null
                LoginType.JWXT -> jwxtLogin = null
                LoginType.DZPZ -> dzpzLogin = null
                LoginType.VENUE -> venueLogin = null
                LoginType.CLASS -> classLogin = null
                LoginType.LMS -> lmsLogin = null
                else -> {}
            }
        }

        // 内部服务需要 VPN：自动检测网络并建立 VPN
        if (isInternalService(type)) {
            if (isOnCampus == null) isOnCampus = detectCampusNetwork()
            if (isOnCampus == false && vpnClient == null) loginWebVpn()
        }

        val useWebVpn = isInternalService(type) && isOnCampus == false
        val clientForLogin = if (useWebVpn) {
            vpnClient ?: return null
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
                        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                        .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                        .also { sharedClient = it }
                }
            }
        }

        val visitorId = sharedClient?.let { firstVisitorId } ?: firstVisitorId

        // [E1] 带重试的登录（区分可重试 IOException 和不可重试的认证失败）
        var lastException: Exception? = null
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            repeat(3) { attempt ->
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
                        val finalResult = login.login(accountType = XJTULogin.AccountType.UNDERGRADUATE)
                        if (finalResult.state == LoginState.SUCCESS) {
                            if (sharedClient == null) sharedClient = login.client
                            if (firstVisitorId == null) firstVisitorId = login.fpVisitorId
                            if (cachedRsaKey == null) cachedRsaKey = login.getRsaPublicKey()
                            cache(login, savedUsername)
                            return@withContext login
                        }
                    }
                    // 认证失败（密码错误等）：不可重试
                    android.util.Log.w("AppLoginState", "autoLogin($type): auth failed state=${result.state} msg=${result.message}")
                    return@withContext null
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
                    android.util.Log.e("AppLoginState", "autoLogin($type): non-retryable exception", e)
                    // RSA 公钥解析失败 → 清除缓存以便下次登录可刷新
                    if (e.message?.contains("RSA", ignoreCase = true) == true ||
                        e.message?.contains("公钥", ignoreCase = true) == true ||
                        e.cause is java.security.spec.InvalidKeySpecException) {
                        cachedRsaKey = null
                        android.util.Log.w("AppLoginState", "autoLogin: RSA 公钥失败，已清除缓存")
                        // RSA 失败可重试（新实例会重新获取公钥）
                        lastException = e
                        if (attempt < 2) {
                            kotlinx.coroutines.delay(if (attempt == 0) 1000L else 3000L)
                        } else {
                            return@withContext null
                        }
                    } else {
                        return@withContext null
                    }
                }
            }
            android.util.Log.w("AppLoginState", "autoLogin($type): all 3 attempts failed: ${lastException?.message}")
            // [FALLBACK] 校园网直连全部超时 → 强制切换 WebVPN 重试一次
            if (isInternalService(type) && isOnCampus == true &&
                (lastException is java.net.SocketTimeoutException ||
                 lastException?.message?.contains("timeout", ignoreCase = true) == true)) {
                android.util.Log.w("AppLoginState", "autoLogin($type): campus direct all timed out, fallback to WebVPN")
                isOnCampus = false
                campusDetectTime = 0L // 清除缓存，下次重新检测
                if (vpnClient == null) loginWebVpn()
                val vpnFallbackClient = vpnClient
                if (vpnFallbackClient != null) {
                    try {
                        val login = type.createLogin(vpnFallbackClient, visitorId, true, cachedRsaKey)
                        val result = login.login(savedUsername, savedPassword)
                        if (result.state == LoginState.SUCCESS) {
                            if (firstVisitorId == null) firstVisitorId = login.fpVisitorId
                            if (cachedRsaKey == null) cachedRsaKey = login.getRsaPublicKey()
                            cache(login, savedUsername)
                            android.util.Log.d("AppLoginState", "autoLogin($type): WebVPN fallback SUCCESS")
                            return@withContext login
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
                            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                            .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                            .build()
                    }
                }
                val login = LoginType.JWXT.createLogin(sharedClient, sharedClient?.let { firstVisitorId } ?: firstVisitorId, false, cachedRsaKey)
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
        activeUsername = ""
        savedUsername = ""; savedPassword = ""
        attendanceLogin = null; jwxtLogin = null; jwappLogin = null
        ywtbLogin = null; libraryLogin = null; campusCardLogin = null
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

// ── [VM] ViewModel：状态不因 Configuration Change（旋转/深色切换）而丢失 ──

class AppLoginStateViewModel(application: android.app.Application) : androidx.lifecycle.AndroidViewModel(application) {
    val loginState = AppLoginState()
    val credentialStore = CredentialStore(application)
    val persistentCookieJar = com.xjtu.toolbox.util.PersistentCookieJar(application)
    val vpnCookieJar = com.xjtu.toolbox.util.PersistentCookieJar(application, "xjtu_vpn_cookies")

    init {
        // 注入持久化组件（无需 LaunchedEffect，ViewModel 创建时即完成）
        loginState.persistentCookieJar = persistentCookieJar
        loginState.vpnCookieJar = vpnCookieJar
        // 恢复凭据（同步执行，确保 Compose 首帧读取到正确状态）
        loginState.restoreCredentials(credentialStore)
    }
}

// ── 主导航 ────────────────────────────────

@Composable
fun AppNavigation(
    initialRoute: String? = null,
    onInitialRouteConsumed: () -> Unit = {},
    onReady: () -> Unit = {}
) {
    val navController = rememberNavController()
    // [VM] ViewModel 保证状态跨 Configuration Change 存活
    val viewModel: AppLoginStateViewModel = viewModel()
    val loginState = viewModel.loginState
    val credentialStore = viewModel.credentialStore
    val context = LocalContext.current

    LaunchedEffect(initialRoute) {
        val route = initialRoute
        if (route.isNullOrBlank() || route == Routes.MAIN) return@LaunchedEffect

        if (route == Routes.SCHEDULE) {
            val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val isOnline = cm?.activeNetwork != null &&
                    cm.getNetworkCapabilities(cm.activeNetwork)
                        ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

            if (loginState.jwxtLogin != null) {
                navController.navigate(route) { launchSingleTop = true }
            } else if (!isOnline) {
                navController.navigate(route) { launchSingleTop = true }
            } else if (loginState.hasCredentials) {
                val loginResult = kotlinx.coroutines.withTimeoutOrNull(15_000L) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        loginState.autoLogin(LoginType.JWXT)
                    }
                }
                if (loginResult != null || loginState.jwxtLogin != null) {
                    navController.navigate(route) { launchSingleTop = true }
                } else {
                    navController.navigate(Routes.login(LoginType.JWXT, route)) { launchSingleTop = true }
                }
            } else {
                navController.navigate(Routes.login(LoginType.JWXT, route)) { launchSingleTop = true }
            }
        } else {
            navController.navigate(route) { launchSingleTop = true }
        }
        onInitialRouteConsumed()
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
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && loginState.isLoggedIn) {
                val now = System.currentTimeMillis()
                if (now - lastResumeRefresh.longValue < 30_000L) return@LifecycleEventObserver // 30s 节流
                lastResumeRefresh.longValue = now
                lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
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
                    } catch (e: Exception) {
                        android.util.Log.w("Lifecycle", "ON_RESUME: token refresh failed: ${e.message}")
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // [I1] 网络变化监听：WiFi/移动数据切换时重新检测校内/校外，自动切换
    val networkScope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        val connectivityManager = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        var networkCheckJob: kotlinx.coroutines.Job? = null  // 防抖：取消前一次检测
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                // 网络可用（WiFi/移动数据连接）→ 防抖 1s 后重新检测校内外
                if (loginState.isLoggedIn) {
                    networkCheckJob?.cancel()
                    networkCheckJob = networkScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        kotlinx.coroutines.delay(1000L)  // 防抖 1s，避免频繁网络切换风暴
                        try {
                            val oldOnCampus = loginState.isOnCampus
                            // 强制刷新（清除缓存）
                            loginState.isOnCampus = null
                            val newOnCampus = loginState.detectCampusNetwork()
                            loginState.isOnCampus = newOnCampus
                            if (oldOnCampus != newOnCampus) {
                                android.util.Log.d("Network", "Campus status changed: $oldOnCampus → $newOnCampus")
                                if (newOnCampus == true) {
                                    // 切到校内：VPN 不再需要，可清除 vpnClient
                                    android.util.Log.d("Network", "Switched to campus, VPN no longer needed")
                                } else if (newOnCampus == false && oldOnCampus == true) {
                                    // 切到校外：需要重新建立 VPN
                                    android.util.Log.d("Network", "Switched off campus, re-login WebVPN...")
                                    loginState.loginWebVpn()
                                }
                            }
                            // 网络变化后 proactive 刷新 token-based 系统
                            loginState.jwappLogin?.let { if (!it.isTokenValid()) it.reAuthenticate() }
                            loginState.ywtbLogin?.let { if (!it.isTokenValid()) it.reAuthenticate() }
                        } catch (e: Exception) {
                            android.util.Log.w("Network", "Network callback error: ${e.message}")
                        }
                    }
                }
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
        onReady()

        // 有凭据且尚未建立任何登录会话 → 启动后台恢复
        // 注意：isLoggedIn 可能因 [OFF-1] 已为 true（仅设了 username），但实际登录实例为 0
        if (loginState.hasCredentials && loginState.loginCount == 0) {
            isRestoring = true
            // Phase 1: JWXT + 网络检测（阻塞，完成后隐藏 banner）
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val startTime = System.currentTimeMillis()
                    android.util.Log.d("Restore", "开始恢复登录（并行模式）...")
                    restoreStep = "正在认证..."
                    kotlinx.coroutines.coroutineScope {
                        val jwxtDeferred = async(kotlinx.coroutines.Dispatchers.IO) {
                            loginState.autoLogin(LoginType.JWXT)
                        }
                        val networkDeferred = async(kotlinx.coroutines.Dispatchers.IO) {
                            loginState.detectCampusNetwork()
                        }
                        val jwxt = jwxtDeferred.await()
                        loginState.isOnCampus = networkDeferred.await()
                        android.util.Log.d("Restore", "Phase1 ${System.currentTimeMillis() - startTime}ms: JWXT=${jwxt != null}, campus=${loginState.isOnCampus}")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("Restore", "Phase1 恢复失败", e)
                }
            }
            isRestoring = false  // Phase1 完成，立即隐藏 banner

            // Phase 2+3: VPN + 所有子系统 + JWT 预热（全后台，不阻塞 UI）
            restoreScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val startTime = System.currentTimeMillis()
                    kotlinx.coroutines.coroutineScope {
                        // VPN（校外必须先完成才能登录 ATTENDANCE/LIBRARY）
                        val vpnDeferred = if (loginState.isOnCampus == false) {
                            async(kotlinx.coroutines.Dispatchers.IO) { loginState.loginWebVpn() }
                        } else null

                        // 不需要VPN的子系统 + 付款码预热，立即并行启动
                        launch { loginState.autoLogin(LoginType.JWAPP) }
                        launch { loginState.autoLogin(LoginType.YWTB) }
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
                                loginState.getSharedClient()?.let { client ->
                                    android.util.Log.d("Restore", "预热付款码 JWT...")
                                    com.xjtu.toolbox.pay.PaymentCodeApi(client).authenticate()
                                    android.util.Log.d("Restore", "付款码 JWT 预热完成")
                                }
                            } catch (e: Exception) {
                                android.util.Log.w("Restore", "付款码 JWT 预热失败: ${e.message}")
                            }
                        }

                        // 等VPN完成后启动需要VPN的子系统
                        if (vpnDeferred != null) {
                            val vpnOk = vpnDeferred.await()
                            android.util.Log.d("Restore", "VPN ${System.currentTimeMillis() - startTime}ms: ok=$vpnOk")
                        }
                        launch { loginState.autoLogin(LoginType.ATTENDANCE) }
                        launch { loginState.autoLogin(LoginType.LIBRARY) }
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

    // ── v2.0 更新公告弹窗（一次性） ──
    val showUpdateNotice = remember { mutableStateOf(!credentialStore.isUpdateNoticeSeen(BuildConfig.VERSION_NAME)) }
    if (showUpdateNotice.value) {
        UpdateNoticeDialog(show = showUpdateNotice, onDismiss = {
            credentialStore.markUpdateNoticeSeen(BuildConfig.VERSION_NAME)
            showUpdateNotice.value = false
        })
    }

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
            MainScreen(navController = navController, loginState = loginState, credentialStore = credentialStore, isRestoring = isRestoring, restoreStep = restoreStep)
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
                    navController.navigate(target) { launchSingleTop = true }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.EMPTY_ROOM) {
            EmptyRoomScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.NOTIFICATION) {
            NotificationScreen(onBack = { navController.popBackStack() }, onNavigate = { navController.navigate(it) { launchSingleTop = true } })
        }
        composable(Routes.ATTENDANCE) {
            loginState.attendanceLogin?.let { AttendanceScreen(login = it, onBack = { navController.popBackStack() }) } ?: LaunchedEffect(Unit) { navController.popBackStack() }
        }
        composable(Routes.SCHEDULE) {
            ScheduleScreen(login = loginState.jwxtLogin, studentId = loginState.activeUsername, onBack = { navController.popBackStack() })
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
        dialog(
            Routes.PAYMENT_CODE,
            dialogProperties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            val client = loginState.getSharedClient()
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
                com.xjtu.toolbox.classreplay.ClassScreen(
                    login = classLogin,
                    onBack = { navController.popBackStack() },
                    onPlayReplay = { login, activityId ->
                        navController.navigate(Routes.videoPlayer(activityId))
                    }
                )
            } ?: LaunchedEffect(Unit) { navController.popBackStack() }
        }
        composable(Routes.LMS) {
            loginState.lmsLogin?.let { lmsLogin ->
                com.xjtu.toolbox.lms.LmsScreen(
                    login = lmsLogin,
                    onBack = { navController.popBackStack() }
                )
            } ?: LaunchedEffect(Unit) { navController.popBackStack() }
        }
        composable(Routes.SCHOOL_COURSE) {
            com.xjtu.toolbox.schedule.SchoolCourseScreen(
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
        composable(
            Routes.BROWSER,
            arguments = listOf(navArgument("url") { type = NavType.StringType; defaultValue = "" })
        ) { backStackEntry ->
            val url = try { java.net.URLDecoder.decode(backStackEntry.arguments?.getString("url") ?: "", "UTF-8") } catch (_: Exception) { "" }
            com.xjtu.toolbox.browser.BrowserScreen(
                initialUrl = url,
                login = loginState.jwxtLogin,
                onBack = { navController.popBackStack() }
            )
        }
    }
}

// ── 主屏幕（底部导航栏）──────────────────

@Composable
private fun MainScreen(navController: NavHostController, loginState: AppLoginState, credentialStore: CredentialStore, isRestoring: Boolean = false, restoreStep: String = "") {
    var selectedTabOrdinal by rememberSaveable { mutableIntStateOf(0) }
    val selectedTab = BottomTab.entries[selectedTabOrdinal.coerceIn(0, BottomTab.entries.size - 1)]
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var lastBackPressTime by remember { mutableLongStateOf(0L) }
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

    fun navigateWithLogin(target: String, type: LoginType) {
        // [OFF-2] 快速网络检测（ConnectivityManager，瞬时，不阻塞）
        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        val isOnline = cm?.activeNetwork != null && cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

        // 离线可用的路由（有本地缓存支持）
        val offlineCapableRoutes = setOf(Routes.SCHEDULE, Routes.JWAPP_SCORE)

        // ── 断网处理（优先于所有登录检查）──
        if (!isOnline) {
            if (target in offlineCapableRoutes) {
                navController.navigate(target) { launchSingleTop = true }
                scope.launch { snackbarHostState.showSnackbar("当前无网络，进入离线模式", duration = SnackbarDuration.Short) }
            } else {
                scope.launch { snackbarHostState.showSnackbar("该功能需要联网使用，请检查网络连接", duration = SnackbarDuration.Short) }
            }
            return
        }

        // ── 在线：有缓存 login → 直接进入 ──
        if (loginState.getCached(type) != null) {
            navController.navigate(target) { launchSingleTop = true }
        } else if (loginState.hasCredentials) {
            // 有保存的凭据，尝试自动登录
            showAutoLoginSheet.value = true
            autoLoginMessage = "正在自动登录${type.label}..."
            autoLoginJob?.cancel() // 取消旧的登录任务，避免竞态
            autoLoginJob = scope.launch {
                try {
                    val result = kotlinx.coroutines.withTimeoutOrNull(15_000L) {
                        loginState.autoLogin(type)
                    }
                    showAutoLoginSheet.value = false
                    autoLoginJob = null
                    if (result != null) {
                        navController.navigate(target) { launchSingleTop = true }
                    } else {
                        // 登录超时 → 离线可用路由降级，其余提示
                        if (target in offlineCapableRoutes) {
                            navController.navigate(target) { launchSingleTop = true }
                            scope.launch { snackbarHostState.showSnackbar("登录超时，进入离线模式", duration = SnackbarDuration.Short) }
                        } else {
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    message = "${type.label}登录超时，请检查网络后重试",
                                    duration = SnackbarDuration.Short
                                )
                            }
                            selectedTabOrdinal = BottomTab.PROFILE.ordinal
                        }
                    }
                } catch (e: Exception) {
                    showAutoLoginSheet.value = false
                    autoLoginJob = null
                    // 离线可用路由降级
                    if (target in offlineCapableRoutes) {
                        navController.navigate(target) { launchSingleTop = true }
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

    // ── 各 Tab 独立的滚动折叠状态 ──
    val homeScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val academicScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val toolsScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val profileScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

    // ── Haze 高斯模糊状态 ──
    val hazeState = rememberHazeState()
    @OptIn(ExperimentalHazeMaterialsApi::class)
    val hazeStyle = HazeMaterials.regular(MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))

    // HOME 大标题
    val homeGreeting = if (loginState.isLoggedIn) {
        val name = loginState.nsaProfile?.name ?: loginState.ywtbUserInfo?.userName ?: loginState.cachedNickname
        if (!name.isNullOrBlank()) "你好, $name" else "你好"
    } else "岱宗盒子"

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = when (selectedTab) {
                    BottomTab.HOME -> "岱宗盒子"
                    BottomTab.ACADEMIC -> "教务服务"
                    BottomTab.TOOLS -> "实用工具"
                    BottomTab.PROFILE -> "我的"
                },
                largeTitle = when (selectedTab) {
                    BottomTab.HOME -> homeGreeting
                    BottomTab.ACADEMIC -> "教务服务"
                    BottomTab.TOOLS -> "实用工具"
                    BottomTab.PROFILE -> "我的"
                },
                scrollBehavior = when (selectedTab) {
                    BottomTab.HOME -> homeScrollBehavior
                    BottomTab.ACADEMIC -> academicScrollBehavior
                    BottomTab.TOOLS -> toolsScrollBehavior
                    BottomTab.PROFILE -> profileScrollBehavior
                }
            )
        },
        bottomBar = {},
        floatingToolbar = {
            @OptIn(ExperimentalHazeMaterialsApi::class)
            FloatingNavigationBar(
                color = androidx.compose.ui.graphics.Color.Transparent,
                modifier = Modifier.hazeEffect(state = hazeState, style = hazeStyle),
                mode = NavigationDisplayMode.IconOnly
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
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).hazeSource(state = hazeState)) {
            // 需要联网的无登录路由（空闲教室、通知公告等纯网络功能）
            val networkRequiredRoutes = setOf(Routes.EMPTY_ROOM, Routes.NOTIFICATION)
            val onNavigateWithNetCheck: (String) -> Unit = { route ->
                if (route in networkRequiredRoutes) {
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
            Box(Modifier.fillMaxSize()) {
                BottomTab.entries.forEach { tab ->
                    key(tab) {
                        if (tab in composedTabs) {
                            val isActive = selectedTab == tab
                            val tabAlpha by animateFloatAsState(
                                targetValue = if (isActive) 1f else 0f,
                                animationSpec = tween(if (isActive) 220 else 150),
                                label = "tabAlpha"
                            )
                            Box(
                                Modifier.fillMaxSize()
                                    .zIndex(if (isActive) 1f else 0f)
                                    .graphicsLayer { alpha = tabAlpha }
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
                                    BottomTab.HOME -> HomeTab(loginState, isRestoring = isRestoring, onNavigate = onNavigateWithNetCheck, onNavigateWithLogin = ::navigateWithLogin, onNavigateToProfile = { selectedTabOrdinal = BottomTab.PROFILE.ordinal }, scrollBehavior = homeScrollBehavior)
                                    BottomTab.ACADEMIC -> AcademicTab(loginState, ::navigateWithLogin, scrollBehavior = academicScrollBehavior)
                                    BottomTab.TOOLS -> ToolsTab(loginState, ::navigateWithLogin, onNavigateWithNetCheck, scrollBehavior = toolsScrollBehavior)
                                    BottomTab.PROFILE -> ProfileTab(loginState, ::navigateWithLogin, credentialStore, scrollBehavior = profileScrollBehavior)
                                }
                            }
                        }
                    }
                }
            }

            // 登录恢复非阻塞提示条（底部，不遮挡欢迎卡片）
            AnimatedVisibility(
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

            // 自动登录 SuperBottomSheet（可取消，15s超时）
            BackHandler(enabled = showAutoLoginSheet.value) {
                autoLoginJob?.cancel()
                showAutoLoginSheet.value = false
                autoLoginJob = null
            }
            SuperBottomSheet(
                show = showAutoLoginSheet,
                title = "自动登录",
                onDismissRequest = {
                    autoLoginJob?.cancel()
                    showAutoLoginSheet.value = false
                    autoLoginJob = null
                }
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 30.dp)
                        .navigationBarsPadding(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        autoLoginMessage,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        textAlign = TextAlign.Center
                    )
                    CircularProgressIndicator()
                    Button(
                        onClick = {
                            autoLoginJob?.cancel()
                            showAutoLoginSheet.value = false
                            autoLoginJob = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("取消") }
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
    scrollBehavior: ScrollBehavior? = null
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
                Spacer(Modifier.height(2.dp))
                Text(
                    when {
                        loginState.loginCount > 0 -> "已连接 ${loginState.loginCount} 个系统"
                        isRestoring -> "正在连接..."
                        else -> "离线模式"
                    },
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                HomeQuickAction(Icons.Default.CreditCard, "校园卡", colorGreen) {
                    onNavigateWithLogin(Routes.CAMPUS_CARD, LoginType.CAMPUS_CARD)
                }
                HomeQuickAction(Icons.Default.CalendarMonth, "课表", colorIndigo) {
                    onNavigateWithLogin(Routes.SCHEDULE, LoginType.JWXT)
                }
                HomeQuickAction(Icons.Default.QrCode, "付款码", colorTeal) {
                    onNavigateWithLogin(Routes.PAYMENT_CODE, LoginType.JWXT)
                }
                HomeQuickAction(Icons.Default.Notifications, "通知", colorOrange) {
                    onNavigate(Routes.NOTIFICATION)
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── Zone B+: 智能卡片 ──
        run {
            val context = LocalContext.current
            val isSummer = com.xjtu.toolbox.util.XjtuTime.isSummerTime()
            // 今日课表数据（从 DataCache 离线读取）
            var todayCoursesState by remember { mutableStateOf<List<CourseCardInfo>?>(null) }
            // 缓存余额数据（从 SharedPreferences 读取）
            val cardPrefs = remember { context.getSharedPreferences("campus_card", 0) }
            var cachedBalance by remember { mutableStateOf(cardPrefs.getFloat("card_balance_cache", -1f)) }
            val cachedName = remember { cardPrefs.getString("card_name_cache", "") ?: "" }
            val cachedRecentTx = remember {
                val json = cardPrefs.getString("card_recent_tx_cache", null)
                if (json != null) runCatching {
                    com.google.gson.Gson().fromJson(json, Array<com.xjtu.toolbox.card.Transaction>::class.java)?.toList()
                }.getOrNull() ?: emptyList()
                else emptyList()
            }
            var isRefreshingCard by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val dataCache = com.xjtu.toolbox.util.DataCache(context)
                        val gson = com.google.gson.Gson()
                        // 获取学期列表（按最新的来）
                        val termListJson = dataCache.get("schedule_term_list", Long.MAX_VALUE)
                        val termList = if (termListJson != null) {
                            gson.fromJson(termListJson, Array<String>::class.java)?.toList() ?: emptyList()
                        } else emptyList<String>()
                        val termCode = termList.firstOrNull() ?: return@withContext
                        // 获取 API 课程
                        val coursesJson = dataCache.get("schedule_$termCode", Long.MAX_VALUE)
                        val apiCourses = if (coursesJson != null) {
                            gson.fromJson(coursesJson, Array<com.xjtu.toolbox.schedule.CourseItem>::class.java)?.toList() ?: emptyList()
                        } else emptyList()
                        // 获取自定义课程（Room DB）
                        val customCourses = try {
                            com.xjtu.toolbox.util.AppDatabase.getInstance(context)
                                .customCourseDao().getByTerm(termCode)
                                .map { it.toCourseItem() }
                        } catch (_: Exception) { emptyList() }
                        val courses = apiCourses + customCourses
                        // 获取学期开始日期
                        val startDateJson = dataCache.get("start_date_$termCode", Long.MAX_VALUE)
                        val startDateStr = if (startDateJson != null) gson.fromJson(startDateJson, String::class.java) else null
                        val startDate = if (!startDateStr.isNullOrBlank()) runCatching { java.time.LocalDate.parse(startDateStr) }.getOrNull() else null
                        if (startDate == null) {
                            // 即使没有开始日期，也不能直接 return — 自定义课程可能也需要展示
                            todayCoursesState = emptyList()
                            return@withContext
                        }
                        val today = java.time.LocalDate.now()
                        val daysBetween = java.time.temporal.ChronoUnit.DAYS.between(startDate, today)
                        val currentWeek = ((daysBetween / 7) + 1).toInt()
                        val todayDow = today.dayOfWeek.value  // 1=Mon ... 7=Sun
                        val todayCourses = courses
                            .filter { it.dayOfWeek == todayDow && it.isInWeek(currentWeek) }
                            .sortedBy { it.startSection }
                            .map { CourseCardInfo(it.courseName, it.location, it.startSection, it.endSection) }
                        todayCoursesState = todayCourses
                    } catch (_: Exception) { todayCoursesState = emptyList() }
                }
            }

            Column(Modifier.padding(horizontal = 16.dp)) {
                // ═══ 今日课表智能卡片 ═══
                val todayCourses = todayCoursesState
                if (todayCourses != null) {
                    val now = java.time.LocalTime.now()
                    // 判断课程状态
                    val currentCourse = todayCourses.firstOrNull { course ->
                        val startTime = com.xjtu.toolbox.util.XjtuTime.getClassTime(course.startSection, isSummer)?.start
                        val endTime = com.xjtu.toolbox.util.XjtuTime.getClassTime(course.endSection, isSummer)?.end
                        startTime != null && endTime != null && now >= startTime && now <= endTime
                    }
                    val nextCourse = todayCourses.firstOrNull { course ->
                        val startTime = com.xjtu.toolbox.util.XjtuTime.getClassTime(course.startSection, isSummer)?.start
                        startTime != null && now < startTime
                    }
                    val allDone = todayCourses.isNotEmpty() && currentCourse == null && nextCourse == null

                    // 智能标题 + 颜色
                    val (cardTitle, statusHint, accentColor) = when {
                        todayCourses.isEmpty() -> Triple("今日课程", "今天没有课，好好休息 🎉", MiuixTheme.colorScheme.primary)
                        currentCourse != null -> {
                            val endTime = com.xjtu.toolbox.util.XjtuTime.getClassTime(currentCourse.endSection, isSummer)?.end
                            val endStr = endTime?.toString() ?: ""
                            Triple("正在上课", "${currentCourse.name}  $endStr 下课", MiuixTheme.colorScheme.primary)
                        }
                        nextCourse != null -> {
                            val startTime = com.xjtu.toolbox.util.XjtuTime.getClassTime(nextCourse.startSection, isSummer)?.start
                            val minutesToNext = if (startTime != null) java.time.Duration.between(now, startTime).toMinutes() else -1
                            val timeHint = if (minutesToNext in 1..60) "${minutesToNext}分钟后" else startTime?.toString() ?: ""
                            Triple("下节课", "$timeHint · ${nextCourse.name}", androidx.compose.ui.graphics.Color(0xFF2E7D32))
                        }
                        allDone -> Triple("今日课程", "今天的课已全部结束", MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        else -> Triple("今日课程", "", MiuixTheme.colorScheme.primary)
                    }

                    Card(
                        onClick = { onNavigateWithLogin(Routes.SCHEDULE, LoginType.JWXT) },
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 20.dp,
                        colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant),
                        pressFeedbackType = PressFeedbackType.Sink
                    ) {
                        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CalendarMonth, null, Modifier.size(16.dp), tint = MiuixTheme.colorScheme.primary)
                                Spacer(Modifier.width(6.dp))
                                Text(cardTitle, style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                                if (todayCourses.isNotEmpty()) {
                                    Text("${todayCourses.size} 节", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                    Spacer(Modifier.width(4.dp))
                                }
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(16.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f))
                            }
                            if (statusHint.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Text(statusHint, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            }
                            if (todayCourses.isNotEmpty() && !allDone) {
                                Spacer(Modifier.height(10.dp))
                                // 显示剩余未上的课程（当前在上的高亮）
                                val remainingCourses = todayCourses.filter { course ->
                                    val endTime = com.xjtu.toolbox.util.XjtuTime.getClassTime(course.endSection, isSummer)?.end
                                    endTime == null || now <= endTime
                                }
                                remainingCourses.take(3).forEachIndexed { idx, course ->
                                    if (idx > 0) Spacer(Modifier.height(6.dp))
                                    val isCurrent = course == currentCourse
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        // 时间标签
                                        val timeRange = com.xjtu.toolbox.util.XjtuTime.getTimeRangeStr(course.startSection, course.endSection, isSummer)
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = if (isCurrent) accentColor.copy(alpha = 0.15f) else MiuixTheme.colorScheme.secondaryContainer,
                                            modifier = Modifier.widthIn(min = 56.dp)
                                        ) {
                                            Text(
                                                timeRange.split("-").first(),
                                                Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                                style = MiuixTheme.textStyles.footnote1,
                                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isCurrent) accentColor else MiuixTheme.colorScheme.onSecondaryContainer,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                course.name,
                                                style = MiuixTheme.textStyles.body2,
                                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                                color = if (isCurrent) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurface,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis
                                            )
                                            if (course.location.isNotBlank()) {
                                                Text(course.location, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                        if (isCurrent) {
                                            Surface(shape = RoundedCornerShape(4.dp), color = accentColor) {
                                                Text("进行中", Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onPrimary, fontSize = 10.sp)
                                            }
                                        }
                                    }
                                }
                                val hiddenCount = remainingCourses.size - 3
                                if (hiddenCount > 0) {
                                    Spacer(Modifier.height(4.dp))
                                    Text("+$hiddenCount 门课…", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

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
                                                val api = com.xjtu.toolbox.card.CampusCardApi(login)
                                                val info = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { api.getCardInfo() }
                                                cachedBalance = info.balance.toFloat()
                                                context.getSharedPreferences("campus_card", 0).edit()
                                                    .putFloat("card_balance_cache", info.balance.toFloat())
                                                    .putString("card_name_cache", info.name)
                                                    .putLong("card_cache_time", System.currentTimeMillis())
                                                    .apply()
                                                // 最近消费也刷新（只取最近5笔，快速）
                                                val (_, recentTx) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { api.getTransactions(page = 1, pageSize = 5) }
                                                val recentJson = com.google.gson.Gson().toJson(recentTx)
                                                context.getSharedPreferences("campus_card", 0).edit()
                                                    .putString("card_recent_tx_cache", recentJson)
                                                    .apply()
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

            @Composable
            fun svcRow(vararg items: @Composable (Modifier) -> Unit) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items.forEach { it(Modifier.weight(1f)) }
                    if (items.size < 2) Spacer(Modifier.weight(1f))
                }
            }

            val svcGreen = androidx.compose.ui.graphics.Color(0xFF2E7D32)
            val svcOrange = androidx.compose.ui.graphics.Color(0xFFE65100)
            val svcPurple = androidx.compose.ui.graphics.Color(0xFF7B1FA2)
            val svcTeal = androidx.compose.ui.graphics.Color(0xFF00796B)
            val svcIndigo = androidx.compose.ui.graphics.Color(0xFF283593)
            val svcBrown = androidx.compose.ui.graphics.Color(0xFF4E342E)
            val svcCyan = androidx.compose.ui.graphics.Color(0xFF00838F)
            val svcPink = androidx.compose.ui.graphics.Color(0xFFC2185B)
            val svcDeepPurple = androidx.compose.ui.graphics.Color(0xFF512DA8)
            svcRow(
                { m -> HomeServiceCard(Icons.Default.CreditCard, "校园卡", "账单 · 洞察", svcGreen, m) { onNavigateWithLogin(Routes.CAMPUS_CARD, LoginType.CAMPUS_CARD) } },
                { m -> HomeServiceCard(Icons.Default.CalendarMonth, "课表考试", "课表 · 考试", svcIndigo, m) { onNavigateWithLogin(Routes.SCHEDULE, LoginType.JWXT) } }
            )
            svcRow(
                { m -> HomeServiceCard(Icons.Default.Assessment, "成绩查询", "成绩 · GPA", svcPurple, m) { onNavigateWithLogin(Routes.JWAPP_SCORE, LoginType.JWAPP) } },
                { m -> HomeServiceCard(Icons.Default.QrCode, "付款码", "校园支付", svcTeal, m) { onNavigateWithLogin(Routes.PAYMENT_CODE, LoginType.JWXT) } }
            )
            svcRow(
                { m -> HomeServiceCard(Icons.Default.DateRange, "考勤查询", "出勤记录", svcBrown, m) { onNavigateWithLogin(Routes.ATTENDANCE, LoginType.ATTENDANCE) } },
                { m -> HomeServiceCard(Icons.Default.Description, "电子成绩单", "下载 · 签章", svcIndigo, m) { onNavigateWithLogin(Routes.TRANSCRIPT, LoginType.DZPZ) } }
            )
            svcRow(
                { m -> HomeServiceCard(Icons.Default.RateReview, "本科评教", "评教系统", svcPink, m) { onNavigateWithLogin(Routes.JUDGE, LoginType.JWXT) } },
                { m -> HomeServiceCard(Icons.Default.Chair, "图书馆", "座位预约", svcOrange, m) { onNavigateWithLogin(Routes.LIBRARY, LoginType.LIBRARY) } }
            )
            svcRow(
                { m -> HomeServiceCard(Icons.Default.LocationOn, "空闲教室", "教室查询", svcPurple, m) { onNavigate(Routes.EMPTY_ROOM) } },
                { m -> HomeServiceCard(Icons.Default.Notifications, "通知公告", "校园通知", MiuixTheme.colorScheme.error, m) { onNavigate(Routes.NOTIFICATION) } }
            )
            svcRow(
                { m -> HomeServiceCard(Icons.Default.Place, "场馆预订", "运动场地", svcCyan, m) { onNavigateWithLogin(Routes.VENUE, LoginType.VENUE) } },
                { m -> HomeServiceCard(Icons.Default.OndemandVideo, "课程回放", "Class录播", svcDeepPurple, m) { onNavigateWithLogin(Routes.CLASS_REPLAY, LoginType.CLASS) } },
            )
            svcRow(
                { m -> HomeServiceCard(Icons.Default.School, "思源学堂", "课件 · 作业", svcIndigo, m) { onNavigateWithLogin(Routes.LMS, LoginType.LMS) } },
                { m -> HomeServiceCard(Icons.Default.TravelExplore, "课表查询", "全校课程", svcCyan, m) { onNavigateWithLogin(Routes.SCHOOL_COURSE, LoginType.JWXT) } }
            )
        }

        Spacer(Modifier.height(100.dp))
    }
}

// ══════════════════════════════════════════
//  Tab 2 — 教务
// ══════════════════════════════════════════

@Composable
private fun AcademicTab(loginState: AppLoginState, onNavigateWithLogin: (String, LoginType) -> Unit, scrollBehavior: ScrollBehavior? = null) {
    Column(
        Modifier
            .fillMaxSize()
            .then(if (scrollBehavior != null) Modifier.nestedScroll(scrollBehavior.nestedScrollConnection) else Modifier)
            .overScrollVertical()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(12.dp))

        val cIndigo = androidx.compose.ui.graphics.Color(0xFF283593)
        val cPurple = androidx.compose.ui.graphics.Color(0xFF7B1FA2)
        val cBrown = androidx.compose.ui.graphics.Color(0xFF4E342E)
        val cPink = androidx.compose.ui.graphics.Color(0xFFC2185B)
        val cCyan = androidx.compose.ui.graphics.Color(0xFF00838F)
        val cGreen = androidx.compose.ui.graphics.Color(0xFF2E7D32)
        val cTeal = androidx.compose.ui.graphics.Color(0xFF00796B)
        val cOrange = androidx.compose.ui.graphics.Color(0xFFE65100)
        val cDeepPurple = androidx.compose.ui.graphics.Color(0xFF512DA8)

        SectionLabel("本科生")
        ServiceCard(Icons.Default.CalendarMonth, "课表 / 考试", "课表安排 · 考试时间 · 教材查询", loginState.jwxtLogin != null, iconColor = cIndigo) { onNavigateWithLogin(Routes.SCHEDULE, LoginType.JWXT) }
        ServiceCard(Icons.Default.Assessment, "成绩查询", "查看成绩 / GPA / 含报表补充", loginState.jwappLogin != null, iconColor = cPurple) { onNavigateWithLogin(Routes.JWAPP_SCORE, LoginType.JWAPP) }
        ServiceCard(Icons.Default.DateRange, "考勤查询", "查看课堂出勤情况", loginState.attendanceLogin != null, iconColor = cBrown) { onNavigateWithLogin(Routes.ATTENDANCE, LoginType.ATTENDANCE) }
        ServiceCard(Icons.Default.RateReview, "本科评教", "一键自动评教", loginState.jwxtLogin != null, iconColor = cPink) { onNavigateWithLogin(Routes.JUDGE, LoginType.JWXT) }
        ServiceCard(Icons.Default.TravelExplore, "全校课表查询", "全校课程检索 · 地点 · 选课人数", loginState.jwxtLogin != null, iconColor = cCyan) { onNavigateWithLogin(Routes.SCHOOL_COURSE, LoginType.JWXT) }

        Spacer(Modifier.height(16.dp))

        SectionLabel("课程学习")
        ServiceCard(Icons.Default.OndemandVideo, "课程回放", "课程录播 · 倍速回看", loginState.classLogin != null, iconColor = cDeepPurple) { onNavigateWithLogin(Routes.CLASS_REPLAY, LoginType.CLASS) }
        ServiceCard(Icons.Default.School, "思源学堂", "课件 · 作业 · 课堂回放", loginState.lmsLogin != null, iconColor = cIndigo) { onNavigateWithLogin(Routes.LMS, LoginType.LMS) }

        Spacer(Modifier.height(100.dp))
    }
}

// ══════════════════════════════════════════
//  Tab 3 — 工具
// ══════════════════════════════════════════

@Composable
private fun ToolsTab(loginState: AppLoginState, onNavigateWithLogin: (String, LoginType) -> Unit, onNavigate: (String) -> Unit, scrollBehavior: ScrollBehavior? = null) {
    Column(
        Modifier
            .fillMaxSize()
            .then(if (scrollBehavior != null) Modifier.nestedScroll(scrollBehavior.nestedScrollConnection) else Modifier)
            .overScrollVertical()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(12.dp))

        val cGreen = androidx.compose.ui.graphics.Color(0xFF2E7D32)
        val cTeal = androidx.compose.ui.graphics.Color(0xFF00796B)
        val cOrange = androidx.compose.ui.graphics.Color(0xFFE65100)
        val cCyan = androidx.compose.ui.graphics.Color(0xFF00838F)

        SectionLabel("校园服务")
        ServiceCard(Icons.Default.CreditCard, "校园卡", "余额查询 / 消费账单 / 分析", loginState.campusCardLogin != null, iconColor = cGreen) { onNavigateWithLogin(Routes.CAMPUS_CARD, LoginType.CAMPUS_CARD) }
        ServiceCard(Icons.Default.QrCode, "付款码", "校园支付 · 点击即用", loginState.getSharedClient() != null, iconColor = cTeal) { onNavigateWithLogin(Routes.PAYMENT_CODE, LoginType.JWXT) }
        ServiceCard(Icons.Default.Chair, "图书馆座位", "查询 · 预约座位", loginState.libraryLogin != null, iconColor = cOrange) { onNavigateWithLogin(Routes.LIBRARY, LoginType.LIBRARY) }
        ServiceCard(Icons.Default.Place, "场馆预订", "体育场馆 · 运动场地预订", loginState.venueLogin != null, iconColor = cCyan) { onNavigateWithLogin(Routes.VENUE, LoginType.VENUE) }

        Spacer(Modifier.height(16.dp))

        SectionLabel("校园查询")
        ServiceCard(Icons.Default.LocationOn, "空闲教室", "查询各校区各时段空闲教室 · 无需登录", false, iconColor = androidx.compose.ui.graphics.Color(0xFF7B1FA2)) { onNavigate(Routes.EMPTY_ROOM) }

        Spacer(Modifier.height(16.dp))

        SectionLabel("信息获取")
        ServiceCard(Icons.Default.Notifications, "通知公告", "教务处 / 研究生院 / 物理学院通知 · 无需登录", false, iconColor = androidx.compose.ui.graphics.Color(0xFFE65100)) { onNavigate(Routes.NOTIFICATION) }

        Spacer(Modifier.height(16.dp))

        SectionLabel("实用工具")
        // WebVPN 网址互转
        var inputUrl by remember { mutableStateOf("") }
        var convertedUrl by remember { mutableStateOf("") }
        var isReversed by remember { mutableStateOf(false) } // false=原始→VPN, true=VPN→原始
        Card(Modifier.fillMaxWidth(), cornerRadius = 12.dp) {
            Column(Modifier.padding(16.dp)) {
                Text("WebVPN 网址互转", style = MiuixTheme.textStyles.subtitle, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))

                // 方向切换
                TabRowWithContour(
                    tabs = listOf("原始 → VPN", "VPN → 原始"),
                    selectedTabIndex = if (isReversed) 1 else 0,
                    onTabSelected = { tab -> isReversed = tab == 1; convertedUrl = "" },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(12.dp))
                TextField(
                    value = inputUrl,
                    onValueChange = { inputUrl = it; convertedUrl = "" },
                    label = if (!isReversed) "校内网址" else "WebVPN 网址",
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (inputUrl.isNotBlank()) {
                            val normalized = inputUrl.trim().let { url ->
                                if (!isReversed && !url.startsWith("http://") && !url.startsWith("https://")) {
                                    "https://$url"
                                } else url
                            }
                            convertedUrl = if (!isReversed) {
                                com.xjtu.toolbox.util.WebVpnUtil.getVpnUrl(normalized)
                            } else {
                                com.xjtu.toolbox.util.WebVpnUtil.getOriginalUrl(normalized) ?: "⚠ 无法解析此 WebVPN 网址"
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("转换") }
                if (convertedUrl.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    SelectionContainer {
                        Text(convertedUrl, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.primary)
                    }
                    // 如果是有效 URL，提供内置浏览器跳转按钮
                    if (convertedUrl.startsWith("http")) {
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { onNavigate(Routes.browser(convertedUrl)) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("在内置浏览器中打开")
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(100.dp))
    }
}

// ══════════════════════════════════════════
//  Tab 4 — 我的（含统一登录）
// ══════════════════════════════════════════

@Composable
private fun ProfileTab(loginState: AppLoginState, onNavigateWithLogin: (String, LoginType) -> Unit, credentialStore: CredentialStore, scrollBehavior: ScrollBehavior? = null) {
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

                // WebVPN + 后台并行贯通所有子系统
                if (loginState.isOnCampus == false) loginState.loginWebVpn()
                loginState.persistCredentials(credentialStore)
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        kotlinx.coroutines.coroutineScope {
                            launch { loginState.autoLogin(LoginType.JWAPP) }
                            launch { loginState.autoLogin(LoginType.YWTB) }
                            launch { loginState.autoLogin(LoginType.ATTENDANCE) }
                            launch { loginState.autoLogin(LoginType.LIBRARY) }
                        }
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

            // ── 后台: 并行贯通所有子系统（不阻塞 UI）──
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    kotlinx.coroutines.coroutineScope {
                        launch { loginState.autoLogin(LoginType.JWAPP) }
                        launch { loginState.autoLogin(LoginType.YWTB) }
                        launch { loginState.autoLogin(LoginType.ATTENDANCE) }
                        launch { loginState.autoLogin(LoginType.LIBRARY) }
                    }
                    if (loginState.ywtbLogin != null && loginState.ywtbUserInfo == null) {
                        val api = com.xjtu.toolbox.ywtb.YwtbApi(loginState.ywtbLogin!!)
                        loginState.ywtbUserInfo = api.getUserInfo()
                    }
                } catch (_: Exception) { }
            }
        }
    }

    // ── MFA 两步验证对话框 ──
    val showMfaDialog = remember { mutableStateOf(false) }
    LaunchedEffect(mfaLogin) { showMfaDialog.value = mfaLogin != null }
    if (mfaLogin != null) {
        BackHandler(enabled = showMfaDialog.value) {
            showMfaDialog.value = false
            mfaLogin = null
            mfaCode = ""
            mfaError = null
        }
        SuperBottomSheet(
            show = showMfaDialog,
            title = "两步验证",
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
                    .verticalScroll(rememberScrollState())
                    .imePadding()
            ) {
                Text("需要短信验证码完成登录", style = MiuixTheme.textStyles.body1)
                Spacer(Modifier.height(8.dp))
                Text("手机号: $mfaPhone", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(12.dp))

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

                Spacer(Modifier.height(16.dp))

                // ━━ 设置区块（网络模式 + 退出登录） ━━
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 20.dp,
                    colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant)
                ) {
                    Column {
                        // 网络模式行
                        val (networkIcon, networkText, networkColor) = when (loginState.isOnCampus) {
                            true -> Triple(Icons.Default.Wifi, "校内直连", MiuixTheme.colorScheme.primary)
                            false -> Triple(Icons.Default.VpnKey, "WebVPN 代理", MiuixTheme.colorScheme.primaryVariant)
                            null -> Triple(Icons.Default.WifiFind, "检测中...", MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(shape = CircleShape, color = networkColor.copy(alpha = 0.1f), modifier = Modifier.size(36.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(networkIcon, null, Modifier.size(18.dp), tint = networkColor)
                                }
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text("网络模式", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium)
                                Text("用到时会自动登录", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            }
                            Surface(shape = RoundedCornerShape(6.dp), color = networkColor.copy(alpha = 0.12f)) {
                                Text(networkText, Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MiuixTheme.textStyles.footnote1, color = networkColor)
                            }
                        }

                        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MiuixTheme.colorScheme.outline.copy(alpha = 0.3f))

                        // 退出登录行
                        val showLogoutDialog = remember { mutableStateOf(false) }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = SinkFeedback()
                                ) { showLogoutDialog.value = true }
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
                            SuperDialog(
                                show = showLogoutDialog,
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

        Spacer(Modifier.height(24.dp))

        // ── 关于区域 ──
        val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
        var plansExpanded by remember { mutableStateOf(false) }

        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ━━ 应用标识卡片 ━━
            Card(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 20.dp,
                colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant)
            ) {
                Column {
                    // 应用信息行
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = SinkFeedback()
                            ) { uriHandler.openUri("https://www.runqinliu666.cn/") }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(shape = CircleShape, color = androidx.compose.ui.graphics.Color(0xFF2E7D32).copy(alpha = 0.15f), modifier = Modifier.size(36.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Terrain, null, Modifier.size(18.dp), tint = androidx.compose.ui.graphics.Color(0xFF2E7D32))
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("岱宗盒子", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Bold)
                            Text("by Yeliqin666 · 点击访问主页", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.primary.copy(alpha = 0.7f))
                        }
                        Surface(shape = RoundedCornerShape(6.dp), color = MiuixTheme.colorScheme.secondaryContainer) {
                            Text("v${BuildConfig.VERSION_NAME}", Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSecondaryContainer)
                        }
                    }

                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MiuixTheme.colorScheme.outline.copy(alpha = 0.3f))

                    // 检查更新行
                    var updateCheckState by remember { mutableStateOf<String?>(null) }
                    var latestDownloadUrl by remember { mutableStateOf<String?>(null) }
                    val updateScope = rememberCoroutineScope()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = SinkFeedback(),
                                enabled = updateCheckState != "checking"
                            ) {
                                updateCheckState = "checking"
                                latestDownloadUrl = null
                                updateScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    try {
                                        val client = okhttp3.OkHttpClient.Builder()
                                            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                                            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                                            .build()
                                        val req = okhttp3.Request.Builder()
                                            .url("https://api.github.com/repos/yeliqin666/xjtu-toolbox-android/releases/latest")
                                            .header("Accept", "application/vnd.github+json")
                                            .build()
                                        val resp = client.newCall(req).execute()
                                        if (!resp.isSuccessful) { updateCheckState = "error:HTTP ${resp.code}"; return@launch }
                                        val body = resp.body?.string() ?: ""
                                        val json = com.google.gson.JsonParser.parseString(body).asJsonObject
                                        val tagName = json.get("tag_name")?.asString ?: ""
                                        val latestVersion = tagName.removePrefix("v")
                                        if (latestVersion == BuildConfig.VERSION_NAME) {
                                            updateCheckState = "latest"
                                        } else {
                                            updateCheckState = latestVersion
                                            val assets = json.getAsJsonArray("assets")
                                            latestDownloadUrl = if (assets != null && assets.size() > 0)
                                                assets[0].asJsonObject.get("browser_download_url")?.asString
                                            else json.get("html_url")?.asString
                                        }
                                    } catch (e: Exception) { updateCheckState = "error:${e.message?.take(50)}" }
                                }
                            }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val (updIcon, updColor) = when {
                            updateCheckState == "checking" -> Icons.Default.Refresh to MiuixTheme.colorScheme.onSurfaceVariantSummary
                            updateCheckState == "latest" -> Icons.Default.CheckCircle to MiuixTheme.colorScheme.primary
                            updateCheckState?.startsWith("error:") == true -> Icons.Default.ErrorOutline to MiuixTheme.colorScheme.error
                            updateCheckState != null -> Icons.Default.NewReleases to MiuixTheme.colorScheme.primaryVariant
                            else -> Icons.Default.SystemUpdate to MiuixTheme.colorScheme.onSurfaceVariantSummary
                        }
                        Surface(shape = CircleShape, color = updColor.copy(alpha = 0.1f), modifier = Modifier.size(36.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                if (updateCheckState == "checking")
                                    CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp, colors = ProgressIndicatorDefaults.progressIndicatorColors(foregroundColor = updColor))
                                else Icon(updIcon, null, Modifier.size(18.dp), tint = updColor)
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("检查更新", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium)
                            Text(
                                when {
                                    updateCheckState == "checking" -> "检查中…"
                                    updateCheckState == "latest" -> "当前已是最新版本"
                                    updateCheckState?.startsWith("error:") == true -> "检查失败，点击重试"
                                    updateCheckState != null -> "发现新版本 v$updateCheckState"
                                    else -> "当前版本 v${BuildConfig.VERSION_NAME}"
                                },
                                style = MiuixTheme.textStyles.body2,
                                color = when {
                                    updateCheckState == "latest" -> MiuixTheme.colorScheme.primary
                                    updateCheckState?.startsWith("error:") == true -> MiuixTheme.colorScheme.error
                                    updateCheckState != null && !updateCheckState!!.startsWith("error:") && updateCheckState != "latest" && updateCheckState != "checking" -> MiuixTheme.colorScheme.primaryVariant
                                    else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                                }
                            )
                        }
                        if (latestDownloadUrl != null && updateCheckState != null && !updateCheckState!!.startsWith("error:") && updateCheckState != "latest" && updateCheckState != "checking") {
                            Button(
                                onClick = { uriHandler.openUri(latestDownloadUrl!!) },
                                modifier = Modifier,
                                insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.primary)
                            ) {
                                Icon(Icons.Default.Download, null, Modifier.size(14.dp), tint = MiuixTheme.colorScheme.onPrimary)
                                Spacer(Modifier.width(4.dp))
                                Text("下载更新", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onPrimary)
                            }
                        } else if (updateCheckState != "checking") {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f))
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ━━ 开源社区卡片 ━━
            Card(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 20.dp,
                colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant)
            ) {
                Column {
                    // 源代码 — 引导参与
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = SinkFeedback()
                            ) { uriHandler.openUri("https://github.com/yeliqin666/xjtu-toolbox-android") }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(shape = CircleShape, color = androidx.compose.ui.graphics.Color(0xFF37474F).copy(alpha = 0.15f), modifier = Modifier.size(36.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Code, null, Modifier.size(18.dp), tint = androidx.compose.ui.graphics.Color(0xFF37474F))
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("源代码 · 开源透明", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium)
                            Text("GitHub 开源 · 欢迎 Star / Issue / PR", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f))
                    }

                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MiuixTheme.colorScheme.outline.copy(alpha = 0.3f))

                    // 反馈建议 — 引导提 Issue
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = SinkFeedback()
                            ) { uriHandler.openUri("https://github.com/yeliqin666/xjtu-toolbox-android/issues") }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(shape = CircleShape, color = MiuixTheme.colorScheme.primary.copy(alpha = 0.12f), modifier = Modifier.size(36.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Feedback, null, Modifier.size(18.dp), tint = MiuixTheme.colorScheme.primary)
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("反馈 · 建议 · 想法", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium)
                            Text("发现 Bug？有新点子？来 Issue 告诉我", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f))
                    }

                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MiuixTheme.colorScheme.outline.copy(alpha = 0.3f))

                    // 致谢行
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = SinkFeedback()
                            ) { uriHandler.openUri("https://github.com/yan-xiaoo/XJTUToolBox") }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(shape = CircleShape, color = MiuixTheme.colorScheme.error.copy(alpha = 0.12f), modifier = Modifier.size(36.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Favorite, null, Modifier.size(18.dp), tint = MiuixTheme.colorScheme.error)
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("致谢", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium)
                            Text("XJTUToolBox by yan-xiaoo", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f))
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ━━ 开发计划卡片 ━━
            Card(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 20.dp,
                colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant)
            ) {
                Column {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = SinkFeedback()
                            ) { plansExpanded = !plansExpanded }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(shape = CircleShape, color = MiuixTheme.colorScheme.primaryVariant.copy(alpha = 0.12f), modifier = Modifier.size(36.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.RocketLaunch, null, Modifier.size(18.dp), tint = MiuixTheme.colorScheme.primaryVariant)
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Text("开发计划", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Icon(
                            if (plansExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            null, Modifier.size(18.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f)
                        )
                    }
                    androidx.compose.animation.AnimatedVisibility(visible = plansExpanded) {
                        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 14.dp)) {
                            val plans = listOf(
                                "图书馆座位推荐",
                                "餐券领取 & 付款码优化",
                                "智能选课助手",
                                "通知聚合订阅 & Push",
                                "电子教材在线阅读"
                            )
                            plans.forEachIndexed { idx, plan ->
                                Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Surface(shape = CircleShape, color = MiuixTheme.colorScheme.primaryVariant.copy(alpha = 0.10f), modifier = Modifier.size(20.dp)) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text("${idx + 1}", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.primaryVariant, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Text(plan, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(100.dp))
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

/** HomeTab 今日课表卡片用的轻量数据类 */
private data class CourseCardInfo(val name: String, val location: String, val startSection: Int, val endSection: Int)

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
    iconColor: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier, onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = modifier.height(72.dp),
        cornerRadius = 16.dp,
        pressFeedbackType = PressFeedbackType.Sink,
        colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant)
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(iconColor.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = iconColor, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MiuixTheme.textStyles.subtitle, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
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
//  版本更新公告弹窗 —— 版本化日志注册表
// ══════════════════════════════════════════

/**
 * 每个版本的更新日志。
 * key = versionName，value = (更新项列表, 已知问题列表)。
 * ⚠️ 发版前必须为当前 versionName 添加条目，否则编译期 init{} 会崩溃。
 */
private data class VersionChangelog(
    val items: List<Pair<String, String>>,
    val issues: List<String> = emptyList()
)

private val CHANGELOGS: Map<String, VersionChangelog> = mapOf(
    "2.3.2" to VersionChangelog(
        items = listOf(
            "🎉" to "正式版来了！感谢参与内测的山东老乡！",
            "🔐" to "接入学工系统，可查看详细信息。",
            "📸" to "新增成绩单下载，绕开限制；成绩查询纳入未评教成绩",
            "💳" to "图书馆智能座位推荐、地图选座V2。",
            "🏠" to "UI改版，使用MIUIX开源的HyperOS设计语言。",
            "👍" to "大量Bug修复与人性化改进！"
        ),
        issues = listOf(
            "图书馆定时抢座功能待修复",
            "通知推送功能有待优化",
            "校园卡登录偶尔失败（教务 Token 获取）"
        )
    ),
    "2.5.0" to VersionChangelog(
        items = listOf(
            "🎓" to "新增课程回放功能（教学平台 TronClass）",
            "🏟️" to "新增体育场馆预订",
            "📜" to "用户协议更新",
            "📚" to "图书馆状态优化",
            "🔄" to "视频播放器修复",
            "�" to "移除定时抢座功能，避免风险",
            "👍" to "大量UI优化与Bug修复"
        ),
        issues = listOf(
            "通知推送功能有待优化"
        )
    ),
    "2.5.1" to VersionChangelog(
        items = listOf(
            "🔙" to "修复所有界面按返回直接回桌面的严重Bug",
            "🧹" to "移除多余的 NavigationEvent 依赖"
        )
    ),
    "2.6.0" to VersionChangelog(
        items = listOf(
            "📖" to "新增思源学堂（LMS）功能：课程、作业、课件、课堂回放",
            "📝" to "作业详情：查看提交记录、评分、教师评语",
            "🎬" to "课堂回放：支持多机位视频下载",
            "📎" to "课件附件：一键下载课程资料",
            "👍" to "UI 优化与多处细节改进"
        )
    ),
    "2.7.0" to VersionChangelog(
        items = listOf(
            "🔍" to "新增全校课表查询：按课程名、教师、院系等多维度检索",
            "🏠" to "首页/教务/工具 Tab 重新分区，更加合理",
            "👤" to "\"我的\" 页大幅重构：全新关于区域、开源社区入口、开发计划",
            "🎬" to "修复思源学堂视频播放闪退（横屏 Activity 重建问题）",
            "📊" to "成绩查询页新增免责声明提示",
            "🐛" to "修复全校课表 API 解析异常导致的闪退",
            "👍" to "出勤记录文案修正、多处 UI 细节优化"
        )
    )
)

// 编译期校验：确保当前版本号有对应的更新日志
private val _changelogCheck = run {
    val currentVersion = BuildConfig.VERSION_NAME
    require(CHANGELOGS.containsKey(currentVersion)) {
        "⚠️ 版本 $currentVersion 没有对应的更新日志！请在 CHANGELOGS 中添加条目。"
    }
}

@Composable
private fun UpdateNoticeDialog(show: MutableState<Boolean>, onDismiss: () -> Unit) {
    val changelog = CHANGELOGS[BuildConfig.VERSION_NAME] ?: return
    BackHandler(enabled = show.value) { onDismiss() }
    SuperBottomSheet(
        show = show,
        title = "岱宗盒子 v${BuildConfig.VERSION_NAME}",
        onDismissRequest = onDismiss
    ) {
        Text("更新说明", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Spacer(Modifier.height(8.dp))
        changelog.items.forEach { (emoji, text) ->
            Row(Modifier.padding(vertical = 3.dp)) {
                Text(emoji, style = MiuixTheme.textStyles.body1)
                Spacer(Modifier.width(8.dp))
                Text(text, style = MiuixTheme.textStyles.body2, modifier = Modifier.weight(1f))
            }
        }

        if (changelog.issues.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MiuixTheme.colorScheme.outline.copy(alpha = 0.3f))
            Spacer(Modifier.height(12.dp))

            Text("已知问题", style = MiuixTheme.textStyles.subtitle, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.error)
            Spacer(Modifier.height(6.dp))
            changelog.issues.forEach { issue ->
                Row(Modifier.padding(vertical = 2.dp)) {
                    Text("•", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.outline)
                    Spacer(Modifier.width(6.dp))
                    Text(issue, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("知道了")
        }
        Spacer(Modifier.height(16.dp))
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}
