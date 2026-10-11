package com.xjtu.toolbox.web

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ktor.client.HttpClient
import com.xjtu.toolbox.core.net.ApiMode
import com.xjtu.toolbox.schedule.CampusScheduleApi
import com.xjtu.toolbox.schedule.ConflictGroup
import com.xjtu.toolbox.schedule.CourseSlot
import com.xjtu.toolbox.schedule.CourseTable
import com.xjtu.toolbox.schedule.GridRow
import com.xjtu.toolbox.schedule.PlacedSlot
import com.xjtu.toolbox.schedule.WeekGridLayout
import com.xjtu.toolbox.schedule.XjtuTime
import com.xjtu.toolbox.schedule.buildConflictGroups
import com.xjtu.toolbox.schedule.colorOf
import com.xjtu.toolbox.schedule.courseColorMap
import com.xjtu.toolbox.schedule.dateOf
import com.xjtu.toolbox.schedule.layoutWeekGrid
import com.xjtu.toolbox.schedule.weekOf
import com.xjtu.toolbox.util.todayInSystemZone
import kotlin.math.floor
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 课表（周视图）—— 几何、**冲突分组**、左轴口径与当前时间线都对齐 App 的 `ScheduleGrid`。
 *
 * 取数、周次展开、学期日期换算、颜色、`layoutWeekGrid`（作息钟点 / 午休晚休带 / 跨令时对齐 /
 * 早间夜间带）全部来自 `:core`；本文件只做「把已经算好的行列画到屏幕上」。
 *
 * 与上一版的差别（交接文档 §1.2 记的三处已知差异，这一版逐条消掉）：
 *  1. **重叠条目**不再互相盖住：改用 `:core` 的 [buildConflictGroups] 分组，组内多条走
 *     `HorizontalPager` + 圆点指示器 —— 与 App 的 `FlippableCourseCell` 同一套交互（左右翻）；
 *  2. **左轴**从「只写起始时刻」改成 `14:00-14:50` 起止区间；跨令时的那一周按 App 的写法
 *     补一行「冬14:00-14:50」，宽度也跟着放宽（56 → 68dp，与 App 的 `LEFT_COL_WIDTH(_MIXED)` 同值）；
 *  3. **当前时间线**：当前周才画 —— 跨整行淡虚线 + 今日列实线 + 左端圆点，
 *     位置同样由共享算法给出（`layout.positionOf(今天的分钟数, 今天的作息)`），
 *     与 App 的 `timeLineInfo` 是同一个公式。
 */
