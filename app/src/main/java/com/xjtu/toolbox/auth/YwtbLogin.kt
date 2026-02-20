package com.xjtu.toolbox.auth

import android.util.Base64
import android.util.Log
import com.xjtu.toolbox.util.safeParseJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

private const val TAG = "YwtbLogin"

/**
 * 新师生综合服务大厅 (ywtb.xjtu.edu.cn) 专用登录
 * 登录后从重定向 URL 中提取 CAS ticket（JWT），解码提取 idToken。
 *
 * ### Token 生命周期
 * - idToken 从 CAS ticket JWT payload 解码获得
 * - JWT `exp` 字段用于精确过期检测（[isTokenValid]）
 * - 过期后通过 [reAuthenticate] 利用 CAS SSO TGC cookie 重新获取
 * - [executeWithReAuth] 自动检测过期/401 并重试
 */
class YwtbLogin(
    session: OkHttpClient? = null,
    visitorId: String? = null,
    cachedRsaKey: String? = null
) : XJTULogin(YWTB_LOGIN_URL, session, visitorId, cachedRsaKey) {

    var idToken: String? = null
        private set

    /** JWT 过期时间戳（秒），从 `exp` 字段解析 */
    private var tokenExpireAt: Long = 0L

    /** Token 获取时间戳（毫秒），作为 exp 字段缺失时的兜底 */
    private var tokenObtainedAt: Long = 0L

    override fun postLogin(response: Response) {
        extractTokenFromResponse(response)
    }

    /**
     * 从 CAS 响应中提取 idToken
     */
    private fun extractTokenFromResponse(response: Response) {
        // 使用 OkHttp HttpUrl 解析 query parameter，自动处理 URL 编码
        val ticketJwt = response.request.url.queryParameter("ticket")
            ?.takeIf { it.isNotEmpty() }
            ?: throw RuntimeException("登录失败：无法获取 YWTB ticket")

        val parts = ticketJwt.split(".")
        if (parts.size < 2) throw RuntimeException("无效的 JWT Token")

        // JWT payload 使用 Base64url 编码（可能有或无 padding）
        // 先补齐 padding 再解码，兼容所有格式
        val payload64 = parts[1].let { p ->
            when (p.length % 4) {
                2 -> p + "=="
                3 -> p + "="
                else -> p
            }
        }
        val payloadJson = String(Base64.decode(payload64, Base64.URL_SAFE or Base64.NO_WRAP))
        val payload = payloadJson.safeParseJsonObject()
        idToken = payload.get("idToken")?.asString
            ?: throw RuntimeException("JWT 中未找到 idToken")

        // 提取 exp 字段（JWT 标准，单位：秒）
        tokenExpireAt = payload.get("exp")?.asLong ?: 0L
        tokenObtainedAt = System.currentTimeMillis()

        if (tokenExpireAt > 0) {
            val remainSec = tokenExpireAt - System.currentTimeMillis() / 1000
            Log.d(TAG, "postLogin: idToken obtained, expires in ${remainSec}s")
        } else {
            Log.d(TAG, "postLogin: idToken obtained, no exp field (fallback TTL=${FALLBACK_TTL_MS / 1000}s)")
        }
    }

    /**
     * 检查 token 是否仍有效
     * - 优先使用 JWT `exp` 字段（提前 EXPIRY_MARGIN_SEC 秒视为过期，避免边缘状态）
     * - 若无 exp 字段，使用 FALLBACK_TTL_MS 兜底
     */
    fun isTokenValid(): Boolean {
        val token = idToken ?: return false
        if (token.isEmpty()) return false

        val nowSec = System.currentTimeMillis() / 1000
        if (tokenExpireAt > 0) {
            val valid = nowSec < tokenExpireAt - EXPIRY_MARGIN_SEC
            if (!valid) Log.d(TAG, "isTokenValid: JWT expired (exp=$tokenExpireAt, now=$nowSec)")
            return valid
        }
        // 无 exp 字段时用获取时间兜底
        if (tokenObtainedAt > 0) {
            val age = System.currentTimeMillis() - tokenObtainedAt
            val valid = age < FALLBACK_TTL_MS
            if (!valid) Log.d(TAG, "isTokenValid: fallback TTL expired (age=${age / 1000}s)")
            return valid
        }
        return true
    }

    private val reAuthLock = Any()

    /**
     * [D1] 重新认证：先尝试 SSO，失败后 fallback 到 casAuthenticate（TGC 过期时用保存的密码）
     * @return true 表示重新认证成功
     */
    fun reAuthenticate(): Boolean = synchronized(reAuthLock) {
        try {
            // 第一步：SSO（TGC 有效时直接成功）
            Log.d(TAG, "reAuthenticate: attempting SSO re-login")
            val request = Request.Builder().url(YWTB_LOGIN_URL).get().build()
            val response = client.newCall(request).execute()
            try {
                extractTokenFromResponse(response)
                Log.d(TAG, "reAuthenticate: SSO success, new idToken obtained")
                return true
            } catch (_: Exception) {
                Log.d(TAG, "reAuthenticate: SSO failed (no ticket in redirect), trying casAuthenticate")
            }

            // 第二步：casAuthenticate fallback（TGC 过期）
            val serviceUrl = YWTB_LOGIN_URL.substringAfter("service=").let {
                java.net.URLDecoder.decode(it, "UTF-8")
            }
            val casResult = casAuthenticate(serviceUrl)
            if (casResult != null) {
                // casAuthenticate 成功后，CAS cookie 已更新
                // 重新尝试 SSO 访问
                val retryRequest = Request.Builder().url(YWTB_LOGIN_URL).get().build()
                val retryResponse = client.newCall(retryRequest).execute()
                try {
                    extractTokenFromResponse(retryResponse)
                    Log.d(TAG, "reAuthenticate: casAuthenticate fallback success")
                    return true
                } catch (_: Exception) {
                    Log.w(TAG, "reAuthenticate: casAuthenticate succeeded but token extraction failed")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "reAuthenticate failed", e)
        }
        return false
    }

    /**
     * 执行带自动重认证的请求
     * 1. 如果 token 已知过期（JWT exp），先 proactive 刷新
     * 2. 如果请求返回 401/403，reactive 刷新并重试一次
     */
    fun executeWithReAuth(requestBuilder: Request.Builder): Response {
        // Proactive: 如果 JWT 已过期，先刷新
        if (!isTokenValid()) {
            Log.d(TAG, "executeWithReAuth: token expired, proactive reAuth")
            reAuthenticate()
        }

        val response = client.newCall(
            requestBuilder
                .header("x-id-token", idToken ?: "")
                .build()
        ).execute()

        if (response.code in listOf(401, 403)) {
            Log.d(TAG, "executeWithReAuth: got ${response.code}, reactive reAuth")
            response.close()
            if (reAuthenticate()) {
                return client.newCall(
                    requestBuilder
                        .header("x-id-token", idToken ?: "")
                        .build()
                ).execute()
            }
        }
        return response
    }

    companion object {
        const val YWTB_LOGIN_URL =
            "https://login.xjtu.edu.cn/cas/login?service=https%3A%2F%2Fywtb.xjtu.edu.cn%2F%3Fpath%3Dhttps%253A%252F%252Fywtb.xjtu.edu.cn%252Fmain.html%2523%252FIndex"

        /** JWT 过期提前裕量：30 秒 */
        private const val EXPIRY_MARGIN_SEC = 30L

        /** 无 exp 字段时的兜底 TTL：1 小时 */
        private const val FALLBACK_TTL_MS = 60 * 60 * 1000L
    }
}
