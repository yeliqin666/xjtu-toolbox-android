package com.xjtu.toolbox.jiaocai

import android.util.Log
import com.xjtu.toolbox.auth.XJTULogin
import com.xjtu.toolbox.util.safeParseJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
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
        const val BASE_URL = "https://jiaocai.lib.xjtu.edu.cn"
        const val FID = "17071"
        const val WEBSITE_ID = "12950"
        const val PAGE_ID = "13858"
        const val SEARCH_ID = "10700"
    }

    var isReady: Boolean = false
        private set

    var uid: String = ""
        private set

    var enc: String = ""
        private set

    override fun postLogin(response: Response) {
        val finalUrl = response.request.url.toString()
        Log.d(TAG, "postLogin: finalUrl=$finalUrl, bodyLen=${lastResponseBody.length}")
        tryFetchUserInfo()
    }

    private fun tryFetchUserInfo() {
        try {
            val req = Request.Builder()
                .url("$BASE_URL/engine2/header/user-info")
                .get()
                .build()
            val resp = client.newCall(req).execute()
            val text = resp.body?.use { it.string() } ?: ""
            Log.d(TAG, "user-info: ${text.take(200)}")

            val json = text.safeParseJsonObject()
            val data = json.getAsJsonObject("data")
            if (data != null) {
                // data.uid 可能为 JsonNull（首次访问 / session 未完全建立），需 null-safe
                uid = data.get("uid")?.takeIf { !it.isJsonNull }?.asString ?: ""
                enc = data.get("enc")?.takeIf { !it.isJsonNull }?.asString ?: ""
                if (uid.isNotBlank()) {
                    isReady = true
                    Log.d(TAG, "jiaocai ready: uid=$uid")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "tryFetchUserInfo failed", e)
        }
    }
}
