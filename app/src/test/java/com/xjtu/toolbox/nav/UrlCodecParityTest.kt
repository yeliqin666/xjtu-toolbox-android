package com.xjtu.toolbox.nav

import com.xjtu.toolbox.util.decodeUrlComponentOrNull
import com.xjtu.toolbox.util.encodeUrlComponent
import java.net.URLDecoder
import java.net.URLEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 差分测试：[encodeUrlComponent] / [decodeUrlComponentOrNull] 与 `java.net.URLEncoder/URLDecoder` 逐字节对比。 */
class UrlCodecParityTest {

    private val samples = listOf(
        "", "plain", "a b", "a+b", "a/b?c=d&e=f#g", "~tilde", "*star", ".dot", "-dash", "_under",
        "https://example.com/a?b=1&c=中文#frag", "高等数学（第七版）上册", "emoji 😀 混合",
        "%20", "%2F", "%E4%B8%AD", "a%2Fb", "空格 与 + 号", "\\backslash\"quote'",
        "\u0000\u001F", "ünïcödé", "日本語", "１全角２", "line\nbreak\ttab", "  ", "++", "%%",
        "%", "%z", "%zz", "%2", "100%", "a%", "%E4%B8",
    )

    @Test
    fun `编码与 java_net 逐字节一致`() {
        for (s in samples) {
            assertEquals("encode($s)", URLEncoder.encode(s, "UTF-8"), encodeUrlComponent(s))
        }
    }

    @Test
    fun `解码与 java_net 一致，连它抛异常的那些也一致`() {
        for (s in samples) {
            // java.net 对非法转义抛异常；我们返回 null，由调用方拿原串兜底。
            val expected = try {
                URLDecoder.decode(s, "UTF-8")
            } catch (_: IllegalArgumentException) {
                null
            }
            assertEquals("decode($s)", expected, decodeUrlComponentOrNull(s))
        }
    }

    @Test
    fun `非法转义确实返回 null 而不是抛`() {
        for (bad in listOf("%", "%z", "%zz", "%2", "100%", "a%")) {
            assertNull("decode($bad)", decodeUrlComponentOrNull(bad))
        }
    }

    @Test
    fun `往返无损（含非 ASCII 与 emoji）`() {
        for (s in samples) {
            assertEquals("roundtrip($s)", s, decodeUrlComponentOrNull(encodeUrlComponent(s)))
        }
    }
}
