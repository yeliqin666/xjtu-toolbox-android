package com.xjtu.toolbox.auth

import com.xjtu.toolbox.webvpn.WebVpnUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 研究生评教 gste.xjtu.edu.cn。只在校园网内可达，校外走 WebVPN。
 * 身份固定选研究生：本站只服务研究生，同时有本科身份的账号也要登研究生那一支。
 *
 * 从 `:app/auth/Sites.kt` 剪出来搬进 `:data`（类名与包路径一字未改 ⇒ `:app` 的 `AppLoginState`
 * 里那两处 `register(GsteSession())` 一行不用改），因为桌面端的研究生评教路由要它自己登录
 * （`:data` 的 `GraduateJudgeApi` + `GraduateJudgeSource`）。
 *
 * 搬迁时被替换的写法只有一处：`Sites.kt` 那个私有的 `withIo { }`（它留在那边给其余站点用）
 * 换成本文件里的 `withContext(Dispatchers.IO)` —— 与 [JwxtSession] 搬迁时同一条做法。
 * 登录器 [LandingCasLogin] 与 [GmisSession] 共用，因此单列在它自己的文件里。
 */
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
