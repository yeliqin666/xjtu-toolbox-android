package com.xjtu.toolbox

import android.os.Bundle
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.withLock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** 标记应用是否准备好（登录恢复完成后为 true），供 SplashScreen 决定何时消失 */
    var isAppReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        splash.setKeepOnScreenCondition { !isAppReady }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            XJTUToolBoxTheme {
                AppNavigation(onReady = { isAppReady = true })
            }
        }
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
    const val BROWSER = "browser?url={url}"

    fun login(type: LoginType, target: String) = "login/${type.name}/$target"
    fun browser(url: String = "") = "browser?url=${java.net.URLEncoder.encode(url, "UTF-8")}"
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

    // 持久化 CookieJar（由外部传入，整个 App 共享一个实例）
    var persistentCookieJar: com.xjtu.toolbox.util.PersistentCookieJar? = null

    // SSO: 共享的 OkHttpClient（携带 CAS TGC cookie），实现一次登录、所有系统自动认证
    private var sharedClient: okhttp3.OkHttpClient? = null

    // 并发保护：多个 autoLogin 并行时，保证 sharedClient 只初始化一次
    private val clientInitMutex = kotlinx.coroutines.sync.Mutex()

    // WebVPN: 校外自动模式
    // vpnClient = sharedClient + WebVpnInterceptor（仅用于内部服务）
    private var vpnClient: okhttp3.OkHttpClient? = null
    var isOnCampus by mutableStateOf<Boolean?>(null)   // null=未检测, true=校内, false=校外
    private var webVpnLoggedIn = false

    // 设备指纹 ID（首次登录时生成，后续系统复用以避免 MFA 重复验证）
    private var firstVisitorId: String? = null

    // RSA 公钥缓存
    private var cachedRsaKey: String? = null

    // 一网通办个人信息（登录后自动获取，在"我的"页面展示）
    var ywtbUserInfo by mutableStateOf<com.xjtu.toolbox.ywtb.UserInfo?>(null)

    // 保存的凭据（内存中），用于自动登录其他系统
    var savedUsername: String = ""
        private set
    var savedPassword: String = ""
        private set

    val hasCredentials: Boolean get() = savedUsername.isNotEmpty() && savedPassword.isNotEmpty()
    val isLoggedIn: Boolean get() = activeUsername.isNotEmpty()

    val loginCount: Int
        get() = listOfNotNull(
            attendanceLogin, jwxtLogin, jwappLogin, ywtbLogin, libraryLogin, campusCardLogin
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
        val creds = store.load() ?: return
        savedUsername = creds.first
        savedPassword = creds.second
        // 恢复持久化的 fpVisitorId（保持设备一致性，避免 MFA）
        firstVisitorId = store.loadFpVisitorId()
        // 恢复 RSA 公钥缓存（24h 有效期）
        cachedRsaKey = store.loadRsaPublicKey()
    }

    /** 持久化凭据和缓存到 EncryptedSharedPreferences */
    fun persistCredentials(store: CredentialStore) {
        if (hasCredentials) store.save(savedUsername, savedPassword)
        firstVisitorId?.let { store.saveFpVisitorId(it) }
        cachedRsaKey?.let { store.saveRsaPublicKey(it) }
    }

    fun getCached(type: LoginType): XJTULogin? {
        val login = when (type) {
            LoginType.ATTENDANCE -> attendanceLogin
            LoginType.JWXT -> jwxtLogin
            LoginType.JWAPP -> jwappLogin
            LoginType.YWTB -> ywtbLogin
            LoginType.LIBRARY -> libraryLogin
            LoginType.CAMPUS_CARD -> campusCardLogin
        } ?: return null

        // Token-based 系统：检查 token 有效性，过期则尝试 reAuth
        when (login) {
            is JwappLogin -> {
                if (!login.isTokenValid()) {
                    android.util.Log.d("AppLoginState", "getCached(JWAPP): token invalid, attempting reAuth")
                    if (!login.reAuthenticate()) {
                        android.util.Log.w("AppLoginState", "getCached(JWAPP): reAuth failed, clearing cache")
                        jwappLogin = null
                        return null
                    }
                }
            }
            is YwtbLogin -> {
                if (!login.isTokenValid()) {
                    android.util.Log.d("AppLoginState", "getCached(YWTB): token invalid, attempting reAuth")
                    if (!login.reAuthenticate()) {
                        android.util.Log.w("AppLoginState", "getCached(YWTB): reAuth failed, clearing cache")
                        ywtbLogin = null
                        return null
                    }
                }
            }
            // AttendanceLogin 已有 executeWithReAuth，不在此处做额外检查
            // Cookie-based 系统（Jwxt/Library/CampusCard/Gmis/Gste）依赖 PersistentCookieJar
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
        }
    }

    /**
     * 检测是否在校园网内（尝试连接内部服务器）
     */
    suspend fun detectCampusNetwork(): Boolean {
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
                // 关键：不传 existingClient，创建全新 client（干净的 CookieJar）
                // 与 Python 版 self.cookies.clear() + NewLogin(WEBVPN_LOGIN_URL, self) 等价
                android.util.Log.d("WebVPN", "Creating XJTULogin for WebVPN URL")
                val login = XJTULogin(
                    com.xjtu.toolbox.util.WebVpnUtil.WEBVPN_LOGIN_URL,
                    existingClient = null,
                    visitorId = firstVisitorId  // 复用 visitorId 减少 MFA
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
     * 首次登录使用用户名密码，后续通过共享 CookieManager 实现 CAS SSO
     * 内部服务（考勤/图书馆）自动检测并使用 WebVPN
     * @return 登录成功的 XJTULogin 实例，失败返回 null
     */
    suspend fun autoLogin(type: LoginType): XJTULogin? {
        if (!hasCredentials) return null
        getCached(type)?.let { return it }

        // 内部服务需要 VPN：自动检测网络并建立 VPN
        if (isInternalService(type)) {
            if (isOnCampus == null) isOnCampus = detectCampusNetwork()
            if (isOnCampus == false && vpnClient == null) loginWebVpn()
        }

        val useWebVpn = isInternalService(type) && isOnCampus == false
        val clientForLogin = if (useWebVpn) {
            vpnClient ?: return null
        } else {
            // 使用 Mutex 保护 sharedClient 初始化（多 autoLogin 并行时避免重复创建）
            clientInitMutex.withLock {
                sharedClient ?: persistentCookieJar?.let { jar ->
                    okhttp3.OkHttpClient.Builder()
                        .addInterceptor(okhttp3.brotli.BrotliInterceptor)
                        .cookieJar(jar)
                        .followRedirects(true)
                        .followSslRedirects(true)
                        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                        .also { sharedClient = it }
                }
            }
        }

        val visitorId = sharedClient?.let { firstVisitorId } ?: firstVisitorId

        return try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val login = type.createLogin(clientForLogin, visitorId, useWebVpn, cachedRsaKey)
                val result = login.login(savedUsername, savedPassword)
                if (result.state == LoginState.SUCCESS) {
                    // 保存共享客户端以便后续 SSO 复用
                    if (sharedClient == null) sharedClient = login.client
                    // 保存 visitorId 以便后续系统复用
                    if (firstVisitorId == null) firstVisitorId = login.fpVisitorId
                    // 缓存 RSA 公钥
                    if (cachedRsaKey == null) cachedRsaKey = login.getRsaPublicKey()
                    cache(login, savedUsername)
                    login
                } else if (result.state == LoginState.REQUIRE_ACCOUNT_CHOICE) {
                    // 有账户选择的情况，默认选本科
                    val finalResult = login.login(accountType = XJTULogin.AccountType.UNDERGRADUATE)
                    if (finalResult.state == LoginState.SUCCESS) {
                        if (sharedClient == null) sharedClient = login.client
                        if (firstVisitorId == null) firstVisitorId = login.fpVisitorId
                        if (cachedRsaKey == null) cachedRsaKey = login.getRsaPublicKey()
                        cache(login, savedUsername)
                        login
                    } else null
                } else null
            }
        } catch (_: Exception) { null }
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
                // 确保首次登录时 sharedClient 使用 PersistentCookieJar
                if (sharedClient == null) {
                    persistentCookieJar?.let { jar ->
                        sharedClient = okhttp3.OkHttpClient.Builder()
                            .addInterceptor(okhttp3.brotli.BrotliInterceptor)
                            .cookieJar(jar)
                            .followRedirects(true).followSslRedirects(true)
                            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
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
        ywtbUserInfo = null
        firstVisitorId = null
        cachedRsaKey = null
        persistentCookieJar?.clear()
        store?.clear()
    }
}

// ── 主导航 ────────────────────────────────

@Composable
fun AppNavigation(onReady: () -> Unit = {}) {
    val navController = rememberNavController()
    val loginState = remember { AppLoginState() }
    val context = LocalContext.current
    val credentialStore = remember { CredentialStore(context) }
    val persistentCookieJar = remember { com.xjtu.toolbox.util.PersistentCookieJar(context) }

    // 注入持久化 CookieJar
    LaunchedEffect(persistentCookieJar) {
        loginState.persistentCookieJar = persistentCookieJar
    }

    // 恢复凭据并自动初始化（Splash 只做启动，登录在主界面后台进行）
    var isRestoring by remember { mutableStateOf(false) }
    var restoreStep by remember { mutableStateOf("") }
    val restoreScope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        loginState.restoreCredentials(credentialStore)
        // Splash 立即消失，进入主界面
        onReady()

        if (loginState.hasCredentials && !loginState.isLoggedIn) {
            isRestoring = true
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val startTime = System.currentTimeMillis()
                    android.util.Log.d("Restore", "开始恢复登录（并行模式）...")

                    // Phase 1: JWXT + 网络检测 并行
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

                    // Phase 2: WebVPN（仅校外）
                    if (loginState.isOnCampus == false) {
                        restoreStep = "正在连接 VPN..."
                        val vpnOk = loginState.loginWebVpn()
                        android.util.Log.d("Restore", "Phase2 ${System.currentTimeMillis() - startTime}ms: VPN=$vpnOk")
                    }

                    android.util.Log.d("Restore", "核心恢复完成 ${System.currentTimeMillis() - startTime}ms, isLoggedIn=${loginState.isLoggedIn}")
                } catch (e: Exception) {
                    android.util.Log.e("Restore", "恢复登录失败", e)
                }
            }
            isRestoring = false

            // Phase 3: 并行贯通所有剩余子系统（不阻塞 UI）
            restoreScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    kotlinx.coroutines.coroutineScope {
                        launch { loginState.autoLogin(LoginType.JWAPP) }
                        launch { loginState.autoLogin(LoginType.YWTB) }
                        launch { loginState.autoLogin(LoginType.ATTENDANCE) }
                        launch { loginState.autoLogin(LoginType.LIBRARY) }
                    }
                    android.util.Log.d("Restore", "全部子系统贯通完成: ${loginState.loginCount} 个系统")
                    // 持久化首次获取的 fpVisitorId 和 RSA 公钥
                    loginState.persistCredentials(credentialStore)
                    if (loginState.ywtbLogin != null && loginState.ywtbUserInfo == null) {
                        val api = com.xjtu.toolbox.ywtb.YwtbApi(loginState.ywtbLogin!!)
                        loginState.ywtbUserInfo = api.getUserInfo()
                    }
                } catch (e: Exception) {
                    android.util.Log.e("Restore", "子系统贯通失败", e)
                }
            }
        }
    }

    // 恢复中显示加载遮罩
    if (isRestoring) {
        androidx.compose.ui.window.Dialog(onDismissRequest = {}) {
            Card(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(restoreStep.ifEmpty { "正在恢复登录..." }, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = Routes.MAIN,
        enterTransition = { androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(300)) +
            androidx.compose.animation.slideInHorizontally(initialOffsetX = { it / 4 }, animationSpec = androidx.compose.animation.core.tween(300)) },
        exitTransition = { androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(200)) },
        popEnterTransition = { androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(300)) },
        popExitTransition = { androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(200)) +
            androidx.compose.animation.slideOutHorizontally(targetOffsetX = { it / 4 }, animationSpec = androidx.compose.animation.core.tween(200)) }
    ) {

        composable(Routes.MAIN) {
            MainScreen(navController = navController, loginState = loginState, credentialStore = credentialStore)
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

        composable(Routes.EMPTY_ROOM) { EmptyRoomScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.NOTIFICATION) { NotificationScreen(onBack = { navController.popBackStack() }, onNavigate = { navController.navigate(it) { launchSingleTop = true } }) }
        composable(Routes.ATTENDANCE) { loginState.attendanceLogin?.let { AttendanceScreen(login = it, onBack = { navController.popBackStack() }) } }
        composable(Routes.SCHEDULE) { loginState.jwxtLogin?.let { ScheduleScreen(login = it, studentId = loginState.activeUsername, onBack = { navController.popBackStack() }) } }
        composable(Routes.JWAPP_SCORE) { loginState.jwappLogin?.let { JwappScoreScreen(login = it, jwxtLogin = loginState.jwxtLogin, studentId = loginState.activeUsername, onBack = { navController.popBackStack() }) } }
        composable(Routes.JUDGE) { loginState.jwxtLogin?.let { JudgeScreen(login = it, username = loginState.activeUsername, onBack = { navController.popBackStack() }) } }
        composable(Routes.LIBRARY) { loginState.libraryLogin?.let { LibraryScreen(login = it, onBack = { navController.popBackStack() }) } }
        composable(Routes.CAMPUS_CARD) { loginState.campusCardLogin?.let { com.xjtu.toolbox.card.CampusCardScreen(login = it, onBack = { navController.popBackStack() }) } }
        composable(Routes.SCORE_REPORT) { loginState.jwxtLogin?.let { ScoreReportScreen(login = it, studentId = loginState.activeUsername, onBack = { navController.popBackStack() }) } }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(navController: NavHostController, loginState: AppLoginState, credentialStore: CredentialStore) {
    var selectedTabOrdinal by rememberSaveable { mutableIntStateOf(0) }
    val selectedTab = BottomTab.entries[selectedTabOrdinal.coerceIn(0, BottomTab.entries.size - 1)]
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 返回键：非 HOME 标签先回到 HOME；HOME 标签最小化 App
    BackHandler {
        if (selectedTab != BottomTab.HOME) {
            selectedTabOrdinal = BottomTab.HOME.ordinal
        } else {
            (context as? android.app.Activity)?.moveTaskToBack(true)
        }
    }

    // 自动登录状态
    var isAutoLogging by remember { mutableStateOf(false) }
    var autoLoginMessage by remember { mutableStateOf("") }

    fun navigateWithLogin(target: String, type: LoginType) {
        if (loginState.getCached(type) != null) {
            navController.navigate(target) { launchSingleTop = true }
        } else if (loginState.hasCredentials) {
            // 有保存的凭据，尝试自动登录
            isAutoLogging = true
            autoLoginMessage = "正在自动登录${type.label}..."
            scope.launch {
                val result = loginState.autoLogin(type)
                isAutoLogging = false
                if (result != null) {
                    navController.navigate(target) { launchSingleTop = true }
                } else {
                    // 自动登录失败，跳到"我的"页面提示重新登录
                    selectedTabOrdinal = BottomTab.PROFILE.ordinal
                }
            }
        } else {
            // 没有凭据，切换到"我的"标签页让用户登录
            selectedTabOrdinal = BottomTab.PROFILE.ordinal
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        when (selectedTab) {
                            BottomTab.HOME -> "XJTU 工具箱"
                            BottomTab.ACADEMIC -> "教务服务"
                            BottomTab.TOOLS -> "实用工具"
                            BottomTab.PROFILE -> "我的"
                        },
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar {
                BottomTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTabOrdinal = tab.ordinal },
                        icon = {
                            Icon(
                                if (selectedTab == tab) tab.selectedIcon else tab.unselectedIcon,
                                contentDescription = tab.label
                            )
                        },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "tabContent"
            ) { tab ->
                when (tab) {
                    BottomTab.HOME -> HomeTab(loginState, onNavigate = { navController.navigate(it) { launchSingleTop = true } }, onNavigateWithLogin = ::navigateWithLogin)
                    BottomTab.ACADEMIC -> AcademicTab(loginState, ::navigateWithLogin)
                    BottomTab.TOOLS -> ToolsTab { navController.navigate(it) { launchSingleTop = true } }
                    BottomTab.PROFILE -> ProfileTab(loginState, ::navigateWithLogin, credentialStore)
                }
            }

            // 自动登录加载遮罩
            if (isAutoLogging) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f)
                ) {
                    Column(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(12.dp))
                        Text(autoLoginMessage, color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.bodyMedium)
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
    onNavigate: (String) -> Unit,
    onNavigateWithLogin: (String, LoginType) -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(8.dp))

        // 欢迎卡片
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    if (loginState.isLoggedIn) {
                        val name = loginState.ywtbUserInfo?.userName
                        if (!name.isNullOrBlank()) {
                            val surname = name.first()
                            "欢迎, ${surname}${surname}宝宝"
                        } else "欢迎, ${loginState.activeUsername}"
                    } else "岱宗盒子",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (loginState.isLoggedIn) "已登录 ${loginState.loginCount} 个系统" else "登录后可使用全部功能",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // 快捷入口
        Text("快捷入口", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            QuickEntryItem(Icons.Default.CalendarMonth, "课表", MaterialTheme.colorScheme.primary) { onNavigateWithLogin(Routes.SCHEDULE, LoginType.JWXT) }
            QuickEntryItem(Icons.Default.Assessment, "成绩", MaterialTheme.colorScheme.tertiary) { onNavigateWithLogin(Routes.JWAPP_SCORE, LoginType.JWAPP) }
            QuickEntryItem(Icons.Default.LocationOn, "空教室", MaterialTheme.colorScheme.secondary) { onNavigate(Routes.EMPTY_ROOM) }
            QuickEntryItem(Icons.Default.Notifications, "通知", MaterialTheme.colorScheme.error) { onNavigate(Routes.NOTIFICATION) }
        }

        Spacer(Modifier.height(24.dp))

        // 全部服务网格
        Text("全部服务", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))

        val features = listOf(
            Triple(Icons.Default.LocationOn, "空闲教室", "无需登录") to { onNavigate(Routes.EMPTY_ROOM) },
            Triple(Icons.Default.Notifications, "通知公告", "无需登录") to { onNavigate(Routes.NOTIFICATION) },
            Triple(Icons.Default.DateRange, "考勤查询", loginHint(loginState.attendanceLogin)) to { onNavigateWithLogin(Routes.ATTENDANCE, LoginType.ATTENDANCE) },
            Triple(Icons.Default.CalendarMonth, "课表考试", loginHint(loginState.jwxtLogin)) to { onNavigateWithLogin(Routes.SCHEDULE, LoginType.JWXT) },
            Triple(Icons.Default.Assessment, "成绩查询", loginHint(loginState.jwappLogin)) to { onNavigateWithLogin(Routes.JWAPP_SCORE, LoginType.JWAPP) },
            Triple(Icons.Default.RateReview, "本科评教", loginHint(loginState.jwxtLogin)) to { onNavigateWithLogin(Routes.JUDGE, LoginType.JWXT) },
            Triple(Icons.Default.Chair, "图书馆座位", loginHint(loginState.libraryLogin)) to { onNavigateWithLogin(Routes.LIBRARY, LoginType.LIBRARY) }
        )

        features.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (info, action) ->
                    ServiceGridItem(info.first, info.second, info.third, action, Modifier.weight(1f))
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ══════════════════════════════════════════
//  Tab 2 — 教务
// ══════════════════════════════════════════

@Composable
private fun AcademicTab(loginState: AppLoginState, onNavigateWithLogin: (String, LoginType) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(12.dp))

        SectionLabel("本科生")
        ServiceCard(Icons.Default.CalendarMonth, "课表 / 考试", "课表安排 · 考试时间 · 教材查询", loginState.jwxtLogin != null) { onNavigateWithLogin(Routes.SCHEDULE, LoginType.JWXT) }
        ServiceCard(Icons.Default.Assessment, "成绩查询", "查看成绩 / GPA / 含报表补充", loginState.jwappLogin != null) { onNavigateWithLogin(Routes.JWAPP_SCORE, LoginType.JWAPP) }
        ServiceCard(Icons.Default.DateRange, "考勤查询", "查看进出校园记录", loginState.attendanceLogin != null) { onNavigateWithLogin(Routes.ATTENDANCE, LoginType.ATTENDANCE) }
        ServiceCard(Icons.Default.RateReview, "本科评教", "一键自动评教", loginState.jwxtLogin != null) { onNavigateWithLogin(Routes.JUDGE, LoginType.JWXT) }

        Spacer(Modifier.height(16.dp))

        SectionLabel("校园生活")
        ServiceCard(Icons.Default.Chair, "图书馆座位", "座位查询与一键预约", loginState.libraryLogin != null) { onNavigateWithLogin(Routes.LIBRARY, LoginType.LIBRARY) }
        ServiceCard(Icons.Default.CreditCard, "校园卡", "余额查询 / 消费账单 / 分析", loginState.campusCardLogin != null) { onNavigateWithLogin(Routes.CAMPUS_CARD, LoginType.CAMPUS_CARD) }

        Spacer(Modifier.height(24.dp))
    }
}