@Composable
fun ScheduleScreen(
    /**
     * 与整页同一个客户端（由外壳传下来）：serve 模式下它带着 `Authorization` 头，
     * 所以不能在这里自己 new 一个（那样就漏了令牌，所有请求都会被闸门 401）。
     */
    client: HttpClient = toolboxWebClient(),
    /**
     * 这一份 `:web` 在对谁说话（见 [ApiMode]）：两个后端的课表端点形状相同、**信封**不同，
     * 默认旧行为（campus-api）一字不改 —— 由外壳探到 serve 后传 [ApiMode.SERVE]。
     */
    mode: ApiMode = ApiMode.CAMPUS_API,
) {
    val cs = MiuixTheme.colorScheme
    var data by remember { mutableStateOf<CourseTable?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var week by remember { mutableStateOf(1) }
    var picked by remember { mutableStateOf<CourseSlot?>(null) }

    LaunchedEffect(Unit) {
        try {
            val d = CampusScheduleApi(client, API_BASE, mode).load()
            data = d
            week = d.termStart.weekOf(todayInSystemZone().toString())
        } catch (e: Throwable) {
            error = e.message ?: e.toString()
        }
    }

    val d = data
    if (d == null) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Spacer(Modifier.height(20.dp))
            Text(
                if (error == null) "正在从 :core 拉课表…" else "课表加载失败：$error",
                color = if (error == null) cs.onSurface else cs.error,
            )
        }
        return
    }

    val today = todayInSystemZone()
    val todayWeek = remember(d) { d.termStart.weekOf(today.toString()) }
    val maxWeek = remember(d) {
        maxOf(d.totalWeeks.coerceAtLeast(1), d.slots.flatMap { it.weeks }.maxOrNull() ?: 1)
    }
    val shown = remember(d, week) { d.slots.filter { week in it.weeks } }
    val colors = remember(d) { courseColorMap(d.slots.map { it.courseName }) }

    // 每条条目按**它那天的日期**决定令时（与 App 的「课块各按自己那天的作息对齐」同一规则），
    // 所以 5/1、10/1 所在那周两套作息并存也能各自对齐。
    val layout = remember(d, week) {
        layoutWeekGrid(shown) { day -> XjtuTime.isSummerTime(d.termStart.dateOf(week, day).month.ordinal + 1) }
    }
    val offsets = remember(layout) { rowOffsets(layout) }
    val groups = remember(layout) { buildConflictGroups(layout.slots) }
    // 跨令时的那一周要给左轴多留一行「冬14:00-14:50」（与 App 的 mixedWeek 同一个判据）
    val mixed = remember(layout) {
        layout.rows.indices.any { i ->
            layout.rows[i].section != null && layout.times(true)[i] != layout.times(false)[i]
        }
    }
    val leftW = if (mixed) LEFT_W_MIXED else LEFT_W

    // 当前时间线：只有「当前周」才画（与 App 的 isCurrentWeek 一致）。
    // 位置用共享算法：今天的分钟数 → 行刻度；夏令/冬令按**今天**的月份判。
    val lineScale = remember(layout, week, todayWeek, today) {
        if (week != todayWeek) {
            null
        } else {
            val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            layout.positionOf(now.hour * 60 + now.minute, XjtuTime.isSummerTime(now.month.ordinal + 1))
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── 顶栏：学期 + 周次切换 ──
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("第 $week 周" + if (week == todayWeek) "（本周）" else "", color = cs.onSurface, fontSize = 17.sp)
                Text("${d.term} · 开学 ${d.termStart} · 共 $maxWeek 周", color = cs.onBackgroundVariant, fontSize = 11.sp)
            }
            TextButton(text = "‹", onClick = { if (week > 1) week-- }, enabled = week > 1, minWidth = 40.dp)
            TextButton(text = "今天", onClick = { week = todayWeek }, minWidth = 56.dp)
            TextButton(text = "›", onClick = { if (week < maxWeek) week++ }, enabled = week < maxWeek, minWidth = 40.dp)
        }

        // ── 表头：星期 + 日期 ──
        Row(modifier = Modifier.fillMaxWidth().background(cs.surface)) {
            Spacer(modifier = Modifier.width(leftW))
            for (i in 0..6) {
                val date = d.termStart.dateOf(week, i + 1)
                val isToday = date == today
                Column(
                    modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(DAY_NAMES[i], color = if (isToday) cs.primary else cs.onBackgroundVariant, fontSize = 11.sp)
                    Text(date.toString().substring(5), color = if (isToday) cs.primary else cs.onSurface, fontSize = 11.sp)
                }
            }
        }

        // ── 网格：行 = `:core` 算好的行（节次行 / 午休晚休带 / 早间夜间带）──
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    // 左轴：节号 + 该节在两套作息下的起止（两套一样时只写一次）
                    Column(modifier = Modifier.width(leftW)) {
                        layout.rows.forEachIndexed { i, row ->
                            LeftAxisCell(row, layout, i, cs)
                        }
                    }
                    for (day in 1..7) {
                        DayColumn(layout, offsets, groups, day, colors) { picked = it }
                    }
                }
                // 当前时间线画在最上层（跨整行的淡虚线 + 今日列实线 + 左端圆点）
                if (lineScale != null) {
                    TimeLineOverlay(lineScale, layout, offsets, leftW, today.dayOfWeek.ordinal + 1)
                }
            }
            Spacer(Modifier.height(12.dp))
            if (shown.isEmpty()) {
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), insideMargin = PaddingValues(16.dp)) {
                    Text("第 $week 周没有课", color = cs.onSurface)
                }
            }
        }

        // ── 点课后的详情（内联卡片）──
        picked?.let { c ->
            Card(modifier = Modifier.fillMaxWidth().padding(10.dp), insideMargin = PaddingValues(14.dp)) {
                Text(c.courseName, color = cs.onSurface, fontSize = 16.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    listOf(c.teacher, c.room, c.campus).filter { it.isNotEmpty() }.joinToString("  ·  "),
                    color = cs.onBackgroundVariant,
                )
                Text(
                    "周${DAY_NAMES[c.dayOfWeek - 1]} 第 ${c.startSection}-${c.endSection} 节 · 第 ${c.weeks.joinToString(",")} 周",
                    color = cs.onBackgroundVariant,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

/**
 * 左轴一格：节次行写「节号 + 起止区间」，跨令时的周补一行「冬14:00-14:50」；
 * 时段带写带名。口径与 App 的 `ScheduleGrid` 左轴一致（差别只在字号 —— Web 的行更矮）。
 */
@Composable
private fun LeftAxisCell(row: GridRow, layout: WeekGridLayout, index: Int, cs: top.yukonga.miuix.kmp.theme.Colors) {
    Box(modifier = Modifier.fillMaxWidth().height(rowHeights(layout)[index])) {
        if (row.section == null) {
            Text(row.label, color = cs.onBackgroundVariant, fontSize = 9.sp, modifier = Modifier.padding(start = 2.dp))
            return@Box
        }
        val summer = layout.times(true)[index]
        val winter = layout.times(false)[index]
        Column(modifier = Modifier.padding(start = 2.dp)) {
            Text("${row.section}", color = cs.onBackgroundVariant, fontSize = 12.sp)
            Text(range(summer), color = cs.onBackgroundVariant, fontSize = 8.sp)
            if (winter != summer) Text("冬${range(winter)}", color = cs.onBackgroundVariant, fontSize = 8.sp)
        }
    }
}

/** 当前时间线：横跨整行的淡虚线 + 今日列的实线 + 左端圆点（与 App 的 `timeLineInfo` 同一画法）。 */
@Composable
private fun TimeLineOverlay(
    scale: Float,
    layout: WeekGridLayout,
    offsets: List<Dp>,
    leftW: Dp,
    todayDow: Int,
) {
    val density = LocalDensity.current
    val y = yOf(offsets, layout, scale)
    val totalH = rowHeights(layout).fold(0.dp) { acc, h -> acc + h }
    val lineColor = Color(0xFFE53935) // Material Red 600，与 App 同一个颜色
    Box(
        modifier = Modifier.fillMaxWidth().height(totalH).drawBehind {
            val yPos = with(density) { y.toPx() }
            val leftPx = with(density) { leftW.toPx() }
            val dayWidth = (size.width - leftPx) / 7f
            val dayLeft = leftPx + dayWidth * (todayDow - 1)
            drawLine(
                color = lineColor.copy(alpha = 0.4f),
                start = Offset(leftPx, yPos),
                end = Offset(size.width, yPos),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 3.dp.toPx())),
            )
            drawLine(
                color = lineColor,
                start = Offset(dayLeft, yPos),
                end = Offset(dayLeft + dayWidth, yPos),
                strokeWidth = 2.dp.toPx(),
            )
            drawCircle(color = lineColor, radius = 4.dp.toPx(), center = Offset(dayLeft, yPos))
        },
    )
}

@Composable
private fun RowScope.DayColumn(
    layout: WeekGridLayout,
    offsets: List<Dp>,
    groups: List<ConflictGroup>,
    day: Int,
    colors: Map<String, Color>,
    onPick: (CourseSlot) -> Unit,
) {
    Box(modifier = Modifier.weight(1f)) {
        // 背景灰格子（行高与左轴同一份，课块才正好盖在格子上）
        Column {
            layout.rows.forEachIndexed { i, _ ->
                Box(modifier = Modifier.fillMaxWidth().height(rowHeight(layout, i)))
            }
        }
        // 这一天的条目：按冲突分组画。组内 1 条 = 原样；多条 = 可左右翻（与 App 的
        // FlippableCourseCell 同一套交互），**不再互相盖住**。
        groups.filter { it.dayOfWeek == day }.forEach { group ->
            val top = yOf(offsets, layout, group.start)
            val bottom = yOf(offsets, layout, group.end)
            Box(modifier = Modifier.fillMaxWidth().offset(y = top).height(bottom - top)) {
                if (group.slots.size == 1) {
                    val placed = group.slots[0]
                    // ⚠️ 跨模块拿不到 smart cast（`ScheduleSlot` 在 :core，实现在 :app）：
                    // 必须显式转成局部 val 再用 —— 与 App 侧同一处坑（交接文档 §4.4）。
                    val only = placed.slot as CourseSlot
                    CourseBlock(
                        only,
                        colors.colorOf(only.courseName),
                        Modifier
                            .fillMaxWidth()
                            .offset(y = yOf(offsets, layout, placed.start) - top)
                            .height(yOf(offsets, layout, placed.end) - yOf(offsets, layout, placed.start)),
                        onPick,
                    )
                } else {
                    ConflictPager(group, layout, offsets, top, colors, onPick)
                }
            }
        }
    }
}

/** 组内多条：左右翻页 + 圆点指示器（App 用 `HorizontalPager` + 同款圆点）。 */
@Composable
private fun ConflictPager(
    group: ConflictGroup,
    layout: WeekGridLayout,
    offsets: List<Dp>,
    groupTop: Dp,
    colors: Map<String, Color>,
    onPick: (CourseSlot) -> Unit,
) {
    val pager = rememberPagerState(pageCount = { group.slots.size })
    Box(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            val placed: PlacedSlot = group.slots[page]
            val slot = placed.slot as CourseSlot
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = yOf(offsets, layout, placed.start) - groupTop)
                    .height(yOf(offsets, layout, placed.end) - yOf(offsets, layout, placed.start)),
            ) {
                CourseBlock(slot, colors.colorOf(slot.courseName), Modifier.fillMaxSize(), onPick)
            }
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 2.dp)
                .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
                .padding(horizontal = 3.dp, vertical = 1.5.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            repeat(group.slots.size) { index ->
                Box(
                    modifier = Modifier.size(4.dp).background(
                        if (pager.currentPage == index) Color.White else Color.White.copy(alpha = 0.5f)
                    )
                )
            }
        }
    }
}

