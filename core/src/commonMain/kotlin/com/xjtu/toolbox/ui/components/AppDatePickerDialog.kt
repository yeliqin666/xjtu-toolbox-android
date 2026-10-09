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

/**
 * 选一个日期：**Android 上调系统的日期选择器（月历），不自己画**。
 *
 * 以前这里是自己拼的三个下拉框（年 / 月 / 日），选一个日期要点开三次、每次在长列表里找，
 * 又难看又难用。系统月历一眼看到整月、点一下就选中，厂商系统上也是用户最熟的样子。
 *
 * 用法不变：[show] 变成 true 时弹出，确定回调 [onConfirm]，取消或点外面回调 [onDismiss]。
 * [title] 系统月历没有地方放标题（设了标题会把月历上方的日期头挤掉），保留参数只为不改调用方
 * —— 但**非 Android 端的降级实现会用到它**（见 [FallbackDatePickerDialog]）。
 *
 * 从 :app 搬进 commonMain 时它成了平台家族的一员（第 9 个）：`android.app.DatePickerDialog`
 * 与 `ZoneId.systemDefault()` 都留在 androidMain 的 actual 里，日期类型换成 `kotlinx-datetime`
 * 的 [LocalDate]（调用方在本侧边界转，与 `TermWeeks` 等一致）。
 * 深浅色跟着 App 自己的主题走（App 可以单独设深色，不一定和系统一致）。
 *
 * 各端：Android = 系统月历（行为与搬迁前逐字一致）；jvm / Web = [FallbackDatePickerDialog]
 * （一个 `YYYY-MM-DD` 输入框 —— 这些端没有系统日历，自己画一个月的代价不值得；
 * 将来要更好的只改各自的 actual）。
 */
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

/**
 * 没有系统日期选择器的那些端的降级实现：一个 `YYYY-MM-DD` 输入框。
 *
 * 为什么不做成画一个月的日历：那是一个**新造的** UI（配色、手势、无障碍都要自己负责），
 * 而这几端目前还没有真实用户；先给一个能用、能校验范围、能被替换的入口。
 * 范围校验与 Android 那侧一致（把初始值夹进 [minDate]/[maxDate]，确定时也要求落在范围内）。
 */
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
