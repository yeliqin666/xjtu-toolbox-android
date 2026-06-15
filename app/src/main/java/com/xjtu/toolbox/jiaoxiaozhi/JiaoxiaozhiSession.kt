package com.xjtu.toolbox.jiaoxiaozhi

import com.xjtu.toolbox.auth.CasSiteSession
import com.xjtu.toolbox.auth.XJTULogin
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

    override suspend fun validateLogin(): Boolean {
        val expiresAt = localToken["expires_at"]?.toLongOrNull() ?: return false
        return localToken["access_token"].isNullOrBlank().not() &&
            System.currentTimeMillis() < expiresAt - 30_000L
    }

    override fun decorateRequest(builder: okhttp3.Request.Builder): okhttp3.Request.Builder =
        builder.header("Blade-Auth", "bearer ${localToken["access_token"].orEmpty()}")

    companion object {
        const val SITE_KEY = "jiaoxiaozhi"
    }
}
