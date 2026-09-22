package com.xjtu.toolbox.jiaocai

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.gson.Gson
import com.xjtu.toolbox.Routes
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.jiaocai1.Jiaocai1Api
import com.xjtu.toolbox.jiaocai1.Jiaocai1SearchField
import com.xjtu.toolbox.schedule.TextbookItem
import com.xjtu.toolbox.ui.components.AppSearchBar
import com.xjtu.toolbox.ui.components.AppSuggestionChip
import com.xjtu.toolbox.ui.components.rememberRetainedLazyListState
import com.xjtu.toolbox.ui.components.rememberRetainedLazyStaggeredGridState
import com.xjtu.toolbox.ui.adaptive.AdaptiveCardGrid
import com.xjtu.toolbox.ui.adaptive.fullLineItem
import com.xjtu.toolbox.ui.adaptive.readableWidth
import androidx.compose.foundation.lazy.staggeredgrid.items
import com.xjtu.toolbox.util.DataCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
fun JiaocaiScreen(
    site: SiteSession,
    onBack: () -> Unit,
    onOpenFullText: (ssno: String, title: String) -> Unit,
) {
    // PR L 之后这只是薄包装：真正的页面是合并了「查教材/书架/全文库」三栏的
    // TextbookScreen，本函数只是把老路由（快捷方式、全局搜索、屁岱工具可能还在用）
    // 接到它的「查教材」栏。
    TextbookScreen(
        site = site,
        onBack = onBack,
        onOpenBook = onOpenFullText,
        initialTab = 0,
        authExpiredRoute = Routes.JIAOCAI,
    )
}

/**
 * 「查教材」栏内容，不带 Scaffold / TopAppBar：检索框 + 教材中心检索结果，
 * 搜索框为空时额外插一段「本学期我的教材」，带一键「读全文」。
 * 供 [TextbookScreen] 调用。
 */
@Composable
internal fun JiaocaiSearchContent(
    site: SiteSession,
    vm: JiaocaiViewModel,
    onOpenFullText: (ssno: String, title: String) -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val listState = rememberRetainedLazyStaggeredGridState("jiaocai_search")
    val keyword = vm.keyword
    val books = vm.books
    val isLoading = vm.loading
    val errorMsg = vm.error
    val hasSearched = vm.hasSearched
    val selected = vm.selected

    BackHandler(enabled = selected != null) { vm.selected = null }

    Column(
        modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.surface)
    ) {
        AppSearchBar(
            query = keyword,
            onQueryChange = { vm.keyword = it },
            label = "书名、作者或课程",
            onSearch = { vm.search() },
            // 宽屏限宽居中：一条搜索框横跨整个平板宽度不好用
            modifier = Modifier
                .readableWidth()
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )

        if (!hasSearched && !isLoading) {
            Column(Modifier.readableWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                Text("搜索建议", style = MiuixTheme.textStyles.subtitle, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("高等数学", "大学物理", "程序设计").forEach { suggestion ->
                        AppSuggestionChip(
                            label = suggestion,
                            onClick = { vm.searchWith(suggestion) }
                        )
                    }
                }
            }
            // 搜索框为空、还没搜过：先看看本学期课程自己配的教材，不必非得手动搜。
            MyTextbooksSection(
                site = site,
                onOpenFullText = onOpenFullText,
                snackbarHostState = snackbarHostState,
            )
        }

        AnimatedContent(
                targetState = Triple(isLoading, errorMsg, books),
                contentKey = { (l, e, b) -> "$l|${e != null}|${b.size}" },
                transitionSpec = {
                    fadeIn(spring(stiffness = 500f)) togetherWith fadeOut(spring(stiffness = 500f))
                },
                label = "jiaocaiResult",
                modifier = Modifier.weight(1f),
            ) { (isLoading, errorMsg, books) ->
                when {
                    isLoading -> Box(
                        Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        com.xjtu.toolbox.ui.components.MorphingLoader()  // 整页加载统一用形变加载器
                    }
                    errorMsg != null -> Text(
                        errorMsg!!,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp)
                    )
                    hasSearched && books.isEmpty() -> {
                        Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.MenuBook, null, Modifier.size(40.dp),
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                                Spacer(Modifier.height(8.dp))
                                Text("未找到相关教材", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            }
                        }
                    }
                    // 宽屏教材卡分两三列（见 AdaptiveCardGrid）
                    else -> AdaptiveCardGrid(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        spacing = 10.dp,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        if (hasSearched) {
                            fullLineItem {
                                Text(
                                    "${books.size} 本",
                                    style = MiuixTheme.textStyles.subtitle,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                                )
                            }
                        }
                        items(books) { book ->
                            BookCard(book = book, onClick = { vm.selected = book })
                        }
                        fullLineItem { Spacer(Modifier.height(80.dp)) }
                    }
                }
            }

        selected?.let { book ->
            JiaocaiBookSheet(
                site = site,
                book = book,
                onDismiss = { vm.selected = null },
                onRead = { ssno -> onOpenFullText(ssno, book.title) },
            )
        }
    }
}

