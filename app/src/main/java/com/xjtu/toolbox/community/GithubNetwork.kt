package com.xjtu.toolbox.community

// 改编自 JoyinJoester/Etoile（GPL-3.0）的 github/data/GithubNetwork.kt、GithubAuthenticatedRequests.kt

import com.xjtu.toolbox.network.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 社区取数里**真正碰 okhttp 的那一点点**。
 *
 * 与它配对的那一半（请求头、限流判定、GraphQL 查询字符串、JSON 解析、401 回调）已经在 :core 的
 * `community/GithubGraphQl.kt` 里 —— 于是「同一份查询」两端共用，这里只实现
 * [GithubHttpTransport] 的「把一次 POST 发出去」。
 */

object GithubNetwork {
    val client: OkHttpClient by lazy {
        HttpClients.base.newBuilder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}

/** 把 okhttp 的响应收成共享层认得的形状（状态码 + 正文 + 限流要看的那两个头）。 */
fun okhttp3.Response.toGithubHttpResponse(): GithubHttpResponse = GithubHttpResponse(
    status = code,
    body = body?.string().orEmpty(),
    retryAfter = header("Retry-After"),
    rateLimitRemaining = header("X-RateLimit-Remaining"),
)

/**
 * Android 侧的传输实现：请求头由共享层给全（`githubHeaders`），这里只加 okhttp 需要的
 * Content-Type。阻塞调用仍在 `Dispatchers.IO` 上跑 —— 与搬迁前
 * `HttpGithubDiscussionsRepository.execute` 的调度一致。
 */
object OkHttpGithubTransport : GithubHttpTransport {
    private val JSON = "application/json".toMediaType()

    override suspend fun post(url: String, headers: Map<String, String>, jsonBody: String): GithubHttpResponse =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .post(jsonBody.toRequestBody(JSON))
                .build()
            GithubNetwork.client.newCall(request).execute().use { it.toGithubHttpResponse() }
        }
}

/**
 * 限流判定的 **okhttp 形状重载**：`GithubOAuthDeviceAuthRepository`（设备码登录，仍在 :app）
 * 手里是 `Response`，让它继续按状态码判即可 —— 判定规则本身只有 :core 那一份。
 */
fun githubApiExceptionOf(response: okhttp3.Response): GithubApiException =
    githubApiExceptionOf(response.toGithubHttpResponse())
