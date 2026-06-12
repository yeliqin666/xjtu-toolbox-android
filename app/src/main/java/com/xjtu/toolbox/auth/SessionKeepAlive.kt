package com.xjtu.toolbox.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 后台 Session 保活。
 *
 * 每隔 [intervalMs] 自动对各「已登录」子系统发起一次轻量 GET（依赖 reAuthenticate / SSO），
 * 让服务端不会因为长时间空闲而吊销 session。
 *
 * 使用：
 * - 在应用启动 / 登录成功后调用 [start]；登出时 [stop]。
 * - 设置开关 / 间隔：[KeepAlivePrefs.setEnabled] / [setIntervalMinutes]，[applyConfigChange] 立即生效。
 */
object SessionKeepAlive {
    private const val TAG = "SessionKeepAlive"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var loopJob: Job? = null
    private val _lastReport = MutableStateFlow<KeepAliveReport?>(null)
    val lastReport: StateFlow<KeepAliveReport?> = _lastReport

    /** 默认 10 分钟 */
    const val DEFAULT_INTERVAL_MIN = 10L

    /** 提供者：把 AppLoginState 转换成可保活的 login 列表，避免直接依赖 MainActivity 的类。 */
    fun interface LoginProvider {
        /**
         * 返回当前已登录、需要保活的 login 实例（与 vpnClient 的 ping URL 一并提供）。
         */
        fun snapshot(): KeepAliveSnapshot
    }

    /**
     * 一次保活快照。
     *
     * @param logins 已登录子系统的 [XJTULogin]（每个都会调用其 reAuthenticate）。
     * @param vpnClient 校外 WebVPN 客户端（不为 null 时会发起一次 vpnPingUrl 的 HEAD/GET 请求）。
     */
    data class KeepAliveSnapshot(
        val logins: List<XJTULogin> = emptyList(),
        val vpnClient: OkHttpClient? = null,
        val vpnPingUrl: String = "https://webvpn.xjtu.edu.cn/"
    )

    enum class WebVpnKeepAliveStatus {
        NOT_CONFIGURED,
        VALID,
        NETWORK_ERROR
    }

    data class KeepAliveSiteResult(
        val siteName: String,
        val status: XJTULogin.KeepAliveStatus
    )

    data class KeepAliveReport(
        val checkedAt: Long,
        val webVpnStatus: WebVpnKeepAliveStatus,
        val siteResults: List<KeepAliveSiteResult>
    )

    @Volatile private var provider: LoginProvider? = null

    fun setProvider(p: LoginProvider) {
        provider = p
    }

    /** 启动循环。重复调用是幂等的（仅在未运行时启动）。 */
    fun start(context: Context) {
        if (loopJob?.isActive == true) return
        val prefs = KeepAlivePrefs(context.applicationContext)
        if (!prefs.isEnabled()) {
            Log.d(TAG, "start: disabled by user, skip")
            return
        }
        loopJob = scope.launch {
            Log.d(TAG, "loop started, interval=${prefs.intervalMinutes()}min")
            while (isActive) {
                val intervalMs = prefs.intervalMinutes().coerceAtLeast(1) * 60_000L
                delay(intervalMs)
                if (!prefs.isEnabled()) {
                    Log.d(TAG, "tick: disabled, exiting loop")
                    break
                }
                runOnce()
            }
        }
    }

    /** 立刻停止循环。 */
    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    /** 立即触发一轮保活（不影响循环）。 */
    fun pokeNow() {
        scope.launch { runOnce() }
    }

    /** 设置变更后调用：立即重启循环以应用新间隔/开关。 */
    fun applyConfigChange(context: Context) {
        stop()
        start(context)
    }

    private suspend fun runOnce() {
        val snap = provider?.snapshot() ?: return
        Log.d(TAG, "tick: ${snap.logins.size} logins, vpn=${snap.vpnClient != null}")

        var webVpnStatus = WebVpnKeepAliveStatus.NOT_CONFIGURED
        // VPN ping：让 webvpn cookie 不被回收
        snap.vpnClient?.let { client ->
            try {
                val req = Request.Builder().url(snap.vpnPingUrl).get().build()
                client.newCall(req).execute().use { /* drain */ it.body?.bytes() }
                Log.d(TAG, "vpn ping ok")
                webVpnStatus = WebVpnKeepAliveStatus.VALID
            } catch (e: Exception) {
                Log.w(TAG, "vpn ping failed: ${e.message}")
                webVpnStatus = WebVpnKeepAliveStatus.NETWORK_ERROR
            }
        }

        // 各子系统 keepAlive：validate → 失效则 reAuth → 结构化状态。
        // 站点间加间隔，避免一轮保活内对统一认证形成并发/突发请求（风控敏感）。
        val siteResults = mutableListOf<KeepAliveSiteResult>()
        for ((index, login) in snap.logins.withIndex()) {
            if (index > 0) delay(2_000L)
            try {
                val status = withContext(Dispatchers.IO) { login.keepAlive() }
                val name = login.javaClass.simpleName
                siteResults += KeepAliveSiteResult(name, status)
                when (status) {
                    XJTULogin.KeepAliveStatus.VALID -> Log.d(TAG, "$name: VALID")
                    XJTULogin.KeepAliveStatus.REAUTH_OK -> Log.d(TAG, "$name: REAUTH_OK")
                    XJTULogin.KeepAliveStatus.AUTH_INVALID -> Log.w(TAG, "$name: AUTH_INVALID")
                    XJTULogin.KeepAliveStatus.NETWORK_ERROR -> Log.w(TAG, "$name: NETWORK_ERROR")
                    XJTULogin.KeepAliveStatus.ERROR -> Log.e(TAG, "$name: ERROR")
                }
            } catch (e: Exception) {
                Log.w(TAG, "keep alive ${login.javaClass.simpleName} exception: ${e.message}")
                siteResults += KeepAliveSiteResult(login.javaClass.simpleName, XJTULogin.KeepAliveStatus.ERROR)
            }
        }
        _lastReport.value = KeepAliveReport(
            checkedAt = System.currentTimeMillis(),
            webVpnStatus = webVpnStatus,
            siteResults = siteResults
        )
    }
}

/** 保活相关用户设置（开关 + 间隔分钟数），存于 SharedPreferences。 */
class KeepAlivePrefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("session_keepalive", Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = sp.getBoolean(KEY_ENABLED, true /* 上游默认开启 */)
    fun setEnabled(value: Boolean) = sp.edit { putBoolean(KEY_ENABLED, value) }

    fun intervalMinutes(): Long = sp.getLong(KEY_INTERVAL_MIN, SessionKeepAlive.DEFAULT_INTERVAL_MIN)
    fun setIntervalMinutes(value: Long) = sp.edit { putLong(KEY_INTERVAL_MIN, value.coerceAtLeast(1)) }

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_INTERVAL_MIN = "interval_min"
    }
}
