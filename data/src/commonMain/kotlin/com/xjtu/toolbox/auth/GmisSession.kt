package com.xjtu.toolbox.auth

import com.xjtu.toolbox.webvpn.WebVpnUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** 研究生管理信息系统 gmis.xjtu.edu.cn。 */
class GmisSession : CasSiteSession("gmis", "研究生管理信息系统", mustUseWebVpn = true) {
    override val accountType: XJTULogin.AccountType get() = XJTULogin.AccountType.POSTGRADUATE

    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        LandingCasLogin(LOGIN_URL, "gmis.xjtu.edu.cn", client, visitorId, cachedRsaKey)

    override suspend fun validateLogin(): Boolean = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(SCORE_URL).get().build()).execute().use { resp ->
            resp.code == 200 &&
                WebVpnUtil.isAtTargetSite(resp.request.url.toString(), "gmis.xjtu.edu.cn")
        }
    }

    companion object {
        const val LOGIN_URL = "https://org.xjtu.edu.cn/openplatform/oauth/authorize?appId=1036&state=abcd1234" +
            "&redirectUri=http://gmis.xjtu.edu.cn/pyxx/sso/login&responseType=code&scope=user_info"
        const val SCORE_URL = "https://gmis.xjtu.edu.cn/pyxx/pygl/xscjcx/index"
    }
}
