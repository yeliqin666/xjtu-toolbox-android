package com.xjtu.toolbox.auth

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * 一网通办（ywtb.xjtu.edu.cn）：消息收纳那四路（消息 / 事务中心 / 预约 / 校车）共用的站点，
 * 它们只认这里换来的 `x-id-token`。登录细节见 [YwtbLogin]。
 *
 * mustUseWebVpn=false：一网通办和挂在它令牌上的消息、事务中心、预约、校车都能公网直连（学校超级 App
 * 校外也是直连这几个域名）。绕 WebVPN 只会多一跳，而且服务端看到的来源 IP 变成网关的校内地址。
 *
 * 与它的 [YwtbLogin] 一起从 `:app/auth/Sites.kt` 剪出来搬进 `:data`（桌面端第 12 条真数据路由：
 * 消息收纳 —— 桌面要自己登一网通办、自己去那四路取数，取数见 `:data` 的 `AppInboxSource`）。
 * 类名与包路径都没变 ⇒ `:app` 的 `AppLoginState` 里那处 `register(YwtbSession())` 一行不用改；
 * 顺手补一个 `companion object { const val SITE_KEY }`（与 `JsSession`/`VenueSession` 同一个写法，
 * 桌面端用它注册）。
 *
 * 搬出来时**只改了一行**：登录成功后那颗一网通办令牌原来直接写给 `:app` 的 `CampusProbe`
 * （网络判定，绑 `ConnectivityManager`，属宿主能力、没跟着会话内核搬），现在改成交给宿主注入的
 * [SessionManager.onYwtbToken]（`:app` 在 `AppLoginState` / `HeadlessSessions` 里各挂一行，
 * 行为逐字不变；桌面端没有那份缓存，留空即可 —— 与 [SessionManager.onAccountSwitched] 同一条缝）。
 */
class YwtbSession : CasSiteSession(SITE_KEY, "一网通办", mustUseWebVpn = false) {
    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        YwtbLogin(session = client, visitorId = visitorId, cachedRsaKey = cachedRsaKey)

    override fun onLoginSuccess(login: XJTULogin) {
        (login as? YwtbLogin)?.idToken?.takeIf { it.isNotEmpty() }?.let {
            localToken["id_token"] = it
            manager?.onYwtbToken?.invoke(it)
        }
    }

    override fun decorateRequest(builder: Request.Builder): Request.Builder {
        localToken["id_token"]?.let { builder.header("x-id-token", it) }
        return builder
    }

    override fun isAuthFailureResponse(response: Response, bodyPreview: String?): Boolean {
        if (super.isAuthFailureResponse(response, bodyPreview)) return true
        val body = bodyPreview ?: return false
        return """"code"\s*:\s*401""".toRegex().containsMatchIn(body) ||
            body.contains("未登录") || body.contains("登录过期")
    }

    companion object {
        const val SITE_KEY = "ywtb"
    }
}
