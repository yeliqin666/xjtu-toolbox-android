package com.xjtu.toolbox.feedback

import com.xjtu.toolbox.network.HttpClients
import com.xjtu.toolbox.platform.Log
import com.xjtu.toolbox.util.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
import java.util.concurrent.TimeUnit

/** 飞书多维表格作为反馈后端。 */
object FeedbackApi {

    /** 后端凭据 + 三个只用于展示的字段。 */
    data class Config(
        val appId: String,
        val appSecret: String,
        val baseToken: String,
        /** 只给应用**写**权限的那张表的 id（[Config] 里的 `submitTable`）。 */
        val submitTable: String,
        /** 只给应用**读**权限的那张表的 id。 */
        val replyTable: String,
        /** 「版本」列的值。 */
        val versionLabel: String,
        /** 「机型」列：Android = `"${Build.MANUFACTURER} ${Build.MODEL}"`；桌面 = 宿主机说明。 */
        val deviceLabel: String,
        /** 「系统」列：Android = `"Android ${RELEASE} / API ${SDK_INT}"`；桌面 = 宿主 OS。 */
        val osLabel: String,
        /** 提交时才取的运行诊断（Android：近几次启动耗时），拼在「系统」列后面；空串不拼。 */
        val diagnostics: () -> String = { "" },
    )

    /** 宿主注入的凭据；没注入（或注入的是空串）时整条链降级回外链，见 [isConfigured]。 */
    @Volatile
    var config: Config? = null

    /** 凭据没配全时整个反馈功能降级回外链，不弹表单。 */
    val isConfigured: Boolean
        get() = config?.let {
            it.appId.isNotBlank() && it.appSecret.isNotBlank() && it.baseToken.isNotBlank()
        } ?: false

