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

/**
 * **这一份 `Campus*Api` 在对谁说话** —— 两种后端共用同一份屏/端口，只是 JSON 形状不同。
 *
 * | | [CAMPUS_API]（旧，默认） | [SERVE]（新） |
 * |---|---|---|
 * | 谁在答 | 同源反代的 campus-api（`127.0.0.1:3099`） | serve 模式的 `:server`（`127.0.0.1:8123`，`:web` 由它托管） |
 * | 信封 | `{code,data,message}`，`code == 0` 成功、非 0 是一个**业务码** | 同一个信封，但 `code`**就是 HTTP 状态码**（`docs/api-contract.md` §4），失败时 `message` 是能直接给用户看的中文短句 |
 * | 错误码怎么读 | 看 `code != 0` 与 `msg`/`error` 两个字段 | 看 `code != 0` 与 `message` 一个字段（不许把 HTTP 状态码当业务码解） |
 * | 字段面 | campus-api 的投影（有些字段**刻意不投影**） | 契约 §5 的投影（`要改`的那几条补齐了新字段与能力开关） |
 *
 * ## 为什么用参数而不是「改造这两条路中的一条」
 *
 * C1 红线是 **Android 行为完全不变**：Android（`:app`）/ 桌面端 / 既有测试**一个调用点都没动**，
 * 靠的就是这里的默认值 —— 不传 [ApiMode] 时逐字走老路（分支都写在 `mode == ApiMode.SERVE` 那一侧，
 * 老代码一行不重排）。serve 模式只在 `:web` 显式传 [SERVE] 时启用。
 *
 * ## 谁决定用哪个模式
 *
 * `:web` 的装配（`web/…/ServeMode.kt`）：`?backend=` 显式覆盖，否则探一次 `/api/status` ——
 * serve 的那一份是**裸对象且不含身份**（契约 §3.4），campus-api 的那一份带 `username`，
 * 这是两者在同一个路径上唯一稳定的差别。
 */
enum class ApiMode {
    /** 旧形状（默认）：同源反代的 campus-api。 */
    CAMPUS_API,

    /** 新契约：serve 模式的 `:server`（`docs/api-contract.md` §4/§5）。 */
    SERVE,
}

/**
 * serve 契约（§4）的一次取数：**成功的判据是 `code == 0`**，失败时 `code` 与 HTTP 状态码逐位相同、
 * `message` 直接是给用户看的中文短句（`ApiErrors` 是服务端那一侧的实现）。
 *
 * 与旧路的三处刻意差别（都是契约里钉死的，不是风格问题）：
 *  1. 失败文案只读 `message` —— 旧形状的 `msg`/`error` 在新契约里**不存在**，读它们等于永远拿到空串；
 *  2. **不把 HTTP 状态码当业务码**：`code != 0` 就失败，`code` 是几不重要（它等于 HTTP 状态码只是
 *     为了让「一条信息不出现两种写法」成立，客户端不需要拿它分支）；
 *  3. `data` 缺失是**错误**（`null` 与「字段缺失」在 §4 里是两件事：前者是"本端明确说没有"，
 *     后者是"本端不投影"）—— 顶层 `data` 缺失说明这个端点没按契约答，如实报出来。
 *
 * @param what 面向用户的动作名（"加载校历"这种），用来拼一句能看懂的话
 * @param baseUrl 同 [CampusApi] 的约定：留空 ⇒ 相对路径即同源（`:web` 由 `:server` 托管，必须是空）
 * @param query 查询参数；**空值的参数不发**（与旧路 `getData` 的判据一致）
 * @throws IllegalStateException 非 JSON / `code != 0` / 缺 `data`（message 是给人看的那一句）
 */
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


/**
 * serve 契约的一次**写**（`POST`，例如 `POST /api/library/action`、`POST /api/venue/book`）。
 *
 * 与 [serveData] 的差别在**失败怎么报**：写动作的失败是**业务结果**（座位被占、验证码有误、当前
 * 预约上没有这个操作 —— 契约 §5.3.1 / §5.3.2 里这些是 `409` + 中文 `message`），调用方要把它显示
 * 出来，不能当异常吞掉。所以这里把它**返回**而不是抛：
 *
 * - `code == 0` ⇒ `ok = true`，`message` = `data.message`，`data` = 整个 `data` 对象
 *   （除了 `message` 之外还要读结果的写端点用它：`/api/venue/book` 的 `orderId` / `price`）；
 * - `code != 0` ⇒ `ok = false` + 信封的 `message`，`data` 为 null（失败响应的 `data` 本来就不存在）；
 * - 形状没对上（非 JSON / 缺顶层 `data` / 网络错）**仍然抛** —— 那是「这一枪没打通」，不是「上游说不行」。
 *
 * 请求体是调用方拼好的 JSON 字符串（本服务只有两个写端点，不值得为它们起一个序列化框架）。
 */
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
        return ServeActionOutcome(ok = false, message = message, data = null)
    }
    val data = envelope["data"] as? JsonObject
        ?: error("服务端返回缺少 data（HTTP ${response.status.value}）")
    return ServeActionOutcome(ok = true, message = data["message"].safeString(), data = data)
}

/**
 * 一次写动作的结果（见 [serveAction]）：业务拒绝（`code != 0`）也是**结果**，不是异常。
 *
 * [data] 是成功时信封里那个 `data` 对象（`:core` 的调用方按自己的形状读它）—— 失败时为 null。
 */
internal data class ServeActionOutcome(
    val ok: Boolean,
    val message: String,
    val data: JsonObject?,
)
