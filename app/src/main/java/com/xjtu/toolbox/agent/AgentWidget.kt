package com.xjtu.toolbox.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.AssignmentTurnedIn
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.EventSeat
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import com.xjtu.toolbox.ui.components.AppCardColor
import com.xjtu.toolbox.ui.theme.LocalIsDarkTheme
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleClip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.gson.Gson
import com.xjtu.toolbox.attendance.AttendanceWaterRecord
import com.xjtu.toolbox.attendance.WaterType
import com.xjtu.toolbox.card.CardInfo
import com.xjtu.toolbox.emptyroom.RoomInfo
import com.xjtu.toolbox.schedule.CourseItem
import com.xjtu.toolbox.schedule.ExamItem
import com.xjtu.toolbox.score.ReportedGrade
import com.xjtu.toolbox.schedule.XjtuTime
import top.yukonga.miuix.kmp.basic.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.xjtu.toolbox.nav.AppRoute

/**
 * Agent 回复可携带的富控件。工具执行时由 [AgentToolRegistry] 产出结构化数据，
 * UI 端 [AgentWidgetView] 用项目内已有视觉语言渲染，**不依赖大模型吐标记**。
 *
 * 设计：全部是只读展示卡片，数据直接复用各功能模块的公开 data class，避免重复建模。
 */
sealed interface AgentWidget

/** 课表：courses 已按目标日期/本周过滤好，控件内部按星期分组展示。 */
data class ScheduleWidget(val title: String, val courses: List<CourseItem>) : AgentWidget

/** 考试安排。 */
data class ExamWidget(val exams: List<ExamItem>) : AgentWidget

/** 空闲教室。currentPeriod 为 0 基节次索引，-1 表示无"当前节次"语境。 */
data class RoomWidget(val condition: String, val rooms: List<RoomInfo>, val currentPeriod: Int) : AgentWidget

/**
 * 空闲教室的实时状态（智慧教室平台）：只放空闲和"没排课但有人"的教室。
 * 类名是存盘判别式，字段名受 proguard 保护，改名要同步 proguard-rules.pro。
 */
data class LiveRoomWidget(
    val condition: String,
    val rooms: List<com.xjtu.toolbox.emptyroom.LiveRoom>,
    val fetchedAt: Long,
) : AgentWidget

/** 考勤记录。 */
data class AttendanceWidget(val records: List<AttendanceWaterRecord>) : AgentWidget

/** 成绩 + 加权 GPA 汇总。 */
data class GradeWidget(val grades: List<ReportedGrade>, val gpa: Double?, val totalPoints: Double) : AgentWidget

/** 校园卡信息。 */
data class CardWidget(val info: CardInfo) : AgentWidget

/**
 * 仲英学辅资料站的检索结果。
 *
 * 和其它卡片不同，这张是**可操作**的：点文件就下载，点目录就让屁岱继续往里翻。
 * 检索结果天然是"给你一串候选，你挑一个"，纯文本把文件名和 ID 抄一遍再让用户
 * 复述给屁岱，中间那几步毫无意义。
 */
data class ZyxfWidget(val query: String, val items: List<ZyxfEntryRef>) : AgentWidget

/** 卡片里的一条。字段全是可序列化的原始类型，卡片要随会话一起存盘。 */
data class ZyxfEntryRef(
    val id: Int,
    val name: String,
    val path: String,
    val sizeText: String,
    val isFolder: Boolean,
) {
    /** 磁盘缓存反序列化兜底，原理见 [com.xjtu.toolbox.schedule.CourseItem.sanitized]。 */
    fun sanitized(): ZyxfEntryRef = copy(
        name = (name as String?) ?: "",
        path = (path as String?) ?: "",
        sizeText = (sizeText as String?) ?: "",
    )
}

/**
 * 图书馆某区域的平面图缩略图：底图来自平面图磁盘缓存（工具执行时已落盘），
 * 有人的座位压暗，空座就亮出来。点一下打开图书馆页并定位到这个区域。
 */
data class LibraryWidget(
    val campusId: String,
    val campusName: String,
    val areaCode: String,
    val areaName: String,
    val imageName: String,
    val seats: List<com.xjtu.toolbox.library.PlanSeat>,
) : AgentWidget

