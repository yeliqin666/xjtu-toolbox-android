package com.xjtu.toolbox.attendance

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
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.utils.overScrollVertical

import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.*
import com.xjtu.toolbox.ui.components.AppDropdownMenu
import com.xjtu.toolbox.ui.components.AppDropdownMenuItem
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
import kotlinx.coroutines.async
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
    var selectedTermName by rememberSaveable { mutableStateOf("") }
    var termDropdownExpanded by remember { mutableStateOf(false) }

    // 周次筛选（null = 全部）
    var selectedWeek by rememberSaveable { mutableStateOf<Int?>(null) }
    // 状态筛选
    var selectedStatus by rememberSaveable { mutableStateOf<WaterType?>(null) }
    // 课程搜索
    var searchQuery by rememberSaveable { mutableStateOf("") }

    fun loadData(termBh: String? = null) {
        isLoading = true
        errorMessage = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // 并行加载学生信息和学期列表
                    val infoDeferred = async { api.getStudentInfo() }
                    val termListDeferred = async {
                        try { api.getTermList() } catch (_: Exception) { emptyList() }
                    }
                    // 只在首次加载时获取当前学期编号
                    val currentTermDeferred = if (currentTermBh.isEmpty()) async {
                        try { api.getTermBh() } catch (_: Exception) { "" }
                    } else null

                    val info = infoDeferred.await()
                    studentName = info["name"] as? String ?: ""
                    termList = termListDeferred.await()
                    currentTermDeferred?.await()?.let { currentTermBh = it }

                    // 确定要查询的学期
                    val bh = termBh ?: currentTermBh
                    if (bh.isNotEmpty()) {
                        selectedTermBh = bh
                        selectedTermName = termList.firstOrNull { it.bh == bh }?.name ?: "当前学期"
                    }

                    // 加载考勤记录（历史学期需要日期范围）
                    val isCurrentTerm = termBh == null || bh == currentTermBh
                    val termInfo = termList.firstOrNull { it.bh == bh }
                    val fetchedRecords = if (!isCurrentTerm && termInfo != null && termInfo.startDate.isNotEmpty()) {
                        api.getWaterRecords(bh, startDate = termInfo.startDate, endDate = termInfo.endDate)
                    } else {
                        api.getWaterRecords(bh.ifEmpty { null })
                    }
                    records = fetchedRecords

                    // 课程统计：当前学期用 getKqtjCurrentWeek，历史学期用 getKqtjByTime + 回退
                    courseStats = if (isCurrentTerm) {
                        try {
                            api.getKqtjCurrentWeek()
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
                        } catch (_: Exception) { emptyList() }
                        // 3) API 为空则从 records 直接聚合
                        statsFromApi.ifEmpty { api.computeCourseStatsFromRecords(fetchedRecords) }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: AuthExpiredException) {
                appLoginState.handleAuthExpired(LoginType.ATTENDANCE, Routes.ATTENDANCE, onBack)
            } catch (e: Exception) {
                errorMessage = "加载失败: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    fun switchTerm(bh: String, name: String) {
        if (bh == selectedTermBh) return
        selectedTermBh = bh
        selectedTermName = name
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
                    .background(MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
            ) {
                // 学期选择器
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Surface(
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = top.yukonga.miuix.kmp.utils.SinkFeedback()
                            ) { termDropdownExpanded = true },
                        shape = RoundedCornerShape(12.dp),
                        color = MiuixTheme.colorScheme.surface
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(selectedTermName.ifEmpty { "选择学期" },
                                style = MiuixTheme.textStyles.body1)
                            Icon(Icons.Default.ArrowDropDown, null)
                        }
                    }
                    AppDropdownMenu(
                        expanded = termDropdownExpanded,
                        onDismissRequest = { termDropdownExpanded = false }
                    ) {
                        termList.forEach { term ->
                            AppDropdownMenuItem(
                                text = { Text(term.name) },
                                onClick = {
                                    switchTerm(term.bh, term.name)
                                    termDropdownExpanded = false
                                },
                                trailingIcon = {
                                    if (term.bh == selectedTermBh)
                                        Icon(Icons.Default.Check, null, tint = MiuixTheme.colorScheme.primary)
                                }
                            )
                        }
                    }
                }

                // 周次快速筛选
                if (maxWeek > 0) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        AppFilterChip(
                            selected = selectedWeek == null,
                            onClick = { selectedWeek = null },
                            label = "全部周",
                            modifier = Modifier.height(32.dp)
                        )
                        for (w in 1..maxWeek) {
                            val weekRecordCount = records.count { it.week == w }
                            val hasIssue = records.any { it.week == w &&
                                    (it.status == WaterType.ABSENCE || it.status == WaterType.LATE) }
                            AppFilterChip(
                                selected = selectedWeek == w,
                                onClick = { selectedWeek = if (selectedWeek == w) null else w },
                                label = "$w",
                                modifier = Modifier.height(32.dp),
                                unselectedContainerColor = when {
                                    hasIssue -> MiuixTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                                    weekRecordCount == 0 -> MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                    else -> Color.Transparent
                                }
                            )
                        }
                    }
                }

                // Tab
                Surface(modifier = Modifier.fillMaxWidth(), color = MiuixTheme.colorScheme.surface) {
                TabRowWithContour(
                    tabs = listOf("概览", "流水"),
                    selectedTabIndex = selectedTab,
                    onTabSelected = { selectedTab = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                )
                }

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
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "⚠ 有 $totalAbsence 次缺勤记录" +
                                    if (totalLate > 0) "，$totalLate 次迟到" else "",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        // 四格统计
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("正常", totalNormal.toString(), MiuixTheme.colorScheme.primary, Modifier.weight(1f))
                StatCard("迟到", totalLate.toString(), MiuixTheme.colorScheme.primaryVariant, Modifier.weight(1f))
                StatCard("缺勤", totalAbsence.toString(), MiuixTheme.colorScheme.error, Modifier.weight(1f))
                StatCard("请假", totalLeave.toString(), MiuixTheme.colorScheme.onSurfaceVariantSummary, Modifier.weight(1f))
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
                        Modifier.fillMaxWidth()
                    ) {
                        Text("✓ 所有课程出勤良好", Modifier.padding(16.dp),
                            style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Bold)
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
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
private fun StatCard(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    top.yukonga.miuix.kmp.basic.Card(
        modifier = modifier,
        colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surface)
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MiuixTheme.textStyles.title4, color = color, fontWeight = FontWeight.Bold)
            Text(label, style = MiuixTheme.textStyles.footnote1)
        }
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
        Row(Modifier.fillMaxWidth().padding(12.dp),
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
            Surface(shape = RoundedCornerShape(6.dp), color = statusColor.copy(alpha = 0.12f)) {
                Text(record.status.displayName, Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MiuixTheme.textStyles.footnote1, color = statusColor, fontWeight = FontWeight.Bold)
            }
        }
    }
}