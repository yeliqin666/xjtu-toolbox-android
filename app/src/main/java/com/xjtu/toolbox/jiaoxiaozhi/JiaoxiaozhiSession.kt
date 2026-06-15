package com.xjtu.toolbox.jiaoxiaozhi

import com.xjtu.toolbox.auth.CasSiteSession
import com.xjtu.toolbox.auth.XJTULogin
import com.xjtu.toolbox.util.PersistentCookieJar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

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
        val wxToken = assistant.wxToken.ifBlank { JiaoxiaozhiLogin.lastWxToken }
        val accessToken = assistant.accessToken.ifBlank { JiaoxiaozhiLogin.lastAccessToken }
        val expiresAt = assistant.accessTokenExpiresAt.takeIf { it > 0L }
            ?: JiaoxiaozhiLogin.lastAccessTokenExpiresAt
        val entryUrl = assistant.entryUrl
            .takeIf { it.contains("token=") }
            ?: JiaoxiaozhiLogin.lastEntryUrl.takeIf { it.contains("token=") }
            ?: assistant.entryUrl
        android.util.Log.d(
            "JiaoxiaozhiSession",
            "onLoginSuccess token=${accessToken.isNotBlank()} wx=${wxToken.isNotBlank()} entry=${entryUrl.contains("token=")}"
        )
        localToken["wx_token"] = wxToken
        localToken["access_token"] = accessToken
        localToken["expires_at"] = expiresAt.toString()
        localToken["entry_url"] = entryUrl
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
        System.currentTimeMillis() < expiresAt - 30_000L
    }

    override fun decorateRequest(builder: okhttp3.Request.Builder): okhttp3.Request.Builder {
        builder
            .header("Blade-Auth", "bearer ${localToken["access_token"].orEmpty()}")
            .header("User-Agent", USER_AGENT)
        val jar = apiClient.cookieJar as? PersistentCookieJar
        jar?.findCookieByName("CAS_AUTH_SESSION")?.let {
            builder.header("Cookie", "${it.name}=${it.value}")
        }
        return builder
    }

    fun hasAccessTokenForLog(): Boolean = localToken["access_token"].orEmpty().isNotBlank()

    fun hasCasAuthCookieForLog(): Boolean =
        (apiClient.cookieJar as? PersistentCookieJar)
            ?.findCookieByName("CAS_AUTH_SESSION") != null

    companion object {
        const val SITE_KEY = "jiaoxiaozhi"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 11; SAMSUNG SM-G973U) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/14.2 Chrome/87.0.4280.141 Mobile Safari/537.36"
    }
}
