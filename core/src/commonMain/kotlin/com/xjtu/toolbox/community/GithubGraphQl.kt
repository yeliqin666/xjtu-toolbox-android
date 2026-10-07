package com.xjtu.toolbox.community

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

// 改编自 JoyinJoester/Etoile（GPL-3.0）：github/domain/GithubDiscussion.kt、
// github/data/GithubDiscussionsRepositoryImpl.kt。补了头像、时间、表情回应、楼中楼、分类筛选、
// 置顶展示、关闭 / 锁定 / 折叠、投票；401（授权被撤销）时回调 onUnauthorized。
//
// ⚠️ 本文件是「社区那半个域」的**共享实现**：从 :app 的 `community/GithubDiscussions.kt`
// 搬进 commonMain，并把它与 okhttp 的唯一接触点（构造请求体 + `newCall().execute()`）
// 抽成 [GithubHttpTransport]。这样查询字符串、JSON 解析、限流/错误的判定只有一份，
// 两端各自只提供一次「把字节发出去」：Android = okhttp（`OkHttpGithubTransport`），
// Web = Ktor（`KtorGithubTransport`）。搬的时候**查询与解析逐字未动**。

/**
 * 一次 GraphQL 往返的原始结果 —— [GithubHttpTransport] 的返回值。
 *
 * 为什么不是直接把 `HttpClient` 传进来：`Request` / `Response` 这两种类型在 okhttp 与 Ktor 里
 * 各有一套，谁进来谁就把另一个端挡在门外。收成这个纯粹的数据类之后，**查询与解析只有一份**，
 * 两端各自只提供一次「把字节发出去」。
 *
 * 三个字段都有出处：`status` 判 401/成功，`retryAfter` 与 `rateLimitRemaining` 是限流判定要看的
 * 两个头（搬迁前写在 `GithubApiException.of(response)` 里）。
 */
class GithubHttpResponse(
    val status: Int,
    val body: String,
    val retryAfter: String? = null,
    val rateLimitRemaining: String? = null,
)

/** 「把一次 GraphQL POST 发出去」这一件事的端口。`fun interface` 是为了两端实现都只有一行。 */
fun interface GithubHttpTransport {
    suspend fun post(url: String, headers: Map<String, String>, jsonBody: String): GithubHttpResponse
}

/** 两端共用的请求头（搬迁前写在 `:app` 的 `GithubNetwork.builder` 里，一字未改）。 */
fun githubHeaders(token: String?): Map<String, String> = buildMap {
    put("Accept", "application/vnd.github+json")
    put("X-GitHub-Api-Version", "2022-11-28")
    put("User-Agent", "XJTUToolbox-Community")
    if (token != null) put("Authorization", "Bearer $token")
}

/** 限流判定：403 和 429 都会出现，光看状态码分不清「没权限」和「等会再试」。 */
fun githubApiExceptionOf(response: GithubHttpResponse): GithubApiException {
    val throttled = response.status == 429 ||
        (response.status == 403 && (response.retryAfter != null ||
            response.rateLimitRemaining?.trim() == "0"))
    return GithubApiException(statusCode = response.status, rateLimited = throttled)
}

/**
 * GitHub Discussions 的 GraphQL 实现（唯一一份）。
 *
 * @param dispatcher 阻塞式传输要用的调度器。Android 传 `Dispatchers.IO`（与搬迁前一致）；
 *   Web 传默认值即可 —— 那边 Ktor 的 fetch 本身是异步的，没有阻塞要躲。
 *   **`Dispatchers.IO` 是 JVM 专有的**，所以默认值只能是 [Dispatchers.Default]。
 */
