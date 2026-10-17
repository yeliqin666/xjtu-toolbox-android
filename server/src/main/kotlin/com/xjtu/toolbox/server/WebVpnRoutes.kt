package com.xjtu.toolbox.server

import com.xjtu.toolbox.webvpn.WebVpnUtil
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/** `/api/webvpn/convert` —— WebVPN 网址互转（`docs/api-contract.md` §5.7）的一个纯转换端点。 */
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
