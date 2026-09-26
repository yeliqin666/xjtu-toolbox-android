package com.xjtu.toolbox.community

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** 帖子详情：主帖、楼层、楼中楼的读取和各种操作；提交中的回复、点赞不随旋转屏幕丢失。 */
internal class DiscussionDetailViewModel(
    initial: GithubDiscussion,
    private val repo: GithubDiscussionsRepository,
) : ViewModel() {
    var discussion by mutableStateOf(initial); private set
    val loader = DiscussionCommentsLoader(viewModelScope) { cursor -> repo.comments(initial.id, cursor) }
    /** 最近一次失败的说明，界面显示几秒后清掉。 */
    var message by mutableStateOf<String?>(null)
    /** 某一楼刚收到新回复：把它的楼中楼展开并重新拉一遍。 */
    val replyRefresh = mutableStateMapOf<String, Int>()
    /** 楼中楼不在 loader 里：编辑 / 删除了其中一条，靠这个补丁通知各楼自己改。 */
    var patch by mutableStateOf<ReplyPatch?>(null); private set
    /** 刚发了新楼：拉完以后滚到最后一楼。 */
    var scrollToEnd by mutableStateOf(false)
    private val deletedChannel = Channel<Unit>(Channel.CONFLATED)
    val deleted = deletedChannel.receiveAsFlow()
    private var loadedAt = 0L

    init { reloadAll() }

    /** 重新打开同一个帖子：隔了一阵子才重拉。 */
    fun onShown() {
        if (System.currentTimeMillis() - loadedAt > 60_000) reloadAll()
    }

    fun reloadAll() {
        loadedAt = System.currentTimeMillis()
        loader.fetch(true)
        viewModelScope.launch {
            repo.detail(CommunityRepo.OWNER, CommunityRepo.NAME, discussion.number).onSuccess { discussion = it }
        }
    }

    private fun fail(action: String, error: Throwable) { message = failureText(action, error) }

    fun reactDiscussion(content: String) {
        val before = discussion
        discussion = before.copy(reactions = before.reactions.toggled(content))
        viewModelScope.launch {
            repo.react(before.id, content, !before.reactions.mine(content)).onFailure { discussion = before; fail("回应", it) }
        }
    }

    /** [apply] 把新状态写回界面上那一条（楼层走 loader，楼中楼走各自的预览）。 */
    fun reactComment(comment: GithubDiscussionComment, content: String, apply: (GithubDiscussionComment) -> Unit) {
        apply(comment.copy(reactions = comment.reactions.toggled(content)))
        viewModelScope.launch {
            repo.react(comment.id, content, !comment.reactions.mine(content)).onFailure { apply(comment); fail("回应", it) }
        }
    }

    fun vote(option: GithubPollOption) {
        val before = discussion
        val poll = before.poll ?: return
        discussion = before.copy(poll = poll.votedFor(option.id))
        viewModelScope.launch { repo.vote(option.id).onFailure { discussion = before; fail("投票", it) } }
    }

    /** 楼层和楼中楼都可能是它：两边都通知一遍。 */
    private fun patchComment(comment: GithubDiscussionComment) {
        loader.edited(comment)
        patch = ReplyPatch(edited = comment, deletedId = null, seq = (patch?.seq ?: 0) + 1)
    }

    /** 改帖子状态（关闭、锁定、采纳…），成功后整帖重拉拿最新权限。 */
    fun moderate(action: String, call: suspend GithubDiscussionsRepository.() -> Result<Unit>) {
        viewModelScope.launch { repo.call().fold(onSuccess = { reloadAll() }, onFailure = { fail(action, it) }) }
    }

    fun minimize(comment: GithubDiscussionComment, classifier: String) = viewModelScope.launch {
        repo.minimize(comment.id, classifier).fold(
            onSuccess = { patchComment(comment.copy(minimizedReason = classifier, canMinimize = false, canUnminimize = true)) },
            onFailure = { fail("折叠", it) },
        )
    }

    fun unminimize(comment: GithubDiscussionComment) = viewModelScope.launch {
        repo.unminimize(comment.id).fold(
            onSuccess = { patchComment(comment.copy(minimizedReason = null, canMinimize = true, canUnminimize = false)) },
            onFailure = { fail("取消折叠", it) },
        )
    }

    fun deleteComment(comment: GithubDiscussionComment) = viewModelScope.launch {
        repo.deleteComment(comment.id).fold(
            onSuccess = {
                val topLevel = loader.state.value.items.any { it.id == comment.id }
                loader.deleted(comment.id)
                if (topLevel) discussion = discussion.copy(comments = (discussion.comments - 1).coerceAtLeast(0))
                else patch = ReplyPatch(edited = null, deletedId = comment.id, seq = (patch?.seq ?: 0) + 1)
            },
            onFailure = { fail("删除", it) },
        )
    }

    fun deleteDiscussion() = viewModelScope.launch {
        repo.deleteDiscussion(discussion.id).fold(onSuccess = { deletedChannel.send(Unit) }, onFailure = { fail("删除", it) })
    }

    /** 编辑页提交：回帖、回复某楼、编辑回复、编辑主帖。在本作用域里跑，编辑页被重建也不会打断。 */
    suspend fun submit(target: EditorTarget, title: String?, body: String): Result<Unit> =
        viewModelScope.async { doSubmit(target, title, body) }.await()

    private suspend fun doSubmit(target: EditorTarget, title: String?, body: String): Result<Unit> = when (target) {
        EditorTarget.NewComment, is EditorTarget.Quote -> repo.reply(discussion.id, body).map {
            discussion = discussion.copy(comments = discussion.comments + 1)
            loader.fetch(true)
            scrollToEnd = true
        }
        is EditorTarget.ReplyTo -> repo.replyToComment(discussion.id, target.comment.id, body).map {
            loader.state.value.items.firstOrNull { it.id == target.comment.id }
                ?.let { loader.edited(it.copy(replyCount = it.replyCount + 1)) }
            replyRefresh[target.comment.id] = (replyRefresh[target.comment.id] ?: 0) + 1
        }
        is EditorTarget.EditComment -> repo.editComment(target.comment.id, body).map { patchComment(it) }
        EditorTarget.EditDiscussion -> repo.edit(discussion.id, title.orEmpty(), body).map { discussion = it }
    }
}

/** 失败提示：带上 GitHub 返回的原因（权限 / 限流 / 网络），并写日志。 */
internal fun failureText(action: String, error: Throwable): String {
    android.util.Log.w("Community", "$action failed", error)
    val reason = when (error) {
        is GithubSignedOutException -> "请先登录 GitHub"
        is GithubApiException -> when {
            error.rateLimited -> "GitHub 请求太频繁，等一会儿再试"
            error.statusCode == 401 -> "GitHub 登录已失效，请重新登录"
            error.statusCode == 403 -> "GitHub 拒绝了这次操作（HTTP 403）"
            else -> "GitHub 返回 HTTP ${error.statusCode}"
        }
        is GithubDiscussionException -> error.message
        is java.io.IOException -> "网络不通，检查网络后重试"
        else -> error.message
    }
    return if (reason.isNullOrBlank()) "${action}没成功，稍后再试" else "${action}没成功：$reason"
}
