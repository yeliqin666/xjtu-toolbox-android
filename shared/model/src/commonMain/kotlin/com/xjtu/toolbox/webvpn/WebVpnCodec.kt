package com.xjtu.toolbox.webvpn

/** WebVPN 网址互转的平台缝。 */
interface WebVpnCodec {

    /** 普通网址 → WebVPN 代理网址；已经是 WebVPN 网址时原样返回。 */
    fun toVpnUrl(url: String): String

    /** WebVPN 代理网址 → 原始网址；解析不出来返回 null。 */
    fun fromVpnUrl(vpnUrl: String): String?
}

/** 同上那条缝的异步形态：转换本体不在本端（serve 形态下它在服务端，一次 HTTP 往返）。 */
interface WebVpnAsyncCodec {

    /** 普通网址 → WebVPN 代理网址；已经是 WebVPN 网址时原样返回。 */
    suspend fun toVpnUrl(url: String): String

    /** WebVPN 代理网址 → 原始网址；解析不出来返回 null。 */
    suspend fun fromVpnUrl(vpnUrl: String): String?
}

/** 同步实现 → 异步缝（`WebVpnUtil` 是纯字符串运算，不阻塞）。 */
fun WebVpnCodec.asAsyncCodec(): WebVpnAsyncCodec = SyncWebVpnCodec(this)

private class SyncWebVpnCodec(private val codec: WebVpnCodec) : WebVpnAsyncCodec {
    override suspend fun toVpnUrl(url: String): String = codec.toVpnUrl(url)
    override suspend fun fromVpnUrl(vpnUrl: String): String? = codec.fromVpnUrl(vpnUrl)
}
