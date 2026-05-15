package com.xjtu.toolbox.auth

import android.util.Log
import com.google.gson.JsonElement
import com.xjtu.toolbox.util.safeGet
import com.xjtu.toolbox.util.safeParseJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

private const val COUPON_TAG = "CouponLogin"
private const val COUPON_BROWSER_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36 Edg/136.0.0.0"

class CouponLogin(
    session: OkHttpClient? = null,
    visitorId: String? = null,
    cachedRsaKey: String? = null
) : XJTULogin(buildCouponOAuthUrl(), session.withCouponTimeouts(), visitorId, cachedRsaKey) {

    companion object {
        private const val BASE_URL = "https://egc.xjtu.edu.cn"
        private const val RECEIVE_URL = "$BASE_URL/page/cas/receiveCas.html?version=SAFT_VERSION"
        private const val SSO_LOGIN_BASE = "$BASE_URL/sso/login"
        private val JSON = "application/json;charset=UTF-8".toMediaType()

        private const val ORG_REDIRECT =
            "https://org.xjtu.edu.cn/openplatform/oauth/authorizesw?redirect_uri=base64aHR0cHM6Ly9lZ2MueGp0dS5lZHUuY24vcGFnZS9jYXMvcmVjZWl2ZUNhcy5odG1sP3ZlcnNpb249U0FGVF9WRVJTSU9O"

        fun buildCouponOAuthUrl(): String {
            return "https://login.xjtu.edu.cn/cas/oauth2.0/authorize" +
                "?response_type=code" +
                "&client_id=1596" +
                "&redirect_uri=${urlEncode(ORG_REDIRECT)}" +
                "&state=1995"
        }
    }

    var authToken: String? = null
        private set

    private var tokenObtainedAt: Long = 0L

    init {
        if (hasLogin && authToken.isNullOrBlank()) {
            reAuthenticate()
        }
    }

    override fun postLogin(response: Response) {
        if (!response.isSuccessful) {
            throw RuntimeException("登录失败：加餐券认证入口返回 HTTP ${response.code}")
        }
        val finalUrl = response.request.url.toString()
        Log.d(COUPON_TAG, "postLogin: finalUrl=${finalUrl.redactCouponCallback()}")
        val params = extractCallbackParams(finalUrl)
            ?: extractCallbackParams(lastResponseBody)
            ?: throw RuntimeException("登录失败：无法获取加餐券授权码")
        exchangeCodeForToken(params)
    }

    fun isTokenValid(): Boolean {
        val token = authToken ?: return false
        if (token.isBlank()) return false
        return tokenObtainedAt == 0L || System.currentTimeMillis() - tokenObtainedAt < TOKEN_TTL_MS
    }

    fun authenticatedRequest(url: String, jsonBody: String): Request.Builder {
        if (!isTokenValid() && !reAuthenticate()) {
            throw RuntimeException("加餐券登录已过期，请重新登录")
        }
        return Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody(JSON))
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("Content-Type", "application/json;charset=UTF-8")
            .header("Origin", BASE_URL)
            .header("Referer", RECEIVE_URL)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Authorization", authToken ?: "")
    }

    override fun validateLogin(): Boolean {
        return isTokenValid()
    }

    override fun keepAlive(): KeepAliveStatus {
        return try {
            if (isTokenValid()) return KeepAliveStatus.VALID
            if (reAuthenticate()) KeepAliveStatus.REAUTH_OK
            else KeepAliveStatus.AUTH_INVALID
        } catch (_: java.io.IOException) { KeepAliveStatus.NETWORK_ERROR }
        catch (_: Exception) { KeepAliveStatus.ERROR }
    }

    private val reAuthLock = Any()

    fun reAuthenticate(): Boolean = synchronized(reAuthLock) {
        try {
            Log.d(COUPON_TAG, "reAuthenticate: start")
            val response = client.newCall(Request.Builder().url(buildCouponOAuthUrl()).get().build()).execute()
            val body = response.body?.string().orEmpty()
            val params = extractCallbackParams(response.request.url.toString())
                ?: extractCallbackParams(body)
                ?: return false
            exchangeCodeForToken(params)
            Log.d(COUPON_TAG, "reAuthenticate: success")
            return true
        } catch (e: Exception) {
            Log.e(COUPON_TAG, "reAuthenticate failed", e)
        }
        false
    }

    fun executeWithReAuth(requestBuilder: Request.Builder): Response {
        val response = client.newCall(requestBuilder.build()).execute()
        val needReAuth = when {
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
                return client.newCall(requestBuilder.header("Authorization", authToken ?: "").build()).execute()
            }
            throw AuthExpiredException("加餐券")
        }
        return response
    }

    private fun exchangeCodeForToken(params: CallbackParams) {
        Log.d(
            COUPON_TAG,
            "exchangeCodeForToken: start userType=${params.userType.isNotBlank()} employeeNo=${params.employeeNo.isNotBlank()}"
        )
        val url = "$SSO_LOGIN_BASE?code=${urlEncode(params.code)}&userType=${urlEncode(params.userType)}&employeeNo=${urlEncode(params.employeeNo)}"
        val request = Request.Builder()
            .url(url)
            .post("""{"json":true}""".toRequestBody(JSON))
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("User-Agent", COUPON_BROWSER_UA)
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .header("Content-Type", "application/json;charset=UTF-8")
            .header("Origin", BASE_URL)
            .header("Referer", RECEIVE_URL)
            .header("X-Requested-With", "XMLHttpRequest")
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            Log.d(COUPON_TAG, "exchangeCodeForToken: response code=${response.code}, bodyLen=${text.length}")
            val headerToken = response.header("Authorization")?.normalizeToken()
            val bodyToken = extractToken(text)
            authToken = headerToken ?: bodyToken
                ?: throw RuntimeException("登录失败：无法获取加餐券令牌 (${text.take(80)})")
            tokenObtainedAt = System.currentTimeMillis()
            Log.d(COUPON_TAG, "exchangeCodeForToken: token obtained, len=${authToken?.length}")
        }
    }

    private data class CallbackParams(
        val code: String,
        val userType: String,
        val employeeNo: String
    )

    private fun extractCallbackParams(text: String): CallbackParams? {
        if (text.isBlank()) return null
        val decoded = runCatching { URLDecoder.decode(text, "UTF-8") }.getOrDefault(text)
        val code = findParam(decoded, "code") ?: return null
        return CallbackParams(
            code = code,
            userType = findParam(decoded, "userType").orEmpty(),
            employeeNo = findParam(decoded, "employeeNo").orEmpty()
        )
    }

    private fun findParam(text: String, key: String): String? {
        val encoded = Regex("""[?&#]$key=([^&#"']*)""").find(text)?.groupValues?.getOrNull(1)
        if (!encoded.isNullOrBlank()) return URLDecoder.decode(encoded, "UTF-8")
        val jsonLike = Regex(""""$key"\s*:\s*"([^"]+)"""").find(text)?.groupValues?.getOrNull(1)
        return jsonLike?.takeIf { it.isNotBlank() }
    }

    private fun extractToken(text: String): String? {
        val root = runCatching { text.safeParseJsonObject() }.getOrNull()
        if (root != null) findTokenInJson(root)?.let { return it }
        return Regex("""eyJ[A-Za-z0-9_\-.]+""").find(text)?.value?.normalizeToken()
    }

    private fun findTokenInJson(element: JsonElement?): String? {
        if (element == null || element.isJsonNull) return null
        if (element.isJsonPrimitive) {
            val value = element.asString.normalizeToken()
            return value.takeIf { it.startsWith("eyJ") }
        }
        if (element.isJsonObject) {
            val obj = element.asJsonObject
            listOf("Authorization", "authorization", "token", "accessToken", "access_token", "jwt", "data").forEach { key ->
                findTokenInJson(obj.safeGet(key))?.let { return it }
            }
            obj.entrySet().forEach { (_, value) -> findTokenInJson(value)?.let { return it } }
        }
        if (element.isJsonArray) {
            element.asJsonArray.forEach { value -> findTokenInJson(value)?.let { return it } }
        }
        return null
    }
}

