package com.xjtu.toolbox.jiaocai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import com.xjtu.toolbox.LocalAppLoginState
import com.xjtu.toolbox.Routes
import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.auth.LoginType
import com.xjtu.toolbox.auth.handleAuthExpired
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

// ── 主入口 ────────────────────────────────────────────────────────────

@Composable
fun JiaocaiScreen(login: JiaocaiLogin, onBack: () -> Unit) {
    var selectedBook by remember { mutableStateOf<JiaocaiBook?>(null) }

    if (selectedBook != null) {
        JiaocaiDetailScreen(
            book = selectedBook!!,
            onBack = { selectedBook = null }
        )
    } else {
        JiaocaiSearchScreen(
            login = login,
            onBookClick = { selectedBook = it },
            onBack = onBack
        )
    }
}

// ── 搜索页 ────────────────────────────────────────────────────────────

@Composable
private fun JiaocaiSearchScreen(
    login: JiaocaiLogin,
    onBookClick: (JiaocaiBook) -> Unit,
    onBack: () -> Unit
) {
    val appLoginState = LocalAppLoginState.current
    val scope = rememberCoroutineScope()
    var keyword by remember { mutableStateOf("") }
    var books by remember { mutableStateOf<List<JiaocaiBook>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var hasSearched by remember { mutableStateOf(false) }

    fun doSearch() {
        if (keyword.isBlank()) return
        scope.launch {
            isLoading = true; errorMsg = null
            try {
                books = withContext(Dispatchers.IO) { JiaocaiApi(login).search(keyword) }
                hasSearched = true
            } catch (e: AuthExpiredException) {
                appLoginState.handleAuthExpired(LoginType.JIAOCAI, Routes.JIAOCAI, onBack)
            } catch (e: Exception) {
                errorMsg = "搜索失败: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = "教材中心",
                color = MiuixTheme.colorScheme.surfaceVariant,
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
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(12.dp))

            // 搜索框
            Row(verticalAlignment = Alignment.CenterVertically) {
                com.xjtu.toolbox.ui.components.AppSearchBar(
                    query = keyword,
                    onQueryChange = { keyword = it },
                    label = "搜索教材（书名/作者/课程）",
                    onSearch = { doSearch() },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { doSearch() },
                    enabled = !isLoading && keyword.isNotBlank()
                ) {
                    Text(if (isLoading) "…" else "搜索")
                }
            }

            Spacer(Modifier.height(12.dp))

            when {
                isLoading -> Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                errorMsg != null -> Text(errorMsg!!, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 24.dp))
                hasSearched && books.isEmpty() -> {
                    Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.MenuBook, null, Modifier.size(40.dp),
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            Spacer(Modifier.height(8.dp))
                            Text("未找到相关教材", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                    }
                }
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(books) { book ->
                        BookCard(book = book, onClick = { onBookClick(book) })
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }
}

@Composable
private fun BookCard(book: JiaocaiBook, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        cornerRadius = 12.dp,
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(44.dp).background(
                    MiuixTheme.colorScheme.primary.copy(alpha = 0.12f),
                    RoundedCornerShape(8.dp)
                ),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.MenuBook, null, Modifier.size(24.dp),
                    tint = MiuixTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(book.title, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (book.author.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(book.author, style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (book.summary.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(book.summary, style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                if (book.hasFullText) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.background(
                            MiuixTheme.colorScheme.primary.copy(alpha = 0.15f),
                            RoundedCornerShape(4.dp)
                        ).padding(horizontal = 6.dp, vertical = 2.dp)) {
                            Text("本地全文", style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}

// ── 详情页（纯信息展示）──────────────────────────────────────────────

@Composable
private fun JiaocaiDetailScreen(
    book: JiaocaiBook,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = book.title,
                color = MiuixTheme.colorScheme.surfaceVariant,
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
                .overScrollVertical()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // 封面占位
            Box(
                Modifier.fillMaxWidth().height(180.dp).background(
                    MiuixTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(12.dp)
                ),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.MenuBook, null, Modifier.size(64.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }

            Spacer(Modifier.height(16.dp))

            Text(book.title, style = MiuixTheme.textStyles.title4, fontWeight = FontWeight.Bold)
            if (book.author.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(book.author, style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            if (book.summary.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(book.summary, style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }

            if (book.hasFullText) {
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.background(
                        MiuixTheme.colorScheme.primary.copy(alpha = 0.15f),
                        RoundedCornerShape(4.dp)
                    ).padding(horizontal = 6.dp, vertical = 2.dp)) {
                        Text("本地全文可用", style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                    }
                }
            }

            Spacer(Modifier.height(80.dp))
        }
    }
}