class GraphQlGithubDiscussionsRepository(
    private val token: () -> String?,
    private val transport: GithubHttpTransport,
    private val onUnauthorized: () -> Unit = {},
    private val endpoint: String = "https://api.github.com/graphql",
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : GithubDiscussionsRepository {
    override suspend fun viewerLogin(): Result<String> = execute("query{viewer{login}}", buildJsonObject { }) {
        it.getValue("viewer").jsonObject.text("login")
    }

    override suspend fun editComment(id: String, body: String): Result<GithubDiscussionComment> {
        if (id.isBlank() || body.isBlank() || body.length > 65536) {
            return Result.failure(IllegalArgumentException("Invalid comment"))
        }
        return execute("mutation(\$input:UpdateDiscussionCommentInput!){updateDiscussionComment(input:\$input){comment{$COMMENT_FIELDS}}}",
            buildJsonObject { put("input", buildJsonObject { put("commentId", id); put("body", body) }) }) { data ->
            comment(data.getValue("updateDiscussionComment").jsonObject.getValue("comment").jsonObject)
        }
    }

    override suspend fun deleteComment(id: String): Result<Unit> {
        if (id.isBlank()) return Result.failure(IllegalArgumentException("Invalid comment ID"))
        return execute("mutation(\$input:DeleteDiscussionCommentInput!){deleteDiscussionComment(input:\$input){clientMutationId}}",
            buildJsonObject { put("input", buildJsonObject { put("id", id) }) }) { data ->
            data.getValue("deleteDiscussionComment").jsonObject
            Unit
        }
    }

    override suspend fun replies(commentId: String, cursor: String?): Result<GithubDiscussionComments> = execute(
        "query(\$id:ID!,\$cursor:String){node(id:\$id){... on DiscussionComment{replies(first:50,after:\$cursor){nodes{$REPLY_FIELDS} pageInfo{hasNextPage endCursor}}}}}",
        buildJsonObject { put("id", commentId); put("cursor", cursor) }
    ) { data -> comments(data.getValue("node").jsonObject.getValue("replies").jsonObject) }

    override suspend fun replyToComment(discussionId: String, commentId: String, body: String): Result<String> {
        if (discussionId.isBlank() || commentId.isBlank() || body.isBlank() || body.length > 65536) {
            return Result.failure(IllegalArgumentException("Invalid reply"))
        }
        return execute("mutation(\$input:AddDiscussionCommentInput!){addDiscussionComment(input:\$input){comment{id}}}",
            buildJsonObject { put("input", buildJsonObject {
                put("discussionId", discussionId); put("replyToId", commentId); put("body", body)
            }) }) { it.getValue("addDiscussionComment").jsonObject.getValue("comment").jsonObject.text("id") }
    }

    override suspend fun edit(id: String, title: String, body: String): Result<GithubDiscussion> {
        if (id.isBlank() || title.isBlank() || title.length > 256 || body.length > 65536) {
            return Result.failure(IllegalArgumentException("Invalid discussion"))
        }
        return execute("mutation(\$input:UpdateDiscussionInput!){updateDiscussion(input:\$input){discussion{$FIELDS}}}",
            buildJsonObject { put("input", buildJsonObject {
                put("discussionId", id); put("title", title.trim()); put("body", body)
            }) }) { discussion(it.getValue("updateDiscussion").jsonObject.getValue("discussion").jsonObject) }
    }

    override suspend fun markAnswer(commentId: String, answered: Boolean): Result<Unit> {
        if (commentId.isBlank()) return Result.failure(IllegalArgumentException("Invalid comment ID"))
        val mutation = if (answered) "markDiscussionCommentAsAnswer" else "unmarkDiscussionCommentAsAnswer"
        val input = if (answered) "MarkDiscussionCommentAsAnswerInput" else "UnmarkDiscussionCommentAsAnswerInput"
        return execute("mutation(\$input:$input!){$mutation(input:\$input){discussion{id}}}",
            buildJsonObject { put("input", buildJsonObject { put("id", commentId) }) }) { data ->
            data.getValue(mutation).jsonObject.getValue("discussion").jsonObject.text("id")
            Unit
        }
    }

    override suspend fun comments(id: String, cursor: String?): Result<GithubDiscussionComments> = execute(
        "query(\$id:ID!,\$cursor:String){node(id:\$id){... on Discussion{comments(first:30,after:\$cursor){nodes{$COMMENT_FIELDS} pageInfo{hasNextPage endCursor}}}}}",
        buildJsonObject { put("id", id); put("cursor", cursor) }
    ) { data -> comments(data.getValue("node").jsonObject.getValue("comments").jsonObject) }

    /** 第一页顺带拉置顶帖，排在最前面。 */
    override suspend fun list(owner: String, name: String, cursor: String?, categoryId: String?): Result<GithubDiscussionPage> = execute(
        "query(\$owner:String!,\$name:String!,\$cursor:String,\$category:ID,\$top:Boolean!){repository(owner:\$owner,name:\$name){id pinnedDiscussions(first:10) @include(if:\$top){nodes{discussion{$FIELDS}}} discussions(first:20,after:\$cursor,categoryId:\$category,orderBy:{field:UPDATED_AT,direction:DESC}){nodes{$FIELDS} pageInfo{hasNextPage endCursor}}}}",
        buildJsonObject { put("owner", owner); put("name", name); put("cursor", cursor); put("category", categoryId); put("top", cursor == null) }
    ) { data ->
        val repo = data.getValue("repository").jsonObject
        val pinned = repo.obj("pinnedDiscussions")?.getValue("nodes")?.jsonArray.orEmpty()
            .map { discussion(it.jsonObject.getValue("discussion").jsonObject).copy(pinned = true) }
            .filter { categoryId == null || it.category.id == categoryId }
        val connection = repo.getValue("discussions").jsonObject
        val page = connection.getValue("pageInfo").jsonObject
        val items = connection.getValue("nodes").jsonArray.map { discussion(it.jsonObject) }
        GithubDiscussionPage(repo.text("id"), (pinned + items).distinctBy { it.id },
            if (page.getValue("hasNextPage").jsonPrimitive.boolean) page.text("endCursor") else null)
    }

    override suspend fun detail(owner: String, name: String, number: Int): Result<GithubDiscussion> = execute(
        "query(\$owner:String!,\$name:String!,\$number:Int!){repository(owner:\$owner,name:\$name){discussion(number:\$number){$FIELDS}}}",
        buildJsonObject { put("owner", owner); put("name", name); put("number", number) }
    ) { discussion(it.getValue("repository").jsonObject.getValue("discussion").jsonObject) }

    /** 删除整个帖子（仓库管理员或发帖人）。 */
    override suspend fun deleteDiscussion(id: String): Result<Unit> = execute(
        "mutation(\$input:DeleteDiscussionInput!){deleteDiscussion(input:\$input){clientMutationId}}",
        buildJsonObject { put("input", buildJsonObject { put("id", id) }) }
    ) { Unit }

    /** 给帖子或评论加 / 撤一个表情回应（点赞就是 👍）。 */
    override suspend fun react(subjectId: String, content: String, add: Boolean): Result<Unit> =
        if (add) mutate("addReaction", "AddReactionInput", buildJsonObject { put("subjectId", subjectId); put("content", content) })
        else mutate("removeReaction", "RemoveReactionInput", buildJsonObject { put("subjectId", subjectId); put("content", content) })

    /** 关闭帖子，[reason] 为 RESOLVED / OUTDATED / DUPLICATE。 */
    override suspend fun close(id: String, reason: String): Result<Unit> =
        mutate("closeDiscussion", "CloseDiscussionInput", buildJsonObject { put("discussionId", id); put("reason", reason) })

    override suspend fun reopen(id: String): Result<Unit> =
        mutate("reopenDiscussion", "ReopenDiscussionInput", buildJsonObject { put("discussionId", id) })

    override suspend fun lock(id: String, locked: Boolean): Result<Unit> =
        if (locked) mutate("lockLockable", "LockLockableInput", buildJsonObject { put("lockableId", id) })
        else mutate("unlockLockable", "UnlockLockableInput", buildJsonObject { put("lockableId", id) })

    /** 折叠回复，[classifier] 为 ReportedContentClassifiers 枚举名。 */
    override suspend fun minimize(id: String, classifier: String): Result<Unit> =
        mutate("minimizeComment", "MinimizeCommentInput", buildJsonObject { put("subjectId", id); put("classifier", classifier) })

    override suspend fun unminimize(id: String): Result<Unit> =
        mutate("unminimizeComment", "UnminimizeCommentInput", buildJsonObject { put("subjectId", id) })

    override suspend fun vote(optionId: String): Result<Unit> =
        mutate("addDiscussionPollVote", "AddDiscussionPollVoteInput", buildJsonObject { put("pollOptionId", optionId) })

    override suspend fun categories(owner: String, name: String): Result<List<GithubDiscussionCategory>> = execute(
        "query(\$owner:String!,\$name:String!){repository(owner:\$owner,name:\$name){discussionCategories(first:100){nodes{id name isAnswerable}}}}",
        buildJsonObject { put("owner", owner); put("name", name) }
    ) { data -> data.getValue("repository").jsonObject.getValue("discussionCategories").jsonObject
        .getValue("nodes").jsonArray.map { category(it.jsonObject) } }

    override suspend fun create(repositoryId: String, categoryId: String, title: String, body: String): Result<GithubDiscussion> {
        if (title.isBlank() || title.length > 256 || body.isBlank() || body.length > 65536) return Result.failure(IllegalArgumentException("Invalid discussion"))
        return execute("mutation(\$input:CreateDiscussionInput!){createDiscussion(input:\$input){discussion{$FIELDS}}}",
            buildJsonObject { put("input", buildJsonObject {
                put("repositoryId", repositoryId); put("categoryId", categoryId); put("title", title.trim()); put("body", body)
            }) }) { discussion(it.getValue("createDiscussion").jsonObject.getValue("discussion").jsonObject) }
    }

    override suspend fun reply(discussionId: String, body: String): Result<String> {
        if (body.isBlank() || body.length > 65536) return Result.failure(IllegalArgumentException("Invalid reply"))
        return execute("mutation(\$input:AddDiscussionCommentInput!){addDiscussionComment(input:\$input){comment{id}}}",
            buildJsonObject { put("input", buildJsonObject { put("discussionId", discussionId); put("body", body) }) }
        ) { it.getValue("addDiscussionComment").jsonObject.getValue("comment").jsonObject.text("id") }
    }

    private suspend fun mutate(mutation: String, inputType: String, input: JsonObject): Result<Unit> =
        execute("mutation(\$input:$inputType!){$mutation(input:\$input){clientMutationId}}",
            buildJsonObject { put("input", input) }) { Unit }

    /**
     * 一次 GraphQL 往返：鉴权头、401 回调、限流/错误判定、`data` 的取出——全在这一处。
     *
     * 与搬迁前**唯一的差别**是「发出去」这一步走了 [transport]（而不是 okhttp 的
     * `GithubNetwork.client.newCall(...)`），以及调度器由构造参数给（:app 传 `Dispatchers.IO`）。
     */
    private suspend fun <T> execute(query: String, variables: JsonObject, decode: (JsonObject) -> T): Result<T> = withContext(dispatcher) {
        githubRunCatching {
            val accessToken = token() ?: throw GithubSignedOutException()
            val body = buildJsonObject { put("query", query); put("variables", variables) }
            val response = transport.post(endpoint, githubHeaders(accessToken), body.toString())
            if (response.status == 401) onUnauthorized()
            if (response.status !in 200..299) throw githubApiExceptionOf(response)
            val payload = Json.parseToJsonElement(response.body).jsonObject
            val errors = payload["errors"]?.jsonArray
            if (errors?.isNotEmpty() == true) {
                throw GithubDiscussionException(errors.first().jsonObject["message"]?.jsonPrimitive?.contentOrNull)
            }
            decode(payload.getValue("data").jsonObject)
        }
    }

    private fun comments(connection: JsonObject): GithubDiscussionComments {
        val page = connection.getValue("pageInfo").jsonObject
        return GithubDiscussionComments(connection.getValue("nodes").jsonArray.map { comment(it.jsonObject) },
            if (page.getValue("hasNextPage").jsonPrimitive.boolean) page.text("endCursor") else null)
    }

    private fun comment(value: JsonObject): GithubDiscussionComment = GithubDiscussionComment(
        id = value.text("id"),
        body = value.text("body"),
        author = login(value),
        isAnswer = value.flag("isAnswer"),
        canMarkAnswer = value.flag("viewerCanMarkAsAnswer"),
        canUnmarkAnswer = value.flag("viewerCanUnmarkAsAnswer"),
        canEdit = value.flag("viewerCanUpdate"),
        canDelete = value.flag("viewerCanDelete"),
        createdAt = value.optText("createdAt").orEmpty(),
        replyCount = value.obj("replies")?.get("totalCount")?.jsonPrimitive?.intOrNull ?: 0,
        authorAvatar = avatar(value),
        reactions = reactions(value),
        canReact = value.flag("viewerCanReact"),
        previewReplies = value.obj("replies")?.get("nodes")?.jsonArray?.map { comment(it.jsonObject) }.orEmpty(),
        authorIsAdmin = isAdmin(value),
        url = value.optText("url").orEmpty(),
        minimizedReason = if (value.flag("isMinimized")) value.optText("minimizedReason").orEmpty() else null,
        canMinimize = value.flag("viewerCanMinimize"),
        canUnminimize = value.flag("viewerCanUnminimize"),
    )

    /** 仓库主人、组织成员、协作者都算管理员。 */
    private fun isAdmin(value: JsonObject) =
        value["authorAssociation"]?.jsonPrimitive?.contentOrNull in setOf("OWNER", "MEMBER", "COLLABORATOR")

    private fun avatar(value: JsonObject) = value.obj("author")?.optText("avatarUrl")
    private fun login(value: JsonObject) = value.obj("author")?.text("login")

    private fun category(value: JsonObject) = GithubDiscussionCategory(value.text("id"), value.text("name"), value.flag("isAnswerable"))
    private fun discussion(value: JsonObject) = GithubDiscussion(
        id = value.text("id"),
        number = value.getValue("number").jsonPrimitive.int,
        title = value.text("title"),
        body = value.text("body"),
        url = value.text("url"),
        author = login(value),
        category = category(value.getValue("category").jsonObject),
        comments = value.getValue("comments").jsonObject.getValue("totalCount").jsonPrimitive.int,
        answered = value.obj("answer") != null,
        canEdit = value.flag("viewerCanUpdate"),
        createdAt = value.optText("createdAt").orEmpty(),
        updatedAt = value.optText("updatedAt").orEmpty(),
        authorAvatar = avatar(value),
        reactions = reactions(value),
        canReact = value.flag("viewerCanReact"),
        canDelete = value.flag("viewerCanDelete"),
        authorIsAdmin = isAdmin(value),
        closedReason = if (value.flag("closed")) value.optText("stateReason") ?: "RESOLVED" else null,
        canClose = value.flag("viewerCanClose"),
        canReopen = value.flag("viewerCanReopen"),
        locked = value.flag("locked"),
        canModerate = value.obj("repository")?.optText("viewerPermission") in setOf("ADMIN", "MAINTAIN", "WRITE", "TRIAGE"),
        poll = value.obj("poll")?.let(::poll),
    )

    private fun poll(value: JsonObject) = GithubPoll(
        question = value.text("question"),
        options = value.getValue("options").jsonObject.getValue("nodes").jsonArray.map {
            val o = it.jsonObject
            GithubPollOption(o.text("id"), o.text("option"), o.getValue("totalVoteCount").jsonPrimitive.int, o.flag("viewerHasVoted"))
        },
        total = value.getValue("totalVoteCount").jsonPrimitive.int,
        canVote = value.flag("viewerCanVote"),
        voted = value.flag("viewerHasVoted"),
    )

    private fun reactions(value: JsonObject): List<GithubReaction> =
        value["reactionGroups"]?.takeUnless { it is JsonNull }?.jsonArray.orEmpty().map { it.jsonObject }.mapNotNull {
            val count = it.obj("reactors")?.get("totalCount")?.jsonPrimitive?.intOrNull ?: 0
            val mine = it.flag("viewerHasReacted")
            if (count > 0 || mine) GithubReaction(it.text("content"), count, mine) else null
        }

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.optText(key: String) = get(key)?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull
    private fun JsonObject.flag(key: String) = get(key)?.takeUnless { it is JsonNull }?.jsonPrimitive?.booleanOrNull ?: false
    private fun JsonObject.obj(key: String) = get(key)?.takeUnless { it is JsonNull }?.jsonObject

    private companion object {
        const val AUTHOR = "author{login avatarUrl(size:80)}"
        const val REACTIONS = "viewerCanReact reactionGroups{content viewerHasReacted reactors{totalCount}}"
        const val REPLY_FIELDS = "id url body createdAt $AUTHOR authorAssociation $REACTIONS isAnswer viewerCanMarkAsAnswer viewerCanUnmarkAsAnswer viewerCanUpdate viewerCanDelete isMinimized minimizedReason viewerCanMinimize viewerCanUnminimize"
        const val COMMENT_FIELDS = "$REPLY_FIELDS replies(first:2){totalCount nodes{$REPLY_FIELDS}}"
        const val POLL = "poll{question totalVoteCount viewerCanVote viewerHasVoted options(first:20){nodes{id option totalVoteCount viewerHasVoted}}}"
        const val FIELDS = "viewerCanUpdate viewerCanDelete viewerCanClose viewerCanReopen authorAssociation id number title body url createdAt updatedAt closed stateReason locked repository{viewerPermission} $AUTHOR $REACTIONS $POLL category{id name isAnswerable} comments{totalCount} answer{id}"
    }
}
