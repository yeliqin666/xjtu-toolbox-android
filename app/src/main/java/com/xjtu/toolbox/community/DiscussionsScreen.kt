package com.xjtu.toolbox.community

// 改编自 JoyinJoester/Etoile（GPL-3.0）：github/feature/discussions/DiscussionsScreen.kt。
// 界面改用 MIUIX，按论坛帖子列表重排：作者头像、时间、分类、回复数、点赞，分类筛选，下拉刷新，滑到底自动翻页。

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import com.xjtu.toolbox.ui.components.AppCardColor
import com.xjtu.toolbox.ui.components.AppFilterChip
import com.xjtu.toolbox.ui.components.AppRefreshTexts
import com.xjtu.toolbox.ui.components.EmptyState
import com.xjtu.toolbox.ui.components.ErrorState
import com.xjtu.toolbox.ui.components.LoadingState
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
fun DiscussionsScreen(
    state: DiscussionsUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onCreate: () -> Unit,
    onOpen: (GithubDiscussion) -> Unit,
    onFilter: (String?) -> Unit,
    onAccount: () -> Unit,
    onLegacyFeedback: () -> Unit,
) {
    // 顶部的刷新圈只跟用户下拉触发的那一次走，滑到底翻页时不转
    var pulling by remember { mutableStateOf(false) }
    LaunchedEffect(state.loading) { if (!state.loading) pulling = false }
    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            last >= listState.layoutInfo.totalItemsCount - 3
        }
    }
    // 翻页失败后不自动重试，免得对着挂掉的网络一直请求；底部给一个手动重试
    LaunchedEffect(nearEnd, state.nextCursor, state.loadError) {
        if (nearEnd && state.nextCursor != null && !state.loadError) onLoadMore()
    }

    CommunityPage(
        title = "社区",
        onBack = onBack,
        actions = { CommunityBarAction(Icons.Outlined.AccountCircle, "GitHub 账号", onClick = onAccount) },
        floatingActionButton = {
            FloatingActionButton(onClick = onCreate) {
                Icon(Icons.Filled.Add, contentDescription = "发帖", tint = Color.White)
            }
        },
    ) { top ->
        PullToRefresh(
            refreshTexts = AppRefreshTexts,
            isRefreshing = pulling && state.loading,
            onRefresh = { pulling = true; onRefresh() },
            contentPadding = PaddingValues(top = top),
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().overScrollVertical(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = top + 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state.categories.isNotEmpty()) item(key = "filters") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { AppFilterChip(selected = state.filter == null, onClick = { onFilter(null) }, label = "全部") }
                        items(state.categories, key = { it.id }) { category ->
                            AppFilterChip(selected = state.filter == category.id, onClick = { onFilter(category.id) }, label = category.name)
                        }
                    }
                }
                item(key = "legacy") { LegacyFeedbackEntry(onLegacyFeedback) }
                when {
                    state.items.isEmpty() && state.loading -> item(key = "loading") {
                        LoadingState("正在加载帖子…", Modifier.fillMaxWidth().padding(vertical = 48.dp))
                    }
                    state.items.isEmpty() && state.loadError -> item(key = "error") {
                        ErrorState("帖子加载失败，检查网络后重试", onRetry = onRefresh, modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp))
                    }
                    state.items.isEmpty() -> item(key = "empty") {
                        EmptyState("还没有帖子", "点右下角的 + 发第一帖", Icons.Outlined.Forum, Modifier.fillMaxWidth().padding(vertical = 48.dp))
                    }
                }
                items(state.items, key = { it.id }) { discussion ->
                    DiscussionCard(discussion, onClick = { onOpen(discussion) }, modifier = Modifier.animateItem())
                }
                if (state.items.isNotEmpty()) item(key = "footer") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                        when {
                            state.loading -> CircularProgressIndicator(size = 22.dp)
                            state.loadError -> TextButton(text = "加载失败，点这里重试", onClick = onLoadMore)
                            state.nextCursor == null -> Text(
                                "没有更多帖子了",
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

@Composable
private fun DiscussionCard(discussion: GithubDiscussion, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MiuixTheme.colorScheme
    val summary = remember(discussion.body) { discussionSummary(discussion.body) }
    Card(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        colors = CardDefaults.defaultColors(color = AppCardColor),
        pressFeedbackType = PressFeedbackType.Sink,
        showIndication = true,
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CommunityAvatar(discussion.authorAvatar, discussion.author, 22.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                discussion.author ?: "已注销用户",
                style = MiuixTheme.textStyles.footnote1,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (discussion.authorIsAdmin) {
                Spacer(Modifier.width(4.dp))
                AdminTag()
            }
            Text(
                " · " + communityTime(discussion.updatedAt.ifEmpty { discussion.createdAt }),
                style = MiuixTheme.textStyles.footnote1,
                color = colors.onSurfaceVariantSummary,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            CommunityTag(discussion.category.name)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            discussion.title,
            style = MiuixTheme.textStyles.title4,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (summary.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                summary,
                style = MiuixTheme.textStyles.body2,
                color = colors.onSurfaceVariantSummary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            CountLabel(Icons.Outlined.ChatBubbleOutline, if (discussion.comments > 0) "${discussion.comments}" else "回复")
            CountLabel(
                if (discussion.upvoted) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                if (discussion.upvotes > 0) "${discussion.upvotes}" else "赞",
                active = discussion.upvoted,
            )
            Spacer(Modifier.weight(1f))
            if (discussion.answered) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CheckCircle, null, tint = COMMUNITY_GREEN, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("已解答", style = MiuixTheme.textStyles.footnote1, color = COMMUNITY_GREEN)
                }
            }
        }
    }
}

@Composable
private fun CountLabel(icon: ImageVector, text: String, active: Boolean = false) {
    val tint = if (active) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, style = MiuixTheme.textStyles.footnote1, color = tint)
    }
}

