package com.xjtu.toolbox.community

/**
 * GitHub Discussions 的**模型与取数接口**（实现留在 :app，因为它是 okhttp 的）。
 *
 * 这一层是第 3 步要的那种「缝」：`DiscussionsViewModel` 只认识这个接口与这些数据类，
 * 于是「帖子列表 + 分类 + 发帖」这条链可以在 commonMain 里跑，而 HTTP 实现（GraphQL 查询、
 * token、错误码）继续待在 :app，由调用方注入。这样搬屏时不必先把 okhttp 换掉 ——
 * 第 1 步的 okhttp→Ktor 可以晚一步做，第 3 步不必等它。
 *
 * 改编自 JoyinJoester/Etoile（GPL-3.0）。
 */

data class GithubDiscussionCategory(val id: String, val name: String, val acceptsAnswers: Boolean)

/** 一种表情回应：[content] 是 GitHub 的 ReactionContent 枚举名。 */
data class GithubReaction(val content: String, val count: Int, val mine: Boolean)

data class GithubPollOption(val id: String, val text: String, val votes: Int, val mine: Boolean)
data class GithubPoll(
    val question: String, val options: List<GithubPollOption>, val total: Int,
    val canVote: Boolean, val voted: Boolean,
)

data class GithubDiscussion(
    val id: String, val number: Int, val title: String, val body: String,
    val url: String, val author: String?, val category: GithubDiscussionCategory,
    val comments: Int, val answered: Boolean, val canEdit: Boolean = false,
    val createdAt: String = "", val updatedAt: String = "", val authorAvatar: String? = null,
    val reactions: List<GithubReaction> = emptyList(), val canReact: Boolean = false,
    val canDelete: Boolean = false, val authorIsAdmin: Boolean = false,
    /** 关闭原因：RESOLVED / OUTDATED / DUPLICATE；没关闭为 null。 */
    val closedReason: String? = null, val canClose: Boolean = false, val canReopen: Boolean = false,
    val locked: Boolean = false,
    /** 能锁帖（仓库 triage 及以上权限）。 */
    val canModerate: Boolean = false,
    val poll: GithubPoll? = null,
    val pinned: Boolean = false,
) {
    val closed get() = closedReason != null
}
data class GithubDiscussionPage(val repositoryId: String, val items: List<GithubDiscussion>, val nextCursor: String?)
data class GithubDiscussionComment(
    val id: String, val body: String, val author: String?, val isAnswer: Boolean,
    val canMarkAnswer: Boolean = false, val canUnmarkAnswer: Boolean = false,
    val canEdit: Boolean = false, val canDelete: Boolean = false,
    val createdAt: String = "", val replyCount: Int = 0, val authorAvatar: String? = null,
    val reactions: List<GithubReaction> = emptyList(), val canReact: Boolean = false,
    /** 楼中楼的前两条，列表里直接露出来；更多的点开再拉。 */
    val previewReplies: List<GithubDiscussionComment> = emptyList(),
    val authorIsAdmin: Boolean = false,
    val url: String = "",
    /** 被折叠的原因（GitHub 返回的小写分类，如 spam）；没折叠为 null。 */
    val minimizedReason: String? = null,
    val canMinimize: Boolean = false, val canUnminimize: Boolean = false,
) {
    val minimized get() = minimizedReason != null
}
data class GithubDiscussionComments(val items: List<GithubDiscussionComment>, val nextCursor: String?)

class GithubDiscussionException(message: String? = null) :
    IllegalStateException(message ?: "GitHub discussion request failed")

/**
 * 取数接口：只声明共享 UI 真正用到的三个方法。
 *
 * 刻意**不**把 :app 那个 322 行的实现类的全部方法都搬过来：接口越瘦，将来 Ktor 版或
 * 假实现（测试/预览）要背的包袱越小；需要新方法时再加。
 */
interface GithubDiscussionsRepository {
    // ⚠️ 这里的成员必须覆盖 :app 调用方**实际用到的全部**成员。第一版只放了 list/categories/create，
    // 于是持有接口类型的地方调 close/lock/replies… 全部 Unresolved —— 缝要缝完整。
    // 签名一律只涉及本文件里的模型，不带 okhttp 类型，所以整份接口都是可移植的。
    suspend fun list(owner: String, name: String, cursor: String?, categoryId: String? = null): Result<GithubDiscussionPage>

    suspend fun categories(owner: String, name: String): Result<List<GithubDiscussionCategory>>

    suspend fun create(repositoryId: String, categoryId: String, title: String, body: String): Result<GithubDiscussion>

    suspend fun editComment(id: String, body: String): Result<GithubDiscussionComment>
    suspend fun deleteComment(id: String): Result<Unit>
    suspend fun replyToComment(discussionId: String, commentId: String, body: String): Result<String>
    suspend fun edit(id: String, title: String, body: String): Result<GithubDiscussion>
    suspend fun markAnswer(commentId: String, answered: Boolean): Result<Unit>
    suspend fun reply(discussionId: String, body: String): Result<String>

    suspend fun viewerLogin(): Result<String>
    suspend fun replies(commentId: String, cursor: String?): Result<GithubDiscussionComments>
    suspend fun comments(id: String, cursor: String?): Result<GithubDiscussionComments>
    suspend fun detail(owner: String, name: String, number: Int): Result<GithubDiscussion>
    suspend fun deleteDiscussion(id: String): Result<Unit>
    suspend fun react(subjectId: String, content: String, add: Boolean): Result<Unit>
    suspend fun close(id: String, reason: String): Result<Unit>
    suspend fun reopen(id: String): Result<Unit>
    suspend fun lock(id: String, locked: Boolean): Result<Unit>
    suspend fun minimize(id: String, classifier: String): Result<Unit>
    suspend fun unminimize(id: String): Result<Unit>
    suspend fun vote(optionId: String): Result<Unit>
}
