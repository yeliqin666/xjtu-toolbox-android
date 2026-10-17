package com.xjtu.toolbox.browser

import com.xjtu.toolbox.webvpn.WebVpnCodec
import kotlin.test.Test
import kotlin.test.assertEquals

/** 「对外用的地址」那条规则的判据（`:core` 的 [shareableUrl]）。 */
class ShareableUrlTest {

    private val gateway = "https://webvpn.xjtu.edu.cn/https/77726e6f746963652e6578616d706c65/" +
        "d3JkdnBuaXN0aGViZXN0/notice"

    /** 只会把 [originals] 里认得的那一个换回去，别的都算「解不出来」。 */
    private class FixtureCodec(private val originals: Map<String, String?>) : WebVpnCodec {
        override fun toVpnUrl(url: String): String = url
        override fun fromVpnUrl(vpnUrl: String): String? = originals[vpnUrl]
    }

    @Test
    fun `网关地址换回原始地址`() {
        val codec = FixtureCodec(mapOf(gateway to "https://notice.example.invalid/notice"))
        assertEquals("https://notice.example.invalid/notice", shareableUrl(gateway, codec))
    }

    @Test
    fun `不是网关地址就原样还回去`() {
        val codec = FixtureCodec(mapOf(gateway to "https://notice.example.invalid/notice"))
        val plain = "https://notice.example.invalid/a?b=c#d"
        assertEquals(plain, shareableUrl(plain, codec))
    }

    @Test
    fun `解不出原地址时原样还回去，不交空串出去`() {
        // codec 认得这条网关地址，但给出的原地址是空串 —— 交出去等于「复制了一个空链接」
        val codec = FixtureCodec(mapOf(gateway to "   "))
        assertEquals(gateway, shareableUrl(gateway, codec))
    }

    @Test
    fun `空串也原样还回去`() {
        // `AppRoute.Browser` 的 url 有默认值 ""、`appRouteOf("browser")` 也会给出空串
        assertEquals("", shareableUrl("", FixtureCodec(emptyMap())))
    }

    @Test
    fun `不改用户那个字符串本身（前后空格与大小写都不动）`() {
        val codec = FixtureCodec(emptyMap())
        val raw = " HTTPS://Notice.Example.Invalid/A "
        assertEquals(raw, shareableUrl(raw, codec))
    }
}
