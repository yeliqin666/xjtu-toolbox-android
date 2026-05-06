package com.xjtu.toolbox.schedule

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
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

    // ── 删除确认 (OverlayDialog) ──
    if (existing != null && showDeleteConfirm.value) {
        BackHandler { showDeleteConfirm.value = false }
        OverlayDialog(
            show = showDeleteConfirm.value,
            title = "删除日程",
            summary = "确定要删除「${existing.courseName}」吗？此操作不可恢复。",
            onDismissRequest = { showDeleteConfirm.value = false }
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(
                    text = "取消",
                    onClick = { showDeleteConfirm.value = false },
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = { onDelete?.invoke(existing); onDismiss() },
                    modifier = Modifier.weight(1f)
                ) { Text("删除") }
            }
        }
    }

    // ── 主编辑面板 ──
    BackHandler(enabled = show.value) {
        if (!isEdit) onAutoSave?.invoke(buildDraft())
        show.value = false
        onDismiss()
    }
    OverlayBottomSheet(
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
        Column(
            modifier = Modifier.overScrollVertical().verticalScroll(rememberScrollState())
        ) {
            // ── 活动名称 / 地点（无外包裹）──
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextField(
                    value = courseName,
                    onValueChange = { courseName = it },
                    label = "活动名称 *",
                    colors = TextFieldDefaults.textFieldColors(
                        borderColor = if (courseName.isNotEmpty() && courseName.isBlank()) {
                            MiuixTheme.colorScheme.error
                        } else {
                            MiuixTheme.colorScheme.primary
                        }
                    ),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                TextField(
                    value = location,
                    onValueChange = { location = it },
                    label = "地点（可选）",
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            // ── 星期 ──
            SmallTitle("星期")
            GroupCard {
                WeekdaySelectorRow(
                    dayOfWeek = dayOfWeek,
                    onDaySelect = { dayOfWeek = it }
                )
            }

            // ── 时间 ──
            SmallTitle("时间")
            GroupCard {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("开始", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            WheelNumberPicker(
                                value = startHour,
                                valueRange = DAY_START_HOUR until DAY_END_HOUR,
                                formatter = { "%02d".format(it) },
                                onValueChange = { startHour = it; ensureEndAfterStart() },
                                modifier = Modifier.weight(1f)
                            )
                            WheelNumberPicker(
                                value = startMinute,
                                valueRange = 0..59,
                                formatter = { "%02d".format(it) },
                                onValueChange = { startMinute = it; ensureEndAfterStart() },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("结束", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            WheelNumberPicker(
                                value = endHour,
                                valueRange = DAY_START_HOUR..DAY_END_HOUR,
                                formatter = { "%02d".format(it) },
                                onValueChange = {
                                    endHour = it
                                    if (endHour == DAY_END_HOUR) endMinute = 0
                                },
                                modifier = Modifier.weight(1f)
                            )
                            WheelNumberPicker(
                                value = if (endHour == DAY_END_HOUR) 0 else endMinute,
                                valueRange = if (endHour == DAY_END_HOUR) 0..0 else 0..59,
                                formatter = { "%02d".format(it) },
                                onValueChange = { endMinute = if (endHour == DAY_END_HOUR) 0 else it },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // ── 生效周次 ──
            SmallTitle("生效周次")
            GroupCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        @Composable
                        fun WeekActionChip(text: String, modifier: Modifier, onClick: () -> Unit) {
                            Surface(
                                modifier = modifier.clickable { onClick() },
                                shape = RoundedCornerShape(20.dp),
                                color = MiuixTheme.colorScheme.surfaceContainer
                            ) {
                                Box(Modifier.padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                                    Text(
                                        text,
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = MiuixTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                        WeekActionChip("全选", Modifier.weight(1f)) { selectedWeeks = (1..totalWeeks).toSet() }
                        WeekActionChip("单周", Modifier.weight(1f)) { selectedWeeks = (1..totalWeeks).filter { it % 2 == 1 }.toSet() }
                        WeekActionChip("双周", Modifier.weight(1f)) { selectedWeeks = (1..totalWeeks).filter { it % 2 == 0 }.toSet() }
                        WeekActionChip("清空", Modifier.weight(1f)) { selectedWeeks = emptySet() }
                    }
                    WeekCheckboxGrid(totalWeeks = totalWeeks, selectedWeeks = selectedWeeks, onToggle = { week ->
                        selectedWeeks = if (week in selectedWeeks) selectedWeeks - week else selectedWeeks + week
                    })
                }
            }

        }

        // ── 底部操作区 ──
        Spacer(Modifier.height(16.dp))
        Button(
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
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isEdit) "保存日程" else "添加日程")
        }

        if (isEdit) {
            Spacer(Modifier.height(8.dp))
            TextButton(
                text = "删除此日程",
                onClick = { showDeleteConfirm.value = true },
                colors = ButtonDefaults.textButtonColors(textColor = MiuixTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(12.dp))
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

@Composable
private fun GroupCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            content = content
        )
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
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        dayLabels.forEachIndexed { index, label ->
            val day = index + 1
            val isSelected = day == dayOfWeek
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 36.dp)
                    .clickable { onDaySelect(day) },
                shape = RoundedCornerShape(8.dp),
                color = if (isSelected) MiuixTheme.colorScheme.primaryContainer
                else MiuixTheme.colorScheme.surfaceContainer,
                contentColor = if (isSelected) MiuixTheme.colorScheme.onPrimaryContainer
                else MiuixTheme.colorScheme.onSurfaceVariantSummary
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
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (row in rows) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (week in row) {
                    val isSelected = week in selectedWeeks
                    Surface(
                        modifier = Modifier.weight(1f).heightIn(min = 32.dp).clickable { onToggle(week) },
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) MiuixTheme.colorScheme.primaryContainer
                        else MiuixTheme.colorScheme.surfaceContainer,
                        contentColor = if (isSelected) MiuixTheme.colorScheme.onPrimaryContainer
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
