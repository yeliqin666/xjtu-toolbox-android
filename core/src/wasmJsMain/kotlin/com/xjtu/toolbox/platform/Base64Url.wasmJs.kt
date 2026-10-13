package com.xjtu.toolbox.platform

/**
 * Web（wasmJs）侧：**没有** —— 编码给空串、解码给 null（见 commonMain 的 KDoc：
 * 单有 Base64 没有 deflate 一样出不了码，所以两件一起做，不先补一份没人跑得到的实现）。
 */
actual fun base64UrlEncode(bytes: ByteArray): String = ""

actual fun base64UrlDecode(text: String): ByteArray? = null

actual val base64Available: Boolean = false
