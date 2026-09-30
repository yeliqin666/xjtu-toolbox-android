package com.xjtu.toolbox.schedule

import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import top.yukonga.miuix.kmp.utils.overScrollVertical
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil
import kotlin.math.floor

// ── 共享常量 ──────────────────────────────

val COURSE_COLORS = listOf(
    Color(0xFF1565C0), Color(0xFF2E7D32), Color(0xFFC62828), Color(0xFF6A1B9A),
    Color(0xFFEF6C00), Color(0xFF00838F), Color(0xFFAD1457), Color(0xFF4527A0),
    Color(0xFF00695C), Color(0xFF283593), Color(0xFF558B2F), Color(0xFF8E24AA),
    Color(0xFFD84315),
)

val DAY_HEADERS = listOf("一", "二", "三", "四", "五", "六", "日")
const val DAY_START_HOUR = 8
const val DAY_END_HOUR = 22
const val MAX_SECTIONS = DAY_END_HOUR - DAY_START_HOUR
private val SECTION_HEIGHT: Dp = 50.dp
/** 一节课多少分钟：时段带放开时按「这么多分钟 = 一个节次行高」的比例。 */
private const val SECTION_MINUTES = 50f
/** 时段带里有条目的部分最多画多高，再长就按比例压。 */
private val BAND_MAX_HEIGHT: Dp = SECTION_HEIGHT * 3
private val LEFT_COL_WIDTH: Dp = 56.dp
/** 跨作息那一周左轴要多标一行「冬14:00-14:50」，放宽一点才放得下。 */
private val LEFT_COL_WIDTH_MIXED: Dp = 68.dp
/**
 * 没课的节次压到本体的多少。0.56（= 28dp）是"刚好放得下节号 + 一行起止时间"的下限：
 * 再矮就只能像以前那样只画节号了，那样虽然更短，但压扁的行看不出上下课时间。
 */
private const val EMPTY_SECTION_SCALE = 0.56f
/** 空着的时段带（午休 / 晚休）的高度；带上有条目时按钟点比例放开，见 [ScheduleGrid]。 */
private val REST_HEIGHT: Dp = 22.dp
/** 课程块在格子里的内缩（格间距）。 */
private val CELL_GAP: Dp = 3.dp

// 纵轴按节次排版（节号与起止时间画在左轴，大空档插成「午休」「晚休」带），条目按真实钟点落位，
// 见 WeekGridLayout。
//
// ⚠️ 本文件下面那三个常量（DAY_START_HOUR / DAY_END_HOUR / MAX_SECTIONS）是**小时**语义的，
// 别处（自定义日程编辑器、Agent 的冲突判定、日程详情文案）还在用 —— 网格不再拿它们当行数，
// 也不要顺手改它们的含义。

/** 课名的默认色：按课名稳定哈希取色。思源学堂那边只认识单门课，用的就是它。 */
fun defaultCourseColor(courseName: String): Color =
    COURSE_COLORS[(courseName.trim().hashCode() and Int.MAX_VALUE) % COURSE_COLORS.size]

/**
 * 一批课程的「课名 → 颜色」表。用户改过的颜色优先；其余从各自哈希位置起取默认色，
 * 撞了就顺延到下一个空位，本批内不重复（超过 [COURSE_COLORS] 的数量才会重复）。
 * 没撞色时与 [defaultCourseColor] 一致，所以课表和思源学堂里同一门课通常同色。
 */
fun courseColorMap(names: Collection<String>): Map<String, Color> {
    val n = COURSE_COLORS.size
    val used = BooleanArray(n)
    val out = HashMap<String, Color>()
    for (name in names.distinct().sorted()) {
        val custom = CourseColors.of(name)
        if (custom != null) {
            out[name] = custom
            continue
        }
        val start = (name.trim().hashCode() and Int.MAX_VALUE) % n
        val i = (0 until n).map { (start + it) % n }.firstOrNull { !used[it] } ?: start
        used[i] = true
        out[name] = COURSE_COLORS[i]
    }
    return out
}

/** 课程集合或用户改色（[CourseColors.revision]）变化时重算；课格只查表，不用每格读一次存储。 */
@Composable
fun rememberCourseColors(names: List<String>): Map<String, Color> {
    val revision = CourseColors.revision
    val account = com.xjtu.toolbox.account.AccountContext.activeAccountId
    return remember(names, revision, account) { courseColorMap(names) }
}

fun Map<String, Color>.colorOf(courseName: String): Color = this[courseName] ?: defaultCourseColor(courseName)

// ── 通用课格接口 ─────────────────────────

