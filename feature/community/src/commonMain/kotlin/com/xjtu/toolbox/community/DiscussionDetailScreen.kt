package com.xjtu.toolbox.community

// 改编自 JoyinJoester/Etoile（GPL-3.0）：github/feature/discussions/DiscussionDetailScreen.kt、
// DiscussionCommentActions.kt。界面改用 MIUIX，按论坛楼层重排：主帖卡片、N 楼、楼主标记、楼中楼预览、
// 表情回应、投票、引用、分享、关闭 / 锁定 / 折叠、底部回帖栏、全屏编辑页（编辑 / 预览）。

import com.xjtu.toolbox.ui.glass.CouiToolbarTextButton
import com.xjtu.toolbox.ui.glass.LocalTabBottomPadding
import com.xjtu.toolbox.ui.glass.tabContentBottomPadding
import com.xjtu.toolbox.ui.components.AppPullToRefresh
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xjtu.toolbox.platform.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import androidx.compose.material.icons.filled.ThumbDown
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
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import com.xjtu.toolbox.platform.rememberShareLink
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.ui.components.AppCardColor
import com.xjtu.toolbox.ui.components.ErrorState
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** 编辑页正在写什么。 */
sealed interface EditorTarget {
    data object NewComment : EditorTarget
    /** 引用某条回复发新楼。 */
    data class Quote(val comment: GithubDiscussionComment) : EditorTarget
    /** 回复某一楼；[prefill] 是回复楼中楼某条时预填的引用和「@某人 」。 */
    data class ReplyTo(val comment: GithubDiscussionComment, val prefill: String?) : EditorTarget
    data class EditComment(val comment: GithubDiscussionComment) : EditorTarget
    data object EditDiscussion : EditorTarget

    /** 回帖就在详情页底部的输入框里写；编辑已有内容才进全屏页。 */
    val inline: Boolean get() = this !is EditComment && this != EditDiscussion
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
    // 系统分享面板（Android = ACTION_SEND，见 platform/Share.kt）；这里不再需要 Context
    val share = rememberShareLink()
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
        if (comment.url.isNotEmpty()) add(MenuAction("分享") { share(discussion.title, comment.url) })
        if (comment.canEdit) add(MenuAction("编辑") { editor = EditorTarget.EditComment(comment) })
        if (comment.canUnminimize && comment.minimized) add(MenuAction("取消折叠") { vm.unminimize(comment) })
        else if (comment.canMinimize && !comment.minimized) add(MenuAction("折叠", options = MINIMIZE_REASONS, onPick = { vm.minimize(comment, it) }))
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

    // 开全屏编辑页时帖子页会被销毁：大标题折叠这类 rememberSaveable 状态存在这里，编辑完回来原样
    val saved = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    AnimatedContent(
        targetState = editor?.takeUnless { it.inline },
        contentKey = { it?.let { t -> "editor:${t.hashCode()}" } ?: "detail" },
        transitionSpec = { communitySlide(forward = targetState != null) },
        label = "detailEditor",
    ) { shownEditor ->
    // ── 全屏编辑页：编辑回复 / 编辑主帖 ──
    if (shownEditor != null) {
        val target = shownEditor
        CommunityEditor(
            target = target,
            discussion = discussion,
            onClose = { editor = null },
            mentionCandidates = participants,
            onSubmit = { title, body -> vm.submit(target, title, body) },
        )
    } else saved.SaveableStateProvider("detail") {
    CommunityPage(
        title = discussion.category.name,
        onBack = onBack,
        actions = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CommunityBarAction(Icons.Outlined.Share, "分享") { share(discussion.title, discussion.url) }
                CommunityBarAction(Icons.Outlined.OpenInBrowser, "在浏览器打开") { uriHandler.openUri(discussion.url) }
            }
        },
        bottomBar = {
            ReplyComposer(
                discussion = discussion,
                canReply = canReply,
                target = editor?.takeIf { it.inline },
                onClearTarget = { editor = null },
                mentionCandidates = participants,
                onLike = { vm.reactDiscussion(LIKE) },
                onSubmit = { target, body -> vm.submit(target, null, body) },
            )
        },
    ) { top ->
        AppPullToRefresh(
            isRefreshing = refreshing,
            onRefresh = { refreshing = true; vm.reloadAll() },
            scrollBehavior = LocalCommunityBarScroll.current,
            topPadding = top,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().overScrollVertical(),
                // 底部的底栏避让已经由回帖栏让过了（Scaffold 的内容区不含它），这里不再叠一次
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
                        replies = vm.replies(comment),
                        canReply = canReply,
                        onReact = vm::reactComment,
                        onReactFloor = { content -> vm.reactComment(comment, content) { loader.edited(it) } },
                        onReply = { prefill -> editor = EditorTarget.ReplyTo(comment, prefill) },
                        menu = ::commentMenu,
                        onToggleAnswer = { vm.moderate("采纳") { markAnswer(comment.id, !comment.isAnswer) } },
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
        ReactionChips(discussion.reactions, discussion.canReact, onToggle = onReact)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ReactionActions(discussion.reactions, discussion.canReact, likeEmpty = true, onToggle = onReact)
            Spacer(Modifier.weight(1f))
            MoreMenu(menu)
        }
    }
}

