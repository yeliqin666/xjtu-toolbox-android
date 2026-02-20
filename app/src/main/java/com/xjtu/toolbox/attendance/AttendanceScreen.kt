package com.xjtu.toolbox.attendance

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AttendanceScreen(
    login: AttendanceLogin,
    onBack: () -> Unit
) {
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

                    // 加载考勤记录
                    val fetchedRecords = api.getWaterRecords(bh.ifEmpty { null })
                    records = fetchedRecords

                    // 课程统计：当前学期用 getKqtjCurrentWeek，历史学期用 getKqtjByTime + 回退
                    val isCurrentTerm = termBh == null || bh == currentTermBh
                    courseStats = if (isCurrentTerm) {
                        try {
                            api.getKqtjCurrentWeek()
                        } catch (_: Exception) {
                            api.computeCourseStatsFromRecords(fetchedRecords)
                        }
                    } else {
                        // 历史学期：1) 从 TermInfo 获取日期范围  2) 从 records 推算
                        val termInfo = termList.firstOrNull { it.bh == bh }
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("考勤查询") },
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
            Column(Modifier.fillMaxSize().padding(padding)) {
                // 学期选择器
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    OutlinedCard(
                        onClick = { termDropdownExpanded = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(selectedTermName.ifEmpty { "选择学期" },
                                style = MaterialTheme.typography.bodyLarge)
                            Icon(Icons.Default.ArrowDropDown, null)
                        }
                    }
                    DropdownMenu(
                        expanded = termDropdownExpanded,
                        onDismissRequest = { termDropdownExpanded = false }
                    ) {
                        termList.forEach { term ->
                            DropdownMenuItem(
                                text = { Text(term.name) },
                                onClick = {
                                    switchTerm(term.bh, term.name)
                                    termDropdownExpanded = false
                                },
                                trailingIcon = {
                                    if (term.bh == selectedTermBh)
                                        Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
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
                            FilterChip(
                                selected = selectedWeek == w,
                                onClick = { selectedWeek = if (selectedWeek == w) null else w },
                                label = { Text("$w") },
                                modifier = Modifier.height(32.dp),
                                colors = when {
                                    hasIssue && selectedWeek != w ->
                                        FilterChipDefaults.filterChipColors(
                                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f))
                                    weekRecordCount == 0 && selectedWeek != w ->
                                        FilterChipDefaults.filterChipColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                                    else -> FilterChipDefaults.filterChipColors()
                                }
                            )
                        }
                    }
                }

                // Tab
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    SegmentedButton(selected = selectedTab == 0, onClick = { selectedTab = 0 },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                        icon = { SegmentedButtonDefaults.Icon(selectedTab == 0) { Icon(Icons.Default.BarChart, null, Modifier.size(18.dp)) } }
                    ) { Text("概览") }
                    SegmentedButton(selected = selectedTab == 1, onClick = { selectedTab = 1 },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                        icon = { SegmentedButtonDefaults.Icon(selectedTab == 1) { Icon(Icons.AutoMirrored.Filled.FormatListBulleted, null, Modifier.size(18.dp)) } }
                    ) { Text("流水") }
                }

                AnimatedContent(
                    targetState = selectedTab,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
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
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        // 概览卡片
        item {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (attendanceRate >= 90) MaterialTheme.colorScheme.primaryContainer
                    else if (attendanceRate >= 70) MaterialTheme.colorScheme.tertiaryContainer
                    else MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("$studentName 的考勤", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (selectedWeek != null) "第${selectedWeek}周 · $totalRecords 条记录"
                                else "全学期 · $totalRecords 条记录",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = when {
                                attendanceRate >= 90 -> MaterialTheme.colorScheme.primary
                                attendanceRate >= 70 -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.error
                            }
                        ) {
                            Text(
                                "${attendanceRate}%",
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    if (totalAbsence > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "⚠ 有 $totalAbsence 次缺勤记录" +
                                    if (totalLate > 0) "，$totalLate 次迟到" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        // 四格统计
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("正常", totalNormal.toString(), MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                StatCard("迟到", totalLate.toString(), MaterialTheme.colorScheme.tertiary, Modifier.weight(1f))
                StatCard("缺勤", totalAbsence.toString(), MaterialTheme.colorScheme.error, Modifier.weight(1f))
                StatCard("请假", totalLeave.toString(), MaterialTheme.colorScheme.onSurfaceVariant, Modifier.weight(1f))
            }
        }

        // 按课程统计
        if (courseStats.isNotEmpty()) {
            item {
                Text("按课程统计", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            }

            // 搜索
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchChange,
                    placeholder = { Text("搜索课程...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) IconButton(onClick = { onSearchChange("") }) {
                            Icon(Icons.Default.Clear, null)
                        }
                    }
                )
            }

            val filtered = courseStats
                .filter { searchQuery.isBlank() || searchQuery.lowercase() in it.subjectName.lowercase() }
                .sortedByDescending { it.abnormalCount }

            items(filtered) { stat -> CourseStatCard(stat) }

            if (filtered.all { it.abnormalCount == 0 }) {
                item {
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Text("✓ 所有课程出勤良好", Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
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
    Card(
        Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        colors = if (hasIssue) CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)
        ) else CardDefaults.cardColors()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text(stat.subjectName, style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                val rate = if (stat.total > 0) stat.actualCount * 100 / stat.total else 100
                val rateColor = when {
                    rate >= 90 -> MaterialTheme.colorScheme.primary
                    rate >= 70 -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.error
                }
                Surface(shape = RoundedCornerShape(6.dp), color = rateColor.copy(alpha = 0.12f)) {
                    Text("${rate}%", Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelMedium, color = rateColor, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(6.dp))
            // 出勤率进度条
            val rateForBar = if (stat.total > 0) stat.actualCount * 100 / stat.total else 100
            val animatedRate by animateFloatAsState(
                targetValue = (rateForBar / 100f).coerceIn(0f, 1f),
                animationSpec = tween(700),
                label = "attendanceBar"
            )
            val barColor = when {
                rateForBar >= 90 -> MaterialTheme.colorScheme.primary
                rateForBar >= 70 -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.error
            }
            LinearProgressIndicator(
                progress = { animatedRate },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = barColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatChip("正常", stat.normalCount, MaterialTheme.colorScheme.primary)
                StatChip("迟到", stat.lateCount, MaterialTheme.colorScheme.tertiary)
                StatChip("缺勤", stat.absenceCount, MaterialTheme.colorScheme.error)
                StatChip("请假", stat.leaveCount, MaterialTheme.colorScheme.onSurfaceVariant)
                StatChip("总计", stat.total, MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun StatChip(label: String, count: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(count.toString(), style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = if (count > 0) color else MaterialTheme.colorScheme.outlineVariant)
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        // 搜索框
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                placeholder = { Text("搜索课程、教室、教师...") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) IconButton(onClick = { onSearchChange("") }) {
                        Icon(Icons.Default.Clear, null)
                    }
                }
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
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // 按日期分组
        val groupedByDate = filteredRecords.groupBy { it.date }
        groupedByDate.forEach { (date, dayRecords) ->
            item(key = "header_$date") {
                Text(date, style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp))
            }
            items(dayRecords, key = { it.sbh.ifEmpty { "${it.date}_${it.startTime}_${it.courseName}" } }) { record ->
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
    Card(modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleLarge, color = color, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun AttendanceRecordCard(record: AttendanceWaterRecord) {
    val statusColor = when (record.status) {
        WaterType.NORMAL -> MaterialTheme.colorScheme.primary
        WaterType.LATE -> MaterialTheme.colorScheme.tertiary
        WaterType.ABSENCE -> MaterialTheme.colorScheme.error
        WaterType.LEAVE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(record.courseName.ifEmpty { record.location },
                    style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (record.courseName.isNotEmpty()) {
                        Text(record.location, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("第${record.week}周 · 第${record.startTime}-${record.endTime}节",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (record.teacher.isNotEmpty()) {
                    Text(record.teacher, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Surface(shape = RoundedCornerShape(6.dp), color = statusColor.copy(alpha = 0.12f)) {
                Text(record.status.displayName, Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall, color = statusColor, fontWeight = FontWeight.Bold)
            }
        }
    }
}
