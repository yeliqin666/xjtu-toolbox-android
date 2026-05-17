package com.xjtu.toolbox.auth

import android.util.Log
import android.util.Base64
import com.xjtu.toolbox.util.safeParseJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class SuperAppLogin(
    session: OkHttpClient? = null,
    visitorId: String? = null,
    cachedRsaKey: String? = null,
) : XJTULogin(LOGIN_URL, session, visitorId, cachedRsaKey) {

    var launchUrl: String = HOME_URL
        private set
    private var launchExpireAt: Long = 0L

    override fun postLogin(response: Response) {
        val finalUrl = response.request.url.toString()
        if ("superapp.xjtu.edu.cn" !in finalUrl) {
            throw RuntimeException("移动交大登录回调异常")
        }
        launchUrl = finalUrl
        lastSuccessfulLaunchUrl = finalUrl
        launchExpireAt = 0L
        response.request.url.queryParameter("ticket")?.split(".")?.getOrNull(1)?.let { payload ->
            val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
            launchExpireAt = runCatching {
                String(Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP))
                    .safeParseJsonObject()
                    .get("exp")?.asLong ?: 0L
            }.getOrDefault(0L)
        }
    }

    fun isLaunchValid(): Boolean =
        launchUrl.contains("ticket=") &&
            (launchExpireAt == 0L || System.currentTimeMillis() / 1000 < launchExpireAt - 30)

    override fun validateLogin(): Boolean = try {
        client.newCall(Request.Builder().url(HOME_URL).get().build()).execute().use {
            it.code == 200 && "login.xjtu.edu.cn" !in it.request.url.host
        }
    } catch (_: Exception) {
        false
    }

    companion object {
        @Volatile var lastSuccessfulLaunchUrl: String = ""
        const val HOME_URL = "https://superapp.xjtu.edu.cn/pages/tab/index/index"
        const val LOGIN_URL =
            "https://login.xjtu.edu.cn/cas/login?service=https%3A%2F%2Fsuperapp.xjtu.edu.cn%2Fpages%2Ftab%2Findex%2Findex"
    }
}
