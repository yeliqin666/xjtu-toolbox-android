package com.xjtu.toolbox.agent

import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import com.xjtu.toolbox.util.stringValue
import com.xjtu.toolbox.util.isNull
import com.xjtu.toolbox.util.obj
import com.xjtu.toolbox.util.arr
import com.xjtu.toolbox.util.AppJson
import kotlinx.serialization.json.jsonObject
import com.xjtu.toolbox.network.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 仿 opencode：首条消息发出时（或首轮结束后）用一次轻量 LLM 调用，把对话概括成 ≤8 字的会话标题。
 * 长度靠提示词约束；`max_tokens` 只是思考没关掉时的兜底额度，不管长度；超长的最后由 [sanitizeAgentTitle] 截断。
 * 失败返回 null（调用方退回首条消息截断）。
 *
 * 要点：
 * 1. 显式禁掉思考。DeepSeek 侧 thinking **默认是开的**，必须显式传
 *    `thinking:{"type":"disabled"}` 才关得掉。原来这个参数只在 provider == deepseek 时才发，
 *    可走中转的用户 provider 填的是 custom，等于从没关过——线上看到的思考链标题多半出在这儿。
 *    现在默认就发，只在 400（后端不认这个字段，典型是 OpenAI 官方）时去掉参数重试一次。
 * 2. **不读 `reasoning_content`。** 曾经为了"兼容某些后端把答案塞进 reasoning"加过这个回退，
 *    结果适得其反：推理模型的 content 未产出时，回退会把思考链原文当标题存下来，
 *    再被 max_tokens 从中间截断，于是侧栏里出现「用户想让我根据这段对话生…」这种东西。
 *    拿不到 content 就返回 null，调用方退回"首条用户消息截断"——那个结果永远比思考链好。
 * 3. 输出还要过一遍 [looksLikeMeta]：模型有时即使写在 content 里，也会输出
 *    「用户询问…」这类**转述请求**而不是主题名。这类一律判废，同样退回首条消息。
 * 4. 对常见清单式的标点（顿号、引号、句号）作主动清理。
 * 5. 接口格式跟着对话走（#427）：Responses 走 `/responses`，先带 `reasoning.effort = low` 试，
 *    非推理模型 400 就去掉重试。Chat 路径上 OpenAI 的推理模型（o 系列、gpt-5 / gpt-6 系列）不认 `max_tokens`、
 *    也不接受自定义 temperature，所以对 OpenAI 改发 `max_completion_tokens`、不发 temperature；
 *    降级重试时 `max_tokens`、`temperature` 也一并去掉，免得两次都 400。
 * 6. 已知的 OpenAI 系型号（[OpenAiReasoningCaps]）不再「先试再降级」：直接发它认的最低档
 *    （Sol 是 low、Luna 是 none、gpt-5-mini 是 minimal、gpt-4o 不发），一次请求就完事，
 *    不会每个标题都白挨一次 400。OpenAI 官方上降级重试只留给表里没有的型号；
 *    自定义端点即便型号已知，400 时也去掉参数再试一次（中转 / 代理常不认推理参数）。
 */
object AgentTitleGen {

    private val json = "application/json; charset=utf-8".toMediaType()
    private val client = HttpClients.base.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun generate(config: AgentConfig, userMsg: String, assistantMsg: String): String? =
        withContext(Dispatchers.IO) {
            if (config.apiKey.isBlank()) return@withContext null
            val floor = OpenAiReasoningCaps.floorOf(config)
            if (floor != null) {
                // 已知型号：档位一次发对。OpenAI 官方不留降级重试（400 就是真失败）；
                // 自定义端点（中转 / 代理）可能不认推理参数，400 时去掉参数再试一次，以前拿得到的标题不能丢。
                val official = config.provider == AgentConfig.PROVIDER_OPENAI
                val responses = config.effectiveApiFormat == AgentConfig.API_RESPONSES
                val known = if (responses) {
                    requestResponses(config, userMsg, assistantMsg, effort = floor.wire, retryOn400 = !official)
                } else {
                    requestKnownChat(config, userMsg, assistantMsg, floor, retryOn400 = !official)
                }
                return@withContext when (known) {
                    is Attempt.Done -> known.title
                    Attempt.Rejected -> (
                        if (responses) requestResponses(config, userMsg, assistantMsg, effort = null, retryOn400 = false)
                        else request(config, userMsg, assistantMsg, disableThinking = false)
                    ).let { (it as? Attempt.Done)?.title }
                }
            }
            if (config.effectiveApiFormat == AgentConfig.API_RESPONSES) {
                return@withContext when (val first = requestResponses(config, userMsg, assistantMsg, effort = "low", retryOn400 = true)) {
                    is Attempt.Done -> first.title
                    Attempt.Rejected -> (requestResponses(config, userMsg, assistantMsg, effort = null, retryOn400 = false) as? Attempt.Done)?.title
                }
            }
            // 先带着"关思考"的参数试；只有 400（后端不认这俩字段）才去掉参数重来。
            // 其余失败重试也没用：开着思考、64 token 的额度会被思维链吃光，拿不到标题。
            when (val first = request(config, userMsg, assistantMsg, disableThinking = true)) {
                is Attempt.Done -> first.title
                Attempt.Rejected -> (request(config, userMsg, assistantMsg, disableThinking = false) as? Attempt.Done)?.title
            }
        }

