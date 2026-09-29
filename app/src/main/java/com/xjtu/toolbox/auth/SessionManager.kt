package com.xjtu.toolbox.auth

import android.content.Context
import android.util.Log
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.network.PersistentCookieJar
import com.xjtu.toolbox.webvpn.WebVpnInterceptor
import com.xjtu.toolbox.webvpn.WebVpnUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * MFA 询问上下文。UI 层观察 [SessionManager.activeMfaRequest] 弹窗，
 * 用户输入验证码后调用 [submit]，取消则调用 [cancel]。验证码被服务端拒绝时 [rejections] 递增，弹窗保留让用户重输。
 */
class MfaRequest(
    val siteKey: String,
    val siteName: String,
    val mfaContext: MFAContext,
) {
    private val codes = Channel<String?>(Channel.CONFLATED)
    private val _rejections = MutableStateFlow(0)
    val rejections: StateFlow<Int> = _rejections

    fun submit(code: String): Boolean = codes.trySend(code).isSuccess
    fun cancel(): Boolean = codes.trySend(null).isSuccess
    internal suspend fun awaitCode(): String? = codes.receive()
    internal fun reject() { _rejections.value++ }
}

/**
 * 顶层会话管家。维护两个 [SessionBackend]、注册所有 [SiteSession]，
 * 统一处理 access mode 切换、凭据存储、密码失效熔断、MFA 状态机宿主。
 *
 * 设计约束：
 * - 业务层不直接持有 *Login 对象。业务通过 [getSite] 取 [SiteSession]，
 *   再用 [SiteSession.executeWithReAuth] 发起请求。
 * - MFA 流程串行化：[activeMfaRequest] 是 StateFlow，同一时刻只可能有一个 MFA 询问处于挂起。
 * - 网络切换不破坏对端 cookies，仅切换 active mode 指针。
 */
class SessionManager(context: Context) {

    private val appContext = context.applicationContext

    private val backendsLock = Any()

    @Volatile
    private var backends: Map<AccessMode, SessionBackend> = buildBackends(null)

    private fun buildBackends(accountSuffix: String?): Map<AccessMode, SessionBackend> {
        val suffix = accountSuffix ?: ANONYMOUS_SUFFIX
        val normalJar = PersistentCookieJar(appContext, "cookies_normal$suffix")
        val webvpnJar = PersistentCookieJar(appContext, "cookies_webvpn$suffix")
        return mapOf(
            AccessMode.NORMAL to SessionBackend(AccessMode.NORMAL, normalJar),
            AccessMode.WEBVPN to SessionBackend(
                AccessMode.WEBVPN,
                webvpnJar,
                webVpnInterceptor = WebVpnInterceptor(),
            ),
        )
    }

    fun backend(accessMode: AccessMode): SessionBackend = backends.getValue(accessMode)

    private val _currentAccessMode = MutableStateFlow(AccessMode.NORMAL)
    val currentAccessMode: StateFlow<AccessMode> = _currentAccessMode

    /**
     * 网络环境变化时调用。仅切换 active mode 指针，重新绑定 backend 给所有已注册 site；
     * 任何一边 backend 的 cookies 都不会被清空——下次切回可零成本 SSO 复用。
     */
    /** 换了网络后，旧网络上的空闲长连接已经不通，复用会卡到读超时才重试；全部丢掉重建。 */
    fun evictConnections() {
        synchronized(backendsLock) { backends.values.forEach { runCatching { it.client.connectionPool.evictAll() } } }
        runCatching { com.xjtu.toolbox.network.HttpClients.base.connectionPool.evictAll() }
    }

    fun onNetworkChanged(newMode: AccessMode) {
        val old = _currentAccessMode.value
        if (old == newMode) return
        Log.i(TAG, "AccessMode changed: ${old.key} -> ${newMode.key}")
        recordDiagnostic("INFO", "network", "访问模式切换：${old.key} -> ${newMode.key}")
        _currentAccessMode.value = newMode
        sites.values.forEach {
            it.backend = backendFor(it)
            // 切换 access mode 后 cookies 域不同，原 hasLogin 应失效以触发 validate
            it.invalidateLogin()
        }
    }

    private val sites: MutableMap<String, SiteSession> = ConcurrentHashMap()

    fun register(site: SiteSession): SiteSession {
        site.backend = backendFor(site)
        site.manager = this
        sites[site.siteKey] = site
        return site
    }

