package com.xjtu.toolbox.webvpn

/**
 * WebVPN 地址的**纯判据**（代理包裹后的 URL 长什么样）。
 *
 * `:app` 的 `WebVpnUtil.isWebVpnUrl` 原样搬到这里（那边改成一行委托）。搬的原因：它被用来
 * 判断「这次请求走的是直连还是网关」——图书馆的错误提示里就要说清是哪一种
 * （`LibraryApi.pageClue`），而那份 `LibraryApi` 现在在 `:data`，不该反过来依赖 `:app` 的
 * WebVPN 实现（那个对象还挂着拦截器与 AES 加密，是 :app 侧的事）。
 *
 * ⚠️ 这里只有**判据**，没有改写（把普通 URL 变成网关地址那段加密逻辑仍在 `:app`）：
 * 加密是 Android 侧拦截器的职责，桌面端将来接 WebVPN 时会有自己那份。
 */
object WebVpnUrl {

    private const val INSTITUTION = "webvpn.xjtu.edu.cn"

    /** 这个地址是不是 `webvpn.xjtu.edu.cn` 上的（即经网关转发过的）。 */
    fun isWebVpnUrl(url: String): Boolean =
        url.startsWith("https://$INSTITUTION") || url.startsWith("http://$INSTITUTION")
}
