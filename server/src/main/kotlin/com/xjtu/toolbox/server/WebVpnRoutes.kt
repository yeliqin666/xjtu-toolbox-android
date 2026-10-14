package com.xjtu.toolbox.server

import com.xjtu.toolbox.webvpn.WebVpnUtil
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/**
 * `/api/webvpn/convert` —— WebVPN 网址互转（`docs/api-contract.md` §5.7）的**一个纯转换端点**。
 * 走 `:data` 的 [WebVpnUtil]（AES-128-CFB，key / iv 都是 `wrdvpnisthebest!`），
 * 一行算法都不重做 —— `:core` 的 `WebVpnConverterScreen` 认的那条缝 `WebVpnCodec` 就是它实现的
 * （Android 与桌面注入的也是同一个对象）。
 *
 * ## 为什么放在服务端（而不是浏览器本地转）—— 这一条要写清
 *
 * 转换本体要**一个对称分组密码**。浏览器端能白拿的只有 `crypto.subtle`，而它**只在安全上下文里
 * 存在**：
 * 1. serve 模式默认明文 HTTP；`--host 0.0.0.0` 时页面就**不是** localhost 了 ⇒
 *    按规范那是**非安全上下文** ⇒ `crypto.subtle` **直接被禁**（`undefined`，调了就抛）。
 *    这不是「性能差一点」而是「这条路根本不存在」—— 与 §3.5 里 `navigator.clipboard` /
 *    Service Worker 那几条同一类失效；
 * 2. 退一步走纯 JS 的 AES：那要引一个第三方实现（几百行、要跟着上游的 CFB 反馈逐字节对得上
 *    `webvpn.xjtu.edu.cn` 的行为）—— 为一条**纯函数**的转换把一份密码学依赖塞进 wasm 产物里，
 *    换来的是「和内网同一个算法在两处各写一遍」；
 * 3. 而服务端（这台已经是 `:data` + JVM 的机器）**本来就有**这份实现：`:app` / 桌面注入的
 *    `WebVpnUtil` 与它逐字同一份 ⇒ 端出去的成本是零，风险也是零（**纯函数**：
 *    不收会话、不落盘、**不改任何状态**、不碰上游，见下）。
 *
 * `window.isSecureContext` 是那条判据的运行时投影：`true`（HTTPS 或 `127.0.0.1`）时浏览器端
 * **将来**可以自己转，但那是另一件事（要把算法搬进 wasm）；本契约这一轮只有服务端这一条路。
 *
 * ## 纯函数：没有会话，也没有个人数据
 *
 * 这条端点**不套 `session.authenticated`**（与 `/api/feedback*` 同一条理由：它不需要任何站点
 * 会话）—— 浏览器带上门牌（令牌闸门）就能用。也正因为它是纯函数：
 * - 发不出任何请求（既不碰 `webvpn.xjtu.edu.cn`，也不碰别的上游）⇒ 没有 `502` 那一档；
 * - 入参与出参都只是**网址**：转换器这一屏本来就是给用户自己转地址用的，不动任何人的数据。
 *
 * ## 请求与响应
 *
 * `POST /api/webvpn/convert`，body：`{"url":"<网址>","direction":"to_vpn"|"from_vpn"}`
 * - `to_vpn`：普通网址 → 网关地址（`WebVpnUtil.getVpnUrl`）。**已经是网关地址的输入原样返回**，
 *   认不出的输入（没有 `://`）也原样返回 —— 那是 `:data` 里那条老口径，本层不改；
 * - `from_vpn`：网关地址 → 原网址（`WebVpnUtil.getOriginalUrl`）。解不出来（不是网关地址、
 *   或者路径不成形）⇒ `400`，**不是** `data:null`：这不是「上游说没有」，是「你给的不是网关地址」。
 *
 * 响应 `data`：`{"url":"<转换后的网址>"}` + `direction`（原样回，免得消费方猜这次的语义）。
 *
 * 错误码：`400` `direction` 不认识 / `url` 空 / `from_vpn` 解不出来 · `401` 无令牌（闸门那一条）。
 */
internal fun Route.webVpnRoutes() {
    route(WEBVPN_SEGMENT) {

        post(WEBVPN_CONVERT) {
            val body = call.receiveOrNull<WebVpnConvertRequest>()
                ?: return@post call.respondBadRequestMessage(WEBVPN_BODY_MESSAGE)
            val url = body.url.trim()
            if (url.isEmpty()) return@post call.respondBadRequestMessage("url 必填（要转换的那个网址）")
            when (val direction = body.direction.trim().lowercase()) {
                WEBVPN_TO_VPN -> call.respond(
                    ApiEnvelope.ok(WebVpnConvertData(direction = direction, url = WebVpnUtil.getVpnUrl(url))),
                )

                WEBVPN_FROM_VPN -> {
                    val original = WebVpnUtil.getOriginalUrl(url)
                        ?: return@post call.respondBadRequestMessage(WEBVPN_NOT_A_GATEWAY_URL_MESSAGE)
                    call.respond(
                        ApiEnvelope.ok(WebVpnConvertData(direction = direction, url = original)),
                    )
                }

                else -> call.respondBadRequestMessage(
                    "direction 只认 $WEBVPN_TO_VPN（普通网址 → 网关地址）或 $WEBVPN_FROM_VPN（网关地址 → 原网址）",
                )
            }
        }
    }
}

// ── 数据模型（客户端形状）──────────────────────────────────────────

/** `POST /api/webvpn/convert` 的请求体。 */
@Serializable
internal data class WebVpnConvertRequest(
    /** 要转换的网址（两侧都收；`to_vpn` 收普通网址、`from_vpn` 收网关地址）。 */
    val url: String = "",
    /** [WEBVPN_TO_VPN] 或 [WEBVPN_FROM_VPN]。 */
    val direction: String = "",
)

/** `POST /api/webvpn/convert` 的 `data`。 */
@Serializable
internal data class WebVpnConvertData(
    /** 原样回请求里那个方向（小写化之后的值）。 */
    val direction: String,
    val url: String,
)

// ── 常量 ─────────────────────────────────────────────────────────────

internal const val WEBVPN_SEGMENT = "webvpn"
internal const val WEBVPN_CONVERT = "convert"

/** 普通网址 → 网关地址。 */
internal const val WEBVPN_TO_VPN = "to_vpn"

/** 网关地址 → 原网址。 */
internal const val WEBVPN_FROM_VPN = "from_vpn"

internal const val WEBVPN_BODY_MESSAGE =
    "请求体需要 JSON：{\"url\":\"https://jwxt.xjtu.edu.cn/\",\"direction\":\"$WEBVPN_TO_VPN\"}"

private const val WEBVPN_NOT_A_GATEWAY_URL_MESSAGE =
    "url 不是 WebVPN 网关地址：形如 https://webvpn.xjtu.edu.cn/<协议>[-端口]/<32 位 IV><密文域名>/<路径>"
