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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xjtu.toolbox.LocalAppLoginState
import com.xjtu.toolbox.Routes
import com.xjtu.toolbox.auth.LoginType
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.auth.handleAuthExpired
import com.xjtu.toolbox.ui.components.AppSearchBar
import com.xjtu.toolbox.ui.components.AppSuggestionChip
import com.xjtu.toolbox.ui.components.rememberRetainedLazyListState
import kotlinx.coroutines.Dispatchers
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
    val appLoginState = LocalAppLoginState.current
    val vm: JiaocaiViewModel = viewModel()
    vm.bind(site)

    if (vm.authExpired) {
        LaunchedEffect(Unit) {
            vm.authExpired = false
            appLoginState.handleAuthExpired(LoginType.JIAOCAI, Routes.JIAOCAI, onBack)
        }
    }

    JiaocaiSearchScreen(
        site = site,
        vm = vm,
        onBack = onBack,
        onOpenFullText = onOpenFullText,
    )
}

@Composable
private fun JiaocaiSearchScreen(
    site: SiteSession,
    vm: JiaocaiViewModel,
    onBack: () -> Unit,
    onOpenFullText: (ssno: String, title: String) -> Unit,
) {
    val listState = rememberRetainedLazyListState("jiaocai_search")
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val keyword = vm.keyword
    val books = vm.books
    val isLoading = vm.loading
    val errorMsg = vm.error
    val hasSearched = vm.hasSearched
    val selected = vm.selected

    BackHandler(enabled = selected != null) { vm.selected = null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "教材中心",
                largeTitle = "教材中心",
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
            AppSearchBar(
                query = keyword,
                onQueryChange = { vm.keyword = it },
                label = "书名、作者或课程",
                onSearch = { vm.search() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            if (!hasSearched && !isLoading) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
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
                    isLoading -> Box(
                        Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
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
                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        if (hasSearched) {
                            item {
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
                        item { Spacer(Modifier.height(80.dp)) }
                    }
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
