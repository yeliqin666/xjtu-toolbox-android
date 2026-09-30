package com.xjtu.toolbox.jiaocai

import com.xjtu.toolbox.util.redactUrl
import android.util.Log
import com.xjtu.toolbox.auth.XJTULogin
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * 西安交通大学教材中心登录
 *
 * 认证链路：
 * jiaocai.lib.xjtu.edu.cn/entry/login
 *   → XJTU CAS (login.xjtu.edu.cn)
 *   → 超星 SSO (portal.chaoxing.com)
 *   → /entry/sso-login/cookie/sync（设置教材平台 cookie）
 *
 * 关键 cookie: UID, _d, fid, vc3, uf, p_auth_token, SESSION
 */
class JiaocaiLogin(
    existingClient: OkHttpClient? = null,
    visitorId: String? = null,
    cachedRsaKey: String? = null
) : XJTULogin(
    loginUrl = "https://jiaocai.lib.xjtu.edu.cn/entry/login",
    existingClient = existingClient,
    visitorId = visitorId,
    cachedRsaKey = cachedRsaKey
) {
    companion object {
        private const val TAG = "JiaocaiLogin"
    }

    /** 会话没建好时由 [JiaocaiApi] 跟 JS 跳转链补上，这里不用再探。 */
    override fun postLogin(response: Response) {
        Log.d(TAG, "postLogin: finalUrl=${response.request.url.toString().redactUrl()}, bodyLen=${lastResponseBody.length}")
    }
}
