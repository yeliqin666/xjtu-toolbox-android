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
import androidx.compose.runtime.remember
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
 * 分级布局的「今日」与「学期」两级。
 *
 * 「本周」那一级直接复用经典布局的周视图网格——二维对齐是它唯一不可替代的地方
 * （一眼看出哪段时间是空的），没有理由为了新布局再造一个。
 *
 * 这两级本身不含任何数据获取，课程、考试、教材都由 ScheduleScreen 传进来，
 * 和经典布局读的是同一份状态。布局开关换的只是摆法。
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
) {
    // 同一门课一周上两次会有两条记录，按课程号合并，周次取并集。
    val merged = remember(courses) {
        courses.groupBy { it.courseCode.ifBlank { it.courseName } }
            .values
            .map { group -> group.first() to group.flatMap { it.getWeeks() }.distinct().sorted() }
            .sortedBy { it.first.courseName }
    }
    if (merged.isEmpty()) {
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
        items(merged) { (course, weeks) ->
            val books = remember(course.courseName, textbooks) {
                CourseLinks.textbooksFor(course.courseName, textbooks)
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 14.dp,
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surface),
                onClick = { onCourseClick(course) },
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        course.courseName,
                        style = MiuixTheme.textStyles.body1,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    val line = listOfNotNull(
                        course.teacher.takeIf { it.isNotBlank() },
                        course.location.takeIf { it.isNotBlank() },
                        weeks.takeIf { it.isNotEmpty() }?.let { "${compactWeeks(it)}周" },
                    ).joinToString("  ·  ")
                    if (line.isNotBlank()) {
                        Spacer(Modifier.height(3.dp))
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
