package com.xjtu.toolbox.auth

import com.xjtu.toolbox.util.stringValue
import android.util.Base64
import android.util.Log
import com.xjtu.toolbox.util.safeParseJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

private const val TAG = "YwtbLogin"

/**
 * 新师生综合服务大厅 (ywtb.xjtu.edu.cn) 专用登录
 * 登录后从重定向 URL 中提取 CAS ticket（JWT），解码提取 idToken。
 *
 * ### Token 生命周期
 * - idToken 从 CAS ticket JWT payload 解码获得
 * - 过期后通过 [reAuthenticate] 利用 CAS SSO TGC cookie 重新获取
 * - [executeWithReAuth] 自动检测过期/401 并重试
 */
class YwtbLogin(
    session: OkHttpClient? = null,
    visitorId: String? = null,
    cachedRsaKey: String? = null
) : XJTULogin(YWTB_LOGIN_URL, session, visitorId, cachedRsaKey) {

    var idToken: String? = null
        private set

    override fun postLogin(response: Response) {
        extractTokenFromResponse(response)
    }

    /**
     * 从 CAS 响应中提取 idToken
     */
    private fun extractTokenFromResponse(response: Response) {
        // 使用 OkHttp HttpUrl 解析 query parameter，自动处理 URL 编码
        val ticketJwt = response.request.url.queryParameter("ticket")
            ?.takeIf { it.isNotEmpty() }
            ?: throw RuntimeException("登录失败：无法获取 YWTB ticket")

        val parts = ticketJwt.split(".")
        if (parts.size < 2) throw RuntimeException("登录信息无效，请重新登录")

        // JWT payload 使用 Base64url 编码（可能有或无 padding）
        // 先补齐 padding 再解码，兼容所有格式
        val payload64 = parts[1].let { p ->
            when (p.length % 4) {
                2 -> p + "=="
                3 -> p + "="
                else -> p
            }
        }
        val payloadJson = String(Base64.decode(payload64, Base64.URL_SAFE or Base64.NO_WRAP))
        val payload = payloadJson.safeParseJsonObject()
        idToken = payload.get("idToken")?.stringValue
            ?: throw RuntimeException("JWT 中未找到 idToken")

        Log.d(TAG, "postLogin: idToken obtained")
    }

    private val reAuthLock = Any()

    /**
     * [D1] 重新认证：先尝试 SSO，失败后 fallback 到 casAuthenticate（TGC 过期时用保存的密码）
     * @return true 表示重新认证成功
     */
    fun reAuthenticate(): Boolean = synchronized(reAuthLock) {
        try {
            // 第一步：SSO（TGC 有效时直接成功）
            Log.d(TAG, "reAuthenticate: attempting SSO re-login")
            val request = Request.Builder().url(YWTB_LOGIN_URL).get().build()
            val response = client.newCall(request).execute()
            try {
                extractTokenFromResponse(response)
                Log.d(TAG, "reAuthenticate: SSO success, new idToken obtained")
                return true
            } catch (_: Exception) {
                Log.d(TAG, "reAuthenticate: SSO failed (no ticket in redirect), trying casAuthenticate")
            }

            // 第二步：casAuthenticate fallback（TGC 过期）
            val serviceUrl = YWTB_LOGIN_URL.substringAfter("service=").let {
                java.net.URLDecoder.decode(it, "UTF-8")
            }
            val casResult = casAuthenticate(serviceUrl)
            if (casResult != null) {
                // casAuthenticate 成功后，CAS cookie 已更新
                // 重新尝试 SSO 访问
                val retryRequest = Request.Builder().url(YWTB_LOGIN_URL).get().build()
                val retryResponse = client.newCall(retryRequest).execute()
                try {
                    extractTokenFromResponse(retryResponse)
                    Log.d(TAG, "reAuthenticate: casAuthenticate fallback success")
                    return true
                } catch (_: Exception) {
                    Log.w(TAG, "reAuthenticate: casAuthenticate succeeded but token extraction failed")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "reAuthenticate failed", e)
        }
        return false
    }

    companion object {
        const val YWTB_LOGIN_URL =
            "https://login.xjtu.edu.cn/cas/login?service=https%3A%2F%2Fywtb.xjtu.edu.cn%2F%3Fpath%3Dhttps%253A%252F%252Fywtb.xjtu.edu.cn%252Fmain.html%2523%252FIndex"
    }
}
