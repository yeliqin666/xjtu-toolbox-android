package com.xjtu.toolbox.auth

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import com.xjtu.toolbox.auth.*
import com.xjtu.toolbox.data.CredentialStore

// ── 登录状态 ──────────────────────────────

class AppLoginState : com.xjtu.toolbox.account.AppLoginStateHolder {
    override var activeUsername by mutableStateOf("")
    // 会话真相唯一来源：sessionManager.getSite(siteKey)；cookie 只存在 SessionManager 的两个 backend 里。

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

    /** 设置项（连接模式、账号类型旧值）的来源，由 [AppLoginStateViewModel] 注入。 */
    var credentialStoreRef: CredentialStore? = null

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

    /**
     * 子系统检测到明确凭据无效时调用。重复调用幂等。
     *
     * 还没登录时（登录页输错密码）只上熔断、不弹"密码可能已变更"：登录页自己会显示错误，
     * 那个弹窗叫人"去设置里更新密码"，对刚输错一次的人没有意义。
     */
    fun reportPasswordInvalidated(siteName: String) {
        if (passwordInvalidatedLatch) return
        passwordInvalidatedLatch = true
        passwordInvalidatedSiteName = siteName
        passwordInvalidatedDialogVisible = isLoggedIn
        android.util.Log.w("AppLoginState", "password invalidated by site=$siteName")
    }

    // ── WebVPN：唯一真相是 SessionManager 的 WEBVPN backend，业务站点与浏览器路径共用同一份网关会话 ──

    private val webVpnBackend: com.xjtu.toolbox.auth.SessionBackend?
        get() = sessionManager?.backend(com.xjtu.toolbox.auth.AccessMode.WEBVPN)

    internal val webVpnClientOrNull: okhttp3.OkHttpClient?
        get() = webVpnBackend?.takeIf { it.webvpnSelfLoggedIn }?.client

    /** 网关会话可用（必要时探活、重登，可能弹 MFA）就返回它的 client，否则 null。 */
    suspend fun ensureWebVpnClient(): okhttp3.OkHttpClient? = try {
        sessionManager?.ensureWebVpnLogin()
        webVpnClientOrNull
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.w("WebVPN", "ensureWebVpnClient failed: ${e.message}")
        null
    }

    /**
     * Screen 内部捕获 [AuthExpiredException] 时调用：清掉 cached login + 让 nav 自动重新进入。
     * 用户表现为：返回首页 → 简短 loading → 自动回到原页面。
     */
    fun markStaleAndRetry(route: com.xjtu.toolbox.nav.AppRoute) {
        android.util.Log.w("AppLoginState", "markStaleAndRetry(${route.id})")
        route.loginType?.let(::clearLogin)
        pendingRetry = route
    }

    /** 等着重新打开的页面，由 [com.xjtu.toolbox.main.AppRoot] 消费。 */
    var pendingRetry by mutableStateOf<com.xjtu.toolbox.nav.AppRoute?>(null)

