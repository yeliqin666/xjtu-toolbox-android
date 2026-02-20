package com.xjtu.toolbox.auth

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

private const val TAG = "JwappLogin"

/**
 * 移动教务系统 (jwapp) 专用登录
 * 登录后自动提取 Authorization token 并注入到 session headers
 *
 * ### Token 生命周期
 * - token 从 CAS 重定向 URL 中提取，存于内存
 * - 通过 [isTokenValid] 检查 token 是否存在
 * - 通过 [reAuthenticate] 利用 CAS SSO TGC cookie 重新获取 token
 * - 通过 [executeWithReAuth] 自动检测 401 并重试
 */
class JwappLogin(
    session: OkHttpClient? = null,
    visitorId: String? = null,
    cachedRsaKey: String? = null
) : XJTULogin(JWAPP_URL, session, visitorId, cachedRsaKey) {

    var authToken: String? = null
        private set

    /** Token 获取时间戳（毫秒），用于估算过期 */
    private var tokenObtainedAt: Long = 0L

    override fun postLogin(response: Response) {
        // 从最终重定向 URL 中提取 token
        val finalUrl = response.request.url.toString()
        authToken = finalUrl.substringAfter("token=", "")
            .substringBefore("&")
            .takeIf { it.isNotEmpty() }
            ?: throw RuntimeException("登录失败：无法获取教务 Token")
        tokenObtainedAt = System.currentTimeMillis()
        Log.d(TAG, "postLogin: token obtained, len=${authToken?.length}")
    }

    /**
     * 检查 token 是否可能有效
     * - token 非空
     * - 获取时间在 TOKEN_TTL_MS 内（默认 1 小时）
     */
    fun isTokenValid(): Boolean {
        val token = authToken ?: return false
        if (token.isEmpty()) return false
        if (tokenObtainedAt > 0 && System.currentTimeMillis() - tokenObtainedAt > TOKEN_TTL_MS) {
            Log.d(TAG, "isTokenValid: token expired (age=${(System.currentTimeMillis() - tokenObtainedAt) / 1000}s)")
            return false
        }
        return true
    }

    /**
     * 获取带 Authorization 头的请求 Builder
     */
    fun authenticatedRequest(url: String): Request.Builder {
        return Request.Builder()
            .url(url)
            .header("Authorization", authToken ?: throw RuntimeException("未登录"))
    }

    private val reAuthLock = Any()

    /**
     * [D1] 重新认证：先尝试 SSO，失败后 fallback 到 casAuthenticate（TGC 过期时用保存的密码）
     * @return true 表示重新认证成功
     */
    fun reAuthenticate(): Boolean = synchronized(reAuthLock) {
        try {
            // 第一步：尝试 SSO（CAS TGC 仍有效时直接成功）
            Log.d(TAG, "reAuthenticate: attempting SSO re-login")
            val request = Request.Builder().url(JWAPP_URL).get().build()
            val response = client.newCall(request).execute()
            val finalUrl = response.request.url.toString()
            val token = finalUrl.substringAfter("token=", "")
                .substringBefore("&")
                .takeIf { it.isNotEmpty() }
            if (token != null) {
                authToken = token
                tokenObtainedAt = System.currentTimeMillis()
                Log.d(TAG, "reAuthenticate: SSO success, new token obtained")
                return true
            }

            // 第二步：SSO 失败（TGC 过期），fallback 到 casAuthenticate 用保存的密码
            Log.d(TAG, "reAuthenticate: SSO failed, trying casAuthenticate fallback")
            val casResult = casAuthenticate(JWAPP_URL)
            if (casResult != null) {
                val casToken = casResult.second.substringAfter("token=", "")
                    .substringBefore("&")
                    .takeIf { it.isNotEmpty() }
                if (casToken != null) {
                    authToken = casToken
                    tokenObtainedAt = System.currentTimeMillis()
                    Log.d(TAG, "reAuthenticate: casAuthenticate success, new token obtained")
                    return true
                }
            }
            Log.w(TAG, "reAuthenticate: all methods failed")
        } catch (e: Exception) {
            Log.e(TAG, "reAuthenticate failed", e)
        }
        return false
    }

    /**
     * 执行带自动重认证的请求
     * 如果请求返回 401/403，自动 reAuthenticate 并重试一次
     */
    fun executeWithReAuth(requestBuilder: Request.Builder): Response {
        val response = client.newCall(
            requestBuilder.header("Authorization", authToken ?: "").build()
        ).execute()
        if (response.code in listOf(401, 403)) {
            Log.d(TAG, "executeWithReAuth: got ${response.code}, attempting reAuth")
            response.close()
            if (reAuthenticate()) {
                return client.newCall(
                    requestBuilder.header("Authorization", authToken ?: "").build()
                ).execute()
            }
        }
        return response
    }

    companion object {
        const val JWAPP_URL =
            "https://org.xjtu.edu.cn/openplatform/oauth/authorize?appId=1370&redirectUri=http://jwapp.xjtu.edu.cn/app/index&responseType=code&scope=user_info&state=1234"

        /** Token 预估 TTL：1 小时（jwapp token 通常较短命） */
        private const val TOKEN_TTL_MS = 60 * 60 * 1000L
    }
}
