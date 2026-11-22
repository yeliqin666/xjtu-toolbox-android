package com.xjtu.toolbox.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * 西交学生智慧社区 ss.xjtu.edu.cn（活动报名，金智 EMAP）。公网可直连。
 * 应用首页 → ss 发起 CAS OAuth2（client_id=1707）→ org 中转 → pubauthapp 落地写 `_WEU` → 回应用首页。
 */
class SsSession : CasSiteSession(SITE_KEY, "智慧社区", mustUseWebVpn = false) {
    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        SsLogin(client, visitorId, cachedRsaKey)

    /** EMAP 会话失效时接口被 302 到 CAS 或回一张 HTML 页。 */
    override fun isAuthFailureResponse(response: Response, bodyPreview: String?): Boolean =
        super.isAuthFailureResponse(response, bodyPreview) ||
            (response.request.url.encodedPath.endsWith(".do") && response.request.url.encodedPath.contains("Controller") &&
                response.header("Content-Type")?.contains("text/html", ignoreCase = true) == true)

    override suspend fun validateLogin(): Boolean = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(APP_INDEX).get().build()).execute().use { resp ->
            resp.code == 200 && resp.request.url.host == HOST
        }
    }

    private class SsLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?) :
        XJTULogin(LOGIN_URL, client, visitorId, cachedRsaKey) {
        override fun postLogin(response: Response) {
            client.newCall(Request.Builder().url(APP_INDEX).get().build()).execute().use { resp ->
                val body = resp.body.string()
                if (XJTULogin.isSafetyVerifyPage(body)) throw SafetyVerifyRequiredException(resp, body)
                if (resp.request.url.host != HOST) throw IOException("智慧社区登录没有完成，请重新登录")
            }
        }
    }

    companion object {
        const val SITE_KEY = "ss"
        const val HOST = "ss.xjtu.edu.cn"
        const val APP_BASE = "https://ss.xjtu.edu.cn/xsfw/sys/swmsyxsfzapp"
        const val APP_INDEX = "$APP_BASE/*default/index.do"
        /**
         * 从应用首页进，让 ss 自己发起 OAuth（client_id=1707）。直接从 CAS authorize 进的话，
         * ss 收到不是自己发起的 code 会重走一遍授权，登完再跳回最初那个用过的 code → 404。
         */
        const val LOGIN_URL = APP_INDEX
    }
}
