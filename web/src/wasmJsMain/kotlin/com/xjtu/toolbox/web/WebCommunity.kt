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
import com.xjtu.toolbox.platform.keyValueStore
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Web 端的社区：**取数实现与 Android 是同一份**（`:core` 的 GraphQL），只差一个 token 来源。
 *
 * - 传输层由本文件的 [KtorGithubTransport] 提供（Android 那边是 `OkHttpGithubTransport`）——
 *   于是「查询字符串 + JSON 解析 + 限流判定 + 401 回调」两端共用，这正是把那 272 行从 :app
 *   搬进 :core 的意义；
 * - **token 来源 = 用户自己粘贴**（存在浏览器本地，与 App 的 `SecurePrefs("github_community")`
 *   是同两个键 `token` / `login` 的对应物）。为什么设备码流程不行：那条路要求浏览器直连
 *   `github.com/login/device`，而 GitHub 不给跨源响应 ⇒ 拿不到 device code。粘贴 token 不需要
 *   任何服务端配合，所以浏览器端走这条（界面长在共享登录页里，见
 *   [com.xjtu.toolbox.community.GithubDeviceAuthRepository.supportsManualToken]）。
 * - 校验口径与 App 逐字一致：**先用 token 查一次用户名，查得到才落盘**（查不到就落盘只会让界面
 *   以为已登录、随后每个请求都 401）。token 被撤销后请求 401 → [signOut] 清掉，与 App 同一套。
 */
internal class WebGithubSession(private val client: HttpClient) : GithubSession {

    private val store = keyValueStore(STORE_NAME)
    private var token: String? = store.getString(KEY_TOKEN)?.takeIf { it.isNotBlank() }
    private val _login = MutableStateFlow(store.getString(KEY_LOGIN)?.takeIf { token != null })
    override val login: StateFlow<String?> = _login.asStateFlow()

    override val repository: GithubDiscussionsRepository = GraphQlGithubDiscussionsRepository(
        token = { token },
        transport = KtorGithubTransport(client),
        onUnauthorized = ::signOut,
    )

    override suspend fun signIn(accessToken: GithubDeviceAccessToken): Result<String> {
        val raw = accessToken.accessToken.trim()
        if (raw.isEmpty()) return Result.failure(GithubSignedOutException())
        // 探针：拿这个 token 查一次 viewer（不落盘、不改登录态）
        val probe = GraphQlGithubDiscussionsRepository(
            token = { raw },
            transport = KtorGithubTransport(client),
            onUnauthorized = {},
        )
        return probe.viewerLogin().onSuccess { name ->
            token = raw
            store.putString(KEY_TOKEN, raw)
            store.putString(KEY_LOGIN, name)
            _login.value = name
        }
    }

    override fun signOut() {
        token = null
        store.remove(KEY_TOKEN)
        store.remove(KEY_LOGIN)
        _login.value = null
    }

    private companion object {
        /** 与 App 的 `SecurePrefs("github_community")` 同名同键（只是落点从加密偏好换成 localStorage）。 */
        const val STORE_NAME = "github_community"
        const val KEY_TOKEN = "token"
        const val KEY_LOGIN = "login"
    }
}

/**
 * Web 端的登录方式：**只有「粘贴 token」**。
 *
 * 设备码流程在浏览器里拿不到 device code（见 [WebGithubSession] 的说明），所以
 * [isConfigured] 仍是 false（共享登录页据此不再画「用 GitHub 登录」那个按钮），
 * 而 [supportsManualToken] 为 true —— 登录页会长出一行输入框。
 */
internal class WebGithubDeviceAuth(private val session: WebGithubSession) : GithubDeviceAuthRepository {
    override val isConfigured: Boolean = false

    override val supportsManualToken: Boolean = true

    override suspend fun signInWithToken(token: String): Result<String> =
        session.signIn(GithubDeviceAccessToken(accessToken = token.trim(), tokenType = "bearer", scopes = emptySet()))

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
