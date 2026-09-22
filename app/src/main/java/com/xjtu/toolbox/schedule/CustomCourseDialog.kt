package com.xjtu.toolbox.schedule

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.ui.DAY_END_HOUR
import com.xjtu.toolbox.ui.DAY_START_HOUR
import com.xjtu.toolbox.ui.MAX_SECTIONS
import kotlin.math.ceil

data class CustomCourseDraft(
    val courseName: String = "",
    val location: String = "",
    val note: String = "",
    val dayOfWeek: Int = 1,
    val startHour: Int = 8,
    val startMinute: Int = 0,
    val endHour: Int = 9,
    val endMinute: Int = 0,
    val selectedWeeks: Set<Int> = emptySet()  // 默认空，由 Dialog 用 totalWeeks 填充
)

/**
 * 自定义日程编辑弹窗 (MIUIX 风格)
 * @param existing 编辑已有课程时传入，为 null 表示新增
 * @param termCode 当前学期代码
 * @param onSave 保存回调
 * @param onDelete 删除回调（仅编辑模式）
 * @param onDismiss 关闭回调
 */
@Composable
fun CustomCourseDialog(
    show: MutableState<Boolean> = mutableStateOf(true),
    existing: CustomCourseEntity? = null,
    termCode: String,
    totalWeeks: Int = 20,
    draft: CustomCourseDraft = CustomCourseDraft(),
    onAutoSave: ((CustomCourseDraft) -> Unit)? = null,
    onSave: (CustomCourseEntity) -> Unit,
    onDelete: ((CustomCourseEntity) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val isEdit = existing != null
    val startDayMinutes = DAY_START_HOUR * 60
    val endDayMinutes = DAY_END_HOUR * 60

    val initialStartMinuteOfDay = remember(existing, draft) {
        if (existing != null) {
            val raw = existing.startMinuteOfDay
            if (raw in startDayMinutes until endDayMinutes) {
                raw
            } else {
                val fallbackSection = existing.startSection.coerceIn(1, MAX_SECTIONS)
                (DAY_START_HOUR + fallbackSection - 1) * 60
            }
        } else {
            val draftMinutes =
                draft.startHour.coerceIn(DAY_START_HOUR, DAY_END_HOUR - 1) * 60 +
                    draft.startMinute.coerceIn(0, 59)
            draftMinutes.coerceIn(startDayMinutes, endDayMinutes - 1)
        }
    }
    val initialEndMinuteOfDay = remember(existing, draft, initialStartMinuteOfDay) {
        if (existing != null) {
            val raw = existing.endMinuteOfDay
            if (raw in (initialStartMinuteOfDay + 1)..endDayMinutes) {
                raw
            } else {
                val fallbackSection = existing.endSection.coerceIn(1, MAX_SECTIONS)
                (DAY_START_HOUR + fallbackSection) * 60
            }
        } else {
            val draftMinutes =
                draft.endHour.coerceIn(DAY_START_HOUR, DAY_END_HOUR) * 60 +
                    draft.endMinute.coerceIn(0, 59)
            val fallbackEnd = (initialStartMinuteOfDay + 60).coerceAtMost(endDayMinutes)
            if (draftMinutes > initialStartMinuteOfDay) {
                draftMinutes.coerceAtMost(endDayMinutes)
            } else {
                fallbackEnd
            }
        }
    }
    var courseName by remember(existing, draft) {
        mutableStateOf(if (existing != null) existing.courseName else draft.courseName)
    }
    val teacher = existing?.teacher.orEmpty()
    var location by remember(existing, draft) {
        mutableStateOf(if (existing != null) existing.location else draft.location)
    }
    var note by remember(existing, draft) {
        mutableStateOf(if (existing != null) decodeAgendaNote(existing.note) else draft.note)
    }
    var dayOfWeek by remember(existing, draft) {
        mutableIntStateOf((if (existing != null) existing.dayOfWeek else draft.dayOfWeek).coerceIn(1, 7))
    }
    var startHour by remember { mutableIntStateOf((initialStartMinuteOfDay / 60).coerceIn(DAY_START_HOUR, DAY_END_HOUR - 1)) }
    var startMinute by remember { mutableIntStateOf((initialStartMinuteOfDay % 60).coerceIn(0, 59)) }
    var endHour by remember { mutableIntStateOf((initialEndMinuteOfDay / 60).coerceIn(DAY_START_HOUR, DAY_END_HOUR)) }
    var endMinute by remember { mutableIntStateOf((initialEndMinuteOfDay % 60).coerceIn(0, 59)) }
    var selectedWeeks by remember(existing, draft) {
        mutableStateOf(
            if (existing != null) {
                existing.weekBits
                    .mapIndexedNotNull { i, c -> if (c == '1') i + 1 else null }
                    .filter { it in 1..totalWeeks }
                    .toSet()
            } else {
                val filtered = draft.selectedWeeks.filter { it in 1..totalWeeks }.toSet()
                if (filtered.isEmpty()) (1..totalWeeks).toSet() else filtered
            }
        )
    }

    fun buildDraft(): CustomCourseDraft {
        val draftSafeEndMinute = if (endHour == DAY_END_HOUR) 0 else endMinute
        return CustomCourseDraft(
            courseName = courseName,
            location = location,
            note = note,
            dayOfWeek = dayOfWeek,
            startHour = startHour,
            startMinute = startMinute,
            endHour = endHour,
            endMinute = draftSafeEndMinute,
            selectedWeeks = selectedWeeks
        )
    }

    val safeEndMinute = if (endHour == DAY_END_HOUR) 0 else endMinute
    val startTotalMinutes = startHour * 60 + startMinute
    val endTotalMinutes = endHour * 60 + safeEndMinute
    val isTimeValid = endTotalMinutes > startTotalMinutes

    val showDeleteConfirm = remember { mutableStateOf(false) }

    // ── 删除确认 ──
    //
    // 用 Window* 而不是 Overlay*：Overlay* 要靠 Scaffold 提供的 LocalDialogStates 宿主才会
    // 被渲染，而本组件的两个调用点（ScheduleScreen 的添加/编辑日程）都在该页 Scaffold **之前**，
    // 拿不到宿主 —— 注册进一个空列表，不报错也不崩溃，就是永远不显示，表现为「点添加日程没反应」。
    if (existing != null && showDeleteConfirm.value) {
        BackHandler { showDeleteConfirm.value = false }
        WindowDialog(
            show = showDeleteConfirm.value,
            title = "删除日程",
            summary = "确定要删除「${existing.courseName}」吗？此操作不可恢复。",
            onDismissRequest = { showDeleteConfirm.value = false }
        ) {
            Row(Modifier.fillMaxWidth()) {
                TextButton(
                    text = "取消",
                    onClick = { showDeleteConfirm.value = false },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = "删除",
                    onClick = { onDelete?.invoke(existing); onDismiss() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColors(
                        textColor = MiuixTheme.colorScheme.error
                    )
                )
            }
        }
    }

    // ── 主编辑面板 ──
    BackHandler(enabled = show.value) {
        if (!isEdit) onAutoSave?.invoke(buildDraft())
        show.value = false
        onDismiss()
    }
    WindowBottomSheet(
        show = show.value,
        title = if (isEdit) "编辑日程" else "添加日程",
        onDismissRequest = {
            if (!isEdit) onAutoSave?.invoke(buildDraft())
            show.value = false
            onDismiss()
        }
    ) {
        // 工具：开始变化后自动调整结束 = start + 30，若越界则到 DAY_END
        fun ensureEndAfterStart() {
            val s = startHour * 60 + startMinute
            val e = endHour * 60 + endMinute
            if (e <= s) {
                val target = (s + 30).coerceAtMost(DAY_END_HOUR * 60)
                endHour = target / 60
                endMinute = target % 60
            }
        }
        // 面板本身是一个限高（窗口高 − 状态栏）的 Column。可滚动区必须用 weight(fill = false)：
        // 不限高的 verticalScroll 会把剩余高度吃光，底部的保存 / 删除按钮就被挤出面板。
        // 这样内容少时面板贴合内容高度，内容多时中间滚动、按钮始终钉在底部。
        Column(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .overScrollVertical()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ── 名称 / 地点 / 备注 ──
            TextField(
                value = courseName,
                onValueChange = { courseName = it },
                label = "活动名称",
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            TextField(
                value = location,
                onValueChange = { location = it },
                label = "地点（可选）",
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            TextField(
                value = note,
                onValueChange = { note = it },
                label = "备注（可选）",
                maxLines = 3,
                modifier = Modifier.fillMaxWidth()
            )

            // ── 星期 ──
            SectionHeader("星期")
            WeekdaySelectorRow(
                dayOfWeek = dayOfWeek,
                onDaySelect = { dayOfWeek = it }
            )

            // ── 时间 ──
            val durationMinutes = endTotalMinutes - startTotalMinutes
            SectionHeader(
                title = "时间",
                trailing = if (isTimeValid) {
                    "%02d:%02d – %02d:%02d · %s".format(
                        startHour, startMinute, endHour, safeEndMinute, formatDuration(durationMinutes)
                    )
                } else "结束需晚于开始",
                trailingColor = if (isTimeValid) MiuixTheme.colorScheme.onSurfaceVariantSummary
                else MiuixTheme.colorScheme.error
            )
            GroupCard {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TimeColumn(
                        label = "开始",
                        hour = startHour,
                        minute = startMinute,
                        hourRange = DAY_START_HOUR until DAY_END_HOUR,
                        minuteRange = 0..59,
                        onHour = { startHour = it; ensureEndAfterStart() },
                        onMinute = { startMinute = it; ensureEndAfterStart() },
                        modifier = Modifier.weight(1f)
                    )
                    TimeColumn(
                        label = "结束",
                        hour = endHour,
                        minute = safeEndMinute,
                        hourRange = DAY_START_HOUR..DAY_END_HOUR,
                        minuteRange = if (endHour == DAY_END_HOUR) 0..0 else 0..59,
                        onHour = {
                            endHour = it
                            if (endHour == DAY_END_HOUR) endMinute = 0
                        },
                        onMinute = { endMinute = if (endHour == DAY_END_HOUR) 0 else it },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // ── 生效周次 ──
            SectionHeader(
                title = "生效周次",
                trailing = if (selectedWeeks.isEmpty()) "至少选一周" else "已选 ${selectedWeeks.size} 周",
                trailingColor = if (selectedWeeks.isEmpty()) MiuixTheme.colorScheme.error
                else MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            GroupCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val all = (1..totalWeeks).toSet()
                    val odd = all.filter { it % 2 == 1 }.toSet()
                    val even = all.filter { it % 2 == 0 }.toSet()
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        WeekPresetChip("全部", selectedWeeks == all, Modifier.weight(1f)) { selectedWeeks = all }
                        WeekPresetChip("单周", selectedWeeks == odd && odd.isNotEmpty(), Modifier.weight(1f)) { selectedWeeks = odd }
                        WeekPresetChip("双周", selectedWeeks == even && even.isNotEmpty(), Modifier.weight(1f)) { selectedWeeks = even }
                        WeekPresetChip("清空", false, Modifier.weight(1f)) { selectedWeeks = emptySet() }
                    }
                    WeekCheckboxGrid(totalWeeks = totalWeeks, selectedWeeks = selectedWeeks, onToggle = { week ->
                        selectedWeeks = if (week in selectedWeeks) selectedWeeks - week else selectedWeeks + week
                    })
                }
            }
        }

        // ── 底部操作区（不随内容滚动）──
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
        if (isEdit) {
            TextButton(
                text = "删除",
                onClick = { showDeleteConfirm.value = true },
                colors = ButtonDefaults.textButtonColors(textColor = MiuixTheme.colorScheme.error),
                modifier = Modifier.weight(1f)
            )
        }
        TextButton(
            text = if (isEdit) "保存" else "添加日程",
            colors = ButtonDefaults.textButtonColorsPrimary(),
            onClick = {
                val weekBitsStr = (1..totalWeeks).joinToString("") { if (it in selectedWeeks) "1" else "0" }
                val startSection = (((startTotalMinutes - startDayMinutes) / 60) + 1)
                    .coerceIn(1, MAX_SECTIONS)
                val endSection = ceil((endTotalMinutes - startDayMinutes) / 60f)
                    .toInt()
                    .coerceIn(startSection, MAX_SECTIONS)
                val entity = (existing ?: CustomCourseEntity(
                    courseName = "", teacher = "", location = "", weekBits = "",
                    dayOfWeek = 1, startSection = 1, endSection = 1, termCode = termCode
                )).copy(
                    courseName = courseName.trim(),
                    teacher = teacher.trim(),
                    location = location.trim(),
                    weekBits = weekBitsStr,
                    dayOfWeek = dayOfWeek,
                    startSection = startSection,
                    endSection = endSection,
                    startMinuteOfDay = startTotalMinutes,
                    endMinuteOfDay = endTotalMinutes,
                    termCode = termCode,
                    note = encodeAgendaNote(note)
                )
                onSave(entity)
                if (!isEdit) onAutoSave?.invoke(CustomCourseDraft())
                show.value = false
                onDismiss()
            },
            enabled = courseName.isNotBlank() && selectedWeeks.isNotEmpty() && isTimeValid,
            modifier = Modifier.weight(if (isEdit) 1.6f else 1f)
        )
        }

        Spacer(Modifier.height(8.dp))
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

private fun formatDuration(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "$m 分钟"
        m == 0 -> "$h 小时"
        else -> "$h 小时 $m 分"
    }
}

/** 分区标题：左边名称，右边一句实时摘要（时长、已选周数或错误提示）。 */
@Composable
private fun SectionHeader(
    title: String,
    trailing: String? = null,
    trailingColor: Color = MiuixTheme.colorScheme.onSurfaceVariantSummary
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MiuixTheme.textStyles.subtitle,
            fontWeight = FontWeight.SemiBold,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (trailing != null) {
            Text(trailing, style = MiuixTheme.textStyles.footnote1, color = trailingColor)
        }
    }
}

/**
 * 面板底色是 background；miuix 深色主题里 surface / surfaceContainer / surfaceContainerHigh
 * 与它同为 #242424，卡片等于隐形。分组块用输入框同款的 secondaryContainer（半透明）分层，
 * 块里的格子再用不透明的 secondaryContainer，深浅两套主题都分得出三层。
 */
@Composable
private fun GroupCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 18.dp,
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            content = content
        )
    }
}

