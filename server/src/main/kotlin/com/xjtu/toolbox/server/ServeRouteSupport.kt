package com.xjtu.toolbox.server

import com.xjtu.toolbox.error.FriendlyError
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 这几个 `*Routes`（jwxt / library / card / venue / lms / jiaocai / jwapp / iclassface）共用的小工具。
 *
 * ## 读路径的失败都走 502
 *
 * 这些端点的**取数**部分：`code == 0` 成功，失败只有一种分法 —— **上游/网络的故障**（同样的
 * 请求稍后重试可能就成），所以统一 502（与 `ApiErrors.UPSTREAM_FAILED` 一条口径，先例是登录那
 * 一族的 502 分支）。`message` 一律由 `:core` 的 `FriendlyError` 给（中文短句、不含学号/姓名）。
 * `CancellationException` 照旧往外抛：客户端断了不该被当成「上游故障」。
 *
 * **写路径多一档**：上游**明确说这次动作不成**（座位被占 / 验证码有误 / 当前预约上没有这个操作）
 * 是**业务结果**不是故障 ⇒ 409 + 中文短句（[respondActionRejected]，先例是 `/api/library/action`
 * 与 `/api/venue/book`）。重发同样的请求没有意义，所以不是 [ApiErrors.UPSTREAM_FAILED]。
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

/**
 * 上游**明确说**这次写动作不成：409 + 中文短句（见 [ApiErrors.ACTION_REJECTED]）。
 *
 * 先例是 `/api/library/action`（约座被占、当前预约上没有这个操作），`/api/venue/book` ——
 * 验证码有误、场地被抢 —— 同一条口径：这是**业务结果**，不是「上游坏了」。文案是 `:data` 的
 * 结果对象里那句原样的中文（`BookResult.message` / `OrderActionResult.message`），不另写一句。
 */
internal suspend fun ApplicationCall.respondActionRejected(message: String) =
    respond(HttpStatusCode.Conflict, ApiEnvelope<Unit>(ApiErrors.ACTION_REJECTED, message = message))

/**
 * 收一个**整数**查询参数 [name]（缺 / 空白 / 不是整数 / 小于 [min] ⇒ 400 + 中文短句，返回 null）。
 *
 * [what] 是给用户看的那半句说明（「取自哪里」），拼出来的文案形如
 * `courseId 需要是正整数（课程 id（取自 /api/lms/courses 的 id））` —— 一律**说清怎么办**。
 *
 * 为什么收在这里：`/api/lms/{activities,activity}`、`/api/jiaocai/ssno` 这几条端点的参数
 * 都只是「一个/几个 id」，各自写一遍「空 / 非数字」的判据必然漂开（`/api/attendance/records`
 * 的 `?term=` 与 `/api/coupon` 的 `?page=` 就是各写一遍的先例）。[min] 默认 `1`（id 都从 1 起，
 * 上游没有 0 号课），`/api/jiaocai/ssno` 的 `appId` / `engineInstanceId` 传 `0`
 * （它们可以是 0，那是 `:data` 里 `JiaocaiBook` 的默认值）。
 */
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

/**
 * 收一个 JSON 请求体；形状不对（缺字段 / JSON 坏 / content-type 不是 JSON）给 null，
 * 由调用方回 400 + 中文短句。
 *
 * 为什么不让它抛：Ktor 默认会把反序列化失败变成**不带信封**的 400 —— 而契约 §4 说 `/api/…`
 * 的每一个响应都是信封。少了这一层，客户端在「我发错了」这种最该看文案的时候会拿到一个空响应体。
 * `CancellationException` 照旧往外抛（客户端断了不该被当成「请求体不对」）。
 *
 * 原先它是 [SessionRoutes] 里的一个 private 函数；`/api/library/action` 也要收请求体 ⇒ 搬家到这里
 *（"四个 `*Routes` 共用的几件小事"这一份）。
 */
internal suspend inline fun <reified T : Any> ApplicationCall.receiveOrNull(): T? = try {
    receive<T>()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

/**
 * epoch 毫秒 → ISO-8601 带时区字符串（契约 §4）；`<= 0`（没读过 / 没过期 / 本端不知道）→ null。
 *
 * 原先它是 `ServeEndpointRoutes.kt` 里的一个 private 函数（`/api/inbox` 那几处时间字段在用）。
 * `/api/accounts` 也要端时间（账号表的 `lastUsedAt`）⇒ 按 [receiveOrNull] 同一条做法搬到这里 ——
 * 时间的口径只有这一份（手写 `SimpleDateFormat`、各文件各写一遍都是第二个口径）。
 */
internal fun isoTime(epochMs: Long): String? {
    if (epochMs <= 0L) return null
    return Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(ISO_FORMATTER)
}

/**
 * `?page=&size=` 那一类的**正整数读法**：没给 ⇒ [default]；给了但不是正整数 ⇒ null
 *（由调用方回 `400`）。
 *
 * 原先它是 [CouponRoutes] 里的一个 private 函数；`/api/attendance/leave/records` 也要这两个参数
 * ⇒ 按 [receiveOrNull] / [isoTime] 同一条做法搬到这里 —— 分页参数的判据只有这一份
 *（各写一遍必然漂开，而「`page=0` 到底算第几页」这种问题不该有两个答案）。
 */
internal fun io.ktor.http.Parameters.positiveInt(name: String, default: Int): Int? {
    val raw = get(name)?.trim() ?: return default
    return raw.toIntOrNull()?.takeIf { it > 0 }
}

private val ISO_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
