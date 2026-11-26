package com.xjtu.toolbox.auth

import com.xjtu.toolbox.util.safeParseJsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Base64

/** 只解 JWT 的载荷、不验签：用来跳过必败的请求或补请求头，真失效仍由服务器判。 */
object Jwt {
    /** 载荷里的一个字段；不是 JWT 或没有这个字段返回 null。 */
    fun claim(token: String, name: String): String? {
        val parts = token.split('.')
        if (parts.size != 3) return null
        val payload = parts[1]
        return runCatching {
            val json = String(Base64.getUrlDecoder().decode(payload.padEnd((payload.length + 3) / 4 * 4, '=')))
            (json.safeParseJsonObject()[name] as? JsonPrimitive)?.content
        }.getOrNull()
    }

    /** 到期时刻（毫秒）；不是 JWT 或没有 exp 返回 null。 */
    fun expiresAtMs(token: String): Long? = claim(token, "exp")?.toLongOrNull()?.times(1000)

    /** 已过期（留 1 分钟余量）；解不出来的不算过期。 */
    fun isExpired(token: String, now: Long): Boolean =
        expiresAtMs(token)?.let { it - 60_000 < now } ?: false
}
