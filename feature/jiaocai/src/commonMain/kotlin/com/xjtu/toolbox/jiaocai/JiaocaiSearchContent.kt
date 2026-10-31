package com.xjtu.toolbox.jiaocai

import com.xjtu.toolbox.jiaocai1.Jiaocai1Source
import com.xjtu.toolbox.platform.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.jiaocai1.Jiaocai1SearchField
import com.xjtu.toolbox.ui.components.AppSearchBar
import com.xjtu.toolbox.ui.components.AppSuggestionChip
import com.xjtu.toolbox.ui.components.rememberRetainedLazyStaggeredGridState
import com.xjtu.toolbox.ui.adaptive.AdaptiveCardGrid
import com.xjtu.toolbox.ui.adaptive.fullLineItem
import com.xjtu.toolbox.ui.adaptive.readableWidth
import androidx.compose.foundation.lazy.staggeredgrid.items
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * 「查教材」栏内容，不带 Scaffold / TopAppBar：检索框 + 教材中心检索结果，
 * 搜索框为空时额外插一段「本学期我的教材」，带一键「读全文」（本端有全文库那条链时）。
 * 供 [TextbookScreen] 调用。
 */
@Composable
internal fun JiaocaiSearchContent(
    source: JiaocaiSource,
    termTextbooks: JiaocaiTermTextbooks,
    /** 全文库那一半的取数（「读全文 / 阅读全文」要它）。 */
    jiaocai1: Jiaocai1Source?,
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

    // 在网格外读：网格项滚出屏幕会被销毁，放里面每次滚回都重读一遍。null = 还没读完。
    var myTextbooks by remember { mutableStateOf<List<JiaocaiTermTextbook>?>(null) }
    LaunchedEffect(termTextbooks) { myTextbooks = termTextbooks.currentTerm() }

    // 搜索结果与教材区共用一个列表，二者切换时回到顶部
    var shownSearched by remember { mutableStateOf(hasSearched) }
    LaunchedEffect(hasSearched) {
        if (hasSearched != shownSearched) {
            shownSearched = hasSearched
            listState.scrollToItem(0)
        }
    }

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
                    isLoading -> com.xjtu.toolbox.ui.components.LoadingState(message = null)
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
                                Text("教材目录里没搜到，试试「全文库」。",
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp))
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
                        // 放进可滚动网格，避免教材多时末卡被压扁（#205）
                        if (!hasSearched) {
                            fullLineItem {
                                MyTextbooksSection(
                                    textbooks = myTextbooks,
                                    jiaocai1 = jiaocai1,
                                    onOpenFullText = onOpenFullText,
                                    snackbarHostState = snackbarHostState,
                                )
                            }
                        } else {
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
                source = source,
                book = book,
                onDismiss = { vm.selected = null },
                // 没有全文库那一半（本端没有那条链）时 `onRead = null`：详情卡里那个
                // 「阅读全文」按钮整个不画（见 `JiaocaiBookSheet` 的说明）。
                onRead = if (jiaocai1 != null) { ssno -> onOpenFullText(ssno, book.title) } else null,
            )
        }
    }
}

