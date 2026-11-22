package com.xjtu.toolbox.lms

import com.xjtu.toolbox.util.redactUrl
import com.xjtu.toolbox.platform.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import com.xjtu.toolbox.auth.XJTULogin

/**
 * 思源学堂 (lms.xjtu.edu.cn) 登录
 *
 * 认证链路 (CAS SSO):
 * 1. 访问 lms.xjtu.edu.cn → 302 → login.xjtu.edu.cn/cas
 * 2. CAS 登录成功 → 回调链 → lms.xjtu.edu.cn/user/index → session cookie
 *
 * 会话凭据: session cookie (on lms.xjtu.edu.cn)
 */
class LmsLogin(
    session: OkHttpClient? = null,
    visitorId: String? = null,
    cachedRsaKey: String? = null,
    /** 从哪儿进统一认证。平时是学堂首页；要把统一认证的登录态也补新时用 [CAS_LOGIN_URL]。 */
    entryUrl: String = LMS_LOGIN_URL,
) : XJTULogin(
    loginUrl = entryUrl,
    existingClient = session,
    visitorId = visitorId,
    cachedRsaKey = cachedRsaKey
) {
    companion object {
        private const val TAG = "LmsLogin"

        /** 思源学堂基础地址 */
        const val BASE_URL = "https://lms.xjtu.edu.cn"

        /** CAS 登录入口 — OkHttp 自动跟随重定向到 login.xjtu.edu.cn */
        const val LMS_LOGIN_URL = "https://lms.xjtu.edu.cn"

        /**
         * 学堂自己的 CAS 回调入口。不管学堂会话还在不在，访问它都会经 identity1 → login.xjtu.edu.cn
         * 走一遍统一认证再带票回来，所以既能探「统一认证的登录态还活着没有」，也能在它过期时
         * 用密码重新建起来（学堂首页做不到：学堂会话还在时根本不去统一认证）。
         */
        const val CAS_LOGIN_URL = "https://lms.xjtu.edu.cn/api/cas-login"
    }

    override fun postLogin(response: Response) {
        val finalUrl = response.request.url.toString()
        Log.d(TAG, "postLogin: finalUrl=${finalUrl.redactUrl()}")

        if (com.xjtu.toolbox.webvpn.WebVpnUtil.isAtTargetSite(finalUrl, "lms.xjtu.edu.cn")) {
            Log.d(TAG, "postLogin: session established via redirect chain")
            return
        }

        // 最终 URL 不在 lms 站点（直连或 WebVPN），手动访问触发 session
        Log.d(TAG, "postLogin: not at LMS site, manually accessing user/index")
        var sessionValid = false
        try {
            val indexReq = Request.Builder()
                .url("$BASE_URL/user/index")
                .get()
                .build()
            val indexResp = client.newCall(indexReq).execute()
            val indexFinalUrl = indexResp.request.url.toString()
            indexResp.close()

            sessionValid = com.xjtu.toolbox.webvpn.WebVpnUtil.isAtTargetSite(indexFinalUrl, "lms.xjtu.edu.cn")
            Log.d(TAG, "postLogin: manual access finalUrl=${indexFinalUrl.redactUrl()}, valid=$sessionValid")
        } catch (e: Exception) {
            Log.e(TAG, "postLogin: manual access failed", e)
        }

        if (!sessionValid) {
            throw RuntimeException("登录失败：无法建立思源学堂会话")
        }
    }
}
