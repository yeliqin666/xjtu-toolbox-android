package com.xjtu.toolbox.agent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlin.concurrent.Volatile

/**
 * 「从提醒气泡进来」那一条路的中转站：气泡（`:app` 的 `ProactiveBubble`，被通知、桌面小组件、
 * 提醒评估那几条路径 `set`）把一件事挂在这里，屁岱这一屏下次可组合时把它当**第一条用户消息**发出去。
 *
 * ## 为什么它在 `:core`
 *
 * `set` 的人在 `:app`（气泡本身要系统通知与小组件），`consume` 的人在 `:core`（屁岱这一屏）。
 * 里外一共一个 `String` + 一段快照文本，没有一处端能力 —— 与 `AgentThinkingHost` 同一种形状
 * （那个也是屏与底栏共用的状态）。搬动时**一行未改**，`:app` 的调用点因为同包同名而不受影响。
 *
 * ## 两个字段
 *
 * [generation] 是可组合的版本号：屏用 `LaunchedEffect(config.isConfigured, generation)` 盯它，
 * 所以**人已经留在屁岱页时**再冒一条提醒也能立刻接上；[Pending.snapshot] 只进发给模型的那条消息，
 * 不上屏（它是「点的是哪件事」的定位信息，不是完整数据）。
 */
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
