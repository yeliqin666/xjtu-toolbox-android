package com.xjtu.toolbox.lms

import com.xjtu.toolbox.util.redactUrl
import android.util.Log
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
    cachedRsaKey: String? = null
) : XJTULogin(
    loginUrl = LMS_LOGIN_URL,
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
