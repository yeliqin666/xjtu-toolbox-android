package com.xjtu.toolbox.agent

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

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
        onToolCall: (name: String) -> Unit = {}
    ): String {
        val toolDefs = JsonParser.parseString(tools.toolDefinitions).asJsonArray
        var toolCallCount = 0

        while (true) {
            // 预算用尽后这一轮不再带 tools：模型无工具可用，只能用已有信息直接作答（而非罢工）
            val allowTools = toolCallCount < config.maxToolCalls
            val reqBody = JsonObject().apply {
                addProperty("model", config.effectiveModel)
                add("messages", messages)
                if (allowTools) {
                    add("tools", toolDefs)
                    addProperty("tool_choice", "auto")
                }
            }

            // 同步网络请求必须在 IO 线程执行：调用方（ViewModel）运行于 Main，
            // 否则 OkHttp 的 execute() 会抛 NetworkOnMainThreadException。
            val (response, body) = withContext(Dispatchers.IO) {
                val resp = httpClient.newCall(
                    Request.Builder()
                        .url("${config.effectiveBaseUrl}/chat/completions")
                        .header("Authorization", "Bearer ${config.apiKey}")
                        .header("Content-Type", "application/json")
                        .post(reqBody.toString().toRequestBody(json))
                        .build()
                ).execute()
                resp to resp.body?.string()
            }
            if (body == null) throw RuntimeException("LLM 响应为空（HTTP ${response.code}）")
            if (!response.isSuccessful) {
                val errMsg = runCatching {
                    JsonParser.parseString(body).asJsonObject
                        .getAsJsonObject("error")?.get("message")?.asString
                }.getOrNull() ?: "HTTP ${response.code}"
                throw RuntimeException("LLM 请求失败：$errMsg")
            }

            val parsed = JsonParser.parseString(body).asJsonObject
            val choice = parsed.getAsJsonArray("choices").get(0).asJsonObject
            val finishReason = choice.get("finish_reason")?.asString ?: "stop"
            val message = choice.getAsJsonObject("message")

            val hasToolCalls = message.has("tool_calls") && !message.get("tool_calls").isJsonNull

            // 先拦截异常终止原因（必须在 !hasToolCalls 判断之前，否则无 tool_calls 时直接短路）
            if (finishReason == "length") return "回复被截断（超出模型上下文限制）。请新开对话或缩短问题。"
            if (finishReason == "content_filter") return "回复被内容过滤拦截。"

            // 正常结束，或本轮未带 tools（预算用尽），或模型未请求工具：写入最终 assistant 消息并返回
            if (!allowTools || finishReason == "stop" || !hasToolCalls) {
                messages.add(message)
                return message.get("content")?.asString ?: "（无回复）"
            }

            // 将 assistant 消息追加到历史（含 tool_calls 字段）
            messages.add(message)

            // 执行所有工具调用，结果写回历史
            val toolCalls = message.getAsJsonArray("tool_calls")
            for (tc in toolCalls) {
                val tcObj = tc.asJsonObject
                val callId = tcObj.get("id").asString
                val funcObj = tcObj.getAsJsonObject("function")
                val funcName = funcObj.get("name").asString
                val funcArgs = funcObj.get("arguments")?.asString ?: ""

                onToolCall(funcName)
                if (toolCallCount > 0) delay(1000L)

                val result = runCatching { tools.execute(funcName, funcArgs) }
                    .getOrElse { e ->
                        if (e is com.xjtu.toolbox.auth.AuthExpiredException) throw e
                        "工具调用出错：${e.message}"
                    }
                toolCallCount++

                messages.add(JsonObject().apply {
                    addProperty("role", "tool")
                    addProperty("tool_call_id", callId)
                    addProperty("content", result)
                })
            }
        }
    }
}
