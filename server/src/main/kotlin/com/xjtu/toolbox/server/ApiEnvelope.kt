package com.xjtu.toolbox.server

import com.xjtu.toolbox.util.AppJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `/api/…` 的**信封**（`docs/api-contract.md` §4）：`{ code, data, message }`。
 *
 * 两条口径：
 * - `code == 0` 是成功，`code != 0` 是失败，且此时 [message] 必须是**能直接给用户看的中文短句**
 *   （口径与 `:core` 的 `FriendlyError` 一致：那里是「哪些失败该说什么话」的真源）。
 * - 本实现里 `code` **就等于 HTTP 状态码**（400 / 401 / 404 / 409 / 502 …），一条信息不出现两种写法。
 *   这一条已写进契约 §4（`docs/api-contract.md`），[ApiErrors] 是它的实现。
 *
 * ⚠️ `/api/status` **不用**信封，它是裸对象（§4 明文写的例外）。所以别把 [ApiEnvelope] 当成
 * 「所有 /api 响应都长这样」。
 */
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

    /** 端点还没搬过来（或写错了路径）。 */
    const val NOT_FOUND = 404
    const val NOT_FOUND_MESSAGE = "接口不存在"

    /** 请求体不是这个端点要的形状（少了字段 / 不是 JSON）。 */
    const val BAD_REQUEST = 400

    /**
     * 登录**没有成功**，且用户得改点什么（凭据不对 / 短信二验被取消或被拒）—— 再来一次同样的请求
     * 也不会变。与 [UPSTREAM_FAILED] 的区别只在「重试有没有意义」，两者都带中文短句。
     */
    const val LOGIN_FAILED = 401

    /**
     * 登录/取数中途是**上游或网络**的故障（非「用户输入错」那一类）：同样的请求稍后重试可能就成功。
     * 用 502 而不是 500：它确实总是「我们这层没事、后面那层出了问题」。
     */
    const val UPSTREAM_FAILED = 502

    /**
     * 另一次登录（含挂起的短信二验）还没跑完。
     *
     * 为什么不是「排队」：挂起的那个可能正等用户输验证码（最长 150 秒）—— 让第二个请求挂在
     * 后面等它，客户端会看到一个没完没了的 spinner。409 是「现在不能，先处理那一个」的准确写法。
     */
    const val LOGIN_BUSY = 409
    const val LOGIN_BUSY_MESSAGE = "已有一次登录正在进行（可能在等短信验证码），请稍后再试或先取消它"

    const val LOGIN_BODY_MESSAGE = "请求体需要 JSON：{\"username\":\"…\",\"password\":\"…\"}"
    const val MFA_BODY_MESSAGE = "请求体需要 JSON：{\"code\":\"…\"} 或 {\"cancel\":true}"

    fun unauthorized(): ApiEnvelope<Unit> = ApiEnvelope(UNAUTHORIZED, message = UNAUTHORIZED_MESSAGE)

    fun notFound(): ApiEnvelope<Unit> = ApiEnvelope(NOT_FOUND, message = NOT_FOUND_MESSAGE)

    fun badRequest(message: String): ApiEnvelope<Unit> = ApiEnvelope(BAD_REQUEST, message = message)

    /** [message] 是 `:core` 的 `FriendlyError` 给的中文短句（保证不含学号/姓名）。 */
    fun loginFailed(message: String, retryable: Boolean): ApiEnvelope<Unit> =
        ApiEnvelope(if (retryable) UPSTREAM_FAILED else LOGIN_FAILED, message = message)

    fun loginBusy(): ApiEnvelope<Unit> = ApiEnvelope(LOGIN_BUSY, message = LOGIN_BUSY_MESSAGE)
}

/**
 * **出网**用的 JSON：与 `:core` 的 [AppJson] 同一份配置，只多开一个 `explicitNulls`。
 *
 * 为什么必须多这一下：`AppJson` 的 `explicitNulls = false` 会把 `null` 写成**字段缺失**，
 * 而契约 §4 明确要求「`null` = 本端明确说没有」与「字段缺失 = 本端不投影」**不能混用**。
 * 出网的信封里 `data: null` 是「这条错误没有数据」，写成缺失就破坏了那条区分（客户端读不出
 * 「有字段但是 null」与「压根没这个字段」的差别）。
 *
 * 读入侧保持 `AppJson` 的全部宽松口径不变（忽略未知字段、lenient…），只影响写出。
 */
internal val ApiJson: Json = Json(AppJson) { explicitNulls = true }
