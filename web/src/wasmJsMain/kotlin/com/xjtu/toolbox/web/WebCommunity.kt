package com.xjtu.toolbox.web

import com.xjtu.toolbox.community.GithubDeviceAccessToken
import com.xjtu.toolbox.community.GithubDeviceAuthRepository
import com.xjtu.toolbox.community.GithubDeviceAuthorization
import com.xjtu.toolbox.community.GithubDeviceFlowNotConfiguredException
import com.xjtu.toolbox.community.GithubDevicePollResult
import com.xjtu.toolbox.community.GithubDiscussion
import com.xjtu.toolbox.community.GithubDiscussionCategory
import com.xjtu.toolbox.community.GithubDiscussionComment
import com.xjtu.toolbox.community.GithubDiscussionComments
import com.xjtu.toolbox.community.GithubDiscussionPage
import com.xjtu.toolbox.community.GithubDiscussionsRepository
import com.xjtu.toolbox.community.GithubSession
import com.xjtu.toolbox.community.GithubSignedOutException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Web 端的社区登录态：**明确的「尚未配置」**，不是假装能用。
 *
 * `CommunityScreen` 是 `:core` 的真屏，它要两个注入：登录态与设备码登录。这两件事在 Android 上
 * 是 `PrefsGithubSession`（加密偏好 + okhttp GraphQL），Web 端现在没有对应物 ——
 * 浏览器直连 `github.com/login/device` 拿不到 CORS 头，`api.github.com` 又需要用户自己的 token。
 *
 * 所以这里给的是**诚实的降级**：`deviceAuth.isConfigured = false` ⇒ 登录页显示它自带的
 * 「社区登录还没配置好，敬请期待。」那句（:core 里本来就有的分支），界面、主题、文案全部与被
 * Android 端同一份代码，只是这一件事暂时做不了。要真接上，只需在这里换成一个走同源反代
 * （campus-api 加一个 GitHub 代理端点）或直连 `api.github.com` 的实现 —— 屏幕一行都不用改。
 *
 * 仓库实现把所有方法都返回「未登录」，因为未登录时屏幕不会走到它们
 * （`CommunityScreen` 内部先按 `login == null` 分流到登录页）。
 */
internal object WebGithubSession : GithubSession {
    override val login: StateFlow<String?> = MutableStateFlow(null)

    override val repository: GithubDiscussionsRepository = NotLoggedInRepository

    override suspend fun signIn(accessToken: GithubDeviceAccessToken): Result<String> =
        Result.failure(GithubSignedOutException())

    override fun signOut() = Unit
}

/** Web 端的设备码登录：同上，如实报「未配置」。 */
internal object WebGithubDeviceAuth : GithubDeviceAuthRepository {
    override val isConfigured: Boolean = false

    override suspend fun start(): Result<GithubDeviceAuthorization> =
        Result.failure(GithubDeviceFlowNotConfiguredException())

    override suspend fun poll(deviceCode: String): Result<GithubDevicePollResult> =
        Result.failure(GithubDeviceFlowNotConfiguredException())
}

/**
 * 「未登录」的取数实现。21 个方法逐个返回失败，而不是抛异常 —— 接口的约定就是用 `Result`。
 * 未登录时屏幕不会调用它们（见 [WebGithubSession] 的说明），列全是为了**接口一变就编译报错**，
 * 而不是等到运行时才发现某条路径没实现。
 */
internal object NotLoggedInRepository : GithubDiscussionsRepository {
    private fun <T> no() : Result<T> = Result.failure(GithubSignedOutException())

    override suspend fun list(owner: String, name: String, cursor: String?, categoryId: String?): Result<GithubDiscussionPage> = no()
    override suspend fun categories(owner: String, name: String): Result<List<GithubDiscussionCategory>> = no()
    override suspend fun create(repositoryId: String, categoryId: String, title: String, body: String): Result<GithubDiscussion> = no()
    override suspend fun editComment(id: String, body: String): Result<GithubDiscussionComment> = no()
    override suspend fun deleteComment(id: String): Result<Unit> = no()
    override suspend fun replyToComment(discussionId: String, commentId: String, body: String): Result<String> = no()
    override suspend fun edit(id: String, title: String, body: String): Result<GithubDiscussion> = no()
    override suspend fun markAnswer(commentId: String, answered: Boolean): Result<Unit> = no()
    override suspend fun reply(discussionId: String, body: String): Result<String> = no()
    override suspend fun viewerLogin(): Result<String> = no()
    override suspend fun replies(commentId: String, cursor: String?): Result<GithubDiscussionComments> = no()
    override suspend fun comments(id: String, cursor: String?): Result<GithubDiscussionComments> = no()
    override suspend fun detail(owner: String, name: String, number: Int): Result<GithubDiscussion> = no()
    override suspend fun deleteDiscussion(id: String): Result<Unit> = no()
    override suspend fun react(subjectId: String, content: String, add: Boolean): Result<Unit> = no()
    override suspend fun close(id: String, reason: String): Result<Unit> = no()
    override suspend fun reopen(id: String): Result<Unit> = no()
    override suspend fun lock(id: String, locked: Boolean): Result<Unit> = no()
    override suspend fun minimize(id: String, classifier: String): Result<Unit> = no()
    override suspend fun unminimize(id: String): Result<Unit> = no()
    override suspend fun vote(optionId: String): Result<Unit> = no()
}
