package com.xjtu.toolbox.platform

/**
 * **URL-safe Base64（无填充）** 的纯 Kotlin 实现 —— 三端同一份，浏览器端靠它把分享码那段字编出来 / 解回来。
 *
 * ## 为什么要有这一份
 *
 * `platform/Base64Url.kt` 那条缝的 androidMain / jvmMain 两份 actual 用的是 `java.util.Base64`
 * （`getUrlEncoder().withoutPadding()`），`:core` 的 commonMain 里没有它 ⇒ wasmJs 那一端原先
 * **如实返回空串 / null**（`base64Available = false`）。分享码是「zlib → URL-safe Base64」两步，
 * 少一步这条链就断，匹配交友在浏览器里整屏用不了。这一份就是补上浏览器那一端的实现。
 *
 * **它的行为与 `java.util.Base64` 的 URL 编解码器逐字对齐**（由 `:core:jvmTest` 的
 * `ShareCodeInteropJvmTest` 与 `UrlSafeBase64JvmTest` 两类用例钉住）：
 *
 * | | 这一份 | `java.util.Base64` |
 * |---|---|---|
 * | 字母表 | `A-Z a-z 0-9 - _` | 同（URL 编码器） |
 * | 填充 | **编码时不留** `=`；解码时接受末端的 `=`，但**要么没有、要么正好补满一组** | 同（实测 `QQ` / `QQ==` / `QUI=` 解得开，`QQ=` 与 `AAA=` 拒） |
 * | 长度 `% 4 == 1` | 非法（null） | 同（`Q` 抛 `IllegalArgumentException`） |
 * | 末端多余的位 | 忽略（`QR` → `0x41`） | 同（实测一致） |
 * | 字母表外的字符 / `=` 出现在中间 | 非法（null） | 同 |
 *
 * 解码这一侧的宽松与 JDK 一致是**有意的**：对方发来的码可能被聊天软件加过填充或被手工改过尾部，
 * 「解得开就解」比「挑剔形状」更接近 Android 的行为（`:app` 那边同样是 JDK 的解码器）。
 */
internal object UrlSafeBase64 {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /** 字母表反查表：`-1` = 不是字母表里的字符。 */
    private val DECODE_TABLE = IntArray(128) { -1 }.also { table ->
        for (i in ALPHABET.indices) table[ALPHABET[i].code] = i
    }

    /** URL-safe Base64、**不带** `=` 填充（分享码就长这样）。 */
    fun encode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i + 2 < bytes.size) {
            val chunk = ((bytes[i].toInt() and 0xFF) shl 16) or
                ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                (bytes[i + 2].toInt() and 0xFF)
            out.append(ALPHABET[(chunk ushr 18) and 0x3F])
            out.append(ALPHABET[(chunk ushr 12) and 0x3F])
            out.append(ALPHABET[(chunk ushr 6) and 0x3F])
            out.append(ALPHABET[chunk and 0x3F])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val chunk = (bytes[i].toInt() and 0xFF) shl 16
                out.append(ALPHABET[(chunk ushr 18) and 0x3F])
                out.append(ALPHABET[(chunk ushr 12) and 0x3F])
            }
            2 -> {
                val chunk = ((bytes[i].toInt() and 0xFF) shl 16) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
                out.append(ALPHABET[(chunk ushr 18) and 0x3F])
                out.append(ALPHABET[(chunk ushr 12) and 0x3F])
                out.append(ALPHABET[(chunk ushr 6) and 0x3F])
            }
        }
        return out.toString()
    }

    /**
     * 解码 URL-safe Base64。**接受带填充与不带填充两种**；不是合法 Base64 时返回 null
     * （与 `java.util.Base64` 抛 `IllegalArgumentException` 同一档，只是换成 null）。
     */
    fun decode(text: String): ByteArray? {
        // 末端最多两个 '='：先剥掉，再按「长度 % 4 不能是 1」判。
        var end = text.length
        var padding = 0
        while (end > 0 && text[end - 1] == '=') {
            end--
            padding++
            if (padding > 2) return null
        }
        val length = end
        if (length % 4 == 1) return null
        // 填充要么一个都没有，要么正好补满一组（JDK 的判据，实测：「QQ」与「QQ==」都行，
        // 「QQ=」不行、`AAA=` 行 —— 见 `UrlSafeBase64JvmTest` 的边界用例）。
        val requiredPadding = (4 - length % 4) % 4
        if (padding != 0 && padding != requiredPadding) return null

        val out = ByteArray(length / 4 * 3 + if (length % 4 == 0) 0 else (length % 4) - 1)
        var outPos = 0
        var i = 0
        // 整组（4 个字符 → 3 字节）
        while (i + 4 <= length) {
            var chunk = 0
            for (k in 0 until 4) {
                val v = value(text[i + k]) ?: return null
                chunk = (chunk shl 6) or v
            }
            out[outPos++] = ((chunk ushr 16) and 0xFF).toByte()
            out[outPos++] = ((chunk ushr 8) and 0xFF).toByte()
            out[outPos++] = (chunk and 0xFF).toByte()
            i += 4
        }
        // 尾巴（2 或 3 个字符 → 1 或 2 字节；末端多余的位忽略，与 JDK 一致）
        val rest = length - i
        if (rest == 2 || rest == 3) {
            var chunk = 0
            for (k in 0 until rest) {
                val v = value(text[i + k]) ?: return null
                chunk = (chunk shl 6) or v
            }
            if (rest == 2) {
                out[outPos++] = ((chunk ushr 4) and 0xFF).toByte()
            } else {
                out[outPos++] = ((chunk ushr 10) and 0xFF).toByte()
                out[outPos++] = ((chunk ushr 2) and 0xFF).toByte()
            }
        }
        return out
    }

    private fun value(c: Char): Int? {
        val code = c.code
        if (code >= DECODE_TABLE.size) return null
        val v = DECODE_TABLE[code]
        return if (v < 0) null else v
    }
}
