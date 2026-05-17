package com.xjtu.toolbox.auth

import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * 电子打印证系统登录 (dzpz.xjtu.edu.cn)
 *
 * 认证链路（CAS OAuth2.0 方式）：
 * 1. 访问 CAS OAuth2 authorize → CAS 登录页面
 * 2. CAS 登录成功 → 302 回调 /login/Login.jsp?code=OC-xxxxx
 * 3. Login.jsp 用 code 换取 access_token → 设置会话 cookies
 * 4. 获取 loginidweaver (用户 OA ID) 用于后续 API 调用
 *
 * cookies: oauth2_access_token, loginidweaver, ecology_JSessionid, JSESSIONID
 */
class DzpzLogin(
    session: OkHttpClient? = null,
    visitorId: String? = null,
    cachedRsaKey: String? = null
) : XJTULogin(
    loginUrl = DZPZ_OAUTH_URL,
    existingClient = session,
    visitorId = visitorId,
    cachedRsaKey = cachedRsaKey
) {
    companion object {
        private const val TAG = "DzpzLogin"

        /**
         * CAS OAuth2.0 授权 URL — 打开后自动跳转到 CAS 登录页面
         * client_id=new9940 → 泛微 Ecology OA 的 OAuth2 客户端标识
         * redirect_uri → 登录成功后回调地址 (Login.jsp 交换 code→token)
         */
        const val DZPZ_OAUTH_URL =
            "https://login.xjtu.edu.cn/cas/oauth2.0/authorize?" +
            "response_type=code&client_id=new9940&" +
            "redirect_uri=https%3A%2F%2Fdzpz.xjtu.edu.cn%2Flogin%2FLogin.jsp"

        const val BASE_URL = "https://dzpz.xjtu.edu.cn"
    }

    /** 用户 OA ID (来自 loginidweaver cookie)，用于所有 workflow API 调用 */
    var userId: String? = null
        private set

    override fun postLogin(response: Response) {
        // 从 cookie jar 中提取 loginidweaver。WebVPN 模式下 cookie 存在 webvpn.xjtu.edu.cn 域，
        // loadForRequest(dzpz 明文 URL) 拿不到，必须跨域搜。
        userId = findLoginIdWeaver()
        if (userId != null) {
            Log.d(TAG, "postLogin: userId=$userId")
            return
        }

        // 诊断日志：dzpz Login.jsp 拿到 OAuth code 后没继续到 wui/index.html，可能用 meta-refresh / JS
        // 跳转 OkHttp 不跟随。打印 finalUrl + body 头便于分析。
        val finalUrl = response.request.url.toString()
        val bodyHead = try { response.peekBody(800).string() } catch (_: Exception) { "<peekBody failed>" }
        Log.w(TAG, "postLogin: loginidweaver not found")
        Log.w(TAG, "postLogin: finalUrl=$finalUrl")
        Log.w(TAG, "postLogin: bodyHead=${bodyHead.replace("\n", " ").take(600)}")
        // dump 现有 cookie 名（不暴露 value）便于排查
        try {
            val jar = client.cookieJar
            if (jar is com.xjtu.toolbox.util.PersistentCookieJar) {
                val all = jar.getCookiesForDomain("webvpn.xjtu.edu.cn") + jar.getCookiesForDomain(".webvpn.xjtu.edu.cn")
                Log.w(TAG, "postLogin: webvpn-cookies(names)=${all.map { it.name }}")
            }
        } catch (_: Exception) {}

        // Cookie 可能还没写入 jar，尝试重新访问以触发 session 初始化
        Log.d(TAG, "postLogin: re-accessing home page")
        try {
            val homeReq = Request.Builder().url("$BASE_URL/wui/index.html").get().build()
            val homeResp = client.newCall(homeReq).execute()
            val homeFinal = homeResp.request.url.toString()
            val homeBodyHead = try { homeResp.peekBody(400).string() } catch (_: Exception) { "" }
            homeResp.close()
            Log.d(TAG, "postLogin: home retry finalUrl=$homeFinal")
            Log.d(TAG, "postLogin: home retry bodyHead=${homeBodyHead.take(300)}")
            userId = findLoginIdWeaver()
        } catch (e: Exception) {
            Log.e(TAG, "postLogin: retry failed", e)
        }

        if (userId == null) {
            throw RuntimeException("登录失败：无法获取用户 OA ID (loginidweaver)")
        }
        Log.d(TAG, "postLogin: userId=$userId (from retry)")
    }

    /**
     * 跨域查找 loginidweaver cookie。
     * 优先使用 PersistentCookieJar 的跨域接口，退化时在 dzpz 域上查。
     */
    private fun findLoginIdWeaver(): String? {
        val jar = client.cookieJar
        if (jar is com.xjtu.toolbox.util.PersistentCookieJar) {
            jar.findCookieByName("loginidweaver")?.value?.let { return it }
        }
        return jar.loadForRequest(BASE_URL.toHttpUrl())
            .find { it.name == "loginidweaver" }?.value
    }

    /**
     * 构建带 session cookies 的请求
     */
    fun authenticatedRequest(url: String): Request.Builder {
        return Request.Builder()
            .url(url)
            .header("Referer", "$BASE_URL/spa/workflow/static4form/index.html")
    }

    override fun validateLogin(): Boolean {
        return try {
            val request = Request.Builder().url("$BASE_URL/api/ecode/sync").get().build()
            val response = client.newCall(request).execute()
            val finalUrl = response.request.url.toString()
            val body = response.peekBody(4096).string()
            response.close()
            !finalUrl.contains("login.xjtu.edu.cn/cas/login", ignoreCase = true) && !isAuthFailureResponse(body)
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
     * 重新认证：先尝试 SSO（TGC 有效时直接成功），失败后 fallback 到 casAuthenticate
     */
    fun reAuthenticate(): Boolean = synchronized(reAuthLock) {
        try {
            // 尝试直接访问 dzpz API 检查 session 是否有效
            val checkReq = Request.Builder()
                .url("$BASE_URL/api/ecode/sync")
                .get()
                .build()
            val checkResp = client.newCall(checkReq).execute()
            val finalUrl = checkResp.request.url.toString()
            checkResp.close()

            // 如果没有被重定向到 CAS，说明 session 仍有效
            if (!finalUrl.contains("login.xjtu.edu.cn/cas/login", ignoreCase = true)) {
                Log.d(TAG, "reAuthenticate: session still valid")
                return true
            }

            // SSO 方式：TGC 可能仍有效
            Log.d(TAG, "reAuthenticate: session expired, trying SSO via OAuth URL")
            val ssoReq = Request.Builder().url(DZPZ_OAUTH_URL).get().build()
            val ssoResp = client.newCall(ssoReq).execute()
            ssoResp.body?.string()
            val ssoFinalUrl = ssoResp.request.url.toString()

            if (com.xjtu.toolbox.util.WebVpnUtil.isAtTargetSite(ssoFinalUrl, "dzpz.xjtu.edu.cn")) {
                userId = findLoginIdWeaver()
                Log.d(TAG, "reAuthenticate: SSO success, userId=$userId")
                return userId != null
            }

            // SSO 失败，尝试 casAuthenticate
            Log.d(TAG, "reAuthenticate: SSO failed, trying casAuthenticate")
            casAuthenticate(DZPZ_OAUTH_URL) ?: return false
            userId = findLoginIdWeaver()
            if (userId != null) {
                Log.d(TAG, "reAuthenticate: casAuthenticate success, userId=$userId")
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "reAuthenticate failed", e)
        }
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
            throw AuthExpiredException("电子打印证")
        }
        return response
    }
}
