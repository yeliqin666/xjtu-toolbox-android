package com.xjtu.toolbox.web

import com.xjtu.toolbox.community.GithubDeviceAccessToken
import com.xjtu.toolbox.community.GithubDeviceAuthRepository
import com.xjtu.toolbox.community.GithubDeviceAuthorization
import com.xjtu.toolbox.community.GithubDeviceFlowNotConfiguredException
import com.xjtu.toolbox.community.GithubDevicePollResult
import com.xjtu.toolbox.community.GithubDiscussionsRepository
import com.xjtu.toolbox.community.GithubHttpResponse
import com.xjtu.toolbox.community.GithubHttpTransport
import com.xjtu.toolbox.community.GithubSession
import com.xjtu.toolbox.community.GithubSignedOutException
import com.xjtu.toolbox.community.GraphQlGithubDiscussionsRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Web 端的社区：**取数实现与 Android 是同一份**（`:core` 的 GraphQL），只差一个 token 来源。
 *
 * - 传输层由本文件的 [KtorGithubTransport] 提供（Android 那边是 `OkHttpGithubTransport`）——
 *   于是「查询字符串 + JSON 解析 + 限流判定 + 401 回调」两端共用，这正是把那 272 行从 :app
 *   搬进 :core 的意义；
 * - **token 现在恒为 null**：浏览器直连 `github.com/login/device` 拿不到 CORS 头（设备码流程
 *   走不通），`api.github.com` 又需要用户自己的 token。所以任何取数都会得到
 *   `GithubSignedOutException`，界面停在 `CommunityScreen` 自带的登录页 —— 这是如实的降级，
 *   不是假装能用。
 * - 接上它只需要改这一处的 `token`：要么 campus-api 加一个 GitHub 代理端点（走同源），
 *   要么让用户粘贴一个自己的 token（界面那侧加一个输入框）。**屏幕与 :core 都不用改。**
 */
internal class WebGithubSession(client: HttpClient) : GithubSession {

    private var token: String? = null

    override val login: StateFlow<String?> = MutableStateFlow(null)

    override val repository: GithubDiscussionsRepository = GraphQlGithubDiscussionsRepository(
        token = { token },
        transport = KtorGithubTransport(client),
        onUnauthorized = { token = null },
    )

    override suspend fun signIn(accessToken: GithubDeviceAccessToken): Result<String> =
        Result.failure(GithubSignedOutException())

    override fun signOut() {
        token = null
    }
}

/** Web 端的设备码登录：如实报「未配置」（见 [WebGithubSession] 的说明）。 */
internal object WebGithubDeviceAuth : GithubDeviceAuthRepository {
    override val isConfigured: Boolean = false

    override suspend fun start(): Result<GithubDeviceAuthorization> =
        Result.failure(GithubDeviceFlowNotConfiguredException())

    override suspend fun poll(deviceCode: String): Result<GithubDevicePollResult> =
        Result.failure(GithubDeviceFlowNotConfiguredException())
}

/**
 * Ktor 版传输：与 Android 的 `OkHttpGithubTransport` 配对，两个实现都只有「发一次 POST、
 * 把状态码/正文/限流头收成 [GithubHttpResponse]」这一步。
 *
 * 没有 `dispatcher` 参数：Ktor 的 fetch 本身异步，不需要 `Dispatchers.IO`（那是 JVM 专有的）。
 */
internal class KtorGithubTransport(private val client: HttpClient) : GithubHttpTransport {
    override suspend fun post(url: String, headers: Map<String, String>, jsonBody: String): GithubHttpResponse {
        val response = client.post(url) {
            headers.forEach { (name, value) -> header(name, value) }
            contentType(ContentType.Application.Json)
            setBody(jsonBody)
        }
        return GithubHttpResponse(
            status = response.status.value,
            body = response.bodyAsText(),
            retryAfter = response.headers["Retry-After"],
            rateLimitRemaining = response.headers["X-RateLimit-Remaining"],
        )
    }
}
