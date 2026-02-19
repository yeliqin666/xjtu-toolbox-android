package com.xjtu.toolbox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjtu.toolbox.schedule.CourseItem
import com.xjtu.toolbox.util.XjtuTime

// ── 共享常量 ──────────────────────────────

val COURSE_COLORS = listOf(
    Color(0xFF1565C0), Color(0xFF2E7D32), Color(0xFFC62828), Color(0xFF6A1B9A),
    Color(0xFFEF6C00), Color(0xFF00838F), Color(0xFFAD1457), Color(0xFF4527A0),
    Color(0xFF00695C), Color(0xFF283593), Color(0xFF558B2F), Color(0xFF8E24AA),
    Color(0xFFD84315),
)

val DAY_HEADERS = listOf("一", "二", "三", "四", "五", "六", "日")
const val MAX_SECTIONS = 11
private val SECTION_HEIGHT: Dp = 58.dp
private val LEFT_COL_WIDTH: Dp = 38.dp

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

// ── 周选择器（左右箭头式）────────────────

@Composable
fun WeekSelector(currentWeek: Int, totalWeeks: Int, onWeekChange: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = { if (currentWeek > 1) onWeekChange(currentWeek - 1) },
            enabled = currentWeek > 1,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "上一周",
                tint = if (currentWeek > 1) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.outlineVariant
            )
        }
        Text(
            "第 $currentWeek 周",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        IconButton(
            onClick = { if (currentWeek < totalWeeks) onWeekChange(currentWeek + 1) },
            enabled = currentWeek < totalWeeks,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "下一周",
                tint = if (currentWeek < totalWeeks) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}

// ── 课表网格（绝对定位，完美对齐）────────

@Composable
fun ScheduleGrid(
    slots: List<ScheduleSlot>,
    allCourseNames: List<String>,
    showWeeks: Boolean = false,
    onSlotClick: (ScheduleSlot) -> Unit = {}
) {
    val scrollState = rememberScrollState()
    val isSummer = remember { XjtuTime.isSummerTime() }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
    ) {
        // ── 星期头 ──
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.width(LEFT_COL_WIDTH), contentAlignment = Alignment.Center) {
                Text("", fontSize = 9.sp)
            }
            DAY_HEADERS.forEach { day ->
                Box(
                    Modifier
                        .weight(1f)
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        day, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
        HorizontalDivider(thickness = 0.5.dp)

        // ── 网格主体：BoxWithConstraints 精确定位 ──
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(SECTION_HEIGHT * MAX_SECTIONS)
        ) {
            val dayWidth = (maxWidth - LEFT_COL_WIDTH) / 7

            // 背景层：节次编号 + 交替色网格
            Column(Modifier.fillMaxSize()) {
                for (section in 1..MAX_SECTIONS) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(SECTION_HEIGHT),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.width(LEFT_COL_WIDTH).fillMaxHeight(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                val timeStr = XjtuTime.getClassTime(section, isSummer)?.start
                                    ?.let { "%d:%02d".format(it.hour, it.minute) } ?: ""
                                Text(
                                    timeStr, fontSize = 8.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                                Text(
                                    "$section", fontSize = 9.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                                )
                            }
                        }
                        for (day in 1..7) {
                            Box(
                                Modifier
                                    .width(dayWidth)
                                    .fillMaxHeight()
                                    .background(
                                        if ((section + day) % 2 == 0)
                                            MaterialTheme.colorScheme.surfaceContainerLowest
                                        else
                                            MaterialTheme.colorScheme.surface
                                    )
                            )
                        }
                    }
                }
            }

            // 前景层：课程卡片（绝对定位，冲突并列）
            // 冲突检测：同一天的时间段重叠 → 并列显示
            val slotLayouts = remember(slots) {
                val result = mutableMapOf<ScheduleSlot, Pair<Int, Int>>() // slot → (colIndex, totalCols)
                val byDay = slots
                    .filter { it.slotDayOfWeek in 1..7 && it.slotStartSection in 1..MAX_SECTIONS }
                    .groupBy { it.slotDayOfWeek }
                byDay.forEach { (_, daySlots) ->
                    val sorted = daySlots.sortedWith(compareBy({ it.slotStartSection }, { it.slotEndSection }))
                    // 贪心分列
                    val columns = mutableListOf<MutableList<ScheduleSlot>>()
                    sorted.forEach { slot ->
                        var placed = false
                        for (col in columns) {
                            val conflicts = col.any { ex ->
                                ex.slotStartSection <= slot.slotEndSection && slot.slotStartSection <= ex.slotEndSection
                            }
                            if (!conflicts) { col.add(slot); placed = true; break }
                        }
                        if (!placed) columns.add(mutableListOf(slot))
                    }
                    // 计算每个 slot 的最大重叠列数
                    columns.forEachIndexed { colIdx, col ->
                        col.forEach { slot ->
                            val overlapCols = columns.count { c ->
                                c.any { o -> o.slotStartSection <= slot.slotEndSection && slot.slotStartSection <= o.slotEndSection }
                            }
                            result[slot] = Pair(colIdx, overlapCols)
                        }
                    }
                }
                result
            }

            slots.forEach { slot ->
                if (slot.slotDayOfWeek in 1..7 && slot.slotStartSection in 1..MAX_SECTIONS) {
                    val (colIndex, totalCols) = slotLayouts[slot] ?: Pair(0, 1)
                    val topOffset = SECTION_HEIGHT * (slot.slotStartSection - 1)
                    val span = (slot.slotEndSection - slot.slotStartSection + 1)
                        .coerceIn(1, MAX_SECTIONS - slot.slotStartSection + 1)
                    val cellHeight = SECTION_HEIGHT * span
                    val dayLeft = LEFT_COL_WIDTH + dayWidth * (slot.slotDayOfWeek - 1)

                    // 冲突课程：纵向等分（每门课占 cellHeight/totalCols 高度），全宽显示
                    val slotWidth = dayWidth
                    val leftOffset = dayLeft
                    val slotHeight = if (totalCols <= 1) cellHeight else cellHeight / totalCols
                    val slotTopOffset = topOffset + slotHeight * colIndex

                    Box(
                        Modifier
                            .offset(x = leftOffset, y = slotTopOffset)
                            .width(slotWidth)
                            .height(slotHeight)
                            .padding(1.dp)
                    ) {
                        // 总览模式显示周次信息
                        val weekStr = if (showWeeks) {
                            (slot as? CourseItem)?.getWeeks()?.let { weeks ->
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
                        } else ""
                        CourseCell(
                            name = slot.slotName,
                            location = slot.slotLocation,
                            weekInfo = weekStr,
                            spanSections = span,
                            color = courseColor(slot.slotName, allCourseNames),
                            onClick = { onSlotClick(slot) }
                        )
                    }
                }
            }
        }
    }
}

// ── 课程卡片 ──

@Composable
fun CourseCell(
    name: String,
    location: String,
    weekInfo: String = "",
    spanSections: Int,
    color: Color,
    onClick: () -> Unit = {}
) {
    Card(
        modifier = Modifier
            .fillMaxSize()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(6.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.88f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 3.dp, vertical = 2.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                name, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                color = if (color.luminance() > 0.5f) Color.Black else Color.White,
                textAlign = TextAlign.Center,
                maxLines = if (spanSections >= 3) 3 else 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 12.sp
            )
            if (spanSections >= 2 && location.isNotEmpty()) {
                Spacer(Modifier.height(1.dp))
                Text(
                    "@$location", fontSize = 8.sp,
                    color = (if (color.luminance() > 0.5f) Color.Black else Color.White).copy(alpha = 0.85f),
                    textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            if (weekInfo.isNotEmpty() && spanSections >= 2) {
                Text(
                    weekInfo, fontSize = 7.sp,
                    color = (if (color.luminance() > 0.5f) Color.Black else Color.White).copy(alpha = 0.65f),
                    textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
