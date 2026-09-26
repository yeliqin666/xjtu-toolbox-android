package com.xjtu.toolbox.community

// 改编自 JoyinJoester/Etoile（GPL-3.0）：github/feature/discussions/DiscussionDetailScreen.kt、
// DiscussionCommentActions.kt。界面改用 MIUIX，按论坛楼层重排：主帖卡片、N 楼、楼主标记、楼中楼预览、
// 点赞、底部回帖栏、全屏编辑页（编辑 / 预览），删除走二次确认。

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.ui.components.AppCardColor
import com.xjtu.toolbox.ui.components.AppRefreshTexts
import com.xjtu.toolbox.ui.components.ErrorState
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** 楼中楼里某条被编辑（[edited]）或删除（[deletedId]）了；[seq] 保证同一条连改两次也会触发。 */
internal data class ReplyPatch(val edited: GithubDiscussionComment?, val deletedId: String?, val seq: Int)

/** 编辑页正在写什么。 */
internal sealed interface EditorTarget {
    data object NewComment : EditorTarget
    /** 回复某一楼；[mention] 是回复楼中楼时预填的「@某人 」。 */
    data class ReplyTo(val comment: GithubDiscussionComment, val mention: String?) : EditorTarget
    data class EditComment(val comment: GithubDiscussionComment) : EditorTarget
    data object EditDiscussion : EditorTarget
}

