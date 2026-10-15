package com.xjtu.toolbox.platform

import android.provider.Settings
import java.security.MessageDigest

actual fun deviceInstallId(salt: String): String? {
    val context = androidPlatformContext() ?: return null
    val androidId = runCatching {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    }.getOrNull()?.trim().orEmpty()
    if (androidId.isEmpty() || androidId.equals(BUG_ANDROID_ID, ignoreCase = true)) return null
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest("$salt|$androidId".toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(8)
}

/** 旧模拟器上 ANDROID_ID 会固定成这个值，当无效。 */
private const val BUG_ANDROID_ID = "9774d56d682e549c"
