package com.xjtu.toolbox.social

import com.xjtu.toolbox.platform.base64UrlEncode
import com.xjtu.toolbox.platform.deflateBytes
import com.xjtu.toolbox.platform.inflateBytes
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 分享码本体在 jvm 上的三条钉子。 */
class MatchShareCodeJvmTest {

    /** `java.util.zip` 的 deflate，当基准。 */
    private fun jdkDeflate(raw: String): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(raw.toByteArray(Charsets.UTF_8))
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        return out.toByteArray()
    }

    /** `java.util.zip` 的 inflate，当基准。 */
    private fun jdkInflate(bytes: ByteArray, maxInflated: Int = 256 * 1024): String {
        val inflater = Inflater()
        inflater.setInput(bytes)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        try {
            while (!inflater.finished() && out.size() <= maxInflated) {
                val n = inflater.inflate(buf)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buf, 0, n)
            }
        } finally {
            inflater.end()
        }
        return out.toString(Charsets.UTF_8.name())
    }

    @Test
    fun `deflate 与搬迁前的 java util zip 逐字节相同`() {
        // 空串、单字节、一张 77 位网格、以及「课名很长很多」那种真会超过任何固定缓冲区的输入。
        val samples = listOf(
            "",
            "a",
            "5|阿离|" + "0".repeat(77) + "|CS001~高等数学",
            (1..600).joinToString(",") { "课程名称名称名称$it~老师姓名" },
        )
        for (raw in samples) {
            assertContentEquals(
                jdkDeflate(raw),
                deflateBytes(raw.encodeToByteArray()),
                "与 java.util.zip 的结果不一致：${raw.take(24)}…",
            )
        }
    }

    @Test
    fun `inflate 与搬迁前的 java util zip 相同，而且解不开就返回 null 而不是抛异常`() {
        val raw = "5|阿离|" + "10".repeat(38) + "|CS001~高等数学"
        assertEquals(raw, inflateBytes(jdkDeflate(raw), 256 * 1024)?.decodeToString())
        assertNull(inflateBytes(byteArrayOf(1, 2, 3, 4), 1024))
    }

    @Test
    fun `Base64 与搬迁前的 java util Base64 相同（URL-safe 无填充）`() {
        for (bytes in listOf(byteArrayOf(), byteArrayOf(0), ByteArray(300) { (it % 256).toByte() })) {
            assertEquals(
                Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
                base64UrlEncode(bytes),
            )
        }
        // URL-safe 字母表里才有 - 与 _：正是那段会把 + / 换掉的字节。
        assertEquals("-_8", base64UrlEncode(byteArrayOf(0xFB.toByte(), 0xFF.toByte())))
    }

    @Test
    fun `分享码仍是 版本号 + 二十个字段，而且跨实现解得回来`() {
        val nickname = "阿离"
        val grid = "1" + "0".repeat(MatchProfile.DAYS * MatchProfile.SECTIONS - 1)
        val mine = MatchProfile.Profile(nickname = nickname, busyGrid = grid)

        val code = MatchProfile.encode(mine)
        assertTrue(code.isNotEmpty(), "jvm 上有 deflate 与 Base64，不该是空串")
        assertTrue(
            code.none { it == '=' || it == '+' || it == '/' },
            "分享码是 URL-safe、无填充的 Base64：$code",
        )

        // 用 java 那一份实现解回来（不碰 `:core` 的解码器），看线上格式有没有变。
        val raw = jdkInflate(Base64.getUrlDecoder().decode(code))
        val parts = raw.split("|")
        assertEquals(20, parts.size, "字段数变了：$raw")
        assertEquals("5", parts[0], "版本号变了")
        assertEquals(nickname, parts[1])
        assertEquals(grid, parts[2])

        // 反方向：java 那份实现编出来的码，`:core` 解得回同一个档案。
        val fromJava = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(jdkDeflate(raw))
        assertEquals(mine, MatchProfile.decode(fromJava))
    }
}
