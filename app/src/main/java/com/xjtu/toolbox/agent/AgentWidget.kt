package com.xjtu.toolbox.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.attendance.AttendanceWaterRecord
import com.xjtu.toolbox.attendance.WaterType
import com.xjtu.toolbox.card.CardInfo
import com.xjtu.toolbox.emptyroom.RoomInfo
import com.xjtu.toolbox.schedule.CourseItem
import com.xjtu.toolbox.schedule.ExamItem
import com.xjtu.toolbox.score.ReportedGrade
import com.xjtu.toolbox.util.XjtuTime
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

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

/** 考勤记录。 */
data class AttendanceWidget(val records: List<AttendanceWaterRecord>) : AgentWidget

/** 成绩 + 加权 GPA 汇总。 */
data class GradeWidget(val grades: List<ReportedGrade>, val gpa: Double?, val totalPoints: Double) : AgentWidget

/** 校园卡信息。 */
data class CardWidget(val info: CardInfo) : AgentWidget

private val DAY_NAMES = listOf("", "周一", "周二", "周三", "周四", "周五", "周六", "周日")

// ── 渲染入口 ─────────────────────────────────────────────────────────────

@Composable
fun AgentWidgetView(widget: AgentWidget, modifier: Modifier = Modifier) {
    when (widget) {
        is ScheduleWidget   -> ScheduleWidgetView(widget, modifier)
        is ExamWidget       -> ExamWidgetView(widget, modifier)
        is RoomWidget       -> RoomWidgetView(widget, modifier)
        is AttendanceWidget -> AttendanceWidgetView(widget, modifier)
        is GradeWidget      -> GradeWidgetView(widget, modifier)
        is CardWidget       -> CardWidgetView(widget, modifier)
    }
}

