package com.xjtu.toolbox.auth

import android.content.Context
import android.util.Log
import com.xjtu.toolbox.util.PersistentCookieJar
import com.xjtu.toolbox.util.WebVpnInterceptor
import com.xjtu.toolbox.util.WebVpnUtil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
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
 * 用户输入验证码后调用 [submit]，取消则调用 [cancel]。
 */
data class MfaRequest(
    val siteKey: String,
    val siteName: String,
    val mfaContext: MFAContext,
    private val deferred: CompletableDeferred<String?>,
) {
    fun submit(code: String): Boolean = deferred.complete(code)
    fun cancel(): Boolean = deferred.complete(null)
}

data class SessionDiagnosticEvent(
    val timestamp: Long,
    val level: String,
    val siteKey: String,
    val message: String,
)

data class SessionSiteSnapshot(
    val siteKey: String,
    val siteName: String,
    val hasLogin: Boolean,
    val accessMode: String,
    val mustUseWebVpn: Boolean,
    val localTokenKeys: List<String>,
)

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
        // suffix 为 null 时用 "_default"：直接插值会拼出字面量 "cookies_normalnull"，
        // 白白留下一个谁也不会再读的加密 prefs 文件（启动后 restoreActiveAccount 立刻用
        // 真实账号后缀重建 backends）。与 AccountManager 注销时用的 "_default" 命名空间对齐。
        val suffix = accountSuffix ?: "_default"
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

    fun siteSnapshots(): List<SessionSiteSnapshot> =
        sites.values.sortedBy { it.siteKey }.map { site ->
            SessionSiteSnapshot(
                siteKey = site.siteKey,
                siteName = site.siteName,
                hasLogin = site.hasLogin,
                accessMode = site.currentAccessMode.key,
                mustUseWebVpn = site.mustUseWebVpn,
                localTokenKeys = site.localToken.keys.sorted(),
            )
        }

    private val diagnosticEvents = ArrayDeque<SessionDiagnosticEvent>()
    private val diagnosticLock = Any()
    private val maxDiagnosticEvents = 120

    fun recordDiagnostic(level: String, siteKey: String, message: String) {
        val event = SessionDiagnosticEvent(
            timestamp = System.currentTimeMillis(),
            level = level,
            siteKey = siteKey,
            message = message.take(240),
        )
        synchronized(diagnosticLock) {
            diagnosticEvents.addLast(event)
            while (diagnosticEvents.size > maxDiagnosticEvents) diagnosticEvents.removeFirst()
        }
    }

    fun recentDiagnostics(limit: Int = 30): List<SessionDiagnosticEvent> =
        synchronized(diagnosticLock) {
            diagnosticEvents.takeLast(limit.coerceIn(1, maxDiagnosticEvents))
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
    val passwordInvalidated: StateFlow<Boolean> = _passwordInvalidated

    private val _passwordInvalidatedSite = MutableStateFlow("")
    val passwordInvalidatedSite: StateFlow<String> = _passwordInvalidatedSite

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
     */
    @Throws(IOException::class, PasswordInvalidatedException::class)
    suspend fun ensureWebVpnLogin() {
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
            val creds = credentials ?: throw IOException("WebVPN 未配置凭据")
            val login = withContext(Dispatchers.IO) {
                XJTULogin(
                    WebVpnUtil.WEBVPN_LOGIN_URL,
                    existingClient = backend.client,
                    visitorId = fpVisitorId,
                    cachedRsaKey = cachedRsaKey,
                    cookieJar = backend.cookieJar,
                )
            }
            var result = withContext(Dispatchers.IO) { login.login(creds.first, creds.second) }
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
                        // 网关**已登录**时，/login?cas_login=true 不会给登录页，而是 302 去它
                        // 记住的上次访问地址。那可能是某个业务 API（真机实测跳到了
                        // ncard 的 queryCard），不带业务会话自然返回 401/403——但这跟网关
                        // 认证成没成功毫无关系。只要落地在 webvpn 的 /https/... 代理路径上，
                        // 就说明网关认了我们，否则它只会把我们挡在登录页。
                        //
                        // 不做这个判断的后果（已发生）：网关明明是好的，却被判失败并触发
                        // 60 秒登录冷却，期间所有走 WebVPN 的站点全部连不上。
                        if (backend.cookieJar.findCookieByName(WEBVPN_TICKET_COOKIE) != null ||
                            com.xjtu.toolbox.util.WebVpnUtil.getOriginalUrl(login.finalUrl) != null
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
                        val ctx = result.mfaContext ?: throw IOException("WebVPN 未返回 MFA 上下文")
                        if (ctx.flow == MFAFlow.MFA_DETECT) {
                            withContext(Dispatchers.IO) { ctx.sendVerifyCode() }
                        }
                        val code = askMfaCode("webvpn", "WebVPN（校外接入）", ctx)
                            ?: throw IOException("WebVPN 用户取消验证")
                        withContext(Dispatchers.IO) { ctx.verifyCode(code) }
                        result = withContext(Dispatchers.IO) { login.login() }
                    }
                    LoginState.REQUIRE_CAPTCHA -> throw IOException("WebVPN 需要图形验证码")
                    LoginState.REQUIRE_ACCOUNT_CHOICE -> {
                        result = withContext(Dispatchers.IO) { login.login(accountType = accountType) }
                    }
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

    /**
     * SiteSession.runLogin 在 [LoginState.REQUIRE_MFA] 时调用。锁内更新 [_activeMfaRequest]
     * 触发 UI 弹窗，挂起等待用户提交或取消；同一时刻仅一个 MFA 询问在挂起。
     */
    suspend fun askMfaCode(siteKey: String, siteName: String, ctx: MFAContext): String? {
        return mfaMutex.withLock {
            val deferred = CompletableDeferred<String?>()
            val req = MfaRequest(siteKey, siteName, ctx, deferred)
            _activeMfaRequest.value = req
            try {
                deferred.await()
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
     * @param accountSuffix 命名空间后缀（形如 "_学号"），由 [com.xjtu.toolbox.account.AccountContext.safeSuffix] 派生
     */
    fun reconfigureForAccount(accountSuffix: String) {
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

    private fun hasLiveWebVpnTicket(backend: SessionBackend): Boolean =
        backend.cookieJar.findCookieByName(WEBVPN_TICKET_COOKIE) != null

    private fun isWebVpnGatewayFresh(backend: SessionBackend): Boolean {
        if (!backend.webvpnSelfLoggedIn || !hasLiveWebVpnTicket(backend)) return false
        val age = android.os.SystemClock.elapsedRealtime() - backend.webvpnValidatedAt
        return backend.webvpnValidatedAt > 0L && age in 0 until WEBVPN_VALIDATE_TTL_MS
    }

    /** 不跟随重定向：被扔回 CAS 就说明网关 ticket 已经死了。网络抖动不当失效。 */
    private suspend fun probeWebVpnGateway(backend: SessionBackend): Boolean = withContext(Dispatchers.IO) {
        try {
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
    }

    companion object {
        private const val TAG = "SessionManager"
        private const val WEBVPN_TICKET_COOKIE = "wengine_vpn_ticketwebvpn_xjtu_edu_cn"
        private const val WEBVPN_VALIDATE_TTL_MS = 120_000L
    }
}
