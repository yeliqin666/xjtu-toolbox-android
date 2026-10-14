package com.xjtu.toolbox.server

import com.xjtu.toolbox.feedback.FeedbackApi
import com.xjtu.toolbox.feedback.FeedbackPublicQa
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/**
 * `/api/feedback*` —— 反馈的**两个只读端点**（`docs/api-contract.md` §5.7）：工单回复 + 公开问答。
 * 走 `:data` 的 [FeedbackApi]（飞书开放平台多维表格），解析口径一行不重做。
 *
 * | 端点 | 对应端口方法 |
 * |---|---|
 * | `GET /api/feedback/replies?tickets=` | `FeedbackSource.replies(tickets)` |
 * | `GET /api/feedback/public-qa` | `FeedbackSource.publicQa()` |
 *
 * ## 为什么是这两条（照 `:core` 的 `FeedbackScreen` 实际读的那几样）
 *
 * 反馈屏的取数只有两处：**自己提过的工单有没有回复**（`replies`，工单号来自 `:core` 的
 * `FeedbackStore` 那份**本地账本**）与「大家在问」（`publicQa`）。两条都是读。
 *
 * ⚠️ **刻意不端的写路径**：`FeedbackSource.submit(...)`（`POST .../records`）—— 提交一条反馈是
 * **写**（真往多维表格里加一行），serve 模式下那个表单**没有端点**（如实缺口，见契约 §5.7）。
 * 于是「提交」那一半在 `:web` 上只能退回 GitHub 外链那条路（屏本来就有这条降级）。
 *
 * ## 会话：这一族**没有**站点的概念
 *
 * 反馈不认 `SiteSession`、也不走统一认证：所谓「登录」是拿 `app_id` + `app_secret` 换一张
 * `tenant_access_token`（见 `FeedbackApi` 的 KDoc）。所以这两个端点**不套 `session.authenticated`**
 * —— 浏览器带上门牌（令牌闸门）就能读；凭据由 serve 进程启动时注入（见 [ServeFeedback]）。
 *
 * ## 逐字段映射
 *
 * - `replies`：`{tickets:[…], count, replies:[{ticket, text, time}]}` —— `replies` 是**数组**而不是
 *   对象：同一个工单号只可能出现一次（`:data` 用 `LinkedHashMap` 收敛），而数组给得出稳定的顺序
 *   （按请求里 `tickets` 的先后）；`ticket` 是 `:data` 归一化过的工单号（`T[A-Z0-9]{8,16}` 那段），
 *   `text` / `time` 分别是回复正文与回复时间（上游原文，`时间` 那一格本来就不是 ISO-8601 的时刻）；
 * - `public-qa`：`{count, items:[{question, answer, category}]}` —— 三条都是上游原文。
 *   ⚠️ 上游那张表里**没勾「公开」的行不进响应**（`:data` 的判据，本层不再筛一次）；
 *   也**绝不**退回整表拉取 —— 那会把未公开的回复读给所有人（`:data` 的 KDoc 把这条写死了）。
 *
 * ## 请求参数与错误码
 *
 * - `?tickets=`：逗号分隔的工单号，**必填**（至少一个非空白）；**最多认 20 个**（`:data` 的
 *   `take(20)` 就是这一条 —— 本层照抄那个上限，多给的不发出去，也不报错）。空白 / 一个都没有 ⇒ `400`；
 * - `public-qa` **不收参数**（URL 写错只能是 `404`）；
 * - `502` 上游 / 网络故障，**含「凭据没配全」那一档**：`FeedbackApi` 没有注入 `config` 时抛
 *   「反馈后端凭据没配全」⇒ 这一层按 502 + 一句**说清怎么办**的中文短句报（见 [ServeFeedback]）；
 *   不用 `404` 是因为端点**在**、只是这个部署没把凭据给它 —— 与「这条路径不存在」是两件事。
 *
 * @see ServeFeedback serve 进程启动时把飞书凭据交给 `:data` 的那一处。
 */
internal fun Route.feedbackRoutes() {
    route(FEEDBACK_SEGMENT) {

        // ── 这些工单号有没有回复（只读）──────────────────────────────
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
    /** 请求里那一串工单号（去空白、去重、**原样回**；超过 20 个的没发出去，也在这里）。 */
    val tickets: List<String>,
    /** 真取到回复的条数（不是请求条数：没人回过的那几个工单不在 [replies] 里）。 */
    val count: Int,
    val replies: List<FeedbackReplyDto>,
)

/** 一条回复：工单号 + 正文 + 回复时间（都是上游原文）。 */
@Serializable
internal data class FeedbackReplyDto(val ticket: String, val text: String, val time: String)

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
internal const val FEEDBACK_PUBLIC_QA = "public-qa"
internal const val FEEDBACK_PARAM_TICKETS = "tickets"

/** 一次最多认几个工单号（= `FeedbackApi.replies` 里的 `take(20)`，本层不另定一个数）。 */
private const val FEEDBACK_MAX_TICKETS = 20

/**
 * 凭据没配全时那句话。**说清怎么办**（§4 的 message 口径）：去看 [ServeFeedback] 认的那几个
 * 环境变量名 —— 用户拿到的不是「502」而是「去哪儿配」。
 */
internal const val FEEDBACK_UNCONFIGURED_MESSAGE =
    "反馈后端凭据没配全：启动 serve 前设 FEEDBACK_APP_ID / FEEDBACK_APP_SECRET / FEEDBACK_BASE_TOKEN（可选 FEEDBACK_TABLE_SUBMIT / FEEDBACK_TABLE_REPLY）"
