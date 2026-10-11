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

/**
 * 飞书多维表格作为反馈后端。没有自建服务器，客户端直连开放平台。
 *
 * ## 安全模型（重要，改动前先读）
 *
 * `app_secret` 打进安装包，反编译必然能取到，所以按"泄露了也不痛"来设计权限：
 * - [Config.submitTable] 只给应用**写**权限。泄露的后果是被刷垃圾记录，可清理。
 * - [Config.replyTable] 只给应用**读**权限，且表里只放工单号/回复正文/回复时间，
 *   以及开发者手写的公开问答（见 [publicQa]）。不含任何用户填写的原文和设备信息。
 *
 * 绝对不要把回复字段并进反馈表——那等于给读权限开了整张反馈表。
 *
 * ## 从 `:app` 搬进 `:data` 时改了什么（三类，结论都是「口径不变」）
 *
 * 1. **凭据与那两条展示字段**（`版本` / `机型` / `系统`）原来直接读 `BuildConfig` 与
 *    `android.os.Build` —— `:data` 是 JVM/Android 共用的模块，不认识这两样。现在由宿主在启动时
 *    注入 [config]（Android = `:app` 的 `AppFeedback.install()`；桌面 = 构建期注入的那份常量），
 *    **拼串的口径一字未改**（preview 后缀也照旧由宿主拼进 [Config.versionLabel]）。
 * 2. **JSON** 原来是 Android 平台那份 `org.json`（`:data` 的 commonMain 里编不过，而且它在
 *    JVM 单测里是 android.jar 的桩，一调就抛）⇒ 换 `:core` 的 `AppJson` + `kotlinx.serialization`。
 *    [plainText] / [fieldChecked] 这两个「多维表格字段形状」的判据逐条照搬（多行文本在响应里是
 *    `[{type:text, text:"..."}]` 而不是纯字符串，这是这一条链最容易踩的地方）。
 * 3. **日志** `android.util.Log` → `:core` 的 [Log]（同一套级别、同样的 tag）。
 *
 * ⚠️ 反馈**没有校园站点会话**：它不认 `SiteSession`，也不走统一认证。"登录"是拿 `app_id` +
 * `app_secret` 换一张 `tenant_access_token`（见 [token]）—— 所以两端都不套 `DesktopSiteGate`。
 */
object FeedbackApi {

