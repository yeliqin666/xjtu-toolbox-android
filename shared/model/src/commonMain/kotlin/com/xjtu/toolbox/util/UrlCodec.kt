package com.xjtu.toolbox.util

// 不转义的字符集：`java.net.URLEncoder` 的原样（`*` 与 `.` 不转义、`~` **会**被转义）
private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.*"

private const val HEX_UPPER = "0123456789ABCDEF"

private fun hexValue(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> -1
}

/** `java.net.URLEncoder.encode(value, "UTF-8")` 的等价物。 */
fun encodeUrlComponent(value: String): String {
    val out = StringBuilder(value.length + 8)
    for (byte in value.encodeToByteArray()) {
        val int = byte.toInt() and 0xFF
        val char = int.toChar()
        when {
            char in UNRESERVED -> out.append(char)
            char == ' ' -> out.append('+')
            else -> {
                out.append('%')
                out.append(HEX_UPPER[int shr 4])
                out.append(HEX_UPPER[int and 0x0F])
            }
        }
    }
    return out.toString()
}

/** `java.net.URLDecoder.decode(value, "UTF-8")` 的等价物，解码失败返回 null。 */
fun decodeUrlComponentOrNull(value: String): String? {
    val out = StringBuilder(value.length)
    var i = 0
    while (i < value.length) {
        val char = value[i]
        when {
            char == '+' -> {
                out.append(' ')
                i++
            }
            char == '%' -> {
                val bytes = ArrayList<Byte>(8)
                while (value.getOrNull(i) == '%') {
                    val hi = hexValue(value.getOrNull(i + 1) ?: return null)
                    val lo = hexValue(value.getOrNull(i + 2) ?: return null)
                    if (hi < 0 || lo < 0) return null
                    bytes.add(((hi shl 4) or lo).toByte())
                    i += 3
                }
                out.append(bytes.toByteArray().decodeToString())
            }
            else -> {
                out.append(char)
                i++
            }
        }
    }
    return out.toString()
}
