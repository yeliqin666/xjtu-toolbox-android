package com.xjtu.toolbox.platform

import java.util.Base64

/**
 * jvm（桌面 / serve 模式）侧：**同一份 `java.util.Base64`**，与 androidMain 那份逐字相同
 * —— 于是同一条分享码在 Android 与桌面上编出来、解得开都是同一个结果。
 */
actual fun base64UrlEncode(bytes: ByteArray): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

actual fun base64UrlDecode(text: String): ByteArray? =
    runCatching { Base64.getUrlDecoder().decode(text) }.getOrNull()

actual val base64Available: Boolean = true
