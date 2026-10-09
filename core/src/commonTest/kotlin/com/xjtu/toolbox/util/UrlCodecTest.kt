package com.xjtu.toolbox.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [encodeUrlComponent] / [decodeUrlComponentOrNull] 里**与平台无关**的那部分断言：
 * 编码规则的关键几条（空格 → `+`、`*`/`.`/`-`/`_` 不转义、`~` 转义、十六进制大写）、
 * 非 ASCII 按 UTF-8 逐字节转义、往返无损、非法转义返回 null。
 *
 * 与 `java.net` 的逐字节等价性由 :app 侧的差分测试盯着（`nav/UrlCodecParityTest.kt`，
 * 那一条只能在 JVM 上跑）—— 这里只保证「在 Wasm 上也说得通」。
 */
class UrlCodecTest {

    @Test
    fun `不转义的字符集与 java_net 一致`() {
        assertEquals("abcXYZ019-_.*", encodeUrlComponent("abcXYZ019-_.*"))
        // java.net 会把 ~ 转义（与 RFC 3986 不同），这里必须跟着它
        assertEquals("%7Etilde%7E", encodeUrlComponent("~tilde~"))
    }

    @Test
    fun `空格变加号，保留字符按大写十六进制转义`() {
        assertEquals("a+b", encodeUrlComponent("a b"))
        assertEquals("a%2Fb%3Fc%3Dd%26e%3Df%23g", encodeUrlComponent("a/b?c=d&e=f#g"))
        assertEquals("%2B", encodeUrlComponent("+"))
    }

    @Test
    fun `非 ASCII 按 UTF-8 逐字节转义`() {
        assertEquals("%E4%B8%AD", encodeUrlComponent("中"))
        assertEquals("%E9%AB%98%E7%AD%89%E6%95%B0%E5%AD%A6", encodeUrlComponent("高等数学"))
    }

    @Test
    fun `解码认得加号与百分号转义`() {
        assertEquals("a b", decodeUrlComponentOrNull("a+b"))
        assertEquals("中", decodeUrlComponentOrNull("%E4%B8%AD"))
        assertEquals("a/b?c=d", decodeUrlComponentOrNull("a%2Fb%3Fc%3Dd"))
        assertEquals("100%", decodeUrlComponentOrNull("100%25"))
    }

    @Test
    fun `非法转义返回 null 而不是抛`() {
        assertNull(decodeUrlComponentOrNull("%"))
        assertNull(decodeUrlComponentOrNull("%2"))
        assertNull(decodeUrlComponentOrNull("%zz"))
        assertNull(decodeUrlComponentOrNull("a%"))
    }

    @Test
    fun `往返无损`() {
        val samples = listOf(
            "", "plain", "a b", "a+b", "a/b?c=d&e=f#g", "~*.-_",
            "https://example.com/a?b=1&c=中文#frag", "高等数学（第七版）上册", "emoji 😀 混合",
            "line\nbreak\ttab", "  ", "%20", "100%",
        )
        for (s in samples) assertEquals(s, decodeUrlComponentOrNull(encodeUrlComponent(s)))
    }
}
