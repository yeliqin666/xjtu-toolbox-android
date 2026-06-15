package com.xjtu.toolbox.jiaoxiaozhi

import com.xjtu.toolbox.auth.CasSiteSession
import com.xjtu.toolbox.auth.XJTULogin
import com.xjtu.toolbox.util.safeParseJsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

class JiaoxiaozhiSiteSession : CasSiteSession(
    siteKey = SITE_KEY,
    siteName = "交晓智",
    supportsWebVpn = false,
) {
    @Volatile private var authenticatedClient: OkHttpClient? = null

    val apiClient: OkHttpClient
        get() = authenticatedClient ?: client
    val refererUrl: String
        get() = localToken["entry_url"] ?: "https://assistant.xjtu.edu.cn/digitalPeople3/"

    override fun createLogin(
        client: OkHttpClient,
        visitorId: String?,
        cachedRsaKey: String?,
    ): XJTULogin = JiaoxiaozhiLogin(client, visitorId, cachedRsaKey)

    override fun onLoginSuccess(login: XJTULogin) {
        val assistant = login as JiaoxiaozhiLogin
        authenticatedClient = assistant.client
        localToken["wx_token"] = assistant.wxToken
        localToken["access_token"] = assistant.accessToken
        localToken["expires_at"] = assistant.accessTokenExpiresAt.toString()
        localToken["entry_url"] = assistant.entryUrl
    }

    /** 过渡期供旧 LoginScreen 把已完成的登录移交给 SessionManager，避免重复 CAS。 */
    fun adopt(login: JiaoxiaozhiLogin) {
        onLoginSuccess(login)
        hasLogin = true
    }

    override fun invalidateLogin() {
        super.invalidateLogin()
        authenticatedClient = null
    }

    override suspend fun validateLogin(): Boolean = withContext(Dispatchers.IO) {
        val expiresAt = localToken["expires_at"]?.toLongOrNull() ?: return@withContext false
        val accessToken = localToken["access_token"]?.takeIf { it.isNotBlank() } ?: return@withContext false
        val wxToken = localToken["wx_token"]?.takeIf { it.isNotBlank() } ?: return@withContext false
        if (System.currentTimeMillis() >= expiresAt - 30_000L) return@withContext false
        runCatching {
            apiClient.newCall(
                Request.Builder()
                    .url("${JiaoxiaozhiLogin.API_ROOT}/config/initParam")
                    .header("Blade-Auth", "bearer $accessToken")
                    .header("Origin", "https://assistant.xjtu.edu.cn")
                    .header("Referer", refererUrl)
                    .post(FormBody.Builder().add("wxToken", wxToken).build())
                    .build()
            ).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext false
                val text = resp.body?.string().orEmpty()
                text.safeParseJsonObject().get("code")?.asInt == 200
            }
        }.getOrDefault(false)
    }

    override fun decorateRequest(builder: okhttp3.Request.Builder): okhttp3.Request.Builder =
        builder.header("Blade-Auth", "bearer ${localToken["access_token"].orEmpty()}")

    companion object {
        const val SITE_KEY = "jiaoxiaozhi"
    }
}
