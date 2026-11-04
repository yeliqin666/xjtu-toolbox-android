package com.xjtu.toolbox.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.AssignmentTurnedIn
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.EventSeat
import androidx.compose.material.icons.filled.AddTask
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.ui.draw.drawBehind
import com.xjtu.toolbox.util.formatFixed
import com.xjtu.toolbox.util.formatTwoDigits
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
import com.xjtu.toolbox.attendance.AttendanceWaterRecord
import com.xjtu.toolbox.util.AppJson
import kotlinx.serialization.Serializable
import com.xjtu.toolbox.attendance.WaterType
import com.xjtu.toolbox.card.CardInfo
import com.xjtu.toolbox.emptyroom.RoomInfo
import com.xjtu.toolbox.schedule.CourseItem
import com.xjtu.toolbox.schedule.ExamItem
import com.xjtu.toolbox.score.ReportedGrade
import com.xjtu.toolbox.schedule.XjtuTime
import com.xjtu.toolbox.util.todayOnCampus
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import com.xjtu.toolbox.schedule.colorOf
import top.yukonga.miuix.kmp.basic.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.xjtu.toolbox.nav.AppRoute

private val DAY_NAMES = listOf("", "周一", "周二", "周三", "周四", "周五", "周六", "周日")

// ── 渲染入口 ─────────────────────────────────────────────────────────────

/**
 * @param onAsk 卡片想替用户问屁岱一句（比如点目录 → "打开目录 12"）。
 *              默认丢弃：历史消息重绘时没有可用的输入通道。
 */
@Composable
fun AgentWidgetView(
    widget: AgentWidget,
    /** 端上资源（平面图缓存、存进下载）：见 `:core` 的 [AgentHost]。 */
    host: AgentHost,
    modifier: Modifier = Modifier,
    onAsk: (String) -> Unit = {},
    onNavigate: (AppRoute) -> Unit = {},
    /** 卡片上的操作（如「加入日程」）转给对应工具执行；null = 只陈列。 */
    onTool: (suspend (name: String, args: String) -> String)? = null,
) {
    when (widget) {
        is NoticeWidget     -> NoticeWidgetView(widget, modifier, onNavigate)
        is LibraryWidget    -> LibraryWidgetView(widget, host, modifier, onNavigate)
        is ScheduleWidget   -> ScheduleWidgetView(widget, modifier)
        is ExamWidget       -> ExamWidgetView(widget, modifier)
        is RoomWidget       -> RoomWidgetView(widget, modifier)
        is LiveRoomWidget   -> LiveRoomWidgetView(widget, modifier)
        is AttendanceWidget -> AttendanceWidgetView(widget, modifier)
        is GradeWidget      -> GradeWidgetView(widget, modifier)
        is CardWidget       -> CardWidgetView(widget, modifier)
        is ZyxfWidget       -> ZyxfWidgetView(widget, host, modifier, onAsk)
        is CampusMapWidget  -> CampusMapWidgetView(widget, modifier)
        is PlanWidget       -> PlanWidgetView(widget, modifier, onTool)
        is BusWidget        -> BusWidgetView(widget, modifier, onNavigate)
        is LmsFileWidget    -> LmsFileWidgetView(widget, host, modifier, onNavigate, onTool)
    }
}

@Composable
private fun NoticeWidgetView(w: NoticeWidget, modifier: Modifier, onNavigate: (AppRoute) -> Unit) {
    WidgetCard(title = "通知精选", icon = Icons.Default.NotificationsNone, accent = MiuixTheme.colorScheme.primary,
        subtitle = w.query.ifBlank { "${w.items.size} 条通知" }, modifier = modifier) {
        if (w.caption.isNotBlank()) Text(w.caption, style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(bottom = 10.dp))
        if (w.items.isEmpty()) WidgetMore("没有匹配的通知")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            w.items.take(w.display.limit).forEach { n ->
                com.xjtu.toolbox.ui.components.NoticePickItem(
                    title = n.title,
                    metadata = listOf(n.source.takeIf { w.display.shows("source") }, n.date.takeIf { w.display.shows("date") })
                        .filterNotNull().filter { it.isNotBlank() }.joinToString(" · "),
                    why = n.why.ifBlank { n.excerpt }.takeIf { w.display.shows("why") }.orEmpty(), level = n.level,
                    enabled = n.link.startsWith("https://") || n.link.startsWith("http://"),
                    onOpen = { onNavigate(AppRoute.Browser(n.link)) },
                )
            }
        }
        if (w.items.size > w.display.limit) WidgetMore("显示 ${w.display.limit} / ${w.items.size} 条")
    }
}

