package com.xjtu.toolbox.agent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlin.concurrent.Volatile

/** 「从提醒气泡进来」那一条路的中转站：屁岱这一屏下次组合时把它当第一条用户消息发出去。 */
object AgentPendingPrompt {

    data class Pending(
        val text: String,
        /** 不进聊天气泡，只进发给模型的 user 消息。 */
        val snapshot: String? = null,
    )

    @Volatile
    private var pending: Pending? = null

    var generation by mutableIntStateOf(0)
        private set

    fun set(text: String, snapshot: String? = null) {
        pending = Pending(text, snapshot)
        generation++
    }

    fun consume(): Pending? {
        val v = pending
        pending = null
        return v
    }
}
