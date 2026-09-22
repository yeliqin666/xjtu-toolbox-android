package com.xjtu.toolbox.schedule

import com.xjtu.toolbox.ui.components.enterOnce
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.drawBehind
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
import com.xjtu.toolbox.lms.LmsDue
import com.xjtu.toolbox.lms.remaining
import com.xjtu.toolbox.ui.courseColor
import com.xjtu.toolbox.util.XjtuTime
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 日程页三级里的「今日」与「学期」两级（「日程」那一级是周视图网格，直接在
 * ScheduleScreen.kt 里的 ScheduleTabContent，二维对齐是它唯一不可替代的地方，
 * 一眼看出哪段时间是空的）。
 *
 * 这两级本身不含任何数据获取，课程、考试、教材都由 ScheduleScreen 传进来。
 */

/**
 * 今日：课、考试、今天截止的作业合并成一条竖直时间轴，下面接一段「接下来」
 * （plan2 §5）：3 天内的考试、7 天内没提交的作业，按时间混排。
 *
 * 这几类在这里不分家。用户脑子里"今天几点要到哪去、接下来要忙什么"是一件事，
 * 之所以来自三个不同接口，只是我们的实现细节，不该变成用户要自己合并的几张表。
 */
@Composable
fun TodayTimeline(
    courses: List<CourseItem>,
    exams: List<ExamItem>,
    today: LocalDate,
    allCourseNames: List<String>,
    onCourseClick: (CourseItem) -> Unit,
    bottomPadding: Dp = 0.dp,
    /** 顶部留白，加在列表内容里（不是列表外面）：日程页顶栏是玻璃时，内容要从它下面滚过去。 */
    topPadding: Dp = 0.dp,
    /** 3 天内的考试 + 7 天内没提交的作业，见 [buildUpcoming]。 */
    upcoming: List<UpcomingItem> = emptyList(),
    /** 今天截止的作业，插进时间轴对应的时刻（不是「接下来」——那是给以后的）。 */
    todayHomework: List<LmsDue> = emptyList(),
) {
    val isSummer = remember(today) { XjtuTime.isSummerTime(today.monthValue) }
    val entries = remember(courses, exams, today, todayHomework) {
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
                kind = EntryKind.COURSE,
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
                    kind = EntryKind.EXAM,
                    course = null,
                )
            }
        val fromHomework = todayHomework
            .filter { !it.submitted }
            .mapNotNull { hw ->
                val deadline = runCatching { Instant.parse(hw.deadline) }.getOrNull() ?: return@mapNotNull null
                val zoned = deadline.atZone(ZoneId.systemDefault())
                if (zoned.toLocalDate() != today) return@mapNotNull null
                TimelineEntry(
                    startMinute = zoned.hour * 60 + zoned.minute,
                    endMinute = 0,
                    title = "[${hw.courseName}] ${hw.title}",
                    place = "",
                    detail = "%02d:%02d 截止".format(zoned.hour, zoned.minute),
                    kind = EntryKind.HOMEWORK,
                    course = null,
                )
            }
        (fromCourses + fromExams + fromHomework).sortedBy { it.startMinute }
    }

    // 两边都没内容才是真的空——只要「接下来」有东西，就不该用一整页空状态盖住它。
    if (entries.isEmpty() && upcoming.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(top = topPadding, bottom = bottomPadding), Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Outlined.EventAvailable, null, Modifier.size(56.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "今天一节课都没有",
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        return
    }

    // 每到整分钟走一次：正在上的课的进度、过去的课变淡都跟着它，不用重进页面才更新
    val nowMinute by androidx.compose.runtime.produceState(LocalTime.now().toMinuteOfDay()) {
        while (true) {
            kotlinx.coroutines.delay((60 - LocalTime.now().second) * 1000L)
            value = LocalTime.now().toMinuteOfDay()
        }
    }
    LazyColumn(
        Modifier.fillMaxSize().overScrollVertical(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = 8.dp + topPadding, bottom = 12.dp + bottomPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (entries.isEmpty()) {
            // 今天没课也没考试没作业，但「接下来」有内容——给个小卡片交代一下，
            // 别让页面看着像没加载出来。
            item { Box(Modifier.enterOnce(0)) { NoCourseTodayCard() } }
        } else {
            itemsIndexed(entries) { i, e ->
                // 已经过去的条目压暗。今天这一级的价值就是"接下来干什么"，
                // 上午的课到了下午还跟没上过一样醒目，等于每次都要自己再筛一遍。
                val past = e.endMinute in 1 until nowMinute
                val progress = if (e.endMinute > e.startMinute && nowMinute in e.startMinute until e.endMinute)
                    (nowMinute - e.startMinute).toFloat() / (e.endMinute - e.startMinute) else null
                Box(Modifier.enterOnce(i)) {
                    TimelineRow(e, past, allCourseNames, onCourseClick, progress)
                }
            }
        }
        if (upcoming.isNotEmpty()) {
            val base = entries.size.coerceAtLeast(1)
            item { Box(Modifier.enterOnce(base)) { SectionLabel("接下来") } }
            itemsIndexed(upcoming) { i, item -> Box(Modifier.enterOnce(base + 1 + i)) { UpcomingRow(item) } }
        }
    }
}

private enum class EntryKind { COURSE, EXAM, HOMEWORK }

private data class TimelineEntry(
    val startMinute: Int,
    val endMinute: Int,
    val title: String,
    val place: String,
    val detail: String,
    val kind: EntryKind,
    val course: CourseItem?,
)

@Composable
private fun NoCourseTodayCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MiuixTheme.colorScheme.surfaceVariant,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.EventAvailable, null, Modifier.size(18.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "今天没课，后面几天的在下面",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun TimelineRow(
    e: TimelineEntry,
    past: Boolean,
    allCourseNames: List<String>,
    onCourseClick: (CourseItem) -> Unit,
    /** 正在进行时是已过去的比例（0~1），否则 null。 */
    progress: Float? = null,
) {
    val accent = when (e.kind) {
        EntryKind.EXAM -> MiuixTheme.colorScheme.error
        EntryKind.HOMEWORK -> MiuixTheme.colorScheme.primary
        EntryKind.COURSE -> courseColor(e.title, allCourseNames)
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
            // 正在上的课：卡底从左到右铺一层更深的颜色表示已上的部分，进场时长出来
            val grown = remember { androidx.compose.animation.core.Animatable(0f) }
            LaunchedEffect(progress) {
                grown.animateTo(progress ?: 0f, androidx.compose.animation.core.tween(700))
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        if (progress != null) {
                            drawRect(accent.copy(alpha = 0.14f), size = size.copy(width = size.width * grown.value))
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val badge = when (e.kind) {
                        EntryKind.EXAM -> "考试"
                        EntryKind.HOMEWORK -> "作业"
                        EntryKind.COURSE -> null
                    }
                    if (badge != null) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = accent.copy(alpha = 0.9f),
                        ) {
                            Text(
                                badge,
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

/** 「接下来」一行：考试或作业，比今天更晚一点。 */
enum class UpcomingKind { EXAM, HOMEWORK }

data class UpcomingItem(
    val kind: UpcomingKind,
    val title: String,
    val subtitle: String,
    /** 排序用，不展示。 */
    val at: Instant,
)

/**
 * 「接下来」的构建：3 天内的考试（今天的已经在时间轴本体里了，不重复）+
 * 7 天内没提交、且不是今天截止的作业（今天截止的同样已经在时间轴里）。
 * 两类按时间混排。
 *
 * 纯函数，不碰 Context，方便单测（plan2 §5.4）。
 */
fun buildUpcoming(
    exams: List<ExamItem>,
    homework: List<LmsDue>,
    now: LocalDateTime = LocalDateTime.now(),
): List<UpcomingItem> {
    val today = now.toLocalDate()
    val zone = ZoneId.systemDefault()
    val examItems = exams
        .filter { ExamCountdown.phaseOf(it, now) == ExamCountdown.ExamPhase.SOON }
        .mapNotNull { e ->
            val date = ExamCountdown.parseDate(e.examDate) ?: return@mapNotNull null
            val days = ChronoUnit.DAYS.between(today, date).toInt()
            val time = ExamCountdown.startTimeOf(e)
            val dow = DOW_NAMES.getOrElse(date.dayOfWeek.value) { "" }
            val whenText = "周$dow" + (time?.let { " %02d:%02d".format(it.hour, it.minute) } ?: "")
            val subtitle = listOfNotNull(whenText, e.location.takeIf { it.isNotBlank() }).joinToString(" · ")
            UpcomingItem(
                kind = UpcomingKind.EXAM,
                title = e.courseName,
                subtitle = "还有 $days 天 · $subtitle",
                at = date.atTime(time ?: java.time.LocalTime.MAX).atZone(zone).toInstant(),
            )
        }
    val nowInstant = now.atZone(zone).toInstant()
    val horizon = nowInstant.plus(Duration.ofDays(7))
    val homeworkItems = homework
        .filter { !it.submitted }
        .mapNotNull { hw ->
            val deadline = runCatching { Instant.parse(hw.deadline) }.getOrNull() ?: return@mapNotNull null
            if (deadline.isBefore(nowInstant) || deadline.isAfter(horizon)) return@mapNotNull null
            // 今天截止的已经作为时间轴条目出现，这里不重复。
            if (deadline.atZone(zone).toLocalDate() == today) return@mapNotNull null
            UpcomingItem(
                kind = UpcomingKind.HOMEWORK,
                title = "[${hw.courseName}] ${hw.title}",
                subtitle = "还${remaining(nowInstant, deadline)}",
                at = deadline,
            )
        }
    return (examItems + homeworkItems).sortedBy { it.at }
}

@Composable
private fun UpcomingRow(item: UpcomingItem) {
    val accent = when (item.kind) {
        UpcomingKind.EXAM -> MiuixTheme.colorScheme.error
        UpcomingKind.HOMEWORK -> MiuixTheme.colorScheme.primary
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 12.dp,
        colors = CardDefaults.defaultColors(color = accent.copy(alpha = 0.10f)),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                item.title,
                style = MiuixTheme.textStyles.body2,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(item.subtitle, style = MiuixTheme.textStyles.footnote1, color = accent)
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
    /** 顶部留白，加在列表内容里（不是列表外面）：日程页顶栏是玻璃时，内容要从它下面滚过去。 */
    topPadding: Dp = 0.dp,
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
        Box(Modifier.fillMaxSize().padding(top = topPadding, bottom = bottomPadding), Alignment.Center) {
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
            start = 16.dp, end = 16.dp, top = 8.dp + topPadding, bottom = 12.dp + bottomPadding,
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

/** 宽屏「今日」栏右边要默认展开的那节课：正在上的，或者今天接下来最近的一节。 */
internal data class FocusCourse(val course: CourseItem, val ongoing: Boolean)

/**
 * 从本周的课里挑出今天「正在上」或「下一节」。今天的课都上完了、或者今天没课，返回 null。
 * 起止时间的算法和今日时间轴（[TodayTimeline]）完全一样：优先用课表给的分钟数，没有就按节次换算，
 * 冬夏作息都算上。两处算出来的时间必须一致，否则右栏说「正在上」、左栏却已经把它压暗了。
 */
internal fun focusCourseOf(weekCourses: List<CourseItem>, today: LocalDate, now: LocalTime): FocusCourse? {
    val isSummer = XjtuTime.isSummerTime(today.monthValue)
    val nowMinute = now.toMinuteOfDay()
    val todays = weekCourses
        .filter { it.dayOfWeek == today.dayOfWeek.value }
        .map { c ->
            val start = c.startMinuteOfDay.takeIf { it >= 0 }
                ?: XjtuTime.getClassTime(c.startSection, isSummer)?.start?.toMinuteOfDay()
                ?: return@map null
            val end = c.endMinuteOfDay.takeIf { it >= 0 }
                ?: XjtuTime.getClassTime(c.endSection, isSummer)?.end?.toMinuteOfDay()
                ?: start
            Triple(c, start, end)
        }
        .filterNotNull()
        .sortedBy { it.second }
    todays.firstOrNull { nowMinute in it.second until it.third }?.let { return FocusCourse(it.first, ongoing = true) }
    return todays.firstOrNull { it.second > nowMinute }?.let { FocusCourse(it.first, ongoing = false) }
}

/**
 * 宽屏「今日」栏右边在今天没有（剩余）课时显示的本周概览：这周还剩几节、哪天最满、每天几节。
 * 左边已经是今天的时间轴和「接下来」，这里换个尺度，回答「这周还要忙多少」。
 */
@Composable
internal fun WeekGlance(courses: List<CourseItem>, today: LocalDate) {
    val todayDow = today.dayOfWeek.value
    val perDay = remember(courses) { (1..7).map { d -> courses.count { it.dayOfWeek == d } } }
    val remaining = perDay.withIndex().filter { it.index + 1 > todayDow }.sumOf { it.value }
    val busiest = perDay.withIndex().maxByOrNull { it.value }?.takeIf { it.value > 0 }
    val dayNames = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text("本周", style = MiuixTheme.textStyles.subtitle, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            when {
                courses.isEmpty() -> "这周没有课"
                remaining == 0 -> "今天之后这周没有课了"
                else -> "今天之后还有 $remaining 节课" + (busiest?.let { "，${dayNames[it.index]}最满（${it.value} 节）" } ?: "")
            },
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(12.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.defaultColors(color = com.xjtu.toolbox.ui.components.AppCardColor),
        ) {
            Column(Modifier.padding(vertical = 6.dp)) {
                perDay.forEachIndexed { i, n ->
                    val isToday = i + 1 == todayDow
                    val past = i + 1 < todayDow
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            dayNames[i] + if (isToday) " · 今天" else "",
                            style = MiuixTheme.textStyles.body2,
                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                            color = when {
                                isToday -> MiuixTheme.colorScheme.primary
                                past -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                                else -> MiuixTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            if (n == 0) "没课" else "$n 节",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }
    }
}
