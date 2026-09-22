package com.xjtu.toolbox.ui

import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.HorizontalDivider

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import top.yukonga.miuix.kmp.utils.overScrollVertical
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjtu.toolbox.schedule.CourseItem
import com.xjtu.toolbox.util.XjtuTime
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
private val LEFT_COL_WIDTH: Dp = 56.dp
/**
 * 没课的节次压到本体的多少。0.56（= 28dp）是"刚好放得下节号 + 一行起止时间"的下限：
 * 再矮就只能像以前那样只画节号了，那样虽然更短，但压扁的行看不出上下课时间。
 */
private const val EMPTY_SECTION_SCALE = 0.56f
/** 午休/晚休分隔带的高度。 */
private val REST_HEIGHT: Dp = 22.dp
/** 课程块在格子里的内缩（格间距）。 */
private val CELL_GAP: Dp = 3.dp

/**
 * 网格画到第几节。作息表有 11 节，但第 11 节（夏令 21:40–22:30）实际几乎不排课，
 * 白占一整行只会让整周更长，所以网格只画到第 10 节。真有第 11 节的课会被夹到
 * 第 10 节里显示（见 toDisplayScheduleSlot 的上限），不会凭空消失。
 */
private const val GRID_LAST_SECTION = 10

// ── 纵轴：节次行 + 午休/晚休带 ─────────────
//
// 网格以前按「小时」分行（8:00–22:00 共 14 行，没课的小时还会被压扁到半高），左轴只有
// 08:00 / 09:00 … 这种整点标签，看不出第几节、也看不出上下课时间。现在按**节次**排版：
// 每节一行、等高等距，节号与起止时间画在左轴；作息表里两处大空档（12:00→14:00、
// 18:00→19:10）插成通栏的「午休」「晚休」带。
//
// 刻度：第 n 节 = [n, n+1)，小数部分 = 节内比例。倍率条目（自定义日程、体育课这类带钟点
// 的）用 XjtuTime.sectionScaleOf 落到行内正确位置，见 toDisplayScheduleSlot。
//
// ⚠️ 本文件下面那三个常量（DAY_START_HOUR / DAY_END_HOUR / MAX_SECTIONS）是**小时**语义的，
// 别处（自定义日程编辑器、Agent 的冲突判定、日程详情文案）还在用 —— 网格不再拿它们当行数，
// 也不要顺手改它们的含义。

/** 纵轴的一行：节次行（[section] 非空）或分隔带（[label] 非空）。 */
private data class AxisRow(
    val section: Int?,
    val label: String,
    val startText: String,
    val endText: String,
    val top: Dp,
    val height: Dp,
)

/** 一天的纵轴：逐节排列，相邻两节间隔超过一小时的插一条通栏分隔带（午休 / 晚休）。 */
private fun buildAxisRows(isSummer: Boolean): List<AxisRow> {
    val rows = mutableListOf<AxisRow>()
    var y = 0.dp
    var prevEndMinute = Int.MIN_VALUE
    XjtuTime.getAllTimes(isSummer)
        .filter { it.first <= GRID_LAST_SECTION }
        .forEach { (section, t) ->
        val startMinute = t.start.hour * 60 + t.start.minute
        if (prevEndMinute != Int.MIN_VALUE && startMinute - prevEndMinute >= 60) {
            val label = if (t.start.hour < 16) "午休" else "晚休"
            rows += AxisRow(null, label, "", "", y, REST_HEIGHT)
            y += REST_HEIGHT
        }
        rows += AxisRow(section, "", t.start.toString(), t.end.toString(), y, SECTION_HEIGHT)
        y += SECTION_HEIGHT
        prevEndMinute = t.end.hour * 60 + t.end.minute
    }
    return rows
}

fun courseColor(courseName: String, allNames: List<String>): Color {
    val index = allNames.distinct().sorted().indexOf(courseName)
    return if (index >= 0) COURSE_COLORS[index % COURSE_COLORS.size] else COURSE_COLORS[0]
}

