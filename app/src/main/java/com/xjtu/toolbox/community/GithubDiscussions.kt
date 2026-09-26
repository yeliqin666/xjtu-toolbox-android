package com.xjtu.toolbox.community

// 改编自 JoyinJoester/Etoile（GPL-3.0）：github/domain/GithubDiscussion.kt、
// github/data/GithubDiscussionsRepositoryImpl.kt。补了头像、时间、点赞、楼中楼回复数、分类筛选，
// 评论解析合成一处；401（授权被撤销）时回调 onUnauthorized。

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

data class GithubDiscussionCategory(val id: String, val name: String, val acceptsAnswers: Boolean)
data class GithubDiscussion(
    val id: String, val number: Int, val title: String, val body: String,
    val url: String, val author: String?, val category: GithubDiscussionCategory,
    val comments: Int, val answered: Boolean, val canEdit: Boolean = false,
    val createdAt: String = "", val updatedAt: String = "", val authorAvatar: String? = null,
    val upvotes: Int = 0, val upvoted: Boolean = false, val canUpvote: Boolean = false,
    val canDelete: Boolean = false, val authorIsAdmin: Boolean = false,
)
data class GithubDiscussionPage(val repositoryId: String, val items: List<GithubDiscussion>, val nextCursor: String?)
data class GithubDiscussionComment(
    val id: String, val body: String, val author: String?, val isAnswer: Boolean,
    val canMarkAnswer: Boolean = false, val canUnmarkAnswer: Boolean = false,
    val canEdit: Boolean = false, val canDelete: Boolean = false,
    val createdAt: String = "", val replyCount: Int = 0, val authorAvatar: String? = null,
    val upvotes: Int = 0, val upvoted: Boolean = false, val canUpvote: Boolean = false,
    /** 楼中楼的前两条，列表里直接露出来；更多的点开再拉。 */
    val previewReplies: List<GithubDiscussionComment> = emptyList(),
    val authorIsAdmin: Boolean = false,
)
data class GithubDiscussionComments(val items: List<GithubDiscussionComment>, val nextCursor: String?)

class GithubDiscussionException(message: String? = null) :
    IllegalStateException(message ?: "GitHub discussion request failed")

