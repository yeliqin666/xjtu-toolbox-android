package com.xjtu.toolbox.server

import com.xjtu.toolbox.util.AppJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** `/api/…` 的信封（`docs/api-contract.md` §4）：`{ code, data, message }`。 */
@Serializable
data class ApiEnvelope<T>(
    val code: Int,
    val data: T? = null,
    val message: String? = null,
) {
    companion object {
        /** `code == 0`：成功。 */
        const val CODE_OK = 0

        fun <T> ok(data: T? = null): ApiEnvelope<T> = ApiEnvelope(CODE_OK, data)
    }
}

/** `/api/…` 的失败码与本端写死的中文短句。 */
object ApiErrors {

    /** 无令牌 / 令牌不对。消息要说清「怎么办」，不能只说「Unauthorized」。 */
    const val UNAUTHORIZED = 401
    const val UNAUTHORIZED_MESSAGE = "访问令牌缺失或无效：请带 Authorization: Bearer <令牌>"

    /** 端点不存在。 */
    const val NOT_FOUND = 404
    const val NOT_FOUND_MESSAGE = "接口不存在"

    /** 请求体不是这个端点要的形状（少了字段 / 不是 JSON）。 */
    const val BAD_REQUEST = 400

    /** 登录没有成功，且用户得改点什么（凭据不对 / 短信二验被取消或被拒）—— 再来一次同样的请求也不会变。 */
    const val LOGIN_FAILED = 401

    /** 登录/取数中途是上游或网络的故障（非「用户输入错」那一类）：同样的请求稍后重试可能就成功。 */
    const val UPSTREAM_FAILED = 502

    /** 另一次登录（含挂起的短信二验）还没跑完。 */
    const val LOGIN_BUSY = 409
    const val LOGIN_BUSY_MESSAGE = "已有一次登录正在进行（可能在等短信验证码），请稍后再试或先取消它"

    /** 上游明确说这次写动作不成（座位已被他人预约 / 已有别的预约 / 不在开放时间 / 当前预约上没有这个操作）。 */
    const val ACTION_REJECTED = 409

    /**
     * 写账号要求在 HTTPS 下（`docs/parity-ledger.md` §6.1 #10 = 丙）。
     *
     * 与 [BAD_REQUEST] 分开单列：客户端靠这个码决定「把启用 HTTPS 的指引摆到用户面前」，
     * 而 400 只意味着「这次请求本身写错了」。
     */
    const val HTTPS_REQUIRED = 403

    /** 密码类写端点在明文 HTTP 上的拒绝文案：要说清怎么开 HTTPS / 怎么弄一张自签证书。 */
    const val HTTPS_REQUIRED_MESSAGE =
        "写账号必须在 HTTPS 下（明文 HTTP 上不收密码）：给 serve 加 --https 重启即会自动生成自签证书" +
            "（<数据根>/tls/serve.p12）；想自己出一张就用 keytool -genkeypair -alias serve -keyalg RSA " +
            "-keysize 2048 -validity 3650 -dname CN=localhost -ext SAN=dns:localhost,ip:127.0.0.1 " +
            "-storetype PKCS12 -keystore serve.p12，再用 --https --cert serve.p12 指过去。读端点不受影响。"

    const val LOGIN_BODY_MESSAGE = "请求体需要 JSON：{\"username\":\"…\",\"password\":\"…\"}"

    const val MFA_BODY_MESSAGE = "请求体需要 JSON：{\"code\":\"…\"} 或 {\"cancel\":true}"

    fun unauthorized(): ApiEnvelope<Unit> = ApiEnvelope(UNAUTHORIZED, message = UNAUTHORIZED_MESSAGE)

    fun notFound(): ApiEnvelope<Unit> = ApiEnvelope(NOT_FOUND, message = NOT_FOUND_MESSAGE)

    fun httpsRequired(): ApiEnvelope<Unit> = ApiEnvelope(HTTPS_REQUIRED, message = HTTPS_REQUIRED_MESSAGE)

    fun badRequest(message: String): ApiEnvelope<Unit> = ApiEnvelope(BAD_REQUEST, message = message)

    /** [message] 是 `:core` 的 `FriendlyError` 给的中文短句（保证不含学号/姓名）。 */
    fun loginFailed(message: String, retryable: Boolean): ApiEnvelope<Unit> =
        ApiEnvelope(if (retryable) UPSTREAM_FAILED else LOGIN_FAILED, message = message)

    fun loginBusy(): ApiEnvelope<Unit> = ApiEnvelope(LOGIN_BUSY, message = LOGIN_BUSY_MESSAGE)
}

/** 出网用的 JSON：与 `:core` 的 [AppJson] 同一份配置，只多开一个 `explicitNulls`。 */
internal val ApiJson: Json = Json(AppJson) { explicitNulls = true }
