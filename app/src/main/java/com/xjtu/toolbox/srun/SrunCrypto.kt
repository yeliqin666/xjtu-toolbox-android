package com.xjtu.toolbox.srun

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Srun 校园网认证用到的加密原语。
 *
 * 三个核心：
 *   - [hmacMd5Hex]：HMAC-MD5(消息=明文密码, 密钥=challenge)，hex 输出。
 *   - [sha1Hex]：SHA1，hex 输出。
 *   - [srunBx1Encode]：Srun 自定义算法，先用 challenge 做异或编码，再用自定义 base64 字母表编码。
 *
 * srun_bx1 算法对照参考 srun-portal 公开实现（all.min.js 中的 force/encode 函数）。
 */
object SrunCrypto {

    /** Srun 自定义 base64 字母表（与标准 base64 字母表的字符顺序不同）。 */
    private const val SRUN_BX1_ALPHABET =
        "LVoJPiCN2R8G90yg+hmFHuacZ1OWMnrsSTXkYpUq/3dlbfKwv6xztjI7DeBE45QA"

    fun hmacMd5Hex(message: String, key: String): String {
        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacMD5"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8)).toHex()
    }

    fun sha1Hex(input: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        return md.digest(input.toByteArray(Charsets.UTF_8)).toHex()
    }

    /**
     * Srun bx1 编码：将 [str] 用 [key]（challenge）做异或式打包后用自定义 base64 输出。
     *
     * 算法 = `xEncode(str, key)` → `base64(自定义字母表)`。
     */
    fun srunBx1Encode(str: String, key: String): String {
        if (str.isEmpty()) return ""
        val encoded = xEncode(str, key)
        return customBase64(encoded)
    }

    // ── xEncode（移植自 jquery.srun.portal.js 的 _force/_encode；使用 Int 以匹配 JS 32-bit 位运算语义） ──
    private fun xEncode(str: String, key: String): ByteArray {
        val v = strToInts(str, true)
        val k = strToInts(key, false)
        val keyInts = IntArray(4) { idx -> if (idx < k.size) k[idx] else 0 }

        val n = v.size - 1
        if (n < 1) return intsToBytes(v)
        var z = v[n]
        var y: Int
        val c = 0x9E3779B9.toInt()  // = -1640531527
        var m: Int
        var e: Int
        var p: Int
        var q = 6 + 52 / (n + 1)
        var d = 0
        while (q-- > 0) {
            d += c                       // Int 自动 32-bit 回绕，等价于 JS d=d+c|0
            e = (d ushr 2) and 3
            p = 0
            while (p < n) {
                y = v[p + 1]
                // 与 jquery.srun.portal.js 一致：m 分三段累加
                //   m  = (z>>>5)^(y<<2)
                //   m += ((y>>>3)^(z<<4)) ^ (d^y)
                //   m += k[(p&3)^e] ^ z
                m = ((z ushr 5) xor (y shl 2)) +
                    (((y ushr 3) xor (z shl 4)) xor (d xor y)) +
                    ((keyInts[(p and 3) xor e]) xor z)
                v[p] = v[p] + m
                z = v[p]
                p++
            }
            y = v[0]
            m = ((z ushr 5) xor (y shl 2)) +
                (((y ushr 3) xor (z shl 4)) xor (d xor y)) +
                ((keyInts[(p and 3) xor e]) xor z)
            v[n] = v[n] + m
            z = v[n]
        }
        return intsToBytes(v)
    }

    /** 字符串转 Int 数组（小端）。`appendLength=true` 时末尾追加原始字节长度。 */
    private fun strToInts(s: String, appendLength: Boolean): IntArray {
        val bytes = s.toByteArray(Charsets.UTF_8)
        val byteLen = bytes.size
        val intCount = (byteLen + 3) / 4
        val out = IntArray(if (appendLength) intCount + 1 else intCount.coerceAtLeast(1))
        for (i in bytes.indices) {
            val shift = (i and 3) * 8
            out[i ushr 2] = out[i ushr 2] or ((bytes[i].toInt() and 0xFF) shl shift)
        }
        if (appendLength) out[intCount] = byteLen
        return out
    }

    /** Int 数组转字节序列（小端）。 */
    private fun intsToBytes(ints: IntArray): ByteArray {
        val out = ByteArray(ints.size * 4)
        for (i in ints.indices) {
            val v = ints[i]
            out[i * 4] = (v and 0xFF).toByte()
            out[i * 4 + 1] = ((v ushr 8) and 0xFF).toByte()
            out[i * 4 + 2] = ((v ushr 16) and 0xFF).toByte()
            out[i * 4 + 3] = ((v ushr 24) and 0xFF).toByte()
        }
        return out
    }

    /** 使用 Srun 自定义 base64 字母表对字节数组编码（含 `=` padding）。 */
    private fun customBase64(input: ByteArray): String {
        if (input.isEmpty()) return ""
        val sb = StringBuilder()
        var i = 0
        val end = input.size - input.size % 3
        while (i < end) {
            val b = ((input[i].toInt() and 0xFF) shl 16) or
                ((input[i + 1].toInt() and 0xFF) shl 8) or
                (input[i + 2].toInt() and 0xFF)
            sb.append(SRUN_BX1_ALPHABET[(b ushr 18) and 0x3F])
            sb.append(SRUN_BX1_ALPHABET[(b ushr 12) and 0x3F])
            sb.append(SRUN_BX1_ALPHABET[(b ushr 6) and 0x3F])
            sb.append(SRUN_BX1_ALPHABET[b and 0x3F])
            i += 3
        }
        when (input.size - end) {
            1 -> {
                val b = (input[i].toInt() and 0xFF) shl 16
                sb.append(SRUN_BX1_ALPHABET[(b ushr 18) and 0x3F])
                sb.append(SRUN_BX1_ALPHABET[(b ushr 12) and 0x3F])
                sb.append("==")
            }
            2 -> {
                val b = ((input[i].toInt() and 0xFF) shl 16) or
                    ((input[i + 1].toInt() and 0xFF) shl 8)
                sb.append(SRUN_BX1_ALPHABET[(b ushr 18) and 0x3F])
                sb.append(SRUN_BX1_ALPHABET[(b ushr 12) and 0x3F])
                sb.append(SRUN_BX1_ALPHABET[(b ushr 6) and 0x3F])
                sb.append('=')
            }
        }
        return sb.toString()
    }

    private fun ByteArray.toHex(): String {
        val hexChars = "0123456789abcdef"
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            sb.append(hexChars[v ushr 4])
            sb.append(hexChars[v and 0x0F])
        }
        return sb.toString()
    }
}
