package com.xjtu.toolbox.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** 学生评教 QMS（畅评）qms.xjtu.edu.cn，本科生和研究生共用；公网可直连。 */
class QmsSession : CasSiteSession(SITE_KEY, "学生评教", mustUseWebVpn = false) {
    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        LandingCasLogin(LOGIN_URL, HOST, client, visitorId, cachedRsaKey)

    /** 会话失效时接口会被 302 回 SSO，跟着跳完落到网页上，所以接口拿到 HTML 也算失效。 */
    override fun isAuthFailureResponse(response: Response, bodyPreview: String?): Boolean =
        super.isAuthFailureResponse(response, bodyPreview) ||
            (response.request.url.encodedPath.startsWith("/api/") &&
                response.header("Content-Type")?.contains("text/html", ignoreCase = true) == true)

    override suspend fun validateLogin(): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(TASKS_API).header("Accept", "application/json").get().build()
        client.newCall(request).execute().use { resp ->
            resp.code == 200 && resp.request.url.host == HOST &&
                resp.body.string().trimStart().startsWith("{")
        }
    }

    companion object {
        const val SITE_KEY = "qms"
        const val HOST = "qms.xjtu.edu.cn"
        const val LOGIN_URL = "https://qms.xjtu.edu.cn/quality-client/my/tasks"
        const val TASKS_API = "https://qms.xjtu.edu.cn/api/quality/my/tasks?planStatus=IN_PROGRESS"
    }
}
