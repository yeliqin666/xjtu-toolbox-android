package com.xjtu.toolbox.platform

import java.util.Base64

/** Android 侧：`java.util.Base64` 的 URL 编码器 / 解码器。 */
actual fun base64UrlEncode(bytes: ByteArray): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

actual fun base64UrlDecode(text: String): ByteArray? =
    runCatching { Base64.getUrlDecoder().decode(text) }.getOrNull()

actual val base64Available: Boolean = true
