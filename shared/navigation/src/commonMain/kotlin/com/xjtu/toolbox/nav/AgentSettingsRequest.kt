package com.xjtu.toolbox.nav

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.xjtu.toolbox.util.singletonState
import kotlin.concurrent.Volatile

/**
 * 「进小岱页后直接打开它的设置页」的一次性请求（小贴士里讲主动提醒、换形象的那几条用）。
 * 小岱页没有单独的路由：先 [request] 再导航到 [AppRoute.Agent]，小岱页可见时 [consume]。
 */
object AgentSettingsRequest {
    @Volatile
    private var pending = false

    var generation by singletonState { mutableIntStateOf(0) }
        private set

    fun request() {
        pending = true
        generation++
    }

    fun consume(): Boolean = pending.also { pending = false }
}