/** GitHub 的「已解答」绿。 */
internal val COMMUNITY_GREEN = Color(0xFF2DA44E)

/** 发帖页：选分类、写标题和正文，可切到预览看 Markdown 效果。 */
@Composable
fun DiscussionComposer(
    state: DiscussionsUiState,
    onEdit: (String, String, String?) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    onRetryCategories: () -> Unit,
) {
    var preview by remember { mutableStateOf(false) }
    // 分区加载好了还没选：默认选第一个，少点一下
    LaunchedEffect(state.categories) {
        if (state.categoryId == null && state.categories.isNotEmpty()) onEdit(state.title, state.body, state.categories.first().id)
    }
    // 发新帖时能 @ 的人：列表里出现过的作者
    val candidates = remember(state.items) {
        state.items.mapNotNull { d -> d.author?.let { CommunityUser(it, d.authorAvatar) } }.distinctBy { it.login }
    }
    val canSubmit = !state.submitting && state.categories.any { it.id == state.categoryId } &&
        state.title.isNotBlank() && state.body.isNotBlank()
    CommunityPage(
        title = "发帖",
        onBack = { if (!state.submitting) onBack() },
        actions = {
            TextButton(
                text = if (state.submitting) "发布中…" else "发布",
                onClick = onSubmit,
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
            Text("选个分类", style = MiuixTheme.textStyles.subtitle, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            if (state.categoriesError) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("分类加载失败", color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.body2)
                    TextButton(text = "重试", onClick = onRetryCategories, enabled = !state.submitting)
                }
            } else if (state.categories.isEmpty()) {
                CircularProgressIndicator(size = 20.dp)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.categories.forEach { category ->
                    AppFilterChip(
                        selected = state.categoryId == category.id,
                        onClick = { if (!state.submitting) onEdit(state.title, state.body, category.id) },
                        label = category.name,
                    )
                }
            }
            if (state.categories.firstOrNull { it.id == state.categoryId }?.acceptsAnswers == true) {
                Text(
                    "这是问答分类：别人的回复可以被你采纳为答案。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            TextField(
                value = state.title,
                onValueChange = { if (it.length <= 256) onEdit(it, state.body, state.categoryId) },
                label = "标题",
                singleLine = true,
                enabled = !state.submitting,
                modifier = Modifier.fillMaxWidth(),
            )
            MarkdownEditor(
                value = state.body,
                onValueChange = { onEdit(state.title, it, state.categoryId) },
                preview = preview,
                onPreviewChange = { preview = it },
                label = "正文，支持 Markdown，输入 @ 可提到别人",
                enabled = !state.submitting,
                minLines = 10,
                mentionCandidates = candidates,
            )
            if (state.submitError) {
                Text(
                    state.submitMessage?.let { "发布失败：$it" } ?: "发布失败，检查网络后重试。",
                    color = MiuixTheme.colorScheme.error,
                    style = MiuixTheme.textStyles.body2,
                )
            }
            Text(
                "帖子会公开发布在 GitHub 上，署名是你的 GitHub 用户名。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/**
 * 编辑 / 预览两态的 Markdown 输入框，发帖和回帖共用。
 * 输入 @ 时在上方列出 [mentionCandidates] 里匹配的人，点一下补全成「@用户名 」。
 */
@Composable
internal fun MarkdownEditor(
    value: String,
    onValueChange: (String) -> Unit,
    preview: Boolean,
    onPreviewChange: (Boolean) -> Unit,
    label: String,
    enabled: Boolean,
    minLines: Int,
    mentionCandidates: List<CommunityUser> = emptyList(),
) {
    // 要知道光标位置才能判断「正在输 @」，所以内部用 TextFieldValue；外部仍只关心字符串
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    if (field.text != value) field = TextFieldValue(value, TextRange(value.length))
    val query = remember(field) { if (field.selection.collapsed) mentionQuery(field.text, field.selection.start) else null }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppFilterChip(selected = !preview, onClick = { onPreviewChange(false) }, label = "编辑")
            AppFilterChip(selected = preview, onClick = { onPreviewChange(true) }, label = "预览")
        }
        AnimatedContent(
            targetState = preview,
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
            label = "editorPreview",
        ) { showPreview ->
            if (showPreview) {
                Card(Modifier.fillMaxWidth(), cornerRadius = 16.dp, colors = CardDefaults.defaultColors(color = AppCardColor)) {
                    if (value.isBlank()) {
                        Text("还没写内容", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    } else {
                        MarkdownText(value)
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (query != null && enabled) {
                        MentionSuggestions(mentionCandidates, query) { user ->
                            val cursor = field.selection.start
                            val start = cursor - query.length - 1
                            val inserted = "@${user.login} "
                            val text = field.text.replaceRange(start, cursor, inserted)
                            field = TextFieldValue(text, TextRange(start + inserted.length))
                            onValueChange(text)
                        }
                    }
                    TextField(
                        value = field,
                        onValueChange = {
                            if (it.text.length <= 65536) {
                                field = it
                                if (it.text != value) onValueChange(it.text)
                            }
                        },
                        label = label,
                        minLines = minLines,
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
