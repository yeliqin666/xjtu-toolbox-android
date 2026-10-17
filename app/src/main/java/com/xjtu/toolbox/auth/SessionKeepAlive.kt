package com.xjtu.toolbox.auth

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Session 保活：App 在前台时每 10 分钟预热一次常用站点（[SessionManager.warmUp]），
 * 让服务端会话别因闲置被回收，也让点开时命中免检窗口。
 *
 * 只在前台跑：[MainActivity] 的 onStart 里 [start]、onStop 里 [stop]。退到后台还轮询的话，
 * 同一学号全天候在撞 CAS 和教务，费电也容易招来风控；回到前台时会话预热本来就会补一轮。
 */
object SessionKeepAlive {
    private const val TAG = "SessionKeepAlive"
    private const val INTERVAL_MS = 10 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var loopJob: Job? = null

    /** 保活钩子，由会话管家注入。 */
    @Volatile var sessionRefresher: (suspend () -> Unit)? = null

    /** 启动循环。重复调用是幂等的（仅在未运行时启动）。 */
    fun start() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            while (isActive) {
                delay(INTERVAL_MS)
                runOnce()
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    private suspend fun runOnce() {
        sessionRefresher?.let {
            try {
                it()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "session refresh failed: ${e.message}")
            }
        }
    }
}
