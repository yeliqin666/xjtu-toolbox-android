package com.xjtu.toolbox.srun

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.xjtu.toolbox.util.CredentialStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 校园网（XJTU_STU）自动登录管理器。
 *
 * 工作流程：
 *   1. 监听 ConnectivityManager.NetworkCallback：WiFi 网络可用时回调 [onWifiAvailable]
 *   2. 读取当前 SSID，匹配 [TARGET_SSID_KEYWORDS] 时启动登录流程
 *   3. 调用 [SrunLogin.queryStatus] 探测：
 *      - 已在线 → 啥都不做
 *      - 未登录 → 加载凭据后自动 [SrunLogin.login]
 *      - 不可达 → 不在 Srun 网段，跳过
 *
 * 用法：
 *   - 在 Activity 创建/恢复时调用 [register]
 *   - 在 Activity 销毁时调用 [unregister]
 */
class SrunAutoLoginManager(
    private val context: Context,
    private val credentialStore: CredentialStore,
    private val onResult: (Result) -> Unit = {}
) {

    /** 单次登录尝试结果。 */
    sealed class Result {
        data class Success(val username: String) : Result()
        data class AlreadyOnline(val username: String) : Result()
        data class Failed(val message: String) : Result()
        object Skipped : Result()
    }

    companion object {
        private const val TAG = "SrunAutoLogin"
        /** 目标 SSID 包含的关键词（不区分大小写，命中即触发）。 */
        val TARGET_SSID_KEYWORDS = listOf("XJTU_STU", "XJTU-STU")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cm get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val wifiManager get() = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private var callback: ConnectivityManager.NetworkCallback? = null
    private val running = AtomicBoolean(false)

    /** 注册网络回调。重复调用安全。 */
    fun register() {
        if (callback != null) return
        val mgr = cm ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onWifiAvailable(network)
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    onWifiAvailable(network)
                }
            }
        }
        callback = cb
        runCatching { mgr.registerDefaultNetworkCallback(cb) }
            .onFailure { Log.w(TAG, "registerDefaultNetworkCallback failed: ${it.message}") }
    }

    fun unregister() {
        val mgr = cm
        callback?.let { runCatching { mgr?.unregisterNetworkCallback(it) } }
        callback = null
        scope.cancel()
    }

    /** 手动触发一次登录尝试（用于「设置」页的「立即测试」按钮）。 */
    fun triggerNow() {
        scope.launch { runOnce(forceEvenIfDisabled = true) }
    }

    private fun onWifiAvailable(network: Network) {
        // 防抖：同一时间只跑一次
        if (!running.compareAndSet(false, true)) return
        scope.launch {
            try {
                delay(800)  // 防抖：等 SSID 稳定
                runOnce(forceEvenIfDisabled = false)
            } finally {
                running.set(false)
            }
        }
    }

    private suspend fun runOnce(forceEvenIfDisabled: Boolean) = withContext(Dispatchers.IO) {
        if (!forceEvenIfDisabled && !credentialStore.srunAutoLoginEnabled) {
            return@withContext
        }
        val ssid = currentSsid()
        Log.d(TAG, "runOnce: ssid=$ssid, forced=$forceEvenIfDisabled")
        if (!forceEvenIfDisabled && !ssidMatches(ssid)) {
            return@withContext
        }
        val creds = credentialStore.loadSrunCredentials()
        if (creds == null) {
            Log.d(TAG, "runOnce: no Srun credentials saved")
            onResult(Result.Skipped)
            return@withContext
        }
        val srun = SrunLogin()
        when (val status = srun.queryStatus()) {
            is SrunStatus.Online -> {
                Log.d(TAG, "runOnce: already online as ${status.username}")
                onResult(Result.AlreadyOnline(status.username))
            }
            SrunStatus.NotLoggedIn -> {
                Log.d(TAG, "runOnce: not online, attempting login as ${creds.first}")
                val r = srun.login(creds.first, creds.second)
                if (r.success) onResult(Result.Success(r.username))
                else onResult(Result.Failed(r.message))
            }
            SrunStatus.Unreachable, SrunStatus.UNKNOWN -> {
                Log.d(TAG, "runOnce: gateway unreachable, skip")
                onResult(Result.Skipped)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun currentSsid(): String? {
        val wifi = wifiManager ?: return null
        // Android 10+ 拿 SSID 需要 ACCESS_FINE_LOCATION，未授权时返回 "<unknown ssid>"
        return try {
            val info = wifi.connectionInfo ?: return null
            info.ssid?.removeSurrounding("\"")?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
        } catch (e: SecurityException) {
            Log.w(TAG, "currentSsid: missing permission")
            null
        }
    }

    private fun ssidMatches(ssid: String?): Boolean {
        if (ssid.isNullOrBlank()) return false
        return TARGET_SSID_KEYWORDS.any { ssid.contains(it, ignoreCase = true) }
    }
}
