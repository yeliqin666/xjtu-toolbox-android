package com.xjtu.toolbox.auth

import com.xjtu.toolbox.webvpn.WebVpnUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 图书馆座位系统（`rg.lib.xjtu.edu.cn:8086`）的站点会话。
 *
 * ## 为什么在 `:data` 而不是留在 `:app` 的 `Sites.kt` 里
 *
 * 它是「`Sites.kt` 里的站点逐个接上 `:data`」的第一个：类体只用到 [CasSiteSession] +
 * [LibraryLogin] + [WebVpnUtil]，这三样都已在 `:data`，于是它**零改动**就能编过 ——
 * 而桌面端正需要它才能「自己登录」（`docs/desktop-port-plan.md` Stage A 的验收：
 * 用自己登录的会话跑通图书馆那条竖切）。
 *
 * 类名与包路径都没变（`com.xjtu.toolbox.auth.LibrarySession`）⇒ `:app` 侧
 * `register(com.xjtu.toolbox.auth.LibrarySession())` 一行不用改。
 *
 * `createLogin` 把会话管家缓存的 `cachedRsaKey` 一路传到 [LibraryLogin]（与其余站点同型）——
 * 搬迁前这里是漏传的，见 [LibraryLogin] 的 KDoc。
 *
 * `mustUseWebVpn = true`：座位系统的端口对公网不开放，校外必须经网关，故跟随全局
 * [AccessMode] 判定；判定结果由 [SessionManager.backendFor] 决定绑定哪个 backend。
 */
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
