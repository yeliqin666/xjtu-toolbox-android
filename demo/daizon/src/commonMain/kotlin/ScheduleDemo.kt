import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.theme.MiuixTheme

private data class MockCourse(
    val name: String,
    val teacher: String,
    val room: String,
    val startSection: Int,
    val endSection: Int,
    val dayOfWeek: Int,
    val color: Color,
)

private val mockCourses = listOf(
    MockCourse("高等数学", "张伟", "理学院302", 1, 2, 1, Color(0xFF5B8DEF)),
    MockCourse("大学英语", "李梅", "文化大厦201", 3, 4, 1, Color(0xFFFF8A50)),
    MockCourse("线性代数", "王强", "东二楼401", 1, 2, 2, Color(0xFF4CAF8A)),
    MockCourse("物理实验", "刘华", "东三实验室", 5, 8, 2, Color(0xFFAB47BC)),
    MockCourse("程序设计", "陈明", "逸夫馆机房", 1, 4, 3, Color(0xFFFF6B6B)),
    MockCourse("大学物理", "周丽", "理学院101", 3, 4, 3, Color(0xFF26C6DA)),
    MockCourse("思想政治", "赵磊", "文化大厦101", 1, 2, 4, Color(0xFFFFB74D)),
    MockCourse("体育课", "孙涛", "体育场", 3, 4, 4, Color(0xFF66BB6A)),
    MockCourse("工程图学", "钱进", "图书馆报告厅", 5, 6, 5, Color(0xFFEF5350)),
)

private val sectionTimes = listOf(
    "8:00", "8:55", "10:00", "10:55",
    "14:00", "14:55", "16:00", "16:55",
    "19:00", "19:55",
)

private val weekDays = listOf("一", "二", "三", "四", "五", "六", "日")

// 硬编码头部，避免 lambda 算术触发 FIR bug
private val weekHeaders = listOf(
    Triple("一", "13", false),
    Triple("二", "14", false),
    Triple("三", "15", true),
    Triple("四", "16", false),
    Triple("五", "17", false),
    Triple("六", "18", false),
    Triple("日", "19", false),
)
private val todayCourses = mockCourses.filter { it.dayOfWeek == 3 }
private val day1Courses = mockCourses.filter { it.dayOfWeek == 1 }
private val day2Courses = mockCourses.filter { it.dayOfWeek == 2 }
private val day4Courses = mockCourses.filter { it.dayOfWeek == 4 }
private val day5Courses = mockCourses.filter { it.dayOfWeek == 5 }

@Composable
fun ScheduleDemo() {
    var selectedTab by remember { mutableIntStateOf(0) }
    val scrollBehavior = MiuixScrollBehavior()

    Scaffold(
        topBar = {
            TopAppBar(
                title = "课表",
                largeTitle = "日程",
                scrollBehavior = scrollBehavior,
                actions = {
                    Row {
                        IconButton(onClick = {}, modifier = Modifier.padding(end = 4.dp)) {
                            Icon(MiuixIcons.Add, contentDescription = "添加日程", tint = MiuixTheme.colorScheme.onBackground)
                        }
                        IconButton(onClick = {}, modifier = Modifier.padding(end = 12.dp)) {
                            Icon(MiuixIcons.ListView, contentDescription = "切换视图", tint = MiuixTheme.colorScheme.onBackground)
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            TabRow(
                tabs = listOf("日程", "考试", "教材"),
                selectedTabIndex = selectedTab,
                onTabSelected = { selectedTab = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )

            when (selectedTab) {
                0 -> WeekScheduleView()
                1 -> ExamListView()
                else -> TextbookListView()
            }
        }
    }
}

@Composable
private fun WeekScheduleView() {
    val todayIndex = 2

    Column(Modifier.fillMaxSize()) {
        // 周导航行
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "第14周",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                modifier = Modifier.width(36.dp),
                textAlign = TextAlign.Center,
            )
            repeat(weekHeaders.size) { i ->
                val h = weekHeaders[i]
                DayHeaderCell(day = h.first, dateStr = h.second, isToday = h.third)
            }
        }

        HorizontalDivider()

        // 时间轴 + 课程格
        Row(Modifier.fillMaxSize()) {
            // 左侧时间标签
            Column(Modifier.width(36.dp).padding(top = 4.dp)) {
                repeat(sectionTimes.size) { i ->
                    val time = sectionTimes[i]
                    Box(
                        Modifier.height(52.dp),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        if (i % 2 == 0) {
                            Text(
                                text = time,
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                                fontSize = 9.sp,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }

            // 每天课程列
            val allDayCourses = listOf(
                day1Courses, day2Courses, todayCourses,
                day4Courses, day5Courses, emptyList(), emptyList()
            )
            repeat(weekDays.size) { dayIdx ->
                val isToday = dayIdx == 2
                val dayCourses = allDayCourses[dayIdx]
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .background(if (isToday) MiuixTheme.colorScheme.primary.copy(alpha = 0.04f) else Color.Transparent)
                ) {
                    dayCourses.forEach { course ->
                        CourseBlock(course)
                    }
                }
            }
        }
    }
}

@Composable
private fun ExamListView() {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(4) { i ->
            val exams = listOf(
                Triple("高等数学（上）", "2025-01-10  14:00-16:00", "理学院302"),
                Triple("大学英语（四级）", "2025-01-13  09:00-11:20", "文化大厦201"),
                Triple("线性代数", "2025-01-15  14:00-16:00", "东二楼401"),
                Triple("大学物理", "2025-01-18  09:00-11:00", "理学院101"),
            )
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(exams[i].first, style = MiuixTheme.textStyles.headline1)
                    Text(exams[i].second, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantActions)
                    Text("📍 ${exams[i].third}", style = MiuixTheme.textStyles.body2)
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.DayHeaderCell(
    day: String,
    dateStr: String,
    isToday: Boolean,
) {
    Column(
        modifier = Modifier.weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            day,
            style = MiuixTheme.textStyles.footnote1,
            color = if (isToday) MiuixTheme.colorScheme.primary
                    else MiuixTheme.colorScheme.onSurfaceVariantActions,
            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
        )
        Box(
            modifier = Modifier
                .size(if (isToday) 24.dp else 20.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(if (isToday) MiuixTheme.colorScheme.primary else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                dateStr,
                style = MiuixTheme.textStyles.footnote1,
                color = if (isToday) Color.White else MiuixTheme.colorScheme.onBackground,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}

@Composable
private fun CourseBlock(course: MockCourse) {
    val sH = 52.dp
    val topOffset = sH * (course.startSection - 1) + 4.dp
    val span = course.endSection - course.startSection + 1
    val blockHeight = sH * span - 4.dp
    Box(
        modifier = Modifier
            .padding(horizontal = 1.dp)
            .offset(y = topOffset)
            .height(blockHeight)
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(course.color.copy(alpha = 0.9f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                course.name,
                color = Color.White,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (blockHeight > 72.dp) {
                Text(
                    "@${course.room}",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 8.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun TextbookListView() {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(3) { i ->
            val books = listOf(
                Triple("高等数学（第七版）", "同济大学数学系 著", "¥ 39.80"),
                Triple("大学英语精读（第4册）", "董亚芬 主编", "¥ 28.50"),
                Triple("线性代数（第六版）", "同济大学数学系 著", "¥ 27.00"),
            )
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(books[i].first, style = MiuixTheme.textStyles.headline1)
                    Text(books[i].second, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantActions)
                    Text(books[i].third, style = MiuixTheme.textStyles.body1, color = MiuixTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
