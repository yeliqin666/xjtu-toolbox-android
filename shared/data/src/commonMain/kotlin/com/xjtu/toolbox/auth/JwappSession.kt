package com.xjtu.toolbox.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** 移动教务（`jwapp.xjtu.edu.cn`）的站点会话。 */
class JwappSession : CasSiteSession("jwapp", "移动教务", mustUseWebVpn = false) {
    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        JwappLogin(session = client, visitorId = visitorId, cachedRsaKey = cachedRsaKey)

    override fun onLoginSuccess(login: XJTULogin) {
        (login as? JwappLogin)?.authToken?.takeIf { it.isNotEmpty() }?.let {
            localToken["auth_token"] = it
        }
    }

    override fun decorateRequest(builder: Request.Builder): Request.Builder {
        localToken["auth_token"]?.let { builder.header("Authorization", it) }
        return builder
    }

    override suspend fun validateLogin(): Boolean = withContext(Dispatchers.IO) {
        val token = localToken["auth_token"] ?: return@withContext false
        val resp = client.newCall(
            Request.Builder()
                .url("https://jwapp.xjtu.edu.cn/api/biz/v410/common/school/time")
                .header("Authorization", token)
                .get().build()
        ).execute()
        try {
            resp.code == 200
        } finally { resp.close() }
    }

    override fun isAuthFailureResponse(response: Response, bodyPreview: String?): Boolean {
        if (super.isAuthFailureResponse(response, bodyPreview)) return true
        val body = bodyPreview ?: return false
        // 分项成绩接口对「这门课没有细则」也会给 JSON code=401，不能单凭数字当掉登录。
        return body.contains("Authentication error", ignoreCase = true) ||
            (body.contains("token", ignoreCase = true) && body.contains("过期")) ||
            (""""code"\s*:\s*401""".toRegex().containsMatchIn(body) &&
                (body.contains("authentication", ignoreCase = true) ||
                    body.contains("未登录") ||
                    body.contains("过期")))
    }

    companion object {
        /** 站点 key（与其余站点同一个写法，桌面端的 `DesktopAuth.JWAPP_SITE_KEY` 就用它）。 */
        const val SITE_KEY = "jwapp"
    }
}
