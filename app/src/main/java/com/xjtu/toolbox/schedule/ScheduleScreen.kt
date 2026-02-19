package com.xjtu.toolbox.schedule

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Book
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.auth.JwxtLogin
import com.xjtu.toolbox.ui.ScheduleGrid
import com.xjtu.toolbox.ui.WeekSelector
import com.xjtu.toolbox.ui.components.AppFilterChip
import com.xjtu.toolbox.ui.components.LoadingState
import com.xjtu.toolbox.ui.components.ErrorState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(login: JwxtLogin, studentId: String = "", onBack: () -> Unit) {
    val api = remember { ScheduleApi(login) }
    val scope = rememberCoroutineScope()

    var courses by remember { mutableStateOf<List<CourseItem>>(emptyList()) }
    var exams by remember { mutableStateOf<List<ExamItem>>(emptyList()) }
    var textbooks by remember { mutableStateOf<List<TextbookItem>>(emptyList()) }
    var textbooksLoading by remember { mutableStateOf(false) }
    var textbooksError by remember { mutableStateOf<String?>(null) }
    var textbooksLoaded by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var currentWeek by remember { mutableIntStateOf(1) }
    val totalWeeks = 20
    var selectedTab by remember { mutableIntStateOf(0) }
    var weekNote by remember { mutableStateOf<String?>(null) } // "距开学X周" / "学期已结束"

    // 学期相关
    var termList by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedTermCode by remember { mutableStateOf("") }
    var termDropdownExpanded by remember { mutableStateOf(false) }

    // 周视图 vs 总览
    var showAllWeeks by remember { mutableStateOf(false) }

    // 初始加载
    fun loadInitialData() {
        isLoading = true
        errorMessage = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val termCode = api.getCurrentTerm()
                    selectedTermCode = termCode
                    courses = api.getSchedule(termCode)
                    exams = api.getExamSchedule(termCode)
                    try {
                        val startDate = api.getStartOfTerm(termCode)
                        val today = LocalDate.now()
                        val daysBetween = ChronoUnit.DAYS.between(startDate, today)
                        val rawWeek = ((daysBetween / 7) + 1).toInt()
                        // 用课程数据检测是否已开课
                        val firstTeachWeek = courses.flatMap { c ->
                            c.weekBits.indices.filter { c.weekBits[it] == '1' }.map { it + 1 }
                        }.minOrNull()
                        val notStarted = rawWeek in 1..totalWeeks &&
                                firstTeachWeek != null && rawWeek < firstTeachWeek
                        currentWeek = when {
                            rawWeek <= 0 -> 1
                            rawWeek > totalWeeks -> { showAllWeeks = true; 1 } // 已结束→总览+第1周
                            notStarted -> firstTeachWeek
                            else -> rawWeek
                        }
                        weekNote = when {
                            rawWeek <= 0 -> "距开学还有 ${1 - rawWeek} 周"
                            rawWeek > totalWeeks -> "学期已结束"
                            notStarted -> "尚未开课 · 第${firstTeachWeek}周开始上课"
                            else -> null
                        }
                    } catch (_: Exception) { currentWeek = 1; weekNote = null }
                    try { termList = api.getTermList() } catch (_: Exception) { }
                }
            } catch (e: Exception) {
                errorMessage = "加载失败: ${e.message}"
            } finally { isLoading = false }
        }
    }

    // 懒加载教材（切换到教材 tab 时才加载）
    fun loadTextbooks(termCode: String) {
        android.util.Log.d("ScheduleUI", "loadTextbooks called: studentId='$studentId', termCode='$termCode'")
        if (studentId.isBlank()) { textbooksError = "未获取到学号"; return }
        textbooksLoading = true
        textbooksError = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    textbooks = api.getTextbooks(studentId, termCode)
                }
                android.util.Log.d("ScheduleUI", "loadTextbooks done: ${textbooks.size} items")
                textbooksLoaded = true
            } catch (e: Exception) {
                android.util.Log.e("ScheduleUI", "loadTextbooks failed", e)
                textbooksError = "教材查询失败: ${e.message}"
            } finally { textbooksLoading = false }
        }
    }

    LaunchedEffect(Unit) {
        android.util.Log.d("ScheduleUI", "ScheduleScreen entered: studentId='$studentId'")
        loadInitialData()
    }

    // 安全触发: 当 Tab 已在教材且数据未加载时自动加载
    LaunchedEffect(selectedTab, selectedTermCode, textbooksLoaded) {
        if (selectedTab == 2 && !textbooksLoaded && !textbooksLoading && selectedTermCode.isNotEmpty()) {
            android.util.Log.d("ScheduleUI", "LaunchedEffect auto-loading textbooks: term=$selectedTermCode")
            loadTextbooks(selectedTermCode)
        }
    }

    // 切换学期
    fun switchTerm(newTermCode: String) {
        if (newTermCode == selectedTermCode) return
        selectedTermCode = newTermCode
        textbooksLoaded = false
        textbooks = emptyList()
        scope.launch {
            isLoading = true
            errorMessage = null
            try {
                withContext(Dispatchers.IO) {
                    courses = api.getSchedule(newTermCode)
                    exams = api.getExamSchedule(newTermCode)
                    try {
                        val startDate = api.getStartOfTerm(newTermCode)
                        val today = LocalDate.now()
                        val daysBetween = ChronoUnit.DAYS.between(startDate, today)
                        val rawWeek = ((daysBetween / 7) + 1).toInt()
                        val firstTeachWeek = courses.flatMap { c ->
                            c.weekBits.indices.filter { c.weekBits[it] == '1' }.map { it + 1 }
                        }.minOrNull()
                        val notStarted = rawWeek in 1..totalWeeks &&
                                firstTeachWeek != null && rawWeek < firstTeachWeek
                        currentWeek = when {
                            rawWeek <= 0 -> 1
                            rawWeek > totalWeeks -> { showAllWeeks = true; 1 } // 已结束→总览+第1周
                            notStarted -> firstTeachWeek
                            else -> rawWeek
                        }
                        weekNote = when {
                            rawWeek <= 0 -> "距开学还有 ${1 - rawWeek} 周"
                            rawWeek > totalWeeks -> "学期已结束"
                            notStarted -> "尚未开课 · 第${firstTeachWeek}周开始上课"
                            else -> null
                        }
                    } catch (_: Exception) { currentWeek = 1; weekNote = null }
                }
            } catch (e: Exception) {
                errorMessage = "加载失败: ${e.message}"
            } finally { isLoading = false }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // 学期选择下拉
                    Box {
                        TextButton(onClick = { if (termList.isNotEmpty()) termDropdownExpanded = true }) {
                            Text(
                                if (selectedTermCode.isNotEmpty()) selectedTermCode else "课表 · 考试",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            if (termList.isNotEmpty()) {
                                Icon(Icons.Default.ArrowDropDown, null, Modifier.size(20.dp))
                            }
                        }
                        DropdownMenu(expanded = termDropdownExpanded, onDismissRequest = { termDropdownExpanded = false }) {
                            termList.forEach { term ->
                                DropdownMenuItem(
                                    text = { Text(term, fontWeight = if (term == selectedTermCode) FontWeight.Bold else FontWeight.Normal) },
                                    onClick = { termDropdownExpanded = false; switchTerm(term) },
                                    leadingIcon = if (term == selectedTermCode) {{ Icon(Icons.Default.CalendarMonth, null, Modifier.size(18.dp)) }} else null
                                )
                            }
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") } }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                SegmentedButton(selected = selectedTab == 0, onClick = { selectedTab = 0 }, shape = SegmentedButtonDefaults.itemShape(0, 3),
                    icon = { SegmentedButtonDefaults.Icon(selectedTab == 0) { Icon(Icons.Default.CalendarMonth, null, Modifier.size(18.dp)) } }) { Text("课表") }
                SegmentedButton(selected = selectedTab == 1, onClick = { selectedTab = 1 }, shape = SegmentedButtonDefaults.itemShape(1, 3),
                    icon = { SegmentedButtonDefaults.Icon(selectedTab == 1) { Icon(Icons.Default.EditCalendar, null, Modifier.size(18.dp)) } }) { Text("考试") }
                SegmentedButton(selected = selectedTab == 2, onClick = {
                    selectedTab = 2
                    android.util.Log.d("ScheduleUI", "Tab 教材 clicked: loaded=$textbooksLoaded, loading=$textbooksLoading, term=$selectedTermCode")
                    if (!textbooksLoaded && !textbooksLoading && selectedTermCode.isNotEmpty()) loadTextbooks(selectedTermCode)
                }, shape = SegmentedButtonDefaults.itemShape(2, 3),
                    icon = { SegmentedButtonDefaults.Icon(selectedTab == 2) { Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.size(18.dp)) } }) { Text("教材") }
            }

            if (isLoading) {
                LoadingState(message = "加载课表...", modifier = Modifier.fillMaxSize())
            } else if (errorMessage != null) {
                ErrorState(
                    message = errorMessage!!,
                    onRetry = { loadInitialData() },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                AnimatedContent(
                    targetState = selectedTab,
                    transitionSpec = {
                        fadeIn() + slideInHorizontally { if (targetState > initialState) it else -it } togetherWith
                                fadeOut() + slideOutHorizontally { if (targetState > initialState) -it else it }
                    }, label = "tabSwitch"
                ) { tab ->
                    when (tab) {
                        0 -> ScheduleTabContent(courses, currentWeek, totalWeeks, showAllWeeks, weekNote, onWeekChange = { currentWeek = it }, onToggleMode = { showAllWeeks = !showAllWeeks })
                        1 -> ExamTabContent(exams)
                        2 -> TextbookTabContent(
                            textbooks = textbooks,
                            isLoading = textbooksLoading,
                            error = textbooksError,
                            onRetry = { if (selectedTermCode.isNotEmpty()) loadTextbooks(selectedTermCode) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduleTabContent(
    courses: List<CourseItem>, currentWeek: Int, totalWeeks: Int,
    showAllWeeks: Boolean, weekNote: String? = null,
    onWeekChange: (Int) -> Unit, onToggleMode: () -> Unit
) {
    val allNames = remember(courses) { courses.map { it.courseName }.distinct().sorted() }
    var selectedCourse by remember { mutableStateOf<CourseItem?>(null) }

    Column(Modifier.fillMaxSize()) {
        // 学期状态提示（未开学/已结束）
        if (weekNote != null) {
            Surface(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
            ) {
                Text(
                    weekNote,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }

        // 模式切换: 每周 / 总览
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End
        ) {
            AppFilterChip(
                selected = !showAllWeeks,
                onClick = { if (showAllWeeks) onToggleMode() },
                label = "每周"
            )
            Spacer(Modifier.width(8.dp))
            AppFilterChip(
                selected = showAllWeeks,
                onClick = { if (!showAllWeeks) onToggleMode() },
                label = "总览"
            )
        }

        if (!showAllWeeks) {
            WeekSelector(currentWeek, totalWeeks, onWeekChange)
            ScheduleGrid(
                courses.filter { it.isInWeek(currentWeek) }, allNames,
                onSlotClick = { selectedCourse = it as? CourseItem }
            )
        } else {
            ScheduleGrid(
                courses, allNames,
                showWeeks = true,
                onSlotClick = { selectedCourse = it as? CourseItem }
            )
        }
    }

    // 课程详情弹窗
    selectedCourse?.let { course ->
        CourseDetailDialog(course, onDismiss = { selectedCourse = null })
    }
}

@Composable
private fun CourseDetailDialog(course: CourseItem, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(course.courseName, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (course.teacher.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Person, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("教师: ${course.teacher}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (course.location.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Place, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(8.dp))
                        Text("教室: ${course.location}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CalendarMonth, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.width(8.dp))
                    val dayName = when (course.dayOfWeek) {
                        1 -> "一"; 2 -> "二"; 3 -> "三"; 4 -> "四"
                        5 -> "五"; 6 -> "六"; 7 -> "日"; else -> "?"
                    }
                    Text("星期$dayName  第${course.startSection}-${course.endSection}节", style = MaterialTheme.typography.bodyMedium)
                }
                val weeks = course.getWeeks()
                if (weeks.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.DateRange, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("周次: ${formatWeeks(weeks)}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (course.courseType.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Text("类型: ${course.courseType}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

/** 格式化周次：[1,2,3,5,7,8,9] → "1-3, 5, 7-9" */
private fun formatWeeks(weeks: List<Int>): String {
    if (weeks.isEmpty()) return ""
    val sorted = weeks.sorted()
    val ranges = mutableListOf<String>()
    var start = sorted[0]; var end = sorted[0]
    for (i in 1 until sorted.size) {
        if (sorted[i] == end + 1) { end = sorted[i] }
        else {
            ranges.add(if (start == end) "$start" else "$start-$end")
            start = sorted[i]; end = sorted[i]
        }
    }
    ranges.add(if (start == end) "$start" else "$start-$end")
    return ranges.joinToString(", ")
}

@Composable
private fun ExamTabContent(exams: List<ExamItem>) {
    // 去重（同一门课+同一天只显示一次）
    val uniqueExams = exams.distinctBy { "${it.courseName}_${it.examDate}" }
    if (uniqueExams.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无考试安排", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        items(uniqueExams) { exam -> ExamCard(exam) }
    }
}

@Composable
private fun ExamCard(exam: ExamItem) {
    // 判断已考/今天/未考
    val today = java.time.LocalDate.now()
    val examLocalDate = try {
        val dateStr = exam.examDate.replace("年", "-").replace("月", "-").replace("日", "").trim()
        java.time.LocalDate.parse(dateStr)
    } catch (_: Exception) { null }
    val isPast = examLocalDate != null && examLocalDate.isBefore(today)
    val isToday = examLocalDate != null && examLocalDate.isEqual(today)
    val daysUntil = examLocalDate?.let { java.time.temporal.ChronoUnit.DAYS.between(today, it).toInt() }

    val accentColor = when {
        isToday -> MaterialTheme.colorScheme.error
        isPast -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.primary
    }

    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isPast) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isPast) 0.dp else 2.dp)
    ) {
        Row(Modifier.fillMaxWidth()) {
            // ── 左侧：日历日期 ──
            if (examLocalDate != null) {
                Surface(
                    modifier = Modifier.width(72.dp).fillMaxHeight(),
                    shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp),
                    color = accentColor.copy(alpha = if (isPast) 0.08f else 0.12f)
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            "${examLocalDate.monthValue}月",
                            style = MaterialTheme.typography.labelSmall,
                            color = accentColor,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            "${examLocalDate.dayOfMonth}",
                            style = MaterialTheme.typography.headlineMedium,
                            color = accentColor,
                            fontWeight = FontWeight.Bold
                        )
                        val dayOfWeek = when (examLocalDate.dayOfWeek.value) {
                            1 -> "周一"; 2 -> "周二"; 3 -> "周三"; 4 -> "周四"
                            5 -> "周五"; 6 -> "周六"; 7 -> "周日"; else -> ""
                        }
                        Text(
                            dayOfWeek,
                            style = MaterialTheme.typography.labelSmall,
                            color = accentColor.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            // ── 右侧：考试信息 ──
            Column(Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 14.dp)) {
                // 课程名 + 状态
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        exam.courseName.ifEmpty { "未知课程" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        modifier = Modifier.weight(1f),
                        color = if (isPast) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.width(6.dp))
                    // 状态标签
                    val (label, badgeColor) = when {
                        isToday -> "今天" to MaterialTheme.colorScheme.error
                        daysUntil != null && daysUntil in 1..7 -> "${daysUntil}天后" to MaterialTheme.colorScheme.tertiary
                        isPast -> "已结束" to MaterialTheme.colorScheme.outline
                        daysUntil != null && daysUntil > 7 -> "${daysUntil}天后" to MaterialTheme.colorScheme.primary
                        else -> "" to MaterialTheme.colorScheme.outline
                    }
                    if (label.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = badgeColor.copy(alpha = 0.12f)
                        ) {
                            Text(
                                label,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = badgeColor
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // 时间 + 地点 紧凑排列
                val infoColor = if (isPast) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    else MaterialTheme.colorScheme.onSurfaceVariant

                if (exam.examTime.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Schedule, contentDescription = null, Modifier.size(14.dp), tint = infoColor)
                        Spacer(Modifier.width(4.dp))
                        Text(exam.examTime, style = MaterialTheme.typography.bodySmall, color = infoColor)
                    }
                    Spacer(Modifier.height(3.dp))
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Place, contentDescription = null, Modifier.size(14.dp), tint = infoColor)
                    Spacer(Modifier.width(4.dp))
                    Text(exam.location.ifEmpty { "待定" }, style = MaterialTheme.typography.bodySmall, color = infoColor)
                    if (exam.seatNumber.isNotEmpty()) {
                        Text("  ·  座位 ${exam.seatNumber}", style = MaterialTheme.typography.bodySmall, color = infoColor, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

// ═══════════════════════ 教材 Tab ═══════════════════════

@Composable
private fun TextbookTabContent(
    textbooks: List<TextbookItem>,
    isLoading: Boolean,
    error: String?,
    onRetry: () -> Unit
) {
    when {
        isLoading -> LoadingState(message = "查询教材信息...", modifier = Modifier.fillMaxSize())
        error != null -> ErrorState(message = error, onRetry = onRetry, modifier = Modifier.fillMaxSize())
        textbooks.isEmpty() -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(12.dp))
                    Text("暂无教材信息", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text("本学期可能未录入教材数据", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
        else -> {
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                items(textbooks) { item -> TextbookCard(item) }
            }
        }
    }
}

@Composable
private fun TextbookCard(item: TextbookItem) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(Modifier.fillMaxWidth()) {
            // 左侧书本图标区域
            Surface(
                modifier = Modifier.width(56.dp).heightIn(min = 80.dp),
                shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            ) {
                Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.BottomCenter) {
                    Icon(
                        Icons.Default.Book, null,
                        Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Column(
                Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // 课程名
                Text(
                    item.courseName.ifEmpty { "未知课程" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // 教材名
                Text(
                    item.textbookName.ifEmpty { "教材名称未录入" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                // 作者 + 出版社
                val meta = buildList {
                    if (item.author.isNotBlank()) add(item.author)
                    if (item.publisher.isNotBlank()) add(item.publisher)
                    if (item.edition.isNotBlank()) add(item.edition)
                }.joinToString(" · ")
                if (meta.isNotEmpty()) {
                    Text(
                        meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // ISBN + 价格
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (item.isbn.isNotBlank()) {
                        Text(
                            "ISBN: ${item.isbn}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                    if (item.price.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f)
                        ) {
                            Text(
                                "¥${item.price}",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                }
            }
        }
    }
}
