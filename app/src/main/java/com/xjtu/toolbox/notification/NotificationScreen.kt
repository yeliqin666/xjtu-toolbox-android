package com.xjtu.toolbox.notification

import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.utils.overScrollVertical

import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Merge
import com.xjtu.toolbox.ui.components.AppTopBar
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjtu.toolbox.ui.components.AppFilterChip
import com.xjtu.toolbox.ui.components.AppSuggestionChip
import com.xjtu.toolbox.ui.components.EmptyState
import com.xjtu.toolbox.ui.components.ErrorState
import com.xjtu.toolbox.ui.components.LoadingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.temporal.ChronoUnit

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
    var currentPage by rememberSaveable { mutableIntStateOf(1) }
    var hasMorePages by remember { mutableStateOf(true) }

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

    // ── 加载通知（suspend 版，由 LaunchedEffect / scope.launch 调用） ──
    suspend fun loadNotifications(page: Int = 1, append: Boolean = false) {
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
            if (append && result.isEmpty()) hasMorePages = false
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

    // 来源/模式切换 → 加载 + 滚动归顶
    LaunchedEffect(selectedSource, mergeMode, selectedSources.size) {
        currentPage = 1
        hasMorePages = true
        cache[cacheKey]?.let { notifications = it }
        loadNotifications()
        // scrollToItem 必须在 loadNotifications 之后：
        // 首次加载时 LazyColumn 不存在（显示 LoadingState），
        // 如果先 scroll 会无限挂起导致 loadNotifications 永不执行
        try { listState.scrollToItem(0) } catch (_: Exception) {}
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
        if (shouldLoadMore && hasMorePages && !isLoading && !isLoadingMore && filteredNotifications.isNotEmpty()) {
            isLoadingMore = true
            loadNotifications(page = currentPage + 1, append = true)
        }
    }

    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    Scaffold(
        topBar = {
            TopAppBar(
                title = "通知公告",
                largeTitle = "通知公告",
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        mergeMode = !mergeMode
                        if (mergeMode && selectedSources.isEmpty()) {
                            selectedSources = setOf(selectedSource)
                        }
                    }) {
                        Icon(
                            Icons.Default.Merge,
                            contentDescription = if (mergeMode) "取消合并" else "合并模式",
                            tint = if (mergeMode) MiuixTheme.colorScheme.primary
                            else MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
        ) {
            // ═══ 分类选择（文本 Tab 样式，轻量级层级感） ═══
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                val allCats = listOf<SourceCategory?>(null) + SourceCategory.entries
                allCats.forEach { cat ->
                    val isSelected = selectedCategory == cat
                    val label = cat?.displayName ?: "全部"
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { selectedCategory = cat }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            label,
                            style = MiuixTheme.textStyles.body2,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) MiuixTheme.colorScheme.primary
                            else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1
                        )
                        Spacer(Modifier.height(4.dp))
                        Box(
                            Modifier
                                .width(if (isSelected) 20.dp else 0.dp)
                                .height(3.dp)
                                .background(
                                    if (isSelected) MiuixTheme.colorScheme.primary else Color.Transparent,
                                    RoundedCornerShape(1.5.dp)
                                )
                        )
                    }
                }
            }

            // ─── 分割线 ───
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                thickness = 0.5.dp,
                color = MiuixTheme.colorScheme.outline.copy(alpha = 0.5f)
            )

            // ═══ 来源选择（Chip 样式） ═══
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        Icons.Default.Merge,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MiuixTheme.colorScheme.primary
                    )
                    Text(
                        "已选 ${selectedSources.size} 个来源 · 按时间排列",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.primary
                    )
                }
            }

            com.xjtu.toolbox.ui.components.AppSearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                label = "搜索通知标题",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )

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
                        onRetry = { scope.launch { loadNotifications() } },
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
                        var isPullRefreshing by remember { mutableStateOf(false) }
                        top.yukonga.miuix.kmp.basic.PullToRefresh(
                            isRefreshing = isPullRefreshing,
                            onRefresh = {
                                isPullRefreshing = true
                                scope.launch {
                                    currentPage = 1
                                    loadNotifications()
                                    isPullRefreshing = false
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize().overScrollVertical(),
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
                                        CircularProgressIndicator(size = 24.dp)
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
    top.yukonga.miuix.kmp.basic.Card(
        modifier = Modifier
            .fillMaxWidth(),
        onClick = onClick,
        pressFeedbackType = top.yukonga.miuix.kmp.utils.PressFeedbackType.Sink,
        colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = notification.title,
                style = MiuixTheme.textStyles.body1,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MiuixTheme.colorScheme.onSurface
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
                            shape = RoundedCornerShape(4.dp),
                            color = MiuixTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                notification.source.displayName,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MiuixTheme.textStyles.footnote1,
                                fontWeight = FontWeight.Bold,
                                color = MiuixTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    notification.tags.forEach { tag ->
                        AppSuggestionChip(
                            onClick = {},
                            label = tag,
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
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = formatRelativeDate(notification.date),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
        }
    }
}
