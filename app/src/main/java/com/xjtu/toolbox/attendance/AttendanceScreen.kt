package com.xjtu.toolbox.attendance

import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical

import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.xjtu.toolbox.LocalAppLoginState
import com.xjtu.toolbox.Routes
import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.auth.LoginType
import com.xjtu.toolbox.auth.handleAuthExpired
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.auth.AttendanceLogin
import com.xjtu.toolbox.ui.components.ErrorState
import com.xjtu.toolbox.ui.components.LoadingState
import com.xjtu.toolbox.ui.components.EmptyState
import com.xjtu.toolbox.ui.components.AppFilterChip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AttendanceScreen(
    login: AttendanceLogin,
    onBack: () -> Unit
) {
    val appLoginState = LocalAppLoginState.current
    val api = remember { AttendanceApi(login) }
    val scope = rememberCoroutineScope()

    var records by remember { mutableStateOf<List<AttendanceWaterRecord>>(emptyList()) }
    var courseStats by remember { mutableStateOf<List<CourseAttendanceStat>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var studentName by remember { mutableStateOf("") }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    // 学期选择
    var termList by remember { mutableStateOf<List<TermInfo>>(emptyList()) }
    var currentTermBh by remember { mutableStateOf("") }
    var selectedTermBh by rememberSaveable { mutableStateOf("") }
    var loadJob by remember { mutableStateOf<Job?>(null) }
    var loadGeneration by remember { mutableIntStateOf(0) }

    // 周次筛选（null = 全部）
    var selectedWeek by rememberSaveable { mutableStateOf<Int?>(null) }
    // 状态筛选
    var selectedStatus by rememberSaveable { mutableStateOf<WaterType?>(null) }
    // 课程搜索
    var searchQuery by rememberSaveable { mutableStateOf("") }

    fun loadData(termBh: String? = null) {
        loadJob?.cancel()
        val myGeneration = ++loadGeneration
        fun ensureLatest() {
            if (myGeneration != loadGeneration) {
                throw kotlinx.coroutines.CancellationException("superseded by newer attendance load")
            }
        }
        isLoading = true
        errorMessage = null
        loadJob = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // 学校端较脆弱，元数据按顺序取，避免进入页面时瞬间打出三路请求。
                    val info = api.getStudentInfo()
                    ensureLatest()
                    studentName = info["name"] as? String ?: ""

                    val fetchedTerms = try {
                        api.getTermList()
                    } catch (e: AuthExpiredException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList()
                    }
                    ensureLatest()
                    termList = fetchedTerms

                    if (currentTermBh.isEmpty()) {
                        try {
                            val fetchedCurrentTerm = api.getTermBh()
                            ensureLatest()
                            currentTermBh = fetchedCurrentTerm
                        }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: AuthExpiredException) { throw e }
                        catch (_: Exception) { currentTermBh = "" }
                    }

                    // 确定要查询的学期
                    val bh = termBh ?: currentTermBh
                    if (bh.isNotEmpty()) {
                        selectedTermBh = bh
                    }

                    // 加载考勤记录（历史学期需要日期范围）
                    val isCurrentTerm = termBh == null || bh == currentTermBh
                    val termInfo = termList.firstOrNull { it.bh == bh }
                    val fetchedRecords = if (!isCurrentTerm && termInfo != null && termInfo.startDate.isNotEmpty()) {
                        api.getWaterRecords(bh, startDate = termInfo.startDate, endDate = termInfo.endDate)
                    } else {
                        api.getWaterRecords(bh.ifEmpty { null })
                    }
                    ensureLatest()
                    records = fetchedRecords

                    // 课程统计：当前学期用 getKqtjCurrentWeek，历史学期用 getKqtjByTime + 回退
                    val fetchedStats = if (isCurrentTerm) {
                        try {
                            api.getKqtjCurrentWeek()
                        } catch (e: AuthExpiredException) {
                            throw e
                        } catch (_: Exception) {
                            api.computeCourseStatsFromRecords(fetchedRecords)
                        }
                    } else {
                        val statsFromApi = try {
                            if (termInfo != null && termInfo.startDate.isNotEmpty() && termInfo.endDate.isNotEmpty()) {
                                api.getKqtjByTime(termInfo.startDate, termInfo.endDate)
                            } else if (fetchedRecords.isNotEmpty()) {
                                val minDate = fetchedRecords.minOf { it.date }
                                val maxDate = fetchedRecords.maxOf { it.date }
                                api.getKqtjByTime(minDate, maxDate)
                            } else {
                                emptyList()
                            }
                        } catch (e: AuthExpiredException) {
                            throw e
                        } catch (_: Exception) { emptyList() }
                        // 3) API 为空则从 records 直接聚合
                        statsFromApi.ifEmpty { api.computeCourseStatsFromRecords(fetchedRecords) }
                    }
                    ensureLatest()
                    courseStats = fetchedStats
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: AuthExpiredException) {
                appLoginState.handleAuthExpired(LoginType.ATTENDANCE, Routes.ATTENDANCE, onBack)
            } catch (e: Exception) {
                errorMessage = "加载失败: ${e.message}"
            } finally {
                if (myGeneration == loadGeneration) {
                    isLoading = false
                }
            }
        }
    }

    fun switchTerm(bh: String) {
        if (bh == selectedTermBh) return
        selectedTermBh = bh
        selectedWeek = null
        selectedStatus = null
        loadData(bh)
    }

    LaunchedEffect(Unit) { loadData() }

    // 派生数据
    val maxWeek = remember(records) { records.maxOfOrNull { it.week } ?: 0 }

    val filteredRecords = remember(records, selectedWeek, selectedStatus, searchQuery) {
        records.asSequence()
            .let { seq -> if (selectedWeek != null) seq.filter { it.week == selectedWeek } else seq }
            .let { seq -> if (selectedStatus != null) seq.filter { it.status == selectedStatus } else seq }
            .let { seq ->
                if (searchQuery.isNotBlank()) seq.filter {
                    searchQuery.lowercase() in it.courseName.lowercase() ||
                            searchQuery.lowercase() in it.location.lowercase() ||
                            searchQuery.lowercase() in it.teacher.lowercase()
                } else seq
            }
            .sortedByDescending { it.date }
            .toList()
    }

    // 全局统计
    val displayRecords = if (selectedWeek != null) records.filter { it.week == selectedWeek } else records
    val totalNormal = displayRecords.count { it.status == WaterType.NORMAL }
    val totalLate = displayRecords.count { it.status == WaterType.LATE }
    val totalAbsence = displayRecords.count { it.status == WaterType.ABSENCE }
    val totalLeave = displayRecords.count { it.status == WaterType.LEAVE }
    val attendanceRate = if (displayRecords.isNotEmpty())
        (totalNormal + totalLeave) * 100 / displayRecords.size else 100
    val termItems = remember(termList) { termList.map { DropdownItem(text = it.name) } }
    val selectedTermIndex = termList.indexOfFirst { it.bh == selectedTermBh }.coerceAtLeast(0)
    val weekItems = remember(maxWeek) {
        listOf(DropdownItem(text = "全部周次")) +
                (1..maxWeek).map { DropdownItem(text = "第${it}周") }
    }
    val selectedWeekIndex = if (maxWeek > 0) selectedWeek?.coerceIn(1, maxWeek) ?: 0 else 0

    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    Scaffold(
        topBar = {
            TopAppBar(
                title = "考勤查询",
                color = MiuixTheme.colorScheme.surface,
                largeTitle = "考勤查询",
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { loadData(selectedTermBh.ifEmpty { null }) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                }
            )
        }
    ) { padding ->
        if (isLoading) {
            LoadingState(message = "加载考勤数据...", modifier = Modifier.fillMaxSize().padding(padding))
        } else if (errorMessage != null) {
            ErrorState(
                message = errorMessage!!,
                onRetry = { loadData() },
                modifier = Modifier.fillMaxSize().padding(padding)
            )
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MiuixTheme.colorScheme.surface)
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.defaultColors(
                        color = MiuixTheme.colorScheme.secondaryContainer
                    )
                ) {
                    if (termItems.isNotEmpty()) {
                        OverlaySpinnerPreference(
                            title = "学期",
                            summary = "选择要查询的学期",
                            items = termItems,
                            selectedIndex = selectedTermIndex,
                            onSelectedIndexChange = { index ->
                                termList.getOrNull(index)?.let { switchTerm(it.bh) }
                            }
                        )
                    }
                    if (maxWeek > 0) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        OverlaySpinnerPreference(
                            title = "周次",
                            summary = if (selectedWeek == null) {
                                "查看整个学期"
                            } else {
                                "${displayRecords.size} 条记录"
                            },
                            items = weekItems,
                            selectedIndex = selectedWeekIndex,
                            onSelectedIndexChange = { index ->
                                selectedWeek = index.takeIf { it > 0 }
                            }
                        )
                    }
                }

                TabRowWithContour(
                    tabs = listOf("概览", "流水"),
                    selectedTabIndex = selectedTab,
                    onTabSelected = { selectedTab = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                )

                AnimatedContent(
                    targetState = selectedTab,
                    transitionSpec = {
                        val direction = if (targetState > initialState) 1 else -1
                        (slideInHorizontally { direction * it / 4 } + fadeIn(
                            androidx.compose.animation.core.spring(dampingRatio = 0.85f, stiffness = 500f)
                        )) togetherWith (slideOutHorizontally { -direction * it / 4 } + fadeOut(
                            androidx.compose.animation.core.spring(dampingRatio = 0.85f, stiffness = 500f)
                        ))
                    },
                    label = "attendanceTab"
                ) { tab ->
                    when (tab) {
                        0 -> OverviewTab(studentName, courseStats, totalNormal, totalLate,
                            totalAbsence, totalLeave, displayRecords.size, attendanceRate,
                            selectedWeek, searchQuery) { searchQuery = it }
                        1 -> RecordFlowTab(filteredRecords, selectedStatus, displayRecords.size,
                            searchQuery, { searchQuery = it }) { selectedStatus = it }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OverviewTab(
    studentName: String,
    courseStats: List<CourseAttendanceStat>,
    totalNormal: Int, totalLate: Int, totalAbsence: Int, totalLeave: Int,
    totalRecords: Int, attendanceRate: Int,
    selectedWeek: Int?,
    searchQuery: String,
    onSearchChange: (String) -> Unit
) {
    LazyColumn(
        Modifier.fillMaxSize().overScrollVertical().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        // 概览卡片
        item {
            top.yukonga.miuix.kmp.basic.Card(
                Modifier.fillMaxWidth(),
                colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = if (attendanceRate < 70) MiuixTheme.colorScheme.errorContainer
                    else MiuixTheme.colorScheme.surface
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("$studentName 的考勤", style = MiuixTheme.textStyles.subtitle)
                            Text(
                                if (selectedWeek != null) "第${selectedWeek}周 · $totalRecords 条记录"
                                else "全学期 · $totalRecords 条记录",
                                style = MiuixTheme.textStyles.footnote1
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = when {
                                attendanceRate >= 90 -> MiuixTheme.colorScheme.primary
                                attendanceRate >= 70 -> MiuixTheme.colorScheme.primaryVariant
                                else -> MiuixTheme.colorScheme.error
                            }
                        ) {
                            Text(
                                "${attendanceRate}%",
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                style = MiuixTheme.textStyles.subtitle,
                                color = MiuixTheme.colorScheme.onPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    if (totalAbsence > 0) {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.WarningAmber, null,
                                modifier = Modifier.size(15.dp),
                                tint = MiuixTheme.colorScheme.error
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                "有 $totalAbsence 次缺勤记录" +
                                        if (totalLate > 0) "，$totalLate 次迟到" else "",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.error
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    val overviewBarColor = when {
                        attendanceRate >= 90 -> MiuixTheme.colorScheme.primary
                        attendanceRate >= 70 -> MiuixTheme.colorScheme.primaryVariant
                        else -> MiuixTheme.colorScheme.error
                    }
                    val animatedOverview by animateFloatAsState(
                        targetValue = (attendanceRate / 100f).coerceIn(0f, 1f),
                        animationSpec = spring(dampingRatio = 0.85f, stiffness = 500f),
                        label = "overviewBar"
                    )
                    LinearProgressIndicator(
                        progress = animatedOverview,
                        modifier = Modifier.fillMaxWidth(),
                        height = 6.dp,
                        colors = ProgressIndicatorDefaults.progressIndicatorColors(
                            foregroundColor = overviewBarColor,
                            backgroundColor = overviewBarColor.copy(alpha = 0.12f)
                        )
                    )
                }
            }
        }

        // 统计信息收进同一张卡，避免仪表盘式的碎片布局。
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.secondaryContainer)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatValue("正常", totalNormal, MiuixTheme.colorScheme.primary, Modifier.weight(1f))
                    StatValue("迟到", totalLate, MiuixTheme.colorScheme.primaryVariant, Modifier.weight(1f))
                    StatValue("缺勤", totalAbsence, MiuixTheme.colorScheme.error, Modifier.weight(1f))
                    StatValue(
                        "请假",
                        totalLeave,
                        MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        Modifier.weight(1f)
                    )
                }
            }
        }

        // 按课程统计
        if (courseStats.isNotEmpty()) {
            item {
                Text("按课程统计", style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            }

            // 搜索
            item {
                com.xjtu.toolbox.ui.components.AppSearchBar(
                    query = searchQuery,
                    onQueryChange = onSearchChange,
                    label = "搜索课程...",
                    modifier = Modifier.fillMaxWidth()
                )
            }

            val filtered = courseStats
                .filter { searchQuery.isBlank() || searchQuery.lowercase() in it.subjectName.lowercase() }
                .sortedByDescending { it.abnormalCount }

            items(filtered) { stat -> CourseStatCard(stat) }

            if (filtered.all { it.abnormalCount == 0 }) {
                item {
                    top.yukonga.miuix.kmp.basic.Card(
                        Modifier.fillMaxWidth(),
                        colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(
                            color = MiuixTheme.colorScheme.primary.copy(alpha = 0.08f)
                        )
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.CheckCircle, null,
                                modifier = Modifier.size(18.dp),
                                tint = MiuixTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("所有课程出勤良好", style = MiuixTheme.textStyles.body2,
                                fontWeight = FontWeight.Medium,
                                color = MiuixTheme.colorScheme.primary)
                        }
                    }
                }
            }
        } else {
            item {
                EmptyState(
                    title = "暂无课程考勤统计数据",
                    subtitle = "尝试切换学期或检查网络连接"
                )
            }
        }
    }
}

@Composable
private fun CourseStatCard(stat: CourseAttendanceStat) {
    val hasIssue = stat.abnormalCount > 0
    top.yukonga.miuix.kmp.basic.Card(
        modifier = Modifier.fillMaxWidth(), cornerRadius = 12.dp,
        colors = if (hasIssue) top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = MiuixTheme.colorScheme.errorContainer.copy(alpha = 0.15f)
        ) else top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text(stat.subjectName, style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                val rate = if (stat.total > 0) stat.actualCount * 100 / stat.total else 100
                val rateColor = when {
                    rate >= 90 -> MiuixTheme.colorScheme.primary
                    rate >= 70 -> MiuixTheme.colorScheme.primaryVariant
                    else -> MiuixTheme.colorScheme.error
                }
                Surface(shape = RoundedCornerShape(6.dp), color = rateColor.copy(alpha = 0.12f)) {
                    Text("${rate}%", Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MiuixTheme.textStyles.body2, color = rateColor, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(6.dp))
            // 出勤率进度条
            val rateForBar = if (stat.total > 0) stat.actualCount * 100 / stat.total else 100
            val animatedRate by animateFloatAsState(
                targetValue = (rateForBar / 100f).coerceIn(0f, 1f),
                animationSpec = spring(dampingRatio = 0.85f, stiffness = 500f),
                label = "attendanceBar"
            )
            val barColor = when {
                rateForBar >= 90 -> MiuixTheme.colorScheme.primary
                rateForBar >= 70 -> MiuixTheme.colorScheme.primaryVariant
                else -> MiuixTheme.colorScheme.error
            }
            LinearProgressIndicator(
                progress = animatedRate,
                modifier = Modifier.fillMaxWidth(),
                height = 4.dp,
                colors = ProgressIndicatorDefaults.progressIndicatorColors(foregroundColor = barColor, backgroundColor = MiuixTheme.colorScheme.surfaceVariant)
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatChip("正常", stat.normalCount, MiuixTheme.colorScheme.primary)
                StatChip("迟到", stat.lateCount, MiuixTheme.colorScheme.primaryVariant)
                StatChip("缺勤", stat.absenceCount, MiuixTheme.colorScheme.error)
                StatChip("请假", stat.leaveCount, MiuixTheme.colorScheme.onSurfaceVariantSummary)
                StatChip("总计", stat.total, MiuixTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun StatChip(label: String, count: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(count.toString(), style = MiuixTheme.textStyles.body1,
            fontWeight = FontWeight.Bold,
            color = if (count > 0) color else MiuixTheme.colorScheme.outline)
        Text(label, style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecordFlowTab(
    filteredRecords: List<AttendanceWaterRecord>,
    selectedStatus: WaterType?,
    totalCount: Int,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onStatusChange: (WaterType?) -> Unit
) {
    LazyColumn(
        Modifier.fillMaxSize().overScrollVertical().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        // 搜索框
        item {
            com.xjtu.toolbox.ui.components.AppSearchBar(
                query = searchQuery,
                onQueryChange = onSearchChange,
                label = "搜索课程、教室、教师...",
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 状态筛选
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.secondaryContainer)
            ) {
                FlowRow(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AppFilterChip(
                        selected = selectedStatus == null,
                        onClick = { onStatusChange(null) },
                        label = "全部 ($totalCount)",
                        leadingIcon = { Icon(Icons.Default.FilterList, null, Modifier.size(16.dp)) }
                    )
                    WaterType.entries.forEach { type ->
                        AppFilterChip(
                            selected = selectedStatus == type,
                            onClick = { onStatusChange(if (selectedStatus == type) null else type) },
                            label = type.displayName
                        )
                    }
                }
            }
        }

        item {
            Text("${filteredRecords.size} 条记录",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }

        // 按日期分组
        val groupedByDate = filteredRecords.groupBy { it.date }
        groupedByDate.forEach { (date, dayRecords) ->
            item(key = "header_$date") {
                Text(date, style = MiuixTheme.textStyles.body2,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp))
            }
            itemsIndexed(dayRecords, key = { idx, it -> "${it.date}_${it.startTime}_${it.courseName}_${it.sbh}_$idx" }) { _, record ->
                AttendanceRecordCard(record)
            }
        }

        if (filteredRecords.isEmpty()) {
            item {
                EmptyState(
                    title = "暂无符合条件的记录",
                    subtitle = "请尝试更换周次筛选"
                )
            }
        }
    }
}

@Composable
private fun StatValue(
    label: String,
    value: Int,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            value.toString(),
            style = MiuixTheme.textStyles.title3,
            color = if (value == 0) MiuixTheme.colorScheme.onSurfaceVariantSummary else color,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
    }
}

@Composable
private fun AttendanceRecordCard(record: AttendanceWaterRecord) {
    val statusColor = when (record.status) {
        WaterType.NORMAL -> MiuixTheme.colorScheme.primary
        WaterType.LATE -> MiuixTheme.colorScheme.primaryVariant
        WaterType.ABSENCE -> MiuixTheme.colorScheme.error
        WaterType.LEAVE -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    top.yukonga.miuix.kmp.basic.Card(
        modifier = Modifier.fillMaxWidth(),
        colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surface)
    ) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.width(3.dp).fillMaxHeight()
                    .padding(vertical = 10.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(statusColor)
            )
            Row(Modifier.weight(1f).padding(start = 11.dp, top = 12.dp, bottom = 12.dp, end = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(record.courseName.ifEmpty { record.location },
                        style = MiuixTheme.textStyles.body1)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (record.courseName.isNotEmpty()) {
                            Text(record.location, style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                        Text("第${record.week}周 · 第${record.startTime}-${record.endTime}节",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                    if (record.teacher.isNotEmpty()) {
                        Text(record.teacher, style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
                Surface(shape = RoundedCornerShape(8.dp), color = statusColor.copy(alpha = 0.12f)) {
                    Text(record.status.displayName, Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        style = MiuixTheme.textStyles.footnote1, color = statusColor, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
