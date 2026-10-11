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

/**
 * `/api/session*` —— 四个端点（`docs/api-contract.md` §5 的「P0（新）」那一行）。
 *
 * ## 它们是什么
 *
 * | 端点 | 干什么 |
 * |---|---|
 * | `GET /api/session` | 有没有会话（+ 把访问令牌换成 cookie） |
 * | `POST /api/session/login` | 学号密码登一次（走完整 CAS，可能挂起等短信验证码） |
 * | `POST /api/session/logout` | 退出（凭据 / cookie / 站点快照一起删） |
 * | `GET /api/session/mfa` | 有没有挂起的短信二验询问（**轮询**） |
 * | `POST /api/session/mfa` | 交验证码，或取消 |
 *
 * ## 三个共同口径
 *
 * 1. **挂载点**：都挂在 [serveModule] 的 `route("/api")` 里 ⇒ 自动被 [accessTokenGate] 罩住
 *    （`/api/status` 是闸门里那条**唯一**的豁免，这里没有第二条）；
 * 2. **形状**：一律 [ApiEnvelope] 信封（`code == HTTP 状态码`，见 [ApiErrors]），
 *    唯一的例外是 `/api/status`（裸对象，历史遗留）；
 * 3. **红线**：响应体与日志**绝不出现学号/姓名**。所以这里没有任何「当前账号」字段 ——
 *    「是谁」不属于这一层（§3.4：`/api/status` 那个免令牌端点的 KDoc 记着同一条）。
 *
 * ## MFA 为什么是轮询而不是 SSE
 *
 * 「服务端主动问用户要验证码」这件事在 HTTP 上有两种形态：**轮询** `/api/session/mfa`，或上 SSE 推。
 * 这里定的是**轮询**（契约 §5 写死）：它不需要新协议、断线重连是客户端一行 `setInterval`，
 * 而这一层的并发量是「一个人的浏览器」—— 省下的那点请求量毫无价值，多出来的协议面却是真的。
 * 形态与两端的窗口实现一致：`SessionManager.activeMfaRequest` 是那条询问，弹窗轮询它的
 * `rejections` / 站点名；`POST` 是「提交」或「取消」两个动作，**结果一律从下一次轮询读**
 * （验证码是发给内核那条登录流程的，它才是决定成功/失败的那一边）。
 *
 * @param session 会话装配（[ServeSession]）；四个端点都只是它的薄投影。
 * @param accessToken 换 cookie 用的那一枚令牌（[AccessToken.setCookieHeader]）。
 */
internal fun Route.sessionRoutes(session: ServeSession, accessToken: String) {
    // ⚠️ 相对段而不是绝对路径：这棵子树已经挂在 `route("/api")` 之下，Ktor 会把路径段**接在父节点后面**
    //    —— 再写一遍 `/api/session` 会得到 `/api/api/session`（一个静默的 404，测试会告诉你）。
    route(SESSION_SEGMENT) {

        /**
         * 有没有会话。**同时是「令牌换 cookie」的那一步**（契约 §3.2 的第二种形态）：
         * 浏览器在地址栏里贴不了 `Authorization`，所以它先用 Bearer 打这一枪，
         * 之后只带 cookie（闸门两种形态都认）。
         *
         * 带 cookie 的那一行只在这里发：`/api/session*` 的其余三个端点都不发 —— 客户端启动时
         * 必然要问一次「有没有会话」，cookie 顺手就拿到了，多余地到处发只会多出几个「哪一份才是真的」。
         */
        get {
            call.response.header(HttpHeaders.SetCookie, AccessToken.setCookieHeader(accessToken))
            call.respond(ApiEnvelope.ok(SessionState(session.authenticated)))
        }

        /**
         * 登一次。挂起等短信验证码时这一发**一直开着**（最长 150 秒）—— 客户端在它挂起期间
         * 去轮询 `/api/session/mfa`，别的端点也照常（内核在等 Channel，不占线程）。
         *
         * 失败码两种：**401** = 凭据/验证被拒（重发同样的请求没意义），**502** = 上游或网络的故障
         * （稍后重试可能就成）。另一次登录还在跑则 **409**。
         */
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

        /**
         * 短信二验：**轮询**挂起的那条询问。
         *
         * 报 `pending:true` 的时候就顺手做一件事：[ServeSession.prepareMfaDialog] 取回绑定手机号
         * （`MFAContext.getPhoneNumber()`）—— 它不只是为了显示，`verifyCode` 要拿它设下的 `gid`
         * 才能校验验证码（见那个函数的 KDoc）。
         *
         * ⚠️ **手机号刻意不投出来**：这一轮的形状定死是 `{pending, siteName, rejections, attemptsLeft}`
         * （契约 §5）。弹窗里那句「验证码已发送到 138****0000」要它，但多一个字段就多一处口径
         * —— 留到浏览器那个弹窗真开始画的时候一起加（契约里记着这条 TODO）。
         */
        get(SESSION_MFA) {
            val request = session.activeMfaRequest
            if (request == null) return@get call.respond(ApiEnvelope.ok(MfaState(pending = false)))
            session.prepareMfaDialog(request)
            call.respond(ApiEnvelope.ok(mfaStateOf(session.activeMfaRequest)))
        }

        /**
         * 短信二验：**动作**。两种形态二选一 —— `{"code":"…"}`（交验证码）或 `{"cancel":true}`（取消）。
         *
         * 返回的是**动作之后那一刻**的快照，不是结果：交上去的验证码由内核那条登录流程去校验，
         * 结论从下一次轮询（`rejections` 涨没涨）或那一发登录请求的响应读。取消之后
         * `_activeMfaRequest` 要等登录流程从等待里退出来才会变 null，所以这一枪可能仍报 `pending:true`
         * ——这是如实的，别把它读成「取消没生效」。
         *
         * 没有挂起的询问时：**幂等**返回 `{pending:false}`，不是 4xx（浏览器轮询到一半、
         * 登录已经在别处结束，这种情况本来就会发生）。
         */
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

/**
 * `/api/session/mfa` 的 `data`。
 *
 * 没有挂起时只有 `pending:false` 是「有意义的」—— 其余三个字段按 §4 的口径写成 `null`（= 本端
 * 明确说没有），不是字段缺失（浏览器读得出一件事：此刻没有任何询问）。
 */
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

/**
 * `/api/session` 一族的路由段。
 *
 * [SESSION_SEGMENT] 是路由树里用的那一段（相对 [API_PREFIX]），[SESSION_PATH] 是它对外的完整路径
 * —— 由前者拼出来，所以两者永远对得上（测试里的请求路径用的就是后者）。
 */
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

/**
 * 收请求体；形状不对（缺字段 / JSON 坏 / content-type 不是 JSON）给 null。
 *
 * 为什么不让它抛：Ktor 默认会把反序列化失败变成**不带信封**的 400 —— 而契约 §4 说 `/api/…`
 * 的每一个响应都是信封。少了这一层，客户端在「我发错了」这种最该看文案的时候会拿到一个空响应体。
 * `CancellationException` 照旧往外抛（客户端断了不该被当成「请求体不对」）。
 */
private suspend inline fun <reified T : Any> ApplicationCall.receiveOrNull(): T? = try {
    receive<T>()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

/** 400 + 信封（[ApiErrors.badRequest]）。 */
private suspend fun ApplicationCall.respondBadRequest(message: String) =
    respond(HttpStatusCode.BadRequest, ApiErrors.badRequest(message))
