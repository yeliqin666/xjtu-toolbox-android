package com.xjtu.toolbox.jiaoxiaozhi

import com.xjtu.toolbox.auth.XJTULogin
import com.xjtu.toolbox.util.safeParseJsonObject
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class JiaoxiaozhiLogin(
    session: OkHttpClient? = null,
    visitorId: String? = null,
    cachedRsaKey: String? = null,
) : XJTULogin(LOGIN_URL, session, visitorId, cachedRsaKey) {

    var wxToken: String = ""
        private set
    var accessToken: String = ""
        private set
    var accessTokenExpiresAt: Long = 0L
        private set
    var entryUrl: String = "https://assistant.xjtu.edu.cn/digitalPeople3/"
        private set

    override fun postLogin(response: Response) {
        val callback = response.request.url
        if ("assistant.xjtu.edu.cn" !in callback.host) {
            throw RuntimeException("交晓智登录回调异常")
        }
        entryUrl = callback.toString()
        wxToken = callback.queryParameter("token").orEmpty()
        if (wxToken.isBlank()) throw RuntimeException("交晓智登录未返回入口令牌")
        exchangeBladeToken()
        initializeSession()
    }

    private fun exchangeBladeToken() {
        val request = Request.Builder()
            .url(OAUTH_URL)
            .header("Authorization", XBMDCAS_BASIC_AUTH)
            .header("Tenant-Id", "000000")
            .header("Role-Id", "1664528453134516226")
            .header("Origin", "https://assistant.xjtu.edu.cn")
            .header("Referer", entryUrl)
            .post(
                FormBody.Builder()
                    .add("grant_type", "XBMDCas")
                    .add("scope", "all")
                    .add("token", wxToken)
                    .add("customerServiceConfId", CUSTOMER_ID)
                    .build()
            )
            .build()
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("交晓智令牌交换失败：HTTP ${resp.code}")
            val root = text.safeParseJsonObject()
            accessToken = root.get("access_token")?.asString.orEmpty()
            if (accessToken.isBlank()) {
                val message = root.get("error_description")?.asString
                    ?: root.get("msg")?.asString
                    ?: "未返回 access_token"
                throw RuntimeException("交晓智令牌交换失败：$message")
            }
            val expiresIn = root.get("expires_in")?.asLong ?: 3600L
            accessTokenExpiresAt = System.currentTimeMillis() + expiresIn * 1000L
        }
    }

    private fun initializeSession() {
        val request = Request.Builder()
            .url(INIT_URL)
            .header("Blade-Auth", "bearer $accessToken")
            .header("Origin", "https://assistant.xjtu.edu.cn")
            .header("Referer", entryUrl)
            .post(FormBody.Builder().add("wxToken", wxToken).build())
            .build()
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val ok = runCatching {
                resp.isSuccessful && text.safeParseJsonObject().get("code")?.asInt == 200
            }.getOrDefault(false)
            if (!ok) throw RuntimeException("交晓智会话初始化失败")
        }
    }

    fun isAccessTokenValid(): Boolean =
        accessToken.isNotBlank() && System.currentTimeMillis() < accessTokenExpiresAt - 30_000L

    companion object {
        const val CUSTOMER_ID = "80"
        const val SERVICE_URL =
            "https://assistant.xjtu.edu.cn/blade-auth2/oauth/casTokenXBMD?chatId=$CUSTOMER_ID"
        const val LOGIN_URL =
            "https://login.xjtu.edu.cn/cas/login?service=https%3A%2F%2Fassistant.xjtu.edu.cn%2Fblade-auth2%2Foauth%2FcasTokenXBMD%3FchatId%3D80"
        const val API_ROOT = "https://assistant.xjtu.edu.cn/virtualhuman2/serverApi"
        private const val OAUTH_URL = "https://assistant.xjtu.edu.cn/blade-auth2/oauth/token"
        private const val INIT_URL = "$API_ROOT/config/initParam"
        private const val XBMDCAS_BASIC_AUTH = "Basic WEJNRENhczpYQk1EQ2FzX3NlY3JldA=="
    }
}
