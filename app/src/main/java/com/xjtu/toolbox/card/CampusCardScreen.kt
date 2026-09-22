package com.xjtu.toolbox.card

import com.xjtu.toolbox.ui.glass.followTopBar
import com.xjtu.toolbox.ui.components.enterOnce
import com.xjtu.toolbox.ui.adaptive.readableWidth
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical

import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.xjtu.toolbox.ui.glass.glassBarSurface
import com.xjtu.toolbox.ui.glass.glassBarTint
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.xjtu.toolbox.LocalAppLoginState
import com.xjtu.toolbox.Routes
import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.auth.LoginType
import com.xjtu.toolbox.auth.handleAuthExpired
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.ui.components.AppSegmentedTabs
import com.xjtu.toolbox.ui.components.AppTabPager
import com.xjtu.toolbox.ui.components.MeshBackground
import com.xjtu.toolbox.ui.components.appCardShadow
import com.xjtu.toolbox.ui.components.LoadingState
import com.xjtu.toolbox.ui.components.ErrorState
import com.xjtu.toolbox.ui.components.EmptyState
import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.ui.platform.LocalContext
import com.xjtu.toolbox.ui.components.AppDatePickerDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs

// ==================== 时间范围枚举 ====================

private enum class TimeRange(val label: String, val months: Int?) {
    ONE_MONTH("1个月", 1),
    THREE_MONTHS("3个月", 3),
    SIX_MONTHS("半年", 6),
    ONE_YEAR("1年", 12),
    CUSTOM("自定义", null);
}

private val CardDateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/M/d")
private val CardDateChipFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("M/d")

private fun TimeRange.resolve(customStart: LocalDate, customEnd: LocalDate): Pair<LocalDate, LocalDate> {
    if (this == TimeRange.CUSTOM) {
        return if (customStart.isAfter(customEnd)) customEnd to customStart else customStart to customEnd
    }
    val months = this.months ?: 1
    return LocalDate.now().minusMonths(months.toLong()) to LocalDate.now()
}

private fun formatRangeChip(start: LocalDate, end: LocalDate): String {
    return if (start.year == end.year) {
        "${start.format(CardDateChipFmt)}–${end.format(CardDateChipFmt)}"
    } else {
        "${start.format(CardDateFmt)}–${end.format(CardDateFmt)}"
    }
}