interface ScheduleSlot {
    val slotName: String
    val slotLocation: String
    val slotDayOfWeek: Int
    val slotStartSection: Int
    val slotEndSection: Int
}

// ── 周选择器（左右箭头式）────────────────

// ── 日程网格（绝对定位，完美对齐）────────

@Composable
fun ScheduleGrid(
    slots: List<ScheduleSlot>,
    allCourseNames: List<String>,
    showWeeks: Boolean = false,
    isCurrentWeek: Boolean = false,  // 是否显示当前时间线
    weekDates: List<java.time.LocalDate>? = null,
    holidayNames: Map<java.time.LocalDate, String> = emptyMap(),
    enableCompression: Boolean = false,  // 没课的节次是否压扁（0.56 高）
    bottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
    /** 顶部留白，放在纵向滚动里面：顶栏是玻璃时，星期头和网格要能从它下面滚过去。 */
    topPadding: androidx.compose.ui.unit.Dp = 0.dp,
    /**
     * 这一格右上角要不要点标记，null = 不点。
     *
     * 做成回调是因为网格是通用组件，不该知道考勤这回事。调用方必须保证它纯读内存——
     * 它在每一格的组合里被调用，做 IO 就等于把课表渲染绑在别的站点上。
     */
    slotBadge: (ScheduleSlot) -> SlotMark? = { null },
    onSlotClick: (ScheduleSlot) -> Unit = {}
) {
    val scrollState = rememberScrollState()
    val courseColors = rememberCourseColors(allCourseNames)
    // 左轴标哪一套作息：默认跟这一周自己的令时；这一周跨了令时切换（5/1 起夏秋、10/1 起冬春）就按
    // "今天"所在令时标，另一套时间不同的节次在下面补一行。课块各按自己那天的作息对齐（见 WeekGridLayout）。
    val todaySummer = XjtuTime.isSummerTime()
    val weekSeasons = weekDates?.map { XjtuTime.isSummerTime(it.monthValue) }?.distinct()
    val mixedWeek = (weekSeasons?.size ?: 1) > 1
    val axisSummer = if (mixedWeek) todaySummer else (weekSeasons?.firstOrNull() ?: todaySummer)

    val layout = remember(slots, weekDates, axisSummer) {
        layoutWeekGrid(slots) { day ->
            weekDates?.getOrNull(day - 1)?.let { XjtuTime.isSummerTime(it.monthValue) } ?: axisSummer
        }
    }
    val rows = layout.rows
    val axisTimes = layout.times(axisSummer)
    val otherTimes = layout.times(!axisSummer)
    val leftColWidth = if (mixedWeek) LEFT_COL_WIDTH_MIXED else LEFT_COL_WIDTH
    // 行高：有条目的节次全高，空的节次可压扁（总长度随一周的课量伸缩）。时段带里被条目占着的几段
    // 按时长画（一节 50 分钟 = 一行高），空着的几段合起来收成一条窄带，不留大片空白。
    val compressed = rows.indices.map { i -> rows[i].section != null && enableCompression && !layout.isOccupied(i) }
    val bandPieces: List<List<Pair<WeekGridLayout.Piece, Dp>>?> = rows.indices.map { i ->
        if (rows[i].section != null || !layout.isOccupied(i)) return@map null
        val pieces = layout.pieces(i)
        val occupiedLength = pieces.filter { it.occupied }.sumOf { (it.to - it.from).toDouble() }.toFloat()
        val freeLength = 1f - occupiedLength
        // 占着的部分按时长画，但一条带最多放开到 BAND_MAX_HEIGHT（屁岱能建 0:00 起的日程，早间带足有 8 小时）
        val perRow = SECTION_HEIGHT * ((axisTimes[i].second - axisTimes[i].first) / SECTION_MINUTES)
        val occupiedPerRow = minOf(perRow, BAND_MAX_HEIGHT / occupiedLength)
        pieces.map { p ->
            p to if (p.occupied) occupiedPerRow * (p.to - p.from) else REST_HEIGHT * ((p.to - p.from) / freeLength)
        }
    }
    val targetHeights = rows.mapIndexed { i, row ->
        when {
            row.section != null -> if (compressed[i]) SECTION_HEIGHT * EMPTY_SECTION_SCALE else SECTION_HEIGHT
            else -> bandPieces[i]?.fold(0.dp) { sum, (_, h) -> sum + h } ?: REST_HEIGHT
        }
    }
    val rowHeights = rows.mapIndexed { i, row ->
        // 直接朝目标高度做动画；按行的身份记状态，时段带出现、消失时别的行不会串动画
        key(row) {
            animateDpAsState(targetHeights[i], tween(durationMillis = 380), label = "gridRow").value
        }
    }
    val rowTops = rowHeights.runningFold(0.dp) { top, h -> top + h }
    val gridHeight = rowTops.last()

    /** 行刻度 → 纵坐标：节次行内按比例插值，时段带按各段的高度分段插值。 */
    fun yOf(pos: Float): Dp {
        val i = floor(pos).toInt()
        if (i < 0) return 0.dp
        if (i >= rows.size) return gridHeight
        val f = pos - i
        val pieces = bandPieces[i] ?: return rowTops[i] + rowHeights[i] * f
        val scale = if (targetHeights[i] > 0.dp) rowHeights[i] / targetHeights[i] else 0f
        var y = 0.dp
        for ((p, h) in pieces) {
            if (f >= p.to) { y += h; continue }
            y += h * ((f - p.from) / (p.to - p.from))
            break
        }
        return rowTops[i] + y * scale
    }

    // 当前时间线位置计算（仅在当前周激活）
    val timeLineInfo = if (isCurrentWeek) {
        val now = java.time.LocalTime.now()
        val todayDow = java.time.LocalDate.now().dayOfWeek.value  // 1=Mon...7=Sun
        Pair(todayDow, layout.positionOf(now.hour * 60 + now.minute, todaySummer))
    } else null
    Column(
        Modifier
            .fillMaxSize()
            .overScrollVertical()
            .verticalScroll(scrollState)
    ) {
        if (topPadding > 0.dp) Spacer(Modifier.height(topPadding))
        // ── 星期头 ──
        Row(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
            Box(Modifier.width(leftColWidth), contentAlignment = Alignment.Center) {
                Text("", fontSize = 9.sp)
            }
            DAY_HEADERS.forEachIndexed { idx, day ->
                val todayDow = java.time.LocalDate.now().dayOfWeek.value
                val isToday = isCurrentWeek && (idx + 1) == todayDow
                val date = weekDates?.getOrNull(idx)
                val holidayName = if (date != null) holidayNames[date] else null

                Column(
                    Modifier
                        .weight(1f)
                        .padding(vertical = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        day, fontSize = 12.sp,
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
                        color = if (isToday) MiuixTheme.colorScheme.primary
                               else MiuixTheme.colorScheme.onSurface
                    )
                    if (holidayName != null) {
                        Text(
                            holidayName, fontSize = 8.sp,
                            color = MiuixTheme.colorScheme.error,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    } else if (date != null) {
                        Text(
                            "${date.monthValue}月${date.dayOfMonth}日",
                            fontSize = 8.sp,
                            color = if (isToday) MiuixTheme.colorScheme.primary
                            else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // ── 网格主体：BoxWithConstraints 精确定位 ──
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(gridHeight)
        ) {
            val dayWidth = (maxWidth - leftColWidth) / 7

            // 背景层：左轴（节号 + 起止时间）+ 时段通栏带。没课的节次被压扁时只留节号。
            val axisTextColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
            Column(Modifier.fillMaxSize()) {
                rows.forEachIndexed { i, row ->
                    val startText = formatMinuteOfDay(axisTimes[i].first)
                    val endText = formatMinuteOfDay(axisTimes[i].second)
                    // 跨作息那一周，另一套作息时间不同的节次补一行（冬令 / 夏令各自的起止）
                    val otherText = if (mixedWeek && otherTimes[i] != axisTimes[i]) {
                        (if (axisSummer) "冬" else "夏") +
                            "${formatMinuteOfDay(otherTimes[i].first)}-${formatMinuteOfDay(otherTimes[i].second)}"
                    } else null
                    if (row.section == null && bandPieces[i] == null) {
                        // 空着的时段带：一条通栏窄带，名字居中
                        Box(
                            Modifier.fillMaxWidth().height(rowHeights[i]),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(row.label, fontSize = 10.sp, color = axisTextColor)
                        }
                    } else if (row.section == null) {
                        // 有条目的时段带：名字和起止挪到左轴，不压在课块上
                        Column(
                            Modifier.width(leftColWidth).height(rowHeights[i]),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(row.label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = axisTextColor)
                            Text("$startText-$endText", fontSize = 8.sp, color = axisTextColor)
                            if (otherText != null) Text(otherText, fontSize = 8.sp, color = axisTextColor.copy(alpha = 0.75f))
                        }
                    } else {
                        Box(Modifier.fillMaxWidth().height(rowHeights[i])) {
                            Column(
                                Modifier.width(leftColWidth).fillMaxHeight(),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                // 按**目标**（不是动画中的行高）决定排版：行高在动画中会跨过阈值，
                                // 拿它判断的话文字会在两套排版之间闪一下
                                if (compressed[i]) {
                                    // 压扁的行放不下完整三行，收成「节号 + 一行起止时间」
                                    Text(
                                        "${row.section}",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = axisTextColor,
                                    )
                                    Text(
                                        "$startText-$endText",
                                        fontSize = 8.sp,
                                        color = axisTextColor.copy(alpha = 0.75f),
                                    )
                                } else if (otherText != null) {
                                    Text(
                                        "${row.section}",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MiuixTheme.colorScheme.onSurface,
                                    )
                                    Text("$startText-$endText", fontSize = 8.sp, color = axisTextColor)
                                    Text(otherText, fontSize = 8.sp, color = axisTextColor.copy(alpha = 0.75f))
                                } else {
                                    Text(
                                        "${row.section}",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MiuixTheme.colorScheme.onSurface,
                                    )
                                    Text(startText, fontSize = 9.sp, color = axisTextColor)
                                    Text(endText, fontSize = 9.sp, color = axisTextColor)
                                }
                            }
                        }
                    }
                }
            }

            // 前景层：课程卡片（冲突课程翻页显示）
            val conflictGroups = remember(layout) { buildConflictGroups(layout.slots) }

            conflictGroups.forEach { group ->
                val topOffset = yOf(group.start)
                val cellHeight = yOf(group.end) - topOffset
                val dayLeft = leftColWidth + dayWidth * (group.dayOfWeek - 1)

                Box(
                    Modifier
                        .offset(x = dayLeft, y = topOffset)
                        .width(dayWidth)
                        .height(cellHeight)
                        // 和背景那层灰格子同一份内缩，课程块才正好盖在格子上
                        .padding(CELL_GAP)
                ) {
                    if (group.slots.size == 1) {
                        val placed = group.slots[0]
                        val slot = placed.slot
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(yOf(placed.end) - yOf(placed.start))
                                .offset(y = yOf(placed.start) - topOffset)
                        ) {
                            CourseCell(
                                name = slot.slotName,
                                location = slot.slotLocation,
                                weekInfo = formatWeekInfo(slot, showWeeks),
                                spanSections = ceil(placed.end - placed.start).toInt().coerceAtLeast(1),
                                color = courseColors.colorOf(slot.slotName),
                                badge = slotBadge(slot),
                                onClick = { onSlotClick(slot) }
                            )
                        }
                    } else {
                        FlippableCourseCell(
                            slots = group.slots,
                            groupStart = group.start,
                            yOf = ::yOf,
                            courseColors = courseColors,
                            showWeeks = showWeeks,
                            slotBadge = slotBadge,
                            onSlotClick = onSlotClick
                        )
                    }
                }
            }

            // ── 当前时间线（横跨整行 + "现在"标签）──
            if (timeLineInfo != null) {
                val (todayDow, yFrac) = timeLineInfo
                val density = androidx.compose.ui.platform.LocalDensity.current
                val leftColPx = with(density) { leftColWidth.toPx() }
                val dayWidthPx = with(density) { dayWidth.toPx() }
                val lineColor = Color(0xFFE53935)  // Material Red 600
                val timelineY = yOf(yFrac)
                val yPos = with(density) { timelineY.toPx() }

                // 时间线（虚线 + 实线 + 圆点）。不画「现在」两字：左轴已经被节次和时间占满，
                // 再塞红字只会让人去读它，而那条线本身已经够说明位置了。
                Box(
                    Modifier
                        .fillMaxSize()
                        .drawBehind {
                            // 虚线横跨整行（淡色）
                            drawLine(
                                color = lineColor.copy(alpha = 0.4f),
                                start = Offset(leftColPx, yPos),
                                end = Offset(size.width, yPos),
                                strokeWidth = 1.dp.toPx(),
                                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                                    floatArrayOf(6.dp.toPx(), 3.dp.toPx())
                                )
                            )
                            // 今日列：加粗实线
                            val dayLeft = leftColPx + dayWidthPx * (todayDow - 1)
                            drawLine(
                                color = lineColor,
                                start = Offset(dayLeft, yPos),
                                end = Offset(dayLeft + dayWidthPx, yPos),
                                strokeWidth = 2.dp.toPx()
                            )
                            // 左侧圆点
                            drawCircle(
                                color = lineColor,
                                radius = 4.dp.toPx(),
                                center = Offset(dayLeft, yPos)
                            )
                        }
                )
            }
        }
        if (bottomPadding > 0.dp) Spacer(Modifier.height(bottomPadding))
    }
}

private fun formatWeekInfo(slot: ScheduleSlot, showWeeks: Boolean): String {
    if (!showWeeks) return ""
    val weeks = (slot as? CourseItem)?.getWeeks().orEmpty()
    return if (weeks.isEmpty()) "" else TermWeeks.formatRanges(weeks) + "周"
}

private fun formatMinuteOfDay(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

// ── 冲突课程翻页卡片 ──

@Composable
private fun FlippableCourseCell(
    slots: List<PlacedSlot>,
    groupStart: Float,
    yOf: (Float) -> Dp,
    courseColors: Map<String, Color>,
    showWeeks: Boolean,
    slotBadge: (ScheduleSlot) -> SlotMark? = { null },
    onSlotClick: (ScheduleSlot) -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { slots.size })
    val groupY = yOf(groupStart)

    Box(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val placed = slots[page]
            val slot = placed.slot

            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(yOf(placed.end) - yOf(placed.start))
                        .offset(y = yOf(placed.start) - groupY)
                ) {
                    CourseCell(
                        name = slot.slotName,
                        location = slot.slotLocation,
                        weekInfo = formatWeekInfo(slot, showWeeks),
                        spanSections = ceil(placed.end - placed.start).toInt().coerceAtLeast(1),
                        color = courseColors.colorOf(slot.slotName),
                        badge = slotBadge(slot),
                        onClick = { onSlotClick(slot) }
                    )
                }
            }
        }

        // 翻页指示器
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 2.dp)
                .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
                .padding(horizontal = 3.dp, vertical = 1.5.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            repeat(slots.size) { index ->
                Box(
                    Modifier
                        .size(4.dp)
                        .background(
                            if (pagerState.currentPage == index) Color.White
                            else Color.White.copy(alpha = 0.4f),
                            CircleShape
                        )
                )
            }
        }
    }
}