    /**
     * [SiteSession.mustUseWebVpn] = false 的站点被永久锁定在 [AccessMode.NORMAL]（直连原域名），
     * 不会跟随全局网络检测切到 WEBVPN。这类站点若域名当前仍仅限校内网络可达，校外需要用户自行
     * 连接校园官方 VPN 或回到校园网——App 内置的 WebVPN 代理对它们不生效。
     */
    private fun backendFor(site: SiteSession): SessionBackend {
        val mode = if (!site.mustUseWebVpn) AccessMode.NORMAL else _currentAccessMode.value
        return backends.getValue(mode)
    }

    fun getSite(siteKey: String): SiteSession =
        sites[siteKey] ?: error("SiteSession[$siteKey] not registered")

    fun getSiteOrNull(siteKey: String): SiteSession? = sites[siteKey]

    /** 让所有站点会话失效（不动 cookies）。切换 access mode / 切换账号时使用。 */
    fun invalidateAllSites() {
        sites.values.forEach { it.invalidateLogin() }
    }

    val activeSiteCount: Int get() = sites.values.count { it.hasLogin }
    val activeSiteKeys: List<String> get() = sites.values.filter { it.hasLogin }.map { it.siteKey }

    /** 会话诊断：写进 logcat（标签 [TAG]）。 */
    fun recordDiagnostic(level: String, siteKey: String, message: String) {
        val priority = when (level) {
            "ERROR" -> Log.ERROR
            "WARN" -> Log.WARN
            "DEBUG" -> Log.DEBUG
            else -> Log.INFO
        }
        Log.println(priority, TAG, "[$siteKey] ${message.take(240)}")
    }

    // ── 凭据 ────────────────────────────────────────────
    @Volatile var credentials: Pair<String, String>? = null
        private set
    @Volatile var accountType: XJTULogin.AccountType = XJTULogin.AccountType.UNDERGRADUATE

    fun setCredentials(username: String, password: String) {
        val old = credentials
        credentials = username to password
        // 凭据变更视为用户已知晓并响应，清除密码失效状态
        if (old != null && old != credentials && _passwordInvalidated.value) {
            _passwordInvalidated.value = false
            _passwordInvalidatedSite.value = ""
            Log.i(TAG, "Credentials updated, password invalidation cleared")
        }
    }

    fun clearCredentials() {
        credentials = null
        _passwordInvalidated.value = false
        _passwordInvalidatedSite.value = ""
        backends.values.forEach { it.clearAuth() }
        sites.values.forEach { it.invalidateLogin() }
    }

    // ── 密码全局失效 ─────────────────────────────────────
    private val _passwordInvalidated = MutableStateFlow(false)

    private val _passwordInvalidatedSite = MutableStateFlow("")

    /**
     * 任一站点确认凭据无效时调用。所有后续 ensureLogin 将立即抛 [PasswordInvalidatedException]，
     * 阻断同账号的连续错密请求。用户重新输入凭据（[setCredentials]）后状态自动清除。
     */
    fun reportPasswordInvalidated(siteKey: String, siteName: String) {
        if (_passwordInvalidated.value) return
        _passwordInvalidated.value = true
        _passwordInvalidatedSite.value = siteName.ifEmpty { siteKey }
        recordDiagnostic("ERROR", siteKey, "凭据被判定无效：${siteName.ifEmpty { siteKey }}")
        Log.w(TAG, "Password invalidated by site=$siteKey")
    }

    @Throws(PasswordInvalidatedException::class)
    fun checkPasswordValid() {
        if (_passwordInvalidated.value) {
            throw PasswordInvalidatedException(_passwordInvalidatedSite.value, "密码已失效，请更新")
        }
    }

    // ── 站点登录失败冷却 ─────────────────────────────────
    private val loginFailedAt = ConcurrentHashMap<String, Long>()
    private val loginCooldownMs = TimeUnit.SECONDS.toMillis(60)

    @Throws(LoginCooldownException::class)
    fun checkLoginCooldown(siteKey: String, siteName: String) {
        val failedAt = loginFailedAt[siteKey] ?: return
        val remainMs = loginCooldownMs - (System.currentTimeMillis() - failedAt)
        if (remainMs > 0) {
            recordDiagnostic("WARN", siteKey, "登录失败冷却中，${((remainMs + 999) / 1000).coerceAtLeast(1)} 秒后可重试")
            throw LoginCooldownException(
                siteName = siteName.ifEmpty { siteKey },
                retryAfterSeconds = ((remainMs + 999) / 1000).coerceAtLeast(1)
            )
        }
        loginFailedAt.remove(siteKey, failedAt)
    }

