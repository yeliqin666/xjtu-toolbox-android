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

/** 社区取数里真正碰 okhttp 的那一点点。 */

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

/** 两侧共用的传输实现：请求头由共享层给全（`githubHeaders`），这里只加 okhttp 需要的 Content-Type。 */
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

/** 限流判定的 okhttp 形状重载。 */
fun githubApiExceptionOf(response: okhttp3.Response): GithubApiException =
    githubApiExceptionOf(response.toGithubHttpResponse())
