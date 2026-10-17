package com.xjtu.toolbox.server

import com.xjtu.toolbox.feedback.FeedbackApi
import com.xjtu.toolbox.feedback.FeedbackPublicQa
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/** `/api/feedback*`。 */
internal fun Route.feedbackRoutes() {
    route(FEEDBACK_SEGMENT) {

        get(FEEDBACK_REPLIES) {
            val tickets = call.request.queryParameters[FEEDBACK_PARAM_TICKETS]
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.distinct()
                .orEmpty()
            if (tickets.isEmpty()) {
                return@get call.respondBadRequestMessage(
                    "$FEEDBACK_PARAM_TICKETS 必填：逗号分隔的工单号（一次最多 $FEEDBACK_MAX_TICKETS 个）",
                )
            }
            if (!FeedbackApi.isConfigured) return@get call.respondFeedbackUnconfigured()
            try {
                val found = FeedbackApi.replies(tickets.take(FEEDBACK_MAX_TICKETS))
                // 按请求给的先后排（`replies` 返回的是 Map，顺序不保证）：数组的稳定顺序是契约的一部分
                val rows = tickets.mapNotNull { ticket ->
                    found[ticket]?.let { FeedbackReplyDto(ticket = ticket, text = it.text, time = it.time) }
                }
                call.respond(ApiEnvelope.ok(FeedbackRepliesData(tickets = tickets, count = rows.size, replies = rows)))
            } catch (e: Exception) {
                call.respondUpstreamFailure(e, "加载反馈回复")
            }
        }

        // ── 大家都在问（只读；上游表里没勾「公开」的不出现）────────────
        get(FEEDBACK_PUBLIC_QA) {
            if (!FeedbackApi.isConfigured) return@get call.respondFeedbackUnconfigured()
            try {
                val items = FeedbackApi.publicQa()
                call.respond(
                    ApiEnvelope.ok(
                        FeedbackPublicQaData(count = items.size, items = items.map { it.toDto() }),
                    ),
                )
            } catch (e: Exception) {
                call.respondUpstreamFailure(e, "加载公开问答")
            }
        }

        // ── 提交一条反馈（写：真往多维表格里加一行）────────────────────
        post(FEEDBACK_SUBMIT) {
            val body = call.receiveOrNull<FeedbackSubmitRequest>()
                ?: return@post call.respondBadRequestMessage(FEEDBACK_SUBMIT_BODY_MESSAGE)
            val ticket = body.ticket.trim().uppercase()
            if (!FEEDBACK_TICKET_RE.matches(ticket)) {
                return@post call.respondBadRequestMessage(FEEDBACK_TICKET_SHAPE_MESSAGE)
            }
            // 三格必填（`contact` 不在其中：屏上那一格本来就可以不填）
            for ((name, value) in listOf(
                FEEDBACK_PARAM_CATEGORY to body.category,
                FEEDBACK_PARAM_CONTENT to body.content,
                FEEDBACK_PARAM_ANON_ID to body.anonId,
            )) {
                if (value.isBlank()) return@post call.respondBadRequestMessage("$name 必填（不能是空白）")
            }
            if (!FeedbackApi.isConfigured) return@post call.respondFeedbackUnconfigured()
            try {
                FeedbackApi.submit(
                    ticket = ticket,
                    category = body.category.trim(),
                    content = body.content.trim(),
                    contact = body.contact.trim(),
                    anonId = body.anonId.trim(),
                )
                call.respond(ApiEnvelope.ok(FeedbackSubmitData(ticket = ticket)))
            } catch (e: Exception) {
                call.respondUpstreamFailure(e, "提交反馈")
            }
        }
    }
}

/** 反馈后端凭据没配全：502 + 一句说清**怎么办**的中文短句（见 [ServeFeedback]）。 */
private suspend fun ApplicationCall.respondFeedbackUnconfigured() =
    respond(
        HttpStatusCode.BadGateway,
        ApiEnvelope<Unit>(ApiErrors.UPSTREAM_FAILED, message = FEEDBACK_UNCONFIGURED_MESSAGE),
    )

