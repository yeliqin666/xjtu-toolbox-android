package com.xjtu.toolbox.auth

import com.xjtu.toolbox.webvpn.WebVpnUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** 研究生评教 gste.xjtu.edu.cn。 */
class GsteSession : CasSiteSession("gste", "研究生评教", mustUseWebVpn = true) {
    override val accountType: XJTULogin.AccountType get() = XJTULogin.AccountType.POSTGRADUATE

    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        LandingCasLogin(LOGIN_URL, "gste.xjtu.edu.cn", client, visitorId, cachedRsaKey)

    override suspend fun validateLogin(): Boolean = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(LIST_URL).get().build()).execute().use { resp ->
            resp.code == 200 &&
                WebVpnUtil.isAtTargetSite(resp.request.url.toString(), "gste.xjtu.edu.cn") &&
                resp.body.string().trimStart().startsWith("[")
        }
    }

    companion object {
        const val LOGIN_URL = "https://cas.xjtu.edu.cn/login?TARGET=http%3A%2F%2Fgste.xjtu.edu.cn%2Flogin.do"
        const val LIST_URL = "http://gste.xjtu.edu.cn/app/sshd4Stu/list.do"
    }
}
