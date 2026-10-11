package com.xjtu.toolbox.auth

import com.xjtu.toolbox.iclassface.IclassfaceLogin
import okhttp3.OkHttpClient

/**
 * 人脸识别签到（`iclassface.xjtu.edu.cn`）的站点会话。
 *
 * 与 [IclassfaceLogin] 一起从 `:app` 的 `auth/Sites.kt` 剪出来搬进 `:data`（桌面端第 19 条真数据
 * 路由：桌面要自己登 `iclassface`、自己查今天的刷卡与人脸记录）。类名与包路径一字未改 ⇒ `:app` 的
 * `AppLoginState` / `HeadlessSessions` 里那两处 `register(IclassfaceSession())` 一行不用改。
 *
 * ## 登录形态（如实交代）
 *
 * **它有自己的独立登录链**（[IclassfaceLogin]：CAS OAuth2 `client_id=1543` →
 * `org.xjtu.edu.cn` 开放平台的 `authorizesw` 中转 → 回落到 `/oauth/callback/xjtu_new`），
 * 不借别的站点会话。落地后**会话凭据就是 `PHPSESSID` cookie**，没有任何额外令牌要提取，
 * 所以这个站点类只做一件事：创建 [IclassfaceLogin] —— 没有 `onLoginSuccess` /
 * `decorateRequest` 覆写（与搬迁前逐字一致）。
 *
 * `mustUseWebVpn = true`：`iclassface.xjtu.edu.cn` 不开放校外访问，校外要走 WebVPN 网关。
 * 考勤屏上的「快速考勤流水」二级入口就是它（`:core` 的 `IclassfaceScreen`）。
 */
class IclassfaceSession : CasSiteSession("iclassface", "快速考勤流水", mustUseWebVpn = true) {
    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        IclassfaceLogin(session = client, visitorId = visitorId, cachedRsaKey = cachedRsaKey)

    companion object {
        /** 站点 key（与其余站点同一个写法，桌面端的 `DesktopAuth.ICLASSFACE_SITE_KEY` 就用它）。 */
        const val SITE_KEY = "iclassface"
    }
}
