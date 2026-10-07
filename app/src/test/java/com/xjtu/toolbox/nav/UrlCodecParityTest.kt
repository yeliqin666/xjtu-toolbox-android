package com.xjtu.toolbox.nav

import com.xjtu.toolbox.util.decodeUrlComponentOrNull
import com.xjtu.toolbox.util.encodeUrlComponent
import java.net.URLDecoder
import java.net.URLEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * **差分测试**：:core 里手写的 [encodeUrlComponent] / [decodeUrlComponentOrNull] 与
 * `java.net.URLEncoder/URLDecoder`（= 搬迁前 AppRoute 用的实现）逐字节对比。
 *
 * 为什么必须有它：`AppRoute` 的 id 已经写进用户数据（服务表、深链、快捷方式、小组件、通知），
 * 搬进 commonMain 后编码结果只要差一个字符（比如把空格编成 `%20` 而不是 `+`，或把 `~` 也放行），
 * 老用户存下的 id 就解析不出来。这种「换个实现、字符串变样」的错在普通单测里看不出来，
 * 只有跟平台自己的实现对着跑才知道。
 *
 * 放在 :app（而不是 :core）：`java.net` 只在 JVM/Android 上存在 —— 这一条**只能**在 Android 侧跑，
 * 而 :core 的 commonTest 要能编到 Wasm。:core 侧另有平台无关的往返测试。
 */
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
            // java.net 对非法转义抛 IllegalArgumentException；我们返回 null，
            // 调用方（appRouteOf）照着搬迁前的 runCatching{}.getOrDefault(raw) 拿原串兜底。
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