private const val TOKEN_TTL_MS = 60 * 60 * 1000L

private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

private fun OkHttpClient?.withCouponTimeouts(): OkHttpClient? =
    this?.newBuilder()
        ?.connectTimeout(60, TimeUnit.SECONDS)
        ?.readTimeout(60, TimeUnit.SECONDS)
        ?.writeTimeout(60, TimeUnit.SECONDS)
        ?.callTimeout(120, TimeUnit.SECONDS)
        ?.addInterceptor { chain ->
            val request = chain.request()
            val requestWithBrowserHeaders = request.newBuilder()
                .header("User-Agent", COUPON_BROWSER_UA)
                .header(
                    "Accept",
                    request.header("Accept")
                        ?: "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8"
                )
                .header("Accept-Language", request.header("Accept-Language") ?: "zh-CN,zh;q=0.9,en;q=0.8")
                .build()
            val start = System.nanoTime()
            Log.d(COUPON_TAG, "HTTP start ${requestWithBrowserHeaders.method} ${requestWithBrowserHeaders.url.redactCouponCallback()}")
            try {
                val response = chain.proceed(requestWithBrowserHeaders)
                val elapsedMs = (System.nanoTime() - start) / 1_000_000
                Log.d(COUPON_TAG, "HTTP end ${response.code} ${requestWithBrowserHeaders.url.redactCouponCallback()} ${elapsedMs}ms")
                response
            } catch (e: IOException) {
                val elapsedMs = (System.nanoTime() - start) / 1_000_000
                Log.e(COUPON_TAG, "HTTP fail ${requestWithBrowserHeaders.url.redactCouponCallback()} ${elapsedMs}ms ${e.javaClass.simpleName}: ${e.message}", e)
                throw e
            }
        }
        ?.build()

private fun String.normalizeToken(): String =
    trim().removePrefix("Bearer ").removePrefix("bearer ")

private fun String.redactCouponCallback(): String =
    replace(Regex("""code=[^&#]+"""), "code=<redacted>")
        .replace(Regex("""employeeNo=[^&#]+"""), "employeeNo=<redacted>")

private fun okhttp3.HttpUrl.redactCouponCallback(): String = toString().redactCouponCallback()
