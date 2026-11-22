package com.xjtu.toolbox.auth

// `LmsLogin` 留在原包（`com.xjtu.toolbox.lms`，与 `:app` 里同一份），文件与 `AttendanceLogin`
// 一样放在 `auth/` 下 —— 所以这里要显式导入（`AttendanceSession` 导入 `AttendanceLogin` 同一条写法）。
import com.xjtu.toolbox.lms.LmsLogin
import com.xjtu.toolbox.platform.Log
import com.xjtu.toolbox.webvpn.WebVpnUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/** 思源学堂（`lms.xjtu.edu.cn`）的站点会话。 */
class LmsSession : CasSiteSession(SITE_KEY, "思源学堂", mustUseWebVpn = false) {

    /** 下一次 [runLogin] 从哪个入口进。平时是学堂首页，只有 [ensureWebSso] 补统一认证时临时换掉。 */
    @Volatile private var loginEntry = LmsLogin.LMS_LOGIN_URL

    override fun createLogin(client: OkHttpClient, visitorId: String?, cachedRsaKey: String?): XJTULogin =
        LmsLogin(session = client, visitorId = visitorId, cachedRsaKey = cachedRsaKey, entryUrl = loginEntry)

    /**
     * 打开学堂网页（`lms-h5` 的作业提交页）之前调一次，让内置浏览器到那儿能直接免密进去。
     *
     * 学堂 h5 不认 App 手里 `lms.xjtu.edu.cn` 的 `session` cookie：它自己的登录态在 localStorage 里，
     * 没有就走 `identity1.xjtu.edu.cn` → `login.xjtu.edu.cn` 换一张票。这一路免密靠的是统一认证的 TGC
     * 和 identity1 的会话 cookie，App 把它们注进 WebView。可学堂会话比统一认证的登录态活得久：
     * 学堂接口还能用、App 从不重登，TGC 却早已在服务端过期，注进去也没用——网页里就要求重新输密码。
     *
     * 所以先探一次 [LmsLogin.CAS_LOGIN_URL]：带票回到学堂 = 统一认证还活着（顺带续了期）；
     * 停在统一认证页 = 过期了，就用存下的密码从这个入口完整登一次，把 TGC 和 identity1 的 cookie 补新。
     * 用户点进来的，所以按前台登录走（可能弹短信验证）。探活网络出错时不硬登：交给网页自己处理。
     */
    @Throws(IOException::class)
    suspend fun ensureWebSso(username: String, password: String) {
        ensureLogin(username, password, userInitiated = true)
        if (withContext(Dispatchers.IO) { webSsoAlive() }) return
        Log.d(TAG, "web SSO expired, re-login via CAS entry")
        loginEntry = LmsLogin.CAS_LOGIN_URL
        try {
            ensureLogin(username, password, force = true, userInitiated = true)
        } finally {
            loginEntry = LmsLogin.LMS_LOGIN_URL
        }
    }

    private fun webSsoAlive(): Boolean = try {
        client.newCall(Request.Builder().url(LmsLogin.CAS_LOGIN_URL).get().build()).execute().use { resp ->
            WebVpnUtil.isAtTargetSite(resp.request.url.toString(), "lms.xjtu.edu.cn")
        }
    } catch (e: IOException) {
        Log.w(TAG, "web SSO probe failed: ${e.message}")
        true
    }

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
        private const val TAG = "LmsSession"
    }
}