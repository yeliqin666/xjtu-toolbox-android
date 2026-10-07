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
import com.xjtu.toolbox.schedule.colorOf
import com.xjtu.toolbox.schedule.courseColorMap
import com.xjtu.toolbox.schedule.dateOf
import com.xjtu.toolbox.schedule.weekOf
import com.xjtu.toolbox.util.todayInSystemZone
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 课表（周视图）——**第一个由 :core 驱动的非 Android 屏**。
 *
 * 这一屏刻意把「业务」压到零：`ZCMC` 展开、学期周次、开学日换算、上游 47 列的宽松收敛全来自
 * `:core`（`com.xjtu.toolbox.schedule` 的 [CourseTable] / [CourseSlot] / [parseWeeksText]），
 * 本文件只做两件渲染的事 —— 按「星期 × 节次」摆块、把课程名映射成稳定颜色。
 *
 * 与 :app 现有那一屏的差别（照实标注，不是漏了）：这里按**节次**分行（1–11 节），
 * 还没上「作息表 + 钟点」那套纵轴（冬夏令第 5 节 14:00/14:30 的换季几何在
 * `WeekGridLayout` 里，属于第 3 步的搬迁内容）；也没有课程详情下钻、考试倒计时、ICS 导出。
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

    val todayWeek = remember(d) { d.termStart.weekOf(todayInSystemZone().toString()) }
    val maxWeek = remember(d) {
        maxOf(d.totalWeeks.coerceAtLeast(1), d.slots.flatMap { it.weeks }.maxOrNull() ?: 1)
    }
    val lastSection = remember(d) { d.slots.maxOfOrNull { it.endSection }?.coerceIn(1, 11) ?: 10 }
    val shown = d.slots.filter { week in it.weeks }
    val colors = remember(d) { courseColorMap(d.slots.map { it.courseName }) }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── 顶栏：学期 + 周次切换 ──
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "第 $week 周" + if (week == todayWeek) "（本周）" else "",
                    color = cs.onSurface,
                    fontSize = 17.sp,
                )
                Text(
                    "${d.term} · 开学 ${d.termStart} · 共 $maxWeek 周",
                    color = cs.onBackgroundVariant,
                    fontSize = 11.sp,
                )
            }
            TextButton(text = "‹", onClick = { if (week > 1) week-- }, enabled = week > 1, minWidth = 40.dp)
            TextButton(text = "今天", onClick = { week = todayWeek }, minWidth = 56.dp)
            TextButton(text = "›", onClick = { if (week < maxWeek) week++ }, enabled = week < maxWeek, minWidth = 40.dp)
        }

        // ── 表头：星期 + 日期（日期由 :core 的开学日 + 周次算出来）──
        val today = todayInSystemZone()
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

        // ── 网格：一列一天，一行一节 ──
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.width(LEFT_W)) {
                    for (section in 1..lastSection) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(ROW_H),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("$section", color = cs.onBackgroundVariant, fontSize = 12.sp)
                        }
                    }
                }
                for (day in 1..7) {
                    DayColumn(lastSection, shown.filter { it.dayOfWeek == day }, colors) { picked = it }
                }
            }
            Spacer(Modifier.height(12.dp))
            if (shown.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    insideMargin = PaddingValues(16.dp),
                ) {
                    Text("第 $week 周没有课", color = cs.onSurface)
                }
            }
        }

        // ── 点课后的详情（内联卡片；详情面板/下钻留给第 3 步）──
        picked?.let { c ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(10.dp),
                insideMargin = PaddingValues(14.dp),
            ) {
                Text(c.courseName, color = cs.onSurface, fontSize = 16.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    listOf(c.teacher, c.room, c.campus).filter { it.isNotEmpty() }.joinToString("  ·  "),
                    color = cs.onBackgroundVariant,
                )
                Text(
                    "周${DAY_NAMES[c.dayOfWeek - 1]} 第 ${c.startSection}-${c.endSection} 节 · " +
                        "第 ${c.weeks.joinToString(",")} 周",
                    color = cs.onBackgroundVariant,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

/** 一天的列：同节多条横向平分；块高 = 跨节数 × 行高（节次视图下这是精确的）。 */
@Composable
private fun RowScope.DayColumn(
    lastSection: Int,
    slots: List<CourseSlot>,
    colors: Map<String, Color>,
    onPick: (CourseSlot) -> Unit,
) {
    Column(modifier = Modifier.weight(1f)) {
        for (section in 1..lastSection) {
            Box(modifier = Modifier.fillMaxWidth().height(ROW_H)) {
                val starting = slots.filter { it.startSection == section }
                if (starting.isNotEmpty()) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        starting.forEach { slot ->
                            val span = (slot.endSection - slot.startSection + 1).coerceAtLeast(1)
                            CourseBlock(
                                slot,
                                colors.colorOf(slot.courseName),
                                Modifier.weight(1f).height(ROW_H * span.toFloat()),
                                onPick,
                            )
                        }
                    }
                }
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

// ─────────────────────────── 常量与小组件 ───────────────────────────

private val LEFT_W = 32.dp
private val ROW_H: Dp = 56.dp
private val DAY_NAMES = listOf("一", "二", "三", "四", "五", "六", "日")

// 颜色与「今天是哪天」都**不再在这里实现**：
// - `courseColorMap` / `defaultCourseColor` / `colorOf` 来自 :core 的 `schedule/CourseColorMap.kt`
//   （那里还有读用户自定义色 `CourseColors.of(name)` 的分支 —— 原先这里抄了一份不带它的，
//   表现为「同一门课 App 里是你改过的颜色、Web 里是默认色」）；
// - `todayInSystemZone()` 来自 :core 的 `util/Today.kt`（原先这里另写了一个 `browserTodayIso`）。
