package com.xjtu.toolbox.community

// 改编自 JoyinJoester/Etoile（GPL-3.0）：github/feature/discussions/DiscussionDetailScreen.kt、
// DiscussionCommentActions.kt。界面改用 MIUIX，按论坛楼层重排：主帖卡片、N 楼、楼主标记、楼中楼预览、
// 表情回应、投票、引用、分享、关闭 / 锁定 / 折叠、底部回帖栏、全屏编辑页（编辑 / 预览）。

import com.xjtu.toolbox.ui.components.AppPullToRefresh
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.ui.components.AppCardColor
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
    /** 引用某条回复发新楼。 */
    data class Quote(val comment: GithubDiscussionComment) : EditorTarget
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
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val vm: DiscussionDetailViewModel = viewModel(key = "discussion-${initial.id}") { DiscussionDetailViewModel(initial, repo) }
    val discussion = vm.discussion
    val loader = vm.loader
    val comments by loader.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.onShown() }
    LaunchedEffect(vm) { vm.deleted.collect { onDeleted() } }
    // 点赞、回复、编辑后把最新的帖子写回列表
    LaunchedEffect(discussion) { onChanged(discussion) }

    var refreshing by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<EditorTarget?>(null) }
    var deleting by remember { mutableStateOf<GithubDiscussionComment?>(null) }
    var deletingPost by remember { mutableStateOf(false) }
    var minimizing by remember { mutableStateOf<GithubDiscussionComment?>(null) }
    var closing by remember { mutableStateOf(false) }
    var locking by remember { mutableStateOf(false) }
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
    LaunchedEffect(comments.loading) { if (!comments.loading) refreshing = false }

    // 楼层和楼中楼共用的「…」菜单
    fun commentMenu(comment: GithubDiscussionComment) = buildList {
        add(MenuAction("引用回复") { editor = EditorTarget.Quote(comment) })
        if (comment.url.isNotEmpty()) add(MenuAction("分享") { shareLink(context, discussion.title, comment.url) })
        if (comment.canEdit) add(MenuAction("编辑") { editor = EditorTarget.EditComment(comment) })
        if (comment.canUnminimize && comment.minimized) add(MenuAction("取消折叠") { vm.unminimize(comment) })
        else if (comment.canMinimize && !comment.minimized) add(MenuAction("折叠") { minimizing = comment })
        if (comment.canDelete) add(MenuAction("删除", danger = true) { deleting = comment })
    }
    // 锁帖后只有管理员还能回复
    val canReply = !discussion.locked || discussion.canModerate

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
    LaunchedEffect(vm.scrollToEnd, comments.loading, comments.items.size) {
        if (vm.scrollToEnd && !comments.loading && comments.items.isNotEmpty()) {
            // 前面有主帖和「全部回复」两项
            listState.animateScrollToItem(1 + comments.items.size)
            vm.scrollToEnd = false
        }
    }


    deleting?.let { comment ->
        ConfirmDialog(
            title = "删除这条回复？",
            summary = "删除后无法恢复，楼中楼的回复也可能一起被删。",
            confirm = "删除",
            onDismiss = { deleting = null },
            onConfirm = {
                deleting = null
                vm.deleteComment(comment)
            },
        )
    }
    if (deletingPost) {
        ConfirmDialog(
            title = "删除整个帖子？",
            summary = "帖子和下面所有回复都会被删掉，无法恢复。",
            confirm = "删除",
            onDismiss = { deletingPost = false },
            onConfirm = {
                deletingPost = false
                vm.deleteDiscussion()
            },
        )
    }
    minimizing?.let { comment ->
        ReasonDialog(
            title = "折叠这条回复",
            summary = "折叠后默认只显示原因，别人点开仍能看到。",
            reasons = MINIMIZE_REASONS,
            onDismiss = { minimizing = null },
            onPick = { minimizing = null; vm.minimize(comment, it) },
        )
    }
    if (closing) {
        ReasonDialog(
            title = "关闭帖子",
            summary = "关闭后仍可查看和回复，随时能重新打开。",
            reasons = CLOSE_REASONS,
            onDismiss = { closing = false },
            onPick = { reason -> closing = false; vm.moderate("关闭") { close(discussion.id, reason) } },
        )
    }
    if (locking) {
        ConfirmDialog(
            title = "锁定帖子？",
            summary = "锁定后只有管理员能回复和回应。",
            confirm = "锁定",
            onDismiss = { locking = false },
            onConfirm = { locking = false; vm.moderate("锁定") { lock(discussion.id, true) } },
        )
    }
    vm.message?.let { text ->
        LaunchedEffect(text) { kotlinx.coroutines.delay(2500); vm.message = null }
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
            onSubmit = { title, body -> vm.submit(target, title, body) },
        )
    } else {
    CommunityPage(
        title = discussion.category.name,
        onBack = onBack,
        actions = {
            CommunityBarAction(Icons.Outlined.Share, "分享") { shareLink(context, discussion.title, discussion.url) }
            CommunityBarAction(Icons.Outlined.OpenInBrowser, "在浏览器打开") { uriHandler.openUri(discussion.url) }
        },
        bottomBar = {
            ReplyBar(
                discussion = discussion,
                canReply = canReply,
                onReply = { editor = EditorTarget.NewComment },
                onLike = { vm.reactDiscussion(LIKE) },
            )
        },
    ) { top ->
        AppPullToRefresh(
            isRefreshing = refreshing,
            onRefresh = { refreshing = true; vm.reloadAll() },
            topPadding = top,
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
                        onReact = vm::reactDiscussion,
                        onVote = vm::vote,
                        menu = buildList {
                            if (discussion.canEdit) add(MenuAction("编辑") { editor = EditorTarget.EditDiscussion })
                            if (discussion.canClose && !discussion.closed) add(MenuAction("关闭帖子") { closing = true })
                            if (discussion.canReopen && discussion.closed) {
                                add(MenuAction("重新打开") { vm.moderate("重新打开") { reopen(discussion.id) } })
                            }
                            if (discussion.canModerate) {
                                if (discussion.locked) add(MenuAction("解除锁定") { vm.moderate("解除锁定") { lock(discussion.id, false) } })
                                else add(MenuAction("锁定帖子") { locking = true })
                            }
                            if (discussion.canDelete) add(MenuAction("删除帖子", danger = true) { deletingPost = true })
                        },
                    )
                }
                item(key = "section") {
                    Row(Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("全部回复", style = MiuixTheme.textStyles.headline2, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(6.dp))
                        Text("${discussion.comments}", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        vm.message?.let {
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
                        refreshKey = vm.replyRefresh[comment.id] ?: 0,
                        repo = repo,
                        canReply = canReply,
                        onReact = vm::reactComment,
                        onReactFloor = { content -> vm.reactComment(comment, content) { loader.edited(it) } },
                        onReply = { mention -> editor = EditorTarget.ReplyTo(comment, mention) },
                        menu = ::commentMenu,
                        onToggleAnswer = { vm.moderate("采纳") { markAnswer(comment.id, !comment.isAnswer) } },
                        patch = vm.patch,
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
private fun OriginalPost(
    discussion: GithubDiscussion,
    onReact: (String) -> Unit,
    onVote: (GithubPollOption) -> Unit,
    menu: List<MenuAction>,
) {
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
        DiscussionStateTags(discussion, Modifier.padding(top = 10.dp))
        Spacer(Modifier.height(12.dp))
        MarkdownText(discussion.body.ifBlank { "（没有正文）" })
        discussion.poll?.let { DiscussionPollCard(it, onVote) }
        ReactionChips(discussion.reactions, discussion.canReact, onReact)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LikeAction(discussion.reactions, discussion.canReact, "赞") { onReact(LIKE) }
            ReactionButton(discussion.reactions, discussion.canReact, onReact)
            Spacer(Modifier.weight(1f))
            MoreMenu(menu)
        }
    }
}

/** 已解答 / 已关闭 / 已锁定；都没有时不占位置。 */
@Composable
internal fun DiscussionStateTags(discussion: GithubDiscussion, modifier: Modifier = Modifier) {
    if (!discussion.answered && !discussion.closed && !discussion.locked && !discussion.pinned) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (discussion.pinned) CommunityTag("置顶", COMMUNITY_ORANGE)
        if (discussion.answered) CommunityTag("已解答", COMMUNITY_GREEN)
        discussion.closedReason?.let { CommunityTag("已关闭 · ${closeReasonLabel(it)}", COMMUNITY_PURPLE) }
        if (discussion.locked) CommunityTag("已锁定", MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

/** 👍：数量为 0 时显示 [empty]。 */
@Composable
internal fun LikeAction(reactions: List<GithubReaction>, canReact: Boolean, empty: String, onClick: () -> Unit) {
    val liked = reactions.mine(LIKE)
    val count = reactions.count(LIKE)
    CommunityAction(
        Icons.Outlined.ThumbUp,
        if (count > 0) "$count" else empty,
        active = liked,
        activeIcon = Icons.Filled.ThumbUp,
        enabled = canReact || liked,
        onClick = onClick,
    )
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
    canReply: Boolean,
    onReact: (GithubDiscussionComment, String, (GithubDiscussionComment) -> Unit) -> Unit,
    onReactFloor: (String) -> Unit,
    onReply: (mention: String?) -> Unit,
    menu: (GithubDiscussionComment) -> List<MenuAction>,
    onToggleAnswer: () -> Unit,
    patch: ReplyPatch?,
) {
    var expanded by remember(comment.id) { mutableStateOf(false) }
    Card(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        insideMargin = PaddingValues(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 6.dp),
        colors = CardDefaults.defaultColors(color = AppCardColor),
    ) {
        AuthorLine(comment, op, avatar = 32.dp, trailing = "$floor 楼")
        Column(Modifier.padding(start = 42.dp)) {
            Spacer(Modifier.height(6.dp))
            val reason = comment.minimizedReason
            if (reason != null && !expanded) {
                MinimizedNotice(reason) { expanded = true }
            } else {
                MarkdownText(comment.body)
                ReactionChips(comment.reactions, comment.canReact, onReactFloor)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                LikeAction(comment.reactions, comment.canReact, "赞") { onReactFloor(LIKE) }
                ReactionButton(comment.reactions, comment.canReact, onReactFloor)
                if (canReply) CommunityAction(Icons.AutoMirrored.Outlined.Reply, "回复", onClick = { onReply(null) })
                if (answerable && (if (comment.isAnswer) comment.canUnmarkAnswer else comment.canMarkAnswer)) {
                    CommunityAction(
                        Icons.Outlined.CheckCircle,
                        if (comment.isAnswer) "取消采纳" else "采纳",
                        active = comment.isAnswer,
                        onClick = onToggleAnswer,
                    )
                }
                Spacer(Modifier.weight(1f))
                MoreMenu(menu(comment))
            }
            DiscussionReplies(
                comment = comment,
                op = op,
                refreshKey = refreshKey,
                load = { cursor -> repo.replies(comment.id, cursor) },
                canReply = canReply,
                onReact = onReact,
                onReply = { reply -> onReply(reply.author?.let { "@$it " }) },
                menu = menu,
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

/** 详情页底部常驻的回帖栏：像论坛一样点一下就开写；锁帖后改成提示。 */
@Composable
private fun ReplyBar(discussion: GithubDiscussion, canReply: Boolean, onReply: () -> Unit, onLike: () -> Unit) {
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
                .clickable(enabled = canReply, onClick = onReply)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(
                if (canReply) "说点什么…" else "帖子已锁定，暂不能回复",
                style = MiuixTheme.textStyles.body2,
                color = colors.onSurfaceVariantSummary,
            )
        }
        Spacer(Modifier.width(8.dp))
        LikeAction(discussion.reactions, discussion.canReact, "赞", onLike)
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
        is EditorTarget.Quote -> quoteMarkdown(target.comment)
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
        is EditorTarget.Quote -> "引用回复"
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
