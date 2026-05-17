package com.xjtu.toolbox.auth

import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * 体育场馆预订系统登录 (202.117.17.144)
 *
 * 认证链路（CAS OAuth2.0 → org.xjtu.edu.cn 中转）：
 * 1. 访问 CAS OAuth2 authorize (client_id=1439)
 * 2. CAS 登录成功 → callbackAuthorize → org.xjtu.edu.cn/authorizesw
 * 3. org.xjtu.edu.cn 302 → http://202.117.17.144/xjtu/cas/oauth2url.html?code=...&employeeNo=...
 * 4. 202.117.17.144 设置 JSESSIONID → 302 → index.html
 *
 * 会话凭据: JSESSIONID cookie (on 202.117.17.144)
 */
class VenueLogin(
    session: OkHttpClient? = null,
    visitorId: String? = null,
    cachedRsaKey: String? = null
) : XJTULogin(
    loginUrl = VENUE_OAUTH_URL,
    existingClient = session,
    visitorId = visitorId,
    cachedRsaKey = cachedRsaKey
) {
    companion object {
        private const val TAG = "VenueLogin"
        @Volatile private var lastSessionValidFromPostLogin: Boolean = false

        /** 场馆系统基础地址 */
        const val BASE_URL = "http://202.117.17.144"
        const val APP_URL = "http://202.117.17.144:8071"

        /**
         * CAS OAuth2.0 授权 URL
         * client_id=1439 → 体育场馆预订平台
         * redirect_uri → org.xjtu.edu.cn 中转后回调到 202.117.17.144
         */
        const val VENUE_OAUTH_URL =
            "https://login.xjtu.edu.cn/cas/oauth2.0/authorize?" +
            "response_type=code&client_id=1439&" +
            "redirect_uri=https%3A%2F%2Forg.xjtu.edu.cn%2Fopenplatform%2Foauth%2Fauthorizesw" +
            "%3Fredirect_uri%3Dhttp%3A%2F%2F202.117.17.144%2Fxjtu%2Fcas%2Foauth2url.html&" +
            "state=1"
    }

    /** 登录后是否已获取有效 session */
    var sessionValid: Boolean = lastSessionValidFromPostLogin
        private set

    override fun postLogin(response: Response) {
        // OkHttp 自动跟随重定向链，最终到达 202.117.17.144/index.html
        // JSESSIONID 已自动存入 CookieJar
        val finalUrl = response.request.url.toString()
        Log.d(TAG, "postLogin: finalUrl=$finalUrl")

        if (com.xjtu.toolbox.util.WebVpnUtil.isAtTargetSite(finalUrl, "202.117.17.144")) {
            sessionValid = true
            lastSessionValidFromPostLogin = true
            Log.d(TAG, "postLogin: session established via redirect chain")
            return
        }

        // 最终 URL 不在 venue 站点（直连或 WebVPN），手动访问首页触发 session
        Log.d(TAG, "postLogin: not at venue site, manually accessing index")
        try {
            val indexReq = Request.Builder()
                .url("$BASE_URL/index.html")
                .get()
                .build()
            val indexResp = client.newCall(indexReq).execute()
            val indexFinalUrl = indexResp.request.url.toString()
            indexResp.close()

            sessionValid = com.xjtu.toolbox.util.WebVpnUtil.isAtTargetSite(indexFinalUrl, "202.117.17.144")
            if (!sessionValid) {
                val appResp = client.newCall(
                    Request.Builder().url("$APP_URL/product/index.html").get().build()
                ).execute()
                val appFinalUrl = appResp.request.url.toString()
                val appBody = appResp.peekBody(4096).string()
                appResp.close()
                sessionValid = com.xjtu.toolbox.util.WebVpnUtil.isAtTargetSite(appFinalUrl, "202.117.17.144") &&
                    !isAuthFailureResponse(appBody)
                Log.d(TAG, "postLogin: app access finalUrl=$appFinalUrl, valid=$sessionValid")
            }
            Log.d(TAG, "postLogin: manual access finalUrl=$indexFinalUrl, valid=$sessionValid")
        } catch (e: Exception) {
            Log.e(TAG, "postLogin: manual access failed", e)
        }

        if (!sessionValid) {
            lastSessionValidFromPostLogin = false
            throw RuntimeException("登录失败：无法建立场馆系统会话")
        }
        lastSessionValidFromPostLogin = true
    }

    /**
     * 构建带 session cookies 的请求
     */
    fun authenticatedRequest(url: String): Request.Builder {
        return Request.Builder()
            .url(url)
            .header("Referer", "$APP_URL/product/index.html")
    }

    override fun validateLogin(): Boolean {
        return try {
            val request = Request.Builder().url("$APP_URL/product/index.html").get().build()
            val response = client.newCall(request).execute()
            val finalUrl = response.request.url.toString()
            val body = response.peekBody(4096).string()
            response.close()
            com.xjtu.toolbox.util.WebVpnUtil.isAtTargetSite(finalUrl, "202.117.17.144") &&
                !isAuthFailureResponse(body)
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
     * 重新认证
     */
    fun reAuthenticate(): Boolean = synchronized(reAuthLock) {
        try {
            // 检查 session 是否仍有效
            val checkReq = Request.Builder()
                .url("$APP_URL/product/index.html")
                .get()
                .build()
            val checkResp = client.newCall(checkReq).execute()
            val finalUrl = checkResp.request.url.toString()
            val body = checkResp.body?.string() ?: ""
            checkResp.close()

            if (com.xjtu.toolbox.util.WebVpnUtil.isAtTargetSite(finalUrl, "202.117.17.144") &&
                body.contains("product/show.html")) {
                Log.d(TAG, "reAuthenticate: session still valid")
                sessionValid = true
                lastSessionValidFromPostLogin = true
                return true
            }

            // SSO 方式
            Log.d(TAG, "reAuthenticate: session expired, trying SSO")
            val ssoReq = Request.Builder().url(VENUE_OAUTH_URL).get().build()
            val ssoResp = client.newCall(ssoReq).execute()
            val ssoFinalUrl = ssoResp.request.url.toString()
            ssoResp.close()

            if (com.xjtu.toolbox.util.WebVpnUtil.isAtTargetSite(ssoFinalUrl, "202.117.17.144")) {
                sessionValid = true
                lastSessionValidFromPostLogin = true
                Log.d(TAG, "reAuthenticate: SSO success")
                return true
            }

            // fallback: casAuthenticate
            Log.d(TAG, "reAuthenticate: SSO failed, trying casAuthenticate")
            val casResult = casAuthenticate(VENUE_OAUTH_URL) ?: return false
            if (com.xjtu.toolbox.util.WebVpnUtil.isAtTargetSite(casResult.second, "202.117.17.144")) {
                sessionValid = true
                lastSessionValidFromPostLogin = true
                Log.d(TAG, "reAuthenticate: casAuthenticate success")
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "reAuthenticate failed", e)
        }
        sessionValid = false
        lastSessionValidFromPostLogin = false
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
            finalUrl.contains("login.xjtu.edu.cn/cas/login", ignoreCase = true) -> true
            response.code in listOf(401, 403) -> true
            response.code == 200 -> {
                val ct = response.header("Content-Type") ?: ""
                if ("html" in ct || "text" in ct) {
                    XJTULogin.isAuthFailureResponse(response.peekBody(8192).string())
                } else false
            }
            else -> false
        }
        if (needReAuth) {
            response.close()
            if (reAuthenticate()) {
                return client.newCall(request.build()).execute()
            }
            throw AuthExpiredException("体育场馆")
        }
        return response
    }
}
