package com.xjtu.toolbox.server

import com.xjtu.toolbox.error.FriendlyError
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.coroutines.CancellationException

/**
 * 四个 `*Routes`（jwxt / library / card / venue）共用的两件小事。
 *
 * ## 失败都走 502
 *
 * 这些端点全部是「取数」：`code == 0` 成功，失败只有一种分法 —— **上游/网络的故障**（同样的
 * 请求稍后重试可能就成），所以统一 502（与 `ApiErrors.UPSTREAM_FAILED` 一条口径，先例是登录那
 * 一族的 502 分支）。`message` 一律由 `:core` 的 `FriendlyError` 给（中文短句、不含学号/姓名）。
 * `CancellationException` 照旧往外抛：客户端断了不该被当成「上游故障」。
 *
 * ## 会话与令牌是两件事
 *
 * 令牌闸门只证明「这一枚令牌对」；数据端点还要「**有会话**」—— 没有会话就拿不到站点，
 * 取数只会一路 502。所以每个端点进门先看 [ServeSession.authenticated]
 * （读它会触发幂等的冷启动恢复：落盘凭据还在就直接算有会话）。
 */

/** 取数失败（含会话过期）：502 + `FriendlyError` 的中文短句。 */
internal suspend fun ApplicationCall.respondUpstreamFailure(error: Throwable, what: String) {
    if (error is CancellationException) throw error
    respond(HttpStatusCode.BadGateway, ApiEnvelope<Unit>(ApiErrors.UPSTREAM_FAILED, message = FriendlyError.of(error, what)))
}

/** 令牌对、但没有会话：401 + 中文短句（与令牌闸门同一个 HTTP 码，message 指路）。 */
internal suspend fun ApplicationCall.respondLoginRequired() =
    respond(HttpStatusCode.Unauthorized, ApiEnvelope<Unit>(ApiErrors.UNAUTHORIZED, message = "请先登录：POST /api/session/login"))
/** 请求参数形状不对：400 + 中文短句。 */
internal suspend fun ApplicationCall.respondBadRequestMessage(message: String) =
    respond(HttpStatusCode.BadRequest, ApiErrors.badRequest(message))