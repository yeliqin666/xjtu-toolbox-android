package com.xjtu.toolbox.auth

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** 加餐券系统（`egc.xjtu.edu.cn`）的站点会话。 */
class CouponSession : CasSiteSession(SITE_KEY, "餐券系统", mustUseWebVpn = false) {
    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        CouponLogin(session = client, visitorId = visitorId, cachedRsaKey = cachedRsaKey)

    override fun onLoginSuccess(login: XJTULogin) {
        (login as? CouponLogin)?.authToken?.takeIf { it.isNotBlank() }?.let {
            localToken["auth_token"] = it
        }
    }

    override fun decorateRequest(builder: Request.Builder): Request.Builder {
        localToken["auth_token"]?.let { builder.header("Authorization", it) }
        return builder
    }

    override fun isAuthFailureResponse(response: Response, bodyPreview: String?): Boolean {
        if (super.isAuthFailureResponse(response, bodyPreview)) return true
        val body = bodyPreview ?: return false
        return """"code"\s*:\s*401""".toRegex().containsMatchIn(body) ||
            body.contains("登录过期") || body.contains("未登录")
    }

    companion object {
        /** 站点 key（与其余站点同一个写法，桌面端的 `DesktopAuth.COUPON_SITE_KEY` 就用它）。 */
        const val SITE_KEY = "coupon"
    }
}