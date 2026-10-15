package com.xjtu.toolbox.platform

/** Web（wasmJs）侧：转调 `:core` commonMain 里那份纯 Kotlin 实现（[UrlSafeBase64]）。 */
actual fun base64UrlEncode(bytes: ByteArray): String = UrlSafeBase64.encode(bytes)

actual fun base64UrlDecode(text: String): ByteArray? = UrlSafeBase64.decode(text)

actual val base64Available: Boolean = true
