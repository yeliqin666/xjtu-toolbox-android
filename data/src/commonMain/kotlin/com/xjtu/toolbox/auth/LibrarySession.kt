package com.xjtu.toolbox.auth

import com.xjtu.toolbox.webvpn.WebVpnUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** 图书馆座位系统（`rg.lib.xjtu.edu.cn:8086`）的站点会话。 */
class LibrarySession : CasSiteSession("library", "图书馆", mustUseWebVpn = true) {
    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        LibraryLogin(existingClient = client, visitorId = visitorId, cachedRsaKey = cachedRsaKey)

    override suspend fun validateLogin(): Boolean = withContext(Dispatchers.IO) {
        val resp = client.newCall(
            Request.Builder().url(SEAT_HOME_URL).get().build()
        ).execute()
        try {
            val finalUrl = resp.request.url.toString()
            // 同 JwxtSession：WebVPN 下明文域名判断会把失效会话误判为有效。
            resp.code in 200..399 &&
                WebVpnUtil.isAtTargetSite(finalUrl, SEAT_HOST)
        } finally { resp.close() }
    }

    companion object {
        /** 座位系统根域名（`LibraryPages.BASE_URL` 的 host）。 */
        const val SEAT_HOST = "rg.lib.xjtu.edu.cn"

        /** 探活地址：会话有效时停在座位页，失效时被跳到统一认证。 */
        const val SEAT_HOME_URL = "http://$SEAT_HOST:8086/seat/"
    }
}
