package com.xjtu.toolbox.auth

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * 加餐券系统（`egc.xjtu.edu.cn`）的站点会话。
 *
 * 与 [CouponLogin] 一起从 `:app` 的 `auth/Sites.kt` 剪出来搬进 `:data`（桌面端第 15 条真数据路由：
 * 桌面要自己登 `egc`、用 `:data` 的 `AppCouponSource` 查券与领券）。类名与包路径一字未改 ⇒
 * `:app` 的 `AppLoginState` / `AgentTool`（按 `CouponSession.SITE_KEY` 取会话）里那几处
 * **一行都不用改**。
 *
 * ## 登录入口（如实交代）
 *
 * **它有自己的独立登录页**，不借别的已搬站点的会话：`CouponLogin` 走
 * `login.xjtu.edu.cn/cas/oauth2.0/authorize?client_id=1596` → CAS 登录 → 回跳到
 * `org.xjtu.edu.cn` 开放平台（`/openplatform/oauth/authorizesw`）→ 落到
 * `egc.xjtu.edu.cn/page/cas/receiveCas.html` → 再 POST `/sso/login` 换 `auth_token`。
 * 所以本类在 `:desktop` 既进 `SESSION_SITE_KEYS`（登录页那一步顺手预热）、也进那条路由的
 * `DesktopSiteGate`（进门时确保会话）。
 *
 * 三处覆写与搬迁前逐字一致：
 *  - `onLoginSuccess`：把 [CouponLogin.authToken] 写进 `localToken["auth_token"]`；
 *  - `decorateRequest`：每个业务请求带上 `Authorization: <token>`；
 *  - `isAuthFailureResponse`：多认 JSON `"code":401`、文案里的「登录过期 / 未登录」。
 */
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