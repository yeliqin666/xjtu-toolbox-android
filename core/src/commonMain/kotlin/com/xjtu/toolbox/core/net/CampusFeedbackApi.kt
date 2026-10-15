package com.xjtu.toolbox.core.net

import com.xjtu.toolbox.feedback.FeedbackPublicQa
import com.xjtu.toolbox.feedback.FeedbackReply
import com.xjtu.toolbox.feedback.FeedbackSource
import com.xjtu.toolbox.util.arr
import com.xjtu.toolbox.util.safeString
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 反馈的 **serve 版取数**：给 `:web` 用，实现 [FeedbackSource]。
 *
 * | 端点 | 本类实现的方法 |
 * |---|---|
 * | `GET /api/feedback/replies?tickets=` | [replies] |
 * | `GET /api/feedback/public-qa` | [publicQa] |
 * | `POST /api/feedback/submit` | [submit]（**写**） |
 *
 * 契约 §5.7；`:server` 那一侧是 `FeedbackRoutes.kt`（薄包装 `:data` 的 `FeedbackApi`，飞书开放平台
 * 多维表格）。
 *
 * ## 这一族的「登录」不是站点会话（别把登录画进屏）
 *
 * 反馈不认 `SiteSession`、也不走统一认证：「登录」是拿 `app_id` + `app_secret` 换一张
 * `tenant_access_token`。serve 那一侧由进程启动时读**环境变量**
 * （`FEEDBACK_APP_ID` / `FEEDBACK_APP_SECRET` / `FEEDBACK_BASE_TOKEN`）注入 —— 所以
 * [isConfigured] 报 `true`：**端点在这份契约里是有的**（这一点在编译期就定死了），而
 * 「这个部署有没有把凭据配齐」是运行期的事、页面上判不出来。没配全时那两条端点答 `502` +
 * 一句「去哪儿配」的中文短句（契约 §5.7），屏把失败吞掉 ⇒ 页面呈现为「没有新回复 / 没有问答」。
 * 这一条写在 `:web` 那一屏的页顶说明里，不在这里编一个假判据出来。
 *
 * ✅ **提交新反馈也端了**（写路径，契约 §5.7）：`POST /api/feedback/submit`—— 请求体就是
 * [submit] 那五格（`ticket` / `category` / `content` / `contact` / `anonId`），工单号仍由**屏自己**
 * 生成（`FeedbackStore.newTicket()`），端点只校形状并**原样回**，不由服务端下发。
 * ⚠️ 这一枪在服务端**真的**往多维表格里加一行，而且上游是匿名可写（反馈没有站点会话）——
 * 能挡住滥用的只有令牌闸门 + 飞书那张表的写权限范围。
 * ## 缺字段不编造
 *
 * - `ticket` 空白的那几条回复**不列**（归不到某个工单上的回复没法显示在「我提过的」里）；
 * - `question` / `answer` 任一为空的那几条公开问答**不列**（与 `:core` 的
 *   `FeedbackStore.cachedPublicQa` 同一判据：少了半边就不显示，不拿空串凑一条）；
 * - 没人回过的工单**不在**响应里（`count` 是「真取到几条」）⇒ 本类回一张**小表**，
 *   屏上那句「处理中」就落在这里 —— 不是拿空回复顶上。
 *
 * ## 一次最多 20 个工单号
 *
 * 与 `:data` 的 `take(20)` **同一个数**（契约 §5.7：本层不另定一个数）：屏上那串工单号来自
 * `:core` 的 `FeedbackStore` 本地账本（它自己也只留最近 20 条），这里再照一次同一条上限，
 * 免得某天账本变长之后发出去一长串。
 *
 * ## 默认形态（campus-api）
 *
 * 反馈在 campus-api 里没有对应端点（两端走 `:data` 的 okhttp 直连飞书）⇒ 非 serve 分支一律
 * [serveOnly]（抛错，不返回一份空数据 —— 那会把「形态不对」说成「真没有回复」）。
 *
 * @param baseUrl 留空 ⇒ 相对路径即**同源**（`:web` 由 `:server` 托管，必须是空）
 * @param mode 见 [ApiMode]：serve 支才走本契约的端点
 */
