package com.xjtu.toolbox.auth

// `LmsLogin` 留在原包（`com.xjtu.toolbox.lms`，与 `:app` 里同一份），文件与 `AttendanceLogin`
// 一样放在 `auth/` 下 —— 所以这里要显式导入（`AttendanceSession` 导入 `AttendanceLogin` 同一条写法）。
import com.xjtu.toolbox.lms.LmsLogin
import okhttp3.OkHttpClient
import okhttp3.Response

/** 思源学堂（`lms.xjtu.edu.cn`）的站点会话。 */
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