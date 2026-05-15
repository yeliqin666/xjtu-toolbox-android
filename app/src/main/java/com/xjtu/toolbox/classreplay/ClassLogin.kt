package com.xjtu.toolbox.classreplay

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import com.xjtu.toolbox.auth.XJTULogin

/**
 * class.xjtu.edu.cn (TronClass) 课程平台登录
 *
 * 认证链路 (CAS → Keycloak → TronClass session)：
 * 1. 访问 class.xjtu.edu.cn → 302 → class.xjtu.edu.cn:8600/auth/realms/xjtu/protocol/cas/login
 * 2. → Keycloak broker → org.xjtu.edu.cn → login.xjtu.edu.cn/cas (appId=968)
 * 3. CAS 登录成功 → 回调链 → class.xjtu.edu.cn/login?ticket=... → session cookie
 *
 * 会话凭据: session cookie (on class.xjtu.edu.cn)
 */
class ClassLogin(
    session: OkHttpClient? = null,
    visitorId: String? = null,
    cachedRsaKey: String? = null
) : XJTULogin(
    loginUrl = CLASS_LOGIN_URL,
    existingClient = session,
    visitorId = visitorId,
    cachedRsaKey = cachedRsaKey
) {
    companion object {
        private const val TAG = "ClassLogin"

        /** TronClass 基础地址 */
        const val BASE_URL = "https://class.xjtu.edu.cn"

        /**
         * 初始登录 URL — OkHttp 自动跟随重定向链到 CAS 登录页面
         * class.xjtu.edu.cn → Keycloak(:8600) → org.xjtu.edu.cn → login.xjtu.edu.cn/cas
         */
        const val CLASS_LOGIN_URL = "https://class.xjtu.edu.cn/login"
    }

    /** 登录后是否已获取有效 session */
    var sessionValid: Boolean = false
        private set

    override fun postLogin(response: Response) {
        val finalUrl = response.request.url.toString()
        Log.d(TAG, "postLogin: finalUrl=$finalUrl")

        if (finalUrl.contains("class.xjtu.edu.cn") && !finalUrl.contains("login.xjtu.edu.cn")) {
            sessionValid = true
            Log.d(TAG, "postLogin: session established via redirect chain")
            return
        }

        // 如果最终 URL 不在 class.xjtu.edu.cn，手动访问触发 session
        Log.d(TAG, "postLogin: not at class site, manually accessing user/index")
        try {
            val indexReq = Request.Builder()
                .url("$BASE_URL/user/index")
                .get()
                .build()
            val indexResp = client.newCall(indexReq).execute()
            val indexFinalUrl = indexResp.request.url.toString()
            indexResp.close()

            sessionValid = indexFinalUrl.contains("class.xjtu.edu.cn") &&
                !indexFinalUrl.contains("login.xjtu.edu.cn")
            Log.d(TAG, "postLogin: manual access finalUrl=$indexFinalUrl, valid=$sessionValid")
        } catch (e: Exception) {
            Log.e(TAG, "postLogin: manual access failed", e)
        }

        if (!sessionValid) {
            throw RuntimeException("登录失败：无法建立课程平台会话")
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
            code == 200 && finalUrl.contains("class.xjtu.edu.cn") &&
                !finalUrl.contains("login.xjtu.edu.cn")
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
            // 检查 session 是否仍有效
            val checkReq = Request.Builder()
                .url("$BASE_URL/api/user/recently-visited-courses")
                .header("Accept", "application/json")
                .get()
                .build()
            val checkResp = client.newCall(checkReq).execute()
            val finalUrl = checkResp.request.url.toString()
            val code = checkResp.code
            checkResp.close()

            if (code == 200 && finalUrl.contains("class.xjtu.edu.cn") &&
                !finalUrl.contains("login.xjtu.edu.cn")) {
                Log.d(TAG, "reAuthenticate: session still valid")
                sessionValid = true
                return true
            }

            // SSO 方式 — 重新访问 login URL
            Log.d(TAG, "reAuthenticate: session expired, trying SSO")
            val ssoReq = Request.Builder().url(CLASS_LOGIN_URL).get().build()
            val ssoResp = client.newCall(ssoReq).execute()
            val ssoFinalUrl = ssoResp.request.url.toString()
            ssoResp.close()

            if (ssoFinalUrl.contains("class.xjtu.edu.cn") &&
                !ssoFinalUrl.contains("login.xjtu.edu.cn")) {
                sessionValid = true
                Log.d(TAG, "reAuthenticate: SSO success")
                return true
            }

            // fallback: casAuthenticate
            Log.d(TAG, "reAuthenticate: SSO failed, trying casAuthenticate")
            val casResult = casAuthenticate(CLASS_LOGIN_URL) ?: return false
            if (casResult.second.contains("class.xjtu.edu.cn") &&
                !casResult.second.contains("login.xjtu.edu.cn")) {
                sessionValid = true
                Log.d(TAG, "reAuthenticate: casAuthenticate success")
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "reAuthenticate failed", e)
        }
        sessionValid = false
        return@synchronized false
    }

    /**
     * 执行带自动重认证的请求
     * 如果请求返回 302 到 CAS、401/403 或被 Safety Verify 拦截，自动重认证并重试
     */
    fun executeWithReAuth(request: Request.Builder): Response {
        val response = client.newCall(request.build()).execute()
        val finalUrl = response.request.url.toString()

        val needReAuth = when {
            finalUrl.contains("login.xjtu.edu.cn") -> true
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
                return client.newCall(request.build()).execute()
            }
            throw com.xjtu.toolbox.auth.AuthExpiredException("课程回放")
        }
        return response
    }
}
