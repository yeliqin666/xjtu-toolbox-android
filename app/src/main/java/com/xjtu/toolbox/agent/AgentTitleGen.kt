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
 * 要点：
 * 1. 显式禁掉思考。DeepSeek 侧 thinking **默认是开的**，必须显式传
 *    `thinking:{"type":"disabled"}` 才关得掉。原来这个参数只在 provider == deepseek 时才发，
 *    可走中转的用户 provider 填的是 custom，等于从没关过——线上看到的思考链标题多半出在这儿。
 *    现在默认就发，遇到 400（后端不认这个字段，典型是 OpenAI 官方）再去掉参数重试一次。
 *    标题这一路是"能成最好、不成退回截断"，多一次轻量重试完全划得来。
 * 2. **不读 `reasoning_content`。** 曾经为了"兼容某些后端把答案塞进 reasoning"加过这个回退，
 *    结果适得其反：推理模型的 content 未产出时，回退会把思考链原文当标题存下来，
 *    再被 max_tokens 从中间截断，于是侧栏里出现「用户想让我根据这段对话生…」这种东西。
 *    拿不到 content 就返回 null，调用方退回"首条用户消息截断"——那个结果永远比思考链好。
 * 3. 输出还要过一遍 [looksLikeMeta]：模型有时即使写在 content 里，也会输出
 *    「用户询问…」这类**转述请求**而不是主题名。这类一律判废，同样退回首条消息。
 * 4. 对常见清单式的标点（顿号、引号、句号）作主动清理。
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
            // 先带着"关思考"的参数试；后端不认就退化成裸请求再来一次。
            request(config, userMsg, assistantMsg, disableThinking = true)
                ?: request(config, userMsg, assistantMsg, disableThinking = false)
        }

    private fun request(
        config: AgentConfig,
        userMsg: String,
        assistantMsg: String,
        disableThinking: Boolean,
    ): String? {
        return run {
            val messages = JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "system")
                    addProperty("content",
                        // 光说"概括主题"不够：模型的默认倾向是**转述这次请求**
                        // （"用户询问本周是否有考试"），那是摘要不是标题。
                        // 所以既要正面给出"名词短语"的形状，也要反面点名禁掉主语开头，再给两个例子对齐。
                        "你为一段对话起标题。只输出标题本身，不超过12个汉字。\n" +
                            "标题是**名词短语**，直接写对话的主题；不是对这次提问的转述。\n" +
                            "禁止以「用户」「助手」「关于」「如何」开头，禁止出现「询问」「想让我」「生成」等描述行为的词。\n" +
                            "不要引号、句号、解释或前后缀。\n" +
                            "示例：\n" +
                            "对话在问这周有没有考试 → 本周考试安排\n" +
                            "对话在查校园卡余额和消费 → 校园卡余额"
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
                // 32 太紧：思考没关掉的后端连 content 都轮不到就被截断。
                // 给到 64 仍然极便宜，但正常情况下一定能把标题写完。
                addProperty("max_tokens", 64)
                // 关掉思考：标题是直接生成任务，不需要 CoT。
                // 两个字段一起发，覆盖两套约定：thinking 是 DeepSeek 侧的开关，
                // reasoning_effort=none 是 OpenAI 兼容侧的写法。都不认就走上面的降级重试。
                if (disableThinking) {
                    add("thinking", JsonObject().apply {
                        addProperty("type", "disabled")
                    })
                    addProperty("reasoning_effort", "none")
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
                    // 400 基本就是"这个后端不认那两个字段"，交给外层去掉参数重试。
                    if (!resp.isSuccessful) return@use null
                    val body = resp.body?.string() ?: return@use null
                    val msg = JsonParser.parseString(body).asJsonObject
                        .getAsJsonArray("choices")?.get(0)?.asJsonObject
                        ?.getAsJsonObject("message") ?: return@use null
                    // 只认 content。reasoning_content 是思考链，不是答案，见类注释第 2 条。
                    val raw = msg.get("content")?.takeIf { !it.isJsonNull }?.asString
                        ?.takeIf { it.isNotBlank() }
                        ?: return@use null
                    // 推理模型偶尔把最终答案跟在思考后面并用换行分隔，取最后一行非空文本。
                    val lastLine = raw.trim().lines().lastOrNull { it.isNotBlank() }?.trim()
                        ?: return@use null
                    lastLine
                        .trim('"', '\u201c', '\u201d', '\u300c', '\u300d', '\u300a', '\u300b', '。', '.', ' ')
                        .takeIf { it.isNotBlank() && !looksLikeMeta(it) }
                        ?.let { sanitizeAgentTitle(it, "") }
                        ?.takeIf { it.isNotBlank() }
                }
            }.getOrNull()
            }
    }

    /**
     * 判断输出是"转述这次请求"而不是"对话主题"。
     *
     * 命中任何一条就整条判废，宁可退回首条用户消息截断——那个虽然笨，但至少说的是
     * 用户自己的话，不会出现「用户想让我根据这段对话生…」这种把模型内心戏挂在侧栏上的效果。
     */
    private fun looksLikeMeta(title: String): Boolean {
        val metaPrefixes = listOf("用户", "助手", "这段对话", "该对话", "本次对话", "根据", "以下是", "标题")
        if (metaPrefixes.any { title.startsWith(it) }) return true
        val metaWords = listOf("想让我", "询问", "生成标题", "这轮对话", "概括", "总结如下")
        return metaWords.any { title.contains(it) }
    }
}
