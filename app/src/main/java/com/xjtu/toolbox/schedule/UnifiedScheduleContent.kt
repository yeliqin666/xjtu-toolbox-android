package com.xjtu.toolbox.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.ui.courseColor
import com.xjtu.toolbox.util.XjtuTime
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import java.time.LocalDate
import java.time.LocalTime

/**
 * 日程页三级里的「今日」与「学期」两级（「日程」那一级是周视图网格，直接在
 * ScheduleScreen.kt 里的 ScheduleTabContent，二维对齐是它唯一不可替代的地方，
 * 一眼看出哪段时间是空的）。
 *
 * 这两级本身不含任何数据获取，课程、考试、教材都由 ScheduleScreen 传进来。
 */

/**
 * 今日：课和考试合并成一条竖直时间轴。
 *
 * 考试和课在这里不分家。用户脑子里"今天几点要到哪去"是一件事，
 * 之所以在旧布局里分成两个 tab，只是因为它们来自两个接口——那是我们的实现细节，
 * 不该变成用户要自己合并的两张表。
 */
@Composable
fun TodayTimeline(
    courses: List<CourseItem>,
    exams: List<ExamItem>,
    today: LocalDate,
    allCourseNames: List<String>,
    onCourseClick: (CourseItem) -> Unit,
    bottomPadding: Dp = 0.dp,
) {
    val isSummer = remember(today) { XjtuTime.isSummerTime(today.monthValue) }
    val entries = remember(courses, exams, today) {
        val dow = today.dayOfWeek.value
        val fromCourses = courses.filter { it.dayOfWeek == dow }.map { c ->
            TimelineEntry(
                startMinute = c.startMinuteOfDay.takeIf { it >= 0 }
                    ?: XjtuTime.getClassTime(c.startSection, isSummer)?.start?.toMinuteOfDay()
                    ?: 0,
                endMinute = c.endMinuteOfDay.takeIf { it >= 0 }
                    ?: XjtuTime.getClassTime(c.endSection, isSummer)?.end?.toMinuteOfDay()
                    ?: 0,
                title = c.courseName,
                place = c.location,
                detail = c.teacher,
                isExam = false,
                course = c,
            )
        }
        val fromExams = exams
            .filter { ExamCountdown.parseDate(it.examDate) == today }
            .map { e ->
                TimelineEntry(
                    // 考试时间是 "14:30-16:30" 这类文本，解析不出就排到最后，
                    // 不猜时间——排错位置比排在末尾更误导人。
                    startMinute = parseExamStart(e.examTime) ?: 24 * 60,
                    endMinute = 0,
                    title = e.courseName,
                    place = e.location,
                    detail = listOfNotNull(
                        e.examTime.takeIf { it.isNotBlank() },
                        e.seatNumber.takeIf { it.isNotBlank() }?.let { "座位 $it" },
                    ).joinToString("  "),
                    isExam = true,
                    course = null,
                )
            }
        (fromCourses + fromExams).sortedBy { it.startMinute }
    }

    if (entries.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(bottom = bottomPadding), Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Outlined.EventAvailable, null, Modifier.size(56.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "今天没有安排",
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        return
    }

    val nowMinute = LocalTime.now().toMinuteOfDay()
    LazyColumn(
        Modifier.fillMaxSize().overScrollVertical(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp + bottomPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(entries) { e ->
            // 已经过去的条目压暗。今天这一级的价值就是"接下来干什么"，
            // 上午的课到了下午还跟没上过一样醒目，等于每次都要自己再筛一遍。
            val past = e.endMinute in 1 until nowMinute
            TimelineRow(e, past, allCourseNames, onCourseClick)
        }
    }
}

private data class TimelineEntry(
    val startMinute: Int,
    val endMinute: Int,
    val title: String,
    val place: String,
    val detail: String,
    val isExam: Boolean,
    val course: CourseItem?,
)

@Composable
private fun TimelineRow(
    e: TimelineEntry,
    past: Boolean,
    allCourseNames: List<String>,
    onCourseClick: (CourseItem) -> Unit,
) {
    val accent = when {
        e.isExam -> MiuixTheme.colorScheme.error
        else -> courseColor(e.title, allCourseNames)
    }
    val alpha = if (past) 0.45f else 1f
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        // 左侧时刻列固定宽度，让所有行的竖线和时间都对齐成一条轴。
        Column(
            Modifier.width(52.dp).padding(top = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (e.startMinute >= 24 * 60) "待定" else formatMinute(e.startMinute),
                style = MiuixTheme.textStyles.body2,
                fontWeight = FontWeight.Bold,
                color = accent.copy(alpha = alpha),
            )
            if (e.endMinute > 0) {
                Text(
                    formatMinute(e.endMinute),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = alpha),
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        Box(
            Modifier.padding(top = 14.dp).size(8.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = alpha)),
        )
        Spacer(Modifier.width(10.dp))
        Card(
            modifier = Modifier.weight(1f),
            cornerRadius = 12.dp,
            colors = CardDefaults.defaultColors(color = accent.copy(alpha = 0.12f * alpha)),
            onClick = { e.course?.let(onCourseClick) },
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (e.isExam) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MiuixTheme.colorScheme.error.copy(alpha = 0.9f),
                        ) {
                            Text(
                                "考试",
                                Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                style = MiuixTheme.textStyles.footnote2,
                                color = Color.White,
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        e.title,
                        style = MiuixTheme.textStyles.body1,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface.copy(alpha = alpha),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val sub = listOfNotNull(
                    e.place.takeIf { it.isNotBlank() },
                    e.detail.takeIf { it.isNotBlank() },
                ).joinToString("  ·  ")
                if (sub.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        sub,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = alpha),
                    )
                }
            }
        }
    }
}

