package com.xjtu.toolbox.core.net

import com.xjtu.toolbox.util.safeString
import com.xjtu.toolbox.webvpn.WebVpnAsyncCodec
import io.ktor.client.HttpClient
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** WebVPN 网址互转的 serve 版实现：给 `:web` 用，实现 [WebVpnAsyncCodec]。 */
class CampusWebVpnApi(
    private val client: HttpClient,
    private val baseUrl: String = "",
    /** 见 [ApiMode]：这一份实现只认 serve 契约（§5.7）。 */
    private val mode: ApiMode = ApiMode.CAMPUS_API,
) : WebVpnAsyncCodec {

    override suspend fun toVpnUrl(url: String): String {
        requireServe()
        val outcome = convert(url, DIRECTION_TO_VPN)
        if (!outcome.ok) error("WebVPN 转换：${outcome.message}")
        return outcome.readUrl("to_vpn")
    }

    override suspend fun fromVpnUrl(vpnUrl: String): String? {
        requireServe()
        val outcome = convert(vpnUrl, DIRECTION_FROM_VPN)
        if (!outcome.ok) {
            if (outcome.code == HTTP_BAD_REQUEST) return null
            error("WebVPN 转换：${outcome.message}")
        }
        return outcome.readUrl("from_vpn")
    }

    /** 一发转换。 */
    private suspend fun convert(url: String, direction: String): ServeActionOutcome = client.serveAction(
        baseUrl,
        CONVERT_PATH,
        buildJsonObject {
            put("url", url)
            put("direction", direction)
        }.toString(),
    )

    /** 读成功响应里的那一格 `url`。 */
    private fun ServeActionOutcome.readUrl(direction: String): String {
        val converted = data?.get("url").safeString().orEmpty()
        if (converted.isBlank()) {
            error(
                "WebVPN 转换：服务端返回的 url 是空的（契约 §5.7 的 data.url —— $direction 这一向" +
                    "只该给一个地址，给不出就如实报错）",
            )
        }
        return converted
    }

    private fun requireServe() {
        if (mode != ApiMode.SERVE) serveOnly("WebVPN 网址转换", CONVERT_PATH)
    }
}

/** 网址互转（纯函数，两个方向共用一个端点）。 */
private const val CONVERT_PATH = "/api/webvpn/convert"

/** 普通网址 → 网关地址。 */
private const val DIRECTION_TO_VPN = "to_vpn"

/** 网关地址 → 原网址。 */
private const val DIRECTION_FROM_VPN = "from_vpn"

/** `400`：`from_vpn` 这一向的「你给的不是网关地址」（契约 §5.7）。 */
private const val HTTP_BAD_REQUEST = 400
