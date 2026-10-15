package com.xjtu.toolbox.server

import com.xjtu.toolbox.error.FriendlyError
import com.xjtu.toolbox.error.SessionExpiredFailure
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 这几个 `*Routes`（jwxt / library / card / venue / lms / jiaocai / jwapp / iclassface）共用的小工具。 */

/**
 * 取数失败：502 + `FriendlyError` 的中文短句。
 * 学校那边的会话过期（[SessionExpiredFailure]，`SessionExpiredException` / `AuthExpiredException` 都实现它）
 * 不是上游故障，重试也没用 ⇒ 改回 401（[respondLoginRequired]），让客户端去重新登录。
 */
internal suspend fun ApplicationCall.respondUpstreamFailure(error: Throwable, what: String) {
    if (error is CancellationException) throw error
    if (error is SessionExpiredFailure) return respondLoginRequired()
    respond(HttpStatusCode.BadGateway, ApiEnvelope<Unit>(ApiErrors.UPSTREAM_FAILED, message = FriendlyError.of(error, what)))
}

/** 令牌对、但没有会话：401 + 中文短句（与令牌闸门同一个 HTTP 码，message 指路）。 */
internal suspend fun ApplicationCall.respondLoginRequired() =
    respond(HttpStatusCode.Unauthorized, ApiEnvelope<Unit>(ApiErrors.UNAUTHORIZED, message = "请先登录：POST /api/session/login"))
/** 请求参数形状不对：400 + 中文短句。 */
internal suspend fun ApplicationCall.respondBadRequestMessage(message: String) =
    respond(HttpStatusCode.BadRequest, ApiErrors.badRequest(message))

/** 上游明确说这次写动作不成：409 + 中文短句（见 [ApiErrors.ACTION_REJECTED]）。 */
internal suspend fun ApplicationCall.respondActionRejected(message: String) =
    respond(HttpStatusCode.Conflict, ApiEnvelope<Unit>(ApiErrors.ACTION_REJECTED, message = message))

/** 收一个整数查询参数 [name]（缺 / 空白 / 不是整数 / 小于 [min] ⇒ 400 + 中文短句，返回 null）。 */
internal suspend fun ApplicationCall.requireIntParam(name: String, what: String, min: Int = 1): Int? {
    val raw = request.queryParameters[name]?.trim().orEmpty()
    if (raw.isEmpty()) {
        respondBadRequestMessage("$name 必填（$what）")
        return null
    }
    val value = raw.toIntOrNull()
    if (value == null || value < min) {
        respondBadRequestMessage(
            when {
                min >= 1 -> "$name 需要是正整数（$what）"
                min == 0 -> "$name 需要是非负整数（$what）"
                else -> "$name 需要是不小于 $min 的整数（$what）"
            },
        )
        return null
    }
    return value
}

/** 收一个 JSON 请求体；形状不对（缺字段 / JSON 坏 / content-type 不是 JSON）给 null，由调用方回 400 + 中文短句。 */
internal suspend inline fun <reified T : Any> ApplicationCall.receiveOrNull(): T? = try {
    receive<T>()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

/** epoch 毫秒 → ISO-8601 带时区字符串（契约 §4）；`<= 0`（没读过 / 没过期 / 本端不知道）→ null。 */
internal fun isoTime(epochMs: Long): String? {
    if (epochMs <= 0L) return null
    return Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(ISO_FORMATTER)
}

/** `?page=&size=` 那一类的正整数读法：没给 ⇒ [default]；给了但不是正整数 ⇒ null （由调用方回 `400`）。 */
internal fun io.ktor.http.Parameters.positiveInt(name: String, default: Int): Int? {
    val raw = get(name)?.trim() ?: return default
    return raw.toIntOrNull()?.takeIf { it > 0 }
}

private val ISO_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
