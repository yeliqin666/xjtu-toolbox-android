package com.xjtu.toolbox.jiaocai1

import com.xjtu.toolbox.webvpn.WebVpnUtil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 封面重定向的「基址 + 同主机复核」（`Jiaocai1PageLoader.fetchCover` 的第二跳起）。
 *
 * 两条都不是凭空的场景：
 * - **WEBVPN**：拦截器在发送前把原站地址改写成网关地址，网关回的 `Location` 是**相对网关**的
 *   路径 ⇒ 基址必须用 `response.request.url`。用原站地址当基址会拼出
 *   `https://jiaocai1.lib.xjtu.edu.cn/https/…`，拦截器再包一层，路径就错了（校外封面全坏）。
 * - **同主机**：`?url=` 是客户端带回来的，只要能 302 到别的域名，serve 就成了任意取数代理（#597）。
 */
class CoverRedirectJvmTest {

    private val cover = "${Jiaocai1Api.BASE}/cover/a.jpg"
    private val gateway = WebVpnUtil.getVpnUrl(cover)

    /** 网关地址去掉最后两段（`/cover/a.jpg`），剩下的就是我们期望的相对跳转前缀。 */
    private val gatewayPrefix = gateway.removePrefix(WebVpnUtil.GATEWAY_ORIGIN)
        .substringBeforeLast('/')
        .substringBeforeLast('/')

    @Test
    fun `直连：相对 Location 按当前地址解，同主机才放行`() {
        assertEquals("${Jiaocai1Api.BASE}/cover/b.jpg", resolveCoverRedirect(cover, "/cover/b.jpg"))
        // 绝对地址同一主机也放行（真站点上偶尔给全路径）
        assertEquals("${Jiaocai1Api.BASE}/cover/c.jpg", resolveCoverRedirect(cover, "${Jiaocai1Api.BASE}/cover/c.jpg"))
        // 跨主机 / 换协议 / 换端口一律不跟
        assertNull(resolveCoverRedirect(cover, "https://example.com/x.jpg"))
        assertNull(resolveCoverRedirect(cover, "http://169.254.169.254/latest/meta-data/"))
        assertNull(resolveCoverRedirect(cover, "ftp://jiaocai1.lib.xjtu.edu.cn/x.jpg"))
    }

    @Test
    fun `WEBVPN：相对 Location 按网关地址解，不能拼成原站下的网关路径`() {
        val hop = "$gatewayPrefix/cover/b.jpg"
        val next = resolveCoverRedirect(gateway, hop)
        assertNotNull(next, "网关里同主机的相对跳转要跟")
        assertEquals("${WebVpnUtil.GATEWAY_ORIGIN}$hop", next)
        assertFalse(next.contains("jiaocai1.lib.xjtu.edu.cn"), "解析结果里不该再出现原站域名：$next")
    }

    @Test
    fun `WEBVPN：跨主机与登录页不跟，同主机的原站绝对地址照跟`() {
        // 网关代理到别的域名 ⇒ 解回原域名后不是全文库，拒
        assertNull(resolveCoverRedirect(gateway, WebVpnUtil.getVpnUrl("https://example.com/a.jpg")))
        // 被网关打回登录前页 ⇒ 不跟（重认证由 SiteSession 那条路负责）
        assertNull(resolveCoverRedirect(gateway, "/login?cas_login=true"))
        // 上游给的是原站绝对地址 ⇒ 照跟，交给拦截器去包（不在这里预先包成网关形态）
        val absolute = "${Jiaocai1Api.BASE}/cover/d.jpg"
        assertEquals(absolute, resolveCoverRedirect(gateway, absolute))
    }

    @Test
    fun `解析不了的基址与非 http(s) 的 Location 都回 null，不抛`() {
        assertNull(resolveCoverRedirect("not a url", "/cover/b.jpg"))
        assertNull(resolveCoverRedirect(cover, "data:image/png;base64,AAAA"))
    }
}
