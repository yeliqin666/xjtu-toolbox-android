package com.xjtu.toolbox.desktop

import com.xjtu.toolbox.webvpn.WebVpnCodec
import com.xjtu.toolbox.webvpn.WebVpnUrl
import com.xjtu.toolbox.webvpn.WebVpnUtil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** WebVPN 转换器（`AppRoute.WebVpnConverter`）在桌面端的装配验收。 */
class DesktopWebVpnCodecJvmTest {

    /** 屏认的那条缝，桌面注入的就是它 —— 顺带证明 `:data` 的对象确实实现了 `:core` 的接口。 */
    private val codec: WebVpnCodec = WebVpnUtil

    @Test
    fun `正向：教务首页转成网关地址，密文域名那一段是定值`() {
        val vpn = codec.toVpnUrl("https://jwxt.xjtu.edu.cn/")

        // 形状：https://webvpn.xjtu.edu.cn/<protocol>/<iv_hex(32)><encrypted_domain_hex>/<rest>
        val prefix = "https://webvpn.xjtu.edu.cn/https/77726476706e69737468656265737421"
        assertTrue(vpn.startsWith(prefix), "网关地址前缀不对：$vpn")
        assertEquals(
            "$prefix" + "fae05988692862446b468ca88d1b203b" + "/",
            vpn,
        )
    }

    @Test
    fun `正向：带端口、路径、查询、片段都搬到网关地址上`() {
        val vpn = codec.toVpnUrl("http://rg.lib.xjtu.edu.cn:8086/seat/?a=1#top")
        assertTrue(vpn.startsWith("https://webvpn.xjtu.edu.cn/http-8086/"), "端口要编进那一段：$vpn")
        assertTrue(vpn.endsWith("/seat/?a=1#top"), "路径/查询/片段要原样接在后面：$vpn")
    }

    @Test
    fun `反向：网关地址解回原网址，host 与端口一个不丢`() {
        val originals = listOf(
            "https://jwxt.xjtu.edu.cn/",
            "https://www.lib.xjtu.edu.cn/",
            "http://rg.lib.xjtu.edu.cn:8086/seat/",
            "https://kq.xjtu.edu.cn/student-pc",
            "https://kq.xjtu.edu.cn/student-pc?date=2026-10-08#top",
        )
        originals.forEach { original ->
            val vpn = codec.toVpnUrl(original)
            assertEquals(
                original.trimEnd('/'),
                codec.fromVpnUrl(vpn)?.trimEnd('/'),
                "来回一趟应当回到原网址：$original",
            )
        }
    }

    @Test
    fun `已经是网关地址的输入原样返回；解析不了的返回 null`() {
        val already = codec.toVpnUrl("https://jwxt.xjtu.edu.cn/")
        assertEquals(already, codec.toVpnUrl(already))

        assertNull(codec.fromVpnUrl("https://jwxt.xjtu.edu.cn/"))
        // 是网关域名但路径不成形 ⇒ 也是 null（不抛）
        assertNull(codec.fromVpnUrl("https://webvpn.xjtu.edu.cn/"))
    }

    @Test
    fun `判据那一半在 core：网关域名认得出来，别的域名不认`() {
        // `:core` 的 `WebVpnUrl`（三端共用的纯判据）与上面那份实现必须指向同一个域名
        assertTrue(WebVpnUrl.isWebVpnUrl(codec.toVpnUrl("https://jwxt.xjtu.edu.cn/")))
        assertTrue(!WebVpnUrl.isWebVpnUrl("https://jwxt.xjtu.edu.cn/"))
    }
}
