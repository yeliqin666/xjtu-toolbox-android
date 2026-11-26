package com.xjtu.toolbox.agent

import com.xjtu.toolbox.network.HttpClients
import com.xjtu.toolbox.util.AppJson
import com.xjtu.toolbox.util.arr
import com.xjtu.toolbox.util.obj
import com.xjtu.toolbox.util.safeStringOrNull
import com.xjtu.toolbox.util.stringValue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * `web_search` / `web_fetch`：DeepSeek 官方的 Search / Fetch API（`/v1/tools/search`、`/v1/tools/fetch`），
 * 与聊天共用同一个 API Key。各端用 [CompositeAgentTools] 把它并进自己的工具层。
 *
 * @param localFetch 端上自己能读得更好的地址（Android 的公众号文章）。返回 null 表示不归它管，走 DeepSeek。
 */
class DeepSeekWebTools(
    private val apiKey: String,
    private val localFetch: (suspend (url: String) -> String?)? = null,
    private val baseUrl: String = AgentConfig.DEEPSEEK_BASE_URL,
) : AgentTools {

    override val toolDefinitions: String by lazy {
        JsonArray(
            listOf(
                tool(
                    "web_search",
                    "联网搜索，返回标题、URL、来源、日期、摘要。",
                    params(
                        "query" to prop(
                            "string",
                            "关键词，最长 128 字。支持 site:（多个用 OR）、-site:、\"精确短语\"、-排除词、after:/before:（年或日期）。学校官网是 xjtu.edu.cn。",
                        ),
                        "limit" to prop("integer", "条数，默认 8，上限 $MAX_RESULTS。"),
                        required = listOf("query"),
                    ),
                ),
                tool(
                    "web_fetch",
                    "读取网页正文，转 Markdown，约一万字。",
                    params("url" to prop("string", "http(s) URL。"), required = listOf("url")),
                ),
            ),
        ).toString()
    }

    override fun rateLimitKeyOf(toolName: String): String? = "deepseek_web"

    override suspend fun execute(name: String, argsJson: String): String {
        val args = runCatching { AppJson.parseToJsonElement(argsJson.ifBlank { "{}" }).jsonObject }
            .getOrDefault(JsonObject(emptyMap()))
        return when (name) {
            "web_search" -> search(
                query = args["query"].safeStringOrNull().orEmpty().trim(),
                limit = args["limit"].safeStringOrNull()?.toDoubleOrNull()?.toInt() ?: 8,
            )
            "web_fetch" -> fetch(args["url"].safeStringOrNull().orEmpty().trim())
            else -> ToolReply.notFound("tool", name)
        }
    }

    private suspend fun search(query: String, limit: Int): String {
        if (query.isBlank()) return ToolReply.missing("query")
        if (query.length > MAX_QUERY) return ToolReply.outOfRange("query", "1-$MAX_QUERY chars")
        val body = buildJsonObject {
            put("query", query)
            put("count", limit.coerceIn(1, MAX_RESULTS))
        }
        val json = when (val r = post("search", body)) {
            is Outcome.Ok -> r.json
            is Outcome.Failed -> return ToolReply.failed("web_search", r.reason)
        }
        val results = json.arr("results").orEmpty().mapNotNull { runCatching { it.jsonObject }.getOrNull() }
        if (results.isEmpty()) return ToolReply.empty("query: $query")
        return buildString {
            append("「$query」｜${results.size} 条\n")
            // 一条一行、带编号，模型转述时能写「据 [2]」，再用 web_fetch 读原文
            results.forEachIndexed { i, r ->
                append("[${i + 1}] ${r.str("title")}｜${r.str("url")}")
                r.str("site_name").takeIf { it.isNotBlank() }?.let { append("｜$it") }
                r.str("date").takeIf { it.isNotBlank() }?.let { append("｜${it.take(10)}") }
                r.str("summary").ifBlank { r.str("snippet") }.replace(Regex("\\s+"), " ").trim()
                    .takeIf { it.isNotBlank() }?.let { append("｜${it.take(SUMMARY_CHARS)}") }
                append("\n")
            }
            append(ToolReply.EXTERNAL_DATA)
        }
    }

    private suspend fun fetch(url: String): String {
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            return ToolReply.badFormat("url", "http(s)://…")
        }
        localFetch?.invoke(url)?.let { return it }
        val json = when (val r = post("fetch", buildJsonObject { put("url", url) })) {
            is Outcome.Ok -> r.json
            is Outcome.Failed -> return ToolReply.failed("web_fetch", r.reason)
        }
        val content = json.str("content")
        if (content.isBlank()) return ToolReply.empty("url: $url")
        return buildString {
            json.str("title").takeIf { it.isNotBlank() }?.let { append("标题：$it\n") }
            append("URL：$url\n\n正文：\n")
            append(content.take(MAX_CONTENT_CHARS))
            append("\n\n" + ToolReply.EXTERNAL_DATA)
        }
    }

    private sealed interface Outcome {
        class Ok(val json: JsonObject) : Outcome
        class Failed(val reason: String) : Outcome
    }

    /** 408、429、5xx 按文档等一下重试一次；其余错误直接把原因交给模型。 */
    private suspend fun post(endpoint: String, body: JsonObject): Outcome = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext Outcome.Failed("api_key_missing")
        var last: Outcome = Outcome.Failed("network")
        for (attempt in 0..1) {
            if (attempt > 0) delay(RETRY_DELAY_MS)
            last = runCatching {
                client.newCall(
                    Request.Builder()
                        .url("$baseUrl/v1/tools/$endpoint")
                        .header("Authorization", "Bearer $apiKey")
                        .post(body.toString().toRequestBody(JSON))
                        .build()
                ).execute().use { resp ->
                    val text = resp.body.string()
                    if (resp.isSuccessful) {
                        Outcome.Ok(AppJson.parseToJsonElement(text).jsonObject)
                    } else {
                        val message = runCatching {
                            AppJson.parseToJsonElement(text).jsonObject.obj("error")?.get("message")?.stringValue
                        }.getOrNull()
                        Outcome.Failed(reasonOf(resp.code, message))
                    }
                }
            }.getOrElse { Outcome.Failed(it.message ?: "network") }
            val retry = (last as? Outcome.Failed)?.reason?.let { it.startsWith("rate_limited") || it.startsWith("server_") || it.startsWith("timeout") }
            if (retry != true) break
        }
        last
    }

    private fun reasonOf(code: Int, message: String?): String = when (code) {
        401 -> "api_key_invalid"
        402 -> "insufficient_balance"
        403 -> "refused_by_policy"
        404 -> "no_usable_content"
        408, 504 -> "timeout"
        429 -> "rate_limited"
        in 500..599 -> "server_$code"
        else -> message?.takeIf { it.isNotBlank() } ?: "http_$code"
    }

    private fun JsonObject.str(key: String): String = this[key].safeStringOrNull().orEmpty()

    private fun prop(type: String, description: String) = buildJsonObject {
        put("type", type)
        put("description", description)
    }

    private fun params(vararg props: Pair<String, JsonObject>, required: List<String>) = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { props.forEach { (k, v) -> put(k, v) } })
        put("required", JsonArray(required.map { JsonPrimitive(it) }))
    }

    private fun tool(name: String, description: String, params: JsonObject) = buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", name)
            put("description", description)
            put("parameters", params)
        })
    }

    companion object {
        val NAMES = listOf("web_search", "web_fetch")

        const val MAX_RESULTS = 20
        private const val MAX_QUERY = 128
        private const val SUMMARY_CHARS = 300
        private const val MAX_CONTENT_CHARS = 20_000
        private const val RETRY_DELAY_MS = 1_000L

        private val JSON = "application/json".toMediaType()

        private val client by lazy {
            HttpClients.base.newBuilder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(40, TimeUnit.SECONDS)
                .build()
        }
    }
}
