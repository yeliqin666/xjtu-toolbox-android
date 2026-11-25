package com.xjtu.toolbox.community

// 改编自 JoyinJoester/Etoile（GPL-3.0）：github/feature/discussions/DiscussionsScreen.kt。
// 界面改用 MIUIX，按论坛帖子列表重排：作者头像、时间、分类、回复数、点赞、状态标签，分类筛选，下拉刷新，滑到底自动翻页。

import com.xjtu.toolbox.ui.glass.CouiToolbarTextButton
import com.xjtu.toolbox.ui.glass.tabContentPadding
import com.xjtu.toolbox.ui.glass.tabContentBottomPadding
import com.xjtu.toolbox.ui.components.AppPullToRefresh
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FilterList
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
import com.xjtu.toolbox.ui.components.EmptyState
import com.xjtu.toolbox.ui.components.ErrorState
import com.xjtu.toolbox.ui.components.LoadingState
import com.xjtu.toolbox.ui.markdown.GithubMarkdown
import com.xjtu.toolbox.ui.markdown.markdownSummary
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.window.WindowDialog
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
    onState: (DiscussionStateFilter) -> Unit,
    onSort: (DiscussionSort) -> Unit,
    onAuthor: (String?) -> Unit,
    /** 当前登录的 GitHub 用户名，「我发的」用。 */
    me: String,
    onAccount: () -> Unit,
    onFeedback: (() -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    // 顶部的刷新圈只跟用户下拉触发的那一次走，滑到底翻页时不转
    var pulling by remember { mutableStateOf(false) }
    LaunchedEffect(state.loading) { if (!state.loading) pulling = false }
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

    // 状态、排序、「我发的」收进顶栏的筛选弹窗；列表顶上只留一行分类，不堆两行胶囊
    var showOptions by remember { mutableStateOf(false) }
    val mine = state.author.equals(me, ignoreCase = true)
    val customized = state.stateFilter != DiscussionStateFilter.OPEN || state.sort != DiscussionSort.UPDATED || mine

    CommunityPage(
        title = "社区",
        onBack = onBack,
        root = true,
        actions = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
            Box {
                com.xjtu.toolbox.ui.glass.CouiIconMenu(
                    expanded = showOptions,
                    onExpandedChange = { showOptions = it },
                    icon = { Icon(Icons.Outlined.FilterList, contentDescription = "筛选与排序", tint = MiuixTheme.colorScheme.onSurface) },
                ) {
                    submenu(title = "状态", summary = state.stateFilter.label) {
                        DiscussionStateFilter.entries.forEach { filter ->
                            option(filter.label, selected = filter == state.stateFilter) {
                                onState(filter)
                            }
                        }
                    }
                    submenu(title = "排序", summary = state.sort.label) {
                        DiscussionSort.entries.forEach { sort ->
                            option(sort.label, selected = sort == state.sort) {
                                onSort(sort)
                            }
                        }
                    }
                    option("只看我发的", selected = mine) {
                        onAuthor(if (mine) null else me)
                    }
                }
                // 改过默认筛选时亮一个小点，免得忘了自己筛过
                if (customized) Box(
                    Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 10.dp).size(7.dp)
                        .background(MiuixTheme.colorScheme.primary, androidx.compose.foundation.shape.CircleShape),
                )
            }
            CommunityBarAction(Icons.Outlined.Edit, "发帖", onClick = onCreate)
            CommunityBarAction(Icons.Outlined.AccountCircle, "GitHub 账号", onClick = onAccount)
            }
        },
    ) { top ->
        AppPullToRefresh(
            isRefreshing = pulling && state.loading,
            onRefresh = { pulling = true; onRefresh() },
            scrollBehavior = LocalCommunityBarScroll.current,
            topPadding = top,
            modifier = Modifier.fillMaxSize(),
        ) {
            com.xjtu.toolbox.ui.glass.ReportScrollPosition(listState)
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().overScrollVertical(),
                contentPadding = tabContentPadding(PaddingValues(start = 12.dp, end = 12.dp, top = top + 8.dp, bottom = 96.dp)),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state.categories.isNotEmpty()) item(key = "filters") {
                    // 空串代表「全部」
                    val names = state.categories.associate { it.id to it.name }
                    com.xjtu.toolbox.ui.components.AppChipTabRow(
                        options = listOf("") + state.categories.map { it.id },
                        selected = state.filter.orEmpty(),
                        onSelect = { onFilter(it.ifEmpty { null }) },
                        label = { names[it] ?: "全部" },
                        // 列表左右留了 12dp：整行伸到屏幕边，滚动渐隐落在边上
                        bleed = 12.dp,
                    )
                }
                if (onFeedback != null) item(key = "feedback") { FeedbackEntry(onFeedback) }
                when {
                    state.items.isEmpty() && state.loading -> item(key = "loading") {
                        LoadingState("正在加载帖子…", Modifier.fillMaxWidth().padding(vertical = 48.dp))
                    }
                    state.items.isEmpty() && state.loadError -> item(key = "error") {
                        ErrorState("帖子加载失败，检查网络后重试", onRetry = onRefresh, modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp))
                    }
                    state.items.isEmpty() -> item(key = "empty") {
                        val filtering = state.filter != null || state.author != null || state.stateFilter != DiscussionStateFilter.OPEN
                        EmptyState(
                            if (filtering) "没有符合条件的帖子" else "还没有帖子",
                            if (filtering) "换个筛选条件试试" else "点右下角的 + 发第一帖",
                            Icons.Outlined.Forum, Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        )
                    }
                }
                items(state.items, key = { it.id }) { discussion ->
                    DiscussionCard(discussion, onClick = { onOpen(discussion) }, modifier = Modifier.animateItem())
                }
                if (state.items.isNotEmpty()) item(key = "footer") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                        when {
                            state.loading -> CircularProgressIndicator(size = 22.dp)
                            state.loadError -> com.xjtu.toolbox.ui.components.TextLink(text = "加载失败，点这里重试", onClick = onLoadMore)
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
    val summary = remember(discussion.body) { markdownSummary(discussion.body) }
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
            // 作者一组独占剩余宽度，分类标签才贴右；和 Spacer 各占 weight 会平分空白
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
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
            }
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
            val liked = discussion.reactions.mine(LIKE)
            val likes = discussion.reactions.count(LIKE)
            CountLabel(
                if (liked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                if (likes > 0) "$likes" else "赞",
                active = liked,
            )
            Spacer(Modifier.weight(1f))
            DiscussionStateTags(discussion)
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

/** GitHub 的「已解答」绿、「已关闭」紫、置顶橙。 */
val COMMUNITY_GREEN = Color(0xFF2DA44E)
val COMMUNITY_PURPLE = Color(0xFF8250DF)
val COMMUNITY_ORANGE = Color(0xFFBC4C00)

/** 宿主提供的诊断信息（App 版本、机型、系统）；null = 这一端不提供，发帖页不出现相关入口（#463）。 */
val LocalCommunityDiagnostics = androidx.compose.runtime.staticCompositionLocalOf<(() -> String)?> { null }

/** 发这两类帖子时提示附诊断信息。 */
private val DIAGNOSTIC_CATEGORIES = setOf("功能建议", "求助与反馈")

private const val DIAGNOSTICS_HEADER = "> ### 诊断信息"

internal fun hasDiagnostics(body: String): Boolean = body.lines().any { it.trim() == DIAGNOSTICS_HEADER }

/** 正文末尾换行后接上诊断引用块；[info] 是「键：值」的多行文本，可带折叠的日志块（空行保留，`<details>` 要靠它分段）。 */
internal fun withDiagnostics(body: String, info: String): String {
    val block = (listOf(DIAGNOSTICS_HEADER) + info.trim().lines().map { if (it.isBlank()) ">" else "> $it" }).joinToString("\n")
    return if (body.isBlank()) block else body.trimEnd() + "\n\n" + block
}

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
    // 无权发帖的分类（如通知公告）不列出
    val postable = remember(state.categories, state.viewerPermission) { postableCategories(state.categories, state.viewerPermission) }
    // 分区加载好了还没选（或选中的发不了）：默认选第一个可发的，少点一下
    LaunchedEffect(postable) {
        if (state.categories.isNotEmpty() && postable.none { it.id == state.categoryId }) {
            val first = postable.firstOrNull()
            onEdit(state.title, bodyForCategory(first, state.body), first?.id)
        }
    }
    // 发新帖时能 @ 的人：列表里出现过的作者
    val candidates = remember(state.items) {
        state.items.mapNotNull { d -> d.author?.let { CommunityUser(it, d.authorAvatar) } }.distinctBy { it.login }
    }
    val canSubmit = !state.submitting && postable.any { it.id == state.categoryId } &&
        state.title.isNotBlank() && state.body.isNotBlank()
    // 反馈类分类可附诊断信息（#463）；宿主没提供（桌面 / 网页）就不出现
    val diagnosticsSource = LocalCommunityDiagnostics.current
    val diagnostics = diagnosticsSource?.takeIf {
        postable.firstOrNull { c -> c.id == state.categoryId }?.name in DIAGNOSTIC_CATEGORIES
    }
    var askDiagnostics by remember { mutableStateOf(false) }
    // 「确定」后先把诊断信息写进正文，等正文更新到 state 里再发，免得发出去的还是旧正文
    var pendingSubmit by remember { mutableStateOf(false) }
    LaunchedEffect(pendingSubmit, state.body) {
        if (pendingSubmit && hasDiagnostics(state.body)) {
            pendingSubmit = false
            onSubmit()
        }
    }
    CommunityPage(
        title = "发帖",
        onBack = { if (!state.submitting) onBack() },
        actions = {
            // 顶栏文字动作：与右侧圆钮同一套 COUI 玻璃材质（原版 text 分支，40dp 高胶囊）
            CouiToolbarTextButton(
                text = if (state.submitting) "发布中…" else "发布",
                onClick = { if (diagnostics != null && !hasDiagnostics(state.body)) askDiagnostics = true else onSubmit() },
                enabled = canSubmit,
            )
        },
    ) { top ->
        if (askDiagnostics && diagnostics != null) {
            WindowDialog(
                show = true,
                title = "是否要附带诊断信息",
                summary = "点击确定则会在发布前，于消息尾部插入诊断信息，以供开发者排错。我们只会收集你的app版本，设备型号以及系统版本，" +
                    "你的账号等隐私信息不会上传；点击“取消”则会原样发送消息。你的诊断信息将会对我们排错有很大帮助！",
                onDismissRequest = { askDiagnostics = false },
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(text = "取消", modifier = Modifier.weight(1f), onClick = { askDiagnostics = false; onSubmit() })
                    TextButton(
                        text = "确定",
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            askDiagnostics = false
                            onEdit(state.title, withDiagnostics(state.body, diagnostics()), state.categoryId)
                            pendingSubmit = true
                        },
                    )
                }
            }
        }
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState()).tabContentBottomPadding()
                .padding(horizontal = 16.dp)
                .padding(top = top + 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 灰色输入框要放在白卡片上：直接放在同样灰的页面上就分不出来了
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(AppCardColor)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                DiscussionCategoryRow(
                    categories = postable,
                    selectedId = state.categoryId,
                    loading = state.categories.isEmpty() && !state.categoriesError,
                    failed = state.categoriesError,
                    enabled = !state.submitting,
                    onRetry = onRetryCategories,
                    onPick = { id -> onEdit(state.title, bodyForCategory(postable.firstOrNull { it.id == id }, state.body), id) },
                )
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
                    diagnostics = diagnostics,
                )
            }
            if (postable.firstOrNull { it.id == state.categoryId }?.acceptsAnswers == true) {
                Text(
                    "这是问答分类：别人的回复可以被你采纳为答案。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
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

/** 选分类：和输入框同样的灰底一行，点开是下拉菜单。 */
@Composable
private fun DiscussionCategoryRow(
    categories: List<GithubDiscussionCategory>,
    selectedId: String?,
    loading: Boolean,
    failed: Boolean,
    enabled: Boolean,
    onRetry: () -> Unit,
    onPick: (String) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(colors.secondaryContainer)
                .clickable(enabled = enabled && !loading) { if (failed) onRetry() else open = true }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("分类", style = MiuixTheme.textStyles.body2, color = colors.onSurfaceVariantSummary)
            Spacer(Modifier.width(12.dp))
            Text(
                when {
                    failed -> "加载失败，点这里重试"
                    loading -> "加载中…"
                    else -> categories.firstOrNull { it.id == selectedId }?.name ?: "选个分类"
                },
                style = MiuixTheme.textStyles.body2,
                fontWeight = FontWeight.Medium,
                color = if (failed) colors.error else colors.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (loading) CircularProgressIndicator(size = 16.dp, strokeWidth = 2.dp)
            else if (!failed) Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(20.dp), tint = colors.onSurfaceVariantSummary)
        }
        com.xjtu.toolbox.ui.components.AppDropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            morph = com.xjtu.toolbox.ui.components.MenuMorph.FromRow,
        ) {
            categories.forEach { category ->
                com.xjtu.toolbox.ui.components.AppDropdownOption(
                    text = category.name,
                    selected = category.id == selectedId,
                    onClick = { open = false; onPick(category.id) },
                )
            }
        }
    }
}