@Composable
private fun TimeColumn(
    label: String,
    hour: Int,
    minute: Int,
    hourRange: IntRange,
    minuteRange: IntRange,
    onHour: (Int) -> Unit,
    onMinute: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            WheelNumberPicker(
                value = hour,
                valueRange = hourRange,
                formatter = { "%02d".format(it) },
                onValueChange = onHour,
                modifier = Modifier.weight(1f)
            )
            Text(
                ":",
                style = MiuixTheme.textStyles.title3,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 2.dp)
            )
            WheelNumberPicker(
                value = minute,
                valueRange = minuteRange,
                formatter = { "%02d".format(it) },
                onValueChange = onMinute,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun WeekPresetChip(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.heightIn(min = 34.dp).clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MiuixTheme.colorScheme.primary.copy(alpha = 0.14f)
        else MiuixTheme.colorScheme.secondaryContainer
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text,
                style = MiuixTheme.textStyles.footnote1,
                color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun WheelNumberPicker(
    value: Int,
    valueRange: IntRange,
    formatter: (Int) -> String,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    top.yukonga.miuix.kmp.basic.NumberPicker(
        value = value.coerceIn(valueRange),
        onValueChange = onValueChange,
        modifier = modifier,
        range = valueRange,
        label = formatter,
        wrapAround = true,
        visibleItemCount = 3
    )
}

@Composable
private fun WeekdaySelectorRow(dayOfWeek: Int, onDaySelect: (Int) -> Unit) {
    val dayLabels = listOf("一", "二", "三", "四", "五", "六", "日")
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        dayLabels.forEachIndexed { index, label ->
            val day = index + 1
            val isSelected = day == dayOfWeek
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .clickable { onDaySelect(day) },
                shape = RoundedCornerShape(14.dp),
                color = if (isSelected) MiuixTheme.colorScheme.primary
                else MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                contentColor = if (isSelected) MiuixTheme.colorScheme.onPrimary
                else MiuixTheme.colorScheme.onSurface
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        label,
                        style = MiuixTheme.textStyles.footnote1,
                        fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
private fun WeekCheckboxGrid(totalWeeks: Int, selectedWeeks: Set<Int>, onToggle: (Int) -> Unit) {
    val rows = (1..totalWeeks).toList().chunked(8)
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        for (row in rows) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                for (week in row) {
                    val isSelected = week in selectedWeeks
                    Surface(
                        modifier = Modifier.weight(1f).heightIn(min = 34.dp).clickable { onToggle(week) },
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.secondaryContainer,
                        contentColor = if (isSelected) MiuixTheme.colorScheme.onPrimary
                        else MiuixTheme.colorScheme.onSurfaceVariantSummary
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                "$week",
                                style = MiuixTheme.textStyles.footnote1,
                                fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal
                            )
                        }
                    }
                }
                if (row.size < 8) {
                    repeat(8 - row.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
