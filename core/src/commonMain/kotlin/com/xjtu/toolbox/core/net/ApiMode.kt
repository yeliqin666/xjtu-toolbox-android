package com.xjtu.toolbox.core.net

import com.xjtu.toolbox.util.AppJson
import com.xjtu.toolbox.util.safeString
import io.ktor.client.HttpClient
import io.ktor.client.request.get
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
