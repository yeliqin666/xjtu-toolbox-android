package com.xjtu.toolbox.server

import com.xjtu.toolbox.platform.KeyValueStore
import com.xjtu.toolbox.platform.secureKeyValueStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** serve 模式的访问令牌：浏览器侧的唯一一道门（`docs/api-contract.md` §3.2）。 */
object AccessToken {

    /** `:data` 侧的文件名 = `serve.properties`。 */
    const val STORE_NAME = "serve"

    /** 存储里的键名。改它等于让所有已发的令牌失效。 */
    const val KEY = "access_token"

    /** 令牌换 cookie 之后随请求带的那个 cookie 名（`docs/api-contract.md` §3.2 的第二种形态）。 */
    const val COOKIE_NAME = "serve_token"

    /** 32 字节 ≈ 256 位熵，Base64URL 之后 43 个字符。 */
    private const val TOKEN_BYTES = 32

    private val random = SecureRandom()

    /** 盘上已有的令牌（没有就是 `null`，**不生成、不写**）。只读档靠它判断这一份还开不开得起来。 */
    fun existing(store: KeyValueStore = secureKeyValueStore(STORE_NAME)): String? =
        store.getString(KEY)?.takeIf { it.isNotBlank() }

    /** 读回已有令牌，没有就生成一个并落盘。 */
    fun loadOrCreate(store: KeyValueStore = secureKeyValueStore(STORE_NAME)): String {
        existing(store)?.let { return it }
        return newToken().also { store.putString(KEY, it) }
    }

    /** 生成一枚新令牌（URL 安全的 Base64，不带 `=` 填充 —— 它要出现在命令行与 cookie 里）。 */
    fun newToken(): String = ByteArray(TOKEN_BYTES)
        .also { random.nextBytes(it) }
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    /** 令牌换 cookie 的 `Set-Cookie` 值（`GET /api/session` 回给浏览器的那一行）。 */
    fun setCookieHeader(token: String, secure: Boolean = false): String =
        "$COOKIE_NAME=$token; Path=/; HttpOnly; SameSite=Lax" + if (secure) "; Secure" else ""

    /** 常数时间比较。 */
    fun matches(expected: String, candidate: String?): Boolean {
        if (candidate == null) return false
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            candidate.toByteArray(Charsets.UTF_8),
        )
    }
}