@Composable
private fun WidgetCard(
    title: String,
    accent: Color,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(width = 4.dp, height = 15.dp)
                        .background(accent, RoundedCornerShape(2.dp))
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    title,
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                subtitle?.let {
                    Surface(shape = RoundedCornerShape(8.dp), color = accent.copy(alpha = 0.12f)) {
                        Text(it, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MiuixTheme.textStyles.footnote1, color = accent,
                            fontWeight = FontWeight.Medium)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MiuixTheme.colorScheme.outline.copy(alpha = 0.10f))
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

/** 一行紧凑条目：左侧主文本（可两行），右侧小标签。 */
@Composable
private fun WidgetRow(primary: String, secondary: String?, trailing: String? = null) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(primary, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            secondary?.let {
                Text(it, style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing?.let {
            Spacer(Modifier.width(8.dp))
            Text(it, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.primary)
        }
    }
}

// ── 各控件 ───────────────────────────────────────────────────────────────

@Composable
private fun ScheduleWidgetView(w: ScheduleWidget, modifier: Modifier) {
    val byDay = w.courses.sortedWith(compareBy({ it.dayOfWeek }, { it.startSection }))
        .groupBy { it.dayOfWeek }
    WidgetCard(title = w.title, accent = MiuixTheme.colorScheme.primary, subtitle = "${w.courses.size} 节", modifier = modifier) {
        if (w.courses.isEmpty()) {
            Text("没有课", style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            return@WidgetCard
        }
        byDay.entries.forEachIndexed { idx, (day, list) ->
            if (idx > 0) {
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = MiuixTheme.colorScheme.outline.copy(alpha = 0.08f))
                Spacer(Modifier.height(2.dp))
            }
            if (byDay.size > 1) {
                Text(DAY_NAMES.getOrElse(day) { "" }, style = MiuixTheme.textStyles.footnote1,
                    fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 2.dp))
            }
            list.forEach { c ->
                WidgetRow(
                    primary = c.courseName,
                    secondary = "第${c.startSection}-${c.endSection}节 ${XjtuTime.getClassStartStr(c.startSection)} · ${c.location}" +
                        if (c.teacher.isNotBlank()) " · ${c.teacher}" else ""
                )
            }
        }
    }
}

@Composable
private fun ExamWidgetView(w: ExamWidget, modifier: Modifier) {
    WidgetCard(title = "考试安排", accent = MiuixTheme.colorScheme.primaryVariant, subtitle = "${w.exams.size} 场", modifier = modifier) {
        w.exams.take(12).forEach { e ->
            WidgetRow(
                primary = e.courseName,
                secondary = "${e.examDate} ${e.examTime} · ${e.location}",
                trailing = e.seatNumber.ifBlank { "待定" }
            )
        }
    }
}

@Composable
private fun RoomWidgetView(w: RoomWidget, modifier: Modifier) {
    val shown = w.rooms.take(12)
    WidgetCard(title = "空闲教室", accent = MiuixTheme.colorScheme.secondary, subtitle = w.condition, modifier = modifier) {
        shown.forEach { r ->
            val freeSlots = r.status.mapIndexedNotNull { i, s -> if (s == 0) i + 1 else null }
            val nowFree = w.currentPeriod in r.status.indices && r.status[w.currentPeriod] == 0
            WidgetRow(
                primary = r.name,
                secondary = "空闲：${freeSlots.joinToString("、") { "${it}节" }}",
                trailing = "${r.size}座"
            )
            if (nowFree) {
                Surface(shape = RoundedCornerShape(6.dp),
                    color = MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)) {
                    Text("本节空闲", modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                        style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.primary)
                }
            }
        }
        if (w.rooms.size > shown.size) {
            Text("…还有 ${w.rooms.size - shown.size} 间", style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun AttendanceWidgetView(w: AttendanceWidget, modifier: Modifier) {
    WidgetCard(title = "考勤记录", accent = MiuixTheme.colorScheme.primary, subtitle = "${w.records.size} 条", modifier = modifier) {
        w.records.forEach { r ->
            val color = when (r.status) {
                WaterType.NORMAL -> MiuixTheme.colorScheme.primary
                WaterType.LEAVE  -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                else             -> MiuixTheme.colorScheme.error
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.courseName, style = MiuixTheme.textStyles.body2,
                        fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${r.date} 第${r.startTime}-${r.endTime}节" +
                        if (r.location.isNotBlank()) " · ${r.location}" else "",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                Spacer(Modifier.width(8.dp))
                Text(r.status.displayName, style = MiuixTheme.textStyles.body2,
                    fontWeight = FontWeight.Bold, color = color)
            }
        }
    }
}

@Composable
private fun GradeWidgetView(w: GradeWidget, modifier: Modifier) {
    WidgetCard(
        title = "成绩",
        accent = MiuixTheme.colorScheme.primaryVariant,
        subtitle = w.gpa?.let { "GPA %.2f".format(it) },
        modifier = modifier
    ) {
        Text("共 ${w.grades.size} 门 · 计入学分 ${"%.1f".format(w.totalPoints)}",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Spacer(Modifier.height(4.dp))
        w.grades.take(15).forEach { g ->
            val scoreColor = when {
                g.score.toDoubleOrNull()?.let { it < 60 } == true -> MiuixTheme.colorScheme.error
                g.score.contains("不及格") -> MiuixTheme.colorScheme.error
                g.score.toDoubleOrNull()?.let { it >= 90 } == true -> MiuixTheme.colorScheme.primary
                else -> MiuixTheme.colorScheme.onSurface
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(g.courseName, style = MiuixTheme.textStyles.body2,
                        fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${g.coursePoint} 学分" + (g.gpa?.let { " · 绩点 %.2f".format(it) } ?: ""),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                Spacer(Modifier.width(8.dp))
                Text(g.score, style = MiuixTheme.textStyles.body2,
                    fontWeight = FontWeight.Bold, color = scoreColor)
            }
        }
        if (w.grades.size > 15) {
            Text("…还有 ${w.grades.size - 15} 门", style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun CardWidgetView(w: CardWidget, modifier: Modifier) {
    val info = w.info
    WidgetCard(title = "校园卡", accent = MiuixTheme.colorScheme.primary, subtitle = info.cardType.ifBlank { null }, modifier = modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("¥%.2f".format(info.balance), style = MiuixTheme.textStyles.headline1,
                fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text("余额", style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(bottom = 4.dp))
        }
        if (info.pendingAmount > 0) {
            Text("待入账 ¥%.2f".format(info.pendingAmount), style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        val flags = buildList {
            if (info.lostFlag) add("已挂失")
            if (info.frozenFlag) add("已冻结")
        }
        if (flags.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(flags.joinToString(" · "), style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.error, fontWeight = FontWeight.Bold)
        }
    }
}
