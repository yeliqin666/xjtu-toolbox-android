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

/** 反馈的 serve 版取数：给 `:web` 用，实现 [FeedbackSource]。 */
class CampusFeedbackApi(
    private val client: HttpClient,
    private val baseUrl: String = "",
    /** 见 [ApiMode]：这一份取数只认 serve 契约（§5.7）。 */
    private val mode: ApiMode = ApiMode.CAMPUS_API,
) : FeedbackSource {

    /** 见类 KDoc：端点在这份契约里有（编译期定死）；「凭据配没配」是服务端运行期的事。 */
    override val isConfigured: Boolean get() = true

    /** 见类 KDoc：提交端了（`POST /api/feedback/submit`，契约 §5.7）⇒ 屏上那块表单照旧画。 */
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

    /** 见类 KDoc：提交是写路径（真往多维表格里加一行），serve 契约 §5.7 不端 ⇒ 这里照旧抛错。 */
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

/** 一次最多认几个工单号（与 `:data` 的 `take(20)` 一致）。 */
private const val MAX_TICKETS = 20