    fun reportLoginFailure(siteKey: String) {
        loginFailedAt[siteKey] = System.currentTimeMillis()
        recordDiagnostic("WARN", siteKey, "登录失败，进入 60 秒冷却")
    }

    fun clearLoginFailure(siteKey: String) {
        loginFailedAt.remove(siteKey)
        recordDiagnostic("INFO", siteKey, "登录失败冷却已清除")
    }

    /**
     * WEBVPN backend 的网关自认证。支持 WebVPN 的业务站点在校外访问前先调用这里，
     * 之后业务 URL 仍按原始域名构造，由 [WebVpnInterceptor] 无感改写。
     *
     * 进程活很久时 [SessionBackend.webvpnSelfLoggedIn] 仍可能是 true，但 ticket 早已失效。
     * 这里先看 cookie / 新鲜窗口，过期再探活，探活失败才重登——别的直连站点不受影响。
     *
     * 主线程安全：整个流程切到 IO——调用方常在界面协程里，而票据检查要读 cookie，
     * 首次读会在主线程打开加密存储。
     */
    @Throws(IOException::class, PasswordInvalidatedException::class)
    suspend fun ensureWebVpnLogin() = withContext(Dispatchers.IO) { ensureWebVpnLoginOnIo() }

    private suspend fun ensureWebVpnLoginOnIo() {
        val backend = backend(AccessMode.WEBVPN)
        if (isWebVpnGatewayFresh(backend)) return
        if (hasLiveWebVpnTicket(backend) && probeWebVpnGateway(backend)) {
            backend.markWebVpnReady()
            return
        }
        if (backend.webvpnSelfLoggedIn) {
            recordDiagnostic("WARN", "webvpn", "网关会话过期，准备重新认证")
            backend.markWebVpnStale()
        }
        checkPasswordValid()
        checkLoginCooldown("webvpn", "WebVPN")
        backend.loginLock.withLock {
            if (isWebVpnGatewayFresh(backend)) return@withLock
            if (hasLiveWebVpnTicket(backend) && probeWebVpnGateway(backend)) {
                backend.markWebVpnReady()
                return@withLock
            }
            val creds = credentials ?: throw IOException("还没有登录账号，请先在「我的」页登录")
            val login = XJTULogin(
                WebVpnUtil.WEBVPN_LOGIN_URL,
                existingClient = backend.client,
                visitorId = fpVisitorId,
                cachedRsaKey = cachedRsaKey,
                cookieJar = backend.cookieJar,
            )
            var result = login.login(creds.first, creds.second)
            while (true) {
                when (result.state) {
                    LoginState.SUCCESS -> {
                        adoptFromLogin(login)
                        backend.markWebVpnReady()
                        clearLoginFailure("webvpn")
                        recordDiagnostic("INFO", "webvpn", "WebVPN 网关登录成功")
                        Log.d(TAG, "WebVPN gateway login ok")
                        return@withLock
                    }
                    LoginState.FAIL -> {
                        val msg = result.message.ifBlank { "未知错误" }
                        if (msg.contains("用户名或密码") ||
                            msg.contains("密码错误") ||
                            msg.contains("账号或密码")) {
                            recordDiagnostic("ERROR", "webvpn", "WebVPN 凭据无效：$msg")
                            throw PasswordInvalidatedException("WebVPN", msg)
                        }
                        // 网关已登录时 /login?cas_login=true 会 302 回它记住的上次访问地址（可能是某个
                        // 业务 API，不带业务会话就返回 401/403），这与网关认证是否成功无关。
                        // 有网关 ticket，或落地在 /https/... 代理路径上，就说明网关已认我们；
                        // 否则会误判失败并触发 60 秒冷却，所有走 WebVPN 的站点都连不上。
                        if (backend.cookieJar.findCookieByName(WEBVPN_TICKET_COOKIE) != null ||
                            com.xjtu.toolbox.webvpn.WebVpnUtil.getOriginalUrl(login.finalUrl) != null
                        ) {
                            backend.markWebVpnReady()
                            clearLoginFailure("webvpn")
                            recordDiagnostic(
                                "INFO", "webvpn",
                                "WebVPN 网关已认证（落地页返回 $msg，属目标业务接口响应，非网关问题）"
                            )
                            Log.d(TAG, "WebVPN gateway already authenticated; ignoring target-site status: $msg")
                            return@withLock
                        }
                        reportLoginFailure("webvpn")
                        throw IOException("WebVPN 登录失败：$msg")
                    }
                    LoginState.REQUIRE_MFA -> {
                        val ctx = result.mfaContext ?: throw IOException("WebVPN 没有返回可用的验证信息，请稍后重试")
                        if (ctx.flow == MFAFlow.MFA_DETECT) ctx.sendVerifyCode()
                        if (!verifyMfaWithUser("webvpn", "WebVPN（校外接入）", ctx)) throw MfaCancelledException("WebVPN")
                        result = login.login()
                    }
                    LoginState.REQUIRE_CAPTCHA -> throw IOException("WebVPN 需要图形验证码")
                    LoginState.REQUIRE_ACCOUNT_CHOICE -> result = login.login(accountType = accountType)
                }
            }
        }
    }

