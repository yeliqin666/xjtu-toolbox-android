package com.xjtu.toolbox.community

/** GitHub Discussions 的模型与取数接口（实现留在 :app，因为它是 okhttp 的）。 */

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
data class GithubDiscussionPage(
    val repositoryId: String, val items: List<GithubDiscussion>, val nextCursor: String?,
    /** 当前用户对仓库的权限：ADMIN / MAINTAIN / WRITE / TRIAGE / READ。 */
    val viewerPermission: String? = null,
)

/** 列表的状态筛选，[graphQl] 是 DiscussionState 枚举名，null 为不限。 */
enum class DiscussionStateFilter(val label: String, val graphQl: String?, val searchTerm: String?) {
    OPEN("开启", "OPEN", "is:open"),
    CLOSED("已关闭", "CLOSED", "is:closed"),
    ALL("全部", null, null),
}

enum class DiscussionSort(val label: String, val field: String, val searchTerm: String) {
    UPDATED("最近更新", "UPDATED_AT", "sort:updated-desc"),
    CREATED("最新发布", "CREATED_AT", "sort:created-desc"),
}

/** 列表查询条件。[author] 非空时走搜索（discussions 连接没有 author 参数），这时分类要用 [categoryName]。 */
data class DiscussionQuery(
    val categoryId: String? = null,
    val categoryName: String? = null,
    val state: DiscussionStateFilter = DiscussionStateFilter.OPEN,
    val sort: DiscussionSort = DiscussionSort.UPDATED,
    val author: String? = null,
)

/** 搜索语法：`author:` 只认登录名字符，分类名里的引号去掉。 */
fun discussionSearchText(owner: String, name: String, query: DiscussionQuery): String = buildString {
    append("repo:$owner/$name is:discussion")
    query.state.searchTerm?.let { append(' ').append(it) }
    query.author?.filter { it.isLetterOrDigit() || it == '-' }?.takeIf { it.isNotEmpty() }?.let { append(" author:").append(it) }
    query.categoryName?.replace("\"", "")?.trim()?.takeIf { it.isNotEmpty() }?.let { append(" category:\"").append(it).append('"') }
    append(' ').append(query.sort.searchTerm)
}

/** 只有这个分类限制发帖。GitHub 没有公告格式字段，按名字（slug 同名）认。 */
const val ANNOUNCEMENT_CATEGORY = "通知公告"

/** 公告分类只有仓库 ADMIN / MAINTAIN 能发；WRITE（协作者）和普通用户都不行。 */
fun canPostIn(category: GithubDiscussionCategory, viewerPermission: String?): Boolean =
    category.name.trim() != ANNOUNCEMENT_CATEGORY || viewerPermission == "ADMIN" || viewerPermission == "MAINTAIN"

fun postableCategories(categories: List<GithubDiscussionCategory>, viewerPermission: String?) =
    categories.filter { canPostIn(it, viewerPermission) }

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

/** 取数接口：只声明共享 UI 真正用到的三个方法。 */
interface GithubDiscussionsRepository {
    suspend fun list(owner: String, name: String, cursor: String?, query: DiscussionQuery = DiscussionQuery()): Result<GithubDiscussionPage>

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

    /** 别人对 [login] 的近期回复与 @，见 [CommunityActivity]。 */
    suspend fun activity(owner: String, name: String, login: String): Result<List<CommunityActivity>>
}
