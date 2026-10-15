package com.xjtu.toolbox.auth

import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** 智慧教室运维平台（网页标题「智慧教室运维-服务端」，XJTUToolBox 里叫「教学服务平台」）。 */
class JsSession : CasSiteSession(SITE_KEY, "智慧教室平台", mustUseWebVpn = true) {
    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        JsLogin(session = client, visitorId = visitorId, cachedRsaKey = cachedRsaKey)

    override fun onLoginSuccess(login: XJTULogin) {
        val grant = (login as? JsLogin)?.grantOrNull
            ?: throw IOException("智慧教室登录失败：未取得令牌")
        localToken[TOKEN_KEY] = grant.token
        localToken[EXPIRES_KEY] = (grant.obtainedAtMs + grant.timeoutSeconds * 1000L).toString()
    }

    override fun decorateRequest(builder: Request.Builder): Request.Builder {
        localToken[TOKEN_KEY]?.let { builder.header(JsLogin.TOKEN_HEADER, it) }
        builder.header(
            JsLogin.SYSTEM_HEADER,
            JsLogin.SYSTEM_VALUE,
        )
        return builder
    }

    /** 不发请求：令牌寿命服务端直接告诉了我们，留 10 分钟余量，过了就重换。 */
    override suspend fun validateLogin(): Boolean {
        if (localToken[TOKEN_KEY].isNullOrBlank()) return false
        val expiresAt = localToken[EXPIRES_KEY]?.toLongOrNull() ?: return false
        return System.currentTimeMillis() < expiresAt - 10 * 60_000L
    }

    override fun isAuthFailureResponse(response: Response, bodyPreview: String?): Boolean {
        if (super.isAuthFailureResponse(response, bodyPreview)) return true
        val body = bodyPreview ?: return false
        return """"code"\s*:\s*401""".toRegex().containsMatchIn(body) ||
            (body.contains("token", ignoreCase = true) && body.contains("过期"))
    }

    companion object {
        const val SITE_KEY = "js"
        private const val TOKEN_KEY = "token_auth"
        private const val EXPIRES_KEY = "token_expires_at"
    }
}
