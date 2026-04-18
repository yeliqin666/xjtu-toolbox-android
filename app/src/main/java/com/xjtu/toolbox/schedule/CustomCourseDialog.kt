package com.xjtu.toolbox.schedule

import android.widget.NumberPicker
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
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.extra.SuperBottomSheet
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.xjtu.toolbox.ui.DAY_END_HOUR
import com.xjtu.toolbox.ui.DAY_START_HOUR
import com.xjtu.toolbox.ui.MAX_SECTIONS
import kotlin.math.ceil

private const val CUSTOM_SCHEDULE_TOTAL_WEEKS = 16

data class CustomCourseDraft(
    val courseName: String = "",
    val location: String = "",
    val note: String = "",
    val dayOfWeek: Int = 1,
    val startHour: Int = 8,
    val startMinute: Int = 0,
    val endHour: Int = 9,
    val endMinute: Int = 0,
    val selectedWeeks: Set<Int> = (1..CUSTOM_SCHEDULE_TOTAL_WEEKS).toSet()
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
                    .filter { it in 1..CUSTOM_SCHEDULE_TOTAL_WEEKS }
                    .toSet()
            } else {
                draft.selectedWeeks.filter { it in 1..CUSTOM_SCHEDULE_TOTAL_WEEKS }.toSet()
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

    // ── 删除确认 (SuperDialog) ──
    if (existing != null && showDeleteConfirm.value) {
        BackHandler { showDeleteConfirm.value = false }
        SuperDialog(
            show = showDeleteConfirm,
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
    SuperBottomSheet(
        show = show,
        title = if (isEdit) "编辑日程" else "添加日程",
        onDismissRequest = {
            if (!isEdit) onAutoSave?.invoke(buildDraft())
            show.value = false
            onDismiss()
        }
    ) {
        Column(
            modifier = Modifier.overScrollVertical().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── 活动名称 / 地点（同一行） ──
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(
                    value = courseName,
                    onValueChange = { courseName = it },
                    label = "活动名称 *",
                    borderColor = if (courseName.isNotEmpty() && courseName.isBlank()) MiuixTheme.colorScheme.error else Color.Unspecified,
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

            HorizontalDivider(color = MiuixTheme.colorScheme.outline.copy(alpha = 0.2f))

            // ── 星期 ──
            Text("星期", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            WeekdaySelectorRow(
                dayOfWeek = dayOfWeek,
                onDaySelect = { dayOfWeek = it }
            )

            // ── 时间（滚轮式） ──

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("开始", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        WheelNumberPicker(
                            value = startHour,
                            valueRange = DAY_START_HOUR until DAY_END_HOUR,
                            formatter = { "%02d".format(it) },
                            onValueChange = { startHour = it },
                            modifier = Modifier.weight(1f)
                        )
                        WheelNumberPicker(
                            value = startMinute,
                            valueRange = 0..59,
                            formatter = { "%02d".format(it) },
                            onValueChange = { startMinute = it },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("结束", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
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

            if (!isTimeValid) {
                Text(
                    "结束时间需晚于开始时间",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.error
                )
            }

            HorizontalDivider(color = MiuixTheme.colorScheme.outline.copy(alpha = 0.2f))

            // ── 生效周次 ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("生效周次", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.weight(1f))
                TextButton(text = "全选", onClick = { selectedWeeks = (1..CUSTOM_SCHEDULE_TOTAL_WEEKS).toSet() })
                TextButton(text = "单周", onClick = { selectedWeeks = (1..CUSTOM_SCHEDULE_TOTAL_WEEKS).filter { it % 2 == 1 }.toSet() })
                TextButton(text = "双周", onClick = { selectedWeeks = (1..CUSTOM_SCHEDULE_TOTAL_WEEKS).filter { it % 2 == 0 }.toSet() })
                TextButton(text = "清空", onClick = { selectedWeeks = emptySet() })
            }
            WeekCheckboxGrid(selectedWeeks = selectedWeeks, onToggle = { week ->
                selectedWeeks = if (week in selectedWeeks) selectedWeeks - week else selectedWeeks + week
            })

            // ── 备注 ──
            TextField(
                value = note,
                onValueChange = { note = it },
                label = "备注（可选）",
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // ── 底部操作区 ──
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                val weekBitsStr = (1..CUSTOM_SCHEDULE_TOTAL_WEEKS).joinToString("") { if (it in selectedWeeks) "1" else "0" }
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

        Spacer(Modifier.height(16.dp))
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.ime))
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
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
    val values = remember(valueRange.first, valueRange.last) { valueRange.toList() }
    val displayedValueLabels = remember(values) { values.map(formatter).toTypedArray() }

    AndroidView(
        modifier = modifier.height(118.dp),
        factory = { context ->
            NumberPicker(context).apply {
                descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
                wrapSelectorWheel = true
                minValue = 0
                maxValue = (values.size - 1).coerceAtLeast(0)
                displayedValues = displayedValueLabels
                setOnValueChangedListener { _, _, newVal ->
                    if (newVal in values.indices) onValueChange(values[newVal])
                }
            }
        },
        update = { picker ->
            val targetIndex = values.indexOf(value).takeIf { it >= 0 } ?: 0
            if (picker.maxValue != (values.size - 1).coerceAtLeast(0)) {
                picker.displayedValues = null
                picker.minValue = 0
                picker.maxValue = (values.size - 1).coerceAtLeast(0)
                picker.displayedValues = displayedValueLabels
            }
            if (picker.value != targetIndex) picker.value = targetIndex
            picker.setOnValueChangedListener { _, _, newVal ->
                if (newVal in values.indices) onValueChange(values[newVal])
            }
        }
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
                color = if (isSelected) MiuixTheme.colorScheme.primary
                else MiuixTheme.colorScheme.surfaceVariant,
                contentColor = if (isSelected) MiuixTheme.colorScheme.onPrimary
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
private fun WeekCheckboxGrid(selectedWeeks: Set<Int>, onToggle: (Int) -> Unit) {
    val rows = (1..CUSTOM_SCHEDULE_TOTAL_WEEKS).toList().chunked(8)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (row in rows) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (week in row) {
                    val isSelected = week in selectedWeeks
                    Surface(
                        modifier = Modifier.weight(1f).heightIn(min = 30.dp).clickable { onToggle(week) },
                        shape = RoundedCornerShape(6.dp),
                        color = if (isSelected) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.surfaceVariant,
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