@Composable
private fun BookCard(book: JiaocaiBook, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        cornerRadius = 20.dp,
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier.width(52.dp).height(68.dp).background(
                    Brush.verticalGradient(listOf(Color(0xFF5168CC), Color(0xFF344B9E))),
                    RoundedCornerShape(12.dp)
                ),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.MenuBook, null, Modifier.size(25.dp), tint = Color.White)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    book.title,
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (book.author.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        book.author,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (book.summary.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        book.summary,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (book.hasFullText) {
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier.background(
                            MiuixTheme.colorScheme.primary.copy(alpha = 0.15f),
                            RoundedCornerShape(8.dp)
                        ).padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "本地全文",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun JiaocaiBookSheet(
    site: SiteSession,
    book: JiaocaiBook,
    onDismiss: () -> Unit,
    onRead: (String) -> Unit,
) {
    var ssno by remember(book.id) { mutableStateOf(book.ssno) }
    var resolving by remember(book.id) { mutableStateOf(false) }
    LaunchedEffect(book.id) {
        if (ssno != null || !book.hasFullText) return@LaunchedEffect
        resolving = true
        ssno = withContext(Dispatchers.IO) { JiaocaiApi(site).fetchSsno(book) }
        resolving = false
    }

    OverlayBottomSheet(
        show = true,
        title = book.title,
        onDismissRequest = onDismiss,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .overScrollVertical()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 8.dp)
        ) {
            if (book.author.isNotBlank()) {
                Text(
                    book.author,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.height(12.dp))
            }

            val infoFields = book.fields.filterKeys { it !in HIDDEN_FIELDS }
            if (infoFields.isNotEmpty()) {
                infoFields.forEach { (key, value) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        Text(
                            key,
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.width(88.dp)
                        )
                        Text(value, style = MiuixTheme.textStyles.body2, modifier = Modifier.weight(1f))
                    }
                }
            } else if (book.summary.isNotBlank()) {
                Text(
                    book.summary,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }

            if (book.hasFullText) {
                Spacer(Modifier.height(16.dp))
                val ready = ssno
                TextButton(
                    text = when {
                        ready != null -> "阅读全文"
                        resolving -> "正在获取全文地址…"
                        else -> "该书暂无法在线阅读"
                    },
                    onClick = { ready?.let(onRead) },
                    enabled = ready != null,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    }
}

/** 获取方式相关字段已由「阅读全文」承担，不再列出 */
private val HIDDEN_FIELDS = setOf("获取方式一", "获取方式二", "获取方式一地址", "获取方式二地址")

// ── 本学期我的教材 ───────────────────────────────────────────────────
//
// 三种「教材」不是一回事，别当成重复功能（见 plan2 §3.1）：
// - 这里的「课程选用教材」答的是「我这学期的课用什么书」，数据来自教务、
//   已经缓存在 schedule_textbooks_$term，跟下面的教材中心检索无关；
// - 「查教材」栏主体（上面 AnimatedContent 那部分）答的是「某门课配什么书（全校范围）」，
//   来自 JiaocaiApi.search；
// - 「全文库」栏答的是「读书」，来自 Jiaocai1Api，全文库只认 IP、不走 CAS，校外大概率用不了。
// 这里把「我这学期的教材」和「读全文」串起来：先读缓存拿到 isbn/书名，
// 点了「读全文」才去全文库解析 ssno，不进页面就逐行请求。

/** 一键「读全文」的结果：拿到 ssno；确认没有；或者请求本身失败（大概率在校外）。 */
private sealed class FullTextOutcome {
    data class Found(val ssno: String) : FullTextOutcome()
    data object NotFound : FullTextOutcome()
    data object Failed : FullTextOutcome()
}

@Composable
private fun MyTextbooksSection(
    site: SiteSession,
    onOpenFullText: (ssno: String, title: String) -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    val context = LocalContext.current
    // null = 还没读完缓存；空列表是"读完了但没有"，跟"还没读"要分得开，否则一进来会闪一下空态。
    var textbooks by remember { mutableStateOf<List<TextbookItem>?>(null) }
    LaunchedEffect(Unit) {
        textbooks = withContext(Dispatchers.IO) { loadCurrentTermTextbooks(context) }
    }
    val list = textbooks ?: return
    if (list.isEmpty()) return

    Column(Modifier.readableWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Text("本学期我的教材", style = MiuixTheme.textStyles.subtitle, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            list.forEach { item ->
                MyTextbookRow(
                    site = site,
                    item = item,
                    onOpenFullText = onOpenFullText,
                    snackbarHostState = snackbarHostState,
                )
            }
        }
    }
}

@Composable
private fun MyTextbookRow(
    site: SiteSession,
    item: TextbookItem,
    onOpenFullText: (ssno: String, title: String) -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    var resolving by remember(item) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    item.courseName,
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    listOf(item.textbookName, item.author, item.publisher)
                        .filter { it.isNotBlank() }
                        .joinToString(" · "),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            TextButton(
                text = if (resolving) "查找中…" else "读全文",
                enabled = !resolving,
                onClick = {
                    resolving = true
                    scope.launch {
                        val outcome = withContext(Dispatchers.IO) { resolveFullText(site, item) }
                        resolving = false
                        when (outcome) {
                            is FullTextOutcome.Found -> onOpenFullText(outcome.ssno, item.textbookName)
                            FullTextOutcome.NotFound -> snackbarHostState.showSnackbar("全文库里没有这本")
                            FullTextOutcome.Failed -> snackbarHostState.showSnackbar("全文库暂时打不开，可能在校外")
                        }
                    }
                }
            )
        }
    }
}

/** 读当前学期（term_list 第一个）的选用教材缓存，过滤掉"无教材"这类空信息。 */
private fun loadCurrentTermTextbooks(context: android.content.Context): List<TextbookItem> {
    val dc = DataCache(context)
    val gson = Gson()
    val term = runCatching {
        dc.get("schedule_term_list", Long.MAX_VALUE)
            ?.let { gson.fromJson(it, Array<String>::class.java)?.firstOrNull() }
    }.getOrNull() ?: return emptyList()
    return runCatching {
        dc.get("schedule_textbooks_$term", Long.MAX_VALUE)?.let { json ->
            gson.fromJson(json, Array<TextbookItem>::class.java)
                .map { it.sanitized() }
                .filter { it.hasSubstantiveTextbook }
        }
    }.getOrNull().orEmpty()
}

/**
 * isbn 非空就按 ISBN 精确检索；isbn 为空退回书名检索，但只认书名完全一致的结果，
 * 防止张冠李戴（全文库里同名异书不少见）。
 *
 * 注意：[Jiaocai1Api.search] 内部把网络异常也吞成了空结果（见该文件），
 * 所以实际的"校外打不开"目前基本都会走到 [FullTextOutcome.NotFound]，
 * 不会走到 [FullTextOutcome.Failed]——这是全文库检索接口本身的既有行为，
 * 这次没有改它（改动面会波及全文库检索/分类浏览，超出本 PR 范围）。
 * [FullTextOutcome.Failed] 分支留着兜运行时异常（比如空指针），不是摆设。
 */
private fun resolveFullText(site: SiteSession, item: TextbookItem): FullTextOutcome = try {
    val isbn = item.isbn.trim()
    val result = if (isbn.isNotBlank()) {
        Jiaocai1Api(site).search(keyword = normalizeIsbn(isbn), field = Jiaocai1SearchField.ISBN)
    } else {
        val byName = Jiaocai1Api(site).search(
            keyword = item.textbookName.trim(),
            field = Jiaocai1SearchField.BOOK_NAME,
        )
        byName.copy(books = byName.books.filter { it.title.trim() == item.textbookName.trim() })
    }
    val ssno = result.books.firstOrNull()?.ssno
    if (ssno != null) FullTextOutcome.Found(ssno) else FullTextOutcome.NotFound
} catch (e: Exception) {
    FullTextOutcome.Failed
}