    // ── 后台预热 / 保活（均为「免密」路径） ──────────────────
    //
    // 前提：TGC 已在 cookie jar 中。此时任何 CAS 站点的登录都是纯 SSO 跳转，
    // **不携带密码、不经 CasGate 的凭据闸门**，因此可以放心地在后台做——
    // 它对统一认证的压力与用户点开一个页面无异，却把等待挪出了用户的关键路径。
    //
    // 三条自我约束：
    // 1. 没有 TGC 就直接放弃（绝不为了预热而提交密码）。
    // 2. 全程 silent：撞到 MFA 立即退出，不弹窗、不发短信。
    // 3. 站点间留间隔、失败静默吞掉，不重试、不上报失败冷却。

    /**
     * 该站点当前是否具备「免密 SSO」条件。必须按**站点实际绑定的 backend** 判断：
     * NORMAL 与 WEBVPN 两个 jar 各有自己的 TGC，用 any() 一概而论会让校外场景下的预热
     * 退化成后台密码登录。
     */
    private fun canSsoSilently(site: SiteSession): Boolean {
        val b = site.backend ?: return false
        if (runCatching { b.cookieJar.findCookieByName("TGC") }.getOrNull() == null) return false
        // WebVPN 网关自身尚未认证时不碰：ensureWebVpnLogin 是带密码的，且可能弹 MFA。
        if (site.currentAccessMode == AccessMode.WEBVPN && !b.webvpnSelfLoggedIn) return false
        return true
    }

    /**
     * 预热指定站点（通常是「上次用过的几个」）。逐个串行、每个之间留间隔。
     * 任何异常都只记录不抛出——预热失败对用户不可见，最多回到「点开时再登」。
     */
    suspend fun prewarmSites(siteKeys: List<String>, gapMs: Long = 300L) {
        if (siteKeys.isEmpty()) return
        val creds = credentials ?: return
        if (_passwordInvalidated.value) return
        for ((i, key) in siteKeys.withIndex()) {
            val site = sites[key] ?: continue
            if (site.hasLogin) continue
            if (!canSsoSilently(site)) {
                Log.d(TAG, "prewarm skipped $key: no silent-SSO path (won't submit password in background)")
                continue
            }
            if (i > 0) kotlinx.coroutines.delay(gapMs)
            try {
                site.ensureLogin(creds.first, creds.second, silent = true)
                Log.d(TAG, "prewarm ok: $key")
            } catch (e: Exception) {
                Log.d(TAG, "prewarm skipped $key: ${e.message}")
            }
        }
    }

    /**
     * 保活：对**已登录**站点做一次探活，失效则免密 SSO 续期。
     * 让"放置一段时间后第一次点功能要等完整 CAS"这件事发生在后台，而不是用户面前。
     *
     * TGC 也过期时**直接跳过**，不在后台补一次密码登录：那样一旦密码在服务端被改过，
     * 用户不在场的情况下会连撞三次触发全局熔断。让用户下次主动进入时付这一次代价更可控。
     */
    suspend fun refreshLoggedInSites(gapMs: Long = 2_000L) {
        val creds = credentials ?: return
        if (_passwordInvalidated.value) return
        val live = sites.values.filter { it.hasLogin && canSsoSilently(it) }
        for ((i, site) in live.withIndex()) {
            if (i > 0) kotlinx.coroutines.delay(gapMs)
            try {
                site.ensureLogin(creds.first, creds.second, silent = true)
            } catch (e: Exception) {
                Log.d(TAG, "keepalive skipped ${site.siteKey}: ${e.message}")
            }
        }
    }

    // ── MFA 状态机宿主 ──────────────────────────────────
    private val _activeMfaRequest = MutableStateFlow<MfaRequest?>(null)
    val activeMfaRequest: StateFlow<MfaRequest?> = _activeMfaRequest

