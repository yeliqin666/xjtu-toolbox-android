package com.xjtu.toolbox.web

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjtu.toolbox.schedule.CampusScheduleApi
import com.xjtu.toolbox.schedule.CourseSlot
import com.xjtu.toolbox.schedule.CourseTable
import com.xjtu.toolbox.schedule.GridRow
import com.xjtu.toolbox.schedule.WeekGridLayout
import com.xjtu.toolbox.schedule.XjtuTime
import com.xjtu.toolbox.schedule.colorOf
import com.xjtu.toolbox.schedule.courseColorMap
import com.xjtu.toolbox.schedule.dateOf
import com.xjtu.toolbox.schedule.layoutWeekGrid
import com.xjtu.toolbox.schedule.weekOf
import com.xjtu.toolbox.util.todayInSystemZone
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.floor

/**
 * 课表（周视图）—— **纵轴几何现在也来自 `:core` 了**。
 *
 * 取数、周次展开、学期日期换算、颜色、`layoutWeekGrid`（作息钟点 / 午休晚休带 / 跨令时对齐 /
 * 早间夜间带）全部来自 `:core`；本文件只做「把已经算好的行列画到屏幕上」。
 *
 * 与上一版的差别（就是「课表还不一致」那一条）：
 * - 上一版按**节次**等距分行、左轴只写节号 ⇒ 冬夏令第 5 节（14:00 vs 14:30）画在同一位置，
 *   10/1 那种跨令时的一周两边对不齐；
 * - 现在用 `:core` 的 [WeekGridLayout]：行结构（含「午休 / 晚休 / 早间 / 夜间」带）、
 *   每条条目的起止、左轴的两套作息时间都来自同一份算法，**与 App 的 `ScheduleGrid` 同源**。
 */
@Composable
fun ScheduleScreen() {
    val cs = MiuixTheme.colorScheme
    var data by remember { mutableStateOf<CourseTable?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var week by remember { mutableStateOf(1) }
    var picked by remember { mutableStateOf<CourseSlot?>(null) }

    LaunchedEffect(Unit) {
        try {
            val d = CampusScheduleApi(toolboxWebClient(), API_BASE).load()
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

    Column(modifier = Modifier.fillMaxSize()) {
        // ── 顶栏：学期 + 周次切换（与上一版一致）──
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
            Spacer(modifier = Modifier.width(LEFT_W))
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
            Row(modifier = Modifier.fillMaxWidth()) {
                // 左轴：节号 + 该节在两套作息下的起止（两套一样时只写一次）
                Column(modifier = Modifier.width(LEFT_W)) {
                    layout.rows.forEachIndexed { i, row ->
                        LeftAxisCell(row, layout, i, cs)
                    }
                }
                for (day in 1..7) {
                    DayColumn(layout, offsets, day, colors) { picked = it }
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

/** 左轴一格：节次行写「节号 + 起止」，时段带写带名。两套作息时间不同时都写出来（与 App 一致）。 */
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
            Text(clock(summer), color = cs.onBackgroundVariant, fontSize = 8.sp)
            if (winter != summer) Text("冬${clock(winter)}", color = cs.onBackgroundVariant, fontSize = 8.sp)
        }
    }
}

@Composable
private fun RowScope.DayColumn(
    layout: WeekGridLayout,
    offsets: List<Dp>,
    day: Int,
    colors: Map<String, Color>,
    onPick: (CourseSlot) -> Unit,
) {
    Box(modifier = Modifier.weight(1f)) {
        Column {
            layout.rows.forEachIndexed { i, _ ->
                Box(modifier = Modifier.fillMaxWidth().height(rowHeight(layout, i)))
            }
        }
        // 这一天的条目：按共享布局给出的行刻度定位（start/end 是「行刻度」，小数 = 行内比例）
        layout.slots.filter { it.slot.slotDayOfWeek == day }.forEach { placed ->
            val top = yOf(offsets, layout, placed.start)
            val bottom = yOf(offsets, layout, placed.end)
            val slot = placed.slot as CourseSlot
            CourseBlock(
                slot,
                colors.colorOf(slot.courseName),
                Modifier.fillMaxWidth().offset(y = top).height(bottom - top),
                onPick,
            )
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

private val LEFT_W = 40.dp

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

private fun clock(range: Pair<Int, Int>): String = "${two(range.first / 60)}:${two(range.first % 60)}"

private fun two(v: Int) = v.toString().padStart(2, '0')
