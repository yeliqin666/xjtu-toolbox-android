package com.xjtu.toolbox.auth

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * 考勤系统专用登录
 * 登录后自动提取 Synjones-Auth token 并注入到 session headers
 *
 * 与 Python 版保持一致：
 * - 直连模式使用 ATTENDANCE_URL (org.xjtu.edu.cn OAuth 流程)
 * - WebVPN 模式使用 ATTENDANCE_WEBVPN_URL (bkkq.xjtu.edu.cn 直连，更简短的 CAS 链)
 */
class AttendanceLogin(
    session: OkHttpClient? = null,
    visitorId: String? = null,
    private val useWebVpn: Boolean = false
) : XJTULogin(
    loginUrl = if (useWebVpn) ATTENDANCE_WEBVPN_URL else ATTENDANCE_URL,
    existingClient = session,
    visitorId = visitorId
) {

    var authToken: String? = null
        private set

    override fun postLogin(response: Response) {
        if (useWebVpn) {
            // WebVPN 模式：直接从登录响应的最终 URL 中提取 token
            // 与 Python AttendanceNewWebVPNLogin.postLogin 一致
            val finalUrl = response.request.url.toString()
            authToken = finalUrl.substringAfter("token=", "")
                .substringBefore("&")
                .substringBefore("#")
                .takeIf { it.isNotEmpty() }

            // 如果 login_response 中没有 token，再尝试用 SSO 重新访问
            if (authToken == null) {
                val request = Request.Builder()
                    .url(ATTENDANCE_WEBVPN_URL)
                    .get()
                    .build()
                val tokenResponse = client.newCall(request).execute()
                val retryUrl = tokenResponse.request.url.toString()
                authToken = retryUrl.substringAfter("token=", "")
                    .substringBefore("&")
                    .substringBefore("#")
                    .takeIf { it.isNotEmpty() }
                    ?: throw RuntimeException("登录失败：无法获取考勤 Token (WebVPN)")
            }
        } else {
            // 直连模式：重新访问考勤 URL 以获取 token
            // 与 Python AttendanceNewLogin.postLogin 一致
            val loginUrl = ATTENDANCE_URL
            val request = Request.Builder()
                .url(loginUrl)
                .get()
                .build()

            val tokenResponse = client.newCall(request).execute()
            val finalUrl = tokenResponse.request.url.toString()

            authToken = finalUrl.substringAfter("token=", "")
                .substringBefore("&")
                .substringBefore("#")
                .takeIf { it.isNotEmpty() }
                ?: throw RuntimeException("登录失败：无法获取考勤 Token")
        }
    }

    /**
     * 获取带 Auth 头的请求 Builder
     */
    fun authenticatedRequest(url: String): Request.Builder {
        return Request.Builder()
            .url(url)
            .header("Synjones-Auth", "bearer $authToken")
    }

    /**
     * 重新认证：通过 CAS SSO 重新获取考勤 Token
     * @return true 表示重新认证成功
     */
    fun reAuthenticate(): Boolean {
        try {
            val loginUrl = if (useWebVpn) ATTENDANCE_WEBVPN_URL else ATTENDANCE_URL
            val request = Request.Builder().url(loginUrl).get().build()
            val response = client.newCall(request).execute()
            val finalUrl = response.request.url.toString()
            val token = finalUrl.substringAfter("token=", "")
                .substringBefore("&")
                .substringBefore("#")
                .takeIf { it.isNotEmpty() }
            if (token != null) {
                authToken = token
                android.util.Log.d("AttendanceLogin", "reAuthenticate: new token obtained")
                return true
            }
        } catch (e: Exception) {
            android.util.Log.e("AttendanceLogin", "reAuthenticate failed", e)
        }
        return false
    }

    /**
     * 执行带自动重认证的请求
     * 如果请求返回 401/403，自动 reAuthenticate 并重试一次
     */
    fun executeWithReAuth(request: Request.Builder): Response {
        val response = client.newCall(request.header("Synjones-Auth", "bearer $authToken").build()).execute()
        if (response.code in listOf(401, 403)) {
            response.close()
            if (reAuthenticate()) {
                return client.newCall(request.header("Synjones-Auth", "bearer $authToken").build()).execute()
            }
        }
        return response
    }
}

/**
 * 教务系统登录（纯 CAS，不需要额外 Token）
 */
class JwxtLogin(
    session: OkHttpClient? = null,
    visitorId: String? = null,
    cachedRsaKey: String? = null
) : XJTULogin(JWXT_URL, session, visitorId, cachedRsaKey) {
    // 教务系统不需要额外的 postLogin 处理
    // CAS cookies 已经自动管理
}

