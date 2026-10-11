package com.xjtu.toolbox.auth

import com.xjtu.toolbox.jiaocai.JiaocaiLogin
import okhttp3.OkHttpClient

/**
 * 教材中心（`jiaocai.lib.xjtu.edu.cn`）的站点会话 —— 从 `:app` 的 `auth/Sites.kt` 剪出来搬进
 * `:data`。类名与包路径一字未改 ⇒ `:app` 的 `AppLoginState` 里那处 `register(JiaocaiSession())`
 * 一行不用改。
 *
 * ## 为什么这个站点会被 jiaocai1 那一条路由用上
 *
 * 「教材全文库」（`jiaocai1.lib.xjtu.edu.cn`）**没有自己的登录器**：它借教材中心的会话。
 * `:app` 里 `AppRoute.Jiaocai1` / `Jiaocai1Reader` 的 `loginType` 都是 `LoginType.JIAOCAI`，
 * 导航层那句就是 `WithSite("jiaocai") { … }`（站点 key 与 [SITE_KEY] 同一个字符串），
 * 而 `Jiaocai1Api` 拿到的正是这个 `SiteSession`：会话由 `/front/user/portalLogin` 建立
 * （教材中心的**超星 SSO 落地页**里那一步桥接），未建立时全文库的接口不返数据而是返回一张
 * 跳转页 —— 那一段跟链重试的逻辑仍在 `:data` 的 `Jiaocai1Api.fetch` 里。
 *
 * 所以桌面端那条路由（`jiaocai1` 与 `jiaocai1_reader`）套 `DesktopSiteGate(JIAOCAI1_SITE_KEY)`
 * 建的**就是这一个**站点；`DesktopAuth.JIAOCAI1_SITE_KEY = JiaocaiSession.SITE_KEY`。
 *
 * ## 登录形态（与搬迁前逐字相同）
 *
 * `JiaocaiLogin.loginUrl = https://jiaocai.lib.xjtu.edu.cn/entry/login`：入口 → CAS
 * （`login.xjtu.edu.cn`）→ 超星 SSO（`portal.chaoxing.com`）→ `/entry/sso-login/cookie/sync`
 * 把教材平台的 cookie（`UID` / `_d` / `fid` / `vc3` / `uf` / `p_auth_token` / `SESSION`）种下来。
 *
 * `mustUseWebVpn = true`：原域名在校外要过 WebVPN 网关 —— 与搬迁前同一条口径。
 * ⚠️ 桌面端目前**永远按直连（`AccessMode.NORMAL`）走**（见 `DesktopAuth` 的「已知缺口」），
 * 所以校外登不上这个站点，与图书馆那条缺口同源（WebVPN 属宿主机能族）。
 */
class JiaocaiSession : CasSiteSession(SITE_KEY, "教材中心", mustUseWebVpn = true) {
    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        JiaocaiLogin(existingClient = client, visitorId = visitorId, cachedRsaKey = cachedRsaKey)

    companion object {
        /** 站点 key（与 `LoginType.JIAOCAI.siteKey()` 同一个字符串）。 */
        const val SITE_KEY = "jiaocai"
    }
}
