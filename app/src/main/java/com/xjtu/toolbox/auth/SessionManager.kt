package com.xjtu.toolbox.auth

import android.content.Context
import android.util.Log
import com.xjtu.toolbox.util.PersistentCookieJar
import com.xjtu.toolbox.util.WebVpnInterceptor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

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

    private val backends: Map<AccessMode, SessionBackend> = run {
        val normalJar = PersistentCookieJar(appContext, prefsName = "cookies_normal")
        val webvpnJar = PersistentCookieJar(appContext, prefsName = "cookies_webvpn")
        mapOf(
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

    private fun backendFor(site: SiteSession): SessionBackend {
        val mode = if (!site.supportsWebVpn) AccessMode.NORMAL else _currentAccessMode.value
        return backends.getValue(mode)
    }

    fun getSite(siteKey: String): SiteSession =
        sites[siteKey] ?: error("SiteSession[$siteKey] not registered")

    fun getSiteOrNull(siteKey: String): SiteSession? = sites[siteKey]

    val activeSiteCount: Int get() = sites.values.count { it.hasLogin }
    val activeSiteKeys: List<String> get() = sites.values.filter { it.hasLogin }.map { it.siteKey }

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
        Log.w(TAG, "Password invalidated by site=$siteKey")
    }

    @Throws(PasswordInvalidatedException::class)
    fun checkPasswordValid() {
        if (_passwordInvalidated.value) {
            throw PasswordInvalidatedException(_passwordInvalidatedSite.value, "密码已失效，请更新")
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

    companion object {
        private const val TAG = "SessionManager"
    }
}
