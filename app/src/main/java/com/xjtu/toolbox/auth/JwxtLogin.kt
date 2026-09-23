package com.xjtu.toolbox.auth

import com.xjtu.toolbox.util.redactUrl
import okhttp3.OkHttpClient

/**
 * 教务系统登录（CAS Cookie-based）
 * [A1] 增加 reAuthenticate：session 过期时通过 CAS SSO/casAuthenticate 恢复
 *
 * 历史上这个类被错放在 attendance/AttendanceLogin.kt 里（跟考勤毫无关系，纯粹是当年
 * 找了个顺手的文件塞进去）；旧版考勤下线整个删掉那个文件时顺带删了它，编译报
 * `JwxtSession` 里 `Unresolved reference 'JwxtLogin'` 才发现——挪回属于自己的文件。
 */
class JwxtLogin(
    session: OkHttpClient? = null,
    visitorId: String? = null,
    cachedRsaKey: String? = null
) : XJTULogin(JWXT_URL, session, visitorId, cachedRsaKey) {

    override fun postLogin(response: okhttp3.Response) {
        val finalUrl = response.request.url.toString()
        // OAuth client_id=1675 → callbackAuthorize → openplatform → jwxt 三跳。
        // CAS 服务端常返回 200 + form auto-submit，OkHttp 不会自动提交 form，
        // finalUrl 卡在 cas/login?service=callbackAuthorize 阶段。
        // 此时 TGC 已建立——重访 JWXT_URL，CAS 看到 TGC 直接 302 把整条链走完。
        if (com.xjtu.toolbox.util.WebVpnUtil.isAtTargetSite(finalUrl, "jwxt.xjtu.edu.cn")) return
        android.util.Log.w("JwxtLogin", "postLogin: finalUrl not at jwxt (${finalUrl.redactUrl()}), retry LOGIN_URL with TGC")
        val retryResp: okhttp3.Response
        val retryBody: String
        try {
            retryResp = client.newCall(
                okhttp3.Request.Builder().url(JWXT_URL).get().build()
            ).execute()
            retryBody = retryResp.body?.string() ?: ""
        } catch (e: Exception) {
            android.util.Log.e("JwxtLogin", "postLogin: retry failed", e)
            throw RuntimeException("教务系统 SSO 未完成跳转，需要重新登录")
        }
        val retryUrl = retryResp.request.url.toString()
        // CAS Safety Verify 二次认证拦截：必须由主 login() 状态机接管转 REQUIRE_MFA。
        // probe 实证 JWXT (client_id=1675) 即使在 webvpn session 已建立时也会触发。
        if (XJTULogin.isSafetyVerifyPage(retryBody)) {
            android.util.Log.w("JwxtLogin", "postLogin: retry hit SAFETY_VERIFY, escalating")
            throw SafetyVerifyRequiredException(retryResp, retryBody)
        }
        if (com.xjtu.toolbox.util.WebVpnUtil.isAtTargetSite(retryUrl, "jwxt.xjtu.edu.cn")) {
            android.util.Log.d("JwxtLogin", "postLogin: retry succeeded, finalUrl=${retryUrl.redactUrl()}")
            return
        }
        android.util.Log.w("JwxtLogin", "postLogin: retry still not at jwxt, finalUrl=${retryUrl.redactUrl()}")
        throw RuntimeException("教务系统 SSO 未完成跳转，需要重新登录")
    }

    override fun validateLogin(): Boolean {
        return try {
            val request = okhttp3.Request.Builder()
                .url("https://jwxt.xjtu.edu.cn/api/v2/system/term-info")
                .get().build()
            val response = client.newCall(request).execute()
            val finalUrl = response.request.url.toString()
            val code = response.code
            response.close()
            code == 200 && !finalUrl.contains("login.xjtu.edu.cn/cas/login", ignoreCase = true)
        } catch (_: Exception) { false }
    }

    override fun keepAlive(): KeepAliveStatus {
        return try {
            if (validateLogin()) return KeepAliveStatus.VALID
            if (reAuthenticate()) KeepAliveStatus.REAUTH_OK
            else KeepAliveStatus.AUTH_INVALID
        } catch (_: java.io.IOException) { KeepAliveStatus.NETWORK_ERROR }
        catch (_: Exception) { KeepAliveStatus.ERROR }
    }

    private val reAuthLock = Any()

    /**
     * [A1] 重新认证：通过 CAS SSO 刷新教务 session
     * [D1] SSO 失败时自动 fallback 到 casAuthenticate（用保存的密码重新提交）
     * @return true 表示重新认证成功
     */
    fun reAuthenticate(): Boolean = synchronized(reAuthLock) {
        return try {
            val result = casAuthenticate(JWXT_URL)
            if (result != null && !result.second.contains("login.xjtu.edu.cn/cas/login")) {
                android.util.Log.d("JwxtLogin", "reAuthenticate: success via casAuthenticate")
                true
            } else {
                android.util.Log.w("JwxtLogin", "reAuthenticate: casAuthenticate returned login page")
                false
            }
        } catch (e: Exception) {
            android.util.Log.e("JwxtLogin", "reAuthenticate failed", e)
            false
        }
    }
}
