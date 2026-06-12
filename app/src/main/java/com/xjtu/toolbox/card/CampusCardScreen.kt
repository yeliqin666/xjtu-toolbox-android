package com.xjtu.toolbox.card

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
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.utils.overScrollVertical

import androidx.compose.ui.input.nestedscroll.nestedScroll
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.auth.CampusCardLogin
import com.xjtu.toolbox.ui.components.LoadingState
import com.xjtu.toolbox.ui.components.ErrorState
import com.xjtu.toolbox.ui.components.EmptyState
import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.ui.platform.LocalContext
import com.xjtu.toolbox.ui.components.AppFilterChip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs

// ==================== 时间范围枚举 ====================

private enum class TimeRange(val label: String, val months: Int) {
    ONE_MONTH("1个月", 1),
    THREE_MONTHS("3个月", 3),
    SIX_MONTHS("半年", 6),
    ONE_YEAR("1年", 12);
}

@Composable
fun CampusCardScreen(
    login: CampusCardLogin,
    onBack: () -> Unit
) {
    val api = remember { CampusCardApi(login) }
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
    // 流水加载
    var isLoadingMore by remember { mutableStateOf(false) }
    var currentPage by rememberSaveable { mutableIntStateOf(1) }
    // 切换时间范围用：不阻塞整页，只在 Tab 顶部细进度条提示
    var isReloadingRange by remember { mutableStateOf(false) }
    // 搜索
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var isSearchActive by rememberSaveable { mutableStateOf(false) }

    fun loadData(range: TimeRange = selectedTimeRange, silent: Boolean = false) {
        if (silent) isReloadingRange = true else isLoading = true
        errorMessage = null
        scope.launch {
            try {
                val startDate = LocalDate.now().minusMonths(range.months.toLong())
                val endDate = LocalDate.now()

                // 先获取卡信息（回填 cardAccount），再并行抓流水
                cardInfo = withContext(Dispatchers.IO) { api.getCardInfo() }
                // 缓存余额 + 姓名 供首页智能卡片使用
                cardInfo?.let { info ->
                    context.getSharedPreferences("campus_card", 0).edit()
                        .putFloat("card_balance_cache", info.balance.toFloat())
                        .putString("card_name_cache", info.name)
                        .putLong("card_cache_time", System.currentTimeMillis())
                        .apply()
                }
                val allTx = withContext(Dispatchers.IO) {
                    api.getAllTransactions(startDate, endDate, maxPages = 50)
                }
                transactions = allTx
                totalRecords = allTx.size
                currentPage = (allTx.size + 49) / 50
                // 缓存最近 5 笔消费供首页智能卡片使用；同时缓存今日消费给校园卡小组件
                run {
                    val recentJson = com.google.gson.Gson().toJson(allTx.take(5))
                    val todayStr = LocalDate.now().toString()
                    val todaySpend = allTx
                        .filter { tx -> tx.time.startsWith(todayStr) && tx.amount < 0 }
                        .sumOf { tx -> -tx.amount }
                    // 今日三餐消费
                    val todayBreakfast = allTx.filter { tx ->
                        tx.time.startsWith(todayStr) && tx.amount < 0 &&
                            tx.time.substringAfter(" ").substringBefore(":").toIntOrNull()?.let { h -> h in 5..10 } == true
                    }.sumOf { tx -> -tx.amount }
                    val todayLunch = allTx.filter { tx ->
                        tx.time.startsWith(todayStr) && tx.amount < 0 &&
                            tx.time.substringAfter(" ").substringBefore(":").toIntOrNull()?.let { h -> h in 11..14 } == true
                    }.sumOf { tx -> -tx.amount }
                    val todayDinner = allTx.filter { tx ->
                        tx.time.startsWith(todayStr) && tx.amount < 0 &&
                            tx.time.substringAfter(" ").substringBefore(":").toIntOrNull()?.let { h -> h in 17..21 } == true
                    }.sumOf { tx -> -tx.amount }
                    context.getSharedPreferences("campus_card", 0).edit()
                        .putString("card_recent_tx_cache", recentJson)
                        .putFloat("card_today_spend_cache", todaySpend.toFloat())
                        .putFloat("card_today_breakfast_cache", todayBreakfast.toFloat())
                        .putFloat("card_today_lunch_cache", todayLunch.toFloat())
                        .putFloat("card_today_dinner_cache", todayDinner.toFloat())
                        .apply()
                    // 通知校园卡小组件刷新
                    com.xjtu.toolbox.widget.CampusCardWidgetUpdater.requestUpdate(context)
                }

                // 并行计算统计
                withContext(Dispatchers.Default) {
                    val s1 = async { api.calculateMonthlyStats(allTx) }
                    val s2 = async { api.categorizeSpending(allTx) }
                    val s3 = async { api.analyzeMealTimes(allTx) }
                    val s4 = async { api.analyzeWeekdayVsWeekend(allTx) }
                    monthlyStats = s1.await()
                    categorySpending = s2.await()
                    val (mealStats3, campusDays3) = s3.await()
                    mealTimeStats = mealStats3
                    activeCampusDays = campusDays3
                    weekdayWeekend = s4.await()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: AuthExpiredException) {
                // 会话失效 → 静默触发重新登录（nav 监听 pendingRetry）
                appLoginState.handleAuthExpired(LoginType.CAMPUS_CARD, Routes.CAMPUS_CARD, onBack)
            } catch (e: Exception) {
                errorMessage = "加载失败: ${e.message}"
            } finally {
                isLoading = false
                isReloadingRange = false
            }
        }
    }

    fun loadMore() {
        if (isLoadingMore) return
        isLoadingMore = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val (_, txList) = api.getTransactions(
                        startDate = LocalDate.now().minusMonths(selectedTimeRange.months.toLong()),
                        endDate = LocalDate.now(),
                        page = currentPage + 1,
                        pageSize = 50
                    )
                    if (txList.isNotEmpty()) {
                        transactions = transactions + txList
                        currentPage++
                        monthlyStats = api.calculateMonthlyStats(transactions)
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

    LaunchedEffect(Unit) { loadData() }

    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = "校园卡",
                largeTitle = "校园卡",
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    if (selectedTab == 1) {
                        IconButton(onClick = {
                            isSearchActive = !isSearchActive
                            if (!isSearchActive) searchQuery = ""
                        }) {
                            Icon(
                                Icons.Default.Search,
                                "搜索",
                                tint = if (isSearchActive) MiuixTheme.colorScheme.primary
                                else MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                    }
                    IconButton(onClick = { loadData() }) {
                        Icon(Icons.Default.Refresh, "刷新")
                    }
                }
            )
        }
    ) { padding ->
        when {
            isLoading -> LoadingState("正在加载校园卡数据...", Modifier.fillMaxSize().padding(padding))
            errorMessage != null -> ErrorState(errorMessage!!, { loadData() }, Modifier.fillMaxSize().padding(padding))
            else -> {
                Column(Modifier.fillMaxSize().padding(padding).nestedScroll(scrollBehavior.nestedScrollConnection)) {
                    TabRowWithContour(
                        tabs = listOf("概览", "流水", "分析"),
                        selectedTabIndex = selectedTab,
                        onTabSelected = { selectedTab = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    var isPullRefreshing by remember { mutableStateOf(false) }
                    LaunchedEffect(isLoading, isReloadingRange) {
                        if (!isLoading && !isReloadingRange) isPullRefreshing = false
                    }
                    top.yukonga.miuix.kmp.basic.PullToRefresh(
                        isRefreshing = isPullRefreshing,
                        onRefresh = {
                            isPullRefreshing = true
                            loadData(silent = true)
                        },
                        modifier = Modifier.fillMaxSize()
                    ) {
                    AnimatedContent(
                        targetState = selectedTab,
                        transitionSpec = {
                            val direction = if (targetState > initialState) 1 else -1
                            (slideInHorizontally { direction * it / 4 } + fadeIn(
                                spring(dampingRatio = 0.85f, stiffness = 500f)
                            )) togetherWith (slideOutHorizontally { -direction * it / 4 } + fadeOut(
                                spring(dampingRatio = 0.85f, stiffness = 500f)
                            ))
                        },
                        label = "campusCardTab"
                    ) { tab ->
                        when (tab) {
                            0 -> OverviewTab(cardInfo, monthlyStats, transactions.take(5), mealTimeStats)
                            1 -> TransactionTab(transactions, totalRecords, isLoadingMore, searchQuery,
                                onSearchChange = { searchQuery = it }, onLoadMore = ::loadMore,
                                selectedTimeRange = selectedTimeRange,
                                onTimeRangeChange = { selectedTimeRange = it; loadData(it, silent = true) },
                                isReloading = isReloadingRange,
                                isSearchActive = isSearchActive)
                            2 -> AnalyticsTab(
                                monthlyStats, categorySpending, mealTimeStats, weekdayWeekend,
                                activeCampusDays, selectedTimeRange,
                                onTimeRangeChange = { selectedTimeRange = it; loadData(it, silent = true) },
                                isReloading = isReloadingRange
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
    mealTimeStats: Map<String, MealTimeStats>
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().overScrollVertical().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        item { cardInfo?.let { BalanceCard(it) } }
        item {
            val thisMonth = monthlyStats.find { it.month == YearMonth.now() }
            val lastMonth = monthlyStats.find { it.month == YearMonth.now().minusMonths(1) }
            ThisMonthCard(thisMonth, lastMonth)
        }
        item { cardInfo?.let { CardStatusRow(it) } }
        if (mealTimeStats.isNotEmpty()) {
            item { MealQuickView(mealTimeStats) }
        }
        if (recentTransactions.isNotEmpty()) {
            item {
                Text("最近交易", style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
            }
            items(recentTransactions) { tx -> TransactionItem(tx) }
        }
    }
}

@Composable
private fun BalanceCard(info: CardInfo) {
    // 固定交大蓝品牌渐变（不随深浅色翻转），白字始终高对比，类似实体银行卡
    val brandStart = Color(0xFF0A4D94)
    val brandEnd = Color(0xFF1E78C8)
    val onBrand = Color.White
    top.yukonga.miuix.kmp.basic.Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 24.dp,
        colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = Color.Transparent)
    ) {
        Box(
            Modifier.fillMaxWidth().background(
                Brush.linearGradient(listOf(brandStart, brandEnd))
            )
        ) {
            Column(Modifier.fillMaxWidth().padding(24.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("校园卡余额", style = MiuixTheme.textStyles.body2,
                            color = onBrand.copy(alpha = 0.85f))
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("¥", style = MiuixTheme.textStyles.title4,
                                fontWeight = FontWeight.Medium,
                                color = onBrand.copy(alpha = 0.9f),
                                modifier = Modifier.padding(bottom = 4.dp))
                            Spacer(Modifier.width(2.dp))
                            Text("%.2f".format(info.balance),
                                style = MiuixTheme.textStyles.title1,
                                fontWeight = FontWeight.Bold,
                                color = onBrand)
                        }
                    }
                    Surface(shape = CircleShape,
                        color = onBrand.copy(alpha = 0.18f),
                        modifier = Modifier.size(56.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.CreditCard, null,
                                tint = onBrand,
                                modifier = Modifier.size(28.dp))
                        }
                    }
                }
                if (info.pendingAmount > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text("待入账: ¥%.2f".format(info.pendingAmount),
                        style = MiuixTheme.textStyles.footnote1,
                        color = onBrand.copy(alpha = 0.75f))
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoPill(info.name, onBrand)
                    InfoPill(info.cardType, onBrand)
                    if (info.account.isNotBlank()) {
                        InfoPill("一卡通号: ${info.account}", onBrand)
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoPill(text: String, color: Color) {
    if (text.isBlank()) return
    Surface(shape = RoundedCornerShape(8.dp), color = color.copy(alpha = 0.16f)) {
        Text(text, Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            style = MiuixTheme.textStyles.footnote1, color = color.copy(alpha = 0.95f),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ThisMonthCard(stats: MonthlyStats?, lastMonth: MonthlyStats?) {
    top.yukonga.miuix.kmp.basic.Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
        Column(Modifier.padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("本月消费", style = MiuixTheme.textStyles.subtitle,
                    fontWeight = FontWeight.Medium)
                if (stats != null && lastMonth != null && lastMonth.totalSpend > 0) {
                    val change = (stats.totalSpend - lastMonth.totalSpend) / lastMonth.totalSpend * 100
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
                            Text("%.0f%%".format(abs(change)),
                                style = MiuixTheme.textStyles.footnote1,
                                fontWeight = FontWeight.Bold,
                                color = if (isUp) MiuixTheme.colorScheme.onErrorContainer
                                else MiuixTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatColumn("总支出", "¥%.2f".format(stats?.totalSpend ?: 0.0),
                    MiuixTheme.colorScheme.error)
                StatColumn("总收入", "¥%.2f".format(stats?.totalIncome ?: 0.0),
                    MiuixTheme.colorScheme.primary)
                StatColumn("笔数", "${stats?.transactionCount ?: 0}",
                    MiuixTheme.colorScheme.primaryVariant)
                StatColumn("日均", "¥%.1f".format(stats?.avgDailySpend ?: 0.0),
                    MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            if (stats != null && stats.peakDay.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LocalFireDepartment, null,
                        tint = MiuixTheme.colorScheme.error.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("消费最多: ${formatDateShort(stats.peakDay)} ¥%.0f".format(stats.peakDayAmount),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
            if (stats != null && stats.topMerchants.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Text("消费去向", style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Spacer(Modifier.height(8.dp))
                stats.topMerchants.take(3).forEach { merchant ->
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
private fun CardStatusRow(info: CardInfo) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusChip(
            icon = if (info.lostFlag) Icons.Default.Warning else Icons.Default.CheckCircle,
            text = if (info.lostFlag) "已挂失" else "正常",
            isWarning = info.lostFlag, modifier = Modifier.weight(1f))
        StatusChip(
            icon = if (info.frozenFlag) Icons.Default.Lock else Icons.Default.LockOpen,
            text = if (info.frozenFlag) "已冻结" else "未冻结",
            isWarning = info.frozenFlag, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun StatusChip(icon: ImageVector, text: String, isWarning: Boolean, modifier: Modifier = Modifier) {
    val okColor = Color(0xFF2E9E5B)
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
    selectedTimeRange: TimeRange,
    onTimeRangeChange: (TimeRange) -> Unit,
    isReloading: Boolean = false,
    isSearchActive: Boolean = false
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
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        // 时间范围选择器
        item { TimeRangeSelector(selectedTimeRange, onTimeRangeChange) }
        if (isReloading) {
            item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), height = 2.dp) }
        }

        // 搜索栏（右上角按钮控制展开）
        item {
            androidx.compose.animation.AnimatedVisibility(visible = isSearchActive) {
                com.xjtu.toolbox.ui.components.AppSearchBar(
                    query = searchQuery,
                    onQueryChange = onSearchChange,
                    label = "搜索商户/交易类型",
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                )
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    if (searchQuery.isNotBlank()) "搜索结果: ${filtered.size} 笔"
                    else "共 $total 笔交易",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                val totalSpend = filtered.filter { it.amount < 0 }.sumOf { -it.amount }
                val totalIncome = filtered.filter { it.amount > 0 }.sumOf { it.amount }
                Text("支出¥%.0f | 收入¥%.0f".format(totalSpend, totalIncome),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            Spacer(Modifier.height(8.dp))
        }
        grouped.forEach { (date, txList) ->
            item {
                val dayTotal = -txList.filter { it.amount < 0 }.sumOf { it.amount }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
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
                Spacer(Modifier.height(4.dp))
            }
            items(txList, key = { "${it.time}_${it.merchant}_${it.amount}" }) { tx ->
                TransactionItem(tx)
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

    Surface(shape = RoundedCornerShape(12.dp), color = MiuixTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
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
}

// ==================== 分析 Tab ====================

@Composable
private fun AnalyticsTab(
    monthlyStats: List<MonthlyStats>,
    categorySpending: Map<String, Double>,
    mealTimeStats: Map<String, MealTimeStats>,
    weekdayWeekend: Pair<DayTypeStats, DayTypeStats>?,
    activeCampusDays: Int,
    selectedTimeRange: TimeRange,
    onTimeRangeChange: (TimeRange) -> Unit,
    isReloading: Boolean = false
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().overScrollVertical().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        item { TimeRangeSelector(selectedTimeRange, onTimeRangeChange) }
        if (isReloading) {
            item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), height = 2.dp) }
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
            if (categorySpending.isNotEmpty()) { item { CategoryCard(categorySpending) } }
            if (monthlyStats.isNotEmpty()) { item { MonthlyTrendCard(monthlyStats) } }
            if (mealTimeStats.isNotEmpty()) { item { MealAnalysisCard(mealTimeStats) } }
            if (weekdayWeekend != null) { item { WeekdayWeekendCard(weekdayWeekend) } }
            if (monthlyStats.isNotEmpty()) { item { TopMerchantsCard(monthlyStats) } }
            item { SpendingInsightsCard(monthlyStats, categorySpending, mealTimeStats, weekdayWeekend, activeCampusDays) }
        }
    }
}

@Composable
private fun TimeRangeSelector(selected: TimeRange, onChange: (TimeRange) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TimeRange.entries.forEach { range ->
            AppFilterChip(selected = range == selected, onClick = { onChange(range) },
                label = range.label, modifier = Modifier.weight(1f))
        }
    }
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
                    LinearProgressIndicator(
                        progress = percent.toFloat().coerceIn(0f, 1f),
                        modifier = Modifier.weight(1f),
                        height = 8.dp,
                        colors = ProgressIndicatorDefaults.progressIndicatorColors(
                            foregroundColor = color, backgroundColor = color.copy(alpha = 0.12f)
                        ))
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
private fun MonthlyTrendCard(stats: List<MonthlyStats>) {
    val maxValue = stats.maxOfOrNull { maxOf(it.totalSpend, it.totalIncome) } ?: 1.0
    top.yukonga.miuix.kmp.basic.Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
        Column(Modifier.padding(20.dp)) {
            Text("月度趋势", style = MiuixTheme.textStyles.subtitle,
                fontWeight = FontWeight.Medium)
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
                val monthLabel = "${monthStat.month.monthValue}月"
                val spendBar = (monthStat.totalSpend / maxValue).toFloat().coerceIn(0f, 1f)
                val incomeBar = (monthStat.totalIncome / maxValue).toFloat().coerceIn(0f, 1f)
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text(monthLabel, style = MiuixTheme.textStyles.footnote1,
                        fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(
                            progress = spendBar,
                            modifier = Modifier.weight(1f),
                            height = 10.dp,
                            colors = ProgressIndicatorDefaults.progressIndicatorColors(
                                foregroundColor = MiuixTheme.colorScheme.error,
                                backgroundColor = MiuixTheme.colorScheme.error.copy(alpha = 0.1f)
                            ))
                        Spacer(Modifier.width(8.dp))
                        Text("¥%.0f".format(monthStat.totalSpend),
                            style = MiuixTheme.textStyles.footnote1,
                            modifier = Modifier.width(60.dp), color = MiuixTheme.colorScheme.error)
                    }
                    if (monthStat.totalIncome > 0) {
                        Spacer(Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LinearProgressIndicator(
                                progress = incomeBar,
                                modifier = Modifier.weight(1f),
                                height = 6.dp,
                                colors = ProgressIndicatorDefaults.progressIndicatorColors(
                                    foregroundColor = MiuixTheme.colorScheme.primary,
                                    backgroundColor = MiuixTheme.colorScheme.primary.copy(alpha = 0.1f)
                                ))
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
                    LinearProgressIndicator(
                        progress = barPercent,
                        modifier = Modifier.weight(1f),
                        height = 8.dp,
                        colors = ProgressIndicatorDefaults.progressIndicatorColors(
                            foregroundColor = color, backgroundColor = color.copy(alpha = 0.12f)
                        ))
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
                    LinearProgressIndicator(
                        progress = barPercent,
                        modifier = Modifier.weight(1f),
                        height = 6.dp,
                        colors = ProgressIndicatorDefaults.progressIndicatorColors(
                            foregroundColor = MiuixTheme.colorScheme.error.copy(alpha = 0.7f),
                            backgroundColor = MiuixTheme.colorScheme.error.copy(alpha = 0.15f)
                        ))
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
    activeCampusDays: Int
) {
    val insights = remember(stats, categories, mealStats, weekdayWeekend, activeCampusDays) {
        generateInsights(stats, categories, mealStats, weekdayWeekend, activeCampusDays)
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
        m.contains("超市") || m.contains("商店") || m.contains("便利") -> Icons.Default.ShoppingCart
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

private fun formatDateShort(dateStr: String): String {
    return try {
        val date = LocalDate.parse(dateStr)
        "${date.monthValue}/${date.dayOfMonth}"
    } catch (_: Exception) { dateStr }
}

private fun generateInsights(
    stats: List<MonthlyStats>,
    categories: Map<String, Double>,
    mealStats: Map<String, MealTimeStats>,
    weekdayWeekend: Pair<DayTypeStats, DayTypeStats>?,
    activeCampusDays: Int
): List<Pair<ImageVector, String>> {
    val insights = mutableListOf<Pair<ImageVector, String>>()
    val total = categories.values.sum()

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

    // 3. 月度趋势
    if (stats.size >= 2) {
        val sorted = stats.sortedByDescending { it.month }
        val latest = sorted.first()
        val prev = sorted[1]
        if (prev.totalSpend > 0) {
            val change = (latest.totalSpend - prev.totalSpend) / prev.totalSpend * 100
            val direction = if (change > 0) "增长" else "减少"
            val icon = if (change > 0) Icons.AutoMirrored.Filled.TrendingUp
            else Icons.AutoMirrored.Filled.TrendingDown
            insights.add(icon to
                    "本月消费比上月${direction} %.0f%%（¥%.0f → ¥%.0f）".format(
                        abs(change), prev.totalSpend, latest.totalSpend))
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
                "单日最高消费: ${formatDateShort(peakMonth.peakDay)} 花了 ¥%.0f".format(peakMonth.peakDayAmount))
    }

    // 7. 早餐频率（用"在校天数"做分母，即至少有一顿正餐的自然日）
    val breakfast = mealStats["早餐"]
    // 如果没有在校天数（全部是零食或少于数据），则退化为日历天数
    val denominator = if (activeCampusDays > 3) activeCampusDays else stats.sumOf {
        if (it.month == YearMonth.now()) LocalDate.now().dayOfMonth
        else it.month.lengthOfMonth()
    }.coerceAtLeast(1)
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

    // 9. 日均消费
    if (total > 0 && denominator > 7) {
        insights.add(Icons.Default.Timeline to
                "统计期间日均消费 ¥%.1f，月均 ¥%.0f".format(
                    total / denominator, total / stats.size.coerceAtLeast(1)))
    }

    return insights
}