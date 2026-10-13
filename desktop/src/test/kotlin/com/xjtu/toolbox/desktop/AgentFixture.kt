package com.xjtu.toolbox.desktop

import com.xjtu.toolbox.agent.AgentConfig
import com.xjtu.toolbox.agent.AgentHost
import com.xjtu.toolbox.agent.AgentStreamResult
import com.xjtu.toolbox.agent.AgentTransport
import kotlinx.serialization.json.JsonObject

/**
 * 屁岱在桌面端的**夹具上游**（`:desktop:test` 与 `renderScreens` 共用一份）。
 *
 * ## 为什么需要它
 *
 * 这条链上只有一个真·外部依赖：`POST {baseUrl}/chat/completions` 那一次 SSE。其余每一环
 * （工具层、调度循环、会话落盘、配置落盘）都在本机 —— 所以夹具只扮那一次请求：
 * [FixtureAgentTransport] 按片回调一段**写死的**回复，并把它记成这一轮的聚合结果。
 *
 * 与 [mockYellowPageClient] / [mockFacultyClient] 同一条口径：假的东西只假在「上游」，
 * 而且是**编出来的值**（这句回复不是任何模型说的，`fixture-key` 也不是任何人的密钥）。
 *
 * ## 它不假装什么
 *
 * - **不连任何 LLM**：`stream` 里没有一行网络代码，返回值是常量；
 * - 不该由它回答的两格（起标题、拉模型列表 —— 那两次都是真的模型请求）由
 *   [fixtureAgentHost] 回空，于是这一枪完全离线。
 *
 * 下面那个常量是**夹具回复的定值**（证据图与用例都拿它当判据）。
 */
internal const val AGENT_FIXTURE_REPLY = "接上了：这句回复来自夹具传输，不是任何模型说的。"

/**
 * 按 [chunk] 个字一段回调 [AgentTransport.stream] 的 `onDelta`，再返回聚合结果。
 *
 * 刻意**分段**（而不是一次给全）：`AgentTransport` 的契约是「增量边收边回调」，
 * 用例靠 [deltas] 钉住这一点 —— 一次给全的假传输会让「流式」这条判据变成空话。
 *
 * 回调**就地在调用线程上执行**（真实现会走 `Dispatchers.Main.immediate`）：这一份不碰 UI 库，
 * 夹具不该为了拟真去引一个调度器。
 */
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

/**
 * 一份宿主面：**装配与落盘都是真的**（`DesktopAgentHost` 的每个槽位），只有两处换掉 ——
 *
 *  - [AgentHost.transport] 换成夹具（不连 LLM）；
 *  - [AgentHost.generateTitle] / [AgentHost.fetchModels] 回空：这两格在真宿主机上各打一次
 *    真的模型请求（标题总结 / 模型列表），离线的那一枪不该去打它们。
 *
 * 也就是说：证据图与用例里**除了「模型那一侧」以外，别的都是本端真在跑**。
 */
internal fun fixtureAgentHost(auth: DesktopAuth, transport: AgentTransport): AgentHost =
    object : AgentHost by DesktopAgentHost(auth) {
        override fun transport(): AgentTransport = transport

        override suspend fun generateTitle(config: AgentConfig, userMsg: String, assistantMsg: String): String? = null

        override suspend fun fetchModels(config: AgentConfig): List<String> = emptyList()
    }
