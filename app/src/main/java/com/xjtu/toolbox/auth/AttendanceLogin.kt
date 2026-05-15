package com.xjtu.toolbox.auth

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import com.xjtu.toolbox.util.safeParseJsonObject

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
    private val useWebVpn: Boolean = false,
    val isPostgraduate: Boolean = false
) : XJTULogin(
    loginUrl = when {
        isPostgraduate && useWebVpn -> POSTGRADUATE_ATTENDANCE_WEBVPN_URL
        isPostgraduate -> POSTGRADUATE_ATTENDANCE_URL
        useWebVpn -> ATTENDANCE_WEBVPN_URL
        else -> ATTENDANCE_URL
    },
    existingClient = session,
    visitorId = visitorId
) {

    /** 考勤 API 请求的域名，本科 bkkq / 研究生 yjskq */
    val attendanceDomain: String
        get() = if (isPostgraduate) "yjskq.xjtu.edu.cn" else "bkkq.xjtu.edu.cn"

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
     * 验证考勤系统登录态是否仍然有效（对齐上游 attendance_session.validate_login）。
     * 通过 POST /attendance-student/global/getStuInfo 检测。
     */
    override fun validateLogin(): Boolean {
        val token = authToken ?: return false
        return try {
            val url = "https://$attendanceDomain/attendance-student/global/getStuInfo"
            val request = Request.Builder()
                .url(url)
                .header("Synjones-Auth", "bearer $token")
                .post("".toRequestBody(null))
                .build()
            val response = client.newCall(request).execute()
            if (response.code != 200) { response.close(); return false }
            val body = response.body?.string() ?: ""
            if (isAuthFailureResponse(body)) return false
            val json = body.safeParseJsonObject()
            json.get("success")?.asBoolean == true
        } catch (_: Exception) { false }
    }

    /**
     * 保活：validate → 失效则 reAuth → 返回状态
     */
    override fun keepAlive(): KeepAliveStatus {
        return try {
            if (validateLogin()) return KeepAliveStatus.VALID
            if (reAuthenticate()) KeepAliveStatus.REAUTH_OK
            else KeepAliveStatus.AUTH_INVALID
        } catch (_: java.io.IOException) {
            KeepAliveStatus.NETWORK_ERROR
        } catch (_: Exception) {
            KeepAliveStatus.ERROR
        }
    }

    /**
     * [D1] 重新认证：先尝试 SSO，失败后 fallback 到 casAuthenticate
     * @return true 表示重新认证成功
     */
    private val reAuthLock = Any()

    fun reAuthenticate(): Boolean = synchronized(reAuthLock) {
        try {
            // 第一步：SSO（TGC 有效时直接成功）
            val loginUrl = when {
                isPostgraduate && useWebVpn -> POSTGRADUATE_ATTENDANCE_WEBVPN_URL
                isPostgraduate -> POSTGRADUATE_ATTENDANCE_URL
                useWebVpn -> ATTENDANCE_WEBVPN_URL
                else -> ATTENDANCE_URL
            }
            val request = Request.Builder().url(loginUrl).get().build()
            val response = client.newCall(request).execute()
            val finalUrl = response.request.url.toString()
            val token = finalUrl.substringAfter("token=", "")
                .substringBefore("&")
                .substringBefore("#")
                .takeIf { it.isNotEmpty() }
            if (token != null) {
                authToken = token
                android.util.Log.d("AttendanceLogin", "reAuthenticate: SSO success, new token obtained")
                return true
            }

            // 第二步：SSO 失败（TGC 过期），fallback 到 casAuthenticate
            android.util.Log.d("AttendanceLogin", "reAuthenticate: SSO failed, trying casAuthenticate")
            val casResult = casAuthenticate(loginUrl) ?: return false
            val casToken = casResult.second.substringAfter("token=", "")
                .substringBefore("&")
                .substringBefore("#")
                .takeIf { it.isNotEmpty() }
            if (casToken != null) {
                authToken = casToken
                android.util.Log.d("AttendanceLogin", "reAuthenticate: casAuthenticate success")
                return true
            }
        } catch (e: Exception) {
            android.util.Log.e("AttendanceLogin", "reAuthenticate failed", e)
        }
        return@synchronized false
    }

    /**
     * 执行带自动重认证的请求
     * 如果请求返回 401/403 或被 CAS Safety Verify / 登录页拦截，自动 reAuthenticate 并重试一次
     */
    fun executeWithReAuth(request: Request.Builder): Response {
        val response = client.newCall(request.header("Synjones-Auth", "bearer $authToken").build()).execute()
        val needReAuth = when {
            response.code in listOf(401, 403) -> true
            response.code == 200 -> {
                val contentType = response.header("Content-Type") ?: ""
                if ("html" in contentType || "text" in contentType) {
                    val body = response.peekBody(8192).string()
                    XJTULogin.isAuthFailureResponse(body)
                } else false
            }
            else -> false
        }
        if (needReAuth) {
            response.close()
            if (reAuthenticate()) {
                return client.newCall(request.header("Synjones-Auth", "bearer $authToken").build()).execute()
            }
            throw AuthExpiredException("考勤系统")
        }
        return response
    }
}

/**
 * 教务系统登录（CAS Cookie-based）
 * [A1] 增加 reAuthenticate：session 过期时通过 CAS SSO/casAuthenticate 恢复
 */
class JwxtLogin(
    session: OkHttpClient? = null,
    visitorId: String? = null,
    cachedRsaKey: String? = null
) : XJTULogin(JWXT_URL, session, visitorId, cachedRsaKey) {

    override fun validateLogin(): Boolean {
        return try {
            val request = okhttp3.Request.Builder()
                .url("https://jwxt.xjtu.edu.cn/api/v2/system/term-info")
                .get().build()
            val response = client.newCall(request).execute()
            val finalUrl = response.request.url.toString()
            val code = response.code
            response.close()
            code == 200 && !finalUrl.contains("login.xjtu.edu.cn")
        } catch (_: Exception) { false }
    }

    override fun keepAlive(): KeepAliveStatus {
        return try {
            if (validateLogin()) return KeepAliveStatus.VALID
            if (reAuthenticate()) KeepAliveStatus.REAUTH_OK
            else KeepAliveStatus.AUTH_INVALID
        } catch (_: java.io.IOException) { KeepAliveStatus.NETWORK_ERROR }
        catch (_: Exception) { KeepAliveStatus.ERROR }
    }

    private val reAuthLock = Any()

    /**
     * [A1] 重新认证：通过 CAS SSO 刷新教务 session
     * [D1] SSO 失败时自动 fallback 到 casAuthenticate（用保存的密码重新提交）
     * @return true 表示重新认证成功
     */
    fun reAuthenticate(): Boolean = synchronized(reAuthLock) {
        return try {
            val result = casAuthenticate(JWXT_URL)
            if (result != null && !result.second.contains("login.xjtu.edu.cn/cas/login")) {
                android.util.Log.d("JwxtLogin", "reAuthenticate: success via casAuthenticate")
                true
            } else {
                android.util.Log.w("JwxtLogin", "reAuthenticate: casAuthenticate returned login page")
                false
            }
        } catch (e: Exception) {
            android.util.Log.e("JwxtLogin", "reAuthenticate failed", e)
            false
        }
    }
}

