package com.xjtu.toolbox.auth

// `LmsLogin` 留在原包（`com.xjtu.toolbox.lms`，与 `:app` 里同一份），文件与 `AttendanceLogin`
// 一样放在 `auth/` 下 —— 所以这里要显式导入（`AttendanceSession` 导入 `AttendanceLogin` 同一条写法）。
import com.xjtu.toolbox.lms.LmsLogin
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * 思源学堂（`lms.xjtu.edu.cn`）的站点会话 —— 从 `:app` 的 `auth/Sites.kt` 剪出来搬进 `:data`
 * （桌面端第 18 条真数据路由：桌面要自己登 lms、用 `:data` 的 `AppLmsSource` 取课程/活动/详情）。
 * 类名与包路径一字未改 ⇒ `:app` 的 `AppLoginState` / `HeadlessSessions` 里那两处
 * `register(LmsSession())` 一行不用改。
 *
 * ## 登录入口（如实交代）
 *
 * **它借统一认证（CAS）的会话，不借别的已搬站点的会话**：`LmsLogin.loginUrl` 就是
 * `https://lms.xjtu.edu.cn`（登录器先摸 lms 根域 → 302 到 `login.xjtu.edu.cn/cas` →
 * 凭据表单 → 回跳 lms 种 session cookie），是「直接 CAS 回跳」那一型 —— 与教务（jwxt）同型，
 * 与 dzpz/coupon 那种「自带独立登录页」不同。所以本类在 `:desktop` 既进 `SESSION_SITE_KEYS`
 * （登录页那一步顺手预热），也进那条路由的 `DesktopSiteGate`（进门时确保会话）。
 *
 * `mustUseWebVpn = false`：永远直连原域名（与 `:app` 里那一条逐字相同）。护网结束后
 * lms.xjtu.edu.cn 已放开公网直连，校外通常也可用；若学校重新收紧，需连校园官方 VPN
 * 或回到校园网。
 *
 * 唯一一处覆写与搬迁前逐字一致：`isAuthFailureResponse` —— 活动已结束时
 * `/api/uploads/{id}/blob` 也是 403 + 「没有权限」，那是业务拒绝不是掉登录，不能按 403 重登空转。
 */
class LmsSession : CasSiteSession(SITE_KEY, "思源学堂", mustUseWebVpn = false) {
    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        LmsLogin(session = client, visitorId = visitorId, cachedRsaKey = cachedRsaKey)

    override fun isAuthFailureResponse(response: Response, bodyPreview: String?): Boolean {
        // 活动已结束时 /api/uploads/{id}/blob 也是 403 + 「没有权限」。
        // 这是业务拒绝，不是掉登录，按 403 重登只会空转。
        if (response.code == 403 && bodyPreview?.contains("没有权限") == true) return false
        if (response.code == 401 || XJTULogin.isCasLoginUrl(response.request.url)) return true
        if (bodyPreview != null) return XJTULogin.isAuthFailureResponse(bodyPreview)
        return false
    }

    companion object {
        /** 站点 key（与其余站点同一个写法，桌面端的 `DesktopAuth.LMS_SITE_KEY` 就用它）。 */
        const val SITE_KEY = "lms"
    }
}