/**
 * 课格右上角的标记。[color] 为 null = 中性，由课格挑一个跟底色对比的颜色；
 * 给了颜色就是警示，按给的画。网格不知道点代表什么，含义由调用方定义。
 */
data class SlotMark(val color: Color? = null)

// ── 课程卡片 ──

@Composable
fun CourseCell(
    name: String,
    location: String,
    weekInfo: String = "",
    spanSections: Int,
    color: Color,
    /** 右上角小圆点，null = 不画。见 [SlotMark]。 */
    badge: SlotMark? = null,
    onClick: () -> Unit = {}
) {
    val textColor = if (color.luminance() > 0.5f) Color.Black else Color.White
    top.yukonga.miuix.kmp.basic.Card(
        modifier = Modifier
            .fillMaxSize(),
        onClick = onClick,
        cornerRadius = 12.dp,
        pressFeedbackType = top.yukonga.miuix.kmp.utils.PressFeedbackType.Sink,
        colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = color.copy(alpha = 0.85f))
    ) {
        // Card 的 content 是 ColumnScope，角标要 align，补一层 Box。
        Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.08f), Color.Black.copy(alpha = 0.10f))
                    )
                )
                .padding(horizontal = 4.dp, vertical = 3.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                name, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                color = textColor,
                textAlign = TextAlign.Center,
                maxLines = when {
                    spanSections >= 4 -> 5
                    spanSections >= 3 -> 4
                    else -> 2
                },
                overflow = TextOverflow.Ellipsis,
                lineHeight = 12.sp
            )
            if (spanSections >= 2 && location.isNotEmpty()) {
                Spacer(Modifier.height(1.dp))
                Text(
                    "@$location", fontSize = 8.sp,
                    color = textColor.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (weekInfo.isNotEmpty() && spanSections >= 2) {
                Text(
                    weekInfo, fontSize = 7.sp,
                    color = textColor.copy(alpha = 0.65f),
                    textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
        // 叠在最上层不占布局，格子已经很挤。描一圈免得跟课程色撞在一起。
        badge?.let { mark ->
            // 中性标记用课格自己的文字色：深浅底都看得见，又不抢眼。
            val dot = mark.color ?: textColor.copy(alpha = 0.9f)
            val ring = if (mark.color == null) {
                if (textColor == Color.White) Color.Black.copy(alpha = 0.35f)
                else Color.White.copy(alpha = 0.55f)
            } else {
                textColor.copy(alpha = 0.55f)
            }
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
                    .size(if (mark.color == null) 6.dp else 7.dp)
                    .background(ring, CircleShape)
                    .padding(1.dp)
                    .background(dot, CircleShape)
            )
        }
        }
    }
}
