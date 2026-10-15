package com.xjtu.toolbox.auth

import com.xjtu.toolbox.jiaocai.JiaocaiLogin
import okhttp3.OkHttpClient

/** 教材中心（`jiaocai.lib.xjtu.edu.cn`）的站点会话。 */
class JiaocaiSession : CasSiteSession(SITE_KEY, "教材中心", mustUseWebVpn = true) {
    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        JiaocaiLogin(existingClient = client, visitorId = visitorId, cachedRsaKey = cachedRsaKey)

    companion object {
        /** 站点 key（与 `LoginType.JIAOCAI.siteKey()` 同一个字符串）。 */
        const val SITE_KEY = "jiaocai"
    }
}
