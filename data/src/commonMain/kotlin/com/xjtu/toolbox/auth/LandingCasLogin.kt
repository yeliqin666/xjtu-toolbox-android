package com.xjtu.toolbox.auth

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * 只需走完 CAS、落到本站就算登录成功的站点。CAS 回跳偶尔停在「200 + 表单自动提交」上，
 * OkHttp 不会替你提交，这时 TGC 已经建好，重访一次入口就能把跳转链走完（同 [JwxtLogin]）。
 *
 * 从 `:app/auth/Sites.kt` 搬进 `:data` 时**单列成一个文件**：它原来与 [GsteSession] / [GmisSession]
 * 挤在同一段里，但两个站点都要用它，跟着其中任何一个走都会让另一个去猜它在哪儿。
 * 可见性从 `private` 放到 `internal`（同一个模块内的两个站点类要用），`internal` 也就是
 * 「`:app` / `:desktop` 看不见」—— 它是个实现细节，不该跨模块构造。
 */
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