@Composable
fun DiscussionDetailScreen(
    initial: GithubDiscussion,
    repo: GithubDiscussionsRepository,
    onChanged: (GithubDiscussion) -> Unit,
    onDeleted: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var discussion by remember(initial.id) { mutableStateOf(initial) }
    fun update(value: GithubDiscussion) { discussion = value; onChanged(value) }

    val loader = remember(initial.id) { DiscussionCommentsLoader(scope) { cursor -> repo.comments(initial.id, cursor) } }
    val comments by loader.state.collectAsState()
    var refreshing by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<EditorTarget?>(null) }
    var deleting by remember { mutableStateOf<GithubDiscussionComment?>(null) }
    var deletingPost by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    // 某一楼刚收到新回复：把它的楼中楼展开并重新拉一遍
    val replyRefresh = remember(initial.id) { mutableStateMapOf<String, Int>() }
    // 楼中楼不在 loader 里：编辑 / 删除了其中一条，靠这个补丁通知各楼自己改
    var patch by remember(initial.id) { mutableStateOf<ReplyPatch?>(null) }
    // 刚发了新楼：拉完以后滚到最后一楼
    var scrollToEnd by remember(initial.id) { mutableStateOf(false) }
    // 本帖参与者，写回复时 @ 联想用
    val participants = remember(discussion, comments.items) {
        buildList {
            discussion.author?.let { add(CommunityUser(it, discussion.authorAvatar)) }
            comments.items.forEach { c ->
                c.author?.let { add(CommunityUser(it, c.authorAvatar)) }
                c.previewReplies.forEach { r -> r.author?.let { add(CommunityUser(it, r.authorAvatar)) } }
            }
        }.distinctBy { it.login }
    }

    fun reloadAll() {
        loader.fetch(true)
        scope.launch {
            repo.detail(CommunityRepo.OWNER, CommunityRepo.NAME, initial.number).onSuccess { update(it) }
        }
    }
    LaunchedEffect(initial.id) { reloadAll() }
    LaunchedEffect(comments.loading) { if (!comments.loading) refreshing = false }

    fun toggleDiscussionUpvote() {
        val before = discussion
        val add = !before.upvoted
        update(before.copy(upvoted = add, upvotes = before.upvotes + if (add) 1 else -1))
        scope.launch {
            repo.upvote(before.id, add).onFailure { update(before); message = failureText("点赞", it) }
        }
    }
    fun toggleCommentUpvote(comment: GithubDiscussionComment, apply: (GithubDiscussionComment) -> Unit) {
        val add = !comment.upvoted
        apply(comment.copy(upvoted = add, upvotes = comment.upvotes + if (add) 1 else -1))
        scope.launch {
            repo.upvote(comment.id, add).onFailure { apply(comment); message = failureText("点赞", it) }
        }
    }
    fun toggleAnswer(comment: GithubDiscussionComment) {
        scope.launch {
            repo.markAnswer(comment.id, !comment.isAnswer).fold(
                onSuccess = { reloadAll() },
                onFailure = { message = failureText("操作", it) },
            )
        }
    }

    // 放在编辑页的 return 之前：开关编辑页时楼层列表的滚动位置不丢
    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            last >= listState.layoutInfo.totalItemsCount - 2
        }
    }
    LaunchedEffect(nearEnd, comments.cursor, comments.failed) {
        if (nearEnd && comments.cursor != null && !comments.failed && !comments.loading) loader.fetch(false)
    }
    LaunchedEffect(scrollToEnd, comments.loading, comments.items.size) {
        if (scrollToEnd && !comments.loading && comments.items.isNotEmpty()) {
            // 前面有主帖和「全部回复」两项
            listState.animateScrollToItem(1 + comments.items.size)
            scrollToEnd = false
        }
    }


    deleting?.let { comment ->
        OverlayDialog(
            show = true,
            title = "删除这条回复？",
            summary = "删除后无法恢复，楼中楼的回复也可能一起被删。",
            onDismissRequest = { deleting = null },
        ) {
            Row(Modifier.fillMaxWidth()) {
                TextButton(text = "取消", onClick = { deleting = null }, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = "删除",
                    onClick = {
                        deleting = null
                        scope.launch {
                            repo.deleteComment(comment.id).fold(
                                onSuccess = {
                                    val topLevel = comments.items.any { it.id == comment.id }
                                    loader.deleted(comment.id)
                                    if (topLevel) update(discussion.copy(comments = (discussion.comments - 1).coerceAtLeast(0)))
                                    if (!topLevel) patch = ReplyPatch(edited = null, deletedId = comment.id, seq = (patch?.seq ?: 0) + 1)
                                },
                                onFailure = { message = failureText("删除", it) },
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
    if (deletingPost) {
        OverlayDialog(
            show = true,
            title = "删除整个帖子？",
            summary = "帖子和下面所有回复都会被删掉，无法恢复。",
            onDismissRequest = { deletingPost = false },
        ) {
            Row(Modifier.fillMaxWidth()) {
                TextButton(text = "取消", onClick = { deletingPost = false }, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = "删除",
                    onClick = {
                        deletingPost = false
                        scope.launch {
                            repo.deleteDiscussion(discussion.id).fold(
                                onSuccess = { onDeleted() },
                                onFailure = { message = failureText("删除", it) },
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
    message?.let { text ->
        LaunchedEffect(text) { kotlinx.coroutines.delay(2500); message = null }
    }


    AnimatedContent(
        targetState = editor,
        contentKey = { it?.let { t -> "editor:${t.hashCode()}" } ?: "detail" },
        transitionSpec = { communitySlide(forward = targetState != null) },
        label = "detailEditor",
    ) { shownEditor ->
    // ── 全屏编辑页：回帖 / 回复某楼 / 编辑 ──
    if (shownEditor != null) {
        val target = shownEditor
        CommunityEditorPage(
            target = target,
            discussion = discussion,
            onClose = { editor = null },
            mentionCandidates = participants,
            onSubmit = { title, body ->
                when (target) {
                    EditorTarget.NewComment -> repo.reply(discussion.id, body).map {
                        update(discussion.copy(comments = discussion.comments + 1))
                        loader.fetch(true)
                        scrollToEnd = true
                    }
                    is EditorTarget.ReplyTo -> repo.replyToComment(discussion.id, target.comment.id, body).map {
                        comments.items.firstOrNull { it.id == target.comment.id }
                            ?.let { loader.edited(it.copy(replyCount = it.replyCount + 1)) }
                        replyRefresh[target.comment.id] = (replyRefresh[target.comment.id] ?: 0) + 1
                    }
                    is EditorTarget.EditComment -> repo.editComment(target.comment.id, body).map {
                        loader.edited(it)
                        patch = ReplyPatch(edited = it, deletedId = null, seq = (patch?.seq ?: 0) + 1)
                    }
                    EditorTarget.EditDiscussion -> repo.edit(discussion.id, title.orEmpty(), body).map { update(it) }
                }
            },
        )
    } else {
    CommunityPage(
        title = discussion.category.name,
        onBack = onBack,
        actions = { CommunityBarAction(Icons.Outlined.OpenInBrowser, "在浏览器打开") { uriHandler.openUri(discussion.url) } },
        bottomBar = {
            ReplyBar(
                discussion = discussion,
                onReply = { editor = EditorTarget.NewComment },
                onUpvote = ::toggleDiscussionUpvote,
            )
        },
    ) { top ->
        PullToRefresh(
            refreshTexts = AppRefreshTexts,
            isRefreshing = refreshing,
            onRefresh = { refreshing = true; reloadAll() },
            contentPadding = PaddingValues(top = top),
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().overScrollVertical(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = top + 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "op") {
                    OriginalPost(
                        discussion = discussion,
                        onUpvote = ::toggleDiscussionUpvote,
                        onEdit = { editor = EditorTarget.EditDiscussion },
                        onDelete = { deletingPost = true },
                    )
                }
                item(key = "section") {
                    Row(Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("全部回复", style = MiuixTheme.textStyles.headline2, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(6.dp))
                        Text("${discussion.comments}", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        message?.let {
                            Spacer(Modifier.weight(1f))
                            Text(it, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.error)
                        }
                    }
                }
                itemsIndexed(comments.items, key = { _, c -> c.id }) { index, comment ->
                    Floor(
                        modifier = Modifier.animateItem(),
                        floor = index + 1,
                        comment = comment,
                        op = discussion.author,
                        answerable = discussion.category.acceptsAnswers,
                        refreshKey = replyRefresh[comment.id] ?: 0,
                        repo = repo,
                        onUpvote = { c, apply -> toggleCommentUpvote(c, apply) },
                        onUpvoteFloor = { toggleCommentUpvote(comment) { loader.edited(it) } },
                        onReply = { mention -> editor = EditorTarget.ReplyTo(comment, mention) },
                        onEdit = { editor = EditorTarget.EditComment(it) },
                        onDelete = { deleting = it },
                        onToggleAnswer = { toggleAnswer(comment) },
                        patch = patch,
                    )
                }
                item(key = "footer") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                        when {
                            comments.failed && comments.items.isEmpty() ->
                                ErrorState("回复加载失败", onRetry = { loader.fetch(true) }, modifier = Modifier.fillMaxWidth())
                            comments.failed -> TextButton(text = "加载失败，点这里重试", onClick = { loader.fetch(false) })
                            comments.loading -> CircularProgressIndicator(size = 22.dp)
                            comments.loaded && comments.items.isEmpty() -> Text(
                                "还没有人回复，来抢沙发",
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            comments.loaded && comments.cursor == null -> Text(
                                "— 到底了 —",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }
        }
    }
    }
    }
}

@Composable
private fun OriginalPost(discussion: GithubDiscussion, onUpvote: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        insideMargin = PaddingValues(16.dp),
        colors = CardDefaults.defaultColors(color = AppCardColor),
    ) {
        Text(discussion.title, style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CommunityAvatar(discussion.authorAvatar, discussion.author, 36.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(discussion.author ?: "已注销用户", style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(6.dp))
                    CommunityTag("楼主")
                    if (discussion.authorIsAdmin) {
                        Spacer(Modifier.width(4.dp))
                        AdminTag()
                    }
                }
                Text(
                    communityTime(discussion.createdAt),
                    style = MiuixTheme.textStyles.footnote1,
                    color = colors.onSurfaceVariantSummary,
                )
            }
            CommunityTag(discussion.category.name, colors.onSurfaceVariantSummary)
        }
        Spacer(Modifier.height(12.dp))
        MarkdownText(discussion.body.ifBlank { "（没有正文）" })
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CommunityAction(
                Icons.Outlined.ThumbUp,
                if (discussion.upvotes > 0) "${discussion.upvotes}" else "赞",
                active = discussion.upvoted,
                activeIcon = Icons.Filled.ThumbUp,
                enabled = discussion.canUpvote || discussion.upvoted,
                onClick = onUpvote,
            )
            if (discussion.answered) {
                Spacer(Modifier.width(4.dp))
                CommunityTag("已解答", COMMUNITY_GREEN)
            }
            Spacer(Modifier.weight(1f))
            if (discussion.canEdit) CommunityAction(Icons.Outlined.Edit, "编辑", onClick = onEdit)
            if (discussion.canDelete) CommunityAction(Icons.Outlined.Delete, "删除", onClick = onDelete)
        }
    }
}

@Composable
private fun Floor(
    modifier: Modifier,
    floor: Int,
    comment: GithubDiscussionComment,
    op: String?,
    answerable: Boolean,
    refreshKey: Int,
    repo: GithubDiscussionsRepository,
    onUpvote: (GithubDiscussionComment, (GithubDiscussionComment) -> Unit) -> Unit,
    onUpvoteFloor: () -> Unit,
    onReply: (mention: String?) -> Unit,
    onEdit: (GithubDiscussionComment) -> Unit,
    onDelete: (GithubDiscussionComment) -> Unit,
    onToggleAnswer: () -> Unit,
    patch: ReplyPatch?,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        insideMargin = PaddingValues(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 6.dp),
        colors = CardDefaults.defaultColors(color = AppCardColor),
    ) {
        AuthorLine(comment, op, avatar = 32.dp, trailing = "$floor 楼")
        Column(Modifier.padding(start = 42.dp)) {
            Spacer(Modifier.height(6.dp))
            MarkdownText(comment.body)
            Row(verticalAlignment = Alignment.CenterVertically) {
                CommunityAction(
                    Icons.Outlined.ThumbUp,
                    if (comment.upvotes > 0) "${comment.upvotes}" else "赞",
                    active = comment.upvoted,
                    activeIcon = Icons.Filled.ThumbUp,
                    enabled = comment.canUpvote || comment.upvoted,
                    onClick = onUpvoteFloor,
                )
                CommunityAction(Icons.AutoMirrored.Outlined.Reply, "回复", onClick = { onReply(null) })
                if (answerable && (if (comment.isAnswer) comment.canUnmarkAnswer else comment.canMarkAnswer)) {
                    CommunityAction(
                        Icons.Outlined.CheckCircle,
                        if (comment.isAnswer) "取消采纳" else "采纳",
                        active = comment.isAnswer,
                        onClick = onToggleAnswer,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (comment.canEdit) CommunityAction(Icons.Outlined.Edit, "", onClick = { onEdit(comment) })
                if (comment.canDelete) CommunityAction(Icons.Outlined.Delete, "", onClick = { onDelete(comment) })
            }
            DiscussionReplies(
                comment = comment,
                op = op,
                refreshKey = refreshKey,
                load = { cursor -> repo.replies(comment.id, cursor) },
                onUpvote = onUpvote,
                onReply = { reply -> onReply(reply.author?.let { "@$it " }) },
                onEdit = onEdit,
                onDelete = onDelete,
                patch = patch,
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}

/** 头像 + 用户名 + 楼主 / 已采纳 + 时间，右边可带楼层号。 */
@Composable
internal fun AuthorLine(comment: GithubDiscussionComment, op: String?, avatar: androidx.compose.ui.unit.Dp, trailing: String? = null) {
    val colors = MiuixTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        CommunityAvatar(comment.authorAvatar, comment.author, avatar)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(comment.author ?: "已注销用户", style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.SemiBold)
                if (op != null && comment.author == op) CommunityTag("楼主")
                if (comment.authorIsAdmin) AdminTag()
                if (comment.isAnswer) CommunityTag("已采纳", COMMUNITY_GREEN)
            }
            Text(communityTime(comment.createdAt), style = MiuixTheme.textStyles.footnote2, color = colors.onSurfaceVariantSummary)
        }
        if (trailing != null) Text(trailing, style = MiuixTheme.textStyles.footnote1, color = colors.onSurfaceVariantSummary)
    }
}

/** 详情页底部常驻的回帖栏：像论坛一样点一下就开写。 */
@Composable
private fun ReplyBar(discussion: GithubDiscussion, onReply: () -> Unit, onUpvote: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(20.dp))
                .background(colors.surfaceContainerHigh)
                .clickable(onClick = onReply)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text("说点什么…", style = MiuixTheme.textStyles.body2, color = colors.onSurfaceVariantSummary)
        }
        Spacer(Modifier.width(8.dp))
        CommunityAction(
            Icons.Outlined.ThumbUp,
            if (discussion.upvotes > 0) "${discussion.upvotes}" else "赞",
            active = discussion.upvoted,
            activeIcon = Icons.Filled.ThumbUp,
            enabled = discussion.canUpvote || discussion.upvoted,
            onClick = onUpvote,
        )
    }
}

/** 全屏编辑页：回帖、回复某楼、编辑回复、编辑主帖都用它。 */
@Composable
private fun CommunityEditorPage(
    target: EditorTarget,
    discussion: GithubDiscussion,
    onClose: () -> Unit,
    mentionCandidates: List<CommunityUser>,
    onSubmit: suspend (title: String?, body: String) -> Result<Unit>,
) {
    val scope = rememberCoroutineScope()
    val initialBody = when (target) {
        EditorTarget.NewComment -> ""
        is EditorTarget.ReplyTo -> target.mention.orEmpty()
        is EditorTarget.EditComment -> target.comment.body
        EditorTarget.EditDiscussion -> discussion.body
    }
    val withTitle = target == EditorTarget.EditDiscussion
    var title by rememberSaveable(target) { mutableStateOf(if (withTitle) discussion.title else "") }
    var body by rememberSaveable(target) { mutableStateOf(initialBody) }
    var preview by rememberSaveable(target) { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val dirty = body != initialBody || (withTitle && title != discussion.title)
    val close = { if (!sending) { if (dirty && body.isNotBlank()) confirmDiscard = true else onClose() } }
    BackHandler(onBack = close)

    val pageTitle = when (target) {
        EditorTarget.NewComment -> "回复楼主"
        is EditorTarget.ReplyTo -> "回复 ${target.comment.author ?: "这一楼"}"
        is EditorTarget.EditComment -> "编辑回复"
        EditorTarget.EditDiscussion -> "编辑帖子"
    }
    val submitLabel = if (target is EditorTarget.EditComment || withTitle) "保存" else "发送"
    val canSubmit = !sending && body.isNotBlank() && (!withTitle || title.isNotBlank())
    val submit = {
        sending = true; error = null
        scope.launch {
            onSubmit(if (withTitle) title else null, body).fold(
                onSuccess = { onClose() },
                onFailure = { e -> error = (e as? GithubDiscussionException)?.message?.let { "没成功：$it" } ?: "没成功，检查网络后重试" },
            )
            sending = false
        }
        Unit
    }

    CommunityPage(
        title = pageTitle,
        onBack = close,
        actions = {
            TextButton(
                text = if (sending) "$submitLabel…" else submitLabel,
                onClick = submit,
                enabled = canSubmit,
                colors = ButtonDefaults.textButtonColorsPrimary(),
                minHeight = 36.dp,
            )
        },
    ) { top ->
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = top + 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (target is EditorTarget.ReplyTo) {
                // 引用被回复的那一楼，写的时候知道在回谁
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MiuixTheme.colorScheme.surfaceContainer)
                        .padding(12.dp),
                ) {
                    Text(target.comment.author ?: "已注销用户", style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.SemiBold)
                    Text(
                        discussionSummary(target.comment.body),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 3,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
            if (withTitle) {
                TextField(
                    value = title,
                    onValueChange = { if (it.length <= 256) title = it },
                    label = "标题",
                    singleLine = true,
                    enabled = !sending,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            MarkdownEditor(
                value = body,
                onValueChange = { body = it; error = null },
                preview = preview,
                onPreviewChange = { preview = it },
                label = "支持 Markdown，输入 @ 可提到别人",
                enabled = !sending,
                minLines = if (withTitle) 10 else 6,
                mentionCandidates = mentionCandidates,
            )
            error?.let { Text(it, color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.body2) }
        }
    }

    if (confirmDiscard) {
        OverlayDialog(
            show = true,
            title = "放弃这次编辑？",
            summary = "写的内容不会保存。",
            onDismissRequest = { confirmDiscard = false },
        ) {
            Row(Modifier.fillMaxWidth()) {
                TextButton(text = "继续写", onClick = { confirmDiscard = false }, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = "放弃",
                    onClick = { confirmDiscard = false; onClose() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

/** 失败提示：带上 GitHub 返回的原因（权限 / 限流 / 网络），并写日志。 */
private fun failureText(action: String, error: Throwable): String {
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
