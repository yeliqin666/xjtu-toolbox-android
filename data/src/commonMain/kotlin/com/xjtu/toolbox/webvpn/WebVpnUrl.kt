package com.xjtu.toolbox.webvpn

/** WebVPN 地址的纯判据（代理包裹后的 URL 长什么样）。 */
object WebVpnUrl {

    private const val INSTITUTION = "webvpn.xjtu.edu.cn"

    /** 这个地址是不是 `webvpn.xjtu.edu.cn` 上的（即经网关转发过的）。 */
    fun isWebVpnUrl(url: String): Boolean =
        url.startsWith("https://$INSTITUTION") || url.startsWith("http://$INSTITUTION")
}
