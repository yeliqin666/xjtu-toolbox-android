package com.xjtu.toolbox.desktop

import com.xjtu.toolbox.agent.AgentConfig
import com.xjtu.toolbox.agent.AgentHost
import com.xjtu.toolbox.agent.AgentStreamResult
import com.xjtu.toolbox.agent.AgentTransport
import kotlinx.serialization.json.JsonObject

/** 屁岱在桌面端的夹具上游（`:desktop:test` 与 `renderScreens` 共用一份）。 */
internal const val AGENT_FIXTURE_REPLY = "接上了：这句回复来自夹具传输，不是任何模型说的。"

/** 按 [chunk] 个字一段回调 [AgentTransport.stream] 的 `onDelta`，再返回聚合结果。 */
internal class FixtureAgentTransport(
    private val reply: String = AGENT_FIXTURE_REPLY,
    /** 每段几个字。 */
    private val chunk: Int = 6,
) : AgentTransport {

    /** 这一枪收到的请求体（用例拿它断言「消息真发出去了」）。 */
    var lastBody: JsonObject? = null
        private set

    /** 回调过几段增量。 */
    var deltas: Int = 0
        private set

    override suspend fun stream(
        config: AgentConfig,
        reqBody: JsonObject,
        onDelta: suspend (String) -> Unit,
        onReasoningDelta: suspend (String) -> Unit,
    ): AgentStreamResult {
        lastBody = reqBody
        reply.chunked(chunk).forEach { piece ->
            deltas++
            onDelta(piece)
        }
        return AgentStreamResult(
            content = reply,
            reasoningContent = "",
            toolCalls = emptyList(),
            finishReason = "stop",
            totalTokens = 42L,
        )
    }
}

/** 一份宿主面：装配与落盘都是真的（`DesktopAgentHost` 的每个槽位），只有两处换掉 —— */
internal fun fixtureAgentHost(auth: DesktopAuth, transport: AgentTransport): AgentHost =
    object : AgentHost by DesktopAgentHost(auth) {
        override fun transport(): AgentTransport = transport

        override suspend fun generateTitle(config: AgentConfig, userMsg: String, assistantMsg: String): String? = null

        override suspend fun fetchModels(config: AgentConfig): List<String> = emptyList()
    }