@Composable
fun CampusCardScreen(
    site: SiteSession,
    onBack: () -> Unit,
    // 「界面风格：经典」时传 false，顶栏退回不透明——玻璃开关接口，见 plan2 §16.1
    // 第 5 条。默认 false，和 agent/ProactiveBubble.kt 的 glass 参数一个约定：
    // 接上设置项之前先按「经典」的不透明样式来，不在没接设置项的分支里提前显示玻璃。
    glass: Boolean = false,
) {
    val api = remember(site) { CampusCardApi(site) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val appLoginState = LocalAppLoginState.current

    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var cardInfo by remember { mutableStateOf<CardInfo?>(null) }
    var transactions by remember { mutableStateOf<List<Transaction>>(emptyList()) }
    var monthlyStats by remember { mutableStateOf<List<MonthlyStats>>(emptyList()) }
    var categorySpending by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    var mealTimeStats by remember { mutableStateOf<Map<String, MealTimeStats>>(emptyMap()) }
    var activeCampusDays by remember { mutableIntStateOf(0) }
    var weekdayWeekend by remember { mutableStateOf<Pair<DayTypeStats, DayTypeStats>?>(null) }
    var totalRecords by remember { mutableIntStateOf(0) }

    // 选项卡: 0=概览 1=流水 2=分析
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    // 时间范围
    var selectedTimeRange by rememberSaveable { mutableStateOf(TimeRange.ONE_MONTH) }
    var customStart by rememberSaveable { mutableStateOf(LocalDate.now().minusMonths(1).toString()) }
    var customEnd by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var showCustomRange by remember { mutableStateOf(false) }
    // 流水加载
    var isLoadingMore by remember { mutableStateOf(false) }
    var currentPage by rememberSaveable { mutableIntStateOf(1) }
    var loadGeneration by remember { mutableIntStateOf(0) }
    // 切换时间范围用：不阻塞整页，只在 Tab 顶部细进度条提示
    var isReloadingRange by remember { mutableStateOf(false) }
    // 搜索
    var searchQuery by rememberSaveable { mutableStateOf("") }

    fun currentRangeDates(): Pair<LocalDate, LocalDate> {
        val start = runCatching { LocalDate.parse(customStart) }.getOrDefault(LocalDate.now().minusMonths(1))
        val end = runCatching { LocalDate.parse(customEnd) }.getOrDefault(LocalDate.now())
        return selectedTimeRange.resolve(start, end)
    }

    fun applyTransactions(
        allTx: List<Transaction>,
        accountId: String? = com.xjtu.toolbox.account.AccountContext.activeAccountId,
    ) {
        transactions = allTx
        totalRecords = allTx.size
        currentPage = (allTx.size + 49) / 50
        CampusCardCache.cardPrefs(context, accountId).edit()
            .putTodaySummary(todaySummaryOf(allTx))
            .apply()
        com.xjtu.toolbox.widget.CampusCardWidgetUpdater.requestUpdate(context)

        val (startDate, endDate) = currentRangeDates()
        val stats = api.calculateMonthlyStats(allTx, startDate, endDate)
        monthlyStats = stats
        categorySpending = api.categorizeSpending(allTx)
        val (meals, campusDays) = api.analyzeMealTimes(allTx)
        mealTimeStats = meals
        activeCampusDays = campusDays
        weekdayWeekend = api.analyzeWeekdayVsWeekend(allTx)
    }

    fun loadData(range: TimeRange = selectedTimeRange, silent: Boolean = false) {
        val hasContent = transactions.isNotEmpty() || cardInfo != null
        if (silent || hasContent) isReloadingRange = true else isLoading = true
        errorMessage = null
        val myGeneration = ++loadGeneration
        // 发请求前定下账号，缓存读写都落在它名下；中途切了账号，结果直接丢弃
        val accountId = com.xjtu.toolbox.account.AccountContext.activeAccountId
        fun accountSwitched() = com.xjtu.toolbox.account.AccountContext.activeAccountId != accountId
        scope.launch {
            try {
                val customS = runCatching { LocalDate.parse(customStart) }.getOrDefault(LocalDate.now().minusMonths(1))
                val customE = runCatching { LocalDate.parse(customEnd) }.getOrDefault(LocalDate.now())
                val (startDate, endDate) = range.resolve(customS, customE)

                // 先获取卡信息（回填 cardAccount），再并行抓流水
                val info = withContext(Dispatchers.IO) { api.getCardInfo() }
                if (accountSwitched()) return@launch
                cardInfo = info
                // 缓存余额 + 姓名 供首页智能卡片使用
                CampusCardCache.cardPrefs(context, accountId).edit()
                    .putFloat("card_balance_cache", info.balance.toFloat())
                    .putString("card_name_cache", info.name)
                    .putLong("card_cache_time", System.currentTimeMillis())
                    .apply()
                val allTx = withContext(Dispatchers.IO) {
                    val cached = CampusCardCache.load(context, accountId)
                    val cachedStart = cached?.rangeStart?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                    if (cached != null && cachedStart != null && !cachedStart.isAfter(startDate)) {
                        val refreshStart = endDate.minusDays(7).coerceAtLeast(startDate)
                        val fresh = api.getAllTransactions(refreshStart, endDate, maxPages = 20, allowIncomplete = true)
                        (fresh + cached.transactions.filter {
                            val date = runCatching { LocalDate.parse(it.time.substringBefore(" ")) }.getOrNull()
                            date != null && date in startDate..endDate
                        }).distinctBy { "${it.time}|${it.merchant}|${it.amount}|${it.balance}|${it.description}" }
                            .sortedByDescending { it.time }
                    } else {
                        api.getAllTransactions(startDate, endDate, maxPages = 12, allowIncomplete = true)
                    }
                }
                if (myGeneration != loadGeneration || accountSwitched()) return@launch
                applyTransactions(allTx, accountId)
                CampusCardCache.save(context, info, allTx, startDate, endDate, accountId)
                // 余额和今日消费都已落盘，通知首页重读。首页 tab 一直留在组合里，
                // 只认这个版本号：不递增的话充值后刷新了这里，回到首页还是旧余额。
                appLoginState.campusCardCacheVersion++
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: AuthExpiredException) {
                // 会话失效 → 静默触发重新登录（nav 监听 pendingRetry）
                appLoginState.handleAuthExpired(LoginType.CAMPUS_CARD, Routes.CAMPUS_CARD, onBack)
            } catch (e: Exception) {
                errorMessage = "加载失败: ${e.message}"
                if (transactions.isNotEmpty()) {
                    snackbarHostState.showSnackbar("更新失败，当前显示上次缓存的数据", duration = SnackbarDuration.Long)
                }
            } finally {
                if (myGeneration == loadGeneration) {
                    isLoading = false
                    isReloadingRange = false
                }
            }
        }
    }

    fun loadMore() {
        if (isLoadingMore) return
        isLoadingMore = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val (startDate, endDate) = currentRangeDates()
                    val (_, txList) = api.getTransactions(
                        startDate = startDate,
                        endDate = endDate,
                        page = currentPage + 1,
                        pageSize = 50
                    )
                    if (txList.isNotEmpty()) {
                        transactions = transactions + txList
                        currentPage++
                        monthlyStats = api.calculateMonthlyStats(transactions, startDate, endDate)
                        categorySpending = api.categorizeSpending(transactions)
                        val (mealStats4, campusDays4) = api.analyzeMealTimes(transactions)
                        mealTimeStats = mealStats4
                        activeCampusDays = campusDays4
                        weekdayWeekend = api.analyzeWeekdayVsWeekend(transactions)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: AuthExpiredException) {
                appLoginState.handleAuthExpired(LoginType.CAMPUS_CARD, Routes.CAMPUS_CARD, onBack)
            } catch (e: Exception) {
                Log.w("CampusCardScreen", "loadMore failed: ${e.message}")
                scope.launch {
                    snackbarHostState.showSnackbar("加载更多失败，请重试", duration = SnackbarDuration.Short)
                }
            }
            finally { isLoadingMore = false }
        }
    }

    LaunchedEffect(Unit) {
        CampusCardCache.load(context)?.let { cached ->
            cardInfo = cached.cardInfo
            // 缓存覆盖的区间可能比当前选中的时间范围宽得多。整份铺上去，首屏会按更宽的
            // 数据算统计（「早午餐分析」这类面板因此出现），等网络结果按当前范围回来又
            // 整块消失——用户看到的就是一闪而过。先裁到当前范围，首屏与最终结果一致。
            val (cacheStart, cacheEnd) = currentRangeDates()
            applyTransactions(
                cached.transactions.filter { tx ->
                    runCatching { LocalDate.parse(tx.time.substringBefore(" ")) }
                        .getOrNull()?.let { it in cacheStart..cacheEnd } == true
                }
            )
            isLoading = false
        }
        loadData(silent = transactions.isNotEmpty())
    }

    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    var showRangeMenu by remember { mutableStateOf(false) }
    // 当前时间范围的一句话说法：顶栏菜单按钮的无障碍描述、流水和分析栏的小字共用
    val rangeLabel = if (selectedTimeRange == TimeRange.CUSTOM) {
        currentRangeDates().let { (start, end) -> formatRangeChip(start, end) }
    } else {
        "近${selectedTimeRange.label}"
    }
    // 这一页自己的采样源：顶栏采它，不用全局的 LocalAppBackdrop（这是二级页，有自己
    // 的 Scaffold/TopAppBar）。
    val cardBackdrop = rememberLayerBackdrop()
    // 宽屏「流水 / 分析」两栏的选中项。标签行在顶栏里，所以提到 Scaffold 外面
    val isWideTop = com.xjtu.toolbox.ui.isWideLayout()
    var wideTab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = "校园卡",
                color = if (glass) Color.Transparent else MiuixTheme.colorScheme.surface,
                largeTitle = "校园卡",
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    // 时间范围收进顶栏菜单：它同时作用于概览、流水、分析三栏，是整页的设置，
                    // 不该在标签行下面单独占一排胶囊。当前范围由各栏里的小字标出（rangeLabel）。
                    Box {
                        IconButton(onClick = { showRangeMenu = true }) {
                            Icon(Icons.Default.DateRange, contentDescription = "时间范围：$rangeLabel")
                        }
                        OverlayListPopup(
                            show = showRangeMenu,
                            alignment = PopupPositionProvider.Align.End,
                            onDismissRequest = { showRangeMenu = false },
                        ) {
                            ListPopupColumn {
                                TimeRange.entries.forEachIndexed { idx, range ->
                                    DropdownImpl(
                                        text = if (range == TimeRange.CUSTOM) "自定义…" else "近${range.label}",
                                        optionSize = TimeRange.entries.size,
                                        isSelected = range == selectedTimeRange,
                                        onSelectedIndexChange = {
                                            showRangeMenu = false
                                            if (range == TimeRange.CUSTOM) {
                                                showCustomRange = true
                                            } else if (range != selectedTimeRange) {
                                                selectedTimeRange = range
                                                currentPage = 1
                                                loadData(range, silent = true)
                                            }
                                        },
                                        index = idx,
                                    )
                                }
                            }
                        }
                    }
                },
                // 标签行不参与滚动：挂在顶栏里和顶栏一起做一整块玻璃，列表从它下面滚过去。
                // 宽屏只有右栏有「流水 / 分析」标签，标签行只占右边那一截，和右栏对齐
                bottomContent = {
                    CompositionLocalProvider(com.xjtu.toolbox.ui.glass.LocalOnGlassBar provides glass) {
                        Column {
                            // 整页在转圈 / 报错时（下面 when 的前两支）没有东西可切，不挂标签
                            val showTabs = !(cardInfo == null && transactions.isEmpty() &&
                                (isLoading || errorMessage != null))
                            if (!showTabs) {
                                // 什么都不放
                            } else if (isWideTop) {
                                Row(Modifier.fillMaxWidth()) {
                                    Spacer(Modifier.weight(0.42f))
                                    Box(Modifier.weight(0.58f)) {
                                        AppSegmentedTabs(
                                            tabs = listOf("流水", "分析"),
                                            selectedTabIndex = wideTab,
                                            onTabSelected = { wideTab = it },
                                        )
                                    }
                                }
                            } else {
                                AppSegmentedTabs(
                                    tabs = listOf("概览", "流水", "分析"),
                                    selectedTabIndex = selectedTab,
                                    onTabSelected = { selectedTab = it },
                                    modifier = Modifier.readableWidth(),
                                )
                            }
                            if (isReloadingRange) {
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                    height = 2.dp,
                                )
                            }
                        }
                    }
                },
                modifier = if (glass) {
                    // 和其他二级页顶栏同一套画法：不投影、不折射、底边渐隐，见 glassBarSurface。
                    // 以前这里单独写了一份：只模糊 4dp、没压表面色，还开了 24dp 的强折射，
                    // 余额卡滚上去时标题下面一大片被拉弯。
                    Modifier.glassBarSurface(cardBackdrop, glassBarTint())
                } else {
                    Modifier
                },
            )
        }
    ) { padding ->
        val rangeDates = currentRangeDates()
        CustomRangeDialog(
            show = showCustomRange,
            initialStart = rangeDates.first,
            initialEnd = rangeDates.second,
            onDismiss = { showCustomRange = false },
            onConfirm = { start, end ->
                customStart = start.toString()
                customEnd = end.toString()
                selectedTimeRange = TimeRange.CUSTOM
                showCustomRange = false
                currentPage = 1
                loadData(TimeRange.CUSTOM, silent = true)
            }
        )
        when {
            isLoading && cardInfo == null && transactions.isEmpty() ->
                LoadingState("正在加载校园卡数据...", Modifier.fillMaxSize().padding(padding))
            errorMessage != null && transactions.isEmpty() && cardInfo == null ->
                ErrorState(errorMessage!!, { loadData() }, Modifier.fillMaxSize().padding(padding))
            else -> {
                // 内容改成从顶栏下面穿过（plan2 §16.2）：不再用 Scaffold 的 padding 把整页
                // 往下推，而是让横滑翻页器铺满整个 Box（从 y=0 开始），列表用
                // contentPadding.top 把「顶栏 + 标签行 + 时间选择器」的高度让出来。这样
                // 初始状态和以前看着一样，往上滚动时余额卡才能真的滚到顶栏下面、透出玻璃。
                // 标签行挂在顶栏里（bottomContent），和顶栏一起是一整块玻璃，
                // 所以 Scaffold 的 padding 顶部已经连标签行一起算进去了。
                // 稳定值：顶栏折叠时不跟着每帧变，否则整页每帧重组；差额由下面的 followTopBar 补位移
                val stableTop = com.xjtu.toolbox.ui.glass.rememberStableTopPadding(padding)
                val topInset = stableTop.value
                val topContentPadding = topInset

                var isPullRefreshing by remember { mutableStateOf(false) }
                LaunchedEffect(isLoading, isReloadingRange) {
                    if (!isLoading && !isReloadingRange) isPullRefreshing = false
                }

                // 平板横屏：左栏概览、右栏「流水 / 分析」，一屏同时看到余额、汇总和明细。
                // 三栏是同一份数据的三种看法，经常要对照着看，所以宽屏下并排摆，而不是像设置页那样
                // 做成左侧目录一次只看一栏。宽屏不再用 readableWidth 限宽，否则左右各空出四分之一。
                val isWide = isWideTop
                Box(
                    Modifier
                        .fillMaxSize()
                        .then(if (isWide) Modifier else Modifier.readableWidth())
                        .nestedScroll(scrollBehavior.nestedScrollConnection)
                ) {
                    top.yukonga.miuix.kmp.basic.PullToRefresh(
                        refreshTexts = com.xjtu.toolbox.ui.components.AppRefreshTexts,
                        // 顶栏折叠交给下拉刷新协调：往下拉先展开大标题，展开完才算下拉刷新。不传的话下拉刷新先把拖动吃掉，慢慢拉只会刷新、标题展不开
                        topAppBarScrollBehavior = scrollBehavior,
                        isRefreshing = isPullRefreshing,
                        onRefresh = {
                            isPullRefreshing = true
                            loadData(silent = true)
                        },
                        modifier = Modifier.fillMaxSize(),
                        // 内容从顶栏下面穿过以后，这一层铺满整页、从屏幕顶边算起；
                        // 不告诉它顶栏和标签行有多高，指示器就会从屏幕顶边拉出来，而不是大标题下面。
                        contentPadding = PaddingValues(top = topContentPadding),
                    ) {
                        // 横滑切栏（概览/流水/分析），用契约组件 AppTabPager；标签行仍由下面的
                        // AppSegmentedTabs 负责点击切换，两者共用同一个 selectedTab。挂上
                        // layerBackdrop，顶栏才能采到「余额卡从这里滚过去」的画面。
                        if (isWide) {
                            Row(Modifier.fillMaxSize().layerBackdrop(cardBackdrop).followTopBar({ stableTop.value }, padding)) {
                                Box(Modifier.weight(0.42f).fillMaxHeight()) {
                                    // 右栏就是完整的流水，左栏不再重复「最近交易」
                                    OverviewTab(
                                        cardInfo, monthlyStats, emptyList(), mealTimeStats,
                                        rangeDates.first, rangeDates.second, topInset,
                                    )
                                }
                                Box(Modifier.weight(0.58f).fillMaxHeight()) {
                                    AppTabPager(
                                        pageCount = 2,
                                        selectedTabIndex = wideTab,
                                        onTabSelected = { wideTab = it },
                                        modifier = Modifier.fillMaxSize(),
                                    ) { tab ->
                                        when (tab) {
                                            0 -> TransactionTab(
                                                transactions, totalRecords, isLoadingMore, searchQuery,
                                                onSearchChange = { searchQuery = it }, onLoadMore = ::loadMore,
                                                topContentPadding = topContentPadding, rangeLabel = rangeLabel,
                                            )
                                            else -> AnalyticsTab(
                                                monthlyStats, categorySpending, mealTimeStats, weekdayWeekend,
                                                activeCampusDays, rangeDates.first, rangeDates.second,
                                                topContentPadding = topContentPadding, rangeLabel = rangeLabel,
                                            )
                                        }
                                    }
                                }
                            }
                        } else AppTabPager(
                            pageCount = 3,
                            selectedTabIndex = selectedTab,
                            onTabSelected = { selectedTab = it },
                            modifier = Modifier.fillMaxSize().layerBackdrop(cardBackdrop).followTopBar({ stableTop.value }, padding),
                        ) { tab ->
                            when (tab) {
                                0 -> OverviewTab(
                                    cardInfo, monthlyStats, transactions.take(5), mealTimeStats,
                                    rangeDates.first, rangeDates.second, topContentPadding,
                                )
                                1 -> TransactionTab(
                                    transactions, totalRecords, isLoadingMore, searchQuery,
                                    onSearchChange = { searchQuery = it }, onLoadMore = ::loadMore,
                                    topContentPadding = topContentPadding, rangeLabel = rangeLabel,
                                )
                                2 -> AnalyticsTab(
                                    monthlyStats, categorySpending, mealTimeStats, weekdayWeekend,
                                    activeCampusDays, rangeDates.first, rangeDates.second,
                                    topContentPadding = topContentPadding, rangeLabel = rangeLabel,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==================== 概览 Tab ====================

@Composable
private fun OverviewTab(
    cardInfo: CardInfo?,
    monthlyStats: List<MonthlyStats>,
    recentTransactions: List<Transaction>,
    mealTimeStats: Map<String, MealTimeStats>,
    rangeStart: LocalDate,
    rangeEnd: LocalDate,
    // 顶栏 + 标签行 + 时间选择器的高度，给列表让出来（plan2 §16.2）。
    topContentPadding: Dp = 0.dp,
) {
    LazyColumn(
        // 左右留白放在 contentPadding 里而不是列表外面：余额卡有投影，
        // 列表外面留白的话，列表的边界就在卡片边上，投影左右两侧会被切掉。
        modifier = Modifier.fillMaxSize().overScrollVertical(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = topContentPadding + 12.dp, bottom = 12.dp)
    ) {
        // 整页依次登场：余额 → 区间消费 → 卡状态 → 三餐 → 最近交易，每块错开一拍
        item { Box(Modifier.enterOnce(0)) { cardInfo?.let { BalanceCard(it) } } }
        item {
            Box(Modifier.enterOnce(1)) {
                RangeSpendCard(CampusCardAnalysis.summarizeRange(monthlyStats, rangeStart, rangeEnd))
            }
        }
        item { Box(Modifier.enterOnce(2)) { cardInfo?.let { CardStatusPanel(it) } } }
        item { Box(Modifier.enterOnce(3)) { MealQuickView(mealTimeStats) } }
        if (recentTransactions.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().enterOnce(4),
                    colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant)
                ) {
                    Column {
                        Text(
                            "最近交易",
                            style = MiuixTheme.textStyles.body1,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
                        )
                        recentTransactions.forEach { tx ->
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                            TransactionItem(tx)
                        }
                    }
                }
            }
        }
    }
}

// 余额卡的 Mesh 顶点颜色：3x3 网格，中间那个顶点缓慢漂移，四角、四边中点固定在卡片边框上，
// 保证渐变始终铺满整张卡。
// 余额卡原来是一整块深蓝，在一片白卡里很突兀。改成浅色：白底上一层很淡的蓝色流光，
// 和页面上其他卡片是一个体系，余额数字用主题色做重点。深色模式同理：贴近卡片底色，只带一点蓝。
private val BalanceCardMeshLight = listOf(
    listOf(Color(0xFFEAF2FF), Color(0xFFF7FAFF), Color(0xFFE2EDFF)),
    listOf(Color(0xFFF4F8FF), Color(0xFFD3E5FF), Color(0xFFF1F6FF)),
    listOf(Color(0xFFE4EEFF), Color(0xFFF8FBFF), Color(0xFFDCEAFF)),
)
private val BalanceCardMeshDark = listOf(
    listOf(Color(0xFF1B2433), Color(0xFF20242C), Color(0xFF1A2536)),
    listOf(Color(0xFF1F242D), Color(0xFF1E3350), Color(0xFF20252E)),
    listOf(Color(0xFF1A2434), Color(0xFF21252D), Color(0xFF1B2839)),
)

@Composable
private fun BalanceCard(info: CardInfo) {
    // 浅色卡：余额和图标用主题色，其余文字用常规的正文 / 次要文字颜色
    val accent = MiuixTheme.colorScheme.primary
    val secondary = MiuixTheme.colorScheme.onSurfaceVariantSummary
    top.yukonga.miuix.kmp.basic.Card(
        // 这一页的主角，托一层带主题色的柔影（和首页 Hero 卡同一档）
        modifier = Modifier
            .fillMaxWidth()
            .then(Modifier.appCardShadow(shape = RoundedCornerShape(24.dp), strong = true)),
        cornerRadius = 24.dp,
        colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = Color.Transparent)
    ) {
        Box(Modifier.fillMaxWidth()) {
            MeshBackground(
                modifier = Modifier.matchParentSize(),
                lightVertexColors = BalanceCardMeshLight,
                darkVertexColors = BalanceCardMeshDark,
                // 这张卡在玻璃顶栏的取样范围里：一直流动的话，静止时顶栏也在持续重新模糊
                runForMillis = 6_000L,
            )
            Column(Modifier.fillMaxWidth().padding(24.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("校园卡余额", style = MiuixTheme.textStyles.body2,
                            color = secondary)
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("¥", style = MiuixTheme.textStyles.title4,
                                fontWeight = FontWeight.Medium,
                                color = accent,
                                modifier = Modifier.padding(bottom = 4.dp))
                            Spacer(Modifier.width(2.dp))
                            // 进页面时从 0 滚到余额，刷新后从旧值滚到新值
                            com.xjtu.toolbox.ui.components.RollingNumberText(
                                value = info.balance,
                                format = { "%.2f".format(it) },
                                style = MiuixTheme.textStyles.title1,
                                fontWeight = FontWeight.Bold,
                                color = accent,
                            )
                        }
                    }
                    Surface(shape = CircleShape,
                        color = accent.copy(alpha = 0.12f),
                        modifier = Modifier.size(56.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.CreditCard, null,
                                tint = accent,
                                modifier = Modifier.size(28.dp))
                        }
                    }
                }
                if (info.pendingAmount > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text("待入账: ¥%.2f".format(info.pendingAmount),
                        style = MiuixTheme.textStyles.footnote1,
                        color = secondary)
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoPill(info.name, accent)
                    InfoPill(info.cardType, accent)
                    if (info.account.isNotBlank()) {
                        InfoPill("一卡通号: ${info.account}", accent)
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoPill(text: String, color: Color) {
    if (text.isBlank()) return
    Surface(shape = RoundedCornerShape(8.dp), color = color.copy(alpha = 0.10f)) {
        Text(text, Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            style = MiuixTheme.textStyles.footnote1, color = color.copy(alpha = 0.95f),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RangeSpendCard(summary: RangeSpendSummary) {
    top.yukonga.miuix.kmp.basic.Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
        Column(Modifier.padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(summary.title, style = MiuixTheme.textStyles.subtitle,
                        fontWeight = FontWeight.Medium)
                    summary.subtitle?.let {
                        Spacer(Modifier.height(2.dp))
                        Text(it, style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
                if (summary.changePercent != null && summary.changeCaption != null) {
                    val change = summary.changePercent
                    val isUp = change > 0
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isUp) MiuixTheme.colorScheme.errorContainer
                        else MiuixTheme.colorScheme.secondaryContainer
                    ) {
                        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (isUp) Icons.AutoMirrored.Filled.TrendingUp
                                else Icons.AutoMirrored.Filled.TrendingDown,
                                null, modifier = Modifier.size(14.dp),
                                tint = if (isUp) MiuixTheme.colorScheme.onErrorContainer
                                else MiuixTheme.colorScheme.onSecondaryContainer)
                            Spacer(Modifier.width(2.dp))
                            Text(
                                "${summary.changeCaption} %.0f%%".format(abs(change)),
                                style = MiuixTheme.textStyles.footnote1,
                                fontWeight = FontWeight.Bold,
                                color = if (isUp) MiuixTheme.colorScheme.onErrorContainer
                                else MiuixTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatColumn("总支出", "¥%.2f".format(summary.totalSpend),
                    MiuixTheme.colorScheme.error)
                StatColumn("总收入", "¥%.2f".format(summary.totalIncome),
                    MiuixTheme.colorScheme.primary)
                StatColumn("笔数", "${summary.transactionCount}",
                    MiuixTheme.colorScheme.primaryVariant)
                StatColumn("日均", "¥%.1f".format(summary.avgDailySpend),
                    MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            if (summary.peakDay.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LocalFireDepartment, null,
                        tint = MiuixTheme.colorScheme.error.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "消费最多: ${CampusCardAnalysis.formatPeakDay(summary.peakDay, spanYears = true)} ¥%.0f".format(summary.peakDayAmount),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
            if (summary.topMerchants.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Text("消费去向", style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Spacer(Modifier.height(8.dp))
                summary.topMerchants.forEach { merchant ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(merchant.name, style = MiuixTheme.textStyles.footnote1,
                            modifier = Modifier.weight(1f), maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        Text("${merchant.count}笔", style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.6f))
                        Spacer(Modifier.width(8.dp))
                        Text("¥%.2f".format(merchant.totalAmount),
                            style = MiuixTheme.textStyles.footnote1,
                            fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun MealQuickView(mealStats: Map<String, MealTimeStats>) {
    top.yukonga.miuix.kmp.basic.Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Restaurant, null,
                    tint = MiuixTheme.colorScheme.primaryVariant, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("用餐概览", style = MiuixTheme.textStyles.subtitle,
                    fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                val mealIcons = mapOf(
                    "早餐" to Icons.Default.WbSunny, "午餐" to Icons.Default.LightMode,
                    "晚餐" to Icons.Default.DarkMode, "夜宵" to Icons.Default.Bedtime)
                listOf("早餐", "午餐", "晚餐", "夜宵").forEach { period ->
                    val stat = mealStats[period]
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(mealIcons[period] ?: Icons.Default.Restaurant, null,
                            modifier = Modifier.size(20.dp),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        Text(period, style = MiuixTheme.textStyles.footnote1)
                        if (stat != null) {
                            Text("¥%.1f".format(stat.avgAmount),
                                style = MiuixTheme.textStyles.footnote1,
                                fontWeight = FontWeight.Bold,
                                color = MiuixTheme.colorScheme.primary)
                            Text("${stat.count}次",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.6f))
                        } else {
                            Text("—", style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.3f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatColumn(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MiuixTheme.textStyles.subtitle,
            fontWeight = FontWeight.Bold, color = color, maxLines = 1)
        Text(label, style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
private fun CardStatusPanel(info: CardInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 20.dp,
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                "卡片状态",
                style = MiuixTheme.textStyles.subtitle,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(
                    icon = if (info.lostFlag) Icons.Default.Warning else Icons.Default.CheckCircle,
                    text = if (info.lostFlag) "已挂失" else "使用正常",
                    isWarning = info.lostFlag, modifier = Modifier.weight(1f))
                StatusChip(
                    icon = if (info.frozenFlag) Icons.Default.Lock else Icons.Default.LockOpen,
                    text = if (info.frozenFlag) "已冻结" else "账户可用",
                    isWarning = info.frozenFlag, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (info.lostFlag || info.frozenFlag) "部分校园卡功能可能暂不可用"
                else "消费、充值与付款码均可正常使用",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
    }
}

@Composable
private fun StatusChip(icon: ImageVector, text: String, isWarning: Boolean, modifier: Modifier = Modifier) {
    // "正常"用主题 success 语义色（如果有），否则降级到 MiuixTheme.colorScheme.primary
    val okColor = MiuixTheme.colorScheme.primary  // primary 在两个主题下都有合理对比度
    val accent = if (isWarning) MiuixTheme.colorScheme.error else okColor
    Surface(
        modifier = modifier, shape = RoundedCornerShape(12.dp),
        color = accent.copy(alpha = 0.10f)
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center) {
            Icon(icon, null, modifier = Modifier.size(15.dp), tint = accent)
            Spacer(Modifier.width(5.dp))
            Text(text, style = MiuixTheme.textStyles.footnote1,
                fontWeight = FontWeight.Medium, color = accent,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ==================== 流水 Tab ====================

@Composable
private fun TransactionTab(
    transactions: List<Transaction>,
    total: Int,
    isLoadingMore: Boolean,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onLoadMore: () -> Unit,
    topContentPadding: Dp = 0.dp,
    /** 当前时间范围（如「近1个月」）。时间范围在顶栏菜单里，这里用小字交代一句。 */
    rangeLabel: String = "",
) {
    val filtered = remember(transactions, searchQuery) {
        if (searchQuery.isBlank()) transactions
        else transactions.filter { tx ->
            tx.merchant.contains(searchQuery, ignoreCase = true) ||
                tx.type.contains(searchQuery, ignoreCase = true) ||
                tx.description.contains(searchQuery, ignoreCase = true)
        }
    }
    val grouped = remember(filtered) {
        filtered.groupBy { it.time.substringBefore(" ") }
            .toSortedMap(compareByDescending { it })
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().overScrollVertical().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(top = topContentPadding + 12.dp, bottom = 12.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    com.xjtu.toolbox.ui.components.AppSearchBar(
                        query = searchQuery,
                        onQueryChange = onSearchChange,
                        label = "搜索商户或交易类型",
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            if (searchQuery.isNotBlank()) "搜索结果: ${filtered.size} 笔"
                            else if (rangeLabel.isNotEmpty()) "$rangeLabel · 共 $total 笔"
                            else "共 $total 笔交易",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        val totalSpend = filtered.filter { it.amount < 0 }.sumOf { -it.amount }
                        val totalIncome = filtered.filter { it.amount > 0 }.sumOf { it.amount }
                        Text("支出¥%.0f | 收入¥%.0f".format(totalSpend, totalIncome),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
        }
        grouped.entries.forEachIndexed { dayIndex, (date, txList) ->
            item(key = "day_$date") {
                Card(
                    modifier = Modifier.fillMaxWidth().enterOnce(dayIndex + 1),
                    colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant)
                ) {
                    Column {
                        val dayTotal = -txList.filter { it.amount < 0 }.sumOf { it.amount }
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(formatDateHeader(date),
                                style = MiuixTheme.textStyles.body2,
                                fontWeight = FontWeight.Medium,
                                color = MiuixTheme.colorScheme.primary)
                            if (dayTotal > 0) {
                                Text("−¥%.2f".format(dayTotal),
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.error.copy(alpha = 0.7f))
                            }
                        }
                        txList.forEach { tx ->
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                            TransactionItem(tx)
                        }
                    }
                }
            }
        }
        if (searchQuery.isBlank() && transactions.size < total) {
            item {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    if (isLoadingMore) CircularProgressIndicator(size = 24.dp)
                    else TextButton(text = "加载更多", onClick = onLoadMore)
                }
            }
        }
        if (filtered.isEmpty() && searchQuery.isNotBlank()) {
            item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.SearchOff, null, modifier = Modifier.size(48.dp),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.3f))
                        Spacer(Modifier.height(8.dp))
                        Text("未找到匹配的交易", style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
        }
    }
}

@Composable
private fun TransactionItem(tx: Transaction) {
    val isExpense = tx.amount < 0
    val icon = getTransactionIcon(tx)
    val iconBg = if (isExpense) MiuixTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
    else MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)

    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = iconBg, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, null, modifier = Modifier.size(20.dp),
                        tint = if (isExpense) MiuixTheme.colorScheme.error
                        else MiuixTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(tx.merchant.ifBlank { tx.type.ifBlank { tx.description.ifBlank { "未知交易" } } },
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(tx.time.substringAfter(" ").substringBeforeLast(":"),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text((if (isExpense) "-" else "+") + "¥%.2f".format(abs(tx.amount)),
                    style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Bold,
                    color = if (isExpense) MiuixTheme.colorScheme.error
                    else MiuixTheme.colorScheme.primary)
                Text("余额 ¥%.2f".format(tx.balance),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.6f))
            }
        }
}

// ==================== 分析 Tab ====================

@Composable
private fun AnalyticsTab(
    monthlyStats: List<MonthlyStats>,
    categorySpending: Map<String, Double>,
    mealTimeStats: Map<String, MealTimeStats>,
    weekdayWeekend: Pair<DayTypeStats, DayTypeStats>?,
    activeCampusDays: Int,
    rangeStart: LocalDate,
    rangeEnd: LocalDate,
    topContentPadding: Dp = 0.dp,
    /** 当前时间范围（如「近1个月」），见 [TransactionTab] 同名参数。 */
    rangeLabel: String = "",
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().overScrollVertical().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = topContentPadding + 12.dp, bottom = 12.dp)
    ) {
        if (rangeLabel.isNotEmpty()) {
            item {
                Text(
                    "统计范围：$rangeLabel",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
        if (categorySpending.isEmpty() && monthlyStats.isEmpty() && mealTimeStats.isEmpty()) {
            item {
                EmptyState(
                    title = "暂无消费分析数据",
                    subtitle = "所选时间段内暂无消费记录",
                    modifier = Modifier.fillParentMaxSize()
                )
            }
        } else {
            // 各统计卡依次登场，卡里的条形图随后从左边长出来
            if (categorySpending.isNotEmpty()) { item { Box(Modifier.enterOnce(0)) { CategoryCard(categorySpending) } } }
            if (monthlyStats.isNotEmpty()) { item { Box(Modifier.enterOnce(1)) { MonthlyTrendCard(monthlyStats, rangeStart, rangeEnd) } } }
            if (mealTimeStats.isNotEmpty()) { item { Box(Modifier.enterOnce(2)) { MealAnalysisCard(mealTimeStats) } } }
            if (weekdayWeekend != null) { item { Box(Modifier.enterOnce(3)) { WeekdayWeekendCard(weekdayWeekend) } } }
            if (monthlyStats.isNotEmpty()) { item { Box(Modifier.enterOnce(4)) { TopMerchantsCard(monthlyStats) } } }
            item { Box(Modifier.enterOnce(5)) { SpendingInsightsCard(monthlyStats, categorySpending, mealTimeStats, weekdayWeekend, activeCampusDays, rangeStart, rangeEnd) } }
        }
    }
}


@Composable
private fun CustomRangeDialog(
    show: Boolean,
    initialStart: LocalDate,
    initialEnd: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate, LocalDate) -> Unit,
) {
    var draftStart by remember(show, initialStart) { mutableStateOf(initialStart) }
    var draftEnd by remember(show, initialEnd) { mutableStateOf(initialEnd) }
    var picking by remember(show) { mutableStateOf<String?>(null) }
    val today = remember { LocalDate.now() }
    val earliest = remember { today.minusYears(6) }

    BackHandler(enabled = show && picking == null) { onDismiss() }
    OverlayDialog(
        show = show,
        title = "自定义时间段",
        summary = "流水和分析共用这一段。",
        onDismissRequest = onDismiss
    ) {
        ArrowPreference(
            title = "开始",
            summary = draftStart.format(CardDateFmt),
            onClick = { picking = "start" }
        )
        ArrowPreference(
            title = "结束",
            summary = draftEnd.format(CardDateFmt),
            onClick = { picking = "end" }
        )
        if (draftStart.isAfter(draftEnd)) {
            Text(
                "开始日晚于结束日时，确定后会自动对调。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
        Row(Modifier.fillMaxWidth()) {
            TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(20.dp))
            TextButton(
                text = "确定",
                onClick = {
                    if (draftStart.isAfter(draftEnd)) onConfirm(draftEnd, draftStart)
                    else onConfirm(draftStart, draftEnd)
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary()
            )
        }
    }
    AppDatePickerDialog(
        show = picking != null,
        title = if (picking == "end") "结束日期" else "开始日期",
        date = if (picking == "end") draftEnd else draftStart,
        minDate = earliest,
        maxDate = today,
        onDismiss = { picking = null },
        onConfirm = { picked ->
            if (picking == "end") draftEnd = picked else draftStart = picked
            picking = null
        }
    )
}

@Composable
private fun CategoryCard(categories: Map<String, Double>) {
    val total = categories.values.sum()
    val categoryIcons = mapOf(
        "餐饮" to Icons.Default.Restaurant, "超市" to Icons.Default.ShoppingCart,
        "洗浴" to Icons.Default.Shower, "水电" to Icons.Default.ElectricBolt,
        "学习" to Icons.Default.MenuBook, "洗衣" to Icons.Default.LocalLaundryService,
        "交通" to Icons.Default.DirectionsBus, "医疗" to Icons.Default.LocalHospital,
        "充值" to Icons.Default.AddCard, "其他" to Icons.Default.MoreHoriz)
    val categoryColors = listOf(
        Color(0xFFE57373), Color(0xFF81C784), Color(0xFF64B5F6),
        Color(0xFFFFB74D), Color(0xFF9575CD), Color(0xFF4DD0E1),
        Color(0xFFA1887F), Color(0xFFFF8A65), Color(0xFF90A4AE), Color(0xFFBDBDBD))

    top.yukonga.miuix.kmp.basic.Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PieChart, null,
                    tint = MiuixTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("消费类别", style = MiuixTheme.textStyles.subtitle,
                    fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text("总计 ¥%.0f".format(total), style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            Spacer(Modifier.height(16.dp))
            categories.entries.forEachIndexed { index, (category, amount) ->
                val percent = if (total > 0) amount / total else 0.0
                val color = categoryColors.getOrElse(index) { MiuixTheme.colorScheme.outline }
                val icon = categoryIcons[category] ?: Icons.Default.MoreHoriz
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, modifier = Modifier.size(20.dp), tint = color)
                    Spacer(Modifier.width(8.dp))
                    Text(category, style = MiuixTheme.textStyles.body2,
                        modifier = Modifier.width(48.dp))
                    com.xjtu.toolbox.ui.components.AnimatedBar(
                            progress = percent.toFloat().coerceIn(0f, 1f),
                            color = color,
                            modifier = Modifier.weight(1f),
                            trackColor = color.copy(alpha = 0.12f),
                            height = 8.dp,
                        )
                    Spacer(Modifier.width(8.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text("¥%.0f".format(amount), style = MiuixTheme.textStyles.footnote1,
                            fontWeight = FontWeight.Medium)
                        Text("%.0f%%".format(percent * 100), style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthlyTrendCard(stats: List<MonthlyStats>, rangeStart: LocalDate, rangeEnd: LocalDate) {
    val maxValue = stats.maxOfOrNull { maxOf(it.totalSpend, it.totalIncome) } ?: 1.0
    val spanYears = CampusCardAnalysis.spansYears(rangeStart, rangeEnd)
    top.yukonga.miuix.kmp.basic.Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
        Column(Modifier.padding(20.dp)) {
            Text("月度趋势", style = MiuixTheme.textStyles.subtitle,
                fontWeight = FontWeight.Medium)
            CampusCardAnalysis.periodSubtitle(rangeStart, rangeEnd)?.let { subtitle ->
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape)
                        .background(MiuixTheme.colorScheme.error))
                    Spacer(Modifier.width(4.dp))
                    Text("支出", style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape)
                        .background(MiuixTheme.colorScheme.primary))
                    Spacer(Modifier.width(4.dp))
                    Text("收入", style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
            Spacer(Modifier.height(12.dp))
            stats.sortedBy { it.month }.forEach { monthStat ->
                val monthLabel = CampusCardAnalysis.monthLabel(monthStat.month, spanYears)
                val spendBar = (monthStat.totalSpend / maxValue).toFloat().coerceIn(0f, 1f)
                val incomeBar = (monthStat.totalIncome / maxValue).toFloat().coerceIn(0f, 1f)
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text(monthLabel, style = MiuixTheme.textStyles.footnote1,
                        fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        com.xjtu.toolbox.ui.components.AnimatedBar(
                            progress = spendBar,
                            color = MiuixTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                            trackColor = MiuixTheme.colorScheme.error.copy(alpha = 0.1f),
                            height = 10.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("¥%.0f".format(monthStat.totalSpend),
                            style = MiuixTheme.textStyles.footnote1,
                            modifier = Modifier.width(60.dp), color = MiuixTheme.colorScheme.error)
                    }
                    if (monthStat.totalIncome > 0) {
                        Spacer(Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            com.xjtu.toolbox.ui.components.AnimatedBar(
                            progress = incomeBar,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                            trackColor = MiuixTheme.colorScheme.primary.copy(alpha = 0.1f),
                            height = 6.dp,
                        )
                            Spacer(Modifier.width(8.dp))
                            Text("¥%.0f".format(monthStat.totalIncome),
                                style = MiuixTheme.textStyles.footnote1,
                                modifier = Modifier.width(60.dp), color = MiuixTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MealAnalysisCard(mealStats: Map<String, MealTimeStats>) {
    top.yukonga.miuix.kmp.basic.Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Restaurant, null,
                    tint = MiuixTheme.colorScheme.primaryVariant, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("用餐分析", style = MiuixTheme.textStyles.subtitle,
                    fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(16.dp))
            val maxAvg = mealStats.values.maxOfOrNull { it.avgAmount } ?: 1.0
            val mealIcons = mapOf(
                "早餐" to Icons.Default.WbSunny, "午餐" to Icons.Default.LightMode,
                "晚餐" to Icons.Default.DarkMode, "夜宵" to Icons.Default.Bedtime)
            val mealColors = mapOf(
                "早餐" to Color(0xFFFFB74D), "午餐" to Color(0xFFFF8A65),
                "晚餐" to Color(0xFF7986CB), "夜宵" to Color(0xFF5C6BC0))
            listOf("早餐", "午餐", "晚餐", "夜宵").forEach { period ->
                val stat = mealStats[period] ?: return@forEach
                val barPercent = (stat.avgAmount / maxAvg).toFloat().coerceIn(0f, 1f)
                val color = mealColors[period] ?: MiuixTheme.colorScheme.primary
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(mealIcons[period] ?: Icons.Default.Restaurant, null,
                        modifier = Modifier.size(20.dp), tint = color)
                    Spacer(Modifier.width(8.dp))
                    Text(period, style = MiuixTheme.textStyles.body2,
                        modifier = Modifier.width(36.dp))
                    com.xjtu.toolbox.ui.components.AnimatedBar(
                            progress = barPercent,
                            color = color,
                            modifier = Modifier.weight(1f),
                            trackColor = color.copy(alpha = 0.12f),
                            height = 8.dp,
                        )
                    Spacer(Modifier.width(8.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text("均¥%.1f".format(stat.avgAmount),
                            style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Bold)
                        Text("${stat.count}次 共¥%.0f".format(stat.totalAmount),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekdayWeekendCard(stats: Pair<DayTypeStats, DayTypeStats>) {
    val (weekday, weekend) = stats
    top.yukonga.miuix.kmp.basic.Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CalendarMonth, null,
                    tint = MiuixTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("工作日 vs 周末", style = MiuixTheme.textStyles.subtitle,
                    fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DayTypeColumn("工作日", Icons.Default.Work, weekday,
                    MiuixTheme.colorScheme.primary, Modifier.weight(1f))
                DayTypeColumn("周末", Icons.Default.Weekend, weekend,
                    MiuixTheme.colorScheme.primaryVariant, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DayTypeColumn(
    label: String, icon: ImageVector, stats: DayTypeStats,
    color: Color, modifier: Modifier = Modifier
) {
    Surface(modifier = modifier, shape = RoundedCornerShape(16.dp), color = color.copy(alpha = 0.15f)) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = color, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(8.dp))
            Text(label, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Text("¥%.0f".format(stats.totalAmount),
                style = MiuixTheme.textStyles.title4, fontWeight = FontWeight.Bold, color = color)
            Spacer(Modifier.height(4.dp))
            Text("${stats.count}笔 | 均¥%.1f".format(stats.avgPerTransaction),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun TopMerchantsCard(monthlyStats: List<MonthlyStats>) {
    val allMerchants = monthlyStats.flatMap { it.topMerchants }
        .groupBy { it.name }
        .map { (name, stats) -> MerchantStat(name, stats.sumOf { it.totalAmount }, stats.sumOf { it.count }) }
        .sortedByDescending { it.totalAmount }
        .take(10)
    if (allMerchants.isEmpty()) return
    val maxAmount = allMerchants.maxOfOrNull { it.totalAmount } ?: 1.0

    top.yukonga.miuix.kmp.basic.Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Leaderboard, null,
                    tint = MiuixTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("消费排行", style = MiuixTheme.textStyles.subtitle,
                    fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(16.dp))
            allMerchants.forEachIndexed { index, merchant ->
                val barPercent = (merchant.totalAmount / maxAmount).toFloat().coerceIn(0f, 1f)
                val rankColor = when (index) {
                    0 -> Color(0xFFFFD700); 1 -> Color(0xFFC0C0C0); 2 -> Color(0xFFCD7F32)
                    else -> MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f)
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = rankColor.copy(alpha = 0.2f),
                        modifier = Modifier.size(24.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("${index + 1}", style = MiuixTheme.textStyles.footnote1,
                                fontWeight = FontWeight.Bold, color = rankColor)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(merchant.name, style = MiuixTheme.textStyles.footnote1,
                        modifier = Modifier.width(80.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(4.dp))
                    com.xjtu.toolbox.ui.components.AnimatedBar(
                            progress = barPercent,
                            color = MiuixTheme.colorScheme.error.copy(alpha = 0.7f),
                            modifier = Modifier.weight(1f),
                            trackColor = MiuixTheme.colorScheme.error.copy(alpha = 0.15f),
                            height = 6.dp,
                        )
                    Spacer(Modifier.width(8.dp))
                    Text("¥%.0f".format(merchant.totalAmount),
                        style = MiuixTheme.textStyles.footnote1,
                        fontWeight = FontWeight.Bold, modifier = Modifier.width(50.dp))
                }
            }
        }
    }
}

@Composable
private fun SpendingInsightsCard(
    stats: List<MonthlyStats>,
    categories: Map<String, Double>,
    mealStats: Map<String, MealTimeStats>,
    weekdayWeekend: Pair<DayTypeStats, DayTypeStats>?,
    activeCampusDays: Int,
    rangeStart: LocalDate,
    rangeEnd: LocalDate,
) {
    val insights = remember(stats, categories, mealStats, weekdayWeekend, activeCampusDays, rangeStart, rangeEnd) {
        generateInsights(stats, categories, mealStats, weekdayWeekend, activeCampusDays, rangeStart, rangeEnd)
    }
    if (insights.isEmpty()) return

    top.yukonga.miuix.kmp.basic.Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lightbulb, null,
                    tint = MiuixTheme.colorScheme.primaryVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("消费小结", style = MiuixTheme.textStyles.subtitle,
                    fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(12.dp))
            insights.forEach { (icon, text) ->
                Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                    Icon(icon, null, modifier = Modifier.size(16.dp).padding(top = 2.dp),
                        tint = MiuixTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(text, style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        lineHeight = MiuixTheme.textStyles.footnote1.lineHeight)
                }
            }
        }
    }
}

// ==================== 辅助函数 ====================

private fun getTransactionIcon(tx: Transaction): ImageVector {
    val m = tx.merchant.lowercase()
    val d = tx.description.lowercase()
    return when {
        m.contains("浴室") || m.contains("澡堂") -> Icons.Default.Shower
        m.contains("能源") || d.contains("电费") || m.contains("电控") -> Icons.Default.ElectricBolt
        m.contains("超市") || m.contains("超级市场") || m.contains("商店") || m.contains("便利") || m.contains("卖场") -> Icons.Default.ShoppingCart
        m.contains("图书") || m.contains("打印") || m.contains("复印") -> Icons.Default.MenuBook
        m.contains("洗衣") || m.contains("洗涤") -> Icons.Default.LocalLaundryService
        m.contains("医院") || m.contains("药") -> Icons.Default.LocalHospital
        tx.type.contains("充值") || tx.type.contains("圈存") -> Icons.Default.AddCard
        tx.amount > 0 -> Icons.Default.AddCard
        else -> Icons.Default.Restaurant
    }
}

private fun formatDateHeader(dateStr: String): String {
    return try {
        val date = LocalDate.parse(dateStr)
        val today = LocalDate.now()
        when {
            date == today -> "今天"
            date == today.minusDays(1) -> "昨天"
            date == today.minusDays(2) -> "前天"
            date.year == today.year -> "${date.monthValue}月${date.dayOfMonth}日"
            else -> "${date.year}年${date.monthValue}月${date.dayOfMonth}日"
        }
    } catch (_: Exception) { dateStr }
}

private fun generateInsights(
    stats: List<MonthlyStats>,
    categories: Map<String, Double>,
    mealStats: Map<String, MealTimeStats>,
    weekdayWeekend: Pair<DayTypeStats, DayTypeStats>?,
    activeCampusDays: Int,
    rangeStart: LocalDate,
    rangeEnd: LocalDate,
): List<Pair<ImageVector, String>> {
    val insights = mutableListOf<Pair<ImageVector, String>>()
    val total = categories.values.sum()
    val spanYears = CampusCardAnalysis.spansYears(rangeStart, rangeEnd)

    // 1. 餐饮消费占比 + 每餐均价
    val foodSpend = categories["餐饮"] ?: 0.0
    if (total > 0 && foodSpend > 0) {
        val foodPercent = foodSpend / total * 100
        val totalMeals = mealStats.values.sumOf { it.count }
        val avgPerMeal = if (totalMeals > 0) foodSpend / totalMeals else 0.0
        insights.add(Icons.Default.Restaurant to
                "餐饮消费占总支出的 %.0f%%，平均每餐 ¥%.1f（共 %d 次用餐）".format(
                    foodPercent, avgPerMeal, totalMeals))
    }

    // 2. 用餐时段对比
    val lunchStats = mealStats["午餐"]
    val dinnerStats = mealStats["晚餐"]
    if (lunchStats != null && dinnerStats != null) {
        val moreExpensive = if (dinnerStats.avgAmount > lunchStats.avgAmount) "晚餐" else "午餐"
        val diff = abs(dinnerStats.avgAmount - lunchStats.avgAmount)
        if (diff > 1.0) {
            insights.add(Icons.Default.Compare to
                    "${moreExpensive}比${if (moreExpensive == "晚餐") "午餐" else "晚餐"}平均贵 ¥%.1f".format(diff))
        }
    }

    CampusCardAnalysis.monthChangeInsight(stats, rangeStart, rangeEnd)?.let { line ->
        val up = "增长" in line
        insights.add(
            (if (up) Icons.AutoMirrored.Filled.TrendingUp else Icons.AutoMirrored.Filled.TrendingDown) to line
        )
    }

    val spendingMonths = stats.filter { it.totalSpend > 0 }
    if (spendingMonths.size >= 3) {
        val high = spendingMonths.maxBy { it.totalSpend }
        val low = spendingMonths.minBy { it.totalSpend }
        if (high.month != low.month) {
            insights.add(
                Icons.Default.Leaderboard to
                    "所选区间内${CampusCardAnalysis.monthLabel(high.month, spanYears)}支出最多（¥%.0f），${CampusCardAnalysis.monthLabel(low.month, spanYears)}最少（¥%.0f）".format(
                        high.totalSpend, low.totalSpend
                    )
            )
        }
    }

    // 4. 工作日 vs 周末
    if (weekdayWeekend != null) {
        val (wd, we) = weekdayWeekend
        if (wd.count > 0 && we.count > 0) {
            val wdAvg = wd.avgPerTransaction
            val weAvg = we.avgPerTransaction
            val higher = if (weAvg > wdAvg) "周末" else "工作日"
            insights.add(Icons.Default.CalendarMonth to
                    "${higher}单笔消费更高（工作日均¥%.1f，周末均¥%.1f）".format(wdAvg, weAvg))
        }
    }

    // 5. 最常去的商户
    val allMerchants = stats.flatMap { it.topMerchants }
    val topMerchant = allMerchants.groupBy { it.name }
        .map { (name, list) -> name to list.sumOf { it.count } }
        .maxByOrNull { it.second }
    if (topMerchant != null && topMerchant.second > 3) {
        insights.add(Icons.Default.Favorite to
                "最常消费的商户是「${topMerchant.first}」，共 ${topMerchant.second} 次")
    }

    // 6. 消费峰值日
    val peakMonth = stats.maxByOrNull { it.peakDayAmount }
    if (peakMonth != null && peakMonth.peakDayAmount > 0) {
        insights.add(Icons.Default.LocalFireDepartment to
                "单日最高消费: ${CampusCardAnalysis.formatPeakDay(peakMonth.peakDay, spanYears)} 花了 ¥%.0f".format(peakMonth.peakDayAmount))
    }

    // 7. 早餐频率（用"在校天数"做分母，即至少有一顿正餐的自然日）
    val breakfast = mealStats["早餐"]
    val calendarDays = CampusCardAnalysis.calendarDays(rangeStart, rangeEnd)
    val denominator = if (activeCampusDays > 3) activeCampusDays else calendarDays
    if (breakfast != null && denominator > 3) {
        val breakfastRate = (breakfast.count.toDouble() / denominator * 100).coerceAtMost(100.0)
        insights.add(Icons.Default.WbSunny to
                if (breakfastRate < 50) "在校天数中仅 %.0f%% 有吃早餐，记得好好吃早餐哦~".format(breakfastRate)
                else "早餐习惯不错，在校天数中有 %.0f%% 吃了早餐".format(breakfastRate))
    }

    // 8. 水电提示
    val utilitySpend = categories["水电"] ?: 0.0
    if (utilitySpend > 0) {
        insights.add(Icons.Default.ElectricBolt to "水电费支出 ¥%.0f，记得关注余额".format(utilitySpend))
    }

    CampusCardAnalysis.dailyInsight(total, rangeStart, rangeEnd, stats.size)?.let { line ->
        insights.add(Icons.Default.Timeline to line)
    }

    return insights
}
