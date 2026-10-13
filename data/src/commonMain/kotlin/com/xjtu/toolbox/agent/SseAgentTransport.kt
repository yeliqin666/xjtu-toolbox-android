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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * [AgentTransport] 的实现：`POST {baseUrl}/chat/completions`，按 SSE 边收边回调，聚合 tool_calls。
 *
 * 这一份是从 `:app` 的 `AgentRunner.streamOnce` **逐行搬出来的**（连同它用的两格成员：共享的
 * `httpClient` 与 `application/json` media type）—— [AgentRunner] 搬进 `:core` 时唯一不跟着走的
 * 就是这段网络代码。搬动本身零成本：Android 与桌面**共用同一份实现**（两端都依赖 `:data`，
 * 而 `:data` 的网络栈就是 `HttpClients.base`，与搬迁前是同一个 OkHttpClient 配置：
 * connect 30s / read 120s、UA、连接池），所以 Android 的行为逐字未变。
 *
 * ## 为什么不是 `:core` 里的一份实现
 *
 * `:core` 有三个目标（含 wasmJs），okhttp 只发布 jvm/android 变体 —— 进不去。这也是
 * [AgentTransport] 这一步缝存在的原因：循环侧的签名里不出现任何 HTTP 客户端类型。
 *
 * ## 责任（与 [AgentTransport] 的 KDoc 对齐）
 *
 *  - 增量当场回调（不是攒完再一次给），回调走 `Dispatchers.Main.immediate`；
 *  - 协程被取消时 `call.cancel()`（[cancellationWatcher]），别让连接在后台把 token 烧完；
 *  - IO 由这一层自己包（循环侧不再 `withContext(Dispatchers.IO)`）—— 网络边界的调度是传输层的事。
 */
class SseAgentTransport : AgentTransport {

    /** 流式累积一个 tool_call（OpenAI 把 id/name/arguments 分片下发，按 index 聚合）。 */
    private class ToolCallAcc(var id: String = "", var name: String = "") {
        val args = StringBuilder()
        val arguments: String get() = args.toString()
    }

    override suspend fun stream(
        config: AgentConfig,
        reqBody: JsonObject,
        onDelta: suspend (String) -> Unit,
        onReasoningDelta: suspend (String) -> Unit,
    ): AgentStreamResult = withContext(Dispatchers.IO) {
        val call = httpClient.newCall(
            Request.Builder()
                .url("${config.effectiveBaseUrl}/chat/completions")
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
                    throw RuntimeException(agentHttpErrorText(resp.code, errMsg))
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
                    val chunk = runCatching { AppJson.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                    chunk.get("usage")?.takeIf { !it.isNull && it.isObject }?.jsonObject
                        ?.get("total_tokens")
                        ?.takeIf { !it.isNull }?.longValue?.let { totalTokens = it }
                    val choices = chunk.arr("choices") ?: continue
                    if (choices.size == 0) continue
                    val choice = choices[0].jsonObject
                    choice.get("finish_reason")?.takeIf { !it.isNull }?.stringValue?.let { finishReason = it }
                    val delta = choice.obj("delta") ?: continue

                    delta.get("reasoning_content")?.takeIf { !it.isNull }?.stringValue?.let {
                        reasoningSb.append(it)
                        withContext(Dispatchers.Main.immediate) { onReasoningDelta(it) }
                    }
                    delta.get("content")?.takeIf { !it.isNull }?.stringValue?.let { frag ->
                        if (frag.isNotEmpty()) {
                            contentSb.append(frag)
                            withContext(Dispatchers.Main.immediate) { onDelta(frag) }
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

    companion object {
        /**
         * 共享连接池：每次 sendMessage 重用同一 OkHttpClient，避免泄漏。
         * 与 `:app` 时代同一份配置（同一个 `HttpClients.base` 派生），一个字未改。
         */
        private val httpClient = HttpClients.base.newBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)   // 流式期间连接需保持更久
            .build()

        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