/**
* 编辑 / 预览两态的 Markdown 输入框，发帖和回帖共用。
* 输入 @ 时在上方列出 [mentionCandidates] 里匹配的人，点一下补全成「@用户名 」。
*/
@Composable
fun MarkdownEditor(
    value: String,
    onValueChange: (String) -> Unit,
    preview: Boolean,
    onPreviewChange: (Boolean) -> Unit,
    label: String,
    enabled: Boolean,
    minLines: Int,
    mentionCandidates: List<CommunityUser> = emptyList(),
    /** 非 null 时显示「插入诊断信息」，返回要插的引用块。 */
    diagnostics: (() -> String)? = null,
) {
    // 要知道光标位置才能判断「正在输 @」，所以内部用 TextFieldValue；外部仍只关心字符串
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    if (field.text != value) field = TextFieldValue(value, TextRange(value.length))
    val query = remember(field) { if (field.selection.collapsed) mentionQuery(field.text, field.selection.start) else null }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            com.xjtu.toolbox.ui.components.AppSegmentedTabs(
                tabs = listOf("编辑", "预览"),
                selectedTabIndex = if (preview) 1 else 0,
                onTabSelected = { onPreviewChange(it == 1) },
                modifier = Modifier.width(144.dp),
                compact = true,
            )
            Spacer(Modifier.weight(1f))
            // 反馈类分类：图片左边一个「插入诊断信息」，已插过就不再显示（#463）
            if (diagnostics != null && !hasDiagnostics(value)) {
                Text(
                    "插入诊断信息",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = enabled) {
                            val text = withDiagnostics(field.text, diagnostics())
                            if (text.length <= 65536) {
                                field = TextFieldValue(text, TextRange(text.length))
                                onValueChange(text)
                            }
                        }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
            CommunityImageActions(enabled = enabled) { markdown ->
                val start = field.selection.min
                val end = field.selection.max
                val text = field.text.replaceRange(start, end, markdown)
                require(text.length <= 65536) { "正文已接近长度上限，请缩短后再插入图片" }
                field = TextFieldValue(text, TextRange(start + markdown.length))
                onValueChange(text)
                onPreviewChange(false)
            }
        }
        AnimatedContent(
            targetState = preview,
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
            label = "editorPreview",
        ) { showPreview ->
            if (showPreview) {
                Card(Modifier.fillMaxWidth(), cornerRadius = 16.dp, colors = CardDefaults.defaultColors(color = AppCardColor)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        if (value.isBlank()) {
                            Text("还没写内容", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        } else {
                            GithubMarkdown(value)
                        }
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
