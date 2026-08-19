package com.xjtu.toolbox.jiaocai1

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xjtu.toolbox.LocalAppLoginState
import com.xjtu.toolbox.Routes
import com.xjtu.toolbox.auth.LoginType
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.auth.handleAuthExpired
import com.xjtu.toolbox.ui.components.AppCardColor
import com.xjtu.toolbox.ui.components.AppFilterChip
import com.xjtu.toolbox.ui.components.AppInsetColor
import com.xjtu.toolbox.ui.components.AppSearchBar
import com.xjtu.toolbox.ui.components.AppSegmentedTabs
import com.xjtu.toolbox.ui.components.EmptyState
import com.xjtu.toolbox.ui.components.ErrorState
import com.xjtu.toolbox.ui.components.LoadingState
import com.xjtu.toolbox.ui.components.rememberRetainedLazyListState
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.SinkFeedback
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * 教材全文库入口：书架 + 五种字段检索 + 中图法分类树。
 * 进阅读器走独立路由，浏览态活在本页 ViewModel 上，返回不丢。
 */
@Composable
fun Jiaocai1Screen(
    site: SiteSession,
    onBack: () -> Unit,
    onOpenBook: (ssno: String, title: String) -> Unit,
    initialKeyword: String = "",
) {
    val context = LocalContext.current
    val appLoginState = LocalAppLoginState.current
    val vm: Jiaocai1ViewModel = viewModel()
    vm.bind(context, site)
    Jiaocai1UsageNotice()

    LaunchedEffect(initialKeyword) {
        if (initialKeyword.isNotBlank() && vm.result == null) {
            vm.keyword = initialKeyword
            vm.tab = 1
            vm.search(1)
        }
    }

    if (vm.authExpired) {
        LaunchedEffect(Unit) {
            vm.authExpired = false
            appLoginState.handleAuthExpired(LoginType.JIAOCAI, Routes.JIAOCAI1, onBack)
        }
    }

    Jiaocai1BrowseScreen(
        site = site,
        vm = vm,
        onBack = onBack,
        onOpenBook = onOpenBook,
    )
}

@Composable
private fun Jiaocai1BrowseScreen(
    site: SiteSession,
    vm: Jiaocai1ViewModel,
    onBack: () -> Unit,
    onOpenBook: (ssno: String, title: String) -> Unit,
) {
    val context = LocalContext.current
    val loader = remember(site) { Jiaocai1PageLoader(context, site) }
    val shelf by vm.shelf.collectAsState()
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

    Scaffold(
        topBar = {
            TopAppBar(
                title = "教材全文库",
                largeTitle = "教材全文库",
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .background(MiuixTheme.colorScheme.surface)
        ) {
            AppSegmentedTabs(
                tabs = listOf("书架", "检索", "分类"),
                selectedTabIndex = vm.tab,
                onTabSelected = { vm.tab = it },
            )

            AnimatedContent(
                targetState = vm.tab,
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    (slideInHorizontally { dir * it / 4 } + fadeIn(spring(dampingRatio = 0.85f, stiffness = 500f)))
                        .togetherWith(
                            slideOutHorizontally { -dir * it / 4 } + fadeOut(spring(dampingRatio = 0.85f, stiffness = 500f))
                        )
                },
                label = "jiaocai1Tab",
                modifier = Modifier.weight(1f)
            ) { current ->
                when (current) {
                    0 -> ShelfTab(
                        items = shelf,
                        loader = loader,
                        onOpen = { onOpenBook(it.ssno, it.title) },
                        onRemove = { ssno -> vm.removeFromShelf(ssno) },
                    )
                    1 -> SearchTab(
                        keyword = vm.keyword,
                        onKeywordChange = { vm.keyword = it },
                        field = vm.field,
                        onFieldChange = { vm.changeField(it) },
                        clsName = vm.clsName,
                        onClearCls = { vm.clearCls() },
                        books = vm.books,
                        result = vm.result,
                        loading = vm.loading,
                        loadingMore = vm.loadingMore,
                        moreFailed = vm.moreFailed,
                        error = vm.error,
                        loader = loader,
                        onSearch = { vm.search(1) },
                        onLoadMore = { vm.result?.let { if (it.hasMore) vm.search(it.currentPage + 1) } },
                        onOpenBook = { onOpenBook(it.ssno, it.title) },
                    )
                    else -> CategoryTab(
                        vm = vm,
                        onPick = { vm.pickCategory(it) },
                    )
                }
            }
        }
    }
}