    private val mfaMutex = Mutex()

    /** 当前挂着的 [MfaDialogHost] 数，0 表示没有 UI 能接住 MFA 询问。 */
    private val mfaHosts = java.util.concurrent.atomic.AtomicInteger(0)

    /** [MfaDialogHost] 挂载时登记，返回的函数在卸载时调用。 */
    fun attachMfaHost(): () -> Unit {
        mfaHosts.incrementAndGet()
        val detached = java.util.concurrent.atomic.AtomicBoolean(false)
        return { if (detached.compareAndSet(false, true)) mfaHosts.decrementAndGet() }
    }

    /**
     * [LoginState.REQUIRE_MFA] 时调用：弹窗向用户要短信验证码并当场校验，验证码不对就留在弹窗里
     * 让用户重输（最多 [MFA_MAX_ATTEMPTS] 次）。同一时刻仅一个 MFA 询问在挂起。
     *
     * 每次等待都有超时：没有弹窗宿主的页面发起的询问永远等不到输入，而登录跑在全局
     * [CasSiteSession] 锁里，无限期挂起会把其余站点的登录一起锁死。
     *
     * @return true 验证通过；false 用户取消或等待超时。
     * @throws IOException 没有能弹窗的界面、网络失败，或验证码错得太多次。
     */
    @Throws(IOException::class)
    suspend fun verifyMfaWithUser(siteKey: String, siteName: String, ctx: MFAContext): Boolean {
        if (mfaHosts.get() == 0) {
            Log.w(TAG, "verifyMfaWithUser($siteKey): no MFA host mounted")
            throw IOException("现在没有可以输入验证码的界面，请回到应用里再试一次")
        }
        return mfaMutex.withLock {
            val req = MfaRequest(siteKey, siteName, ctx)
            _activeMfaRequest.value = req
            try {
                repeat(MFA_MAX_ATTEMPTS) {
                    val code = kotlinx.coroutines.withTimeoutOrNull(MFA_WAIT_TIMEOUT_MS) { req.awaitCode() }
                        ?: return@withLock false
                    try {
                        withContext(Dispatchers.IO) { ctx.verifyCode(code) }
                        return@withLock true
                    } catch (e: IOException) {
                        throw e
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        req.reject() // 服务端明确拒绝了这个验证码，弹窗留着让用户重输
                    }
                }
                throw IOException("验证码输错次数太多，请稍后再试")
            } finally {
                _activeMfaRequest.value = null
            }
        }
    }

    // ── 跨站点共享缓存 ──────────────────────────────────
    /** 设备指纹 ID。首个完成登录的 site 写入后，其余 site 复用以避免重复触发 MFA。 */
    @Volatile var fpVisitorId: String? = null
    @Volatile var cachedRsaKey: String? = null

    fun adoptFromLogin(login: XJTULogin) {
        if (fpVisitorId == null) fpVisitorId = login.fpVisitorId
        if (cachedRsaKey == null) cachedRsaKey = login.getRsaPublicKey()
    }

    /**
     * 切换账号时重建 backends：用目标账号命名空间的 cookieJar 实例化两个新 [SessionBackend]，
     * 重新绑定所有已注册 site 的 backend，并清空凭据/指纹/RSA/熔断/冷却等账号相关状态。
     *
     * 调用方应在切换前后自行更新 [AccountContext.activeAccountId] 与 [credentials]。
     *
     * @param accountSuffix 命名空间后缀（形如 "_学号"），由 [com.xjtu.toolbox.account.AccountContext.suffixFor] 派生
     */
    fun reconfigureForAccount(accountSuffix: String) {
        AccountContext.switchEpoch++
        // 挂着的 MFA 是旧账号的：验证码发到了旧账号手机上，填了也只会登进旧账号
        _activeMfaRequest.value?.cancel()
        synchronized(backendsLock) {
            // 旧 backends 的 cookieJar 不主动 clear——其磁盘文件保留以便切回该账号时复用；
            // 但主动驱逐其连接池里的空闲连接，避免频繁切账号累积 socket fd。
            backends.values.forEach { runCatching { it.client.connectionPool.evictAll() } }
            backends = buildBackends(accountSuffix)
        }
        // 重新绑定每个 site 到新 backend；mustUseWebVpn=false 的 site 永远绑 NORMAL（直连，不代表校外可用）
        sites.values.forEach {
            it.backend = backendFor(it)
            it.invalidateLogin()
        }
        // 清空账号相关共享状态
        credentials = null
        fpVisitorId = null
        cachedRsaKey = null
        _passwordInvalidated.value = false
        _passwordInvalidatedSite.value = ""
        loginFailedAt.clear()
        recordDiagnostic("INFO", "account", "SessionManager reconfigured for suffix=$accountSuffix")
    }

