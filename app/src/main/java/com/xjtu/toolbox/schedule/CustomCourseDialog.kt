package com.xjtu.toolbox.schedule

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * 自定义课程编辑弹窗
 * @param existing 编辑已有课程时传入，为 null 表示新增
 * @param termCode 当前学期代码
 * @param onSave 保存回调
 * @param onDelete 删除回调（仅编辑模式）
 * @param onDismiss 关闭回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomCourseDialog(
    existing: CustomCourseEntity? = null,
    termCode: String,
    onSave: (CustomCourseEntity) -> Unit,
    onDelete: ((CustomCourseEntity) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val isEdit = existing != null

    var courseName by remember { mutableStateOf(existing?.courseName ?: "") }
    var teacher by remember { mutableStateOf(existing?.teacher ?: "") }
    var location by remember { mutableStateOf(existing?.location ?: "") }
    var note by remember { mutableStateOf(existing?.note ?: "") }
    var dayOfWeek by remember { mutableIntStateOf(existing?.dayOfWeek ?: 1) }
    var startSection by remember { mutableIntStateOf(existing?.startSection ?: 1) }
    var endSection by remember { mutableIntStateOf(existing?.endSection ?: 2) }
    var selectedWeeks by remember {
        mutableStateOf(
            if (existing != null) {
                existing.weekBits.mapIndexedNotNull { i, c -> if (c == '1') i + 1 else null }.toSet()
            } else {
                (1..16).toSet()  // 默认1-16周
            }
        )
    }

    var showDeleteConfirm by remember { mutableStateOf(false) }
    val nameError = courseName.isBlank()

    if (showDeleteConfirm && existing != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("确认删除") },
            text = { Text("确定要删除「${existing.courseName}」吗？") },
            confirmButton = {
                TextButton(onClick = { onDelete?.invoke(existing); onDismiss() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text("删除")
                }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") } }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(if (isEdit) "编辑课程" else "添加课程")
                if (isEdit) {
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(Icons.Default.Delete, "删除", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 课程名
                OutlinedTextField(
                    value = courseName,
                    onValueChange = { courseName = it },
                    label = { Text("课程名称 *") },
                    isError = nameError && courseName.isNotEmpty(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // 教师 + 教室
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = teacher,
                        onValueChange = { teacher = it },
                        label = { Text("教师") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = location,
                        onValueChange = { location = it },
                        label = { Text("教室") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                // 星期几
                Text("星期", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    val days = listOf("一", "二", "三", "四", "五", "六", "日")
                    days.forEachIndexed { i, label ->
                        SegmentedButton(
                            selected = dayOfWeek == i + 1,
                            onClick = { dayOfWeek = i + 1 },
                            shape = SegmentedButtonDefaults.itemShape(i, days.size)
                        ) { Text(label, style = MaterialTheme.typography.labelSmall) }
                    }
                }

                // 节次
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("第", style = MaterialTheme.typography.bodyMedium)
                    SectionPicker(value = startSection, onValueChange = {
                        startSection = it
                        if (endSection < it) endSection = it
                    }, modifier = Modifier.weight(1f))
                    Text("→", style = MaterialTheme.typography.bodyMedium)
                    SectionPicker(value = endSection, onValueChange = {
                        endSection = it
                        if (startSection > it) startSection = it
                    }, modifier = Modifier.weight(1f))
                    Text("节", style = MaterialTheme.typography.bodyMedium)
                }

                // 周次选择
                Text("上课周次", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { selectedWeeks = (1..20).toSet() }) { Text("全选") }
                    TextButton(onClick = { selectedWeeks = (1..20).filter { it % 2 == 1 }.toSet() }) { Text("单周") }
                    TextButton(onClick = { selectedWeeks = (1..20).filter { it % 2 == 0 }.toSet() }) { Text("双周") }
                    TextButton(onClick = { selectedWeeks = emptySet() }) { Text("清空") }
                }
                WeekCheckboxGrid(selectedWeeks = selectedWeeks, onToggle = { week ->
                    selectedWeeks = if (week in selectedWeeks) selectedWeeks - week else selectedWeeks + week
                })

                // 备注
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val weekBitsStr = (1..20).joinToString("") { if (it in selectedWeeks) "1" else "0" }
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
                        termCode = termCode,
                        note = note.trim()
                    )
                    onSave(entity)
                    onDismiss()
                },
                enabled = courseName.isNotBlank() && selectedWeeks.isNotEmpty()
            ) {
                Text(if (isEdit) "保存" else "添加")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SectionPicker(value: Int, onValueChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = "$value",
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            textStyle = MaterialTheme.typography.bodyMedium
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            (1..12).forEach { section ->
                DropdownMenuItem(
                    text = { Text("$section") },
                    onClick = { onValueChange(section); expanded = false }
                )
            }
        }
    }
}

@Composable
private fun WeekCheckboxGrid(selectedWeeks: Set<Int>, onToggle: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (row in listOf(1..10, 11..20)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (week in row) {
                    val isSelected = week in selectedWeeks
                    Surface(
                        modifier = Modifier.weight(1f).heightIn(min = 32.dp).clickable { onToggle(week) },
                        shape = RoundedCornerShape(6.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("$week", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}
