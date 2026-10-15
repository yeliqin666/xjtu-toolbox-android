package com.xjtu.toolbox.auth

import com.xjtu.toolbox.webvpn.WebVpnUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** 教务系统（`jwxt.xjtu.edu.cn`）的站点会话 —— 全校课表与成绩报表两条路由共用的那一个站点。 */
class JwxtSession : CasSiteSession("jwxt", "教务系统", mustUseWebVpn = false) {
    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        JwxtLogin(session = client, visitorId = visitorId, cachedRsaKey = cachedRsaKey)

    override suspend fun validateLogin(): Boolean = withContext(Dispatchers.IO) {
        val resp = client.newCall(
            Request.Builder().url(VALIDATE_URL).get().build()
        ).execute()
        try {
            val finalUrl = resp.request.url.toString()
            // WebVPN 下被踢回 CAS 时 URL 里没有明文 "login.xjtu.edu.cn"，`!in` 会把失效会话误判为有效。
            resp.code == 200 && WebVpnUtil.isAtTargetSite(finalUrl, "jwxt.xjtu.edu.cn")
        } finally { resp.close() }
    }

    companion object {
        /** 登录入口页：会话有效时停在教务，失效时被跳到统一认证。 */
        private const val VALIDATE_URL = XJTULogin.JWXT_URL
    }
}
