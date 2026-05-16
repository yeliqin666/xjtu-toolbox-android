package com.xjtu.toolbox.auth

import android.util.Log
import com.xjtu.toolbox.util.safeParseJsonObject
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import java.net.URLDecoder

/**
 * 校园卡系统登录 (ncard.xjtu.edu.cn)
 *
 * 认证链路：
 * 1. 访问 /berserker-base/redirect → 重定向到 org.xjtu.edu.cn/openplatform
 * 2. openplatform → login.xjtu.edu.cn（新 CAS）认证
 * 3. 新 CAS 认证后 → berserker-auth/cas/login → /plat/?ticket=...
 * 4. 从 URL 提取 ticket，POST /berserker-auth/oauth/token 获取 JWT
 * 5. 使用 JWT Bearer token 调用校园卡 API（synjones-auth: bearer <token>）
 */
class CampusCardLogin(
    existingClient: OkHttpClient? = null,
    visitorId: String? = null
) : XJTULogin(
    loginUrl = LOGIN_URL,
    existingClient = existingClient,
    visitorId = visitorId
) {
    companion object {
        private const val TAG = "CampusCardLogin"
        /** 校园卡登录入口（触发 SSO 重定向链到 login.xjtu.edu.cn） */
        const val LOGIN_URL =
            "https://ncard.xjtu.edu.cn/berserker-base/redirect?type=login&loginFrom=h5&synAccessSource=h5"
        const val BASE_URL = "https://ncard.xjtu.edu.cn"
        private const val TOKEN_URL = "$BASE_URL/berserker-auth/oauth/token"
        /** OAuth2 client Basic Auth: mobile_service_platform:mobile_service_platform_secret */
        private const val TOKEN_BASIC_AUTH = "Basic bW9iaWxlX3NlcnZpY2VfcGxhdGZvcm06bW9iaWxlX3NlcnZpY2VfcGxhdGZvcm1fc2VjcmV0"
        private const val USER_URL = "$BASE_URL/berserker-base/user?synAccessSource=h5"
    }

    /** JWT Bearer 访问令牌 */
    var accessToken: String? = null
        private set

    /** 校园卡账号（非学号，如 "255798"） */
    var cardAccount: String? = null
        internal set

    /** 用户姓名 */
    var userName: String = ""
        private set

    /** 学号 */
    var studentNo: String = ""
        private set

    /** 系统是否就绪 */
    var systemReady: Boolean = false
        private set

    override fun postLogin(response: Response) {
        val finalUrl = response.request.url.toString()
        Log.d(TAG, "postLogin: finalUrl=$finalUrl")
        if (!tryExtractTicketAndGetToken(finalUrl)) {
            // 关键：没拿到 ticket → accessToken 仍为 null，整个登录是"半成品"。
            // 必须抛异常让 autoLogin 视为失败，否则会 cache 一个 systemReady=false 的 broken login，
            // Screen 进入后 API 401 → reAuth 也失败 → 死循环 popBack/重进 → 黑屏。
            Log.w(TAG, "postLogin: no ticket in finalUrl=$finalUrl, throwing to trigger fallback")
            throw RuntimeException("校园卡 SSO 未拿到 ticket，需要重新登录")
        }
    }

    private fun tryExtractTicketAndGetToken(url: String): Boolean {
        if ("ncard.xjtu.edu.cn" !in url || "ticket=" !in url) return false

        val queryStr = url.substringAfter("?", "")
        val params = queryStr.split("&").associate { param ->
            param.substringBefore("=") to param.substringAfter("=", "")
        }
        val rawTicket = params["ticket"] ?: return false
        val ticket = try { URLDecoder.decode(rawTicket, "UTF-8") } catch (e: Exception) { return false }

        Log.d(TAG, "tryExtractTicketAndGetToken: ticket length=${ticket.length}")
        return exchangeTicketForToken(ticket) && fetchUserInfo()
    }

    private fun exchangeTicketForToken(ticket: String): Boolean {
        return try {
            val body = FormBody.Builder()
                .add("username", ticket)
                .add("password", ticket)
                .add("grant_type", "password")
                .add("scope", "all")
                .add("loginFrom", "h5")
                .add("logintype", "sso")
                .add("device_token", "h5")
                .add("synAccessSource", "h5")
                .build()
            val resp = client.newCall(
                Request.Builder().url(TOKEN_URL)
                    .header("Authorization", TOKEN_BASIC_AUTH)
                    .post(body)
                    .build()
            ).execute()
            val bodyStr = resp.body?.use { it.string() } ?: return false
            Log.d(TAG, "exchangeTicketForToken: code=${resp.code}, body=${bodyStr.take(100)}")
            val json = bodyStr.safeParseJsonObject()
            val token = json.get("access_token")?.asString ?: return false
            accessToken = token
            true
        } catch (e: Exception) {
            Log.e(TAG, "exchangeTicketForToken failed", e)
            false
        }
    }

    private fun fetchUserInfo(): Boolean {
        return try {
            val resp = client.newCall(makeAuthRequest(USER_URL)).execute()
            val bodyStr = resp.body?.use { it.string() } ?: return false
            val json = bodyStr.safeParseJsonObject()
            val data = json.getAsJsonObject("data") ?: return false
            cardAccount = data.get("cardAccount")?.asString
            userName = data.get("name")?.asString?.trim() ?: ""
            studentNo = data.get("sno")?.asString ?: ""
            systemReady = true
            Log.d(TAG, "fetchUserInfo: cardAccount=$cardAccount, name=$userName, sno=$studentNo")
            true
        } catch (e: Exception) {
            Log.w(TAG, "fetchUserInfo failed", e)
            false
        }
    }

    /**
     * 重新认证（JWT 过期或 API 返回 401 时调用）
     */
    private val reAuthLock = Any()
    fun reAuthenticate(): Boolean = synchronized(reAuthLock) {
        Log.d(TAG, "reAuthenticate: re-triggering SSO flow...")
        accessToken = null
        systemReady = false
        return try {
            val resp = client.newCall(Request.Builder().url(LOGIN_URL).get().build()).execute()
            resp.body?.use { it.string() }
            val finalUrl = resp.request.url.toString()
            Log.d(TAG, "reAuthenticate: finalUrl=$finalUrl")
            tryExtractTicketAndGetToken(finalUrl)
        } catch (e: Exception) {
            Log.e(TAG, "reAuthenticate failed", e)
            false
        }
    }

    /** 创建带 JWT 鉴权的 GET 请求 */
    fun makeAuthRequest(url: String): Request = Request.Builder()
        .url(url)
        .header("synjones-auth", "bearer ${accessToken ?: ""}")
        .header("synAccessSource", "h5")
        .get()
        .build()

    /** 创建带 JWT 鉴权的 POST 请求 */
    fun makeAuthPostRequest(url: String, body: RequestBody): Request = Request.Builder()
        .url(url)
        .header("synjones-auth", "bearer ${accessToken ?: ""}")
        .header("synAccessSource", "h5")
        .post(body)
        .build()
}