    private sealed interface Attempt {
        /** 请求成功；[title] 为 null 表示没拿到能用的标题。 */
        class Done(val title: String?) : Attempt
        /** HTTP 400：后端不认关思考的参数。 */
        data object Rejected : Attempt
    }

    private fun request(
        config: AgentConfig,
        userMsg: String,
        assistantMsg: String,
        disableThinking: Boolean,
    ): Attempt {
        return run {
            val messages = chatMessages(userMsg, assistantMsg)
            val openAi = config.provider == AgentConfig.PROVIDER_OPENAI
            val reqBody = buildJsonObject {
                put("model", config.effectiveModel)
                put("messages", messages)
                // 关掉思考：标题是直接生成任务，不需要 CoT。
                // thinking 是 DeepSeek 侧的开关，reasoning_effort=none 是 OpenAI 兼容侧的写法。
                // 都不认就走上面的降级重试——重试那次连额度和 temperature 一起去掉，只留最基本的字段。
                if (disableThinking) {
                    if (openAi) {
                        // OpenAI 推理模型只认 max_completion_tokens，也不接受自定义 temperature
                        put("max_completion_tokens", 64)
                    } else {
                        put("temperature", 0.3)
                        // 32 太紧：思考没关掉的后端连 content 都轮不到就被截断。
                        // 给到 64 仍然极便宜，但正常情况下一定能把标题写完。
                        put("max_tokens", 64)
                        put("thinking", buildJsonObject {
                            put("type", "disabled")
                        })
                    }
                    put("reasoning_effort", "none")
                }
            }
            postChat(config, reqBody, rejectOn400 = disableThinking)
        }
    }

    /**
     * 能力表里有的 OpenAI 系型号走 Chat：只发 `max_completion_tokens` 和最低档 `reasoning_effort`，
     * 不发 temperature、thinking 这些它不认的字段。最低档还要思考（low / minimal）时不设输出上限，
     * 推理 token 也算在上限里，64 会被思考吃光、拿不到正文；长度照样靠提示词和 [sanitizeAgentTitle] 管。
     */
    private fun requestKnownChat(
        config: AgentConfig,
        userMsg: String,
        assistantMsg: String,
        floor: OpenAiReasoningCaps.Floor,
        retryOn400: Boolean,
    ): Attempt {
        val reqBody = buildJsonObject {
            put("model", config.effectiveModel)
            put("messages", chatMessages(userMsg, assistantMsg))
            floor.wire?.let { put("reasoning_effort", it) }
            if (floor == OpenAiReasoningCaps.Floor.NONE || floor == OpenAiReasoningCaps.Floor.NOT_REASONING) {
                put("max_completion_tokens", 64)
            }
        }
        return postChat(config, reqBody, rejectOn400 = retryOn400)
    }

    private fun chatMessages(userMsg: String, assistantMsg: String) = buildJsonArray {
        add(buildJsonObject {
            put("role", "system")
            put("content", SYSTEM_PROMPT)
        })
        add(buildJsonObject {
            put("role", "user")
            put("content", userContent(userMsg, assistantMsg))
        })
    }

