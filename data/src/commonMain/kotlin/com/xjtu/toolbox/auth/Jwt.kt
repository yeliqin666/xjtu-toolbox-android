package com.xjtu.toolbox.auth

import com.xjtu.toolbox.util.safeParseJsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Base64

/**
 * 只解 JWT 的 exp、不验签：用来跳过必败的请求，真失效仍由服务器判。
 *
 * `internal` 改 `public`：`:app` 的 `CampusProbe`（网络判定，属宿主能力，没跟着内核搬）
 * 也要用它跳过一次注定失败的令牌检查。它本身是纯函数、不含任何秘密。
 */
object Jwt {
    /** 到期时刻（毫秒）；不是 JWT 或没有 exp 返回 null。 */
    fun expiresAtMs(token: String): Long? {
        val parts = token.split('.')
        if (parts.size != 3) return null
        val payload = parts[1]
        return runCatching {
            val json = String(Base64.getUrlDecoder().decode(payload.padEnd((payload.length + 3) / 4 * 4, '=')))
            (json.safeParseJsonObject()["exp"] as? JsonPrimitive)?.content?.toLongOrNull()?.times(1000)
        }.getOrNull()
    }

    /** 已过期（留 1 分钟余量）；解不出来的不算过期。 */
    fun isExpired(token: String, now: Long): Boolean =
        expiresAtMs(token)?.let { it - 60_000 < now } ?: false
}
