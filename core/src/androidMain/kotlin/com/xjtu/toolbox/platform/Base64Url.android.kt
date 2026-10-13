package com.xjtu.toolbox.platform

import java.util.Base64

/**
 * Android 侧：`java.util.Base64` 的 URL 编码器 / 解码器 —— 与搬迁前 `:app` 的
 * `MatchProfile.encode` / `decode` 逐字相同（`getUrlEncoder().withoutPadding()`）；
 * 解码那一步原先被外面那层 `try/catch` 兜住，现在由这里返回 null（见 [base64UrlDecode]）。
 */
actual fun base64UrlEncode(bytes: ByteArray): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

actual fun base64UrlDecode(text: String): ByteArray? =
    runCatching { Base64.getUrlDecoder().decode(text) }.getOrNull()

actual val base64Available: Boolean = true
