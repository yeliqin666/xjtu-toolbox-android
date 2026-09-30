package com.xjtu.toolbox.auth

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Response

private const val TAG = "JwappLogin"

/**
 * 移动教务系统 (jwapp) 专用登录
 * 登录后自动提取 Authorization token 并注入到 session headers
 *
 * token 从 CAS 重定向 URL 中提取；失效后由站点会话层（[SiteSession.executeWithReAuth]）整体重登。
 */
class JwappLogin(
    session: OkHttpClient? = null,
    visitorId: String? = null,
    cachedRsaKey: String? = null
) : XJTULogin(JWAPP_URL, session, visitorId, cachedRsaKey) {

    var authToken: String? = null
        private set

    override fun postLogin(response: Response) {
        // 从最终重定向 URL 中提取 token
        val finalUrl = response.request.url.toString()
        authToken = finalUrl.substringAfter("token=", "")
            .substringBefore("&")
            .takeIf { it.isNotEmpty() }
            ?: throw RuntimeException("移动教务登录没有完成，请稍后重试")
        Log.d(TAG, "postLogin: token obtained, len=${authToken?.length}")
        // 诊断：jwapp 域 cookies 名单（不暴露值），定位 401 是否是缺 session cookie
        try {
            val jar = client.cookieJar
            if (jar is com.xjtu.toolbox.network.PersistentCookieJar) {
                val direct = jar.getCookiesForDomain("jwapp.xjtu.edu.cn") +
                    jar.getCookiesForDomain(".jwapp.xjtu.edu.cn")
                val webvpn = jar.getCookiesForDomain("webvpn.xjtu.edu.cn") +
                    jar.getCookiesForDomain(".webvpn.xjtu.edu.cn")
                Log.d(TAG, "postLogin: jwapp-cookies=${direct.map { it.name }}, webvpn-cookies=${webvpn.map { it.name }}")
            }
        } catch (_: Exception) {}
    }

    companion object {
        const val JWAPP_URL =
            "https://org.xjtu.edu.cn/openplatform/oauth/authorize?appId=1370&redirectUri=http://jwapp.xjtu.edu.cn/app/index&responseType=code&scope=user_info&state=1234"
    }
}
