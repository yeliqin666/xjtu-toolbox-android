package com.xjtu.toolbox.community

// 改编自 JoyinJoester/Etoile（GPL-3.0）的 github/data/GithubNetwork.kt、GithubAuthenticatedRequests.kt

import com.xjtu.toolbox.network.HttpClients
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 社区取数用的 okhttp 客户端与请求构造 —— 这一半**留在 :app**（okhttp 没有多平台发布）。
 *
 * 与它配对的那一半（站点常量 [CommunityRepo]、[GithubApiException]、`githubRunCatching`）
 * 已经从 :app 的 `community/GithubCore.kt` 搬进 :core；这里保存的是真正碰 okhttp 的部分。
 */

object GithubNetwork {
    val client: OkHttpClient by lazy {
        HttpClients.base.newBuilder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    fun builder(url: String, token: String? = null): Request.Builder = Request.Builder()
        .url(url)
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", "XJTUToolbox-Community")
        .apply { if (token != null) header("Authorization", "Bearer $token") }
}

/**
 * 原来挂在 `GithubApiException.Companion.of(response)` 上。异常本体（两个字段）已经进 :core，
 * 但「从 okhttp 的 Response 判限流」这一步必须留在这一侧 —— 所以改成一个同包的顶层函数，
 * 调用点从 `GithubApiException.of(response)` 变成 `githubApiExceptionOf(response)`。
 */
fun githubApiExceptionOf(response: okhttp3.Response): GithubApiException {
    val throttled = response.code == 429 ||
        (response.code == 403 && (response.header("Retry-After") != null ||
            response.header("X-RateLimit-Remaining")?.trim() == "0"))
    return GithubApiException(statusCode = response.code, rateLimited = throttled)
}