// ── 通用课格接口 ─────────────────────────

interface ScheduleSlot {
    val slotName: String
    val slotLocation: String
    val slotDayOfWeek: Int
    val slotStartSection: Int
    val slotEndSection: Int
}

private data class DisplayScheduleSlot(
    val sourceSlot: ScheduleSlot,
    override val slotName: String,
    override val slotLocation: String,
    override val slotDayOfWeek: Int,
    override val slotStartSection: Int,
    override val slotEndSection: Int,
    val startFraction: Float,
    val endFraction: Float
) : ScheduleSlot

private fun normalizeFractions(
    startFraction: Float,
    endFraction: Float,
    lastSection: Int,
): Pair<Float, Float> {
    val minDuration = 5f / 60f
    val minValue = 1f                        // 刻度下限 = 第 1 节起点
    val maxValue = (lastSection + 1).toFloat()  // 刻度上限 = 末节结束
    val boundedStart = startFraction.coerceIn(minValue, maxValue - minDuration)
    val rawEnd = endFraction.coerceIn(minValue, maxValue)
    val boundedEnd = if (rawEnd <= boundedStart) {
        (boundedStart + minDuration).coerceAtMost(maxValue)
    } else {
        rawEnd
    }
    return boundedStart to boundedEnd
}

/**
 * 把条目换算到**节次刻度**：第 n 节 = `[n, n+1)`，小数部分 = 节内比例。
 *
 * - 带钟点的条目（自定义日程、体育课这类，`startMinuteOfDay > 0`）→ 按钟点落到行内比例；
 * - 其余（jwxt / jwapp / 历史自定义）本来就只有节次 → 整节占满 `[start, end + 1)`。
 *
 * [daySummer] 是**这一天**按哪套作息（按当天日期），[axisSummer] 是左轴/网格行按哪套。
 * 两者不一致（跨令时那一周里"另一令时"的那些天）时，整块按真实时刻**平移**：
 * 错开多少就代表那一节的钟点差多少，见下面的 shift。
 */
private fun toDisplayScheduleSlot(
    slot: ScheduleSlot,
    daySummer: Boolean,
    axisSummer: Boolean,
    lastSection: Int,
): DisplayScheduleSlot? {
    val day = slot.slotDayOfWeek
    if (day !in 1..7) return null

    val sectionStart = slot.slotStartSection.coerceIn(1, lastSection)
    val sectionEnd = slot.slotEndSection.coerceIn(sectionStart, lastSection)

    val (rawStartFraction, rawEndFraction) = when {
        // 只有「自定义日程」这种本来就用钟点描述的条目才采信分钟；教务 / 教务 App 的课一律以节次为准。
        // 本质原因：jwapp 记回来的钟点是**按某一令时写死**的（不随令时变），换季之后拿当天的作息
        // 去解释它必然错位（曾把 9-10 节的课画成半行、把 3-4 节的课缩成一节）；而节次字段在
        // 详情面板、考勤索引、冲突判定各处都是统一口径，块的位置就该由它决定。
        // 自建条目（「日程」「自定义」两种）带钟点就只认钟点。以前要求钟点换算出的节次和节次字段吻合才采信，
        // 可节次字段是编辑器按「8 点起每小时一节」推的，和作息表对不上：14:00–18:00 的实验课
        // 存成第 7–10 节，钟点换算是第 5–8 节，一不吻合就退回节次字段，块一路拉到晚课 9–10 节。
        slot is CourseItem &&
            (slot.courseType == "日程" || slot.courseCode.startsWith(com.xjtu.toolbox.schedule.CUSTOM_COURSE_CODE_PREFIX)) &&
            slot.startMinuteOfDay > 0 &&
            slot.endMinuteOfDay > slot.startMinuteOfDay -> {
            XjtuTime.sectionScaleOf(slot.startMinuteOfDay, daySummer) to
                XjtuTime.sectionScaleOf(slot.endMinuteOfDay, daySummer)
        }

        else -> sectionStart.toFloat() to (sectionEnd + 1).toFloat()
    }

    // 跨令时那一周里，"另一令时"的天：整块按真实时刻平移，错开多少 = 时间差多少。
    // 以「起始节在两套作息下的开始时刻之差 ÷ 一节时长」为一格的位移量。
    val dayTimes = XjtuTime.getClassTime(sectionStart, daySummer)
    val axisTimes = XjtuTime.getClassTime(sectionStart, axisSummer)
    val shift = if (daySummer == axisSummer || dayTimes == null || axisTimes == null) 0f else {
        val dayStart = dayTimes.start.hour * 60 + dayTimes.start.minute
        val axisStart = axisTimes.start.hour * 60 + axisTimes.start.minute
        val axisSpan = (axisTimes.end.hour * 60 + axisTimes.end.minute - axisStart).coerceAtLeast(1)
        (dayStart - axisStart).toFloat() / axisSpan
    }

    val (startFraction, endFraction) =
        normalizeFractions(rawStartFraction + shift, rawEndFraction + shift, lastSection)
    val startSection = floor(startFraction).toInt().coerceIn(1, lastSection)
    val endSection = ceil(endFraction).toInt().coerceIn(startSection, lastSection)

    return DisplayScheduleSlot(
        sourceSlot = slot,
        slotName = slot.slotName,
        slotLocation = slot.slotLocation,
        slotDayOfWeek = day,
        slotStartSection = startSection,
        slotEndSection = endSection,
        startFraction = startFraction,
        endFraction = endFraction
    )
}

