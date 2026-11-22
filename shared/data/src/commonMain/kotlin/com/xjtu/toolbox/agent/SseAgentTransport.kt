package com.xjtu.toolbox.agent

import com.xjtu.toolbox.network.HttpClients
import com.xjtu.toolbox.util.AppJson
import com.xjtu.toolbox.util.arr
import com.xjtu.toolbox.util.intValue
import com.xjtu.toolbox.util.isNull
import com.xjtu.toolbox.util.isObject
import com.xjtu.toolbox.util.longValue
import com.xjtu.toolbox.util.obj
import com.xjtu.toolbox.util.stringValue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** [AgentTransport] 的实现。 */
class SseAgentTransport : AgentTransport {

    /** 增量回调派发器：优先 `Dispatchers.Main.immediate`；没有 Main 时取属性就抛，所以包住并退回 [Dispatchers.Default]。 */
    private val callbackDispatcher: CoroutineDispatcher =
        runCatching { Dispatchers.Main.immediate }.getOrElse { Dispatchers.Default }

    /** 流式累积一个 tool_call（OpenAI 把 id/name/arguments 分片下发，按 index 聚合）。 */
    private class ToolCallAcc(var id: String = "", var name: String = "") {
        val args = StringBuilder()
        val arguments: String get() = args.toString()
    }

    /**
     * 请求带了推理参数却被 400：非推理模型不认、组织没验证不给推理摘要，
     * 或者用户自己接的中转 / 代理压根不认这些字段（错误文案五花八门，不一定提到 reasoning）。
     */
    private class ReasoningRejected(userMessage: String, val serverMessage: String) : RuntimeException(userMessage)

    override suspend fun stream(
        config: AgentConfig,
        reqBody: JsonObject,
        onDelta: suspend (String) -> Unit,
        onReasoningDelta: suspend (String) -> Unit,
    ): AgentStreamResult {
        var emitted = false
        var body = reqBody
        var attempt = 0
        var downgrades = 0
        while (true) {
            try {
                return streamOnce(config, body,
                    onDelta = { if (it.isNotEmpty()) emitted = true; onDelta(it) },
                    onReasoningDelta = { if (it.isNotEmpty()) emitted = true; onReasoningDelta(it) },
                )
            } catch (e: ReasoningRejected) {
                // Responses：先只去掉 summary（组织未验证），还不行再整个去掉 reasoning；
                // Chat：去掉 reasoning_effort 重试一次。推理参数去光了还 400 就是别的错，原样抛。
                val next = downgradeReasoning(body, e.serverMessage)
                if (next == null || downgrades >= 2) throw RuntimeException(e.message)
                body = next
                downgrades++
            } catch (e: java.io.IOException) {
                currentCoroutineContext().ensureActive()
                // 尚未展示输出、也没有执行工具，重连不会重复写入或拼接重复回答。
                if (emitted || attempt == 2) throw e
                attempt++
                delay(1_200L * attempt)
            }
        }
    }

    private fun downgradeReasoning(body: JsonObject, serverMessage: String): JsonObject? {
        if (REASONING_EFFORT in body) return JsonObject(body - REASONING_EFFORT)
        val reasoning = body["reasoning"] as? JsonObject ?: return null
        if ("summar" in serverMessage.lowercase() && "summary" in reasoning) {
            return JsonObject(body + ("reasoning" to JsonObject(reasoning - "summary")))
        }
        return JsonObject(body - "reasoning")
    }

    /**
     * 这次 400 值不值得去掉推理参数重试：Responses 看 `reasoning`；Chat 看 `reasoning_effort`。
     * DeepSeek 官方本来就认 thinking / reasoning_effort，它的 400 是别的问题，不重试。
     */
    private fun carriesDroppableReasoning(config: AgentConfig, responses: Boolean, body: JsonObject): Boolean = when {
        responses -> body["reasoning"] != null
        config.provider == AgentConfig.PROVIDER_DEEPSEEK -> false
        else -> body[REASONING_EFFORT] != null
    }