fun AgentWidget.toStored(gson: Gson): StoredWidget =
    StoredWidget(javaClass.simpleName, gson.toJson(this))

/**
 * 会话记录随磁盘缓存整体落盘，旧版本/半截写入的会话一样会踩 Gson 非空约束不生效的坑
 * （原理见 [com.xjtu.toolbox.schedule.CourseItem.sanitized]）。这些控件直接在
 * [AgentWidgetView] 的 Composable 里渲染，没有 try/catch，反序列化后必须就地兜底，
 * 不能指望各个 *WidgetView 自己判空。
 */
fun storedToWidget(stored: StoredWidget, gson: Gson): AgentWidget? = runCatching {
    when (stored.type) {
        "ScheduleWidget" -> gson.fromJson(stored.json, ScheduleWidget::class.java)?.let { w ->
            w.copy(
                title = (w.title as String?) ?: "",
                courses = (w.courses as List<CourseItem>?)?.map { it.sanitized() } ?: emptyList(),
            )
        }
        "ExamWidget" -> gson.fromJson(stored.json, ExamWidget::class.java)?.let { w ->
            w.copy(exams = (w.exams as List<ExamItem>?)?.map { it.sanitized() } ?: emptyList())
        }
        "RoomWidget" -> gson.fromJson(stored.json, RoomWidget::class.java)?.let { w ->
            w.copy(
                condition = (w.condition as String?) ?: "",
                rooms = (w.rooms as List<RoomInfo>?)?.map { it.sanitized() } ?: emptyList(),
            )
        }
        "LiveRoomWidget" -> gson.fromJson(stored.json, LiveRoomWidget::class.java)?.let { w ->
            w.copy(
                condition = (w.condition as String?) ?: "",
                rooms = (w.rooms as List<com.xjtu.toolbox.emptyroom.LiveRoom?>?)?.filterNotNull()?.map { it.sanitized() } ?: emptyList(),
            )
        }
        "AttendanceWidget" -> gson.fromJson(stored.json, AttendanceWidget::class.java)?.let { w ->
            w.copy(records = (w.records as List<AttendanceWaterRecord>?)?.map { it.sanitized() } ?: emptyList())
        }
        "GradeWidget" -> gson.fromJson(stored.json, GradeWidget::class.java)?.let { w ->
            w.copy(grades = (w.grades as List<ReportedGrade>?)?.map { it.sanitized() } ?: emptyList())
        }
        "CardWidget" -> gson.fromJson(stored.json, CardWidget::class.java)?.let { w ->
            (w.info as CardInfo?)?.let { w.copy(info = it.sanitized()) }
        }
        "ZyxfWidget" -> gson.fromJson(stored.json, ZyxfWidget::class.java)?.let { w ->
            w.copy(
                query = (w.query as String?) ?: "",
                items = (w.items as List<ZyxfEntryRef>?)?.map { it.sanitized() } ?: emptyList(),
            )
        }
        "LibraryWidget" -> gson.fromJson(stored.json, LibraryWidget::class.java)?.let { w ->
            w.copy(
                campusId = (w.campusId as String?) ?: "",
                campusName = (w.campusName as String?) ?: "",
                areaCode = (w.areaCode as String?) ?: "",
                areaName = (w.areaName as String?) ?: "",
                imageName = (w.imageName as String?) ?: "",
                seats = (w.seats as List<com.xjtu.toolbox.library.PlanSeat?>?)?.filterNotNull() ?: emptyList(),
            )
        }
        else -> null
    }
}.getOrNull()

private val DAY_NAMES = listOf("", "周一", "周二", "周三", "周四", "周五", "周六", "周日")

// ── 渲染入口 ─────────────────────────────────────────────────────────────

/**
 * @param onAsk 卡片想替用户问屁岱一句（比如点目录 → "打开目录 12"）。
 *              默认丢弃：历史消息重绘时没有可用的输入通道。
 */
