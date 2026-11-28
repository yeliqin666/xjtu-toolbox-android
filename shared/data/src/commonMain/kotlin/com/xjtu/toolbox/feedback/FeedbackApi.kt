package com.xjtu.toolbox.feedback

import com.xjtu.toolbox.network.HttpClients
import com.xjtu.toolbox.util.AppJson
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** 反馈后端（Cloudflare Workers + D1）的客户端。 */
object FeedbackApi {

    const val DEFAULT_BASE_URL = "https://api.dzhz.top/feedback"

    /** 后端地址 + 三个只用于展示的端字段。 */
    data class Config(
        val baseUrl: String = DEFAULT_BASE_URL,
        /** 「版本」列的值。 */
        val versionLabel: String,
        /** 「机型」列：Android = `"${Build.MANUFACTURER} ${Build.MODEL}"`；桌面 = 宿主机说明。 */
        val deviceLabel: String,
        /** 「系统」列：Android = `"Android ${RELEASE} / API ${SDK_INT}"`；桌面 = 宿主 OS。 */
        val osLabel: String,
        /** 提交时才取的运行诊断（Android：近几次启动耗时），拼在「系统」列后面；空串不拼。 */
        val diagnostics: () -> String = { "" },
    )

    /** 单个投票的实时汇总结果。 */
    data class PollLiveSummary(
        val voters: Int,
        val counts: Map<String, Int>,
        val updatedAt: Long,
    )

    /** 宿主注入的配置；没注入（或 baseUrl 为空串）时整条链降级回外链，见 [isConfigured]。 */
    @Volatile
    var config: Config? = null

