package com.xjtu.toolbox.auth

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/** 只需走完 CAS、落到本站就算登录成功的站点。 */
internal class LandingCasLogin(
    private val entryUrl: String,
    private val targetHost: String,
    existingClient: OkHttpClient,
    visitorId: String?,
    cachedRsaKey: String?,
) : XJTULogin(entryUrl, existingClient, visitorId, cachedRsaKey) {
    override fun postLogin(response: Response) {
        if (com.xjtu.toolbox.webvpn.WebVpnUtil.isAtTargetSite(response.request.url.toString(), targetHost)) return
        client.newCall(Request.Builder().url(entryUrl).get().build()).execute().use { retry ->
            val body = retry.body.string()
            if (XJTULogin.isSafetyVerifyPage(body)) throw SafetyVerifyRequiredException(retry, body)
            if (!com.xjtu.toolbox.webvpn.WebVpnUtil.isAtTargetSite(retry.request.url.toString(), targetHost)) {
                throw IOException("$targetHost 登录没有完成，请重新登录")
            }
        }
    }
}
