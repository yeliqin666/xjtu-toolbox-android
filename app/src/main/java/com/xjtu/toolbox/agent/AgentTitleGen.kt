package com.xjtu.toolbox.agent

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 仿 opencode：首轮对话后用一次轻量 LLM 调用，把对话概括成 ≤12 字的会话标题。
 * 失败返回 null（调用方退回首条消息截断）。
 *
 * 修复要点：
 * 1. 显式禁掉 `thinking`：DeepSeek V4 默认开启推理，但标题不需要思考链，
 *    否则白白多 1-2 秒延迟 + 一笔额外 token。
 * 2. 解析时同时读 `content` 和 `reasoning_content`，以防某些后端把完整答案塞进 reasoning。
 * 3. 对常见清单式的标点（顿号、引号、句号）作主动清理。
 */
object AgentTitleGen {

    private val json = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun generate(config: AgentConfig, userMsg: String, assistantMsg: String): String? =
        withContext(Dispatchers.IO) {
            if (config.apiKey.isBlank()) return@withContext null
            val messages = JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "system")
                    addProperty("content",
                        "你是会话标题生成器。根据下面的一轮对话，输出一个不超过12个汉字、概括主题的简短标题。" +
                            "只输出标题本身，不要引号、标点、解释或前后缀。"
                    )
                })
                add(JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content",
                        "【用户】${userMsg.take(300)}\n【助手】${assistantMsg.take(300)}"
                    )
                })
            }
            val reqBody = JsonObject().apply {
                addProperty("model", config.effectiveModel)
                add("messages", messages)
                addProperty("temperature", 0.3)
                addProperty("max_tokens", 32)
                // 关掉思考：标题是直接生成任务，不需要 CoT
                if (config.provider == AgentConfig.PROVIDER_DEEPSEEK) {
                    add("thinking", JsonObject().apply {
                        addProperty("type", "disabled")
                    })
                }
            }
            runCatching {
                client.newCall(
                    Request.Builder()
                        .url("${config.effectiveBaseUrl}/chat/completions")
                        .header("Authorization", "Bearer ${config.apiKey}")
                        .header("Content-Type", "application/json")
                        .post(reqBody.toString().toRequestBody(json))
                        .build()
                ).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val body = resp.body?.string() ?: return@use null
                    val msg = JsonParser.parseString(body).asJsonObject
                        .getAsJsonArray("choices")?.get(0)?.asJsonObject
                        ?.getAsJsonObject("message") ?: return@use null
                    // 先读 content，再回退到 reasoning_content（兼容部分后端把答案放在 reasoning）
                    val raw = msg.get("content")?.takeIf { !it.isJsonNull }?.asString
                        ?: msg.get("reasoning_content")?.takeIf { !it.isJsonNull }?.asString
                        ?: return@use null
                    raw.trim()
                        .trim('"', '\u201c', '\u201d', '\u300c', '\u300d', '\u300a', '\u300b', '。', '.', ' ', '\n')
                        .replace("\n", " ")
                        .let { sanitizeAgentTitle(it, "") }
                        ?.takeIf { it.isNotBlank() }
                }
            }.getOrNull()
        }
}
