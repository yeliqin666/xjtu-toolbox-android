package com.xjtu.toolbox.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.window.WindowDialog

/** 选一个日期：Android 上调系统的日期选择器（月历），不自己画。 */
@Composable
expect fun AppDatePickerDialog(
    show: Boolean,
    title: String,
    date: LocalDate,
    minDate: LocalDate,
    maxDate: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit,
)

/** 没有系统日期选择器的那些端的降级实现：一个 `YYYY-MM-DD` 输入框。 */
@Composable
internal fun FallbackDatePickerDialog(
    show: Boolean,
    title: String,
    date: LocalDate,
    minDate: LocalDate,
    maxDate: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit,
) {
    if (!show) return
    val initial = maxOf(minDate, minOf(maxDate, date))
    var text by remember(initial) { mutableStateOf(initial.toString()) }
    val parsed = runCatching { LocalDate.parse(text.trim()) }.getOrNull()
    val valid = parsed != null && parsed >= minDate && parsed <= maxDate
    WindowDialog(
        show = true,
        title = title.ifBlank { "选择日期" },
        summary = "格式 2026-09-14（$minDate ~ $maxDate）",
        onDismissRequest = onDismiss,
    ) {
        Column {
            TextField(
                value = text,
                onValueChange = { text = it },
                label = "日期",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Row {
                TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                TextButton(
                    text = "确定",
                    onClick = { parsed?.let(onConfirm) },
                    enabled = valid,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}