    private suspend fun streamOnce(
        config: AgentConfig,
        reqBody: JsonObject,
        onDelta: suspend (String) -> Unit,
        onReasoningDelta: suspend (String) -> Unit,
    ): AgentStreamResult = withContext(Dispatchers.IO) {
        val responses = config.effectiveApiFormat == AgentConfig.API_RESPONSES
        val call = httpClient.newCall(
            Request.Builder()
                .url("${config.effectiveBaseUrl}${if (responses) "/responses" else "/chat/completions"}")
                .header("Authorization", "Bearer ${config.apiKey}")
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .post(reqBody.toString().toRequestBody(JSON))
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
            cancellationWatcher.cancel()
            currentCoroutineContext().ensureActive()
            throw e
        }

        try {
            resp.use {
                if (!resp.isSuccessful) {
                    val errBody = resp.body?.string().orEmpty()
                    val errMsg = runCatching {
                        AppJson.parseToJsonElement(errBody).jsonObject
                            .obj("error")?.get("message")?.stringValue
                    }.getOrNull() ?: "HTTP ${resp.code}"
                    if (resp.code == 400 && carriesDroppableReasoning(config, responses, reqBody)) {
                        throw ReasoningRejected(agentHttpErrorText(resp.code, errMsg), errMsg)
                    }
                    throw RuntimeException(agentHttpErrorText(resp.code, errMsg))
                }
                val source = resp.body?.source() ?: throw RuntimeException("LLM 响应为空")
                if (responses) return@withContext readResponses(source, onDelta, onReasoningDelta)
                val contentSb = StringBuilder()
                val reasoningSb = StringBuilder()
                val toolMap = sortedMapOf<Int, ToolCallAcc>()
                var finishReason = "stop"
                var completed = false
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
                    if (data == "[DONE]") { completed = true; break }
                    val chunk = runCatching { AppJson.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                    chunk.get("usage")?.takeIf { !it.isNull && it.isObject }?.jsonObject
                        ?.get("total_tokens")
                        ?.takeIf { !it.isNull }?.longValue?.let { totalTokens = it }
                    val choices = chunk.arr("choices") ?: continue
                    if (choices.size == 0) continue
                    val choice = choices[0].jsonObject
                    choice.get("finish_reason")?.takeIf { !it.isNull }?.stringValue?.let { finishReason = it; completed = true }
                    val delta = choice.obj("delta") ?: continue

                    // DeepSeek 系放在 reasoning_content；部分中转站和新版 vLLM 放在 reasoning（#427）
                    sequenceOf("reasoning_content", "reasoning")
                        .mapNotNull { (delta.get(it) as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                        .firstOrNull { it.isNotEmpty() }
                        ?.let {
                            reasoningSb.append(it)
                            withContext(callbackDispatcher) { onReasoningDelta(it) }
                        }
                    delta.get("content")?.takeIf { !it.isNull }?.stringValue?.let { frag ->
                        if (frag.isNotEmpty()) {
                            contentSb.append(frag)
                            withContext(callbackDispatcher) { onDelta(frag) }
                        }
                    }
                    delta.get("tool_calls")?.takeIf { !it.isNull }?.jsonArray?.forEach { el ->
                        val o = el.jsonObject
                        val idx = o.get("index")?.takeIf { !it.isNull }?.intValue ?: 0
                        val acc = toolMap.getOrPut(idx) { ToolCallAcc() }
                        o.get("id")?.takeIf { !it.isNull }?.stringValue?.let { acc.id = it }
                        o.obj("function")?.let { f ->
                            f.get("name")?.takeIf { !it.isNull }?.stringValue?.let { acc.name = it }
                            f.get("arguments")?.takeIf { !it.isNull }?.stringValue?.let { acc.args.append(it) }
                        }
                    }
                }
                if (!completed) throw java.io.IOException("连接中断，回复尚未完成，请重试")
                return@withContext AgentStreamResult(
                    contentSb.toString(),
                    reasoningSb.toString(),
                    toolMap.values.filter { it.name.isNotBlank() }
                        .map { AgentToolCall(it.id, it.name, it.arguments) },
                    finishReason,
                    totalTokens
                )
            }
        } finally {
            cancellationWatcher.cancel()
        }
    }

    /**
     * Responses API 的流式事件（#427）。每行 `data:` 是一个带 `type` 的事件：
     * - `response.output_text.delta`：正文增量；
     * - `response.reasoning_summary_text.delta`：推理摘要增量（兼容端点的 `response.reasoning_text.delta` 也认），
     *   换了一段摘要时补一个空行；
     * - `response.output_item.added` / `.done`：`function_call` 条目的 call_id、name（done 里带完整 arguments）；
     * - `response.function_call_arguments.delta` / `.done`：参数分片，按 output_index 聚合；
     * - `response.completed` / `response.incomplete`：结束，带 usage 与终止原因；
     * - `response.failed` / `error`：直接抛。
     * 没等到结束事件就断了，和 Chat 路径一样抛 IOException，交给外层决定要不要重连。
     */
    private suspend fun readResponses(
        source: okio.BufferedSource,
        onDelta: suspend (String) -> Unit,
        onReasoningDelta: suspend (String) -> Unit,
    ): AgentStreamResult {
        val contentSb = StringBuilder()
        val reasoningSb = StringBuilder()
        val calls = sortedMapOf<Int, ToolCallAcc>()
        val indexOfItem = mutableMapOf<String, Int>()
        var lastSummaryPart: String? = null
        var finishReason = "stop"
        var completed = false
        var totalTokens: Long? = null

        fun JsonObject.str(key: String): String =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
        fun indexOf(ev: JsonObject): Int? = ev.int("output_index") ?: indexOfItem[ev.str("item_id")]

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
            val ev = runCatching { AppJson.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
            when (val type = ev.str("type")) {
                "response.output_text.delta" -> {
                    val frag = ev.str("delta")
                    if (frag.isNotEmpty()) {
                        contentSb.append(frag)
                        withContext(callbackDispatcher) { onDelta(frag) }
                    }
                }
                "response.reasoning_summary_text.delta", "response.reasoning_text.delta" -> {
                    val frag = ev.str("delta")
                    if (frag.isNotEmpty()) {
                        val part = ev.str("item_id") + ":" + (ev.int("summary_index") ?: ev.int("content_index") ?: 0)
                        val text = if (lastSummaryPart != null && part != lastSummaryPart && reasoningSb.isNotEmpty()) "\n\n$frag" else frag
                        lastSummaryPart = part
                        reasoningSb.append(text)
                        withContext(callbackDispatcher) { onReasoningDelta(text) }
                    }
                }
                "response.output_item.added", "response.output_item.done" -> {
                    val item = ev.obj("item")
                    if (item != null && item.str("type") == "function_call") {
                        val idx = ev.int("output_index") ?: calls.size
                        val acc = calls.getOrPut(idx) { ToolCallAcc() }
                        item.str("call_id").takeIf { it.isNotBlank() }?.let { acc.id = it }
                        item.str("name").takeIf { it.isNotBlank() }?.let { acc.name = it }
                        item.str("id").takeIf { it.isNotBlank() }?.let { indexOfItem[it] = idx }
                        if (type == "response.output_item.done") {
                            item.str("arguments").takeIf { it.isNotEmpty() }?.let { acc.args.setLength(0); acc.args.append(it) }
                        }
                    }
                }
                "response.function_call_arguments.delta" -> {
                    indexOf(ev)?.let { calls.getOrPut(it) { ToolCallAcc() }.args.append(ev.str("delta")) }
                }
                "response.function_call_arguments.done" -> {
                    val idx = indexOf(ev)
                    val args = ev.str("arguments")
                    if (idx != null && args.isNotEmpty()) calls.getOrPut(idx) { ToolCallAcc() }.args.apply { setLength(0); append(args) }
                }
                "response.completed", "response.incomplete" -> {
                    val r = ev.obj("response")
                    (r?.obj("usage")?.get("total_tokens") as? JsonPrimitive)?.longOrNull?.let { totalTokens = it }
                    finishReason = when {
                        type == "response.incomplete" -> when (r?.obj("incomplete_details")?.str("reason")) {
                            "content_filter" -> "content_filter"
                            else -> "length"
                        }
                        calls.values.any { it.name.isNotBlank() } -> "tool_calls"
                        else -> "stop"
                    }
                    completed = true
                    break
                }
                "response.failed", "error" -> {
                    val msg = ev.obj("response")?.obj("error")?.str("message")?.takeIf { it.isNotBlank() }
                        ?: ev.str("message").takeIf { it.isNotBlank() }
                        ?: ev.obj("error")?.str("message")?.takeIf { it.isNotBlank() }
                        ?: "未知错误"
                    throw RuntimeException("LLM 请求失败：$msg")
                }
            }
        }
        if (!completed) throw java.io.IOException("连接中断，回复尚未完成，请重试")
        return AgentStreamResult(
            contentSb.toString(),
            reasoningSb.toString(),
            calls.values.filter { it.name.isNotBlank() }
                .map { AgentToolCall(it.id, it.name, it.arguments.ifBlank { "{}" }) },
            finishReason,
            totalTokens,
        )
    }

    companion object {
        /** 共享连接池：每次 sendMessage 重用同一 OkHttpClient，避免泄漏。 */
        private val httpClient = HttpClients.base.newBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)   // 流式期间连接需保持更久
            .build()

        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val REASONING_EFFORT = "reasoning_effort"
    }
}
