package com.xjtu.toolbox.agent

import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.util.DailyCounters
import com.xjtu.toolbox.util.nowOnCampus
import kotlinx.datetime.LocalDate

/**
 * 和小岱的交互次数，按账号分开记，给学期总结用。
 * 项：`msg` 发消息、`chat` 开新对话、`tool:<名>` 调工具、`hour:<0–23>` 发消息时的钟点。
 */
object AgentUsage {
    private fun counters() = DailyCounters("agent_usage" + AccountContext.safeSuffix())

    fun message(newChat: Boolean) {
        val c = counters()
        c.add("msg")
        if (newChat) c.add("chat")
        c.add("hour:${nowOnCampus().hour}")
    }

    fun tool(name: String) = counters().add("tool:$name")

    fun between(from: LocalDate, to: LocalDate) = counters().between(from, to)
}
