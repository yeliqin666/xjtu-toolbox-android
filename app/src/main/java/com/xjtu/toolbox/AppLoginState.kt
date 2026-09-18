package com.xjtu.toolbox

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.navigation.compose.dialog
import com.xjtu.toolbox.auth.*
import com.xjtu.toolbox.card.putTodaySummary
import com.xjtu.toolbox.util.CredentialStore
import com.xjtu.toolbox.widget.CampusCardWidgetUpdater

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
            b.markWebVpnStale()
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

    /**
     * 登录前 / 徽标为空时探测校园网。有缓存就复用，避免和首次登录并行走两次探针。
     */
    override suspend fun ensureCampusDetected() {
        campusDetectMutex.withLock {
            val cached = isOnCampus
            if (cached != null && System.currentTimeMillis() - campusDetectTime < CAMPUS_CACHE_MS) {
                return
            }
            onNetworkChanged()
        }
    }
    var isOnCampus by mutableStateOf<Boolean?>(null)   // null=未检测, true=校内, false=校外
    // [已移除] webVpnLoggedIn：网关登录态改读 SessionBackend.webvpnSelfLoggedIn，避免两处状态漂移。

    // 网络检测结果缓存（10 分钟）
    private var campusDetectTime: Long = 0L
    private val CAMPUS_CACHE_MS = 10 * 60 * 1000L
    private val campusDetectMutex = Mutex()

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
        webVpnBackend?.markWebVpnStale()
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
        // 先改全局账号、再改可观察的 accountId：界面按 accountId 重建 DataCache 等按账号绑定的
        // 对象时，全局上下文必须已经是新账号（本函数可能在后台线程执行，中间可能插进一次重组）。
        com.xjtu.toolbox.account.AccountContext.activeAccountId = account.accountId
        accountId = account.accountId
        savedUsername = account.accountId
        savedPassword = account.password
        activeUsername = account.accountId
        accountType = account.accountType
        cachedNickname = account.nickname
        firstVisitorId = account.fpVisitorId
        cachedRsaKey = account.rsaPublicKey
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
        // 后台任务复用这一份，别另起一个抢同一批 cookie 文件
        com.xjtu.toolbox.auth.SessionManager.active = sessionManager
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
            register(com.xjtu.toolbox.auth.NewAttendanceSession())
            register(com.xjtu.toolbox.auth.CampusCardSession())
            register(com.xjtu.toolbox.auth.FitnessSession())
            register(com.xjtu.toolbox.auth.IclassfaceSession())
            register(com.xjtu.toolbox.auth.HelloSession())
        }
        // 绑定 AccountManager 到 sessionManager + loginState
        accountManager.sessionManager = sessionManager
        accountManager.holder = loginState

        // 保活：每轮对已登录站点做免密 SSO 续期（静默，撞 MFA 即退出）。
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

/**
 * 抓一次校园卡余额与今日流水写进缓存。
 *
 * internal 而非 private：[com.xjtu.toolbox.home.HomeStatsRefresher] 现在也调它，
 * 好让校园卡和别的首页数据源共用同一套 TTL / 退避 / 串行节奏，
 * 而不是像以前那样在 ON_RESUME 里另起一条只有 60 秒节流的独立路径。
 */
internal suspend fun refreshCampusCardCache(
    context: android.content.Context,
    site: com.xjtu.toolbox.auth.SiteSession
): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val appContext = context.applicationContext
    // 请求发出前定下账号：结果回来时可能已切到别的账号
    val accountId = com.xjtu.toolbox.account.AccountContext.activeAccountId
    val api = com.xjtu.toolbox.card.CampusCardApi(site)
    val info = api.getCardInfo()
    val (_, recentTx) = api.getTransactions(page = 1, pageSize = 50)

    com.xjtu.toolbox.card.CampusCardCache.cardPrefs(appContext, accountId).edit()
        .putFloat("card_balance_cache", info.balance.toFloat())
        .putString("card_name_cache", info.name)
        .putLong("card_cache_time", System.currentTimeMillis())
        .putTodaySummary(com.xjtu.toolbox.card.todaySummaryOf(recentTx))
        .apply()
    CampusCardWidgetUpdater.requestUpdate(appContext)
    true
}
