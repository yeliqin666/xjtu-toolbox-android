package com.xjtu.toolbox.auth

import okhttp3.OkHttpClient
import okhttp3.Response

/** 体育场馆预订系统（`202.117.17.144:8080`）的站点会话。 */
class VenueSession : CasSiteSession(SITE_KEY, "场馆预订", mustUseWebVpn = false) {
    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        VenueLogin(session = client, visitorId = visitorId, cachedRsaKey = cachedRsaKey)

    /**
     * 会话失效时，场馆站会把请求带去**自己的**登录页（`/web/login/pre.html`，用户名密码表单），
     * 不是统一认证页，基类认不出来，订单页就会把登录页当数据解析，报错里显示出一整页文字。
     */
    override fun isAuthFailureResponse(response: Response, bodyPreview: String?): Boolean =
        super.isAuthFailureResponse(response, bodyPreview) || response.request.url.encodedPath.startsWith("/web/login/")

    companion object {
        /** 站点 key（与其余站点同一个写法，桌面端的 `DesktopAuth.VENUE_SITE_KEY` 就用它）。 */
        const val SITE_KEY = "venue"
    }
}
