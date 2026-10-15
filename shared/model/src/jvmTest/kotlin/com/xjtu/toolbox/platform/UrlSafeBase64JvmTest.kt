package com.xjtu.toolbox.platform

import java.util.Base64
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 纯 Kotlin 那份 URL-safe Base64（[UrlSafeBase64]）与 `java.util.Base64` 的 URL 编解码器同结果。 */
class UrlSafeBase64JvmTest {

    @Test
    fun `编码与 java util Base64 的 URL 编码器逐字节相同`() {
        val random = Random(20261015)
        val samples = buildList {
            add(ByteArray(0))
            for (size in 1..40) add(ByteArray(size) { it.toByte() })
            add(ByteArray(300) { (it % 256).toByte() })
            add(ByteArray(1024) { random.nextInt(256).toByte() })
            // URL-safe 字母表里才有 - 与 _ 的那几种字节
            add(byteArrayOf(0xFB.toByte(), 0xFF.toByte()))
            add(byteArrayOf(0xFB.toByte(), 0xFF.toByte(), 0xBF.toByte()))
        }
        for (bytes in samples) {
            assertEquals(
                Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
                UrlSafeBase64.encode(bytes),
                "编码不一致（${bytes.size} 字节）",
            )
        }
        assertEquals("-_8", UrlSafeBase64.encode(byteArrayOf(0xFB.toByte(), 0xFF.toByte())))
        assertTrue(UrlSafeBase64.encode(ByteArray(64)).none { it == '=' }, "编码不该留填充")
    }

    @Test
    fun `解码：合法输入与 java util Base64 同结果`() {
        val random = Random(20261015)
        val samples = buildList {
            add(ByteArray(0))
            for (size in 1..40) add(ByteArray(size) { (it * 7).toByte() })
            add(ByteArray(512) { random.nextInt(256).toByte() })
        }
        for (bytes in samples) {
            val unpadded = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            val padded = Base64.getUrlEncoder().encodeToString(bytes)
            assertContentEquals(bytes, UrlSafeBase64.decode(unpadded), "无填充那条解不回来")
            assertContentEquals(bytes, UrlSafeBase64.decode(padded), "带填充那条解不回来")
        }
    }

    @Test
    fun `解码的宽松与严格边界与 java util Base64 一致`() {
        // 每条都对 JDK 实测过一次：带填充 / 不带填充 / 长度 % 4 == 1 / 末端多余的位 /
        // 中间出现填充 / 字母表外的字符。
        val tricky = listOf(
            "", "QQ", "QQ==", "QQ=", "Q", "QUI=", "QUJ", "QUJ=",
            "-_8", "a-b_", "AA", "AAA", "AAAA", "AAA=", "AA==",
            "QR", "QR==", "AB==CD", "Q===", "====", "a b", "aa+aa", "aa/aa", "AA=A",
        )
        for (text in tricky) {
            val byJdk = runCatching { Base64.getUrlDecoder().decode(text) }.getOrNull()
            val byPure = UrlSafeBase64.decode(text)
            if (byJdk == null) {
                assertTrue(byPure == null, "JDK 拒了「$text」，这一份也该拒")
            } else {
                assertContentEquals(byJdk, byPure, "「$text」两边解出来的不一样")
            }
        }
    }
}
