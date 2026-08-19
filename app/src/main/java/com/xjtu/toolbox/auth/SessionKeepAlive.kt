package com.xjtu.toolbox.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 后台 Session 保活。
 *
 * 每隔用户设定的间隔，对已登录站点做一次免密 SSO 续期（撞 MFA 即退出）。
 *
 * 使用：
 * - 在应用启动 / 登录成功后调用 [start]；登出时 [stop]。
 * - 设置开关 / 间隔：[KeepAlivePrefs.setEnabled] / [setIntervalMinutes]，[applyConfigChange] 立即生效。
 */
object SessionKeepAlive {
    private const val TAG = "SessionKeepAlive"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var loopJob: Job? = null

    /** 默认 10 分钟 */
    const val DEFAULT_INTERVAL_MIN = 10L

    /** 新会话架构的保活钩子（[SessionManager.refreshLoggedInSites]）。 */
    @Volatile var sessionRefresher: (suspend () -> Unit)? = null

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
        sessionRefresher?.let {
            try {
                it()
            } catch (e: Exception) {
                Log.w(TAG, "session refresh failed: ${e.message}")
            }
        }
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