/** 已解答 / 已关闭 / 已锁定；都没有时不占位置。 */
@Composable
fun DiscussionStateTags(discussion: GithubDiscussion, modifier: Modifier = Modifier) {
    if (!discussion.answered && !discussion.closed && !discussion.locked && !discussion.pinned) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (discussion.pinned) CommunityTag("置顶", COMMUNITY_ORANGE)
        if (discussion.answered) CommunityTag("已解答", COMMUNITY_GREEN)
        discussion.closedReason?.let { CommunityTag("已关闭 · ${closeReasonLabel(it)}", COMMUNITY_PURPLE) }
        if (discussion.locked) CommunityTag("已锁定", MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

/** 👍 或 👎（[content]）：数量为 0 时显示 [empty]。 */
@Composable
fun LikeAction(
    reactions: List<GithubReaction>,
    canReact: Boolean,
    empty: String,
    content: String = LIKE,
    onClick: () -> Unit,
) {
    val liked = reactions.mine(content)
    val count = reactions.count(content)
    val down = content == DISLIKE
    CommunityAction(
        if (down) Icons.Outlined.ThumbDown else Icons.Outlined.ThumbUp,
        if (count > 0) "$count" else empty,
        active = liked,
        activeIcon = if (down) Icons.Filled.ThumbDown else Icons.Filled.ThumbUp,
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
    replies: FloorReplies,
    canReply: Boolean,
    onReact: (GithubDiscussionComment, String, (GithubDiscussionComment) -> Unit) -> Unit,
    onReactFloor: (String) -> Unit,
    onReply: (prefill: String?) -> Unit,
    menu: (GithubDiscussionComment) -> List<MenuAction>,
    onToggleAnswer: () -> Unit,
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
                ReactionChips(comment.reactions, comment.canReact, onToggle = onReactFloor)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                ReactionActions(comment.reactions, comment.canReact, likeEmpty = true, onToggle = onReactFloor)
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
                state = replies,
                canReply = canReply,
                onReact = onReact,
                // GitHub 的楼中楼只有一层，回的是其中哪条只能靠引用说明
                onReply = { reply -> onReply(quoteMarkdown(reply)) },
                menu = menu,
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}

/** 头像 + 用户名 + 楼主 / 已采纳 + 时间，右边可带楼层号。 */
@Composable
fun AuthorLine(comment: GithubDiscussionComment, op: String?, avatar: androidx.compose.ui.unit.Dp, trailing: String? = null) {
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

/**
 * 详情页底部常驻的回帖输入框，和小岱的输入框一个样子：点一下原地开写，跟着文字长高，
 * 草稿一直留着。从某一楼点「回复」只是给它定个对象。锁帖后改成提示。
 */
@Composable
private fun ReplyComposer(
    discussion: GithubDiscussion,
    canReply: Boolean,
    target: EditorTarget?,
    onClearTarget: () -> Unit,
    mentionCandidates: List<CommunityUser>,
    onLike: () -> Unit,
    onSubmit: suspend (EditorTarget, String) -> Result<Unit>,
) {
    val colors = MiuixTheme.colorScheme
    val scope = rememberCoroutineScope()
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    var focused by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    LaunchedEffect(target) {
        if (target == null) return@LaunchedEffect
        val prefill = when (target) {
            is EditorTarget.Quote -> quoteMarkdown(target.comment)
            is EditorTarget.ReplyTo -> target.prefill.orEmpty()
            else -> ""
        }
        if (prefill.isNotEmpty() && field.text.isBlank()) field = TextFieldValue(prefill, TextRange(prefill.length))
        focus.requestFocus()
        keyboard?.show()
    }
    BackHandler(enabled = target != null, onBack = onClearTarget)

    val text = field.text
    val canSend = canReply && !sending && text.isNotBlank()
    val send = {
        sending = true
        error = null
        scope.launch {
            onSubmit(target ?: EditorTarget.NewComment, text).fold(
                onSuccess = {
                    field = TextFieldValue()
                    preview = false
                    onClearTarget()
                    focusManager.clearFocus()
                },
                onFailure = { e -> error = (e as? GithubDiscussionException)?.message?.let { "没成功：$it" } ?: "没成功，检查网络后重试" },
            )
            sending = false
        }
        Unit
    }
    val query = if (focused && field.selection.collapsed) mentionQuery(text, field.selection.start) else null
    val density = androidx.compose.ui.platform.LocalDensity.current
    val tabBottom = LocalTabBottomPadding.current
    // 悬浮底栏下页面铺到窗口底边，底栏占位只是「不让子页再垫导航条」的声明，并没真垫：键盘按原始高度让。
    // 其余宿主（经典底栏、独立页）垫了多少都声明了消费，走 windowInsetsPadding 自动扣掉
    val keyboardAvoid = if (tabBottom > 0.dp) {
        Modifier.padding(bottom = with(density) { WindowInsets.ime.getBottom(density).toDp() }.coerceAtLeast(tabBottom))
    } else {
        Modifier.windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
    }
    val borderColor by androidx.compose.animation.animateColorAsState(
        if (focused) colors.primary.copy(alpha = 0.55f) else colors.outline.copy(alpha = 0.25f),
        androidx.compose.animation.core.tween(180),
        label = "replyBorder",
    )

    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .then(keyboardAvoid)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val targetLabel = when (target) {
            is EditorTarget.Quote -> "引用回复"
            is EditorTarget.ReplyTo -> "回复 ${target.comment.author ?: "这一楼"}"
            else -> null
        }
        if (targetLabel != null) {
            Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(targetLabel, style = MiuixTheme.textStyles.footnote1, color = colors.primary, modifier = Modifier.weight(1f))
                Box(Modifier.size(24.dp).clip(CircleShape).clickable(onClick = onClearTarget), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Close, "不回复这一楼了", Modifier.size(15.dp), tint = colors.onSurfaceVariantSummary)
                }
            }
        }
        error?.let { Text(it, color = colors.error, style = MiuixTheme.textStyles.footnote1, modifier = Modifier.padding(start = 12.dp)) }
        if (preview && text.isNotBlank()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(AppCardColor)
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
            ) { MarkdownText(text) }
        }
        if (query != null) {
            MentionSuggestions(mentionCandidates, query) { user ->
                val cursor = field.selection.start
                val start = cursor - query.length - 1
                val inserted = "@${user.login} "
                field = TextFieldValue(text.replaceRange(start, cursor, inserted), TextRange(start + inserted.length))
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Row(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(AppCardColor)
                    .border(1.dp, borderColor, RoundedCornerShape(24.dp))
                    .padding(6.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                CommunityImageActions(enabled = canReply && !sending) { markdown ->
                    val start = field.selection.min
                    val inserted = text.replaceRange(start, field.selection.max, markdown)
                    require(inserted.length <= 65536) { "正文已接近长度上限，请缩短后再插入图片" }
                    field = TextFieldValue(inserted, TextRange(start + markdown.length))
                }
                Box(
                    Modifier.weight(1f).defaultMinSize(minHeight = 36.dp).padding(end = 6.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    BasicTextField(
                        value = field,
                        onValueChange = { if (it.text.length <= 65536) { field = it; error = null } },
                        enabled = canReply && !sending,
                        textStyle = MiuixTheme.textStyles.body1.copy(color = colors.onSurface),
                        cursorBrush = SolidColor(colors.primary),
                        maxLines = 6,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .focusRequester(focus)
                            .onFocusChanged { focused = it.isFocused },
                    )
                    if (text.isEmpty()) {
                        Text(
                            if (canReply) "说点什么…" else "帖子已锁定，暂不能回复",
                            style = MiuixTheme.textStyles.body1,
                            color = colors.onSurfaceVariantSummary,
                            maxLines = 1,
                        )
                    }
                }
                if (text.isNotBlank()) {
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).clickable { preview = !preview },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.Visibility,
                            if (preview) "收起预览" else "预览 Markdown",
                            Modifier.size(20.dp),
                            tint = if (preview) colors.primary else colors.onSurfaceVariantSummary,
                        )
                    }
                }
                if (focused || text.isNotEmpty()) {
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (canSend) colors.primary else colors.onSurface.copy(alpha = 0.10f))
                            .clickable(enabled = canSend, onClick = send),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (sending) {
                            CircularProgressIndicator(size = 16.dp, strokeWidth = 2.dp)
                        } else {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                "发送",
                                Modifier.size(18.dp),
                                tint = if (canSend) colors.onPrimary else colors.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }
            // 没在写的时候，右边是赞
            if (!focused && text.isEmpty()) {
                Spacer(Modifier.width(8.dp))
                Box(Modifier.height(50.dp), contentAlignment = Alignment.Center) {
                    LikeAction(discussion.reactions, discussion.canReact, "赞", onClick = onLike)
                }
            }
        }
    }
}

/** 编辑已有内容的全屏页：编辑回复、编辑主帖。回帖走 [ReplyComposer]。 */
@Composable
private fun CommunityEditor(
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
        is EditorTarget.ReplyTo -> target.prefill.orEmpty()
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

    val form: @Composable ColumnScope.() -> Unit = {
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
                minLines = 10,
                mentionCandidates = mentionCandidates,
            )
            error?.let { Text(it, color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.body2) }
    }
    val submitText = if (sending) "$submitLabel…" else submitLabel

    CommunityPage(
        title = pageTitle,
        onBack = close,
        actions = {
            // 顶栏文字动作：与右侧圆钮同一套 COUI 玻璃材质（原版 text 分支，40dp 高胶囊）
            CouiToolbarTextButton(
                text = submitText,
                onClick = submit,
                enabled = canSubmit,
            )
        },
    ) { top ->
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .tabContentBottomPadding()
                .padding(horizontal = 16.dp)
                .padding(top = top + 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = form,
        )
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
