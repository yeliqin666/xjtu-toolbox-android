package com.xjtu.toolbox.auth

import com.xjtu.toolbox.webvpn.WebVpnUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 教务系统（`jwxt.xjtu.edu.cn`）的站点会话 —— 全校课表与成绩报表两条路由共用的那一个站点。
 *
 * 与 [JwxtLogin] 一起从 `:app` 的 `auth/Sites.kt` 剪出来搬进 `:data`（类名与包路径一字未改 ⇒
 * `:app` 的 `AppLoginState` / `HeadlessSessions` 里那两处 `register(JwxtSession())` 一行不用改）。
 *
 * 搬迁时被替换的写法只有一处：原来用的 `Sites.kt` 里那个私有的 `withIo { }`（它留在那边给
 * 其余站点用）换成本文件里的 `withContext(Dispatchers.IO)` —— `:data` 的 commonMain 里
 * `Dispatchers.IO` 是可用的（`SiteSession` 自己就在用）。
 *
 * `mustUseWebVpn = false`：永远直连原域名。护网结束后 jwxt 已放开公网直连，校外通常也可用，
 * 但这取决于学校当前的网络策略，不是本字段保证的行为 —— 若域名被重新收紧仅限校内，
 * 校外需连接校园官方 VPN 或回到校园网，App 内置 WebVPN 代理对本站点不生效。
 */
class JwxtSession : CasSiteSession("jwxt", "教务系统", mustUseWebVpn = false) {
    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        JwxtLogin(session = client, visitorId = visitorId, cachedRsaKey = cachedRsaKey)

    override suspend fun validateLogin(): Boolean = withContext(Dispatchers.IO) {
        val resp = client.newCall(
            Request.Builder().url(VALIDATE_URL).get().build()
        ).execute()
        try {
            val finalUrl = resp.request.url.toString()
            // WebVPN 下被踢回 CAS 时 URL 是 webvpn.xjtu.edu.cn/https/{加密login域名}/cas/login…，
            // 明文 "login.xjtu.edu.cn" 不出现，`!in` 反而成立 → 失效会话被误判为"仍然有效"，
            // 于是跳过重登，后续接口拿到的是登录页。isAtTargetSite 兼容直连/WebVPN 两种模式。
            resp.code == 200 && WebVpnUtil.isAtTargetSite(finalUrl, "jwxt.xjtu.edu.cn")
        } finally { resp.close() }
    }

    companion object {
        /** 登录入口页：会话有效时停在教务，失效时被跳到统一认证。 */
        private const val VALIDATE_URL = XJTULogin.JWXT_URL
    }
}
