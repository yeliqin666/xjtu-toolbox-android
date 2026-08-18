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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xjtu.toolbox.LocalAppLoginState
import com.xjtu.toolbox.Routes
import com.xjtu.toolbox.auth.LoginType
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.auth.handleAuthExpired
import com.xjtu.toolbox.ui.components.AppFilterChip
import com.xjtu.toolbox.ui.components.AppSearchBar
import com.xjtu.toolbox.ui.components.AppSegmentedTabs
import com.xjtu.toolbox.ui.components.AppSuggestionChip
import com.xjtu.toolbox.ui.components.EmptyState
import com.xjtu.toolbox.ui.components.ErrorState
import com.xjtu.toolbox.ui.components.LoadingState
import com.xjtu.toolbox.ui.components.rememberRetainedLazyListState
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BreadcrumbBar
import top.yukonga.miuix.kmp.basic.BreadcrumbItem
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
        )
        return
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        item {
            SmallTitle("最近阅读")
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
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        cornerRadius = 20.dp,
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverThumb(row.coverUrl, loader)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    row.title.ifBlank { "未命名教材" },
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (row.author.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        row.author,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(3.dp))
                val progress = if (row.totalPages > 0)
                    "读到第 ${row.lastReadIndex + 1} / ${row.totalPages} 页"
                else
                    "读到第 ${row.lastReadIndex + 1} 页"
                Text(
                    listOf(progress, relativeReadAt(row.lastReadAt)).joinToString(" · "),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Delete, contentDescription = "移出书架")
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
    loadingMore: Boolean,
    moreFailed: Boolean,
    error: String?,
    loader: Jiaocai1PageLoader,
    onSearch: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenBook: (Jiaocai1Book) -> Unit,
) {
    val listState = rememberRetainedLazyListState("jiaocai1_search_${field.key}_$clsName")
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            AppSearchBar(
                query = keyword,
                onQueryChange = onKeywordChange,
                label = "按${field.label}检索",
                onSearch = { onSearch() },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                // 限定条件本身可撤销，做成带叉的 chip 比「标签 + 取消按钮」省一行
                AppSuggestionChip(
                    onClick = onClearCls,
                    icon = {
                        Icon(
                            Icons.AutoMirrored.Filled.LibraryBooks,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MiuixTheme.colorScheme.primary,
                        )
                    },
                    labelContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                clsName,
                                style = MiuixTheme.textStyles.footnote1,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "取消分类限定",
                                modifier = Modifier.size(14.dp),
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    },
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        when {
            loading -> LoadingState("正在检索…")
            error != null -> ErrorState(error, onRetry = onSearch)
            result == null -> EmptyState(
                title = "输入书名或选个分类",
                subtitle = "全文库按中图法编排，分类浏览往往比关键词更容易找到教材",
                icon = Icons.AutoMirrored.Filled.LibraryBooks,
            )
            books.isEmpty() -> EmptyState(
                title = "没有匹配的书目",
                subtitle = "换个检索字段试试，索书号和 ISBN 要求完全匹配",
                icon = Icons.AutoMirrored.Filled.MenuBook,
            )
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                item {
                    SmallTitle("共 ${result.totalRows} 条，已显示 ${books.size} 条")
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
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        cornerRadius = 20.dp,
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            CoverThumb(book.coverUrl, loader)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    book.title, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                val meta = listOf(book.author, book.publishDate).filter { it.isNotBlank() }
                if (meta.isNotEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        meta.joinToString(" · "),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (book.callNo.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "索书号 ${book.callNo}",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            Icon(
                Icons.Default.ChevronRight, contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun CoverThumb(url: String, loader: Jiaocai1PageLoader) {
    var bitmap by remember(url) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(url) { bitmap = loader.loadCover(url, 160) }

    Box(
        Modifier
            .width(52.dp).height(70.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                Brush.verticalGradient(listOf(Color(0xFF5168CC), Color(0xFF344B9E)))
            ),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.size(24.dp), tint = Color.White)
        }
    }
}

// ── 分类浏览页 ───────────────────────────────────────────────────────

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

    Column(Modifier.fillMaxSize()) {
        if (vm.categoryPath.isNotEmpty()) {
            BreadcrumbBar(
                items = listOf(BreadcrumbItem("root", "全部")) +
                    vm.categoryPath.map { BreadcrumbItem(it.id, it.name) },
                onItemClick = { i ->
                    vm.categoryPath = if (i == 0) emptyList() else vm.categoryPath.take(i)
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        when {
            vm.categoryLoading -> LoadingState("正在加载分类…")
            vm.categoryError != null -> ErrorState(vm.categoryError!!, onRetry = { vm.reloadCategories() })
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                vm.categoryPath.lastOrNull()?.let { node ->
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable { onPick(node) },
                            cornerRadius = 18.dp,
                            colors = CardDefaults.defaultColors(
                                color = MiuixTheme.colorScheme.tertiaryContainer
                            ),
                        ) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.AutoMirrored.Filled.LibraryBooks, null, Modifier.size(20.dp),
                                    tint = MiuixTheme.colorScheme.onTertiaryContainer,
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    "查看「${node.name}」下的全部图书",
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onTertiaryContainer,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                }
                items(current, key = { it.id }) { node ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable {
                            if (node.children.isEmpty()) onPick(node) else vm.categoryPath = vm.categoryPath + node
                        },
                        cornerRadius = 18.dp,
                        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    node.name, style = MiuixTheme.textStyles.body2,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    if (node.children.isEmpty()) node.id else "${node.id} · ${node.children.size} 个子类",
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            if (node.children.isNotEmpty()) {
                                Icon(
                                    Icons.Default.ChevronRight, null, Modifier.size(20.dp),
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(60.dp)) }
            }
        }
    }
}
