package com.xjtu.toolbox.feedback

import android.os.Build
import com.xjtu.toolbox.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 飞书多维表格作为反馈后端。没有自建服务器，客户端直连开放平台。
 *
 * 安全模型（重要，改动前先读）：
 * app_secret 打进 APK，反编译必然能取到，所以按"泄露了也不痛"来设计权限：
 * - [BuildConfig.FEEDBACK_TABLE_SUBMIT] 只给应用**写**权限。泄露的后果是被刷垃圾记录，可清理。
 * - [BuildConfig.FEEDBACK_TABLE_REPLY] 只给应用**读**权限，且表里只放
 *   工单号/回复正文/回复时间，以及开发者手写的公开问答（见 [publicQa]）。
 *   不含任何用户填写的原文和设备信息。
 * 绝对不要把回复字段并进反馈表——那等于给读权限开了整张反馈表。
 */
object FeedbackApi {

    /** 凭据没配全时整个反馈功能降级回外链，不弹表单。 */
    val isConfigured: Boolean
        get() = BuildConfig.FEEDBACK_APP_ID.isNotBlank() &&
            BuildConfig.FEEDBACK_APP_SECRET.isNotBlank() &&
            BuildConfig.FEEDBACK_BASE_TOKEN.isNotBlank()

    private const val HOST = "https://open.feishu.cn/open-apis"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    // tenant_access_token 有效期 2 小时。只缓存在内存里：进程活着的时间远短于它，
    // 落盘反而多一个泄露面。提前 5 分钟过期，避开临界点上的失败。
    @Volatile private var token: String? = null
    @Volatile private var tokenExpireAt: Long = 0L

    private suspend fun token(): String {
        token?.let { if (System.currentTimeMillis() < tokenExpireAt) return it }
        val body = JSONObject()
            .put("app_id", BuildConfig.FEEDBACK_APP_ID)
            .put("app_secret", BuildConfig.FEEDBACK_APP_SECRET)
            .toString()
        val req = Request.Builder()
            .url("$HOST/auth/v3/tenant_access_token/internal")
            .post(body.toRequestBody(JSON))
            .build()
        val json = client.newCall(req).execute().use { resp ->
            JSONObject(resp.body?.string().orEmpty())
        }
        if (json.optInt("code", -1) != 0) error("取 token 失败：${json.optString("msg")}")
        val t = json.getString("tenant_access_token")
        token = t
        tokenExpireAt = System.currentTimeMillis() +
            (json.optInt("expire", 7200) - 300).coerceAtLeast(60) * 1000L
        return t
    }

