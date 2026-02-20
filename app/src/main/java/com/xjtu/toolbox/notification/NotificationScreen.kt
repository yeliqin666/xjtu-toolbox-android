package com.xjtu.toolbox.notification

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjtu.toolbox.ui.components.AppFilterChip
import com.xjtu.toolbox.ui.components.EmptyState
import com.xjtu.toolbox.ui.components.ErrorState
import com.xjtu.toolbox.ui.components.LoadingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationScreen(
    onBack: () -> Unit,
    onNavigate: (String) -> Unit = {}
) {
    val api = remember { NotificationApi() }
    val scope = rememberCoroutineScope()

    // ── 状态 ──
    var selectedCategory by rememberSaveable { mutableStateOf<SourceCategory?>(null) } // null = 全部分类
    var selectedSource by rememberSaveable { mutableStateOf(NotificationSource.JWC) }
    var mergeMode by rememberSaveable { mutableStateOf(false) }
    var selectedSources by rememberSaveable { mutableStateOf(setOf(NotificationSource.JWC)) }

    var notifications by remember { mutableStateOf<List<Notification>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var isSearchActive by rememberSaveable { mutableStateOf(false) }
    var currentPage by rememberSaveable { mutableIntStateOf(1) }

    // 缓存
    val cache = remember { mutableMapOf<Any, List<Notification>>() }
    val listState = rememberLazyListState()

    // 当前分类下的来源
    val sourcesInCategory = remember(selectedCategory) {
        if (selectedCategory == null) NotificationSource.entries.toList()
        else NotificationSource.byCategory(selectedCategory!!)
    }

    // 过滤后的通知
    val filteredNotifications = remember(notifications, searchQuery) {
        if (searchQuery.isBlank()) notifications
        else notifications.filter { it.title.contains(searchQuery, ignoreCase = true) }
    }

    // 缓存 key
    val cacheKey: Any = if (mergeMode) selectedSources.toSortedSet().joinToString(",") else selectedSource

    // ── 加载通知 ──
    fun loadNotifications(page: Int = 1, append: Boolean = false) {
        scope.launch {
            if (!append && cache[cacheKey] == null) isLoading = true
            errorMessage = null
            try {
                val result = withContext(Dispatchers.IO) {
                    if (mergeMode) {
                        api.getMergedNotifications(selectedSources.toList(), page)
                    } else {
                        api.getNotifications(selectedSource, page)
                    }
                }
                val newList = if (append) notifications + result else result
                notifications = newList
                cache[cacheKey] = newList
                currentPage = page
            } catch (e: Exception) {
                if (!append) errorMessage = "加载失败: ${e.message}"
            } finally {
                isLoading = false
                isLoadingMore = false
            }
        }
    }

    // 来源/模式切换 → 加载
    LaunchedEffect(selectedSource, mergeMode, selectedSources.size) {
        currentPage = 1
        cache[cacheKey]?.let { notifications = it }
        loadNotifications()
    }

    // 滑动到底自动翻页
    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = listState.layoutInfo.totalItemsCount
            totalItems > 0 && lastVisibleIndex >= totalItems - 3
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore && !isLoading && !isLoadingMore && filteredNotifications.isNotEmpty()) {
            isLoadingMore = true
            loadNotifications(page = currentPage + 1, append = true)
        }
    }

    Scaffold(
        topBar = {
            if (isSearchActive) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { isSearchActive = false; searchQuery = "" }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    title = {
                        TextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("搜索通知标题...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                cursorColor = MaterialTheme.colorScheme.primary
                            ),
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Default.Close, contentDescription = "清除")
                                    }
                                }
                            }
                        )
                    }
                )
            } else {
                TopAppBar(
                    title = { Text("通知公告") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        // 合并模式切换
                        IconButton(onClick = {
                            mergeMode = !mergeMode
                            if (mergeMode && selectedSources.isEmpty()) {
                                selectedSources = setOf(selectedSource)
                            }
                        }) {
                            Icon(
                                Icons.Default.Merge,
                                contentDescription = if (mergeMode) "取消合并" else "合并模式",
                                tint = if (mergeMode) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { isSearchActive = true }) {
                            Icon(Icons.Default.Search, contentDescription = "搜索")
                        }
                        IconButton(onClick = {
                            currentPage = 1
                            loadNotifications()
                        }) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新")
                        }
                    }
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ═══ 分类选择（横向滚动 Chips） ═══
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // "全部" chip
                AppFilterChip(
                    selected = selectedCategory == null,
                    onClick = { selectedCategory = null },
                    label = "全部"
                )
                SourceCategory.entries.forEach { cat ->
                    AppFilterChip(
                        selected = selectedCategory == cat,
                        onClick = { selectedCategory = cat },
                        label = cat.displayName
                    )
                }
            }

            // ═══ 来源选择（横向滚动 Chips） ═══
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                sourcesInCategory.forEach { source ->
                    if (mergeMode) {
                        AppFilterChip(
                            selected = source in selectedSources,
                            onClick = {
                                selectedSources = if (source in selectedSources) {
                                    if (selectedSources.size > 1) selectedSources - source else selectedSources
                                } else {
                                    selectedSources + source
                                }
                            },
                            label = source.displayName
                        )
                    } else {
                        AppFilterChip(
                            selected = source == selectedSource,
                            onClick = { selectedSource = source },
                            label = source.displayName
                        )
                    }
                }
            }

            // 合并模式提示
            if (mergeMode) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        "合并模式：已选 ${selectedSources.size} 个来源，通知按时间排列",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            // ═══ 加载条 ═══
            AnimatedVisibility(isLoading && notifications.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            // ═══ 内容区 ═══
            when {
                isLoading && notifications.isEmpty() -> {
                    LoadingState(message = "正在加载通知...", modifier = Modifier.fillMaxSize())
                }

                errorMessage != null && notifications.isEmpty() -> {
                    ErrorState(
                        message = errorMessage ?: "未知错误",
                        onRetry = { loadNotifications() },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                else -> {
                    if (filteredNotifications.isEmpty()) {
                        EmptyState(
                            title = if (searchQuery.isNotBlank()) "没有匹配的通知" else "暂无通知",
                            subtitle = if (searchQuery.isNotBlank()) "请尝试更改搜索关键词" else "当前暂无新通知",
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(
                                filteredNotifications.size,
                                key = { index -> "${filteredNotifications[index].source.name}_${index}_${filteredNotifications[index].link.hashCode()}" }
                            ) { index ->
                                val notification = filteredNotifications[index]
                                NotificationCard(
                                    notification = notification,
                                    showSource = mergeMode,
                                    onClick = {
                                        onNavigate(com.xjtu.toolbox.Routes.browser(notification.link))
                                    }
                                )
                            }

                            if (isLoadingMore) {
                                item {
                                    Box(
                                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==================== 日期格式化 ====================

private fun formatRelativeDate(date: LocalDate): String {
    return try {
        val today = LocalDate.now()
        val days = ChronoUnit.DAYS.between(date, today)
        when {
            days < -1L  -> "${-days}\u5929\u540e"   // \u672a\u6765\u8d85\u8fc71\u5929
            days == -1L -> "\u660e\u65e5"
            days == 0L  -> "\u4eca\u65e5"
            days == 1L  -> "\u6628\u65e5"
            days in 2..6 -> "${days}\u5929\u524d"
            days in 7..13 -> "\u4e0a\u5468"
            days in 14..30 -> "${days / 7}\u5468\u524d"
            days in 31..365 -> "${days / 30}\u4e2a\u6708\u524d"
            else -> date.toString()
        }
    } catch (_: Exception) {
        date.toString()
    }
}

// ==================== 通知卡片 ====================

@Composable
private fun NotificationCard(
    notification: Notification,
    showSource: Boolean = false,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = notification.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // 合并模式下显示来源标签
                    if (showSource) {
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                notification.source.displayName,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    notification.tags.forEach { tag ->
                        AssistChip(
                            onClick = {},
                            label = { Text(tag, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.height(24.dp)
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!showSource) {
                        Text(
                            text = notification.source.displayName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = formatRelativeDate(notification.date),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
