package com.xjtu.toolbox.auth

import com.xjtu.toolbox.webvpn.WebVpnUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 研究生管理信息系统 gmis.xjtu.edu.cn。研究生评教要从这里取教材、授课语言、学位课信息来填问卷。
 *
 * 与 [GsteSession] 一起从 `:app/auth/Sites.kt` 剪出来搬进 `:data`（类名与包路径一字未改）。
 * 登录器 [LandingCasLogin] 两个站点共用，单列在它自己的文件里；`withIo { }` 同样换成
 * `withContext(Dispatchers.IO)`。
 */
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