    /**
     * 提交一条反馈。成功返回工单号——由客户端生成而非服务端下发，
     * 这样不需要账号体系也能让用户回来查自己那条。
     */
    suspend fun submit(
        ticket: String,
        category: String,
        content: String,
        contact: String,
        anonId: String,
    ): Unit = withContext(Dispatchers.IO) {
        val fields = JSONObject()
            .put(F_TICKET, ticket)
            .put("类型", category)
            .put("内容", content)
            .put("联系方式", contact)
            .put("匿名ID", anonId)
            .put("版本", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            .put("机型", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("系统", "Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
        val req = Request.Builder()
            .url("$HOST/bitable/v1/apps/${BuildConfig.FEEDBACK_BASE_TOKEN}" +
                "/tables/${BuildConfig.FEEDBACK_TABLE_SUBMIT}/records")
            .addHeader("Authorization", "Bearer ${token()}")
            .post(JSONObject().put("fields", fields).toString().toRequestBody(JSON))
            .build()
        val json = client.newCall(req).execute().use { JSONObject(it.body?.string().orEmpty()) }
        if (json.optInt("code", -1) != 0) error(json.optString("msg").ifBlank { "提交失败" })
    }

    /**
     * 拉取这些工单号对应的回复。
     *
     * 走官方当前推荐的 [查询记录 search](https://open.feishu.cn/document/docs/bitable-v1/app-table-record/search)，
     * 不走已标为历史接口的 GET list。
     *
     * 按文档：
     * - 多行文本在响应里是 `[{type:text, text:"..."}]`，不是纯字符串
     * - filter 的 value 必须是数组；多行文本的 `is`/`contains` 每次只能一个元素
     * - **公式 / 查找引用不能做筛选条件**（筛选指南原文）。工单号若是查找引用，
     *   filter 会 1254018，这时去掉 filter 拉表再本地匹配
     */
    suspend fun replies(tickets: List<String>): Map<String, Reply> =
        withContext(Dispatchers.IO) {
            if (tickets.isEmpty()) return@withContext emptyMap()
            val wanted = tickets.take(20).map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            if (wanted.isEmpty()) return@withContext emptyMap()

            val conditions = org.json.JSONArray()
            wanted.forEach { id ->
                conditions.put(
                    JSONObject()
                        .put("field_name", F_TICKET)
                        .put("operator", "contains")
                        .put("value", org.json.JSONArray().put(id))
                )
            }
            val filter = JSONObject()
                .put("conjunction", "or")
                .put("conditions", conditions)

            val found = linkedMapOf<String, Reply>()
            var pageToken: String? = null
            var pages = 0
            var useFilter = true
            do {
                val json = searchRecords(
                    tableId = BuildConfig.FEEDBACK_TABLE_REPLY,
                    filter = if (useFilter) filter else null,
                    pageToken = pageToken,
                )
                val code = json.optInt("code", -1)
                if (code != 0) {
                    android.util.Log.w("FeedbackApi", "search replies failed: $code ${json.optString("msg")}")
                    if (useFilter) {
                        useFilter = false
                        pageToken = null
                        continue
                    }
                    break
                }
                val data = json.optJSONObject("data") ?: break
                collectReplies(data.optJSONArray("items"), wanted, found)
                val more = data.optBoolean("has_more")
                pageToken = data.optString("page_token").takeIf { more && it.isNotBlank() }
                pages++
            } while (pageToken != null && found.size < wanted.size && pages < 5)
            found
        }

    /**
     * 所有人都能看的问答。仍读回复表，由开发者勾选「公开」并填写脱敏后的「问题」。
     *
     * 飞书回复表需要两个字段（一次配好）：
     * - **公开**：复选框。勾了才会进 App。
     * - **问题**：多行文本。必须是你改写过的问法，不要原样粘用户原文。
     * 回答继续用已有的「回复」。也可以单独加一行、不填工单号，当公共 FAQ。
     *
     * 筛选失败（字段还没建）就当没有公开条目，**绝不**退回整表拉取——
     * 否则未公开的回复也会被所有人读到。
     */
    suspend fun publicQa(): List<PublicQa> = withContext(Dispatchers.IO) {
        val filter = JSONObject()
            .put("conjunction", "and")
            .put(
                "conditions",
                org.json.JSONArray().put(
                    JSONObject()
                        .put("field_name", F_PUBLIC)
                        .put("operator", "is")
                        .put("value", org.json.JSONArray().put("true"))
                )
            )
        val out = ArrayList<PublicQa>()
        var pageToken: String? = null
        var pages = 0
        do {
            val json = searchRecords(
                tableId = BuildConfig.FEEDBACK_TABLE_REPLY,
                filter = filter,
                pageToken = pageToken,
            )
            if (json.optInt("code", -1) != 0) {
                android.util.Log.w(
                    "FeedbackApi",
                    "search public qa failed: ${json.optInt("code")} ${json.optString("msg")}"
                )
                if (pages == 0) error(json.optString("msg").ifBlank { "公开问答拉取失败" })
                break
            }
            val data = json.optJSONObject("data") ?: break
            val items = data.optJSONArray("items") ?: org.json.JSONArray()
            for (i in 0 until items.length()) {
                val fields = items.optJSONObject(i)?.optJSONObject("fields") ?: continue
                if (!fields.fieldChecked(F_PUBLIC)) continue
                val question = fields.fieldText("问题", "公开问题", "问")
                val answer = fields.fieldText("回复", "回复内容", "答", "回答")
                if (question.isBlank() || answer.isBlank()) continue
                out.add(
                    PublicQa(
                        question = question,
                        answer = answer,
                        category = fields.fieldText("类型", "分类"),
                    )
                )
            }
            val more = data.optBoolean("has_more")
            pageToken = data.optString("page_token").takeIf { more && it.isNotBlank() }
            pages++
        } while (pageToken != null && pages < 3)
        out
    }

    private suspend fun searchRecords(
        tableId: String,
        filter: JSONObject?,
        pageToken: String?,
    ): JSONObject {
        val url = buildString {
            append("$HOST/bitable/v1/apps/${BuildConfig.FEEDBACK_BASE_TOKEN}")
            append("/tables/$tableId/records/search?page_size=100")
            if (!pageToken.isNullOrBlank()) {
                append("&page_token=").append(java.net.URLEncoder.encode(pageToken, "UTF-8"))
            }
        }
        val body = JSONObject()
        if (filter != null) body.put("filter", filter)
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${token()}")
            .post(body.toString().toRequestBody(JSON))
            .build()
        return client.newCall(req).execute().use { JSONObject(it.body?.string().orEmpty()) }
    }

    private fun collectReplies(
        items: org.json.JSONArray?,
        wanted: Set<String>,
        into: MutableMap<String, Reply>,
    ) {
        if (items == null) return
        for (i in 0 until items.length()) {
            val fields = items.optJSONObject(i)?.optJSONObject("fields") ?: continue
            val ticket = normalizeTicket(fields.fieldText("工单号", "工单", "ticket", "Ticket"))
            if (ticket.isEmpty() || ticket !in wanted) continue
            val text = fields.fieldText("回复", "回复内容", "内容", "reply")
            if (text.isBlank()) continue
            into[ticket] = Reply(text, fields.fieldText("回复时间", "时间"))
        }
    }

    private fun normalizeTicket(raw: String): String {
        val t = raw.trim()
        return TICKET_RE.find(t)?.value ?: t
    }

    private fun JSONObject.fieldText(vararg keys: String): String {
        for (k in keys) {
            val t = plainText(k)
            if (t.isNotBlank()) return t
        }
        return ""
    }

    /**
     * 多维表格的文本字段在 API 里可能是字符串，也可能是
     * [{"type":"text","text":"..."}] 这种富文本片段数组。
     *
     * 不能先 [JSONObject.optString]：值是数组时它会把整个 JSON 数组 toString()
     * 成非空垃圾，工单号对不上本地 Txxx，回复就永远显示「处理中」。
     */
    private fun JSONObject.plainText(key: String): String {
        if (!has(key) || isNull(key)) return ""
        return when (val v = opt(key)) {
            null, JSONObject.NULL -> ""
            is String -> v
            is Number -> if (v.toLong() > 10_000_000_000L) {
                // 飞书日期字段常是毫秒时间戳
                v.toLong().toString()
            } else {
                v.toString()
            }
            is org.json.JSONArray -> (0 until v.length()).joinToString("") { i ->
                when (val item = v.opt(i)) {
                    is String -> item
                    is JSONObject -> item.optString("text").ifBlank { item.optString("name") }
                    else -> ""
                }
            }
            is JSONObject -> v.optString("text").ifBlank {
                val arr = v.optJSONArray("text_arr")
                if (arr != null) {
                    (0 until arr.length()).joinToString("") { arr.optString(it) }
                } else {
                    v.optString("name")
                }
            }
            is Boolean -> if (v) "true" else "false"
            else -> v.toString()
        }
    }

    private fun JSONObject.fieldChecked(vararg keys: String): Boolean {
        for (k in keys) {
            if (!has(k) || isNull(k)) continue
            when (val v = opt(k)) {
                is Boolean -> if (v) return true
                is String -> if (v.equals("true", true) || v == "是") return true
                is Number -> if (v.toInt() != 0) return true
            }
        }
        return false
    }

    private const val F_TICKET = "工单号"
    private const val F_PUBLIC = "公开"
    private val TICKET_RE = Regex("""T[A-Z0-9]{8,16}""")

    data class Reply(val text: String, val time: String)

    data class PublicQa(
        val question: String,
        val answer: String,
        val category: String = "",
    )
}