@Composable
private fun CourseBlock(slot: CourseSlot, color: Color, modifier: Modifier, onPick: (CourseSlot) -> Unit) {
    Box(
        modifier = modifier
            .padding(horizontal = 1.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(color)
            .clickable { onPick(slot) }
            .padding(horizontal = 4.dp, vertical = 3.dp),
    ) {
        Column {
            Text(slot.courseName, color = Color.White, fontSize = 10.sp, maxLines = 4)
            if (slot.room.isNotEmpty()) {
                Text(slot.room, color = Color.White.copy(alpha = 0.85f), fontSize = 8.sp, maxLines = 2)
            }
        }
    }
}

// ─────────────────────────── 行高与坐标换算 ───────────────────────────

/** 左轴宽度：与 App 的 `LEFT_COL_WIDTH` 同值（要放得下「14:00-14:50」）。 */
private val LEFT_W = 56.dp

/** 跨作息那一周左轴多一行「冬14:00-14:50」，与 App 的 `LEFT_COL_WIDTH_MIXED` 同值。 */
private val LEFT_W_MIXED = 68.dp

/** 节次行的高度（有课时）。 */
private val ROW_H: Dp = 52.dp

/** 没课的节次压到多少（与 App 的 `EMPTY_SECTION_SCALE` 同一个数）。 */
private const val EMPTY_SCALE = 0.56f

/** 时段带（午休 / 晚休 / 早间 / 夜间）的高度（与 App 的 `REST_HEIGHT` 同一个数）。 */
private val REST_H: Dp = 22.dp

private val DAY_NAMES = listOf("一", "二", "三", "四", "五", "六", "日")

/** 第 [index] 行的高度：时段带固定矮、节次行按有没有课压缩。 */
private fun rowHeight(layout: WeekGridLayout, index: Int): Dp =
    if (layout.rows[index].section == null) REST_H
    else if (layout.isOccupied(index)) ROW_H
    else ROW_H * EMPTY_SCALE

private fun rowHeights(layout: WeekGridLayout): List<Dp> = layout.rows.indices.map { rowHeight(layout, it) }

/** 每行的顶部偏移（行高不一样，位置必须累积算）。 */
private fun rowOffsets(layout: WeekGridLayout): List<Dp> {
    var acc = 0.dp
    return layout.rows.indices.map { i -> acc.also { acc += rowHeight(layout, i) } }
}

/** 「行刻度」→ 像素：整数部分选行，小数部分按该行高度取比例（与 App 的换算一致）。 */
private fun yOf(offsets: List<Dp>, layout: WeekGridLayout, scale: Float): Dp {
    val whole = floor(scale).toInt().coerceIn(0, offsets.lastIndex)
    val frac = scale - floor(scale)
    return offsets[whole] + rowHeight(layout, whole) * frac
}

/** `(510, 540)` → `08:30-09:00`（左轴写的是**起止区间**，不是单个起始时刻）。 */
private fun range(minutes: Pair<Int, Int>): String = "${clock(minutes.first)}-${clock(minutes.second)}"

private fun clock(minute: Int): String = "${two(minute / 60)}:${two(minute % 60)}"

private fun two(v: Int) = v.toString().padStart(2, '0')
