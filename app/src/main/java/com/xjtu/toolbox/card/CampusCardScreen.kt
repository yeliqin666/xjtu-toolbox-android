package com.xjtu.toolbox.card

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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.auth.CampusCardLogin
import com.xjtu.toolbox.ui.components.LoadingState
import com.xjtu.toolbox.ui.components.ErrorState
import com.xjtu.toolbox.ui.components.EmptyState
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CampusCardScreen(
    login: CampusCardLogin,
    onBack: () -> Unit
) {
    val api = remember { CampusCardApi(login) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var cardInfo by remember { mutableStateOf<CardInfo?>(null) }
    var transactions by remember { mutableStateOf<List<Transaction>>(emptyList()) }
    var monthlyStats by remember { mutableStateOf<List<MonthlyStats>>(emptyList()) }
    var categorySpending by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    var mealTimeStats by remember { mutableStateOf<Map<String, MealTimeStats>>(emptyMap()) }
    var weekdayWeekend by remember { mutableStateOf<Pair<DayTypeStats, DayTypeStats>?>(null) }
    var totalRecords by remember { mutableIntStateOf(0) }

    // 选项卡: 0=概览 1=流水 2=分析
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    // 时间范围
    var selectedTimeRange by rememberSaveable { mutableStateOf(TimeRange.ONE_MONTH) }
    // 流水加载
    var isLoadingMore by remember { mutableStateOf(false) }
    var currentPage by rememberSaveable { mutableIntStateOf(1) }
    // 搜索
    var searchQuery by rememberSaveable { mutableStateOf("") }

    fun loadData(range: TimeRange = selectedTimeRange) {
        isLoading = true
        errorMessage = null
        scope.launch {
            try {
                val startDate = LocalDate.now().minusMonths(range.months.toLong())
                val endDate = LocalDate.now()

                // 先获取卡信息（回填 cardAccount），再并行抓流水
                cardInfo = withContext(Dispatchers.IO) { api.getCardInfo() }
                val allTx = withContext(Dispatchers.IO) {
                    api.getAllTransactions(startDate, endDate, maxPages = 50)
                }
                transactions = allTx
                totalRecords = allTx.size
                currentPage = (allTx.size + 49) / 50

                // 并行计算统计
                withContext(Dispatchers.Default) {
                    val s1 = async { api.calculateMonthlyStats(allTx) }
                    val s2 = async { api.categorizeSpending(allTx) }
                    val s3 = async { api.analyzeMealTimes(allTx) }
                    val s4 = async { api.analyzeWeekdayVsWeekend(allTx) }
                    monthlyStats = s1.await()
                    categorySpending = s2.await()
                    mealTimeStats = s3.await()
                    weekdayWeekend = s4.await()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                errorMessage = "加载失败: ${e.message}"
            } finally {
                isLoading = false
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
                        mealTimeStats = api.analyzeMealTimes(transactions)
                        weekdayWeekend = api.analyzeWeekdayVsWeekend(transactions)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("校园卡") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
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
                Column(Modifier.fillMaxSize().padding(padding)) {
                    TabRow(selectedTabIndex = selectedTab) {
                        Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 },
                            text = { Text("概览") },
                            icon = { Icon(Icons.Default.Dashboard, null, Modifier.size(18.dp)) })
                        Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 },
                            text = { Text("流水") },
                            icon = { Icon(Icons.Default.Receipt, null, Modifier.size(18.dp)) })
                        Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 },
                            text = { Text("分析") },
                            icon = { Icon(Icons.Default.Analytics, null, Modifier.size(18.dp)) })
                    }
                    when (selectedTab) {
                        0 -> OverviewTab(cardInfo, monthlyStats, transactions.take(5), mealTimeStats)
                        1 -> TransactionTab(transactions, totalRecords, isLoadingMore, searchQuery,
                            onSearchChange = { searchQuery = it }, onLoadMore = ::loadMore,
                            selectedTimeRange = selectedTimeRange,
                            onTimeRangeChange = { selectedTimeRange = it; loadData(it) })
                        2 -> AnalyticsTab(
                            monthlyStats, categorySpending, mealTimeStats, weekdayWeekend,
                            selectedTimeRange,
                            onTimeRangeChange = { selectedTimeRange = it; loadData(it) }
                        )
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
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
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
                Text("最近交易", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
            }
            items(recentTransactions) { tx -> TransactionItem(tx) }
        }
    }
}