    /**
     * 后端凭据 + 三个只用于展示的字段。搬迁前它们分别是 `BuildConfig.FEEDBACK_*` /
     * `BuildConfig.VERSION_*` / `Build.MANUFACTURER` 之类，现在由宿主注入（见类 KDoc 第 1 条）。
     */
    data class Config(
        val appId: String,
        val appSecret: String,
        val baseToken: String,
        /** 只给应用**写**权限的那张表的 id（[Config] 里的 `submitTable`）。 */
        val submitTable: String,
        /** 只给应用**读**权限的那张表的 id。 */
        val replyTable: String,
        /**
         * 「版本」列的值：搬迁前是
         * `"${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"` + preview 时补 `" [preview]"`。
         * 由宿主按自己的口径拼好整串传进来（提交崩溃日志时会被 [submit] 的 `version` 覆盖）。
         */
        val versionLabel: String,
        /** 「机型」列：Android = `"${Build.MANUFACTURER} ${Build.MODEL}"`；桌面 = 宿主机说明。 */
        val deviceLabel: String,
        /** 「系统」列：Android = `"Android ${RELEASE} / API ${SDK_INT}"`；桌面 = 宿主 OS。 */
        val osLabel: String,
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

    /**
     * 提交一条反馈。成功返回工单号——由客户端生成而非服务端下发，
     * 这样不需要账号体系也能让用户回来查自己那条。
     * [version] 为空取当前版本；崩溃日志是下次启动才补报的，传崩溃时的版本，免得升级后记错。
     */
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
            put("系统", cfg.osLabel)
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
     * 拉取这些工单号对应的回复。
     *
     * 走官方当前推荐的 [查询记录 search](https://open.feishu.cn/document/docs/bitable-v1/app-table-record/search)，
     * 不走已标为历史接口的 GET list。
     *
     * 按文档：
     * - 多行文本在响应里是 `[{type:text, text:"..."}]`，不是纯字符串
     * - filter 的 value 必须是数组；多行文本的 `is`/`contains` 每次只能一个元素
     * - **公式 / 查找引用不能做筛选条件**（筛选指南原文）。工单号若是查找引用，
     *   filter 会 1254018 —— 那时这一版的做法是「记一行日志、去掉 filter 重来」，
     *   ⚠️ 但 `continue` 在 `do/while` 里是**回到条件判断**，而那一发刚把 `pageToken` 置空
     *   ⇒ 循环当场结束，那条兜底路径**实际跑不到**（`found` 会是空表）。
     *   这是从 `:app` **原样搬过来**的既有行为（本轮一行未改，已由 `:data:jvmTest` 钉住）；
     *   要不要把它改成真的重试，是产品决定，不在这次搬迁里。
     */
    suspend fun replies(tickets: List<String>): Map<String, FeedbackReply> =
        withContext(Dispatchers.IO) {
            if (tickets.isEmpty()) return@withContext emptyMap()
            val cfg = requireConfig()
            val wanted = tickets.take(20).map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            if (wanted.isEmpty()) return@withContext emptyMap()

            val conditions = buildJsonArray {
                wanted.forEach { id ->
                    add(
                        buildJsonObject {
                            put("field_name", F_TICKET)
                            put("operator", "contains")
                            put("value", buildJsonArray { add(JsonPrimitive(id)) })
                        },
                    )
                }
            }
            val filter = buildJsonObject {
                put("conjunction", "or")
                put("conditions", conditions)
            }

            val found = linkedMapOf<String, FeedbackReply>()
            var pageToken: String? = null
            var pages = 0
            var useFilter = true
            do {
                val json = searchRecords(
                    tableId = cfg.replyTable,
                    filter = if (useFilter) filter else null,
                    pageToken = pageToken,
                )
                val code = json.intOr("code", -1)
                if (code != 0) {
                    Log.w("FeedbackApi", "search replies failed: $code ${json.textOrEmpty("msg")}")
                    if (useFilter) {
                        useFilter = false
                        pageToken = null
                        continue
                    }
                    break
                }
                val data = json["data"] as? JsonObject ?: break
                collectReplies(data["items"] as? JsonArray, wanted, found)
                val more = data.boolOr("has_more", false)
                pageToken = data["page_token"]?.textOrEmpty()?.takeIf { more && it.isNotBlank() }
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
    suspend fun publicQa(): List<FeedbackPublicQa> = withContext(Dispatchers.IO) {
        val cfg = requireConfig()
        val filter = buildJsonObject {
            put("conjunction", "and")
            put(
                "conditions",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("field_name", F_PUBLIC)
                            put("operator", "is")
                            put("value", buildJsonArray { add(JsonPrimitive("true")) })
                        },
                    )
                },
            )
        }
        val out = ArrayList<FeedbackPublicQa>()
        var pageToken: String? = null
        var pages = 0
        do {
            val json = searchRecords(
                tableId = cfg.replyTable,
                filter = filter,
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
                if (!fields.fieldChecked(F_PUBLIC)) continue
                val question = fields.fieldText("问题", "公开问题", "问")
                val answer = fields.fieldText("回复", "回复内容", "答", "回答")
                if (question.isBlank() || answer.isBlank()) continue
                out.add(
                    FeedbackPublicQa(
                        question = question,
                        answer = answer,
                        category = fields.fieldText("类型", "分类"),
                    )
                )
            }
            val more = data.boolOr("has_more", false)
            pageToken = data["page_token"]?.textOrEmpty()?.takeIf { more && it.isNotBlank() }
            pages++
        } while (pageToken != null && pages < 3)
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
            append("/tables/$tableId/records/search?page_size=100")
            if (!pageToken.isNullOrBlank()) {
                append("&page_token=").append(java.net.URLEncoder.encode(pageToken, "UTF-8"))
            }
        }
        // 搬迁前的请求体就是「有 filter 时 {"filter": …}，没有时 {}」
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
            into[ticket] = FeedbackReply(text, fields.fieldText("回复时间", "时间"))
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

    /**
     * 多维表格的文本字段在 API 里可能是字符串，也可能是
     * `[{"type":"text","text":"..."}]` 这种富文本片段数组，还可能是 `{"text":…}` / `{"name":…}`。
     *
     * 不能先 `optString`：值是数组时它会（在 `org.json` 里）把整个 JSON 数组 `toString()`
     * 成非空垃圾，工单号对不上本地 `Txxx`，回复就永远显示「处理中」。
     * 搬迁前这段判据的每一个分支逐条照搬 —— 只有 JSON 库换了（见类 KDoc 第 2 条）。
     */
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
                // 布尔：content 就是 `true`；数字：非 0 即勾上（搬迁前就是 `v.toInt() != 0`）
                if (p.content.equals("true", true)) return true
                if (p.content.toDoubleOrNull()?.toInt()?.let { it != 0 } == true) return true
            }
        }
        return false
    }

    private fun parse(raw: String): JsonObject =
        runCatching { AppJson.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())

    /** 缺字段给 [default]；字符串数字也认（搬迁前 `optInt` 就是这么宽）。 */
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
