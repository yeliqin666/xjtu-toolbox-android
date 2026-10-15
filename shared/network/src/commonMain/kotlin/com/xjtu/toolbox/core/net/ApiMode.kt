package com.xjtu.toolbox.core.net

import com.xjtu.toolbox.util.AppJson
import com.xjtu.toolbox.util.safeString
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.JsonObject

/** 这一份 `Campus*Api` 在对谁说话 —— 两种后端共用同一份屏/端口，只是 JSON 形状不同。 */
enum class ApiMode {
    /** 旧形状（默认）：同源反代的 campus-api。 */
    CAMPUS_API,

    /** 新契约：serve 模式的 `:server`（`docs/api-contract.md` §4/§5）。 */
    SERVE,
}

/** serve 契约（§4）的一次取数：成功即 `code == 0`，失败时 `message` 是给用户看的中文短句。 */
internal suspend fun HttpClient.serveData(
    what: String,
    baseUrl: String,
    path: String,
    query: List<Pair<String, String>> = emptyList(),
): JsonObject {
    val response = get("$baseUrl$path") {
        query.forEach { (k, v) -> if (v.isNotEmpty()) parameter(k, v) }
    }
    val text = response.bodyAsText()
    val envelope = AppJson.parseToJsonElement(text) as? JsonObject
        ?: error("$what：服务端返回不是 JSON 对象（HTTP ${response.status.value}）")
    // `code` 是 JSON 数字；`safeString` 走 JsonPrimitive.content，数字读成 "0" 那一串字符
    val code = envelope["code"].safeString().trim().toIntOrNull()
    if (code != 0) {
        val message = envelope["message"].safeString().trim()
        error("$what：${message.ifBlank { "服务端返回 HTTP ${response.status.value}" }}")
    }
    return envelope["data"] as? JsonObject
        ?: error("$what：服务端返回缺少 data（HTTP ${response.status.value}）")
}

/** 「这条取数链只在 serve 契约里」的固定说法。 */
internal fun serveOnly(what: String, endpoints: String): Nothing = throw UnsupportedOperationException(
    "campus-api 形态没有「$what」的端点：它只在 serve 契约里（$endpoints，docs/api-contract.md §5.5）" +
        "—— 这一端要在 serve 模式下才有这条取数链",
)


/** serve 契约的一次写（`POST`，例如 `POST /api/library/action`、`POST /api/venue/book`）。 */
internal suspend fun HttpClient.serveAction(
    baseUrl: String,
    path: String,
    body: String,
): ServeActionOutcome {
    val response = post("$baseUrl$path") {
        contentType(ContentType.Application.Json)
        setBody(body)
    }
    val text = response.bodyAsText()
    val envelope = AppJson.parseToJsonElement(text) as? JsonObject
        ?: error("服务端返回不是 JSON 对象（HTTP ${response.status.value}）")
    val code = envelope["code"].safeString().trim().toIntOrNull()
    if (code != 0) {
        val message = envelope["message"].safeString().trim()
            .ifBlank { "服务端返回 HTTP ${response.status.value}" }
        return ServeActionOutcome(ok = false, message = message, data = null, code = code ?: 0)
    }
    val data = envelope["data"] as? JsonObject
        ?: error("服务端返回缺少 data（HTTP ${response.status.value}）")
    return ServeActionOutcome(ok = true, message = data["message"].safeString(), data = data)
}

/** 一次写动作的结果（见 [serveAction]）：业务拒绝（`code != 0`）也是结果，不是异常。 */
internal data class ServeActionOutcome(
    val ok: Boolean,
    val message: String,
    val data: JsonObject?,
    /** 信封里的 `code`（= HTTP 状态码）：成功 `0`，失败是那一次的状态码。 */
    val code: Int = 0,
)