@Composable
private fun BalanceCard(info: CardInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(24.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("校园卡余额", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("¥", style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Light,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("%.2f".format(info.balance),
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                Surface(shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    modifier = Modifier.size(56.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.CreditCard, null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(28.dp))
                    }
                }
            }
            if (info.pendingAmount > 0) {
                Spacer(Modifier.height(8.dp))
                Text("待入账: ¥%.2f".format(info.pendingAmount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f))
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                InfoPill(info.name, MaterialTheme.colorScheme.onPrimaryContainer)
                InfoPill(info.cardType, MaterialTheme.colorScheme.onPrimaryContainer)
                if (info.account.isNotBlank()) {
                    InfoPill("一卡通号: ${info.account}", MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
    }
}

@Composable
private fun InfoPill(text: String, color: Color) {
    if (text.isBlank()) return
    Surface(shape = RoundedCornerShape(8.dp), color = color.copy(alpha = 0.1f)) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall, color = color.copy(alpha = 0.8f))
    }
}

@Composable
private fun ThisMonthCard(stats: MonthlyStats?, lastMonth: MonthlyStats?) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("本月消费", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium)
                if (stats != null && lastMonth != null && lastMonth.totalSpend > 0) {
                    val change = (stats.totalSpend - lastMonth.totalSpend) / lastMonth.totalSpend * 100
                    val isUp = change > 0
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isUp) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (isUp) Icons.AutoMirrored.Filled.TrendingUp
                                else Icons.AutoMirrored.Filled.TrendingDown,
                                null, modifier = Modifier.size(14.dp),
                                tint = if (isUp) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(2.dp))
                            Text("%.0f%%".format(abs(change)),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isUp) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatColumn("总支出", "¥%.2f".format(stats?.totalSpend ?: 0.0),
                    MaterialTheme.colorScheme.error)
                StatColumn("总收入", "¥%.2f".format(stats?.totalIncome ?: 0.0),
                    MaterialTheme.colorScheme.primary)
                StatColumn("笔数", "${stats?.transactionCount ?: 0}",
                    MaterialTheme.colorScheme.tertiary)
                StatColumn("日均", "¥%.1f".format(stats?.avgDailySpend ?: 0.0),
                    MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (stats != null && stats.peakDay.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LocalFireDepartment, null,
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("消费最多: ${formatDateShort(stats.peakDay)} ¥%.0f".format(stats.peakDayAmount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (stats != null && stats.topMerchants.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Text("消费去向", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                stats.topMerchants.take(3).forEach { merchant ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(merchant.name, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f), maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        Text("${merchant.count}笔", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                        Spacer(Modifier.width(8.dp))
                        Text("¥%.2f".format(merchant.totalAmount),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun MealQuickView(mealStats: Map<String, MealTimeStats>) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Restaurant, null,
                    tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("用餐概览", style = MaterialTheme.typography.titleMedium,
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
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(period, style = MaterialTheme.typography.labelSmall)
                        if (stat != null) {
                            Text("¥%.1f".format(stat.avgAmount),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary)
                            Text("${stat.count}次",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                        } else {
                            Text("—", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
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
        Text(value, style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold, color = color, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    Surface(
        modifier = modifier, shape = RoundedCornerShape(12.dp),
        color = if (isWarning) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center) {
            Icon(icon, null, modifier = Modifier.size(14.dp),
                tint = if (isWarning) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
            Text(text, style = MaterialTheme.typography.labelSmall,
                color = if (isWarning) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
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
    onTimeRangeChange: (TimeRange) -> Unit
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
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        // 时间范围选择器
        item { TimeRangeSelector(selectedTimeRange, onTimeRangeChange) }

        // 搜索栏
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                placeholder = { Text("搜索商户/交易类型") },
                leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(20.dp)) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchChange("") }) {
                            Icon(Icons.Default.Clear, "清除", Modifier.size(20.dp))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    if (searchQuery.isNotBlank()) "搜索结果: ${filtered.size} 笔"
                    else "共 $total 笔交易",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                val totalSpend = filtered.filter { it.amount < 0 }.sumOf { -it.amount }
                val totalIncome = filtered.filter { it.amount > 0 }.sumOf { it.amount }
                Text("支出¥%.0f | 收入¥%.0f".format(totalSpend, totalIncome),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
        }
        grouped.forEach { (date, txList) ->
            item {
                val dayTotal = -txList.filter { it.amount < 0 }.sumOf { it.amount }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatDateHeader(date),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary)
                    if (dayTotal > 0) {
                        Text("−¥%.2f".format(dayTotal),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f))
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
                    if (isLoadingMore) CircularProgressIndicator(Modifier.size(24.dp))
                    else TextButton(onClick = onLoadMore) { Text("加载更多") }
                }
            }
        }
        if (filtered.isEmpty() && searchQuery.isNotBlank()) {
            item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.SearchOff, null, modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                        Spacer(Modifier.height(8.dp))
                        Text("未找到匹配的交易", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    val iconBg = if (isExpense) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
    else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)

    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = iconBg, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, null, modifier = Modifier.size(20.dp),
                        tint = if (isExpense) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(tx.merchant.ifBlank { tx.type.ifBlank { tx.description.ifBlank { "未知交易" } } },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(tx.time.substringAfter(" ").substringBeforeLast(":"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text((if (isExpense) "-" else "+") + "¥%.2f".format(abs(tx.amount)),
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                    color = if (isExpense) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary)
                Text("余额 ¥%.2f".format(tx.balance),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
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
    selectedTimeRange: TimeRange,
    onTimeRangeChange: (TimeRange) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        item { TimeRangeSelector(selectedTimeRange, onTimeRangeChange) }
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
            item { SpendingInsightsCard(monthlyStats, categorySpending, mealTimeStats, weekdayWeekend) }
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

    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PieChart, null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("消费类别", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text("总计 ¥%.0f".format(total), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(16.dp))
            categories.entries.forEachIndexed { index, (category, amount) ->
                val percent = if (total > 0) amount / total else 0.0
                val color = categoryColors.getOrElse(index) { Color.Gray }
                val icon = categoryIcons[category] ?: Icons.Default.MoreHoriz
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, modifier = Modifier.size(20.dp), tint = color)
                    Spacer(Modifier.width(8.dp))
                    Text(category, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.width(48.dp))
                    LinearProgressIndicator(
                        progress = { percent.toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)),
                        color = color, trackColor = color.copy(alpha = 0.12f))
                    Spacer(Modifier.width(8.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text("¥%.0f".format(amount), style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium)
                        Text("%.0f%%".format(percent * 100), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthlyTrendCard(stats: List<MonthlyStats>) {
    val maxValue = stats.maxOfOrNull { maxOf(it.totalSpend, it.totalIncome) } ?: 1.0
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(20.dp)) {
            Text("月度趋势", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error))
                    Spacer(Modifier.width(4.dp))
                    Text("支出", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary))
                    Spacer(Modifier.width(4.dp))
                    Text("收入", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(12.dp))
            stats.sortedBy { it.month }.forEach { monthStat ->
                val monthLabel = "${monthStat.month.monthValue}月"
                val spendBar = (monthStat.totalSpend / maxValue).toFloat().coerceIn(0f, 1f)
                val incomeBar = (monthStat.totalIncome / maxValue).toFloat().coerceIn(0f, 1f)
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text(monthLabel, style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(
                            progress = { spendBar },
                            modifier = Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)),
                            color = MaterialTheme.colorScheme.error,
                            trackColor = MaterialTheme.colorScheme.error.copy(alpha = 0.1f))
                        Spacer(Modifier.width(8.dp))
                        Text("¥%.0f".format(monthStat.totalSpend),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.width(60.dp), color = MaterialTheme.colorScheme.error)
                    }
                    if (monthStat.totalIncome > 0) {
                        Spacer(Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LinearProgressIndicator(
                                progress = { incomeBar },
                                modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                            Spacer(Modifier.width(8.dp))
                            Text("¥%.0f".format(monthStat.totalIncome),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.width(60.dp), color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MealAnalysisCard(mealStats: Map<String, MealTimeStats>) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Restaurant, null,
                    tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("用餐分析", style = MaterialTheme.typography.titleMedium,
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
                val color = mealColors[period] ?: MaterialTheme.colorScheme.primary
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(mealIcons[period] ?: Icons.Default.Restaurant, null,
                        modifier = Modifier.size(20.dp), tint = color)
                    Spacer(Modifier.width(8.dp))
                    Text(period, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.width(36.dp))
                    LinearProgressIndicator(
                        progress = { barPercent },
                        modifier = Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)),
                        color = color, trackColor = color.copy(alpha = 0.12f))
                    Spacer(Modifier.width(8.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text("均¥%.1f".format(stat.avgAmount),
                            style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                        Text("${stat.count}次 共¥%.0f".format(stat.totalAmount),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekdayWeekendCard(stats: Pair<DayTypeStats, DayTypeStats>) {
    val (weekday, weekend) = stats
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CalendarMonth, null,
                    tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("工作日 vs 周末", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DayTypeColumn("工作日", Icons.Default.Work, weekday,
                    MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                DayTypeColumn("周末", Icons.Default.Weekend, weekend,
                    MaterialTheme.colorScheme.tertiary, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DayTypeColumn(
    label: String, icon: ImageVector, stats: DayTypeStats,
    color: Color, modifier: Modifier = Modifier
) {
    Surface(modifier = modifier, shape = RoundedCornerShape(16.dp), color = color.copy(alpha = 0.08f)) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = color, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(8.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Text("¥%.0f".format(stats.totalAmount),
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color)
            Spacer(Modifier.height(4.dp))
            Text("${stats.count}笔 | 均¥%.1f".format(stats.avgPerTransaction),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
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

    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Leaderboard, null,
                    tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("消费排行", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(16.dp))
            allMerchants.forEachIndexed { index, merchant ->
                val barPercent = (merchant.totalAmount / maxAmount).toFloat().coerceIn(0f, 1f)
                val rankColor = when (index) {
                    0 -> Color(0xFFFFD700); 1 -> Color(0xFFC0C0C0); 2 -> Color(0xFFCD7F32)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = rankColor.copy(alpha = 0.2f),
                        modifier = Modifier.size(24.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("${index + 1}", style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold, color = rankColor)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(merchant.name, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(80.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(4.dp))
                    LinearProgressIndicator(
                        progress = { barPercent },
                        modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                        trackColor = MaterialTheme.colorScheme.error.copy(alpha = 0.08f))
                    Spacer(Modifier.width(8.dp))
                    Text("¥%.0f".format(merchant.totalAmount),
                        style = MaterialTheme.typography.labelSmall,
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
    weekdayWeekend: Pair<DayTypeStats, DayTypeStats>?
) {
    val insights = remember(stats, categories, mealStats, weekdayWeekend) {
        generateInsights(stats, categories, mealStats, weekdayWeekend)
    }
    if (insights.isEmpty()) return

    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lightbulb, null,
                    tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("智能洞察", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(12.dp))
            insights.forEach { (icon, text) ->
                Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                    Icon(icon, null, modifier = Modifier.size(16.dp).padding(top = 2.dp),
                        tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(text, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = MaterialTheme.typography.bodySmall.lineHeight)
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
    weekdayWeekend: Pair<DayTypeStats, DayTypeStats>?
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

    // 7. 早餐频率（count 已改为天数而非交易笔数）
    val breakfast = mealStats["早餐"]
    val totalDays = stats.sumOf {
        if (it.month == YearMonth.now()) LocalDate.now().dayOfMonth
        else it.month.lengthOfMonth()
    }.coerceAtLeast(1)
    if (breakfast != null && totalDays > 3) {
        val breakfastRate = (breakfast.count.toDouble() / totalDays * 100).coerceAtMost(100.0)
        insights.add(Icons.Default.WbSunny to
                if (breakfastRate < 50) "早餐频率较低（%.0f%%），记得好好吃早餐哦~".format(breakfastRate)
                else "早餐习惯不错，%.0f%% 的天数有吃早餐".format(breakfastRate))
    }

    // 8. 水电提示
    val utilitySpend = categories["水电"] ?: 0.0
    if (utilitySpend > 0) {
        insights.add(Icons.Default.ElectricBolt to "水电费支出 ¥%.0f，记得关注余额".format(utilitySpend))
    }

    // 9. 日均消费
    if (total > 0 && totalDays > 7) {
        insights.add(Icons.Default.Timeline to
                "统计期间日均消费 ¥%.1f，月均 ¥%.0f".format(
                    total / totalDays, total / stats.size.coerceAtLeast(1)))
    }

    return insights
}