    /** 地址配全时可用；置空可整体降级成 GitHub Issue 外链。 */
    val isConfigured: Boolean
        get() = config?.baseUrl?.isNotBlank() ?: false

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient by lazy {
        HttpClients.base.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /** 提交一条反馈。 */
    suspend fun submit(
        ticket: String,
        category: String,
        content: String,
        contact: String,
        anonId: String,
        version: String? = null,
    ): Unit = withContext(Dispatchers.IO) {
        val cfg = requireConfig()
        val diag = runCatching { cfg.diagnostics() }.getOrDefault("")
        val system = if (diag.isBlank()) cfg.osLabel else "${cfg.osLabel} · $diag"
        val body = buildJsonObject {
            put("ticket", ticket)
            put("category", category)
            put("content", content)
            put("contact", contact)
            put("anonId", anonId)
            put("version", version ?: cfg.versionLabel)
            put("device", cfg.deviceLabel)
            put("system", system)
        }.toString()
        val req = Request.Builder()
            .url("${baseUrl(cfg)}/submit")
            .post(body.toRequestBody(JSON))
            .build()
        executeJson(req, "提交反馈")
    }

    /** 拉取这些工单号对应的回复（同一工单取最新）。全部失败抛异常，让调用方保留缓存。 */
    suspend fun replies(tickets: List<String>): Map<String, FeedbackReply> =
        withContext(Dispatchers.IO) {
            val cfg = requireConfig()
            val wanted = tickets.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.distinct()
            if (wanted.isEmpty()) return@withContext emptyMap()
            val found = linkedMapOf<String, FeedbackReply>()
            wanted.chunked(20).forEach { batch ->
                val encoded = URLEncoder.encode(batch.joinToString(","), "UTF-8")
                val req = Request.Builder()
                    .url("${baseUrl(cfg)}/replies?tickets=$encoded")
                    .get()
                    .build()
                val json = executeJson(req, "回复拉取失败")
                val arr = json["replies"] as? JsonArray ?: return@forEach
                for (i in 0 until arr.size) {
                    val row = arr[i] as? JsonObject ?: continue
                    val ticket = row.text("ticket").trim().uppercase()
                    val text = row.text("text")
                    if (ticket.isEmpty() || text.isBlank()) continue
                    found[ticket] = FeedbackReply(
                        text = text,
                        time = row.text("time"),
                        by = row.text("by"),
                    )
                }
            }
            found
        }

    /** 「大家在问」公开问答（D1 聚合 + 边缘缓存）。 */
    suspend fun publicQa(): List<FeedbackPublicQa> = withContext(Dispatchers.IO) {
        val cfg = requireConfig()
        val req = Request.Builder()
            .url("${baseUrl(cfg)}/public-qa")
            .get()
            .build()
        val json = executeJson(req, "公开问答拉取失败")
        val items = json["items"] as? JsonArray ?: return@withContext emptyList()
        buildList {
            for (i in 0 until items.size) {
                val o = items[i] as? JsonObject ?: continue
                val question = o.text("question")
                val answer = o.text("answer")
                if (question.isBlank() || answer.isBlank()) continue
                add(
                    FeedbackPublicQa(
                        question = question,
                        answer = answer,
                        category = o.text("category"),
                        by = o.text("by"),
                    ),
                )
            }
        }
    }

    /** 提交或覆盖一条投票。 */
    suspend fun vote(
        pollId: String,
        selected: List<String>,
        anonId: String,
    ): Unit = withContext(Dispatchers.IO) {
        val cfg = requireConfig()
        val body = buildJsonObject {
            put("pollId", pollId)
            put("anonId", anonId)
            put("selected", buildJsonArray { selected.forEach { add(JsonPrimitive(it)) } })
        }.toString()
        val req = Request.Builder()
            .url("${baseUrl(cfg)}/polls/vote")
            .post(body.toRequestBody(JSON))
            .build()
        executeJson(req, "提交投票失败")
    }

    /** 拉取多个投票的实时计数与参与人数。 */
    suspend fun pollResults(pollIds: List<String>): Map<String, PollLiveSummary> =
        withContext(Dispatchers.IO) {
            val cfg = requireConfig()
            val wanted = pollIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (wanted.isEmpty()) return@withContext emptyMap()
            val out = linkedMapOf<String, PollLiveSummary>()
            wanted.chunked(20).forEach { batch ->
                val encoded = URLEncoder.encode(batch.joinToString(","), "UTF-8")
                val req = Request.Builder()
                    .url("${baseUrl(cfg)}/polls/results?ids=$encoded")
                    .get()
                    .build()
                val json = executeJson(req, "投票结果拉取失败")
                val polls = json["polls"] as? JsonObject ?: return@forEach
                for (id in polls.keys) {
                    val obj = polls[id] as? JsonObject ?: continue
                    val voters = obj.intOr("voters", 0)
                    val updatedAt = obj.longOr("updatedAt", 0L)
                    val countsObj = obj["counts"] as? JsonObject ?: JsonObject(emptyMap())
                    val counts = buildMap {
                        for (opt in countsObj.keys) {
                            put(opt, countsObj.intOr(opt, 0))
                        }
                    }
                    out[id] = PollLiveSummary(voters = voters, counts = counts, updatedAt = updatedAt)
                }
            }
            out
        }

    private fun executeJson(req: Request, action: String): JsonObject {
        return client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            val json = parse(raw)
            if (!resp.isSuccessful) {
                val msg = json.text("error").ifBlank { "HTTP ${resp.code}" }
                error("$action：$msg")
            }
            json
        }
    }

    private fun baseUrl(cfg: Config): String = cfg.baseUrl.trim().trimEnd('/')

    private fun requireConfig(): Config =
        config?.takeIf { it.baseUrl.isNotBlank() }
            ?: error("反馈后端未配置（FeedbackApi.config 还是 null 或 baseUrl 为空，见 isConfigured）")

    private fun parse(raw: String): JsonObject =
        runCatching { AppJson.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())

    private fun JsonObject.text(key: String): String {
        val v = this[key] ?: return ""
        if (v is JsonNull) return ""
        return (v as? JsonPrimitive)?.content.orEmpty()
    }

    private fun JsonObject.intOr(key: String, default: Int): Int =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }
            ?.content?.trim()?.toIntOrNull() ?: default

    private fun JsonObject.longOr(key: String, default: Long): Long =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }
            ?.content?.trim()?.toLongOrNull() ?: default
}
