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

/**
 * `java.net.URLEncoder.encode(value, "UTF-8")` 的等价物。
 *
 * 规则照 `java.net` 抄（不是按 RFC 3986 重新设计），因为 `nav/AppRoute` 的 id
 * （`browser?url=…&then=…`、`jiaocai1_reader/<ssno>?title=…`）**已经写进用户数据**
 * （服务表、深链、快捷方式、小组件、通知）—— 搬进 commonMain 之后必须吐出**逐字节相同**
 * 的字符串，否则老 id 会解析不出来：
 *
 * - 不转义：`A-Z a-z 0-9 - _ . *`；
 * - 空格 → `+`（不是 `%20`）；
 * - 其余每个 **UTF-8 字节** → `%XX`，十六进制**大写**。
 *
 * :app 侧有一个差分测试把这里与 `java.net` 逐串对比（`nav/UrlCodecParityTest.kt`）。
 */
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

/**
 * `java.net.URLDecoder.decode(value, "UTF-8")` 的等价物，解码失败返回 null。
 *
 * 行为照 JDK 实现一步步对齐（这是差分测试逼出来的）：
 * - `+` → 空格；
 * - 连续的 `%XX` 归成一组字节、按 UTF-8 解释后接上去；
 * - **其余字符原样接上去**（不重新做 UTF-8 编码）—— 这一点很反直觉：
 *   `decode("中")` 在 java.net 下就是 `"中"`（没触发转义路径，字符串原样返回），
 *   `decode("中%E4%B8%AD")` 是 `"中中"`（字面字符 + 一组字节）。
 * - `%` 后面凑不够两位十六进制（含末尾落单的 `%`）→ 返回 null（java.net 抛 IAE）。
 *
 * 调用方（`AppRoute.appRouteOf`）照着搬迁前的 `runCatching { … }.getOrDefault(raw)` 语义，
 * 失败时拿原串兜底。
 */
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
