package com.xjtu.toolbox.agent

import kotlinx.serialization.json.JsonObject

/** 模型下发的一次工具调用。 */
class AgentToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

/** 一次流式（SSE）请求的聚合结果：正文、思维链、工具调用、结束原因、总 token。 */
class AgentStreamResult(
    val content: String,
    val reasoningContent: String,
    val toolCalls: List<AgentToolCall>,
    val finishReason: String,
    val totalTokens: Long?,
)

/** 「把一次 chat/completions 请求发出去、按 SSE 增量收回来」这一格缝。 */
interface AgentTransport {

    suspend fun stream(
        config: AgentConfig,
        reqBody: JsonObject,
        onDelta: suspend (String) -> Unit,
        onReasoningDelta: suspend (String) -> Unit,
    ): AgentStreamResult

    companion object {

        /** 没接传输层的端：真去发请求就是 Bug，所以抛。 */
        val None: AgentTransport = object : AgentTransport {
            override suspend fun stream(
                config: AgentConfig,
                reqBody: JsonObject,
                onDelta: suspend (String) -> Unit,
                onReasoningDelta: suspend (String) -> Unit,
            ): AgentStreamResult = throw IllegalStateException(
                "这一端没有接 LLM 传输层（见 AgentHost.transport）"
            )
        }
    }
}

/** 调度循环要工具层做的三件事：报出工具清单、报出限流分组键、执行一次调用。 */
interface AgentTools {

    /** OpenAI function calling 格式的工具描述（JSON 串，送给模型的那一份）。 */
    val toolDefinitions: String

    /** 这次调用打的是哪个后端系统（限流用）。null = 纯本地工具，不需要间隔。 */
    fun rateLimitKeyOf(toolName: String): String?

    /** 执行一次工具调用，返回给模型看的文本。失败就抛，由循环决定怎么记。 */
    suspend fun execute(name: String, argsJson: String): String

    // ── 屏那几件（不是循环要的，是 `AgentViewModel` 要的）────────────────────

    /** 取走这一轮生成的卡片（课表、成绩、空教室、图书馆座位……）并清空。 */
    fun drainWidgets(): List<AgentWidget> = emptyList()

    /** 用户画像那一段（姓名／学院／身份），拼进系统提示。没接的端是空串。 */
    suspend fun userContext(allowNetwork: Boolean): String = ""

    /** 记住的用户偏好那一段，拼进系统提示。 */
    fun memoryBlock(): String = ""

    companion object {

        /** 什么都没接的工具层：画不出工具、执行一律回 `not_found`。 */
        val None: AgentTools = object : AgentTools {
            override val toolDefinitions: String = "[]"
            override fun rateLimitKeyOf(toolName: String): String? = null
            override suspend fun execute(name: String, argsJson: String): String =
                ToolReply.notFound("tool", name)
        }
    }
}

/** 按 DeepSeek 错误码表转成能照着办的中文；400/422 等参数类错误保留服务端原文，便于排查。 */
fun agentHttpErrorText(code: Int, serverMessage: String): String = when (code) {
    401 -> "API Key 无效，请到屁岱设置里检查。"
    402 -> "AI 服务商账户余额不足，请先充值。"
    429 -> "请求太频繁，请稍后再试。"
    500, 502, 503 -> "AI 服务商暂时繁忙（HTTP $code），请稍后再试。"
    else -> "LLM 请求失败：$serverMessage"
}
