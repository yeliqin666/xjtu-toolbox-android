package com.xjtu.toolbox.auth

import com.xjtu.toolbox.util.redactBody
import com.xjtu.toolbox.util.redactUrl
import android.util.Log
import com.xjtu.toolbox.util.safeParseJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Cookie
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * 电子打印证系统登录 (dzpz.xjtu.edu.cn)
 *
 * 认证链路（CAS OAuth2.0 方式）：
 * 1. 访问 /login/Login.jsp（不带 code）→ Ecology 下发 oauth2_redirect_uri + ecology_JSessionid，
 *    再 302 到 CAS OAuth2 authorize → CAS 登录页面
 * 2. CAS 登录成功 → 302 回调 /login/Login.jsp?code=OC-xxxxx
 * 3. Login.jsp 用 code + oauth2_redirect_uri cookie 换取 access_token → 设置会话 cookies
 * 4. 获取 loginidweaver (用户 OA ID) 用于后续 API 调用
 *
 * ⚠ 第 1 步不能跳过：Login.jsp 是从 oauth2_redirect_uri cookie 里取 redirect_uri 去调 CAS
 * accessToken 接口的。直接从 authorize 开始（App 之前的做法）会让 dzpz 域上没有该 cookie，
 * 回调时兑换失败，返回 HTTP 200 的错误页「调用获取token接口失败或解析token返回值失败」，
 * 且不下发任何 loginidweaver。
 *
 * cookies: oauth2_access_token, loginidweaver, ecology_JSessionid, JSESSIONID
 */
