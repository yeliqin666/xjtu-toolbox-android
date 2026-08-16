package com.xjtu.toolbox.agent

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * OpenAI-compatible function calling 调度循环。
 *
 * 安全限制：
 * - 每轮最多 maxToolCalls 次工具调用，超限直接返回提示，不继续请求 LLM
 * - 两次工具调用之间强制等待 1s，防止对学校服务器连续请求
 * - Auth 异常（AuthExpiredException）直接上抛，不让 LLM 自行重试
 *
 * 多轮会话：调用方负责维护 messages（含 system prompt），run() 在原数组上追加
 * assistant/tool 消息，返回后 messages 即为完整历史，可供下一轮复用。
 */
class AgentRunner(private val tools: AgentToolRegistry) {

    companion object {
        // 共享连接池：每次 sendMessage 重用同一 OkHttpClient，避免泄漏
        private val httpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)   // 流式期间连接需保持更久
            .build()

        /** 同一站点两次工具调用的最小间隔。不同站点互不影响。 */
        private const val SAME_SITE_MIN_INTERVAL_MS = 800L

        /**
         * 单条工具结果的字符上限。
         *
         * 这是上下文爆掉的真正来源：`AgentViewModel.truncateHistory()` 按**条数**裁剪，
         * 而一条 get_notifications 可能几千字——条数远没超，token 早就爆了。
         * 与其引入分词器精确算 token（要按模型分，代价和收益不匹配），
         * 不如直接堵住单条巨物，这一条就能覆盖绝大多数情况。
         */
        private const val MAX_TOOL_RESULT_CHARS = 4000

