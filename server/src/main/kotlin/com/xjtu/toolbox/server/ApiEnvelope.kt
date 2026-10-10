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
 * - 本实现里 `code` **就等于 HTTP 状态码**（401 / 404 …），一条信息不出现两种写法。
 *   契约只写了「`code != 0` 即失败」，具体取值由实现定 —— 这一条要在契约文档里补一句
 *   （见交付报告里的「待契约确认」）。
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

    fun unauthorized(): ApiEnvelope<Unit> = ApiEnvelope(UNAUTHORIZED, message = UNAUTHORIZED_MESSAGE)

    fun notFound(): ApiEnvelope<Unit> = ApiEnvelope(NOT_FOUND, message = NOT_FOUND_MESSAGE)
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