    /**
     * 网络环境（access mode）切换时调用：清旧 cached login + vpnClient，
     * 同步通知 SessionManager 切换 active backend（两边 cookies 保留以便快速切回）。
     *
     * @param networkSwitched 换了一张网（WiFi / 数据互切）。这时结论变了是正常的，不用复查。
     */
    suspend fun onNetworkChanged(networkSwitched: Boolean = false): Boolean {
        val prev = isOnCampus
        campusDetectTime = 0L
        val now = detectCampusNetwork(trustFirst = networkSwitched)
        isOnCampus = now
        sessionManager?.onNetworkChanged(
            if (now) com.xjtu.toolbox.auth.AccessMode.NORMAL
            else com.xjtu.toolbox.auth.AccessMode.WEBVPN
        )
        if (prev != null && prev != now) {
            android.util.Log.w("AppLoginState", "Access mode changed: $prev → $now")
            // 各站点登录态已由 SessionManager.onNetworkChanged 作废；网关会话下次用到时再探活
            webVpnBackend?.markWebVpnStale()
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

    // 网络检测结果缓存（10 分钟）
    private var campusDetectTime: Long = 0L
    private val CAMPUS_CACHE_MS = 10 * 60 * 1000L
    private val campusDetectMutex = Mutex()

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
        sessionManager?.forgetAllSites()
        // 网关登录态随 backend 走：切账号时 reconfigureForAccount 会整体换掉 backends，
        // 这里额外置一次，覆盖「尚未 reconfigure 就先清内存态」的调用顺序。
        webVpnBackend?.markWebVpnStale()
        isOnCampus = null
        campusDetectTime = 0L
        ywtbUserInfo = null
        cachedNickname = null
        accountId = ""
        passwordInvalidatedLatch = false
        passwordInvalidatedSiteName = ""
        passwordInvalidatedDialogVisible = false
        rejectedCredentials = null
        com.xjtu.toolbox.home.HomeSignals.clearAccountSignals()
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
        sessionManager?.let {
            it.setCredentials(account.accountId, account.password)
            it.accountType = selectedCasAccountType()
            it.fpVisitorId = account.fpVisitorId
            it.cachedRsaKey = account.rsaPublicKey
        }
    }

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

    /** [prepareCredentialsForLogin] 之前会话层的凭据与账号类型，失败时由 [discardPreparedCredentials] 还原。 */
    private var credentialsBeforeAttempt: Pair<Pair<String, String>?, XJTULogin.AccountType>? = null

    /** 正在尝试的那组凭据；失败且被判密码错时转存到 [rejectedCredentials]。 */
    private var attemptedCredentials: Pair<String, String>? = null

    /** 最近一次被 CAS 明确拒绝的凭据，见 [prepareCredentialsForLogin]。 */
    private var rejectedCredentials: Pair<String, String>? = null

    /**
     * 把一次登录尝试的凭据交给会话层，但不把 UI 提前切成“已登录”。
     * 认证成功后由 [saveCredentials] 提交身份；失败必须调 [discardPreparedCredentials]。
     */
    fun prepareCredentialsForLogin(username: String, password: String) {
        sessionManager?.let { credentialsBeforeAttempt = it.credentials to it.accountType }
        attemptedCredentials = username to password
        // 凭据变更视为用户已知晓并响应，清除密码失效熔断。刚被 CAS 拒掉的那一组不算"变更"：
        // 凭据只在登录成功后才写进 saved*，不单独比对的话，原样再点一次登录就会解开熔断，
        // 同一个错密码可以无限次提交给 CAS。
        val credentialsChanged = (username != savedUsername || password != savedPassword) &&
            (username to password) != rejectedCredentials
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

    /**
     * 登录失败时撤销 [prepareCredentialsForLogin]：会话层退回尝试之前的凭据（之前没有就清空）。
     *
     * 不撤销的话，UI 这边仍是旧身份，会话层却留着这次输错的密码——后台保活、定时刷新、
     * 首页统计都拿会话层凭据自动登录，会反复用错密码撞 CAS，招来限流甚至锁号。
     */
    fun discardPreparedCredentials() {
        // 这次失败若是 CAS 明确判了密码错（熔断已上），记下这组凭据，原样重试时不解熔断
        if (passwordInvalidatedLatch) rejectedCredentials = attemptedCredentials
        attemptedCredentials = null
        val (previous, previousType) = credentialsBeforeAttempt ?: return
        credentialsBeforeAttempt = null
        val sm = sessionManager ?: return
        if (previous != null) sm.setCredentials(previous.first, previous.second) else sm.clearCredentials()
        sm.accountType = previousType
    }

    fun saveCredentials(username: String, password: String) {
        prepareCredentialsForLogin(username, password)
        credentialsBeforeAttempt = null
        attemptedCredentials = null
        rejectedCredentials = null
        savedUsername = username
        savedPassword = password
        activeUsername = username
    }

    /** 清除指定子系统的会话（用于 reAuth 失败后强制 full login）。 */
    fun clearLogin(type: LoginType) {
        sessionManager?.getSiteOrNull(type.siteKey())?.invalidateLogin()
    }

    /**
     * 检测是否在校园网内（带 10 分钟缓存），探测本身见 [CampusProbe]。
     *
     * 波动保护：只凭「内网探针全连不上」得出的弱结论若和缓存不同，隔 1.5 秒再探一次，
     * 两次一致才改判；探针连上或服务器明确回答的强结论直接采用，刚换了网络（[trustFirst]）
     * 也直接采用。手机这会儿没网时不改判。
     *
     * 手动模式短路：用户在「设置 → 连接模式」选了「强制直连」/「强制 WebVPN」时，
     * 跳过探测直接返回对应结果。
     */
    suspend fun detectCampusNetwork(trustFirst: Boolean = false): Boolean {
        when (credentialStoreRef?.networkMode) {
            CredentialStore.NETWORK_DIRECT -> return true
            CredentialStore.NETWORK_VPN -> return false
            else -> {} // 自动检测：走下面的真实探测逻辑
        }
        val cached = isOnCampus
        if (cached != null && System.currentTimeMillis() - campusDetectTime < CAMPUS_CACHE_MS) {
            android.util.Log.d("Campus", "detectCampus: using cached result=$cached (age=${(System.currentTimeMillis() - campusDetectTime) / 1000}s)")
            return cached
        }
        val first = CampusProbe.detect(ywtbToken())
        if (first.offline) {
            android.util.Log.d("Campus", "detectCampus: offline, keeping cached=$cached")
            return cached ?: false
        }
        val result = if (first.strong || trustFirst || cached == null || cached == first.onCampus) {
            first.onCampus
        } else {
            kotlinx.coroutines.delay(1500L)
            val second = CampusProbe.detect(ywtbToken())
            if (!second.offline && second.onCampus == first.onCampus) second.onCampus
            else cached.also { android.util.Log.d("Campus", "detectCampus: 复查不一致（$second），按波动处理，保留 $cached") }
        }
        android.util.Log.d("Campus", "detectCampus: final result=$result (${first.why})")
        campusDetectTime = System.currentTimeMillis()
        return result
    }

    /** 一网通办登录后才有；networkCheck 要带它。 */
    private suspend fun ywtbToken(): String? =
        sessionManager?.getSiteOrNull(LoginType.YWTB.siteKey())?.localToken?.get("id_token")
            ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { CampusProbe.ywtbToken }

    // 登出只走 AccountManager.logoutCurrent：它会把会话层切回匿名命名空间并清掉凭据。
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

    /** 新会话架构入口：双 backend、SiteSession 注册中心、MFA 状态机宿主。 */
    val sessionManager = com.xjtu.toolbox.auth.SessionManager(application)

    /** 多账号编排器。 */
    val accountManager = com.xjtu.toolbox.account.AccountManager(application, accountStore)

    init {
        // 注入会话管家（无需 LaunchedEffect，ViewModel 创建时即完成）
        loginState.sessionManager = sessionManager
        loginState.credentialStoreRef = credentialStore
        // 后台任务复用这一份，别另起一个抢同一批 cookie 文件
        com.xjtu.toolbox.auth.SessionManager.active = sessionManager
        // 注册所有业务子系统
        with(sessionManager) {
            register(com.xjtu.toolbox.auth.JwxtSession())
            register(com.xjtu.toolbox.auth.JwappSession())
            register(com.xjtu.toolbox.auth.YwtbSession())
            register(com.xjtu.toolbox.auth.LibrarySession())
            register(com.xjtu.toolbox.auth.LmsSession())
            register(com.xjtu.toolbox.auth.JiaocaiSession())
            register(com.xjtu.toolbox.auth.CouponSession())
            register(com.xjtu.toolbox.auth.DzpzSession())
            register(com.xjtu.toolbox.auth.VenueSession())
            register(com.xjtu.toolbox.auth.AttendanceSession())
            register(com.xjtu.toolbox.auth.CampusCardSession())
            register(com.xjtu.toolbox.auth.FitnessSession())
            register(com.xjtu.toolbox.auth.IclassfaceSession())
            register(com.xjtu.toolbox.auth.SsnSession())
            register(com.xjtu.toolbox.auth.HelloSession())
            register(com.xjtu.toolbox.auth.GsteSession())
            register(com.xjtu.toolbox.auth.GmisSession())
            register(com.xjtu.toolbox.auth.JsSession())
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
            sessionManager.reconfigureForAccount(com.xjtu.toolbox.account.AccountContext.suffixFor(active.accountId))
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
        if (loginState.accountId == active.accountId) sessionManager.fpVisitorId = hash
        android.util.Log.d("FpVisitorId", "stable fp generated for account=${active.accountId}")
    }
}