class GithubDiscussionsRepository(
    private val token: () -> String?,
    private val onUnauthorized: () -> Unit = {},
    private val endpoint: String = "https://api.github.com/graphql"
) {
    suspend fun viewerLogin(): Result<String> = execute("query{viewer{login}}", buildJsonObject { }) {
        it.getValue("viewer").jsonObject.text("login")
    }

    suspend fun editComment(id: String, body: String): Result<GithubDiscussionComment> {
        if (id.isBlank() || body.isBlank() || body.length > 65536) {
            return Result.failure(IllegalArgumentException("Invalid comment"))
        }
        return execute("mutation(\$input:UpdateDiscussionCommentInput!){updateDiscussionComment(input:\$input){comment{$COMMENT_FIELDS}}}",
            buildJsonObject { put("input", buildJsonObject { put("commentId", id); put("body", body) }) }) { data ->
            comment(data.getValue("updateDiscussionComment").jsonObject.getValue("comment").jsonObject)
        }
    }

    suspend fun deleteComment(id: String): Result<Unit> {
        if (id.isBlank()) return Result.failure(IllegalArgumentException("Invalid comment ID"))
        return execute("mutation(\$input:DeleteDiscussionCommentInput!){deleteDiscussionComment(input:\$input){clientMutationId}}",
            buildJsonObject { put("input", buildJsonObject { put("id", id) }) }) { data ->
            data.getValue("deleteDiscussionComment").jsonObject
            Unit
        }
    }

    suspend fun replies(commentId: String, cursor: String?): Result<GithubDiscussionComments> = execute(
        "query(\$id:ID!,\$cursor:String){node(id:\$id){... on DiscussionComment{replies(first:50,after:\$cursor){nodes{$REPLY_FIELDS} pageInfo{hasNextPage endCursor}}}}}",
        buildJsonObject { put("id", commentId); put("cursor", cursor) }
    ) { data -> comments(data.getValue("node").jsonObject.getValue("replies").jsonObject) }

    suspend fun replyToComment(discussionId: String, commentId: String, body: String): Result<String> {
        if (discussionId.isBlank() || commentId.isBlank() || body.isBlank() || body.length > 65536) {
            return Result.failure(IllegalArgumentException("Invalid reply"))
        }
        return execute("mutation(\$input:AddDiscussionCommentInput!){addDiscussionComment(input:\$input){comment{id}}}",
            buildJsonObject { put("input", buildJsonObject {
                put("discussionId", discussionId); put("replyToId", commentId); put("body", body)
            }) }) { it.getValue("addDiscussionComment").jsonObject.getValue("comment").jsonObject.text("id") }
    }

    suspend fun edit(id: String, title: String, body: String): Result<GithubDiscussion> {
        if (id.isBlank() || title.isBlank() || title.length > 256 || body.length > 65536) {
            return Result.failure(IllegalArgumentException("Invalid discussion"))
        }
        return execute("mutation(\$input:UpdateDiscussionInput!){updateDiscussion(input:\$input){discussion{$FIELDS}}}",
            buildJsonObject { put("input", buildJsonObject {
                put("discussionId", id); put("title", title.trim()); put("body", body)
            }) }) { discussion(it.getValue("updateDiscussion").jsonObject.getValue("discussion").jsonObject) }
    }

    suspend fun markAnswer(commentId: String, answered: Boolean): Result<Unit> {
        if (commentId.isBlank()) return Result.failure(IllegalArgumentException("Invalid comment ID"))
        val mutation = if (answered) "markDiscussionCommentAsAnswer" else "unmarkDiscussionCommentAsAnswer"
        val input = if (answered) "MarkDiscussionCommentAsAnswerInput" else "UnmarkDiscussionCommentAsAnswerInput"
        return execute("mutation(\$input:$input!){$mutation(input:\$input){discussion{id}}}",
            buildJsonObject { put("input", buildJsonObject { put("id", commentId) }) }) { data ->
            data.getValue(mutation).jsonObject.getValue("discussion").jsonObject.text("id")
            Unit
        }
    }

    suspend fun comments(id: String, cursor: String?): Result<GithubDiscussionComments> = execute(
        "query(\$id:ID!,\$cursor:String){node(id:\$id){... on Discussion{comments(first:30,after:\$cursor){nodes{$COMMENT_FIELDS} pageInfo{hasNextPage endCursor}}}}}",
        buildJsonObject { put("id", id); put("cursor", cursor) }
    ) { data -> comments(data.getValue("node").jsonObject.getValue("comments").jsonObject) }

    suspend fun list(owner: String, name: String, cursor: String?, categoryId: String? = null): Result<GithubDiscussionPage> = execute(
        "query(\$owner:String!,\$name:String!,\$cursor:String,\$category:ID){repository(owner:\$owner,name:\$name){id discussions(first:20,after:\$cursor,categoryId:\$category,orderBy:{field:UPDATED_AT,direction:DESC}){nodes{$FIELDS} pageInfo{hasNextPage endCursor}}}}",
        buildJsonObject { put("owner", owner); put("name", name); put("cursor", cursor); put("category", categoryId) }
    ) { data ->
        val repo = data.getValue("repository").jsonObject
        val connection = repo.getValue("discussions").jsonObject
        val page = connection.getValue("pageInfo").jsonObject
        GithubDiscussionPage(repo.text("id"), connection.getValue("nodes").jsonArray.map { discussion(it.jsonObject) },
            if (page.getValue("hasNextPage").jsonPrimitive.boolean) page.text("endCursor") else null)
    }

    suspend fun detail(owner: String, name: String, number: Int): Result<GithubDiscussion> = execute(
        "query(\$owner:String!,\$name:String!,\$number:Int!){repository(owner:\$owner,name:\$name){discussion(number:\$number){$FIELDS}}}",
        buildJsonObject { put("owner", owner); put("name", name); put("number", number) }
    ) { discussion(it.getValue("repository").jsonObject.getValue("discussion").jsonObject) }

    /** 删除整个帖子（仓库管理员或发帖人）。 */
    suspend fun deleteDiscussion(id: String): Result<Unit> = execute(
        "mutation(\$input:DeleteDiscussionInput!){deleteDiscussion(input:\$input){clientMutationId}}",
        buildJsonObject { put("input", buildJsonObject { put("id", id) }) }
    ) { Unit }

    /** 给帖子或评论点赞 / 取消点赞（👍 表情回应）。 */
    suspend fun upvote(subjectId: String, add: Boolean): Result<Unit> {
        val mutation = if (add) "addReaction" else "removeReaction"
        val input = if (add) "AddReactionInput" else "RemoveReactionInput"
        return execute("mutation(\$input:$input!){$mutation(input:\$input){clientMutationId}}",
            buildJsonObject {
                put("input", buildJsonObject {
                    put("subjectId", subjectId)
                    put("content", THUMBS_UP)
                })
            }) { Unit }
    }

    suspend fun categories(owner: String, name: String): Result<List<GithubDiscussionCategory>> = execute(
        "query(\$owner:String!,\$name:String!){repository(owner:\$owner,name:\$name){discussionCategories(first:100){nodes{id name isAnswerable}}}}",
        buildJsonObject { put("owner", owner); put("name", name) }
    ) { data -> data.getValue("repository").jsonObject.getValue("discussionCategories").jsonObject
        .getValue("nodes").jsonArray.map { category(it.jsonObject) } }

    suspend fun create(repositoryId: String, categoryId: String, title: String, body: String): Result<GithubDiscussion> {
        if (title.isBlank() || title.length > 256 || body.isBlank() || body.length > 65536) return Result.failure(IllegalArgumentException("Invalid discussion"))
        return execute("mutation(\$input:CreateDiscussionInput!){createDiscussion(input:\$input){discussion{$FIELDS}}}",
            buildJsonObject { put("input", buildJsonObject {
                put("repositoryId", repositoryId); put("categoryId", categoryId); put("title", title.trim()); put("body", body)
            }) }) { discussion(it.getValue("createDiscussion").jsonObject.getValue("discussion").jsonObject) }
    }

    suspend fun reply(discussionId: String, body: String): Result<String> {
        if (body.isBlank() || body.length > 65536) return Result.failure(IllegalArgumentException("Invalid reply"))
        return execute("mutation(\$input:AddDiscussionCommentInput!){addDiscussionComment(input:\$input){comment{id}}}",
            buildJsonObject { put("input", buildJsonObject { put("discussionId", discussionId); put("body", body) }) }
        ) { it.getValue("addDiscussionComment").jsonObject.getValue("comment").jsonObject.text("id") }
    }

    private suspend fun <T> execute(query: String, variables: JsonObject, decode: (JsonObject) -> T): Result<T> = withContext(Dispatchers.IO) {
        githubRunCatching {
            val accessToken = token() ?: throw GithubSignedOutException()
            val body = buildJsonObject { put("query", query); put("variables", variables) }
            val request = GithubNetwork.builder(endpoint, accessToken)
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            GithubNetwork.client.newCall(request).execute().use { response ->
                if (response.code == 401) onUnauthorized()
                if (!response.isSuccessful) throw GithubApiException.of(response)
                val payload = Json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                val errors = payload["errors"]?.jsonArray
                if (errors?.isNotEmpty() == true) {
                    throw GithubDiscussionException(errors.first().jsonObject["message"]?.jsonPrimitive?.contentOrNull)
                }
                decode(payload.getValue("data").jsonObject)
            }
        }
    }

    private fun comments(connection: JsonObject): GithubDiscussionComments {
        val page = connection.getValue("pageInfo").jsonObject
        return GithubDiscussionComments(connection.getValue("nodes").jsonArray.map { comment(it.jsonObject) },
            if (page.getValue("hasNextPage").jsonPrimitive.boolean) page.text("endCursor") else null)
    }

    private fun comment(value: JsonObject): GithubDiscussionComment = GithubDiscussionComment(
        value.text("id"), value.text("body"),
        value["author"]?.takeUnless { it is JsonNull }?.jsonObject?.text("login"),
        value.getValue("isAnswer").jsonPrimitive.boolean,
        value["viewerCanMarkAsAnswer"]?.jsonPrimitive?.booleanOrNull ?: false,
        value["viewerCanUnmarkAsAnswer"]?.jsonPrimitive?.booleanOrNull ?: false,
        value["viewerCanUpdate"]?.jsonPrimitive?.booleanOrNull ?: false,
        value["viewerCanDelete"]?.jsonPrimitive?.booleanOrNull ?: false,
        value["createdAt"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        value["replies"]?.takeUnless { it is JsonNull }?.jsonObject?.get("totalCount")?.jsonPrimitive?.intOrNull ?: 0,
        avatar(value),
        likeCount(value),
        liked(value),
        canLike(value),
        value["replies"]?.takeUnless { it is JsonNull }?.jsonObject?.get("nodes")?.jsonArray
            ?.map { comment(it.jsonObject) }.orEmpty(),
        isAdmin(value),
    )

    /** 仓库主人、组织成员、协作者都算管理员。 */
    private fun isAdmin(value: JsonObject) =
        value["authorAssociation"]?.jsonPrimitive?.contentOrNull in setOf("OWNER", "MEMBER", "COLLABORATOR")

    private fun avatar(value: JsonObject) =
        value["author"]?.takeUnless { it is JsonNull }?.jsonObject?.get("avatarUrl")?.jsonPrimitive?.contentOrNull

    private fun category(value: JsonObject) = GithubDiscussionCategory(value.text("id"), value.text("name"), value.getValue("isAnswerable").jsonPrimitive.boolean)
    private fun discussion(value: JsonObject) = GithubDiscussion(value.text("id"), value.getValue("number").jsonPrimitive.int,
        value.text("title"), value.text("body"), value.text("url"),
        value["author"]?.takeUnless { it is JsonNull }?.jsonObject?.text("login"),
        category(value.getValue("category").jsonObject), value.getValue("comments").jsonObject.getValue("totalCount").jsonPrimitive.int,
        value["answer"]?.let { it !is JsonNull } == true,
        value["viewerCanUpdate"]?.jsonPrimitive?.booleanOrNull ?: false,
        value["createdAt"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        value["updatedAt"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        avatar(value),
        likeCount(value),
        liked(value),
        canLike(value),
        value["viewerCanDelete"]?.jsonPrimitive?.booleanOrNull ?: false,
        isAdmin(value))

    private fun thumbsUp(value: JsonObject): JsonObject? =
        value["reactionGroups"]?.takeUnless { it is JsonNull }?.jsonArray
            ?.map { it.jsonObject }
            ?.firstOrNull { it["content"]?.jsonPrimitive?.contentOrNull == THUMBS_UP }

    private fun likeCount(value: JsonObject) =
        thumbsUp(value)?.get("reactors")?.jsonObject?.get("totalCount")?.jsonPrimitive?.intOrNull ?: 0

    private fun liked(value: JsonObject) =
        thumbsUp(value)?.get("viewerHasReacted")?.jsonPrimitive?.booleanOrNull ?: false

    private fun canLike(value: JsonObject) = value["viewerCanReact"]?.jsonPrimitive?.booleanOrNull ?: false
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private companion object {
        const val AUTHOR = "author{login avatarUrl(size:80)}"
        const val THUMBS_UP = "THUMBS_UP"
        // 点赞用 👍 表情回应：upvote 不对 GitHub App 令牌开放（Resource not accessible by integration）
        const val LIKE = "viewerCanReact reactionGroups{content viewerHasReacted reactors{totalCount}}"
        const val REPLY_FIELDS = "id body createdAt $AUTHOR authorAssociation $LIKE isAnswer viewerCanMarkAsAnswer viewerCanUnmarkAsAnswer viewerCanUpdate viewerCanDelete"
        const val COMMENT_FIELDS = "$REPLY_FIELDS replies(first:2){totalCount nodes{$REPLY_FIELDS}}"
        const val FIELDS = "viewerCanUpdate viewerCanDelete authorAssociation id number title body url createdAt updatedAt $AUTHOR $LIKE category{id name isAnswerable} comments{totalCount} answer{id}"
    }
}
