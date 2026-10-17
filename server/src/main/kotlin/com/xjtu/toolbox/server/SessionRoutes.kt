package com.xjtu.toolbox.server

import com.xjtu.toolbox.auth.MfaRequest
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

/** `/api/session*` —— 四个端点（`docs/api-contract.md` §5 的「P0（新）」那一行）。 */
internal fun Route.sessionRoutes(
    session: ServeSession,
    accessToken: String,
    secureCookie: Boolean = false,
) {
    // ⚠️ 相对段而不是绝对路径：这棵子树已经挂在 `route("/api")` 之下，Ktor 会把路径段**接在父节点后面**
    //    —— 再写一遍 `/api/session` 会得到 `/api/api/session`（一个静默的 404，测试会告诉你）。
    route(SESSION_SEGMENT) {

        /** 有没有会话。 */
        get {
            call.response.header(
                HttpHeaders.SetCookie,
                AccessToken.setCookieHeader(accessToken, secure = secureCookie),
            )
            call.respond(ApiEnvelope.ok(SessionState(session.authenticated)))
        }

        /** 登一次。 */
        post(SESSION_LOGIN) {
            val body = call.receiveOrNull<LoginRequest>() ?: return@post call.respondBadRequest(ApiErrors.LOGIN_BODY_MESSAGE)
            when (val outcome = session.login(body.username, body.password)) {
                LoginOutcome.Success -> call.respond(ApiEnvelope.ok(SessionState(authenticated = true)))
                LoginOutcome.Busy -> call.respond(HttpStatusCode.Conflict, ApiErrors.loginBusy())
                is LoginOutcome.Failed -> call.respond(
                    if (outcome.retryable) HttpStatusCode.BadGateway else HttpStatusCode.Unauthorized,
                    ApiErrors.loginFailed(outcome.message, outcome.retryable),
                )
            }
        }

        /** 退出登录。没有请求体（`{}` 也行，客户端不必编一个）；幂等：本来就没登录也是 200。 */
        post(SESSION_LOGOUT) {
            session.logout()
            call.respond(ApiEnvelope.ok(SessionState(authenticated = false)))
        }

        /** 短信二验：轮询挂起的那条询问。 */
        get(SESSION_MFA) {
            val request = session.activeMfaRequest
            if (request == null) return@get call.respond(ApiEnvelope.ok(MfaState(pending = false)))
            session.prepareMfaDialog(request)
            call.respond(ApiEnvelope.ok(mfaStateOf(session.activeMfaRequest)))
        }

        /** 短信二验：动作。 */
        post(SESSION_MFA) {
            val body = call.receiveOrNull<MfaSubmitRequest>() ?: return@post call.respondBadRequest(ApiErrors.MFA_BODY_MESSAGE)
            val request = session.activeMfaRequest
                ?: return@post call.respond(ApiEnvelope.ok(MfaState(pending = false)))
            when {
                body.cancel -> request.cancel()
                body.code != null -> request.submit(body.code)
                else -> return@post call.respondBadRequest(ApiErrors.MFA_BODY_MESSAGE)
            }
            call.respond(ApiEnvelope.ok(mfaStateOf(session.activeMfaRequest)))
        }
    }
}

/** `GET /api/session` 与登录/登出响应里的 `data`。**只有这一个字段**：身份不归这一层（见文件头）。 */
@Serializable
internal data class SessionState(val authenticated: Boolean)

/** `/api/session/mfa` 的 `data`。 */
@Serializable
internal data class MfaState(
    val pending: Boolean,
    val siteName: String? = null,
    val rejections: Int? = null,
    val attemptsLeft: Int? = null,
)

/** `POST /api/session/login` 的请求体。学号两侧的空白由 [ServeSession.login] 去掉。 */
@Serializable
internal data class LoginRequest(val username: String, val password: String)

/** `POST /api/session/mfa` 的请求体：`code` 与 `cancel` 二选一（两个都不给是 400）。 */
@Serializable
internal data class MfaSubmitRequest(val code: String? = null, val cancel: Boolean = false)

/** `/api/session` 一族的路由段。 */
internal const val SESSION_SEGMENT = "session"
internal const val SESSION_PATH = "$API_PREFIX/$SESSION_SEGMENT"
internal const val SESSION_LOGIN = "login"
internal const val SESSION_LOGOUT = "logout"
internal const val SESSION_MFA = "mfa"

/** 挂起的询问 → 线上的形状（见 [MfaState]）。 */
private fun mfaStateOf(request: MfaRequest?): MfaState {
    if (request == null) return MfaState(pending = false)
    val rejections = request.rejections.value
    return MfaState(
        pending = true,
        siteName = request.siteName,
        rejections = rejections,
        // 与内核 `SessionManager.MFA_MAX_ATTEMPTS` 同一个数（见 ServeSession.MFA_MAX_ATTEMPTS 的 KDoc）
        attemptsLeft = (ServeSession.MFA_MAX_ATTEMPTS - rejections).coerceAtLeast(0),
    )
}


/** 400 + 信封（[ApiErrors.badRequest]）。 */
private suspend fun ApplicationCall.respondBadRequest(message: String) =
    respond(HttpStatusCode.BadRequest, ApiErrors.badRequest(message))
