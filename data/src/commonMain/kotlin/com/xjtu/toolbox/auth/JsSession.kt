package com.xjtu.toolbox.auth

import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * 智慧教室运维平台（网页标题「智慧教室运维-服务端」，XJTUToolBox 里叫「教学服务平台」）。
 * 空闲教室页和屁岱查教室时的「实时状态」在用。凭据是 loginCas 换来的
 * `TOKEN-AUTH` 请求头（10 小时有效），不是 cookie，登录细节见 [JsLogin]。
 *
 * mustUseWebVpn=true：跟随全局模式，校外走 WebVPN。XJTUToolBox 的 JsSession 是固定直连，
 * 但 2026-09-22 真机实测校外直连 202.117.52.120:443 连接超时——这个域名只在校内可达。
 *
 * 与它的 [JsLogin] 一起从 `:app/auth/Sites.kt` 剪出来搬进 `:data`（桌面端第 10 条真数据路由：
 * 桌面要自己登智慧教室才能读空闲教室的实时状态，取数见 `:data` 的 `AppEmptyRoomSource`）。
 * 类名与包路径都没变 ⇒ `:app` 的 `AppLoginState` 里那处 `register(JsSession())` 一行不用改。
 *
 * 搬出来时**一行未改**：本类没有用 `Sites.kt` 那个文件私有的 `withIo { }`，
 * 校验不发请求（令牌寿命服务端直接告诉了我们），所以连 `withContext(Dispatchers.IO)` 都不需要。
 */
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

    /**
     * 不发请求：令牌寿命服务端直接告诉了我们，留 10 分钟余量，过了就重换。
     * 提前失效（服务端重启、被踢）由 [executeWithReAuth] 按 401 兜底重登。
     * 也不拿 getUserInfoForPersonal 探活——那个接口会把密码哈希一起回过来。
     */
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
