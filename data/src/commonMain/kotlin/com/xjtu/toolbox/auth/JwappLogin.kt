package com.xjtu.toolbox.auth

import com.xjtu.toolbox.platform.Log
import com.xjtu.toolbox.network.PersistentCookieJar
import okhttp3.OkHttpClient
import okhttp3.Response

private const val TAG = "JwappLogin"

/**
 * 移动教务系统 (jwapp) 专用登录
 * 登录后自动提取 Authorization token 并注入到 session headers
 *
 * token 从 CAS 重定向 URL 中提取；失效后由站点会话层（[SiteSession.executeWithReAuth]）整体重登。
 *
 * 与它的站点类 [JwappSession] 一起从 `:app` 的 `auth/JwappLogin.kt` / `auth/Sites.kt` 剪出来搬进
 * `:data`（桌面端第 19 条真数据路由：桌面要自己登移动教务、自己取学期成绩）。类名与包路径一字未改 ⇒
 * `:app` 的 `AppLoginState` / `HeadlessSessions` 里那两处 `register(JwappSession())` 一行不用改。
 *
 * 搬迁时被替换的写法只有一处：`android.util.Log` → `:core` 的 `Log`（tag 与级别不变）；
 * cookie 诊断那一枪读的 [PersistentCookieJar] 本来就在 `:data`，一个字没动。
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
            if (jar is PersistentCookieJar) {
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
