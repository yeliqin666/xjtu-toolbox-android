package com.xjtu.toolbox.auth

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** 一网通办（ywtb.xjtu.edu.cn）：消息收纳那四路（消息 / 事务中心 / 预约 / 校车）共用的站点，它们只认这里换来的 `x-id-token`。 */
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