@Composable
fun AgentWidgetView(
    widget: AgentWidget,
    modifier: Modifier = Modifier,
    onAsk: (String) -> Unit = {},
    onNavigate: (AppRoute) -> Unit = {},
) {
    when (widget) {
        is LibraryWidget    -> LibraryWidgetView(widget, modifier, onNavigate)
        is ScheduleWidget   -> ScheduleWidgetView(widget, modifier)
        is ExamWidget       -> ExamWidgetView(widget, modifier)
        is RoomWidget       -> RoomWidgetView(widget, modifier)
        is LiveRoomWidget   -> LiveRoomWidgetView(widget, modifier)
        is AttendanceWidget -> AttendanceWidgetView(widget, modifier)
        is GradeWidget      -> GradeWidgetView(widget, modifier)
        is CardWidget       -> CardWidgetView(widget, modifier)
        is ZyxfWidget       -> ZyxfWidgetView(widget, modifier, onAsk)
    }
}

/**
 * 所有数据卡片共用的外壳：超椭圆面 + 图标徽记 + 标题/副标题，和首页分类卡同一套语言。
 *
 * 以前是「左侧 4dp 色条 + 标题 + 胶囊 + 分割线」，每张卡都像网页上的一个区块；
 * 现在层级靠面和字重，不靠线条。[trailing] 放这张卡最想让人一眼看到的那个数（GPA、场次）。
 */
