package com.xjtu.toolbox.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 后台 Session 保活：每隔用户设定的间隔预热一次常用站点（[SessionManager.warmUp]），
 * 让服务端会话别因闲置被回收，也让点开时命中免检窗口。
 *
 * 应用启动时调用 [start]；开关和间隔存在 [KeepAlivePrefs]，每轮循环都会重新读取。
 */
object SessionKeepAlive {
    private const val TAG = "SessionKeepAlive"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var loopJob: Job? = null

    /** 默认 10 分钟 */
    const val DEFAULT_INTERVAL_MIN = 10L

    /** 保活钩子，由会话管家注入。 */
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

    fun intervalMinutes(): Long = sp.getLong(KEY_INTERVAL_MIN, SessionKeepAlive.DEFAULT_INTERVAL_MIN)

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_INTERVAL_MIN = "interval_min"
    }
}