        internal fun capToolResult(result: String): String {
            if (result.length <= MAX_TOOL_RESULT_CHARS) return result
            return result.take(MAX_TOOL_RESULT_CHARS) +
                "\n…（结果过长已截断，共 ${result.length} 字。需要更多请缩小查询范围，" +
                "例如指定日期、学期或关键词后重新调用。）"
        }
    }

    /** 站点 -> 上次调用时刻，用于按站点限流。 */
    private val lastCallAt = mutableMapOf<String, Long>()

    /** 流式累积一个 tool_call（OpenAI 把 id/name/arguments 分片下发，按 index 聚合）。 */
    private class ToolCallAcc(var id: String = "", var name: String = "") {
        val args = StringBuilder()
        val arguments: String get() = args.toString()
    }

    private class StreamResult(
        val content: String,
        val reasoningContent: String,
        val toolCalls: List<ToolCallAcc>,
        val finishReason: String,
        val totalTokens: Long?
    )

    private val json = "application/json; charset=utf-8".toMediaType()

    /**
     * 执行一次用户消息的 agent 循环。
     *
     * @param messages 完整历史数组（已包含 system prompt 和本轮 user 消息）。
     *                 函数会将本轮的 assistant/tool 消息就地追加到此数组，
     *                 调用方持有同一引用，下次调用时历史自动延续。
     */
    suspend fun run(
        messages: JsonArray,
        config: AgentConfig,
        onToolCall: (name: String) -> Unit = {},
        onToolResult: (name: String, success: Boolean, errorMessage: String?) -> Unit = { _, _, _ -> },
        onDelta: suspend (String) -> Unit = {},
        onReasoningDelta: suspend (String) -> Unit = {},
        onUsage: (Long) -> Unit = {}
    ): String {
        val toolDefs = JsonParser.parseString(tools.toolDefinitions).asJsonArray
        var toolCallCount = 0

        while (true) {
            // maxToolCalls <= 0 表示不限次数；否则预算用尽后这一轮不带 tools，逼模型直接作答
            val allowTools = config.maxToolCalls <= 0 || toolCallCount < config.maxToolCalls
            val reqBody = JsonObject().apply {
                addProperty("model", config.effectiveModel)
                add("messages", messagesForProvider(messages, config))
                addProperty("stream", true)
                if (config.provider == AgentConfig.PROVIDER_DEEPSEEK) {
                    add("stream_options", JsonObject().apply { addProperty("include_usage", true) })
                    add("thinking", JsonObject().apply {
                        addProperty("type", if (config.thinkingEnabled) "enabled" else "disabled")
                    })
                    if (config.thinkingEnabled && config.reasoningEffort != AgentConfig.REASONING_AUTO) {
                        addProperty("reasoning_effort", config.reasoningEffort)
                    }
                }
                if (allowTools) {
                    add("tools", toolDefs)
                    addProperty("tool_choice", "auto")
                }
            }

            val sr = withContext(Dispatchers.IO) {
                streamOnce(reqBody, config, onDelta, onReasoningDelta)
            }
            sr.totalTokens?.let(onUsage)

            if (sr.finishReason == "length" && sr.content.isBlank() && sr.toolCalls.isEmpty())
                return "回复被截断（超出模型上下文限制）。请新开对话或缩短问题。"
            if (sr.finishReason == "content_filter" && sr.content.isBlank())
                return "回复被内容过滤拦截。"
            if (sr.finishReason == "insufficient_system_resource" && sr.content.isBlank())
                return "模型推理资源暂时不足，请稍后重试。"

            // 组装 assistant 消息写回历史（保持 OpenAI 结构，供续聊）
            val assistantMsg = JsonObject().apply {
                addProperty("role", "assistant")
                addProperty("content", sr.content)
                if (sr.reasoningContent.isNotBlank()) {
                    addProperty("reasoning_content", sr.reasoningContent)
                }
                if (sr.toolCalls.isNotEmpty()) {
                    add("tool_calls", JsonArray().apply {
                        sr.toolCalls.forEach { tc ->
                            add(JsonObject().apply {
                                addProperty("id", tc.id)
                                addProperty("type", "function")
                                add("function", JsonObject().apply {
                                    addProperty("name", tc.name)
                                    addProperty("arguments", tc.arguments)
                                })
                            })
                        }
                    })
                }
            }

            // 无工具调用（或预算用尽）：本轮即最终回答。
            // 兼容 OpenAI-compatible 后端：有些会在存在 tool_calls 时仍返回 finish_reason=stop，
            // 因此只看实体 toolCalls 是否非空，不迷信 finishReason。
            if (!allowTools || sr.toolCalls.isEmpty()) {
                messages.add(assistantMsg)
                return sr.content.ifBlank { "（无回复）" }
            }

            // 原子提交：先把所有 tool 结果算好，再「assistant(tool_calls) + 全部 tool」一起入历史。
            // 若中途被取消/抛 AuthExpired，则什么都不写——历史不会留下"有 tool_calls 却没 tool 回应"的残体，
            // 避免后续请求永久报 "must be followed by tool messages"。
            val toolResults = ArrayList<JsonObject>(sr.toolCalls.size)
            for (tc in sr.toolCalls) {
                onToolCall(tc.name)
                // 限流按**站点**算，不再全局一刀切。
                // 原来是 `if (toolCallCount > 0) delay(1000L)`：不管这次打的是哪个系统都先等 1 秒。
                // 而连环问（"几点下课 / 哪有空教室 / 图书馆还有座"）恰恰打的是不同系统，
                // 互相之间没有任何限频理由，白白多等好几秒。登录侧的防刷已由 CasGate 全局串行 +
                // 失败退避覆盖，这里只需防"同一站点被连续猛打"。
                tools.rateLimitKeyOf(tc.name)?.let { site ->
                    val last = lastCallAt[site]
                    val now = System.currentTimeMillis()
                    if (last != null && now - last < SAME_SITE_MIN_INTERVAL_MS) {
                        delay(SAME_SITE_MIN_INTERVAL_MS - (now - last))
                    }
                    lastCallAt[site] = System.currentTimeMillis()
                }
                val toolResult = runCatching { tools.execute(tc.name, tc.arguments) }
                val result: String
                val toolErrorMsg: String?
                if (toolResult.isSuccess) {
                    result = toolResult.getOrThrow() as String
                    toolErrorMsg = null
                } else {
                    val e = toolResult.exceptionOrNull()
                    if (e is com.xjtu.toolbox.auth.AuthExpiredException) throw e
                    result = "工具调用出错：${e?.message ?: "未知异常"}"
                    toolErrorMsg = e?.message ?: "未知异常"
                }
                onToolResult(tc.name, toolErrorMsg == null, toolErrorMsg)
                toolCallCount++
                toolResults.add(JsonObject().apply {
                    addProperty("role", "tool")
                    addProperty("tool_call_id", tc.id)
                    addProperty("content", capToolResult(result))
                })
            }
            messages.add(assistantMsg)
            toolResults.forEach { messages.add(it) }
        }
    }

    /**
     * 发起一次流式（SSE）请求并增量回调正文，同时聚合 tool_calls。
     * 协程被取消（用户点"停止"）时 [ensureActive] 抛出，连接随 use 关闭。
     */
    private suspend fun streamOnce(
        reqBody: JsonObject,
        config: AgentConfig,
        onDelta: suspend (String) -> Unit,
        onReasoningDelta: suspend (String) -> Unit
    ): StreamResult = coroutineScope {
        val call = httpClient.newCall(
            Request.Builder()
                .url("${config.effectiveBaseUrl}/chat/completions")
                .header("Authorization", "Bearer ${config.apiKey}")
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .post(reqBody.toString().toRequestBody(json))
                .build()
        )
        val cancellationWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        val resp = try {
            call.execute()
        } catch (e: java.io.IOException) {
            currentCoroutineContext().ensureActive()
            throw e
        }

        try {
            resp.use {
                if (!resp.isSuccessful) {
                    val errBody = resp.body?.string().orEmpty()
                    val errMsg = runCatching {
                        JsonParser.parseString(errBody).asJsonObject
                            .getAsJsonObject("error")?.get("message")?.asString
                    }.getOrNull() ?: "HTTP ${resp.code}"
                    throw RuntimeException("LLM 请求失败：$errMsg")
                }
                val source = resp.body?.source() ?: throw RuntimeException("LLM 响应为空")
                val contentSb = StringBuilder()
                val reasoningSb = StringBuilder()
                val toolMap = sortedMapOf<Int, ToolCallAcc>()
                var finishReason = "stop"
                var totalTokens: Long? = null

                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = try {
                        source.readUtf8Line()
                    } catch (e: java.io.IOException) {
                        currentCoroutineContext().ensureActive()
                        throw e
                    } ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.substring(5).trim()
                    if (data == "[DONE]") break
                    val chunk = runCatching { JsonParser.parseString(data).asJsonObject }.getOrNull() ?: continue
                    chunk.get("usage")?.takeIf { !it.isJsonNull && it.isJsonObject }?.asJsonObject
                        ?.get("total_tokens")
                        ?.takeIf { !it.isJsonNull }?.asLong?.let { totalTokens = it }
                    val choices = chunk.getAsJsonArray("choices") ?: continue
                    if (choices.size() == 0) continue
                    val choice = choices[0].asJsonObject
                    choice.get("finish_reason")?.takeIf { !it.isJsonNull }?.asString?.let { finishReason = it }
                    val delta = choice.getAsJsonObject("delta") ?: continue

                    delta.get("reasoning_content")?.takeIf { !it.isJsonNull }?.asString?.let {
                        reasoningSb.append(it)
                        withContext(Dispatchers.Main.immediate) { onReasoningDelta(it) }
                    }
                    delta.get("content")?.takeIf { !it.isJsonNull }?.asString?.let { frag ->
                        if (frag.isNotEmpty()) {
                            contentSb.append(frag)
                            withContext(Dispatchers.Main.immediate) { onDelta(frag) }
                        }
                    }
                    delta.get("tool_calls")?.takeIf { !it.isJsonNull }?.asJsonArray?.forEach { el ->
                        val o = el.asJsonObject
                        val idx = o.get("index")?.takeIf { !it.isJsonNull }?.asInt ?: 0
                        val acc = toolMap.getOrPut(idx) { ToolCallAcc() }
                        o.get("id")?.takeIf { !it.isJsonNull }?.asString?.let { acc.id = it }
                        o.getAsJsonObject("function")?.let { f ->
                            f.get("name")?.takeIf { !it.isJsonNull }?.asString?.let { acc.name = it }
                            f.get("arguments")?.takeIf { !it.isJsonNull }?.asString?.let { acc.args.append(it) }
                        }
                    }
                }
                return@coroutineScope StreamResult(
                    contentSb.toString(),
                    reasoningSb.toString(),
                    toolMap.values.filter { it.name.isNotBlank() },
                    finishReason,
                    totalTokens
                )
            }
        } finally {
            cancellationWatcher.cancel()
        }
    }

    private fun messagesForProvider(messages: JsonArray, config: AgentConfig): JsonArray {
        if (config.provider == AgentConfig.PROVIDER_DEEPSEEK) return messages
        return JsonArray().apply {
            messages.forEach { el ->
                val src = el.asJsonObject
                add(JsonObject().apply {
                    src.entrySet().forEach { (key, value) ->
                        if (key != "reasoning_content") add(key, value.deepCopy())
                    }
                })
            }
        }
    }
}
