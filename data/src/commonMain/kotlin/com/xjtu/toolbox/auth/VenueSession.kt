package com.xjtu.toolbox.auth

import okhttp3.OkHttpClient

/**
 * 体育场馆预订系统（`202.117.17.144:8080`）的站点会话。
 *
 * 与 [VenueLogin] 一起从 `:app` 的 `auth/Sites.kt` 剪出来搬进 `:data`（桌面端第 11 条真数据路由：
 * 桌面要自己登场馆站、用 `:data` 的 `AppVenueSource` 取场馆与时段）。类名与包路径一字未改 ⇒
 * `:app` 的 `AppLoginState` 里那处 `register(VenueSession())` 一行不用改。
 *
 * 与 `JwxtSession` / `CampusCardSession` 那几次同一手法，但这一份**没有要多写的地方**：
 * `Sites.kt` 里那个类本来就只有这三行（一个 `createLogin`），既没有 `validateLogin`
 * （用基类默认的「看 `hasLogin`」），也没有 `onLoginSuccess` / `decorateRequest` ——
 * 会话凭据就是 8080 端口上的 SESSION cookie，由 cookie 罐自己带着走。
 *
 * `mustUseWebVpn = false`：永远直连原域名。场馆站不在 WebVPN 网关的覆盖清单里
 * （它的 OAuth 入口在 `org.xjtu.edu.cn`，业务在 `202.117.17.144:8080`）。
 */
class VenueSession : CasSiteSession(SITE_KEY, "场馆预订", mustUseWebVpn = false) {
    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        VenueLogin(session = client, visitorId = visitorId, cachedRsaKey = cachedRsaKey)

    companion object {
        /** 站点 key（与其余站点同一个写法，桌面端的 `DesktopAuth.VENUE_SITE_KEY` 就用它）。 */
        const val SITE_KEY = "venue"
    }
}