// ── 数据模型（客户端形状）──────────────────────────────────────────

/** `GET /api/feedback/replies` 的 `data`。 */
@Serializable
internal data class FeedbackRepliesData(
    /** 请求里的工单号（去空白、去重、原样回；超过 20 个的没发出去）。 */
    val tickets: List<String>,
    /** 取到回复的条数（没人回过的工单不在 [replies] 里）。 */
    val count: Int,
    val replies: List<FeedbackReplyDto>,
)

/** 一条回复：工单号 + 正文 + 回复时间（都是上游原文）。 */
@Serializable
internal data class FeedbackReplyDto(val ticket: String, val text: String, val time: String)

/** `POST /api/feedback/submit` 的请求体（五格 = `FeedbackSource.submit` 的参数，一个不多）。 */
@Serializable
internal data class FeedbackSubmitRequest(
    /** 工单号（调用方生成：`:core` 的 `FeedbackStore.newTicket()`）。形状不对 ⇒ `400`。 */
    val ticket: String = "",
    val category: String = "",
    val content: String = "",
    /** 联系方式：**允许空**（屏上那一格本来就可以不填）。 */
    val contact: String = "",
    /** 屏自己的匿名标识（`FeedbackStore.anonId()`）；不含学号。 */
    val anonId: String = "",
)

/** `POST /api/feedback/submit` 的 `data`：收下的工单号。 */
@Serializable
internal data class FeedbackSubmitData(val ticket: String)

/** `GET /api/feedback/public-qa` 的 `data`。 */
@Serializable
internal data class FeedbackPublicQaData(val count: Int, val items: List<FeedbackPublicQaDto>)

/** 一条公开问答（上游原文；开发者自己勾「公开」并脱敏后填的那批）。 */
@Serializable
internal data class FeedbackPublicQaDto(val question: String, val answer: String, val category: String)

// ── 投影 helpers ─────────────────────────────────────────────────────

private fun FeedbackPublicQa.toDto() = FeedbackPublicQaDto(question, answer, category)

// ── 常量 ─────────────────────────────────────────────────────────────

internal const val FEEDBACK_SEGMENT = "feedback"
internal const val FEEDBACK_REPLIES = "replies"
internal const val FEEDBACK_SUBMIT = "submit"
internal const val FEEDBACK_PUBLIC_QA = "public-qa"

/** 工单号的形状（同 `FeedbackApi.TICKET_RE`）。 */
private val FEEDBACK_TICKET_RE = Regex("""T[A-Z0-9]{8,16}""")

internal const val FEEDBACK_SUBMIT_BODY_MESSAGE =
    "请求体需要 JSON：{\"ticket\":\"T…\",\"category\":\"…\",\"content\":\"…\",\"contact\":\"…\",\"anonId\":\"…\"}"

internal const val FEEDBACK_TICKET_SHAPE_MESSAGE =
    "ticket 需要形如 T 加 8~16 位大写字母或数字（= FeedbackStore.newTicket() 生成的那一串）"
internal const val FEEDBACK_PARAM_TICKETS = "tickets"
internal const val FEEDBACK_PARAM_CATEGORY = "category"
internal const val FEEDBACK_PARAM_CONTENT = "content"
internal const val FEEDBACK_PARAM_ANON_ID = "anonId"

/** 一次最多认几个工单号（同 `FeedbackApi.replies` 的 `take(20)`）。 */
private const val FEEDBACK_MAX_TICKETS = 20

/** 凭据没配全时那句话。 */
internal const val FEEDBACK_UNCONFIGURED_MESSAGE =
    "反馈后端凭据没配全：启动 serve 前设 FEEDBACK_APP_ID / FEEDBACK_APP_SECRET / FEEDBACK_BASE_TOKEN（可选 FEEDBACK_TABLE_SUBMIT / FEEDBACK_TABLE_REPLY）"
