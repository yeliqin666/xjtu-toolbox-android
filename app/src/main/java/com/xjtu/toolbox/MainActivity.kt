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
import androidx.compose.foundation.BorderStroke
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
import androidx.navigation.compose.dialog
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
    const val CURRICULUM = "curriculum"
    const val PAYMENT_CODE = "payment_code"
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
        // 恢复凭据（非 suspend，可在 init 调用）
        loginState.restoreCredentials(credentialStore)
    }
}

// ── 主导航 ────────────────────────────────

@Composable
fun AppNavigation(onReady: () -> Unit = {}) {
    val navController = rememberNavController()
    // [VM] ViewModel 保证状态跨 Configuration Change 存活
    val viewModel: AppLoginStateViewModel = viewModel()
    val loginState = viewModel.loginState
    val credentialStore = viewModel.credentialStore
    val context = LocalContext.current

    // 当 YWTB 用户信息获取到时，自动缓存昵称（下次启动秒显示）
    LaunchedEffect(loginState.ywtbUserInfo) {
        val name = loginState.ywtbUserInfo?.userName
        if (!name.isNullOrBlank() && name.length >= 2) {
            val givenName = name.drop(1)
            val suffix = if (givenName.length >= 2) "宝宝" else "宝"
            val nick = "$givenName$suffix"
            loginState.cachedNickname = nick
            credentialStore.saveNickname(nick)
        }
    }

    // [C1] Lifecycle Observer：App 从后台恢复时 proactive 刷新即将过期的 token
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    val lifecycleScope = rememberCoroutineScope()
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && loginState.isLoggedIn) {
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
    LaunchedEffect(Unit) {
        // [VM] restoreCredentials 已在 ViewModel init 中完成，无需重复调用
        // Splash 立即消失，进入主界面
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
                } catch (e: Exception) {
                    android.util.Log.e("Restore", "后台贯通失败", e)
                }
            }
        }
    }

    // 非阻塞提示条在 MainScreen 底部显示

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

        composable(Routes.EMPTY_ROOM) { EmptyRoomScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.NOTIFICATION) { NotificationScreen(onBack = { navController.popBackStack() }, onNavigate = { navController.navigate(it) { launchSingleTop = true } }) }
        composable(Routes.ATTENDANCE) { loginState.attendanceLogin?.let { AttendanceScreen(login = it, onBack = { navController.popBackStack() }) } ?: LaunchedEffect(Unit) { navController.popBackStack() } }
        composable(Routes.SCHEDULE) { ScheduleScreen(login = loginState.jwxtLogin, studentId = loginState.activeUsername, onBack = { navController.popBackStack() }) }
        composable(Routes.JWAPP_SCORE) { JwappScoreScreen(login = loginState.jwappLogin, jwxtLogin = loginState.jwxtLogin, studentId = loginState.activeUsername, onBack = { navController.popBackStack() }) }
        composable(Routes.JUDGE) { loginState.jwxtLogin?.let { JudgeScreen(login = it, username = loginState.activeUsername, onBack = { navController.popBackStack() }) } ?: LaunchedEffect(Unit) { navController.popBackStack() } }
        composable(Routes.LIBRARY) { loginState.libraryLogin?.let { LibraryScreen(login = it, onBack = { navController.popBackStack() }) } ?: LaunchedEffect(Unit) { navController.popBackStack() } }
        composable(Routes.CAMPUS_CARD) { loginState.campusCardLogin?.let { com.xjtu.toolbox.card.CampusCardScreen(login = it, onBack = { navController.popBackStack() }) } ?: LaunchedEffect(Unit) { navController.popBackStack() } }
        dialog(
            Routes.PAYMENT_CODE,
            dialogProperties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnClickOutside = false  // 防止加载中误触关闭
            )
        ) {
            val client = loginState.getSharedClient()
            if (client != null) {
                com.xjtu.toolbox.pay.PaymentCodeDialog(client = client, onDismiss = { navController.popBackStack() })
            } else {
                LaunchedEffect(Unit) { navController.popBackStack() }
            }
        }
        composable(Routes.SCORE_REPORT) { loginState.jwxtLogin?.let { ScoreReportScreen(login = it, studentId = loginState.activeUsername, onBack = { navController.popBackStack() }) } ?: LaunchedEffect(Unit) { navController.popBackStack() } }
        composable(Routes.CURRICULUM) { loginState.jwxtLogin?.let { com.xjtu.toolbox.jwapp.CurriculumScreen(jwxtLogin = it, jwappLogin = loginState.jwappLogin, studentId = loginState.activeUsername, onBack = { navController.popBackStack() }) } ?: LaunchedEffect(Unit) { navController.popBackStack() } }
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
private fun MainScreen(navController: NavHostController, loginState: AppLoginState, credentialStore: CredentialStore, isRestoring: Boolean = false, restoreStep: String = "") {
    var selectedTabOrdinal by rememberSaveable { mutableIntStateOf(0) }
    val selectedTab = BottomTab.entries[selectedTabOrdinal.coerceIn(0, BottomTab.entries.size - 1)]
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 返回键：非 HOME 标签先回到 HOME；HOME 标签双击退出 App
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
    var isAutoLogging by remember { mutableStateOf(false) }
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
            isAutoLogging = true
            autoLoginMessage = "正在自动登录${type.label}..."
            autoLoginJob?.cancel() // 取消旧的登录任务，避免竞态
            autoLoginJob = scope.launch {
                try {
                    val result = kotlinx.coroutines.withTimeoutOrNull(15_000L) {
                        loginState.autoLogin(type)
                    }
                    isAutoLogging = false
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
                    isAutoLogging = false
                    autoLoginJob = null
                    // 离线可用路由降级
                    if (target in offlineCapableRoutes) {
                        navController.navigate(target) { launchSingleTop = true }
                        val msg = "网络不佳，进入离线模式"
                        scope.launch { snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short) }
                    } else {
                        val msg = when (e) {
                            is java.io.IOException -> "网络不佳，请检查网络连接"
                            else -> "${type.label}登录异常: ${e.message?.take(30)}"
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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                transitionSpec = {
                    val goingRight = targetState.ordinal > initialState.ordinal
                    (slideInHorizontally(tween(280)) { if (goingRight) it else -it } + fadeIn(tween(280))) togetherWith
                    (slideOutHorizontally(tween(280)) { if (goingRight) -it else it } + fadeOut(tween(180)))
                },
                label = "tabContent"
            ) { tab ->
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
                when (tab) {
                    BottomTab.HOME -> HomeTab(loginState, isRestoring = isRestoring, onNavigate = onNavigateWithNetCheck, onNavigateWithLogin = ::navigateWithLogin, onNavigateToProfile = { selectedTabOrdinal = BottomTab.PROFILE.ordinal })
                    BottomTab.ACADEMIC -> AcademicTab(loginState, ::navigateWithLogin)
                    BottomTab.TOOLS -> ToolsTab(onNavigateWithNetCheck)
                    BottomTab.PROFILE -> ProfileTab(loginState, ::navigateWithLogin, credentialStore)
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
                    color = MaterialTheme.colorScheme.primaryContainer,
                    tonalElevation = 2.dp
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            restoreStep.ifEmpty { "正在恢复登录..." },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            // 自动登录加载遮罩（可取消，15s超时）
            if (isAutoLogging) {
                Surface(
                    modifier = Modifier.fillMaxSize().clickable {
                        // 点击空白区域取消
                        autoLoginJob?.cancel()
                        isAutoLogging = false
                        autoLoginJob = null
                    },
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
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(
                            onClick = {
                                autoLoginJob?.cancel()
                                isAutoLogging = false
                                autoLoginJob = null
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.inverseOnSurface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.5f))
                        ) {
                            Text("取消")
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
    onNavigateToProfile: () -> Unit = {}
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
                        if (!name.isNullOrBlank() && name.length >= 2) {
                            val givenName = name.drop(1)
                            val suffix = if (givenName.length >= 2) "宝宝" else "宝"
                            "欢迎, $givenName$suffix"
                        } else if (!loginState.cachedNickname.isNullOrBlank()) {
                            "欢迎, ${loginState.cachedNickname}"
                        } else "欢迎, ${loginState.activeUsername}"
                    } else "岱宗盒子",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (loginState.isLoggedIn) {
                        when {
                            loginState.loginCount > 0 -> "已登录 ${loginState.loginCount} 个系统"
                            isRestoring -> "正在连接..."
                            else -> "离线模式 · 部分功能可用"
                        }
                    } else "登录后可使用全部功能",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
                if (!loginState.isLoggedIn) {
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onNavigateToProfile,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            contentColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Icon(Icons.Default.AccountCircle, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("立即登录")
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // 快捷入口
        Text("快捷入口", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            QuickEntryItem(Icons.Default.CreditCard, "校园卡", MaterialTheme.colorScheme.secondary) { onNavigateWithLogin(Routes.CAMPUS_CARD, LoginType.CAMPUS_CARD) }
            QuickEntryItem(Icons.Default.CalendarMonth, "课表", MaterialTheme.colorScheme.primary) { onNavigateWithLogin(Routes.SCHEDULE, LoginType.JWXT) }
            QuickEntryItem(Icons.Default.QrCode, "付款码", MaterialTheme.colorScheme.tertiary) { onNavigateWithLogin(Routes.PAYMENT_CODE, LoginType.JWXT) }
            QuickEntryItem(Icons.Default.Notifications, "通知", MaterialTheme.colorScheme.error) { onNavigate(Routes.NOTIFICATION) }
        }

        Spacer(Modifier.height(24.dp))

        // 全部服务网格
        Text("全部服务", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))

        val features = listOf(
            Triple(Icons.Default.CreditCard, "校园卡", loginHint(loginState.campusCardLogin)) to { onNavigateWithLogin(Routes.CAMPUS_CARD, LoginType.CAMPUS_CARD) },
            Triple(Icons.Default.CalendarMonth, "课表考试", loginHint(loginState.jwxtLogin)) to { onNavigateWithLogin(Routes.SCHEDULE, LoginType.JWXT) },
            Triple(Icons.Default.Assessment, "成绩查询", loginHint(loginState.jwappLogin)) to { onNavigateWithLogin(Routes.JWAPP_SCORE, LoginType.JWAPP) },
            Triple(Icons.Default.QrCode, "付款码", loginHint(loginState.getSharedClient())) to { onNavigateWithLogin(Routes.PAYMENT_CODE, LoginType.JWXT) },
            Triple(Icons.Default.AccountTree, "培养进度", loginHint(loginState.jwxtLogin)) to { onNavigateWithLogin(Routes.CURRICULUM, LoginType.JWXT) },
            Triple(Icons.Default.DateRange, "考勤查询", loginHint(loginState.attendanceLogin)) to { onNavigateWithLogin(Routes.ATTENDANCE, LoginType.ATTENDANCE) },
            Triple(Icons.Default.RateReview, "本科评教", loginHint(loginState.jwxtLogin)) to { onNavigateWithLogin(Routes.JUDGE, LoginType.JWXT) },
            Triple(Icons.Default.Chair, "图书馆座位", loginHint(loginState.libraryLogin)) to { onNavigateWithLogin(Routes.LIBRARY, LoginType.LIBRARY) },
            Triple(Icons.Default.LocationOn, "空闲教室", "无需登录") to { onNavigate(Routes.EMPTY_ROOM) },
            Triple(Icons.Default.Notifications, "通知公告", "无需登录") to { onNavigate(Routes.NOTIFICATION) }
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
        ServiceCard(Icons.Default.AccountTree, "培养进度", "培养方案 · 选课进度 · 逾期提醒", loginState.jwxtLogin != null) { onNavigateWithLogin(Routes.CURRICULUM, LoginType.JWXT) }

        Spacer(Modifier.height(16.dp))

        SectionLabel("校园生活")
        ServiceCard(Icons.Default.CreditCard, "校园卡", "余额查询 / 消费账单 / 分析", loginState.campusCardLogin != null) { onNavigateWithLogin(Routes.CAMPUS_CARD, LoginType.CAMPUS_CARD) }
        ServiceCard(Icons.Default.QrCode, "付款码", "校园支付 · 点击即用", loginState.getSharedClient() != null) { onNavigateWithLogin(Routes.PAYMENT_CODE, LoginType.JWXT) }
        ServiceCard(Icons.Default.Chair, "图书馆座位", "座位查询与一键预约", loginState.libraryLogin != null) { onNavigateWithLogin(Routes.LIBRARY, LoginType.LIBRARY) }

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
                android.util.Log.w("Login", "Phase 1 并行异常: ${e.message}")
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
                                    if (loginState.loginCount > 0) "已登录 ${loginState.loginCount} 个服务" else "正在连接...",
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

                // 学期 / 教学周 信息（使用 JWXT，Phase1 即可用，比 YWTB 快 ~3s）
                if (loginState.jwxtLogin != null) {
                    var currentWeekText by remember { mutableStateOf<String?>(null) }
                    var termText by remember { mutableStateOf<String?>(null) }
                    var schoolYear by remember { mutableStateOf<String?>(null) }
                    var countdownText by remember { mutableStateOf<String?>(null) }
                    LaunchedEffect(loginState.jwxtLogin) {
                        android.util.Log.d("ProfileWeek", "LaunchedEffect fired, jwxtLogin=${loginState.jwxtLogin != null}")
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            try {
                                val api = com.xjtu.toolbox.schedule.ScheduleApi(loginState.jwxtLogin!!)
                                val termCode = api.getCurrentTerm()    // e.g. "2025-2026-2"
                                android.util.Log.d("ProfileWeek", "termCode=$termCode")
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
                                    android.util.Log.d("ProfileWeek", "startDate=$startDate, today=$today, rawWeek=$rawWeek")
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
                                } catch (e: Exception) {
                                    android.util.Log.w("ProfileWeek", "getStartOfTerm failed: ${e.message}")
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("ProfileWeek", "JWXT API failed: ${e.javaClass.simpleName}: ${e.message}")
                                val m = java.time.LocalDate.now().monthValue
                                termText = if (m in 2..7) "第二学期" else "第一学期"
                            }
                        }
                        android.util.Log.d("ProfileWeek", "done: currentWeekText=$currentWeekText, termText=$termText, schoolYear=$schoolYear, countdown=$countdownText")
                    }
                    if (currentWeekText != null || termText != null || schoolYear != null) {
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
                                    schoolYear?.let {
                                        Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    }
                                    termText?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    countdownText?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Medium)
                                    }
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

        // ── 关于 (Footer 风格，简洁不割裂) ──
        val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
        var plansExpanded by remember { mutableStateOf(false) }

        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 应用名 + 版本 一行搞定
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Terrain, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text("岱宗盒子", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text("v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.width(6.dp))
                Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)) {
                    Text("Alpha", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
                }
            }

            Spacer(Modifier.height(8.dp))

            // 作者 & 致谢 紧凑链接行
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "by Yeliqin666",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { uriHandler.openUri("https://www.runqinliu666.cn/") }
                )
                Text(" · ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { uriHandler.openUri("https://github.com/yan-xiaoo/XJTUToolBox") }
                ) {
                    Icon(Icons.Default.Favorite, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(3.dp))
                    Text("致谢 XJTUToolBox", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }

            Spacer(Modifier.height(10.dp))

            // 开发计划 - 可展开/收起
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.clickable { plansExpanded = !plansExpanded }
            ) {
                Column {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.RocketLaunch, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(6.dp))
                        Text("开发计划", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Icon(
                            if (plansExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            null,
                            Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    androidx.compose.animation.AnimatedVisibility(visible = plansExpanded) {
                        Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) {
                            val plans = listOf(
                                "图书馆定时抢座",
                                "空闲区域分析 & 座位推荐",
                                "智能抢课",
                                "通知聚合订阅 & Push",
                                "电子成绩单获取与签印分析",
                                "思源学堂解析版"
                            )
                            plans.forEachIndexed { idx, plan ->
                                Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("${idx + 1}.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.width(6.dp))
                                    Text(plan, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
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