    /**
     * 回到未登录（匿名）命名空间，并清空它。匿名罐里若留着 TGC，
     * 下一个在「我的」页登录的人会经 SSO 直接复用上一个人的会话。
     */
    fun reconfigureForAnonymous() {
        reconfigureForAccount(ANONYMOUS_SUFFIX)
        backends.values.forEach { it.cookieJar.clear() }
    }

    /** 还没有账号时匿名命名空间里不该有会话。开始登录前清一次，免得早先遗留的 TGC 让新登录直通别人的会话。 */
    fun purgeAnonymousSession() {
        if (AccountContext.activeAccountId == null) backends.values.forEach { it.cookieJar.clear() }
    }

    /** 首次登录发生在匿名命名空间：把这次产生的 cookie 搬进账号命名空间，匿名罐清空。 */
    fun adoptAnonymousSession(accountSuffix: String) {
        val old = backends
        val raws = old.mapValues { it.value.cookieJar.exportRaw() }
        old.values.forEach { it.cookieJar.clear() }
        reconfigureForAccount(accountSuffix)
        raws.forEach { (mode, raw) -> if (raw.isNotBlank()) backend(mode).cookieJar.importRaw(raw) }
    }

    private fun hasLiveWebVpnTicket(backend: SessionBackend): Boolean =
        backend.cookieJar.findCookieByName(WEBVPN_TICKET_COOKIE) != null

    private fun isWebVpnGatewayFresh(backend: SessionBackend): Boolean {
        if (!backend.webvpnSelfLoggedIn || !hasLiveWebVpnTicket(backend)) return false
        val age = android.os.SystemClock.elapsedRealtime() - backend.webvpnValidatedAt
        return backend.webvpnValidatedAt > 0L && age in 0 until WEBVPN_VALIDATE_TTL_MS
    }

    /** 不跟随重定向：被扔回 CAS 就说明网关 ticket 已经死了。网络抖动不当失效。 */
    private fun probeWebVpnGateway(backend: SessionBackend): Boolean = try {
        val client = backend.client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        val request = okhttp3.Request.Builder()
            .url(WebVpnUtil.WEBVPN_LOGIN_URL)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val loc = response.header("Location").orEmpty()
            val preview = runCatching { response.peekBody(8192).string() }.getOrDefault("")
            val bouncedToCas = "cas_login" in loc ||
                "/cas/login" in loc ||
                "login.xjtu.edu.cn" in loc
            val authPage = XJTULogin.isAuthFailureResponse(preview)
            val resourcePage = "西安交通大学WebVPN" in preview || "资源站点" in preview
            val alive = resourcePage ||
                (response.code in 200..299 && !authPage) ||
                (response.code in 300..399 && !bouncedToCas)
            if (!alive) {
                Log.w(TAG, "WebVPN probe stale: code=${response.code} loc=$loc")
            }
            alive
        }
    } catch (e: Exception) {
        Log.w(TAG, "WebVPN probe failed, keep current session: ${e.message}")
        true
    }

    companion object {
        /**
         * 本进程当前在用的会话管家。
         *
         * 后台任务（WorkManager）拿它复用前台已经建好的会话，而不是另起一个——
         * 两个 [SessionManager] 会各自持有同名 cookie jar，互相覆盖对方写下的 cookies，
         * 表现为「后台跑完一轮之后前台要重登」。App 进程已死时这里是 null，
         * 由后台任务自己按当前账号建一个临时的（见 `HeadlessSessions`）。
         */
        @Volatile
        var active: SessionManager? = null

        private const val TAG = "SessionManager"

        /** 未登录时 backends 所在命名空间。 */
        private const val ANONYMOUS_SUFFIX = "_default"
        private const val WEBVPN_TICKET_COOKIE = "wengine_vpn_ticketwebvpn_xjtu_edu_cn"
        private const val WEBVPN_VALIDATE_TTL_MS = 120_000L

        /** [verifyMfaWithUser] 每次等输入的最长时间，留够用户看到弹窗、收短信、输入。 */
        private const val MFA_WAIT_TIMEOUT_MS = 150_000L
        private const val MFA_MAX_ATTEMPTS = 3
    }
}