// ══════════════════════════════════════════
//  Tab 3 — 工具
// ══════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolsTab(onNavigate: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(12.dp))

        SectionLabel("校园查询")
        ServiceCard(Icons.Default.LocationOn, "空闲教室", "查询各校区各时段空闲教室 · 无需登录", false) { onNavigate(Routes.EMPTY_ROOM) }

        Spacer(Modifier.height(16.dp))

        SectionLabel("信息获取")
        ServiceCard(Icons.Default.Notifications, "通知公告", "教务处 / 研究生院 / 物理学院通知 · 无需登录", false) { onNavigate(Routes.NOTIFICATION) }

        Spacer(Modifier.height(16.dp))

        SectionLabel("实用工具")
        // WebVPN 网址互转
        var inputUrl by remember { mutableStateOf("") }
        var convertedUrl by remember { mutableStateOf("") }
        var isReversed by remember { mutableStateOf(false) } // false=原始→VPN, true=VPN→原始
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("WebVPN 网址互转", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))

                // 方向切换
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !isReversed,
                        onClick = { isReversed = false; convertedUrl = "" },
                        shape = SegmentedButtonDefaults.itemShape(0, 2)
                    ) { Text("原始 → VPN", fontSize = 12.sp, maxLines = 1) }
                    SegmentedButton(
                        selected = isReversed,
                        onClick = { isReversed = true; convertedUrl = "" },
                        shape = SegmentedButtonDefaults.itemShape(1, 2)
                    ) { Text("VPN → 原始", fontSize = 12.sp, maxLines = 1) }
                }

                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = inputUrl,
                    onValueChange = { inputUrl = it; convertedUrl = "" },
                    label = { Text(if (!isReversed) "校内网址" else "WebVPN 网址") },
                    placeholder = { Text(if (!isReversed) "http://bkkq.xjtu.edu.cn/..." else "https://webvpn.xjtu.edu.cn/...") },
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
                        Text(convertedUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    // 如果是有效 URL，提供内置浏览器跳转按钮
                    if (convertedUrl.startsWith("http")) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { onNavigate(Routes.browser(convertedUrl)) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("在内置浏览器中打开")
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ══════════════════════════════════════════
//  Tab 4 — 我的（含统一登录）
// ══════════════════════════════════════════

@Composable
private fun ProfileTab(loginState: AppLoginState, onNavigateWithLogin: (String, LoginType) -> Unit, credentialStore: CredentialStore) {
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
            android.util.Log.d("Login", "核心登录完成 ${System.currentTimeMillis() - startMs}ms")

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
                    android.util.Log.d("Login", "后台贯通完成: ${loginState.loginCount} 个系统")
                } catch (_: Exception) { }
            }
        }
    }

    // ── MFA 两步验证对话框 ──
    if (mfaLogin != null) {
        AlertDialog(
            onDismissRequest = {
                mfaLogin = null
                mfaCode = ""
                mfaError = null
            },
            title = { Text("两步验证") },
            text = {
                Column {
                    Text("需要短信验证码完成登录", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("手机号: $mfaPhone", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(12.dp))

                    // 发送验证码按钮
                    if (!mfaCodeSent) {
                        Button(
                            onClick = {
                                mfaSending = true
                                mfaError = null
                                scope.launch {
                                    try {
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            mfaLogin!!.mfaContext!!.sendVerifyCode()
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
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(if (mfaSending) "发送中..." else "发送验证码")
                        }
                    } else {
                        // 验证码输入
                        OutlinedTextField(
                            value = mfaCode,
                            onValueChange = { mfaCode = it; mfaError = null },
                            label = { Text("验证码") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            isError = mfaError != null,
                            supportingText = mfaError?.let { { Text(it) } }
                        )
                    }
                }
            },
            confirmButton = {
                if (mfaCodeSent) {
                    Button(
                        onClick = { finishMfaAndLogin() },
                        enabled = mfaCode.length >= 4 && !mfaVerifying
                    ) {
                        if (mfaVerifying) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(if (mfaVerifying) "验证中..." else "验证并登录")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    mfaLogin = null
                    mfaCode = ""
                    mfaError = null
                }) { Text("取消") }
            }
        )
    }

    // ── UI ──
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // ━━ Hero Header ━━
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                                MaterialTheme.colorScheme.surface
                            )
                        )
                    )
                    .padding(horizontal = 24.dp, vertical = 36.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Avatar
                    Surface(
                        modifier = Modifier.size(72.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        tonalElevation = 4.dp
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (loginState.isLoggedIn) {
                                val initial = loginState.ywtbUserInfo?.userName?.firstOrNull()?.toString()
                                    ?: loginState.activeUsername.take(1)
                                Text(
                                    initial,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            } else {
                                Icon(Icons.Outlined.Person, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onPrimary)
                            }
                        }
                    }
                    Spacer(Modifier.width(20.dp))
                    Column {
                        if (loginState.isLoggedIn) {
                            Text(
                                loginState.ywtbUserInfo?.userName ?: loginState.activeUsername,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(2.dp))
                            loginState.ywtbUserInfo?.let { info ->
                                Text(
                                    "${info.identityTypeName} · ${info.organizationName}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            } ?: run {
                                Text(
                                    "已登录 ${loginState.loginCount} 个服务",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            Text("XJTU 工具箱", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("登录以使用全部功能", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Column(Modifier.padding(24.dp)) {
                        Text(
                            "统一身份认证",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "单点登录 · CAS SSO · 所有服务一键接入",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(Modifier.height(20.dp))

                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it; loginError = null },
                            label = { Text("学号 / 手机号") },
                            singleLine = true,
                            enabled = !isLoggingIn,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                        )
                        Spacer(Modifier.height(12.dp))
                        var passwordVisible by remember { mutableStateOf(false) }
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it; loginError = null },
                            label = { Text("密码") },
                            singleLine = true,
                            enabled = !isLoggingIn,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            visualTransformation = if (passwordVisible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            isError = loginError != null,
                            supportingText = loginError?.let { { Text(it) } },
                            trailingIcon = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(
                                        if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                        contentDescription = if (passwordVisible) "隐藏密码" else "显示密码"
                                    )
                                }
                            }
                        )

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
                            enabled = !isLoggingIn,
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            if (isLoggingIn) {
                                CircularProgressIndicator(
                                    Modifier.size(20.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(loginStage)
                            } else {
                                Text("登录", style = MaterialTheme.typography.titleSmall)
                            }
                        }

                        // 进度条
                        if (isLoggingIn) {
                            Spacer(Modifier.height(16.dp))
                            val animatedProgress by animateFloatAsState(
                                targetValue = loginProgress,
                                animationSpec = tween(durationMillis = 300),
                                label = "loginProgress"
                            )
                            LinearProgressIndicator(
                                progress = { animatedProgress },
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                            )
                        }

                        Spacer(Modifier.height(12.dp))
                        Text(
                            "密码仅用于本地加密后发送至学校 CAS 服务器",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        // ━━ 已登录 → 个人信息 + 系统状态 ━━
        if (loginState.isLoggedIn) {
            Column(Modifier.padding(horizontal = 20.dp)) {

                // 学号信息卡片（仅当有 YWTB 信息时）
                loginState.ywtbUserInfo?.let { info ->
                    if (info.userUid.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                            )
                        ) {
                            Column(Modifier.padding(20.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Badge, null,
                                        Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "个人信息",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                                Spacer(Modifier.height(12.dp))
                                ProfileInfoItem("学号", info.userUid)
                                ProfileInfoItem("姓名", info.userName)
                                ProfileInfoItem("身份", info.identityTypeName)
                                ProfileInfoItem("部门", info.organizationName)
                            }
                        }
                    }
                } ?: run {
                    if (loginState.ywtbLogin != null) {
                        LaunchedEffect(loginState.ywtbLogin) {
                            try {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    val api = com.xjtu.toolbox.ywtb.YwtbApi(loginState.ywtbLogin!!)
                                    loginState.ywtbUserInfo = api.getUserInfo()
                                }
                            } catch (_: Exception) { }
                        }
                    }
                }

                // 学期 / 教学周 信息
                if (loginState.ywtbLogin != null) {
                    var currentWeekText by remember { mutableStateOf<String?>(null) }
                    var termText by remember { mutableStateOf<String?>(null) }
                    LaunchedEffect(loginState.ywtbLogin) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            try {
                                val api = com.xjtu.toolbox.ywtb.YwtbApi(loginState.ywtbLogin!!)
                                val now = java.time.LocalDate.now()
                                val y = now.year; val m = now.monthValue
                                val term = if (m in 2..7) "${y - 1}-$y-2" else "$y-${y + 1}-1"
                                termText = term.replace("-", " ~ ").replaceFirst(" ~ ", "-").let {
                                    if (term.endsWith("-1")) "第一学期" else "第二学期"
                                }
                                val startStr = api.getStartOfTerm(term)
                                val start = java.time.LocalDate.parse(startStr)
                                val days = java.time.temporal.ChronoUnit.DAYS.between(start, now)
                                if (days < 0) {
                                    currentWeekText = "距开学 ${-days} 天"
                                } else {
                                    val w = ((days / 7) + 1).toInt()
                                    if (w in 1..30) currentWeekText = "第${w}周"
                                }
                            } catch (_: Exception) { }
                        }
                    }
                    if (currentWeekText != null || termText != null) {
                        Spacer(Modifier.height(12.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f))
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(20.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.CalendarMonth, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("学期信息", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    termText?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                                currentWeekText?.let {
                                    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f)) {
                                        Text(it, Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // 网络状态
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val (networkIcon, networkText, networkColor) = when (loginState.isOnCampus) {
                            true -> Triple(Icons.Default.Wifi, "校内直连", MaterialTheme.colorScheme.primary)
                            false -> Triple(Icons.Default.VpnKey, "WebVPN 代理", MaterialTheme.colorScheme.tertiary)
                            null -> Triple(Icons.Default.WifiFind, "检测中...", MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(networkIcon, null, Modifier.size(20.dp), tint = networkColor)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("网络模式", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text("其他服务将在使用时自动连接", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Surface(shape = RoundedCornerShape(6.dp), color = networkColor.copy(alpha = 0.12f)) {
                            Text(networkText, Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall, color = networkColor)
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))

                // 退出按钮
                var showLogoutDialog by remember { mutableStateOf(false) }
                OutlinedButton(
                    onClick = { showLogoutDialog = true },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f)
                    )
                ) {
                    Icon(Icons.AutoMirrored.Filled.Logout, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("退出登录")
                }
                if (showLogoutDialog) {
                    AlertDialog(
                        onDismissRequest = { showLogoutDialog = false },
                        title = { Text("确认退出") },
                        text = { Text("退出登录后需要重新输入凭据。确定要退出吗？") },
                        confirmButton = {
                            TextButton(onClick = {
                                showLogoutDialog = false
                                loginState.logout(credentialStore)
                            }) { Text("退出", color = MaterialTheme.colorScheme.error) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showLogoutDialog = false }) { Text("取消") }
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── 关于 ──
        val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column {
                // 顶部：应用信息（带背景色块）
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
                                )
                            )
                        )
                        .padding(20.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            modifier = Modifier.size(48.dp)
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Terrain, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("岱宗盒子", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.weight(1f))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                        ) {
                            Text(
                                "Alpha",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                // 作者信息
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { uriHandler.openUri("https://www.runqinliu666.cn/") }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Person, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(10.dp))
                    Text("Yeliqin666", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text("runqinliu666.cn", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(4.dp))
                    @Suppress("DEPRECATION")
                    Icon(Icons.Default.OpenInNew, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                }

                HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                // 开发计划
                Column(Modifier.padding(20.dp)) {
                    Text(
                        "开发计划",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )
                    val plans = listOf(
                        "图书馆定时抢座",
                        "空闲区域分析 & 座位推荐",
                        "智能抢课",
                        "等级制课程更精准获取",
                        "通知聚合订阅 & Push",
                        "电子成绩单获取与签印分析",
                        "思源学堂解析版"
                    )
                    plans.forEachIndexed { idx, plan ->
                        Row(
                            Modifier.padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(22.dp)
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        "${idx + 1}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(plan, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
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
                    if (isActive) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant
                )
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = if (isActive) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHighest
        ) {
            Text(
                if (isActive) "已连接" else "离线",
                Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelSmall,
                color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ProfileInfoItem(label: String, value: String) {
    if (value.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f),
            modifier = Modifier.width(48.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

// ══════════════════════════════════════════
//  通用组件
// ══════════════════════════════════════════

private fun loginHint(login: Any?): String = if (login != null) "已登录" else "需登录"

@Composable
private fun QuickEntryItem(icon: ImageVector, label: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Box(
            Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(color.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ServiceGridItem(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier.height(96.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
}

@Composable
private fun ServiceCard(icon: ImageVector, title: String, description: String, loggedIn: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (loggedIn) {
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                    Text("已登录", Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            } else {
                Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// Old ProfileInfoRow and LoginStatusRow removed — replaced by ProfileInfoItem and StatusListItem