class DzpzLogin(
    session: OkHttpClient? = null,
    visitorId: String? = null,
    cachedRsaKey: String? = null
) : XJTULogin(
    loginUrl = DZPZ_LOGIN_ENTRY,
    existingClient = session,
    visitorId = visitorId,
    cachedRsaKey = cachedRsaKey
) {
    companion object {
        private const val TAG = "DzpzLogin"
        @Volatile private var lastUserIdFromPostLogin: String? = null

        const val BASE_URL = "https://dzpz.xjtu.edu.cn"

        /**
         * 登录入口 — 必须走 dzpz 自己的 Login.jsp，由它把 oauth2_redirect_uri cookie 种上
         * 之后再 302 到 `login.xjtu.edu.cn/cas/oauth2.0/authorize?client_id=new9940&...`。
         * 与浏览器实际链路完全一致。
         */
        const val DZPZ_LOGIN_ENTRY = "$BASE_URL/login/Login.jsp"

        /**
         * 登录态探针：已登录时返回 `{"resourceid":72439,...}`，匿名访问时该字段缺失。
         * 同时用作 loginidweaver cookie 拿不到时的 userId 兜底来源。
         */
        const val OS_INFO_URL = "$BASE_URL/api/system/info/getOSinfo"
    }

    /** 用户 OA ID (来自 loginidweaver cookie)，用于所有 workflow API 调用 */
    var userId: String? = lastUserIdFromPostLogin
        private set

    override fun postLogin(response: Response) {
        // 从 cookie jar 中提取 loginidweaver。WebVPN 模式下 cookie 存在 webvpn.xjtu.edu.cn 域，
        // loadForRequest(dzpz 明文 URL) 拿不到，必须跨域搜。
        // cookie 是 HttpOnly 且只在 Login.jsp 兑换成功那一跳下发，漏掉时用 getOSinfo 兜底。
        userId = findLoginIdWeaver(response) ?: fetchUserIdFromApi()
        if (userId != null) {
            lastUserIdFromPostLogin = userId
            Log.d(TAG, "postLogin: userId=$userId")
            return
        }

        // 诊断日志：dzpz Login.jsp 拿到 OAuth code 后没继续到 wui/index.html，可能用 meta-refresh / JS
        // 跳转 OkHttp 不跟随。打印 finalUrl + body 头便于分析。
        val finalUrl = response.request.url.toString()
        val bodyHead = try { response.peekBody(800).string() } catch (_: Exception) { "<peekBody failed>" }
        Log.w(TAG, "postLogin: loginidweaver not found")
        Log.w(TAG, "postLogin: finalUrl=${finalUrl.redactUrl()}")
        Log.w(TAG, "postLogin: bodyHead=${bodyHead.redactBody(600)}")
        // dump 现有 cookie 名（不暴露 value）便于排查
        try {
            val jar = client.cookieJar
            if (jar is com.xjtu.toolbox.util.PersistentCookieJar) {
                val all = jar.getCookiesForDomain("webvpn.xjtu.edu.cn") + jar.getCookiesForDomain(".webvpn.xjtu.edu.cn")
                Log.w(TAG, "postLogin: webvpn-cookies(names)=${all.map { it.name }}")
            }
        } catch (_: Exception) {}

        // 重跑一遍入口：Login.jsp 会重新种 oauth2_redirect_uri，此时 TGC 已在 jar 里，
        // CAS 直接回一个新 code，兑换成功即可拿到 loginidweaver。
        // （注意：/wui/index.html、/api/ecode/sync 这类页面匿名访问也返回 200 静态内容，
        //  不会重定向去 CAS，拿它们做 warmup 永远救不回会话。）
        userId = retryOauthRound()

        if (userId == null) {
            throw RuntimeException("登录失败：无法获取用户 OA ID (loginidweaver)")
        }
        lastUserIdFromPostLogin = userId
        Log.d(TAG, "postLogin: userId=$userId (from retry)")
    }

    /**
     * 跨域查找 loginidweaver cookie。
     * 优先使用 PersistentCookieJar 的跨域接口，退化时在 dzpz 域上查。
     */
    private fun findLoginIdWeaver(response: Response? = null): String? {
        response?.let { resp ->
            Cookie.parseAll(resp.request.url, resp.headers)
                .firstOrNull { it.name == "loginidweaver" }
                ?.value
                ?.let { return it }
            resp.headers.values("Set-Cookie")
                .firstNotNullOfOrNull { header ->
                    Regex("""(?:^|;\s*)loginidweaver=([^;]+)""").find(header)?.groupValues?.getOrNull(1)
                }
                ?.let { return it }
        }
        val jar = client.cookieJar
        if (jar is com.xjtu.toolbox.util.PersistentCookieJar) {
            jar.findCookieByName("loginidweaver")?.value?.let { return it }
        }
        return jar.loadForRequest(BASE_URL.toHttpUrl())
            .find { it.name == "loginidweaver" }?.value
    }

    /**
     * 从 `/api/system/info/getOSinfo` 读取 `resourceid`（= loginidweaver 的值）。
     * 未登录时该字段缺失，返回 null。
     */
    private fun fetchUserIdFromApi(): String? {
        return try {
            val req = Request.Builder()
                .url("$OS_INFO_URL?__random__=${System.currentTimeMillis()}")
                .header("Referer", "$BASE_URL/wui/index.html")
                .get()
                .build()
            val resp = client.newCall(req).execute()
            val body = resp.use { it.body?.string() ?: "" }
            val id = body.safeParseJsonObject()
                .get("resourceid")?.takeIf { !it.isJsonNull }?.asString
                ?.takeIf { it.isNotBlank() && it != "0" }
            Log.d(TAG, "fetchUserIdFromApi: resourceid=$id")
            id
        } catch (e: Exception) {
            Log.w(TAG, "fetchUserIdFromApi failed: ${e.message}")
            null
        }
    }

    /**
     * 重新走一遍 Login.jsp → CAS authorize → Login.jsp?code 往返（依赖 jar 里的 TGC 完成 SSO），
     * 成功则返回 userId。用于 postLogin 首次没拿到 cookie、以及会话过期后的重认证。
     */
    private fun retryOauthRound(): String? {
        return try {
            val req = Request.Builder().url(DZPZ_LOGIN_ENTRY).get().build()
            val resp = client.newCall(req).execute()
            val finalUrl = resp.request.url.toString()
            val bodyHead = try { resp.peekBody(400).string() } catch (_: Exception) { "" }
            val id = findLoginIdWeaver(resp)
            resp.close()
            Log.d(TAG, "retryOauthRound: finalUrl=${finalUrl.redactUrl()}, userIdFound=${id != null}")
            if (id == null) Log.d(TAG, "retryOauthRound: bodyHead=${bodyHead.redactBody(240)}")
            id ?: fetchUserIdFromApi()
        } catch (e: Exception) {
            Log.w(TAG, "retryOauthRound failed: ${e.message}")
            null
        }
    }

    /**
     * 构建带 session cookies 的请求
     */
    fun authenticatedRequest(url: String): Request.Builder {
        return Request.Builder()
            .url(url)
            .header("Referer", "$BASE_URL/spa/workflow/static4form/index.html")
    }

    /**
     * 探活用 getOSinfo 而非 /api/ecode/sync —— 后者匿名访问同样返回 200 空 body、不跳 CAS，
     * 会把已失效的会话判成有效。
     */
    override fun validateLogin(): Boolean {
        val id = fetchUserIdFromApi() ?: return false
        userId = id
        lastUserIdFromPostLogin = id
        return true
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
            // 会话仍有效则直接复用
            if (validateLogin()) {
                Log.d(TAG, "reAuthenticate: session still valid")
                return true
            }

            // SSO 方式：TGC 可能仍有效。必须从 Login.jsp 入口走，否则拿不到 oauth2_redirect_uri。
            Log.d(TAG, "reAuthenticate: session expired, trying SSO via login entry")
            val ssoReq = Request.Builder().url(DZPZ_LOGIN_ENTRY).get().build()
            val ssoResp = client.newCall(ssoReq).execute()
            ssoResp.body?.string()
            val ssoFinalUrl = ssoResp.request.url.toString()

            if (com.xjtu.toolbox.util.WebVpnUtil.isAtTargetSite(ssoFinalUrl, "dzpz.xjtu.edu.cn")) {
                userId = findLoginIdWeaver(ssoResp) ?: fetchUserIdFromApi()
                lastUserIdFromPostLogin = userId
                Log.d(TAG, "reAuthenticate: SSO success, userId=$userId")
                return userId != null
            }

            // SSO 失败，尝试 casAuthenticate
            Log.d(TAG, "reAuthenticate: SSO failed, trying casAuthenticate")
            casAuthenticate(DZPZ_LOGIN_ENTRY) ?: return false
            userId = findLoginIdWeaver() ?: fetchUserIdFromApi()
            if (userId != null) {
                lastUserIdFromPostLogin = userId
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
