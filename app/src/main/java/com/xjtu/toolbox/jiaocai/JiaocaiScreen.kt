package com.xjtu.toolbox.jiaocai

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
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import com.xjtu.toolbox.LocalAppLoginState
import com.xjtu.toolbox.Routes
import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.auth.LoginType
import com.xjtu.toolbox.auth.handleAuthExpired
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
                color = MiuixTheme.colorScheme.background,
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
                .background(MiuixTheme.colorScheme.background)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                cornerRadius = 26.dp,
                colors = CardDefaults.defaultColors(color = Color.Transparent)
            ) {
                Column(
                    Modifier.fillMaxWidth().background(
                        Brush.linearGradient(
                            listOf(Color(0xFF4E68D8).copy(alpha = 0.18f), Color(0xFF36A58D).copy(alpha = 0.10f))
                        )
                    ).padding(20.dp)
                ) {
                    Icon(
                        Icons.Default.AutoStories,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = Color(0xFF4E68D8)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("找到这学期真正要用的书", style = MiuixTheme.textStyles.title4, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "按书名、作者或课程搜索，结果会整理成易读的书目卡片。",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    Spacer(Modifier.height(16.dp))
                    com.xjtu.toolbox.ui.components.AppSearchBar(
                        query = keyword,
                        onQueryChange = { keyword = it },
                        label = "书名、作者或课程",
                        onSearch = { doSearch() },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            when {
                !hasSearched && !isLoading -> {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
                        Text("搜索建议", style = MiuixTheme.textStyles.subtitle, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("高等数学", "大学物理", "程序设计").forEach { suggestion ->
                                com.xjtu.toolbox.ui.components.AppSuggestionChip(
                                    label = suggestion,
                                    onClick = { keyword = suggestion; doSearch() }
                                )
                            }
                        }
                    }
                }
            }

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
                else -> LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    item {
                        Text(
                            "找到 ${books.size} 本教材",
                            style = MiuixTheme.textStyles.subtitle,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                        )
                    }
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
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (book.hasFullText) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.background(
                            MiuixTheme.colorScheme.primary.copy(alpha = 0.15f),
                            RoundedCornerShape(8.dp)
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
                color = MiuixTheme.colorScheme.background,
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
            Card(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 26.dp,
                colors = CardDefaults.defaultColors(color = Color.Transparent)
            ) {
                Row(
                    Modifier.fillMaxWidth().background(
                        Brush.linearGradient(listOf(Color(0xFF4D66CB), Color(0xFF2C887B)))
                    ).padding(22.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.width(74.dp).height(98.dp).background(
                            Color.White.copy(alpha = 0.16f), RoundedCornerShape(16.dp)
                        ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.MenuBook, null, Modifier.size(38.dp), tint = Color.White)
                    }
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text(book.title, style = MiuixTheme.textStyles.title4, fontWeight = FontWeight.Bold, color = Color.White)
                        if (book.author.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(book.author, style = MiuixTheme.textStyles.body2, color = Color.White.copy(alpha = 0.82f))
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            if (book.summary.isNotBlank()) {
                Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
                    Column(Modifier.padding(18.dp)) {
                        Text("书目信息", style = MiuixTheme.textStyles.subtitle, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(book.summary, style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
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
