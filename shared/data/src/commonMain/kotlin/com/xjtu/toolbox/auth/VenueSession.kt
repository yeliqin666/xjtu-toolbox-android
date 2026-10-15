package com.xjtu.toolbox.auth

import okhttp3.OkHttpClient

/** 体育场馆预订系统（`202.117.17.144:8080`）的站点会话。 */
class VenueSession : CasSiteSession(SITE_KEY, "场馆预订", mustUseWebVpn = false) {
    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        VenueLogin(session = client, visitorId = visitorId, cachedRsaKey = cachedRsaKey)

    companion object {
        /** 站点 key（与其余站点同一个写法，桌面端的 `DesktopAuth.VENUE_SITE_KEY` 就用它）。 */
        const val SITE_KEY = "venue"
    }
}
