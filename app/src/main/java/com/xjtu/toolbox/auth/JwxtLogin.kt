package com.xjtu.toolbox.auth

import com.xjtu.toolbox.util.redactUrl
import okhttp3.OkHttpClient

/** 教务系统登录（CAS Cookie-based）。 */
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
        if (com.xjtu.toolbox.webvpn.WebVpnUtil.isAtTargetSite(finalUrl, "jwxt.xjtu.edu.cn")) return
        android.util.Log.w("JwxtLogin", "postLogin: finalUrl not at jwxt (${finalUrl.redactUrl()}), retry LOGIN_URL with TGC")
        val retryResp: okhttp3.Response
        val retryBody: String
        try {
            retryResp = client.newCall(
                okhttp3.Request.Builder().url(JWXT_URL).get().build()
            ).execute()
            retryBody = retryResp.body.string()
        } catch (e: Exception) {
            android.util.Log.e("JwxtLogin", "postLogin: retry failed", e)
            throw RuntimeException("教务系统登录没有完成，请重新登录")
        }
        val retryUrl = retryResp.request.url.toString()
        // CAS Safety Verify 二次认证拦截：必须由主 login() 状态机接管转 REQUIRE_MFA。
        // probe 实证 JWXT (client_id=1675) 即使在 webvpn session 已建立时也会触发。
        if (XJTULogin.isSafetyVerifyPage(retryBody)) {
            android.util.Log.w("JwxtLogin", "postLogin: retry hit SAFETY_VERIFY, escalating")
            throw SafetyVerifyRequiredException(retryResp, retryBody)
        }
        if (com.xjtu.toolbox.webvpn.WebVpnUtil.isAtTargetSite(retryUrl, "jwxt.xjtu.edu.cn")) {
            android.util.Log.d("JwxtLogin", "postLogin: retry succeeded, finalUrl=${retryUrl.redactUrl()}")
            return
        }
        android.util.Log.w("JwxtLogin", "postLogin: retry still not at jwxt, finalUrl=${retryUrl.redactUrl()}")
        throw RuntimeException("教务系统登录没有完成，请重新登录")
    }
}