    private const val HOST = "https://open.feishu.cn/open-apis"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient by lazy {
        HttpClients.base.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    // tenant_access_token 有效期 2 小时。只缓存在内存里：进程活着的时间远短于它，
    // 落盘反而多一个泄露面。提前 5 分钟过期，避开临界点上的失败。
    @Volatile private var token: String? = null
    @Volatile private var tokenExpireAt: Long = 0L

    private suspend fun token(): String {
        val cfg = requireConfig()
        token?.let { if (System.currentTimeMillis() < tokenExpireAt) return it }
        val body = buildJsonObject {
            put("app_id", cfg.appId)
            put("app_secret", cfg.appSecret)
        }.toString()
        val req = Request.Builder()
            .url("$HOST/auth/v3/tenant_access_token/internal")
            .post(body.toRequestBody(JSON))
            .build()
        val json = client.newCall(req).execute().use { resp ->
            parse(resp.body?.string().orEmpty())
        }
        if (json.intOr("code", -1) != 0) error("取 token 失败：${json.textOrEmpty("msg")}")
        val t = json["tenant_access_token"]?.textOrEmpty().orEmpty()
        token = t
        tokenExpireAt = System.currentTimeMillis() +
            (json.intOr("expire", 7200) - 300).coerceAtLeast(60) * 1000L
        return t
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
        val fields = buildJsonObject {
            put(F_TICKET, ticket)
            put("类型", category)
            put("内容", content)
            put("联系方式", contact)
            put("匿名ID", anonId)
            put("版本", version ?: cfg.versionLabel)
            put("机型", cfg.deviceLabel)
            put("系统", runCatching { cfg.diagnostics() }.getOrDefault("").let { if (it.isBlank()) cfg.osLabel else "${cfg.osLabel} · $it" })
        }
        val req = Request.Builder()
            .url("$HOST/bitable/v1/apps/${cfg.baseToken}/tables/${cfg.submitTable}/records")
            .addHeader("Authorization", "Bearer ${token()}")
            .post(buildJsonObject { put("fields", fields) }.toString().toRequestBody(JSON))
            .build()
        val json = client.newCall(req).execute().use { parse(it.body?.string().orEmpty()) }
        if (json.intOr("code", -1) != 0) {
            error(json.textOrEmpty("msg").ifBlank { "提交失败" })
        }
    }

    /**
     * 拉取这些工单号对应的回复（同一工单取最新）。按工单号 OR 查，只拿自己的；
     * 本地账本最多 20 个工单，正好一次调用（飞书按次计额度）。全部失败抛异常，让调用方保留缓存。
     */
    suspend fun replies(tickets: List<String>): Map<String, FeedbackReply> =
        withContext(Dispatchers.IO) {
            val wanted = tickets.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            if (wanted.isEmpty()) return@withContext emptyMap()
            val found = linkedMapOf<String, FeedbackReply>()
            wanted.chunked(20).forEach { batch ->
                val json = searchRecords(
                    requireConfig().replyTable,
                    // contains 而不是 is：早期手填的工单号可能带空格
                    filter("or", batch.map { condition(F_TICKET, "contains", it) }),
                    pageToken = null,
                )
                if (json.intOr("code", -1) != 0) error("回复拉取失败：${json.intOr("code", -1)} ${json.textOrEmpty("msg")}")
                // 同一工单回复多次也就几条，一页 500 足够，不翻页
                collectReplies((json["data"] as? JsonObject)?.get("items") as? JsonArray, batch.toSet(), found)
            }
            found
        }

    private fun condition(field: String, operator: String, vararg values: String) = buildJsonObject {
        put("field_name", field)
        put("operator", operator)
        put("value", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
    }

    private fun filter(conjunction: String, conditions: List<JsonObject>) = buildJsonObject {
        put("conjunction", conjunction)
        put("conditions", JsonArray(conditions))
    }

    /** 所有人都能看的问答，直接查飞书。App 平时读 Action 生成的 feedback-qa.json（见 AppFeedbackSource），这里是兜底。 */
    suspend fun publicQa(): List<FeedbackPublicQa> = withContext(Dispatchers.IO) {
        val cfg = requireConfig()
        // 同一工单可能回复多次（/reply 只追加）：按工单取回复时间最新的一条，它公开才展示——
        // 后来改成私密就等于撤下。所以这里不按「公开」筛，拉全表再挑。没工单号的手填问答按问题去重。
        val latest = linkedMapOf<String, Pair<String, FeedbackPublicQa?>>()
        var pageToken: String? = null
        var pages = 0
        do {
            val json = searchRecords(
                tableId = cfg.replyTable,
                filter = null,
                pageToken = pageToken,
            )
            if (json.intOr("code", -1) != 0) {
                Log.w(
                    "FeedbackApi",
                    "search public qa failed: ${json.intOr("code", -1)} ${json.textOrEmpty("msg")}",
                )
                if (pages == 0) error(json.textOrEmpty("msg").ifBlank { "公开问答拉取失败" })
                break
            }
            val data = json["data"] as? JsonObject ?: break
            val items = data["items"] as? JsonArray ?: JsonArray(emptyList())
            for (i in 0 until items.size) {
                val fields = (items[i] as? JsonObject)?.get("fields") as? JsonObject ?: continue
                val question = fields.fieldText("问题", "公开问题", "问")
                val answer = fields.fieldText("回复", "回复内容", "答", "回答")
                if (question.isBlank() || answer.isBlank()) continue
                val key = normalizeTicket(fields.fieldText(F_TICKET)).ifEmpty { "q:$question" }
                val time = fields.fieldText("回复时间", "时间")
                val prev = latest[key]
                if (prev != null && time < prev.first) continue
                latest[key] = time to FeedbackPublicQa(
                    question = question,
                    answer = answer,
                    category = fields.fieldText("类型", "分类"),
                    by = fields.fieldText("回复人"),
                ).takeIf { fields.fieldChecked(F_PUBLIC) }
            }
            val more = data.boolOr("has_more", false)
            pageToken = data["page_token"]?.textOrEmpty()?.takeIf { more && it.isNotBlank() }
            pages++
        } while (pageToken != null && pages < 10)
        val out = latest.values.sortedByDescending { it.first }.mapNotNull { it.second }
        out
    }

    private suspend fun searchRecords(
        tableId: String,
        filter: JsonObject?,
        pageToken: String?,
    ): JsonObject {
        val cfg = requireConfig()
        val url = buildString {
            append("$HOST/bitable/v1/apps/${cfg.baseToken}")
            // 飞书单页上限 500；按次计费，页大一点少翻几页
            append("/tables/$tableId/records/search?page_size=500")
            if (!pageToken.isNullOrBlank()) {
                append("&page_token=").append(java.net.URLEncoder.encode(pageToken, "UTF-8"))
            }
        }
        val body = if (filter != null) {
            buildJsonObject { put("filter", filter) }
        } else {
            JsonObject(emptyMap())
        }
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${token()}")
            .post(body.toString().toRequestBody(JSON))
            .build()
        return client.newCall(req).execute().use { parse(it.body?.string().orEmpty()) }
    }

    private fun collectReplies(
        items: JsonArray?,
        wanted: Set<String>,
        into: MutableMap<String, FeedbackReply>,
    ) {
        if (items == null) return
        for (i in 0 until items.size) {
            val fields = (items[i] as? JsonObject)?.get("fields") as? JsonObject ?: continue
            val ticket = normalizeTicket(fields.fieldText("工单号", "工单", "ticket", "Ticket"))
            if (ticket.isEmpty() || ticket !in wanted) continue
            val text = fields.fieldText("回复", "回复内容", "内容", "reply")
            if (text.isBlank()) continue
            // 同一工单可能回复多次（/reply 只追加不改），取回复时间最新的；时间是 "yyyy-MM-dd[ HH:mm]" 文本，按字符串比
            val time = fields.fieldText("回复时间", "时间")
            val prev = into[ticket]
            if (prev == null || time >= prev.time) into[ticket] = FeedbackReply(text, time, fields.fieldText("回复人"))
        }
    }

    private fun normalizeTicket(raw: String): String {
        val t = raw.trim()
        return TICKET_RE.find(t)?.value ?: t
    }

    private fun JsonObject.fieldText(vararg keys: String): String {
        for (k in keys) {
            if (k !in this) continue
            val t = this[k].plainText()
            if (t.isNotBlank()) return t
        }
        return ""
    }

    /** 多维表格的文本字段在 API 里可能是字符串，也可能是`[{"type":"text","text":"..."}]` 这种富文本片段数组，还可能是 `{"text":…}` / `{"name":…}`。 */
    private fun JsonElement?.plainText(): String {
        val e = this ?: return ""
        return when (e) {
            is JsonNull -> ""
            is JsonPrimitive -> e.content
            is JsonArray -> (0 until e.size).joinToString("") { i ->
                when (val item = e[i]) {
                    is JsonNull -> ""
                    is JsonPrimitive -> item.content
                    is JsonObject -> item.value("text").ifBlank { item.value("name") }
                    else -> ""
                }
            }
            is JsonObject -> {
                val direct = e.value("text")
                if (direct.isNotBlank()) {
                    direct
                } else {
                    val arr = e["text_arr"] as? JsonArray
                    if (arr != null) {
                        (0 until arr.size).joinToString("") { arr[it].plainText() }
                    } else {
                        e.value("name")
                    }
                }
            }
        }
    }
    /** `optString(key)` 那一档的等价：缺字段、JSON null 都给空串。 */
    private fun JsonObject.value(key: String): String {
        val v = this[key] ?: return ""
        if (v is JsonNull) return ""
        return (v as? JsonPrimitive)?.content.orEmpty()
    }

    private fun JsonObject.fieldChecked(vararg keys: String): Boolean {
        for (k in keys) {
            val v = this[k] ?: continue
            if (v is JsonNull) continue
            val p = v as? JsonPrimitive ?: continue
            if (p.isString) {
                // 字符串那一档：`"true"` 或 `"是"`
                if (p.content.equals("true", true) || p.content == "是") return true
            } else {
                if (p.content.equals("true", true)) return true
                if (p.content.toDoubleOrNull()?.toInt()?.let { it != 0 } == true) return true
            }
        }
        return false
    }

    private fun parse(raw: String): JsonObject =
        runCatching { AppJson.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())

    /** 缺字段给 [default]；字符串数字也认。 */
    private fun JsonObject.intOr(key: String, default: Int): Int =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }
            ?.content?.trim()?.toIntOrNull() ?: default
    /** `optString()` 那一档的等价：缺字段、JSON null 都给空串。 */
    private fun JsonElement?.textOrEmpty(): String {
        val e = this ?: return ""
        if (e is JsonNull) return ""
        return (e as? JsonPrimitive)?.content.orEmpty()
    }

    private fun JsonObject.textOrEmpty(key: String): String {
        val e = this[key] ?: return ""
        if (e is JsonNull) return ""
        return (e as? JsonPrimitive)?.content.orEmpty()
    }

    private fun JsonObject.boolOr(key: String, default: Boolean): Boolean {
        val v = this[key] ?: return default
        if (v is JsonNull) return default
        val c = (v as? JsonPrimitive)?.content ?: return default
        return c.equals("true", ignoreCase = true) || c == "1"
    }

    private fun requireConfig(): Config =
        config ?: error("反馈后端凭据没配全（FeedbackApi.config 还是 null，见 isConfigured）")

    private const val F_TICKET = "工单号"
    private const val F_PUBLIC = "公开"
    private val TICKET_RE = Regex("""T[A-Z0-9]{8,16}""")
}