// ── 书架 ─────────────────────────────────────────────────────────────

@Composable
private fun ShelfTab(
    items: List<Jiaocai1ShelfEntity>,
    loader: Jiaocai1PageLoader,
    onOpen: (Jiaocai1ShelfEntity) -> Unit,
    onRemove: suspend (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val listState = rememberRetainedLazyListState("jiaocai1_shelf")

    if (items.isEmpty()) {
        EmptyState(
            title = "书架还是空的",
            subtitle = "打开过的书会出现在这里，记下看到哪一页",
            icon = Icons.AutoMirrored.Filled.LibraryBooks,
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().overScrollVertical(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        item {
            Text(
                "共 ${items.size} 本",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
        items(items, key = { it.ssno }) { row ->
            ShelfCard(
                row = row,
                loader = loader,
                onOpen = { onOpen(row) },
                onRemove = { scope.launch { onRemove(row.ssno) } },
            )
        }
        item { Spacer(Modifier.height(60.dp)) }
    }
}

@Composable
private fun ShelfCard(
    row: Jiaocai1ShelfEntity,
    loader: Jiaocai1PageLoader,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    val progressFrac = if (row.totalPages > 0)
        ((row.lastReadIndex + 1f) / row.totalPages).coerceIn(0f, 1f)
    else 0f
    val progressLabel = if (row.totalPages > 0)
        "第 ${row.lastReadIndex + 1} / ${row.totalPages} 页"
    else
        "第 ${row.lastReadIndex + 1} 页"
    val whenLabel = relativeReadAt(row.lastReadAt)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = SinkFeedback(),
                onClick = onOpen,
            ),
        cornerRadius = 18.dp,
        colors = CardDefaults.defaultColors(color = AppCardColor),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverThumb(
                url = row.coverUrl,
                loader = loader,
                title = row.title,
                width = 64.dp,
                height = 88.dp,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    row.title.ifBlank { "未命名教材" },
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (row.author.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        row.author,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (row.totalPages > 0) {
                    LinearProgressIndicator(
                        progress = progressFrac,
                        modifier = Modifier.fillMaxWidth(),
                        height = 3.dp,
                        colors = ProgressIndicatorDefaults.progressIndicatorColors(
                            foregroundColor = MiuixTheme.colorScheme.primary,
                            backgroundColor = AppInsetColor,
                        ),
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Text(
                    listOf(progressLabel, whenLabel).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Box(
                Modifier
                    .size(32.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = SinkFeedback(),
                        onClick = onRemove,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = "移出书架",
                    modifier = Modifier.size(18.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

private fun relativeReadAt(at: Long): String {
    if (at <= 0L) return ""
    val min = (System.currentTimeMillis() - at) / 60_000L
    return when {
        min < 1 -> "刚刚"
        min < 60 -> "${min} 分钟前"
        min < 60 * 24 -> "${min / 60} 小时前"
        min < 60 * 24 * 7 -> "${min / (60 * 24)} 天前"
        else -> java.text.SimpleDateFormat("M月d日", java.util.Locale.CHINA).format(java.util.Date(at))
    }
}

// ── 检索页 ───────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchTab(
    keyword: String,
    onKeywordChange: (String) -> Unit,
    field: Jiaocai1SearchField,
    onFieldChange: (Jiaocai1SearchField) -> Unit,
    clsName: String,
    onClearCls: () -> Unit,
    books: List<Jiaocai1Book>,
    result: Jiaocai1SearchResult?,
    loading: Boolean,
    @Suppress("UNUSED_PARAMETER") loadingMore: Boolean,
    moreFailed: Boolean,
    error: String?,
    loader: Jiaocai1PageLoader,
    onSearch: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenBook: (Jiaocai1Book) -> Unit,
) {
    val listState = rememberRetainedLazyListState("jiaocai1_search_${field.key}_$clsName")
    Column(Modifier.fillMaxSize()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            cornerRadius = 18.dp,
            colors = CardDefaults.defaultColors(color = AppCardColor),
        ) {
            Column(Modifier.padding(12.dp)) {
                AppSearchBar(
                    query = keyword,
                    onQueryChange = onKeywordChange,
                    label = "按${field.label}检索",
                    onSearch = { onSearch() },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Jiaocai1SearchField.entries.forEach { f ->
                        AppFilterChip(
                            selected = f == field,
                            onClick = { onFieldChange(f) },
                            label = f.label,
                        )
                    }
                }
                if (clsName.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .squircleSurface(
                                color = MiuixTheme.colorScheme.tertiaryContainer,
                                cornerRadius = 10.dp,
                            )
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = SinkFeedback(),
                                onClick = onClearCls,
                            )
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "限定",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            clsName,
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onTertiaryContainer,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "取消分类限定",
                            modifier = Modifier.size(14.dp),
                            tint = MiuixTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
            }
        }

        when {
            loading -> LoadingState("正在检索…", modifier = Modifier.weight(1f).fillMaxSize())
            error != null -> ErrorState(error, onRetry = onSearch, modifier = Modifier.weight(1f).fillMaxSize())
            result == null -> EmptyState(
                title = "输入书名或选个分类",
                subtitle = "全文库按中图法编排，分类浏览往往比关键词更容易找到教材",
                icon = Icons.AutoMirrored.Filled.LibraryBooks,
                modifier = Modifier.weight(1f).fillMaxSize(),
            )
            books.isEmpty() -> EmptyState(
                title = "没有匹配的书目",
                subtitle = "换个检索字段试试，索书号和 ISBN 要求完全匹配",
                icon = Icons.AutoMirrored.Filled.MenuBook,
                modifier = Modifier.weight(1f).fillMaxSize(),
            )
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).overScrollVertical(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                item {
                    Text(
                        "共 ${result.totalRows} 条",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
                items(books, key = { it.ssno }) { book ->
                    Jiaocai1BookCard(book = book, loader = loader, onClick = { onOpenBook(book) })
                }
                if (result.hasMore) {
                    item {
                        if (moreFailed) {
                            Box(
                                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center
                            ) { TextButton(text = "加载失败，点击重试", onClick = onLoadMore) }
                        } else {
                            LaunchedEffect(result.currentPage) { onLoadMore() }
                            Box(
                                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center
                            ) { CircularProgressIndicator() }
                        }
                    }
                }
                item { Spacer(Modifier.height(60.dp)) }
            }
        }
    }
}

@Composable
private fun Jiaocai1BookCard(
    book: Jiaocai1Book,
    loader: Jiaocai1PageLoader,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = SinkFeedback(),
                onClick = onClick,
            ),
        cornerRadius = 18.dp,
        colors = CardDefaults.defaultColors(color = AppCardColor),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            CoverThumb(
                url = book.coverUrl,
                loader = loader,
                title = book.title,
                width = 56.dp,
                height = 76.dp,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    book.title, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                val meta = listOf(book.author, book.publishDate).filter { it.isNotBlank() }
                if (meta.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        meta.joinToString(" · "),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (book.callNo.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "索书号 ${book.callNo}",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}

@Composable
private fun CoverThumb(
    url: String,
    loader: Jiaocai1PageLoader,
    title: String = "",
    width: Dp = 56.dp,
    height: Dp = 76.dp,
) {
    var bitmap by remember(url) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(url) { bitmap = loader.loadCover(url, 200) }
    val (top, bottom) = remember(title) { coverPalette(title) }
    val initial = remember(title) { coverInitial(title) }
    val corner = 8.dp
    val outline = MiuixTheme.colorScheme.outline.copy(alpha = 0.18f)

    Box(
        Modifier
            .width(width)
            .height(height)
            .squircleSurface(color = top, cornerRadius = corner)
            .squircleBorder(width = { 0.6.dp }, color = { outline }, cornerRadius = corner),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(top, bottom))),
        )
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                initial,
                style = MiuixTheme.textStyles.title3,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.92f),
            )
        }
    }
}

private fun coverInitial(title: String): String {
    val ch = title.firstOrNull { !it.isWhitespace() } ?: '书'
    return ch.uppercaseChar().toString()
}

private fun coverPalette(title: String): Pair<Color, Color> {
    val palettes = listOf(
        Color(0xFF6B7BB8) to Color(0xFF44518A),
        Color(0xFF6A8F7A) to Color(0xFF446354),
        Color(0xFFB07A62) to Color(0xFF8A5644),
        Color(0xFF7A6B8F) to Color(0xFF54486A),
        Color(0xFF8A7A5C) to Color(0xFF5C503C),
        Color(0xFF5C7A8A) to Color(0xFF3C5460),
    )
    val i = (title.hashCode().toLong() and 0x7fffffffL).toInt() % palettes.size
    return palettes[i]
}

// ── 分类浏览页 ───────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryTab(
    vm: Jiaocai1ViewModel,
    onPick: (Jiaocai1Category) -> Unit,
) {
    LaunchedEffect(Unit) { vm.loadCategories() }
    BackHandler(enabled = vm.categoryPath.isNotEmpty()) {
        vm.categoryPath = vm.categoryPath.dropLast(1)
    }

    val current = vm.categoryPath.lastOrNull()?.children ?: vm.categoryRoots
    val pathKey = vm.categoryPath.joinToString("/") { it.id }
    val listState = rememberRetainedLazyListState("jiaocai1_cat_$pathKey")
    val here = vm.categoryPath.lastOrNull()

    Column(Modifier.fillMaxSize()) {
        when {
            vm.categoryLoading -> LoadingState("正在加载分类…", modifier = Modifier.weight(1f).fillMaxSize())
            vm.categoryError != null -> ErrorState(
                vm.categoryError!!,
                onRetry = { vm.reloadCategories() },
                modifier = Modifier.weight(1f).fillMaxSize(),
            )
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).overScrollVertical(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                if (vm.categoryPath.isNotEmpty()) {
                    item {
                        CategoryPathBar(
                            path = vm.categoryPath,
                            onJump = { i ->
                                vm.categoryPath = if (i < 0) emptyList() else vm.categoryPath.take(i + 1)
                            },
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                }
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 16.dp,
                        colors = CardDefaults.defaultColors(color = AppCardColor),
                    ) {
                        Column {
                            if (here != null) {
                                CategoryBrowseAllRow(node = here, onClick = { onPick(here) })
                                if (current.isNotEmpty()) {
                                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                                }
                            }
                            current.forEachIndexed { index, node ->
                                CategoryRow(
                                    node = node,
                                    onClick = {
                                        if (node.children.isEmpty()) onPick(node)
                                        else vm.categoryPath = vm.categoryPath + node
                                    },
                                )
                                if (index < current.lastIndex) {
                                    HorizontalDivider(Modifier.padding(start = 62.dp, end = 16.dp))
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(60.dp)) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryPathBar(
    path: List<Jiaocai1Category>,
    onJump: (Int) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CategoryPathChip(label = "全部", selected = false, onClick = { onJump(-1) })
        path.forEachIndexed { i, node ->
            CategoryPathChip(
                label = node.name,
                selected = i == path.lastIndex,
                onClick = { onJump(i) },
            )
        }
    }
}

@Composable
private fun CategoryPathChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (selected) MiuixTheme.colorScheme.tertiaryContainer else Color.Transparent
    val fg = if (selected) MiuixTheme.colorScheme.onTertiaryContainer
    else MiuixTheme.colorScheme.onSurfaceVariantSummary
    val outline = MiuixTheme.colorScheme.outline
    val corner = 10.dp
    Box(
        Modifier
            .squircleSurface(color = bg, cornerRadius = corner)
            .squircleBorder(
                width = { if (selected) 0.dp else 1.dp },
                color = { outline },
                cornerRadius = corner,
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = SinkFeedback(),
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            style = MiuixTheme.textStyles.footnote1,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CategoryBrowseAllRow(
    node: Jiaocai1Category,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = SinkFeedback(),
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryMark(label = "书", tinted = true)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "查看本类图书",
                style = MiuixTheme.textStyles.body2,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.primary,
            )
            Text(
                node.name,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.Default.ChevronRight, null, Modifier.size(18.dp),
            tint = MiuixTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun CategoryRow(
    node: Jiaocai1Category,
    onClick: () -> Unit,
) {
    val hasChildren = node.children.isNotEmpty()
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = SinkFeedback(),
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryMark(label = node.name)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                node.name,
                style = MiuixTheme.textStyles.body2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (hasChildren) {
                Text(
                    "${node.children.size} 个子类",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        if (hasChildren) {
            Icon(
                Icons.Default.ChevronRight, null, Modifier.size(18.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun CategoryMark(label: String, tinted: Boolean = false) {
    val ch = coverInitial(label)
    val bg = if (tinted) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
    else MiuixTheme.colorScheme.tertiaryContainer
    val fg = if (tinted) MiuixTheme.colorScheme.primary
    else MiuixTheme.colorScheme.onTertiaryContainer
    Box(
        Modifier
            .size(36.dp)
            .squircleSurface(color = bg, cornerRadius = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            ch,
            style = MiuixTheme.textStyles.subtitle,
            fontWeight = FontWeight.SemiBold,
            color = fg,
        )
    }
}
