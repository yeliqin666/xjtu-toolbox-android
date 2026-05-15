package com.xjtu.toolbox.lms

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import com.xjtu.toolbox.auth.XJTULogin

/**
 * 思源学堂 (lms.xjtu.edu.cn) 登录
 *
 * 认证链路 (CAS SSO):
 * 1. 访问 lms.xjtu.edu.cn → 302 → login.xjtu.edu.cn/cas
 * 2. CAS 登录成功 → 回调链 → lms.xjtu.edu.cn/user/index → session cookie
 *
 * 会话凭据: session cookie (on lms.xjtu.edu.cn)
 */
class LmsLogin(
    session: OkHttpClient? = null,
    visitorId: String? = null,
    cachedRsaKey: String? = null
) : XJTULogin(
    loginUrl = LMS_LOGIN_URL,
    existingClient = session,
    visitorId = visitorId,
    cachedRsaKey = cachedRsaKey
) {
    companion object {
        private const val TAG = "LmsLogin"

        /** 思源学堂基础地址 */
        const val BASE_URL = "https://lms.xjtu.edu.cn"

        /** RMS 回放服务地址 */
        const val RMS_BASE_URL = "https://rms-v5.xjtu.edu.cn"

        /** CAS 登录入口 — OkHttp 自动跟随重定向到 login.xjtu.edu.cn */
        const val LMS_LOGIN_URL = "https://lms.xjtu.edu.cn"
    }

    /** 登录后是否已获取有效 session */
    var sessionValid: Boolean = false
        private set

    override fun postLogin(response: Response) {
        val finalUrl = response.request.url.toString()
        Log.d(TAG, "postLogin: finalUrl=$finalUrl")

        if (finalUrl.contains("lms.xjtu.edu.cn") && !finalUrl.contains("login.xjtu.edu.cn")) {
            sessionValid = true
            Log.d(TAG, "postLogin: session established via redirect chain")
            return
        }

        // 如果最终 URL 不在 lms，手动访问触发 session
        Log.d(TAG, "postLogin: not at LMS site, manually accessing user/index")
        try {
            val indexReq = Request.Builder()
                .url("$BASE_URL/user/index")
                .get()
                .build()
            val indexResp = client.newCall(indexReq).execute()
            val indexFinalUrl = indexResp.request.url.toString()
            indexResp.close()

            sessionValid = indexFinalUrl.contains("lms.xjtu.edu.cn") &&
                !indexFinalUrl.contains("login.xjtu.edu.cn")
            Log.d(TAG, "postLogin: manual access finalUrl=$indexFinalUrl, valid=$sessionValid")
        } catch (e: Exception) {
            Log.e(TAG, "postLogin: manual access failed", e)
        }

        if (!sessionValid) {
            throw RuntimeException("登录失败：无法建立思源学堂会话")
        }
    }

    /**
     * 构建带 session cookies 的请求
     */
    fun authenticatedRequest(url: String): Request.Builder {
        return Request.Builder()
            .url(url)
            .header("Referer", "$BASE_URL/user/courses")
            .header("Accept", "application/json, text/plain, */*")
    }

    override fun validateLogin(): Boolean {
        return try {
            val request = okhttp3.Request.Builder()
                .url("$BASE_URL/api/user/recently-visited-courses")
                .header("Accept", "application/json")
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
     * 重新认证 (session 过期时调用)
     */
    fun reAuthenticate(): Boolean = synchronized(reAuthLock) {
        try {
            val checkReq = Request.Builder()
                .url("$BASE_URL/api/my-courses")
                .header("Accept", "application/json")
                .post(ByteArray(0).toRequestBody(null))
                .build()
            val checkResp = client.newCall(checkReq).execute()
            val body = checkResp.body?.use { it.string() } ?: ""

            if (checkResp.code == 200 && body.contains("courses")) {
                sessionValid = true
                Log.d(TAG, "reAuthenticate: session still valid")
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "reAuthenticate: check failed", e)
        }

        // Session 过期，尝试通过 CAS SSO 重新认证
        try {
            val result = casAuthenticate("$BASE_URL/user/index") ?: return false
            val (_, finalUrl) = result
            sessionValid = finalUrl.contains("lms.xjtu.edu.cn") &&
                !finalUrl.contains("login.xjtu.edu.cn")
            Log.d(TAG, "reAuthenticate: CAS re-auth, finalUrl=$finalUrl, valid=$sessionValid")
            return sessionValid
        } catch (e: Exception) {
            Log.e(TAG, "reAuthenticate: CAS re-auth failed", e)
            return false
        }
    }

    /**
     * 执行请求，自动在会话过期时重新认证并重试
     * 如果请求返回 302 到 CAS、401/403 或被 Safety Verify 拦截，自动重认证并重试
     */
    fun executeWithReAuth(requestBuilder: Request.Builder): okhttp3.Response {
        val request = requestBuilder.build()
        val response = client.newCall(request).execute()

        val needReAuth = when {
            response.request.url.toString().contains("login.xjtu.edu.cn") -> true
            response.code in listOf(401, 403) -> true
            response.code == 200 -> {
                val ct = response.header("Content-Type") ?: ""
                if ("html" in ct || "text" in ct) {
                    com.xjtu.toolbox.auth.XJTULogin.isAuthFailureResponse(response.peekBody(8192).string())
                } else false
            }
            else -> false
        }
        if (needReAuth) {
            response.close()
            if (reAuthenticate()) {
                return client.newCall(request).execute()
            }
            throw com.xjtu.toolbox.auth.AuthExpiredException("思源学堂")
        }
        return response
    }
}