class CampusFeedbackApi(
    private val client: HttpClient,
    private val baseUrl: String = "",
    /** 见 [ApiMode]：这一份取数只认 serve 契约（§5.7）。 */
    private val mode: ApiMode = ApiMode.CAMPUS_API,
) : FeedbackSource {

    /** 见类 KDoc：端点在这份契约里有（编译期定死）；「凭据配没配」是服务端运行期的事。 */
    override val isConfigured: Boolean get() = true

    /**
     * 见类 KDoc：提交端了（`POST /api/feedback/submit`，契约 §5.7）⇒ 屏上那块表单照旧画。
     *
     * 与 `:data` / 桌面那一份**逐字相同**的默认值（`FeedbackSource.canSubmit` 默认 `true`）：
     * 本类不再覆写它，否则这一端就会自己把自己画成「只能看」—— 而端点是在的。
     */
    override val canSubmit: Boolean get() = true

    override suspend fun replies(tickets: List<String>): Map<String, FeedbackReply> {
        requireServe()
        val asked = tickets.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_TICKETS)
        if (asked.isEmpty()) return emptyMap()
        val data = client.serveData(
            "加载反馈回复",
            baseUrl,
            REPLIES_PATH,
            listOf(TICKETS_PARAM to asked.joinToString(",")),
        )
        return buildMap {
            data.arr("replies").orEmpty().forEach { element ->
                val row = element as? JsonObject ?: return@forEach
                val ticket = row["ticket"].safeString().trim()
                if (ticket.isEmpty()) return@forEach
                // `text` 是服务端把富文本片段数组合成的那一串；`time` 是上游原文（不是 ISO-8601 的时刻）
                put(ticket, FeedbackReply(text = row["text"].safeString(), time = row["time"].safeString()))
            }
        }
    }

    override suspend fun publicQa(): List<FeedbackPublicQa> {
        requireServe()
        val data = client.serveData("加载公开问答", baseUrl, PUBLIC_QA_PATH)
        return data.arr("items").orEmpty().mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val question = row["question"].safeString()
            val answer = row["answer"].safeString()
            // 见类 KDoc：少了半边就不显示（与 FeedbackStore.cachedPublicQa 同一条判据）
            if (question.isBlank() || answer.isBlank()) return@mapNotNull null
            FeedbackPublicQa(question = question, answer = answer, category = row["category"].safeString())
        }
    }

    /**
     * 见类 KDoc：提交是**写路径**（真往多维表格里加一行），serve 契约 §5.7 不端 ⇒ 这里照旧抛错。
     * 正常路径上它不会被调用：屏按 [canSubmit] 把整块表单不画。
     */
    override suspend fun submit(
        ticket: String,
        category: String,
        content: String,
        contact: String,
        anonId: String,
        version: String?,
    ) {
        requireServe()
        // `version` 不进请求体（见类 KDoc）—— 留空时由 `:data` 用宿主注入的 config 那三格顶上：
        // 这里显式丢掉它，免得下一个读的人以为「传了就会发出去」—— 端点不收这一格。
        val body = buildJsonObject {
            put("ticket", ticket)
            put("category", category)
            put("content", content)
            put("contact", contact)
            put("anonId", anonId)
        }.toString()
        val outcome = client.serveAction(baseUrl, SUBMIT_PATH, body)
        // 400（形状不对）与 502（凭据没配全 / 上游故障）都是「这一枪没成」⇒ 抛出去，
        // 由屏上那条降级文案接（与两端同一档）；成功就是 200 + `data.ticket`。
        if (!outcome.ok) error("提交反馈：${outcome.message}")
    }

    private fun requireServe() {
        if (mode != ApiMode.SERVE) serveOnly("反馈取数", "$REPLIES_PATH + $PUBLIC_QA_PATH + $SUBMIT_PATH")
    }
}

/** 工单回复（`?tickets=` 必填，逗号分隔）。 */
private const val REPLIES_PATH = "/api/feedback/replies"

/** 「大家在问」（不收参数；上游表里没勾「公开」的不出现）。 */
private const val PUBLIC_QA_PATH = "/api/feedback/public-qa"

/** 提交一条反馈（**写**：`POST /api/feedback/submit`，请求体是 [CampusFeedbackApi.submit] 那五格）。 */
private const val SUBMIT_PATH = "/api/feedback/submit"

private const val TICKETS_PARAM = "tickets"

/** 一次最多认几个工单号（= `:data` 的 `take(20)`，本层不另定一个数）。 */
private const val MAX_TICKETS = 20