// ── 周选择器（左右箭头式）────────────────

@Composable
fun WeekSelector(currentWeek: Int, totalWeeks: Int, onWeekChange: (Int) -> Unit) {
    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .pointerInput(currentWeek, totalWeeks) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        when {
                            dragOffsetX < -80f && currentWeek < totalWeeks -> onWeekChange(currentWeek + 1)
                            dragOffsetX > 80f && currentWeek > 1 -> onWeekChange(currentWeek - 1)
                        }
                        dragOffsetX = 0f
                    },
                    onHorizontalDrag = { _, dragAmount -> dragOffsetX += dragAmount }
                )
            },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = { if (currentWeek > 1) onWeekChange(currentWeek - 1) },
            enabled = currentWeek > 1
        ) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "上一周",
                tint = if (currentWeek > 1) MiuixTheme.colorScheme.primary
                       else MiuixTheme.colorScheme.outline
            )
        }
        Text(
            "第 $currentWeek 周",
            style = MiuixTheme.textStyles.subtitle,
            fontWeight = FontWeight.Bold,
            color = MiuixTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        IconButton(
            onClick = { if (currentWeek < totalWeeks) onWeekChange(currentWeek + 1) },
            enabled = currentWeek < totalWeeks
        ) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "下一周",
                tint = if (currentWeek < totalWeeks) MiuixTheme.colorScheme.primary
                       else MiuixTheme.colorScheme.outline
            )
        }
    }
}

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
    // 左轴用哪一套作息：默认跟这一周自己的令时；若这一周跨了令时切换（5/1 起夏秋、10/1 起冬春），
    // 按"今天"所在令时画 —— 另一令时的那些天，课程块按真实时刻平移错开（见 toDisplayScheduleSlot）。
    val todaySummer = XjtuTime.isSummerTime()
    val weekSeasons = weekDates?.map { XjtuTime.isSummerTime(it.monthValue) }?.distinct()
    val axisSummer =
        if ((weekSeasons?.size ?: 1) > 1) todaySummer else (weekSeasons?.firstOrNull() ?: todaySummer)
    val axisRows = remember(axisSummer) { buildAxisRows(axisSummer) }
    val plannedLastSection = remember(axisRows) {
        axisRows.mapNotNull { it.section }.maxOrNull() ?: 1
    }

    val displaySlots = remember(slots, weekDates, plannedLastSection, axisSummer) {
        slots.mapNotNull { slot ->
            val slotDate = weekDates?.getOrNull(slot.slotDayOfWeek - 1)
            val daySummer = slotDate?.let { XjtuTime.isSummerTime(it.monthValue) } ?: axisSummer
            toDisplayScheduleSlot(slot, daySummer, axisSummer, plannedLastSection)
        }
    }

    // ── 时段压缩：没课的节次压到半高，有课的全高（总长度随一周的课量伸缩）──
    val targetScales = remember(displaySlots, enableCompression, plannedLastSection) {
        if (!enableCompression) FloatArray(plannedLastSection + 1) { 1f }
        else {
            val used = BooleanArray(plannedLastSection + 1)
            displaySlots.forEach { s ->
                val a = floor(s.startFraction).toInt().coerceIn(1, plannedLastSection)
                // 刻度上界是「末节 + 1」（在末节结束 = 末节 + 1），所以 b 只能钳到 plannedLastSection + 1；
                // 钳到 plannedLastSection 的话，"最后一节还有课"会被漏掉、那一行被误压扁。
                val b = ceil(s.endFraction).toInt()
                    .coerceIn(a + 1, plannedLastSection + 1)
                for (k in a until b) used[k] = true
            }
            FloatArray(plannedLastSection + 1) {
                if (it == 0 || used[it]) 1f else EMPTY_SECTION_SCALE
            }
        }
    }
    // 直接朝目标缩放做动画：以前是"切周先恢复全高、等 250ms 再压回去"的两段动画，
    // 视觉上就是整条轴先被拉长再缩回，左轴文字还会在跨过压扁判断线时闪一下。现在不重置了。
    val animatedScales = (0..plannedLastSection).map { i ->
        animateFloatAsState(
            targetValue = targetScales[i],
            animationSpec = tween(durationMillis = 380),
            label = "sectionScale$i",
        ).value
    }

    // 按当前缩放把轴行摊开：节次行高随动画变，所以 top 每次重算（十来行，开销可忽略）
    val laidOutRows = run {
        var y = 0.dp
        axisRows.map { row ->
            val h = row.section?.let { SECTION_HEIGHT * animatedScales[it] } ?: row.height
            row.copy(top = y, height = h).also { y += h }
        }
    }
    val sectionRows = laidOutRows.filter { it.section != null }.associateBy { it.section!! }
    val lastSection = sectionRows.keys.maxOrNull() ?: 1
    val gridHeight = laidOutRows.last().let { it.top + it.height }

    /** 节次刻度 → 纵坐标：第 n 节那一行内按比例插值（第 n 节 = `[n, n+1)`）。 */
    fun yOf(frac: Float): Dp {
        val sec = floor(frac).toInt().coerceIn(1, lastSection)
        val rest = (frac - sec).coerceIn(0f, 1f)
        val row = sectionRows[sec] ?: return 0.dp
        return row.top + row.height * rest
    }

    /**
     * 块的下沿。整数刻度表示「上一节结束」，必须取**上一行的底边**，不能取下一样行的顶边：
     * 第 4/5 节、第 8/9 节之间夹着午休/晚休带，取下一行顶边会让块整条盖住那条带
     * （3-4 节的课会一直伸到「午休」那一横）。
     */
    fun yOfBlockEnd(frac: Float): Dp {
        val lower = floor(frac).toInt()
        if (frac == lower.toFloat() && lower > 1) {
            val prev = sectionRows[(lower - 1).coerceIn(1, lastSection)] ?: return 0.dp
            return prev.top + prev.height
        }
        return yOf(frac)
    }

    // 当前时间线位置计算（仅在当前周激活）
    val timeLineInfo = if (isCurrentWeek) {
        val now = java.time.LocalTime.now()
        val todayDow = java.time.LocalDate.now().dayOfWeek.value  // 1=Mon...7=Sun
        val nowMinutes = now.hour * 60 + now.minute
        Pair(todayDow, XjtuTime.sectionScaleOf(nowMinutes, axisSummer))
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
            Box(Modifier.width(LEFT_COL_WIDTH), contentAlignment = Alignment.Center) {
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
            val dayWidth = (maxWidth - LEFT_COL_WIDTH) / 7

            // 背景层：左轴（节号 + 起止时间）+ 午休/晚休通栏带。没课的节次被压扁时只留节号。
            val axisTextColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
            Column(Modifier.fillMaxSize()) {
                laidOutRows.forEach { row ->
                    if (row.section == null) {
                        Box(
                            Modifier.fillMaxWidth().height(row.height),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(row.label, fontSize = 10.sp, color = axisTextColor)
                        }
                    } else {
                        Box(Modifier.fillMaxWidth().height(row.height)) {
                            Column(
                                Modifier.width(LEFT_COL_WIDTH).fillMaxHeight(),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                // 用**目标**缩放（不是动画中的行高）决定排版：行高在动画中会跨过阈值，
                                // 拿它判断的话文字会在两套排版之间闪一下
                                if (targetScales[row.section] < 0.99f) {
                                    // 压扁的行放不下完整三行，收成「节号 + 一行起止时间」
                                    Text(
                                        "${row.section}",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = axisTextColor,
                                    )
                                    Text(
                                        "${row.startText}-${row.endText}",
                                        fontSize = 8.sp,
                                        color = axisTextColor.copy(alpha = 0.75f),
                                    )
                                } else {
                                    Text(
                                        "${row.section}",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MiuixTheme.colorScheme.onSurface,
                                    )
                                    Text(row.startText, fontSize = 9.sp, color = axisTextColor)
                                    Text(row.endText, fontSize = 9.sp, color = axisTextColor)
                                }
                            }
                        }
                    }
                }
            }

            // 前景层：课程卡片（冲突课程翻页显示）
            val conflictGroups = remember(displaySlots) { buildConflictGroups(displaySlots) }

            conflictGroups.forEach { group ->
                val topOffset = yOf(group.startFraction)
                val cellHeight = yOfBlockEnd(group.endFraction) - topOffset
                val dayLeft = LEFT_COL_WIDTH + dayWidth * (group.dayOfWeek - 1)

                Box(
                    Modifier
                        .offset(x = dayLeft, y = topOffset)
                        .width(dayWidth)
                        .height(cellHeight)
                        // 和背景那层灰格子同一份内缩，课程块才正好盖在格子上
                        .padding(CELL_GAP)
                ) {
                    if (group.slots.size == 1) {
                        val slot = group.slots[0]
                        val slotTop = yOf(slot.startFraction) - topOffset
                        val slotH = (yOfBlockEnd(slot.endFraction) - yOf(slot.startFraction))
                            .coerceAtLeast(SECTION_HEIGHT * (5f / 60f))
                        val slotDuration = (slot.endFraction - slot.startFraction).coerceAtLeast(5f / 60f)
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(slotH)
                                .offset(y = slotTop)
                        ) {
                            CourseCell(
                                name = slot.slotName,
                                location = slot.slotLocation,
                                weekInfo = formatWeekInfo(slot, showWeeks),
                                spanSections = ceil(slotDuration).toInt().coerceAtLeast(1),
                                color = courseColor(slot.slotName, allCourseNames),
                                badge = slotBadge(slot.sourceSlot),
                                onClick = { onSlotClick(slot.sourceSlot) }
                            )
                        }
                    } else {
                        FlippableCourseCell(
                            slots = group.slots,
                            groupStartFraction = group.startFraction,
                            yOf = ::yOf,
                            yOfBlockEnd = ::yOfBlockEnd,
                            allCourseNames = allCourseNames,
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
                val leftColPx = with(density) { LEFT_COL_WIDTH.toPx() }
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

// ── 冲突分组 ──

private data class ConflictGroup(
    val slots: List<DisplayScheduleSlot>,
    val dayOfWeek: Int,
    val startFraction: Float,
    val endFraction: Float
)

private fun buildConflictGroups(slots: List<DisplayScheduleSlot>): List<ConflictGroup> {
    val validSlots = slots.filter { it.slotDayOfWeek in 1..7 && it.endFraction > it.startFraction }
    val byDay = validSlots.groupBy { it.slotDayOfWeek }
    val groups = mutableListOf<ConflictGroup>()

    byDay.forEach { (day, daySlots) ->
        val n = daySlots.size
        val parent = IntArray(n) { it }
        fun find(x: Int): Int {
            var r = x; while (parent[r] != r) r = parent[r]
            var c = x; while (c != r) { val next = parent[c]; parent[c] = r; c = next }
            return r
        }
        fun union(a: Int, b: Int) { parent[find(a)] = find(b) }

        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val a = daySlots[i]; val b = daySlots[j]
                if (a.startFraction < b.endFraction && b.startFraction < a.endFraction) {
                    union(i, j)
                }
            }
        }

        daySlots.indices.groupBy { find(it) }.values.forEach { indices ->
            val groupSlots = indices.map { daySlots[it] }
            groups.add(ConflictGroup(
                slots = groupSlots,
                dayOfWeek = day,
                startFraction = groupSlots.minOf { it.startFraction },
                endFraction = groupSlots.maxOf { it.endFraction }
            ))
        }
    }
    return groups
}

private fun formatWeekInfo(slot: ScheduleSlot, showWeeks: Boolean): String {
    if (!showWeeks) return ""
    val rawSlot = (slot as? DisplayScheduleSlot)?.sourceSlot ?: slot
    return (rawSlot as? CourseItem)?.getWeeks()?.let { weeks ->
        if (weeks.isEmpty()) "" else {
            val sorted = weeks.sorted()
            val ranges = mutableListOf<String>()
            var s = sorted[0]; var e = sorted[0]
            for (i in 1 until sorted.size) {
                if (sorted[i] == e + 1) e = sorted[i]
                else { ranges.add(if (s == e) "$s" else "$s-$e"); s = sorted[i]; e = sorted[i] }
            }
            ranges.add(if (s == e) "$s" else "$s-$e")
            ranges.joinToString(",") + "周"
        }
    } ?: ""
}

// ── 冲突课程翻页卡片 ──

@Composable
private fun FlippableCourseCell(
    slots: List<DisplayScheduleSlot>,
    groupStartFraction: Float,
    yOf: (Float) -> Dp,
    yOfBlockEnd: (Float) -> Dp,
    allCourseNames: List<String>,
    showWeeks: Boolean,
    slotBadge: (ScheduleSlot) -> SlotMark? = { null },
    onSlotClick: (ScheduleSlot) -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { slots.size })
    val groupY = yOf(groupStartFraction)

    Box(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val slot = slots[page]
            val slotDuration = (slot.endFraction - slot.startFraction).coerceAtLeast(5f / 60f)
            val slotTop = yOf(slot.startFraction) - groupY
            val slotH = (yOfBlockEnd(slot.endFraction) - yOf(slot.startFraction))
                .coerceAtLeast(SECTION_HEIGHT * (5f / 60f))

            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(slotH)
                        .offset(y = slotTop)
                ) {
                    CourseCell(
                        name = slot.slotName,
                        location = slot.slotLocation,
                        weekInfo = formatWeekInfo(slot, showWeeks),
                        spanSections = ceil(slotDuration).toInt().coerceAtLeast(1),
                        color = courseColor(slot.slotName, allCourseNames),
                        badge = slotBadge(slot.sourceSlot),
                        onClick = { onSlotClick(slot.sourceSlot) }
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
