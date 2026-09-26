package com.xjtu.toolbox.notification

import com.xjtu.toolbox.ui.components.enterOnce
import com.xjtu.toolbox.ui.adaptive.fullLineItem
import com.xjtu.toolbox.ui.glass.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.ui.components.AppFilterChip
import com.xjtu.toolbox.ui.components.AppSuggestionChip
import com.xjtu.toolbox.ui.components.EmptyState
import com.xjtu.toolbox.ui.components.ErrorState
import com.xjtu.toolbox.ui.components.LoadingState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import com.xjtu.toolbox.nav.AppRoute

@Composable
fun NotificationScreen(
    onBack: () -> Unit,
    onNavigate: (AppRoute) -> Unit = {}
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
    /**
     * 本轮哪些通知源被静默跳过（域名级失败/异常）：用于顶部 banner 告知用户
     * 「这些来源可能没拉到，不要以为是没人发通知」。
     */
    var skippedSourceNotice by remember { mutableStateOf<String?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var currentPage by rememberSaveable { mutableIntStateOf(1) }
    var hasMorePages by remember { mutableStateOf(true) }

    // 缓存
    val cache = remember { mutableMapOf<Any, List<Notification>>() }
    val listState = androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState()

    // 当前分类下的来源
    val sourcesInCategory = remember(selectedCategory) {
        if (selectedCategory == null) NotificationSource.entries.toList()
        else NotificationSource.byCategory(selectedCategory!!)
    }

    // 缓存 key
    val cacheKey: Any = if (mergeMode) selectedSources.toSortedSet().joinToString(",") else selectedSource

    // ── 站内搜索 ──
    // 以前只在已经抓回来的一两页里按标题筛，半年前的通知永远搜不到。现在停手 0.5 秒后
    // 用各站自己的检索查全站（见 NotificationApi.search）；结果回来之前先拿本地筛的顶着，不空屏。
    val searching = searchQuery.isNotBlank()
    var searchResults by remember { mutableStateOf<List<Notification>?>(null) }
    var searchLoading by remember { mutableStateOf(false) }
    var searchNotice by remember { mutableStateOf<String?>(null) }
    val searchSources = if (mergeMode) selectedSources.toList() else listOf(selectedSource)
    LaunchedEffect(searchQuery, cacheKey) {
        searchResults = null
        searchNotice = null
        val kw = searchQuery.trim()
        if (kw.isEmpty()) { searchLoading = false; return@LaunchedEffect }
        searchLoading = true
        kotlinx.coroutines.delay(500)
        try {
            val r = api.search(searchSources, kw)
            searchResults = r.items
            searchNotice = r.skipped.takeIf { it.isNotEmpty() }
                ?.joinToString("、") { it.displayName }?.let { "$it 这次没搜成，可能是网络问题或站点维护" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            searchNotice = "站内搜索失败：${e.message ?: "未知错误"}，下面只是已加载通知里的匹配"
        } finally {
            searchLoading = false
        }
    }

    // 展示的通知：没在搜就是列表；在搜时是站内结果，外加本地已加载里的匹配（合并去重、按日期）
    val filteredNotifications = remember(notifications, searchQuery, searchResults) {
        if (!searching) notifications
        else {
            val local = notifications.filter { it.title.contains(searchQuery.trim(), ignoreCase = true) }
            val remote = searchResults
            if (remote == null) local
            else (remote + local).distinctBy { it.link }.sortedByDescending { it.date }
        }
    }

    // ── 加载通知（suspend 版，由 LaunchedEffect / scope.launch 调用） ──
    suspend fun loadNotifications(page: Int = 1, append: Boolean = false) {
        if (!append && cache[cacheKey] == null) isLoading = true
        errorMessage = null
        try {
            val fetched = withContext(Dispatchers.IO) {
                if (mergeMode) {
                    api.getMergedNotificationsWithSkipped(selectedSources.toList(), page)
                } else {
                    val pageResult = try {
                        api.getNotificationPage(selectedSource, page)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        NotificationPage(emptyList(), false)
                    }
                    MergedNotificationPage(pageResult.items, emptySet(), pageResult.hasMore)
                }
            }
            skippedSourceNotice = if (fetched.skipped.isNotEmpty()) {
                fetched.skipped.joinToString("、") { it.displayName } + " 暂不可达，可能是网络问题或站点维护"
            } else null

            val incoming = fetched.items
            if (append) {
                val seen = notifications.map { it.link }.toHashSet()
                val fresh = incoming.filter { it.link !in seen }
                if (fresh.isEmpty()) hasMorePages = false
                else {
                    notifications = notifications + fresh
                    cache[cacheKey] = notifications
                    hasMorePages = fetched.hasMore
                }
            } else {
                notifications = incoming
                cache[cacheKey] = incoming
                hasMorePages = fetched.hasMore
            }
            currentPage = page
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!append) errorMessage = "加载失败: ${e.message}"
            else hasMorePages = false
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
        try {
            listState.scrollToItem(0)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {}
    }

    // 滑动到底自动翻页
    val shouldLoadMore by remember(hasMorePages) {
        derivedStateOf {
            if (!hasMorePages) return@derivedStateOf false
            // 瀑布流里可见项不一定按下标排好，取最大的下标
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.maxOfOrNull { it.index } ?: return@derivedStateOf false
            val totalItems = listState.layoutInfo.totalItemsCount
            totalItems > 0 && lastVisibleIndex >= totalItems - 3
        }
    }

    // 和加餐券页一样：真正的请求丢进 scope，不要写在这个 Effect 里。
    // 若把 isLoadingMore 当 key 又在 Effect 里 await，状态一改 Effect 就会被取消；
    // CancellationException 再被当成普通失败，hasMorePages 会被关掉，之后怎么拉都不翻页。
    fun requestLoadMore() {
        // 搜索结果一次取齐（每个来源前两页），不跟着列表翻页
        if (searching || isLoading || isLoadingMore || !hasMorePages || filteredNotifications.isEmpty()) return
        isLoadingMore = true
        scope.launch { loadNotifications(page = currentPage + 1, append = true) }
    }

    LaunchedEffect(shouldLoadMore, isLoadingMore, isLoading) {
        if (shouldLoadMore && hasMorePages && !isLoading && !isLoadingMore && filteredNotifications.isNotEmpty()) {
            requestLoadMore()
        }
    }

    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    // 玻璃顶栏（经典风格下为 null，一切照旧），用法见 ui/glass/GlassTopBar.kt
    val glass = rememberPageGlass()
    Scaffold(
        topBar = {
            TopAppBar(
                title = "通知公告",
                largeTitle = "通知公告",
                color = glassBarColor(glass),
                modifier = Modifier.glassTopBar(glass),
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
                },
                // 分类、来源两行是导航，不跟列表滚：挂在顶栏里和顶栏一起做一整块玻璃，
                // 通知卡片从它们下面滚过去
                bottomContent = {
                  Column {
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

                    // ═══ 加载条 ═══
                    AnimatedVisibility((isLoading && notifications.isNotEmpty()) || searchLoading, enter = fadeIn(), exit = fadeOut()) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                  }
                },
            )
        }
    ) { padding ->
        // 内容铺到顶栏下面。宽屏不再限宽 720：下面的卡片分列铺开，上面两行跟着占满。
        val glassTop = padding.glassTop(glass)
        Column(
            modifier = Modifier
                .padding(padding.withoutTop(glass))
                .glassSource(glass)
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
        ) {
            // 分类、来源两行在顶栏里（bottomContent）；列表把 glassTop 放进 contentPadding，
            // 不是列表的几种状态（加载中 / 出错 / 暂无）在这里让出顶栏高度
            val listShown = !(isLoading && notifications.isEmpty()) &&
                !(errorMessage != null && notifications.isEmpty()) &&
                notifications.isNotEmpty()
            if (!listShown) Spacer(Modifier.height(glassTop))

            // 搜索框和「有源不可达」提示：有通知列表时是列表的头两项，跟着列表滚走；
            // 分类、来源两行是导航，留在顶上。以前这一整段都钉在顶部，占掉小半屏，
            // 往上划只有下面一截通知在动。没有列表（加载中 / 出错 / 暂无通知）时它们照旧放在这里。
            // 搜不到匹配项时列表还在（只剩搜索框和一行「没有匹配」）：要是这时把搜索框挪出列表，
            // 输入框换了位置就会丢焦点，打字打到一半键盘收起来。
            val searchBar: @Composable (Modifier) -> Unit = { m ->
                com.xjtu.toolbox.ui.components.AppSearchBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    label = if (mergeMode) "在已选的 ${selectedSources.size} 个来源里搜索" else "在${selectedSource.displayName}站内搜索",
                    modifier = m.fillMaxWidth()
                )
            }
            // 通知源静默跳过提示：让用户知道"不是没通知，是某些源被静默"。
            val bannerText = if (searching) searchNotice else skippedSourceNotice
            val skippedNoticeBanner: @Composable (Modifier) -> Unit = { m ->
              bannerText?.let { msg ->
                Surface(
                    color = MiuixTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f),
                    modifier = m.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.WarningAmber,
                            contentDescription = "通知源不可达",
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            msg,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.footnote1,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
              }
            }
            if (!listShown) {
                searchBar(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                skippedNoticeBanner(Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            }

            // ═══ 内容区 ═══
            when {
                isLoading && notifications.isEmpty() -> {
                    com.xjtu.toolbox.ui.components.SkeletonList(Modifier.fillMaxSize(), rows = 7, rowHeight = 84.dp)
                }

                errorMessage != null && notifications.isEmpty() -> {
                    ErrorState(
                        message = errorMessage ?: "未知错误",
                        onRetry = { scope.launch { loadNotifications() } },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                else -> {
                    if (!listShown) {
                        EmptyState(
                            title = "暂无通知",
                            subtitle = "当前暂无新通知",
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        var isPullRefreshing by remember { mutableStateOf(false) }
                        top.yukonga.miuix.kmp.basic.PullToRefresh(
                            refreshTexts = com.xjtu.toolbox.ui.components.AppRefreshTexts,
                            // 顶栏折叠交给下拉刷新协调：往下拉先展开大标题，展开完才算下拉刷新。不传的话下拉刷新先把拖动吃掉，慢慢拉只会刷新、标题展不开
                            topAppBarScrollBehavior = scrollBehavior,
                            isRefreshing = isPullRefreshing,
                            onRefresh = {
                                isPullRefreshing = true
                                scope.launch {
                                    cache.remove(cacheKey)
                                    currentPage = 1
                                    hasMorePages = true
                                    loadNotifications()
                                    isPullRefreshing = false
                                }
                            },
                            // 下拉指示器从玻璃顶栏（含分类、来源两行）下面出来
                            contentPadding = PaddingValues(top = glassTop),
                            modifier = Modifier.fillMaxSize()
                        ) {
                        // 宽屏分两三列排卡片（见 AdaptiveCardGrid），窄屏和原来的单列一样
                        com.xjtu.toolbox.ui.adaptive.AdaptiveCardGrid(
                            modifier = Modifier.fillMaxSize().overScrollVertical(),
                            state = listState,
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp + glassTop, bottom = 8.dp),
                            spacing = 8.dp,
                        ) {
                            fullLineItem(key = "search") { searchBar(Modifier) }
                            if (bannerText != null) {
                                fullLineItem(key = "skipped") { skippedNoticeBanner(Modifier) }
                            }
                            if (searching && !searchLoading && searchResults != null && filteredNotifications.isNotEmpty()) {
                                fullLineItem(key = "search_count") {
                                    Text(
                                        "站内搜到 ${filteredNotifications.size} 条 · 按时间排列",
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        modifier = Modifier.padding(horizontal = 4.dp),
                                    )
                                }
                            }
                            if (filteredNotifications.isEmpty()) {
                                fullLineItem(key = "no_match") {
                                    if (searching && searchLoading) {
                                        Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                                            CircularProgressIndicator(size = 24.dp)
                                        }
                                    } else {
                                        EmptyState(
                                            title = "没有匹配的通知",
                                            subtitle = if (searching) "站内也没搜到，换个关键词试试" else "请尝试更改搜索关键词",
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp)
                                        )
                                    }
                                }
                            }
                            items(
                                filteredNotifications.size,
                                key = { index -> "${filteredNotifications[index].source.name}_${index}_${filteredNotifications[index].link.hashCode()}" }
                            ) { index ->
                                val notification = filteredNotifications[index]
                                // 第一屏错峰淡入，之后的直接就位
                                androidx.compose.foundation.layout.Box(Modifier.enterOnce(index)) {
                                NotificationCard(
                                    notification = notification,
                                    showSource = mergeMode,
                                    onClick = {
                                        onNavigate(AppRoute.Browser(notification.link))
                                    }
                                )
                                }
                            }

                            if (!searching && (hasMorePages || isLoadingMore)) {
                                fullLineItem {
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
                // 左边这组让出右边的来源和日期：标签多了自己截断，不把右边挤出卡片
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp),
                ) {
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
                    // 标签只是说明，不能点：写成和右边来源、日期同一种小字。
                    // 以前套的是可点的 AppSuggestionChip，还把高度硬压到 24dp，比组件自己要的矮，字被上下切掉。
                    notification.tags.forEach { tag ->
                        Text(
                            tag,
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
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