@Composable
private fun WidgetCard(
    title: String,
    icon: ImageVector,
    accent: Color,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val dark = LocalIsDarkTheme.current
    Column(
        modifier
            .fillMaxWidth()
            .squircleClip(WIDGET_RADIUS)
            .background(AppCardColor)
            .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(32.dp)
                    .squircleBackground(accent.copy(alpha = if (dark) 0.24f else 0.12f), 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            trailing?.let {
                Spacer(Modifier.width(8.dp))
                it()
            }
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}

private val WIDGET_RADIUS = 22.dp

/** 卡片里的小节标题（如按星期分组）。 */
@Composable
private fun WidgetGroupLabel(text: String) {
    Text(
        text,
        style = MiuixTheme.textStyles.footnote2,
        fontWeight = FontWeight.Bold,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(start = 2.dp, top = 6.dp, bottom = 2.dp),
    )
}

/** 列表条目：可选前导块、主副两行、可选尾部。条目之间不画线，靠留白分隔。 */
@Composable
private fun WidgetRow(
    primary: String,
    secondary: String?,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.let {
            it()
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                primary,
                style = MiuixTheme.textStyles.body2,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            secondary?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(1.dp))
                Text(
                    it,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.let {
            Spacer(Modifier.width(10.dp))
            it()
        }
    }
}

/** 尾部的小徽标：淡色底 + 同色字，用于分数、状态、倒计时。 */
@Composable
private fun WidgetBadge(text: String, color: Color) {
    val dark = LocalIsDarkTheme.current
    Box(
        Modifier
            .squircleBackground(color.copy(alpha = if (dark) 0.22f else 0.11f), 9.dp)
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        Text(text, style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
    }
}

@Composable
private fun WidgetMore(text: String) {
    Text(
        text,
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(start = 2.dp, top = 4.dp, bottom = 4.dp),
    )
}

// ── 各控件 ───────────────────────────────────────────────────────────────

/** 课程的开始 / 结束钟点：自建日程有分钟级时间就用它，否则按节次和当天所在月份的作息推。 */
private fun courseClock(c: CourseItem): Pair<String, String> {
    fun fmt(m: Int) = "%02d:%02d".format(m / 60, m % 60)
    val start = c.startMinuteOfDay.takeIf { it >= 0 }?.let(::fmt)
        ?: XjtuTime.getClassTime(c.startSection)?.start?.let { "%02d:%02d".format(it.hour, it.minute) }
        ?: "第${c.startSection}节"
    val end = c.endMinuteOfDay.takeIf { it >= 0 }?.let(::fmt)
        ?: XjtuTime.getClassTime(c.endSection)?.end?.let { "%02d:%02d".format(it.hour, it.minute) }
        ?: "第${c.endSection}节"
    return start to end
}

@Composable
private fun ScheduleWidgetView(w: ScheduleWidget, modifier: Modifier) {
    val sorted = w.courses.sortedWith(compareBy({ it.dayOfWeek }, { it.startSection }, { it.startMinuteOfDay }))
    val byDay = sorted.groupBy { it.dayOfWeek }
    val names = remember(w.courses) { w.courses.map { it.courseName }.distinct() }
    WidgetCard(
        title = w.title,
        icon = Icons.Default.CalendarMonth,
        accent = MiuixTheme.colorScheme.primary,
        subtitle = if (w.courses.isEmpty()) "没有安排" else "${w.courses.size} 项安排",
        modifier = modifier,
    ) {
        if (w.courses.isEmpty()) return@WidgetCard
        byDay.entries.forEach { (day, list) ->
            if (byDay.size > 1) WidgetGroupLabel(DAY_NAMES.getOrElse(day) { "" })
            list.forEach { c ->
                val (start, end) = courseClock(c)
                val color = com.xjtu.toolbox.schedule.courseColor(c.courseName, names)
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 5.dp).height(IntrinsicSize.Min),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.width(44.dp), horizontalAlignment = Alignment.End) {
                        Text(start, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Bold)
                        Text(end, style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier
                            .width(4.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(2.dp))
                            .background(color)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.courseName, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sub = listOf(c.location, c.teacher).filter { it.isNotBlank() }.joinToString(" · ")
                        if (sub.isNotBlank()) {
                            Text(sub, style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/** 考试日期块：上面月份、下面日子，和日历 App 的日期图标一个意思。 */
@Composable
private fun DateBlock(date: java.time.LocalDate?, accent: Color) {
    val dark = LocalIsDarkTheme.current
    Column(
        Modifier
            .size(42.dp)
            .squircleBackground(accent.copy(alpha = if (dark) 0.22f else 0.10f), 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (date == null) {
            Text("待定", style = MiuixTheme.textStyles.footnote2, color = accent, fontWeight = FontWeight.Bold)
        } else {
            Text("${date.monthValue}月", style = MiuixTheme.textStyles.footnote2, color = accent)
            Text("${date.dayOfMonth}", style = MiuixTheme.textStyles.body1, color = accent, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ExamWidgetView(w: ExamWidget, modifier: Modifier) {
    val accent = MiuixTheme.colorScheme.primary
    val today = java.time.LocalDate.now()
    WidgetCard(
        title = "考试安排",
        icon = Icons.AutoMirrored.Filled.EventNote,
        accent = accent,
        subtitle = "${w.exams.size} 场",
        modifier = modifier,
    ) {
        w.exams.take(12).forEach { e ->
            val date = runCatching { java.time.LocalDate.parse(e.examDate.take(10)) }.getOrNull()
            val days = date?.let { java.time.temporal.ChronoUnit.DAYS.between(today, it) }
            WidgetRow(
                primary = e.courseName,
                secondary = listOf(e.examTime, e.location, e.seatNumber.takeIf { it.isNotBlank() }?.let { "座位 $it" })
                    .filter { !it.isNullOrBlank() }.joinToString(" · "),
                leading = { DateBlock(date, accent) },
                trailing = when {
                    days == null -> null
                    days < 0 -> ({ WidgetBadge("已结束", MiuixTheme.colorScheme.onSurfaceVariantSummary) })
                    days == 0L -> ({ WidgetBadge("今天", MiuixTheme.colorScheme.error) })
                    days <= 7 -> ({ WidgetBadge("${days} 天", MiuixTheme.colorScheme.error) })
                    else -> ({ WidgetBadge("${days} 天", accent) })
                },
            )
        }
        if (w.exams.size > 12) WidgetMore("…还有 ${w.exams.size - 12} 场")
    }
}

@Composable
private fun RoomWidgetView(w: RoomWidget, modifier: Modifier) {
    val accent = MiuixTheme.colorScheme.primary
    val shown = w.rooms.take(12)
    WidgetCard(
        title = "空闲教室",
        icon = Icons.Default.MeetingRoom,
        accent = accent,
        subtitle = w.condition,
        modifier = modifier,
    ) {
        shown.forEach { r ->
            val nowFree = w.currentPeriod in r.status.indices && r.status[w.currentPeriod] == 0
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.name, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (nowFree) {
                        WidgetBadge("本节空闲", accent)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text("${r.size} 座", style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                if (r.status.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    // 和空闲教室页同一条节次条（一次画完、进场依次亮起）
                    com.xjtu.toolbox.ui.components.SlotStripe(
                        free = r.status.map { it == 0 },
                        freeColor = accent,
                        currentIndex = w.currentPeriod,
                        busyColor = MiuixTheme.colorScheme.outline.copy(alpha = 0.16f),
                    )
                }
            }
        }
        if (w.rooms.size > shown.size) WidgetMore("…还有 ${w.rooms.size - shown.size} 间")
    }
}

@Composable
private fun LiveRoomWidgetView(w: LiveRoomWidget, modifier: Modifier) {
    val accent = MiuixTheme.colorScheme.primary
    // 和空闲教室页的"其它使用"同一个琥珀色
    val inUseColor = Color(0xFFD9822B)
    val shown = w.rooms.take(12)
    WidgetCard(
        title = "空闲教室",
        icon = Icons.Default.MeetingRoom,
        accent = accent,
        subtitle = w.condition,
        modifier = modifier,
    ) {
        if (w.rooms.isEmpty()) WidgetMore("此刻没有空闲或其它使用的教室")
        shown.forEach { r ->
            WidgetRow(
                primary = r.name,
                secondary = if (r.isInUse) "其它使用 · ${r.seats} 座" else "${r.seats} 座",
                trailing = {
                    if (r.isInUse) WidgetBadge("${r.people} 人", inUseColor)
                    else WidgetBadge("空闲", accent)
                },
            )
        }
        if (w.rooms.size > shown.size) WidgetMore("…还有 ${w.rooms.size - shown.size} 间")
    }
}

@Composable
private fun AttendanceWidgetView(w: AttendanceWidget, modifier: Modifier) {
    val normal = w.records.count { it.status == WaterType.NORMAL }
    WidgetCard(
        title = "考勤记录",
        icon = Icons.Default.AssignmentTurnedIn,
        accent = MiuixTheme.colorScheme.primary,
        subtitle = if (w.records.isEmpty()) null else "正常 $normal / ${w.records.size}",
        modifier = modifier,
    ) {
        w.records.forEach { r ->
            val color = when (r.status) {
                WaterType.NORMAL -> STATUS_OK
                WaterType.LEAVE -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                else -> MiuixTheme.colorScheme.error
            }
            WidgetRow(
                primary = r.courseName,
                secondary = "${r.date} 第${r.startTime}-${r.endTime}节" +
                    if (r.location.isNotBlank()) " · ${r.location}" else "",
                trailing = { WidgetBadge(r.status.displayName, color) },
            )
        }
    }
}

@Composable
private fun GradeWidgetView(w: GradeWidget, modifier: Modifier) {
    val accent = MiuixTheme.colorScheme.primary
    WidgetCard(
        title = "成绩",
        icon = Icons.Default.Assessment,
        accent = accent,
        subtitle = "${w.grades.size} 门 · 计入学分 ${"%.1f".format(w.totalPoints)}",
        trailing = w.gpa?.let { gpa ->
            {
                Column(horizontalAlignment = Alignment.End) {
                    Text("%.2f".format(gpa), style = MiuixTheme.textStyles.title4,
                        fontWeight = FontWeight.Bold, color = accent)
                    Text("加权 GPA", style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        },
        modifier = modifier,
    ) {
        w.grades.take(15).forEach { g ->
            val n = g.score.toDoubleOrNull()
            val color = when {
                n != null && n < 60 || g.score.contains("不及格") -> MiuixTheme.colorScheme.error
                n != null && n >= 90 || g.score == "优秀" -> STATUS_OK
                else -> accent
            }
            WidgetRow(
                primary = g.courseName,
                secondary = "${g.coursePoint} 学分" + (g.gpa?.let { " · 绩点 %.2f".format(it) } ?: ""),
                trailing = { WidgetBadge(g.score, color) },
            )
        }
        if (w.grades.size > 15) WidgetMore("…还有 ${w.grades.size - 15} 门")
    }
}

/**
 * 校园卡做成一张「卡」：主题色渐变的卡面，余额大字压在上面。
 * 这是唯一一张不走 [WidgetCard] 外壳的：它本身就是实物卡片的样子，再套一层反而像截图。
 */
@Composable
private fun CardWidgetView(w: CardWidget, modifier: Modifier) {
    val info = w.info
    val primary = MiuixTheme.colorScheme.primary
    val deep = androidx.compose.ui.graphics.lerp(primary, Color.Black, 0.28f)
    val light = androidx.compose.ui.graphics.lerp(primary, Color.White, 0.12f)
    val onCard = Color.White
    Column(
        modifier
            .fillMaxWidth()
            .squircleClip(WIDGET_RADIUS)
            .background(Brush.linearGradient(listOf(light, primary, deep)))
            .drawBehind {
                // 右上角一圈淡光，卡面才有质感
                drawCircle(
                    Brush.radialGradient(
                        listOf(Color.White.copy(alpha = 0.22f), Color.Transparent),
                        center = androidx.compose.ui.geometry.Offset(size.width, 0f),
                        radius = size.width * 0.6f,
                    ),
                    radius = size.width * 0.6f,
                    center = androidx.compose.ui.geometry.Offset(size.width, 0f),
                )
            }
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CreditCard, contentDescription = null, tint = onCard, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("校园卡", style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Bold, color = onCard,
                modifier = Modifier.weight(1f))
            info.cardType.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MiuixTheme.textStyles.footnote1, color = onCard.copy(alpha = 0.8f))
            }
        }
        Spacer(Modifier.height(22.dp))
        Text("余额", style = MiuixTheme.textStyles.footnote1, color = onCard.copy(alpha = 0.75f))
        Text(
            "¥%.2f".format(info.balance),
            style = MiuixTheme.textStyles.title2,
            fontWeight = FontWeight.Bold,
            color = onCard,
        )
        val notes = buildList {
            if (info.pendingAmount > 0) add("待入账 ¥%.2f".format(info.pendingAmount))
            if (info.lostFlag) add("已挂失")
            if (info.frozenFlag) add("已冻结")
        }
        if (notes.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(notes.joinToString(" · "), style = MiuixTheme.textStyles.footnote1,
                fontWeight = if (info.lostFlag || info.frozenFlag) FontWeight.Bold else FontWeight.Normal,
                color = onCard.copy(alpha = 0.9f))
        }
    }
}

private val STATUS_OK = Color(0xFF2E9D5A)


// ── 仲英学辅资料卡 ───────────────────────────────────────────────────────

private const val ZYXF_DOWNLOADING = "下载中…"

/**
 * 资料检索结果卡。
 *
 * 下载直接在卡片里做，不经过屁岱再转一轮：模型手上没有 Context，也不该由它替用户
 * 决定"要不要往手机里写文件"。点一下就开始下，结果标在这一行上。
 * 点目录则把"继续翻这一层"交回给屁岱（[onAsk]），因为那本来就是它该接着做的事。
 */
@Composable
private fun ZyxfWidgetView(w: ZyxfWidget, modifier: Modifier, onAsk: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // 每行的下载状态，key 是文件 ID；滚动离屏再回来也不会丢。
    val states = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateMapOf<Int, String>()
    }
    val shown = w.items.take(12)
    val accent = MiuixTheme.colorScheme.primary
    val dark = LocalIsDarkTheme.current

    WidgetCard(
        title = "仲英学辅资料",
        icon = Icons.Default.FolderOpen,
        accent = accent,
        subtitle = w.query.takeIf { it.isNotBlank() }?.let { "「$it」" },
        modifier = modifier,
    ) {
        shown.forEach { item ->
            val state = states[item.id]
            val busy = state == ZYXF_DOWNLOADING
            val tint = if (item.isFolder) FOLDER_TINT else accent
            WidgetRow(
                primary = item.name,
                secondary = listOfNotNull(
                    item.path.takeIf { it.isNotBlank() },
                    item.sizeText.takeIf { it.isNotBlank() },
                    state,
                ).joinToString(" · "),
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = !busy) {
                        if (item.isFolder) {
                            onAsk("打开仲英学辅资料站的目录 ${item.id}（${item.name}）")
                        } else {
                            states[item.id] = ZYXF_DOWNLOADING
                            scope.launch {
                                val saved = withContext(Dispatchers.IO) {
                                    runCatching {
                                        com.xjtu.toolbox.zyxf.ZyxfApi.download(context, item.id)
                                    }.getOrNull()
                                }
                                states[item.id] = if (saved != null) "已保存到下载" else "下载失败，稍后再试"
                            }
                        }
                    },
                leading = {
                    Box(
                        Modifier
                            .size(36.dp)
                            .squircleBackground(tint.copy(alpha = if (dark) 0.22f else 0.12f), 11.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (item.isFolder) Icons.Default.Folder else Icons.Default.Description,
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                },
                trailing = {
                    WidgetBadge(
                        when {
                            item.isFolder -> "打开"
                            busy -> "…"
                            state == "已保存到下载" -> "已下载"
                            else -> "下载"
                        },
                        accent,
                    )
                },
            )
        }
        if (w.items.size > shown.size) {
            WidgetMore("…还有 ${w.items.size - shown.size} 条，换个更具体的关键词能更快找到")
        }
    }
}

private val FOLDER_TINT = Color(0xFFE0A030)

// ── 图书馆平面图 ──────────────────────────────────────────────────────────

@Composable
private fun LibraryWidgetView(w: LibraryWidget, modifier: Modifier, onNavigate: (AppRoute) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val dark = LocalIsDarkTheme.current
    // 底图工具执行时已经进了磁盘缓存；历史会话里缓存过期被清掉了，就只剩文字
    // (缩略图, 原图宽)：座位坐标是原图像素，缩略图降采样过，要按原图宽换算
    val decoded by androidx.compose.runtime.produceState<Pair<androidx.compose.ui.graphics.ImageBitmap, Int>?>(null, w.imageName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val file = java.io.File(java.io.File(context.cacheDir, "library_plan"), w.imageName.replace('/', '_'))
                if (!file.isFile) return@runCatching null
                val bytes = file.readBytes()
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                val opts = android.graphics.BitmapFactory.Options().apply {
                    inSampleSize = if (bounds.outWidth > 1600) 2 else 1
                    inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                    ?.let { bmp -> Pair(bmp.asImageBitmap(), bounds.outWidth) }
            }.getOrNull()
        }
    }
    val free = w.seats.count { it.available }
    val accent = Color(0xFF2FA36B)
    WidgetCard(
        title = w.areaName,
        icon = Icons.Default.EventSeat,
        accent = accent,
        subtitle = "${w.campusName} · 点开在图书馆页选座",
        trailing = {
            Text("空闲 $free / ${w.seats.size}", style = MiuixTheme.textStyles.footnote1,
                fontWeight = FontWeight.Bold, color = accent)
        },
        modifier = modifier.clickable {
            com.xjtu.toolbox.library.LibraryFocus.request(
                com.xjtu.toolbox.library.LibraryFocus.Target(w.campusId, w.areaCode)
            )
            onNavigate(AppRoute.Library)
        },
    ) {
        val d = decoded
        if (d != null && w.seats.isNotEmpty()) {
            val img = d.first
            val origW = d.second.toFloat()
            val dimFilter = if (dark) androidx.compose.ui.graphics.ColorFilter.colorMatrix(
                androidx.compose.ui.graphics.ColorMatrix().apply { setToScale(0.78f, 0.78f, 0.8f, 1f) }
            ) else null
            androidx.compose.foundation.Canvas(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(img.width.toFloat() / img.height)
                    .clip(RoundedCornerShape(14.dp))
            ) {
                val k = size.width / origW
                drawImage(
                    img,
                    dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()),
                    colorFilter = dimFilter,
                    filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium,
                )
                // 有人的座位压暗，空座就在一片暗里亮出来
                w.seats.forEach { s ->
                    if (!s.available) drawRect(
                        Color.Black.copy(alpha = 0.55f),
                        androidx.compose.ui.geometry.Offset(s.left * k, s.top * k),
                        androidx.compose.ui.geometry.Size(s.width * k, s.height * k),
                    )
                }
            }
        } else {
            Text(
                "平面图已过期，点开图书馆页查看",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

