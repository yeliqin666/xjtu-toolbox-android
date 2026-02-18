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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationScreen(
    onBack: () -> Unit,
    onNavigate: (String) -> Unit = {}
) {
    val api = remember { NotificationApi() }
    val scope = rememberCoroutineScope()

    // ── 状态 ──
    var selectedCategory by remember { mutableStateOf<SourceCategory?>(null) } // null = 全部分类
    var selectedSource by remember { mutableStateOf(NotificationSource.JWC) }
    var mergeMode by remember { mutableStateOf(false) }
    var selectedSources by remember { mutableStateOf(setOf(NotificationSource.JWC)) }

    var notifications by remember { mutableStateOf<List<Notification>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    var currentPage by remember { mutableIntStateOf(1) }

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
                FilterChip(
                    selected = selectedCategory == null,
                    onClick = { selectedCategory = null },
                    label = { Text("全部", fontSize = 13.sp) }
                )
                SourceCategory.entries.forEach { cat ->
                    FilterChip(
                        selected = selectedCategory == cat,
                        onClick = { selectedCategory = cat },
                        label = { Text(cat.displayName, fontSize = 13.sp) }
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
                        // 合并模式：多选
                        FilterChip(
                            selected = source in selectedSources,
                            onClick = {
                                selectedSources = if (source in selectedSources) {
                                    if (selectedSources.size > 1) selectedSources - source else selectedSources
                                } else {
                                    selectedSources + source
                                }
                            },
                            label = { Text(source.displayName, fontSize = 12.sp) }
                        )
                    } else {
                        // 单选模式
                        FilterChip(
                            selected = source == selectedSource,
                            onClick = { selectedSource = source },
                            label = { Text(source.displayName, fontSize = 12.sp) }
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
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(8.dp))
                            Text("正在加载通知...", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                errorMessage != null && notifications.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                errorMessage ?: "未知错误",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.height(16.dp))
                            FilledTonalButton(onClick = { loadNotifications() }) { Text("重试") }
                        }
                    }
                }

                else -> {
                    if (filteredNotifications.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                if (searchQuery.isNotBlank()) "没有匹配的通知" else "暂无通知",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
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
                        text = notification.date.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