@Composable
private fun BookCard(book: JiaocaiBook, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick),
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
    source: JiaocaiSource,
    book: JiaocaiBook,
    onDismiss: () -> Unit,
    /** 开了全文阅读才传（本端没有 `jiaocai1` 那条链时传 null ⇒ 那个按钮不画）。 */
    onRead: ((ssno: String) -> Unit)?,
) {
    var ssno by remember(book.id) { mutableStateOf(book.ssno) }
    var resolving by remember(book.id) { mutableStateOf(false) }
    LaunchedEffect(book.id) {
        // 没有阅读入口时连 `ssno` 也不拉：拉了也没地方用（入口那半在下面同一个判断里）
        if (onRead == null) return@LaunchedEffect
        if (ssno != null || !book.hasFullText) return@LaunchedEffect
        resolving = true
        ssno = source.fetchSsno(book)
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

            // 见参数说明：本端没有全文阅读（serve 形态）时连这个按钮都不画 ——
            // 画一个点了打不开的「阅读全文」比不画更差。
            val reader = onRead
            if (book.hasFullText && reader != null) {
                Spacer(Modifier.height(16.dp))
                val ready = ssno
                TextButton(
                    text = when {
                        ready != null -> "阅读全文"
                        resolving -> "正在获取全文地址…"
                        else -> "该书暂无法在线阅读"
                    },
                    onClick = { if (ready != null) reader(ready) },
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
//   来自 JiaocaiApi.search（现在屏只认 JiaocaiSource 端口）；
// - 「全文库」栏答的是「读书」，来自 Jiaocai1Api（现在屏只认 Jiaocai1Source 端口），全文库只认 IP、不走 CAS，校外大概率用不了。
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
    /** null = 还没读完缓存，先不画，免得闪一下空态。 */
    textbooks: List<JiaocaiTermTextbook>?,
    jiaocai1: Jiaocai1Source?,
    onOpenFullText: (ssno: String, title: String) -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    val list = textbooks ?: return
    if (list.isEmpty()) {
        Card(Modifier.readableWidth().padding(16.dp).fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("本学期教材", style = MiuixTheme.textStyles.subtitle, fontWeight = FontWeight.Bold)
                Text("暂无配书信息，可以按书名或课程搜索。", style = MiuixTheme.textStyles.body2)
            }
        }
        return
    }

    Column(Modifier.readableWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Text("本学期我的教材", style = MiuixTheme.textStyles.subtitle, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            list.forEach { item ->
                MyTextbookRow(
                    jiaocai1 = jiaocai1,
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
    jiaocai1: Jiaocai1Source?,
    item: JiaocaiTermTextbook,
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
            // 「读全文」要打全文库：本端没有那条链（serve 形态）时这一格的按钮不画
            val lib = jiaocai1
            if (lib != null) {
                TextButton(
                    text = if (resolving) "查找中…" else "读全文",
                    enabled = !resolving,
                    onClick = {
                        resolving = true
                        scope.launch {
                            val outcome = resolveFullText(lib, item)
                            resolving = false
                            when (outcome) {
                                is FullTextOutcome.Found -> onOpenFullText(outcome.ssno, item.textbookName)
                                FullTextOutcome.NotFound -> {
                                    snackbarHostState.showSnackbar("没对上这本书，试试在「全文库」按书名搜。")
                                }
                                FullTextOutcome.Failed -> snackbarHostState.showSnackbar("全文库暂时打不开，请稍后重试。")
                            }
                        }
                    }
                )
            }
        }
    }
}


/** 先按 ISBN 查询；没有结果时按书名查，唯一匹配才直接打开。 */
private suspend fun resolveFullText(jiaocai1: Jiaocai1Source, item: JiaocaiTermTextbook): FullTextOutcome = try {
    val isbn = item.isbn.trim()
    val byIsbn = if (isbn.isNotBlank()) {
        jiaocai1.search(
            keyword = normalizeIsbn(isbn),
            field = Jiaocai1SearchField.ISBN,
            cls = "",
            page = 1,
        )
    } else null
    val result = if (byIsbn != null && byIsbn.books.isNotEmpty()) byIsbn else {
        val byName = jiaocai1.search(
            keyword = item.textbookName.trim(),
            field = Jiaocai1SearchField.BOOK_NAME,
            cls = "",
            page = 1,
        )
        fun normalized(title: String) = title.filterNot { it.isWhitespace() || it in "·・，,：:（）()" }.lowercase()
        byName.copy(books = byName.books.filter { normalized(it.title) == normalized(item.textbookName) })
    }
    val ssno = result.books.singleOrNull()?.ssno
    if (ssno != null) FullTextOutcome.Found(ssno) else FullTextOutcome.NotFound
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Exception) {
    FullTextOutcome.Failed
}