    private fun postChat(config: AgentConfig, reqBody: kotlinx.serialization.json.JsonObject, rejectOn400: Boolean): Attempt =
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
                if (resp.code == 400 && rejectOn400) return@use Attempt.Rejected
                Attempt.Done(if (resp.isSuccessful) parseTitle(resp.body?.string()) else null)
            }
        }.getOrElse { Attempt.Done(null) }

    // 光说"概括主题"不够：模型的默认倾向是**转述这次请求**
    // （"用户询问本周是否有考试"），那是摘要不是标题。
    // 所以既要正面给出"名词短语"的形状，也要反面点名禁掉主语开头，再给两个例子对齐。
    private const val SYSTEM_PROMPT =
        "为对话起标题：一个不超过 8 个字的名词短语，写主题，不转述提问。\n" +
            "不以「用户」「助手」「关于」「如何」开头，不含「询问」「想让我」这类动作词；只输出标题，不加标点。\n" +
            "例：问这周有没有考试 → 本周考试；帮我把实验加进日程 → 实验日程"

    // 刚发出还没有回复时只给问题
    private fun userContent(userMsg: String, assistantMsg: String): String =
        "【用户】${userMsg.take(300)}" + assistantMsg.takeIf { it.isNotBlank() }?.let { "\n【助手】${it.take(300)}" }.orEmpty()

    /**
     * Responses 路径：非流式，`store: false`。[effort] 为 null 就不带 `reasoning`。
     * 未知型号先带 low 试（推理模型上省时间省额度），400（非推理模型不认）就去掉重试；
     * 已知型号由调用方直接给对档位，[retryOn400] = false。
     * 不设输出上限：推理 token 也算在上限里，卡死了反而拿不到正文。
     */
    private fun requestResponses(
        config: AgentConfig,
        userMsg: String,
        assistantMsg: String,
        effort: String?,
        retryOn400: Boolean,
    ): Attempt {
        val reqBody = buildJsonObject {
            put("model", config.effectiveModel)
            put("instructions", SYSTEM_PROMPT)
            put("input", userContent(userMsg, assistantMsg))
            put("store", false)
            effort?.let { put("reasoning", buildJsonObject { put("effort", it) }) }
        }
        return runCatching {
            client.newCall(
                Request.Builder()
                    .url("${config.effectiveBaseUrl}/responses")
                    .header("Authorization", "Bearer ${config.apiKey}")
                    .header("Content-Type", "application/json")
                    .post(reqBody.toString().toRequestBody(json))
                    .build()
            ).execute().use { resp ->
                if (resp.code == 400 && retryOn400) return@use Attempt.Rejected
                Attempt.Done(if (resp.isSuccessful) parseResponsesTitle(resp.body?.string()) else null)
            }
        }.getOrElse { Attempt.Done(null) }
    }

    /** Responses 的 `output` 里只取 `message` 条目的 `output_text`；`reasoning` 条目是摘要，不是答案。 */
    private fun parseResponsesTitle(body: String?): String? {
        body ?: return null
        val output = AppJson.parseToJsonElement(body).jsonObject.arr("output") ?: return null
        val raw = output.mapNotNull { it as? kotlinx.serialization.json.JsonObject }
            .filter { it["type"]?.stringValue == "message" }
            .flatMap { it.arr("content").orEmpty() }
            .mapNotNull { it as? kotlinx.serialization.json.JsonObject }
            .filter { it["type"]?.stringValue == "output_text" }
            .joinToString("") { it["text"]?.stringValue.orEmpty() }
        return cleanTitle(raw)
    }

    private fun parseTitle(body: String?): String? {
        body ?: return null
        val msg = AppJson.parseToJsonElement(body).jsonObject
            .arr("choices")?.get(0)?.jsonObject
            ?.obj("message") ?: return null
        // 只认 content。reasoning_content 是思考链，不是答案，见类注释第 2 条。
        return cleanTitle(msg.get("content")?.takeIf { !it.isNull }?.stringValue)
    }

    private fun cleanTitle(content: String?): String? {
        val raw = content?.takeIf { it.isNotBlank() } ?: return null
        // 推理模型偶尔把最终答案跟在思考后面并用换行分隔，取最后一行非空文本。
        val lastLine = raw.trim().lines().lastOrNull { it.isNotBlank() }?.trim()
            ?: return null
        return lastLine
            .trim('"', '\u201c', '\u201d', '\u300c', '\u300d', '\u300a', '\u300b', '。', '.', ' ')
            .takeIf { it.isNotBlank() && !looksLikeMeta(it) }
            ?.let { sanitizeAgentTitle(it, "", AGENT_TITLE_MAX_CHARS) }
            ?.takeIf { it.isNotBlank() }
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