/**
 * 学期：一门课一行，教材直接显示在行里。
 *
 * 这一级替掉了旧布局的「教材」tab。开学时"这学期都有什么课、要买哪些书"是一次性查询，
 * 值得有个地方一眼看完；但它是**课程的属性**，按课列而不是按书列才对得上人的问法。
 */
@Composable
fun SemesterCourseList(
    courses: List<CourseItem>,
    textbooks: List<TextbookItem>,
    onCourseClick: (CourseItem) -> Unit,
    bottomPadding: Dp = 0.dp,
    /** 整学期的考试。分级布局没有独立的「考试」页，它们排在课程列表前面。 */
    exams: List<ExamItem> = emptyList(),
) {
    // 同一门课一周上两次会有两条记录，按课程号合并，周次取并集。
    val merged = remember(courses) {
        courses.groupBy { it.courseCode.ifBlank { it.courseName } }
            .values
            .map { group ->
                SemesterRow(
                    // 临时换教室会让同一门课多出只上一两周的几条；代表行取上课周数最多的那条，
                    // 别让"第 5 周借用的教室"冒充这门课的教室。
                    course = group.maxBy { it.getWeeks().size },
                    weeks = group.flatMap { it.getWeeks() }.distinct().sorted(),
                    // 一门课一周可能上两次（周一 1-2 节、周三 3-4 节）。只取 first()
                    // 会把另一次悄悄丢掉，整学期视图里本来就该看得到全部时段。
                    slots = group.map { it.dayOfWeek to (it.startSection to it.endSection) }
                        .distinct()
                        .sortedWith(compareBy({ it.first }, { it.second.first })),
                )
            }
            .sortedBy { it.course.courseName }
    }
    // 排好序的考试：未结束在前、已结束折叠。与考试倒计时横幅点开的弹窗共用同一套排序，见 ExamList.kt。
    val examData = remember(exams) { sortExamsForList(exams) }
    var examsEndedExpanded by rememberSaveable { mutableStateOf(false) }

    if (merged.isEmpty() && examData.total == 0) {
        Box(Modifier.fillMaxSize().padding(bottom = bottomPadding), Alignment.Center) {
            Text(
                "本学期没有课程数据",
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize().overScrollVertical(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp + bottomPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (examData.total > 0) {
            // 全考完时不再报「还有 N 场」——那句话在这个场景下没有意义，
            // 直接就是那一行折叠的「已结束 N 场」。
            if (examData.active.isNotEmpty()) {
                item { SectionLabel("考试安排 · 还有 ${examData.active.size} 场") }
            }
            examListItems(examData, examsEndedExpanded, { examsEndedExpanded = !examsEndedExpanded })
            if (merged.isNotEmpty()) item { SectionLabel("本学期课程 · ${merged.size} 门") }
        }
        items(merged) { row ->
            val course = row.course
            val books = remember(course.courseName, course.courseCode, textbooks) {
                CourseLinks.textbooksFor(course.courseName, textbooks, course.courseCode)
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 14.dp,
                // 页面底色就是 surface，卡片再用 surface 就等于没有卡片——
                // 看上去是一堆字直接浮在背景上。全 app 的卡片都用 surfaceVariant。
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant),
                onClick = { onCourseClick(course) },
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        course.courseName,
                        style = MiuixTheme.textStyles.body1,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    // 第一行给上课时间——整学期视图里这是最常被问的（"这课周几"），
                    // 之前只有老师、地点、周次，恰恰缺了它。
                    val slotLine = row.slots.joinToString("，") { (dow, sec) ->
                        "周${DOW_NAMES.getOrElse(dow) { "?" }} ${sec.first}-${sec.second}节"
                    }
                    if (slotLine.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            slotLine,
                            style = MiuixTheme.textStyles.footnote1,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                    val line = listOfNotNull(
                        course.teacher.takeIf { it.isNotBlank() },
                        course.location.takeIf { it.isNotBlank() },
                        row.weeks.takeIf { it.isNotEmpty() }?.let { "${compactWeeks(it)}周" },
                    ).joinToString("  ·  ")
                    if (line.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            line,
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    if (books.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.EditCalendar, null, Modifier.size(14.dp),
                                tint = MiuixTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                books.joinToString("；") { it.textbookName },
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.primary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 学期一级的一行：一门课 + 它这学期的全部时段和周次。 */
private data class SemesterRow(
    val course: CourseItem,
    val weeks: List<Int>,
    /** (星期, (起始节, 结束节))，已按星期、节次排序。 */
    val slots: List<Pair<Int, Pair<Int, Int>>>,
)

private val DOW_NAMES = listOf("", "一", "二", "三", "四", "五", "六", "日")

/** 分组标题。列表里没有分隔时，考试和课程会糊成一片。 */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MiuixTheme.textStyles.footnote1,
        fontWeight = FontWeight.Bold,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp),
    )
}

// ── 小工具 ────────────────────────────────────────────────

private fun LocalTime.toMinuteOfDay() = hour * 60 + minute

private fun formatMinute(m: Int) = "%02d:%02d".format(m / 60, m % 60)

/** 考试时间是 "14:30-16:30" / "14:30~16:30" 这类文本，取开头的时刻。 */
private fun parseExamStart(raw: String): Int? =
    Regex("""(\d{1,2})[:：](\d{2})""").find(raw)?.let { m ->
        m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
    }

/** [1,2,3,5,7,8,9] → "1-3,5,7-9" */
private fun compactWeeks(weeks: List<Int>): String {
    if (weeks.isEmpty()) return ""
    val out = mutableListOf<String>()
    var s = weeks[0]
    var e = weeks[0]
    for (i in 1 until weeks.size) {
        if (weeks[i] == e + 1) e = weeks[i] else {
            out.add(if (s == e) "$s" else "$s-$e"); s = weeks[i]; e = weeks[i]
        }
    }
    out.add(if (s == e) "$s" else "$s-$e")
    return out.joinToString(",")
}