/** 校区简图卡：按名字回查打包的地图数据，找不到的地点不画。 */
@Composable
private fun CampusMapWidgetView(w: CampusMapWidget, modifier: Modifier) {
    val map = remember(w.campus) { com.xjtu.toolbox.campusmap.CampusMaps.of(w.campus) } ?: return
    val marks = remember(w) {
        val here = w.here?.takeIf { it.size == 2 }?.let {
            com.xjtu.toolbox.ui.components.MapMark(
                com.xjtu.toolbox.campusmap.CampusPlace("你在这", com.xjtu.toolbox.campusmap.PlaceKind.OTHER, it[0], it[1]), "你在这",
            )
        }
        val placeMarks = w.places.mapNotNull { name -> map.places.firstOrNull { it.name == name } }
            .mapIndexed { i, p -> com.xjtu.toolbox.ui.components.MapMark(p, p.name, order = if (w.route) i + 1 else null) }
        listOfNotNull(here) + placeMarks
    }
    androidx.compose.foundation.layout.Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        com.xjtu.toolbox.ui.components.CampusMapView(map, marks, route = w.route, height = 230.dp)
        if (w.caption.isNotBlank()) {
            Text(w.caption, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
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

/**
 * 课程的开始 / 结束钟点，和日程页同一个算法（[CourseItem.clockMinutes]）。卡片不带日期，
 * 作息按今天所在月份定。
 */
private fun courseClock(c: CourseItem): Pair<String, String> {
    fun fmt(m: Int) = formatTwoDigits(m / 60) + ":" + formatTwoDigits(m % 60)
    val (start, end) = c.clockMinutes(XjtuTime.isSummerTime())
    return fmt(start) to fmt(end)
}

@Composable
private fun ScheduleWidgetView(w: ScheduleWidget, modifier: Modifier) {
    val summer = XjtuTime.isSummerTime()
    val sorted = (if (w.display.matchedCount > 0) w.courses else w.courses.sortedWith(compareBy({ it.dayOfWeek }, { it.clockMinutes(summer).first }))).take(w.display.limit)
    val byDay = sorted.groupBy { it.dayOfWeek }
    val courseColors = com.xjtu.toolbox.schedule.rememberCourseColors(remember(w.courses) { w.courses.map { it.courseName }.distinct() })
    WidgetCard(
        title = w.title,
        icon = Icons.Default.CalendarMonth,
        accent = MiuixTheme.colorScheme.primary,
        subtitle = if (w.courses.isEmpty()) "没有匹配的安排" else "${w.courses.size} 项安排",
        modifier = modifier,
    ) {
        if (w.courses.isEmpty()) return@WidgetCard
        byDay.entries.forEach { (day, list) ->
            if (byDay.size > 1) WidgetGroupLabel(DAY_NAMES.getOrElse(day) { "" })
            list.forEach { c ->
                val (start, end) = courseClock(c)
                val color = courseColors.colorOf(c.courseName)
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 5.dp).height(IntrinsicSize.Min),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (w.display.shows("time")) Column(Modifier.width(44.dp), horizontalAlignment = Alignment.End) {
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
                        val sub = listOfNotNull(c.location.takeIf { w.display.shows("location") }, c.teacher.takeIf { w.display.shows("teacher") }).filter { it.isNotBlank() }.joinToString(" · ")
                        if (sub.isNotBlank()) {
                            Text(sub, style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        if (w.courses.size > sorted.size) WidgetMore("显示 ${sorted.size} / ${w.courses.size} 项")
    }
}

/** 考试日期块：上面月份、下面日子，和日历 App 的日期图标一个意思。 */
@Composable
private fun DateBlock(date: LocalDate?, accent: Color) {
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
            Text("${date.monthNumber}月", style = MiuixTheme.textStyles.footnote2, color = accent)
            Text("${date.dayOfMonth}", style = MiuixTheme.textStyles.body1, color = accent, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ExamWidgetView(w: ExamWidget, modifier: Modifier) {
    val accent = MiuixTheme.colorScheme.primary
    val today = todayOnCampus()
    WidgetCard(
        title = "考试安排",
        icon = Icons.AutoMirrored.Filled.EventNote,
        accent = accent,
        subtitle = "${w.exams.size} 场",
        modifier = modifier,
    ) {
        if (w.exams.isEmpty()) WidgetMore("没有匹配的考试")
        w.exams.take(w.display.limit).forEach { e ->
            val date = runCatching { LocalDate.parse(e.examDate.take(10)) }.getOrNull()
            val days = date?.let { today.daysUntil(it) }
            WidgetRow(
                primary = e.courseName,
                secondary = listOf(e.examTime.takeIf { w.display.shows("time") }, e.location.takeIf { w.display.shows("location") }, e.seatNumber.takeIf { w.display.shows("seat") && it.isNotBlank() }?.let { "座位 $it" })
                    .filter { !it.isNullOrBlank() }.joinToString(" · "),
                leading = { DateBlock(date, accent) },
                trailing = when {
                    days == null -> null
                    days < 0 -> ({ WidgetBadge("已结束", MiuixTheme.colorScheme.onSurfaceVariantSummary) })
                    days == 0 -> ({ WidgetBadge("今天", MiuixTheme.colorScheme.error) })
                    days <= 7 -> ({ WidgetBadge("${days} 天", MiuixTheme.colorScheme.error) })
                    else -> ({ WidgetBadge("${days} 天", accent) })
                },
            )
        }
        if (w.exams.size > w.display.limit) WidgetMore("显示 ${w.display.limit} / ${w.exams.size} 场")
    }
}

@Composable
private fun RoomWidgetView(w: RoomWidget, modifier: Modifier) {
    val accent = MiuixTheme.colorScheme.primary
    val shown = w.rooms.take(w.display.limit)
    WidgetCard(
        title = "空闲教室",
        icon = Icons.Default.MeetingRoom,
        accent = accent,
        subtitle = w.condition,
        modifier = modifier,
    ) {
        if (shown.isEmpty()) WidgetMore("没有匹配的教室")
        WidgetMore(if (w.liveRooms.isEmpty()) "按课表 · 亮色空闲 / 灰色有课" else "空闲条按课表，实时状态另标")
        shown.forEach { r ->
            val nowFree = w.currentPeriod in r.status.indices && r.status[w.currentPeriod] == 0
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.name, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (nowFree && w.display.view != "slots") {
                        WidgetBadge("本节无课", accent)
                        Spacer(Modifier.width(6.dp))
                    }
                    if (w.display.shows("seats")) Text("${r.size} 座", style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                if (w.display.view in setOf("auto", "slots", "both") && w.display.shows("slots") && r.status.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    // 和空闲教室页同一条节次条（一次画完、进场依次亮起）
                    com.xjtu.toolbox.ui.components.SlotStripe(
                        free = r.status.map { it == 0 },
                        freeColor = accent,
                        currentIndex = w.currentPeriod,
                        busyColor = MiuixTheme.colorScheme.outline.copy(alpha = 0.16f),
                    )
                    Row(Modifier.fillMaxWidth()) {
                        r.status.indices.forEach { index ->
                            Text("${index + 1}", modifier = Modifier.weight(1f),
                                style = MiuixTheme.textStyles.footnote2,
                                color = if (index + 1 in w.display.sections) accent else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                fontWeight = if (index + 1 in w.display.sections) FontWeight.Bold else FontWeight.Normal,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                }
                if (w.display.view in setOf("both", "status") && w.display.shows("status")) {
                    val live = w.liveRooms.firstOrNull { it.name == r.name }
                    Spacer(Modifier.height(4.dp))
                    if (live != null) LiveRoomStatusBadge(live)
                    else Text("未匹配到实时状态", style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
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
    val shown = w.rooms.take(w.display.limit)
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
                secondary = listOfNotNull(
                    "${r.seats} 座".takeIf { w.display.shows("seats") },
                ).joinToString(" · "),
                trailing = {
                    if (w.display.shows("status")) LiveRoomStatusBadge(r)
                },
            )
        }
        if (w.rooms.size > shown.size) WidgetMore("…还有 ${w.rooms.size - shown.size} 间")
    }
}

@Composable
private fun LiveRoomStatusBadge(room: com.xjtu.toolbox.emptyroom.LiveRoom) {
    WidgetBadge(
        when { room.isFree -> "此刻空闲"; room.isInUse -> "有人 · ${room.people} 人"; room.isInClass -> "上课中"; else -> "状态未知" },
        when { room.isFree -> STATUS_OK; room.isInUse -> Color(0xFFD9822B); room.isInClass -> MiuixTheme.colorScheme.error; else -> MiuixTheme.colorScheme.onSurfaceVariantSummary },
    )
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
        if (w.records.isEmpty()) WidgetMore("没有匹配的考勤记录")
        w.records.take(w.display.limit).forEach { r ->
            val color = when (r.status) {
                WaterType.NORMAL -> STATUS_OK
                WaterType.LEAVE -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                else -> MiuixTheme.colorScheme.error
            }
            WidgetRow(
                primary = r.courseName,
                secondary = listOfNotNull(
                    "${r.date} 第${r.startTime}-${r.endTime}节".takeIf { w.display.shows("date") },
                    r.location.takeIf { w.display.shows("location") && it.isNotBlank() },
                ).joinToString(" · "),
                trailing = { if (w.display.shows("status")) WidgetBadge(r.status.displayName, color) },
            )
        }
        if (w.records.size > w.display.limit) WidgetMore("显示 ${w.display.limit} / ${w.records.size} 条")
    }
}

@Composable
private fun GradeWidgetView(w: GradeWidget, modifier: Modifier) {
    val accent = MiuixTheme.colorScheme.primary
    WidgetCard(
        title = "成绩",
        icon = Icons.Default.Assessment,
        accent = accent,
        subtitle = "所选 ${w.grades.size} 门" + if (w.display.summary != "none" && w.display.shows("credit")) " · ${if (w.display.summary == "selected") "所选" else "查询全部"}计入学分 ${formatFixed(w.totalPoints, 1)}" else "",
        trailing = w.gpa?.takeIf { w.display.summary != "none" && w.display.shows("gpa") }?.let { gpa ->
            {
                Column(horizontalAlignment = Alignment.End) {
                    Text(formatFixed(gpa, 2), style = MiuixTheme.textStyles.title4,
                        fontWeight = FontWeight.Bold, color = accent)
                    Text(if (w.display.summary == "selected") "所选课程 GPA" else "查询全部 GPA", style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        },
        modifier = modifier,
    ) {
        if (w.grades.isEmpty()) WidgetMore("没有匹配的成绩")
        w.grades.take(w.display.limit).forEach { g ->
            val n = g.score.toDoubleOrNull()
            val color = when {
                n != null && n < 60 || g.score.contains("不及格") -> MiuixTheme.colorScheme.error
                n != null && n >= 90 || g.score == "优秀" -> STATUS_OK
                else -> accent
            }
            WidgetRow(
                primary = g.courseName,
                secondary = listOfNotNull("${g.coursePoint} 学分".takeIf { w.display.shows("credit") },
                    g.gpa?.takeIf { w.display.shows("gpa") }?.let { "绩点 " + formatFixed(it, 2) }).joinToString(" · "),
                trailing = { WidgetBadge(g.score, color) },
            )
        }
        if (w.grades.size > w.display.limit) WidgetMore("显示 ${w.display.limit} / ${w.grades.size} 门")
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
        if (w.display.view in setOf("auto", "all", "balance") && w.display.shows("balance")) {
        Spacer(Modifier.height(22.dp))
        Text("余额", style = MiuixTheme.textStyles.footnote1, color = onCard.copy(alpha = 0.75f))
        Text(
            "¥" + formatFixed(info.balance, 2),
            style = MiuixTheme.textStyles.title2,
            fontWeight = FontWeight.Bold,
            color = onCard,
        )
        val notes = buildList {
            if (info.pendingAmount > 0) add("待入账 ¥" + formatFixed(info.pendingAmount, 2))
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
        if (w.transactionsLoaded && w.display.view in setOf("auto", "all", "summary") && w.display.shows("summary")) {
            Spacer(Modifier.height(14.dp))
            Text("${w.rangeLabel} · 所选 ${w.transactions.size} 笔", style = MiuixTheme.textStyles.footnote1, color = onCard.copy(alpha = 0.8f))
            Text("支出 ¥${formatFixed(w.spend ?: w.transactions.filter { it.amount < 0 }.sumOf { -it.amount }, 2)} · 收入 ¥${formatFixed(w.income ?: w.transactions.filter { it.amount > 0 }.sumOf { it.amount }, 2)}",
                style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium, color = onCard)
        }
        if (w.transactionsLoaded && w.display.view in setOf("all", "transactions") && w.display.shows("transactions")) {
            Spacer(Modifier.height(10.dp))
            if (w.transactions.isEmpty()) Text("没有匹配的流水", style = MiuixTheme.textStyles.footnote1, color = onCard)
            w.transactions.take(w.display.limit).forEach { t ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.displayMerchant, style = MiuixTheme.textStyles.body2, color = onCard, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(t.time, style = MiuixTheme.textStyles.footnote2, color = onCard.copy(alpha = 0.75f))
                    }
                    Text((if (t.amount > 0) "+" else "") + formatFixed(t.amount, 2), style = MiuixTheme.textStyles.body2, color = onCard, fontWeight = FontWeight.Bold)
                }
            }
            if (w.transactions.size > w.display.limit) Text("显示 ${w.display.limit} / ${w.transactions.size} 笔", style = MiuixTheme.textStyles.footnote2, color = onCard.copy(alpha = 0.8f))
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
private fun ZyxfWidgetView(w: ZyxfWidget, host: AgentHost, modifier: Modifier, onAsk: (String) -> Unit) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // 这一端存不了文件（没有系统「下载」目录）的活，那一行的下载动作**整行不画** ——
    // 画出来点了没反应比不画更糟（同一条纪律见 [AgentCapability.FILE_SAVE]）。
    val canSave = AgentCapability.FILE_SAVE in host.honored
    // 每行的下载状态，key 是文件 ID；滚动离屏再回来也不会丢。
    val states = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateMapOf<Int, String>()
    }
    val shown = w.items.take(w.display.limit)
    val accent = MiuixTheme.colorScheme.primary
    val dark = LocalIsDarkTheme.current

    WidgetCard(
        title = "仲英学辅资料",
        icon = Icons.Default.FolderOpen,
        accent = accent,
        subtitle = w.query.takeIf { it.isNotBlank() }?.let { "「$it」" },
        modifier = modifier,
    ) {
        if (shown.isEmpty()) WidgetMore("没有匹配的资料")
        shown.forEach { item ->
            val state = states[item.id]
            val busy = state == ZYXF_DOWNLOADING
            val tint = if (item.isFolder) FOLDER_TINT else accent
            WidgetRow(
                primary = item.name,
                secondary = listOfNotNull(
                    item.path.takeIf { w.display.shows("path") && it.isNotBlank() },
                    item.sizeText.takeIf { w.display.shows("size") && it.isNotBlank() },
                    state,
                ).joinToString(" · "),
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = !busy && (item.isFolder || canSave)) {
                        if (item.isFolder) {
                            onAsk("打开仲英学辅资料站的目录 ${item.id}（${item.name}）")
                        } else if (canSave) {
                            states[item.id] = ZYXF_DOWNLOADING
                            scope.launch {
                                // 存进「下载」是端能力（`:app` = ZyxfApi.download），文案也由它给。
                                states[item.id] = host.saveZyxfFile(item.id) ?: "下载失败，稍后再试"
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
                trailing = if (!item.isFolder && !canSave) null else {
                    {
                        WidgetBadge(
                            when {
                                item.isFolder -> "打开"
                                busy -> "…"
                                state == "已保存到下载" -> "已下载"
                                else -> "下载"
                            },
                            accent,
                        )
                    }
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
private fun LibraryWidgetView(w: LibraryWidget, host: AgentHost, modifier: Modifier, onNavigate: (AppRoute) -> Unit) {
    val dark = LocalIsDarkTheme.current
    // 底图工具执行时已经进了磁盘缓存（`:app` = `cacheDir/library_plan/`）；历史会话里缓存过期
    // 被清掉了、或这一端没有那份缓存（桌面）就只剩文字。读+解码都在宿主那侧，
    // 尺寸取的是**原始**宽（座位坐标是相对原图算的）。
    val decoded by androidx.compose.runtime.produceState<DecodedPlanImage?>(null, w.imageName) {
        value = host.readPlanImage(w.imageName)
    }
    val free = w.seats.count { it.available }
    val accent = Color(0xFF2FA36B)
    WidgetCard(
        title = w.areaName,
        icon = Icons.Default.EventSeat,
        accent = accent,
        subtitle = "${w.campusName} · 点开在图书馆页选座",
        trailing = if (w.areas.isNotEmpty() || w.display.view == "booking") null else {
          {
            Text("空闲 $free / ${w.seats.size}", style = MiuixTheme.textStyles.footnote1,
                fontWeight = FontWeight.Bold, color = accent)
          }
        },
        modifier = modifier.clickable {
            com.xjtu.toolbox.library.LibraryFocus.request(
                com.xjtu.toolbox.library.LibraryFocus.Target(w.campusId, w.areaCode)
            )
            onNavigate(AppRoute.Library)
        },
    ) {
        val d = decoded
        if (w.display.shows("booking") || w.display.view == "booking") w.booking?.let {
            Text(it, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurface)
            Spacer(Modifier.height(6.dp))
        }
        if (w.display.view == "booking") return@WidgetCard
        if (w.areas.isNotEmpty()) {
            w.areas.take(w.display.limit).forEach { area ->
                WidgetRow(area.name, null, trailing = {
                    WidgetBadge(when { area.total == null -> "未取得统计"; area.total == 0 -> "未开放"; else -> "${area.free} / ${area.total} 空座" }, accent)
                })
            }
            if (w.areas.size > w.display.limit) WidgetMore("显示 ${w.display.limit} / ${w.areas.size} 个区域")
            return@WidgetCard
        }
        if (w.highlightedSeats.isNotEmpty()) Text("已突出座位 ${w.highlightedSeats.joinToString("、")}", style = MiuixTheme.textStyles.footnote1, color = accent)
        if (w.display.view == "summary") {
            Text("可选空座 $free 个", style = MiuixTheme.textStyles.body2, color = accent)
        } else if (w.display.shows("plan") && d != null && w.seats.isNotEmpty()) {
            val img = d.image
            val origW = d.originalWidth.toFloat()
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
                    if (s.seatId in w.highlightedSeats) drawRect(
                        accent,
                        androidx.compose.ui.geometry.Offset(s.left * k, s.top * k),
                        androidx.compose.ui.geometry.Size(s.width * k, s.height * k),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
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


// ── 小岱提议的安排 ─────────────────────────────────────────────────────

private sealed interface PlanAddState {
    data object Idle : PlanAddState
    data object Busy : PlanAddState
    data object Added : PlanAddState
    data class Conflict(val with: String) : PlanAddState
    data object Failed : PlanAddState
}

/** 加过的条目按内容记在本机：重绘历史消息、重启后仍显示「已加入」，不会重复写。 */
private object PlanAddedLog {
    private const val KEY = "keys"
    private val store get() = com.xjtu.toolbox.platform.keyValueStore("agent_plan_added" + com.xjtu.toolbox.account.AccountContext.safeSuffix())
    private fun keyOf(p: PlanProposal) = listOf(p.title, p.date, p.start, p.todo).joinToString("|")
    private fun all(): List<String> = store.getString(KEY)?.split('\n')?.filter { it.isNotEmpty() }.orEmpty()
    fun contains(p: PlanProposal) = keyOf(p) in all()
    fun add(p: PlanProposal) = store.putString(KEY, (all() - keyOf(p) + keyOf(p)).takeLast(300).joinToString("\n"))
}

@Composable
private fun PlanWidgetView(w: PlanWidget, modifier: Modifier, onTool: (suspend (String, String) -> String)?) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val states = remember(w) {
        androidx.compose.runtime.mutableStateMapOf<Int, PlanAddState>().apply {
            w.items.forEachIndexed { i, p -> if (PlanAddedLog.contains(p)) put(i, PlanAddState.Added) }
        }
    }
    suspend fun addOne(i: Int, force: Boolean) {
        val run = onTool ?: return
        val item = w.items[i]
        states[i] = PlanAddState.Busy
        val (name, args) = AgentPlanCard.addCall(item, force)
        val reply = runCatching { run(name, args) }.getOrElse { "error: ${it.message}" }
        states[i] = when {
            reply.startsWith("ok:") -> PlanAddState.Added.also { PlanAddedLog.add(item) }
            "conflict" in reply -> PlanAddState.Conflict(reply.substringAfter("with: ", "").substringBefore("; retry").ifBlank { "已有安排" })
            else -> PlanAddState.Failed
        }
    }
    val pending = w.items.indices.filter { states[it] == null || states[it] == PlanAddState.Failed }
    WidgetCard(
        title = "可加入的安排", icon = Icons.Default.CalendarMonth, accent = MiuixTheme.colorScheme.primary,
        subtitle = if (onTool == null) "${w.items.size} 项" else "${w.items.size} 项 · 点「加入」才会写入",
        modifier = modifier,
    ) {
        if (w.caption.isNotBlank()) Text(w.caption, style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(bottom = 10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            w.items.forEachIndexed { i, p ->
                PlanProposalRow(p, states[i] ?: PlanAddState.Idle,
                    onAdd = onTool?.let { { force: Boolean -> scope.launch { addOne(i, force) }; Unit } })
            }
        }
        if (onTool != null && pending.size > 1) {
            Row(Modifier.padding(top = 10.dp)) {
                com.xjtu.toolbox.ui.components.PidaiChip("全部加入（${pending.size}）", icon = Icons.Default.EventAvailable,
                    onClick = { scope.launch { pending.forEach { addOne(it, false) } } })
            }
        }
    }
}

@Composable
private fun PlanProposalRow(p: PlanProposal, state: PlanAddState, onAdd: ((Boolean) -> Unit)?) {
    val summary = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(com.xjtu.toolbox.ui.components.AppInsetColor)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(52.dp)) {
            Text(p.start.ifEmpty { "全天" }, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Bold)
            Text(if (p.todo) "截止" else p.end.ifEmpty { "1 小时" }, style = MiuixTheme.textStyles.footnote2, color = summary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(p.title, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val day = runCatching { LocalDate.parse(p.date) }.getOrNull()?.let { "${it.monthNumber}月${it.dayOfMonth}日 ${DAY_NAMES[it.dayOfWeek.ordinal + 1]}" } ?: p.date
            Text(listOf(day, p.place, p.note).filter { it.isNotBlank() }.joinToString(" · "),
                style = MiuixTheme.textStyles.footnote1, color = summary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            when (state) {
                is PlanAddState.Conflict -> Text("与 ${state.with} 时间重叠", style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.error)
                PlanAddState.Failed -> Text("没加上，可再试一次", style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.error)
                else -> Unit
            }
        }
        if (onAdd != null) {
            val todoLabel = if (p.todo) "加入待办" else "加入日程"
            com.xjtu.toolbox.ui.components.PidaiChip(
                when (state) {
                    PlanAddState.Added -> "已加入"
                    PlanAddState.Busy -> "加入中"
                    is PlanAddState.Conflict -> "仍然加入"
                    else -> todoLabel
                },
                icon = when (state) {
                    PlanAddState.Added -> Icons.Default.Check
                    else -> if (p.todo) Icons.Default.AddTask else Icons.Default.EventAvailable
                },
                enabled = state != PlanAddState.Added && state != PlanAddState.Busy,
                onClick = { onAdd(state is PlanAddState.Conflict) },
            )
        }
    }
}

@Composable
private fun BusWidgetView(w: BusWidget, modifier: Modifier, onNavigate: (AppRoute) -> Unit) {
    val cs = MiuixTheme.colorScheme
    val shown = w.items.take(w.display.limit)
    WidgetCard(title = "校车", icon = Icons.Default.DirectionsBus, accent = cs.primary, subtitle = w.title.ifBlank { null }, modifier = modifier) {
        if (shown.isEmpty()) WidgetMore("没有匹配的班次")
        shown.forEach { run ->
            WidgetRow(
                primary = "${run.time}  ${run.line}",
                secondary = listOfNotNull(
                    run.route.takeIf { w.display.shows("route") && it.isNotBlank() },
                    run.plate.takeIf { w.display.shows("plate") && it.isNotBlank() },
                ).joinToString(" · "),
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { onNavigate(AppRoute.CampusBus) },
                trailing = {
                    Text(
                        if (run.seatsLeft <= 0) "已满" else "余 ${run.seatsLeft}",
                        style = MiuixTheme.textStyles.footnote1,
                        fontWeight = FontWeight.Bold,
                        color = if (run.tight || run.seatsLeft <= 0) cs.error else cs.onSurface,
                    )
                },
            )
        }
        if (w.items.size > shown.size) WidgetMore("还有 ${w.items.size - shown.size} 班，点开校车页看全部")
        WidgetMore(com.xjtu.toolbox.bus.BusRules.BOARDING_TIP)
    }
}

/** 点一行去活动详情（预览在那边，地址现取）；右边的按钮直接存进下载管理。 */
@Composable
private fun LmsFileWidgetView(
    w: LmsFileWidget,
    host: AgentHost,
    modifier: Modifier,
    onNavigate: (AppRoute) -> Unit,
    onTool: (suspend (name: String, args: String) -> String)?,
) {
    val cs = MiuixTheme.colorScheme
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val canSave = AgentCapability.FILE_SAVE in host.honored && onTool != null
    val states = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateMapOf<Int, String>() }
    val shown = w.items.take(w.display.limit)
    WidgetCard(
        title = w.activityTitle.ifBlank { "思源附件" },
        icon = Icons.Default.FolderOpen,
        accent = cs.primary,
        subtitle = w.courseName.ifBlank { null },
        modifier = modifier,
    ) {
        if (shown.isEmpty()) WidgetMore("没有匹配的附件")
        shown.forEach { file ->
            val lower = file.name.lowercase()
            val icon = when {
                lower.endsWith(".pdf") -> Icons.Default.PictureAsPdf
                listOf(".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp").any(lower::endsWith) -> Icons.Default.Image
                else -> Icons.Default.Description
            }
            val state = states[file.id]
            WidgetRow(
                primary = file.name,
                secondary = listOfNotNull(
                    file.sizeText.takeIf { w.display.shows("size") && it.isNotBlank() },
                    "我提交的".takeIf { file.mine },
                    state,
                ).joinToString(" · "),
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable {
                    onNavigate(AppRoute.Lms(w.courseId.takeIf { it > 0 }, w.activityId.takeIf { it > 0 }))
                },
                leading = { Icon(icon, null, tint = cs.primary, modifier = Modifier.size(22.dp)) },
                trailing = if (!canSave) null else {
                    {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = "保存到下载管理",
                            tint = cs.onSurfaceVariantSummary,
                            modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).clickable(enabled = state != LMS_SAVING) {
                                states[file.id] = LMS_SAVING
                                scope.launch {
                                    val args = buildJsonObject {
                                        put("source", "lms")
                                        put("course", w.courseName)
                                        put("activity", w.activityTitle)
                                        put("file", file.id.toString())
                                    }.toString()
                                    val reply = runCatching { onTool?.invoke("save_file", args) }.getOrNull().orEmpty()
                                    states[file.id] = if (reply.startsWith("ok")) "已存到下载管理" else "保存失败"
                                }
                            }.padding(6.dp),
                        )
                    }
                },
            )
        }
        if (w.items.size > shown.size) WidgetMore("还有 ${w.items.size - shown.size} 个附件，点开活动看全部")
    }
}

private const val LMS_SAVING = "保存中…"
