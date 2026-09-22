package com.xjtu.toolbox.ui.components

import android.app.DatePickerDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.xjtu.toolbox.ui.theme.LocalIsDarkTheme
import java.time.LocalDate
import java.time.ZoneId

/**
 * 选一个日期：调系统的日期选择器（月历），不自己画。
 *
 * 以前这里是自己拼的三个下拉框（年 / 月 / 日），选一个日期要点开三次、每次在长列表里找，
 * 又难看又难用。系统月历一眼看到整月、点一下就选中，厂商系统上也是用户最熟的样子。
 *
 * 用法不变：[show] 变成 true 时弹出，确定回调 [onConfirm]，取消或点外面回调 [onDismiss]。
 * [title] 系统月历没有地方放标题（设了标题会把月历上方的日期头挤掉），保留参数只为不改调用方。
 * 深浅色跟着 App 自己的主题走（App 可以单独设深色，不一定和系统一致）。
 */
@Composable
fun AppDatePickerDialog(
    show: Boolean,
    @Suppress("UNUSED_PARAMETER") title: String,
    date: LocalDate,
    minDate: LocalDate,
    maxDate: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit,
) {
    if (!show) return
    val context = LocalContext.current
    val dark = LocalIsDarkTheme.current
    val confirm = rememberUpdatedState(onConfirm)
    val dismiss = rememberUpdatedState(onDismiss)
    DisposableEffect(Unit) {
        val initial = date.coerceIn(minDate, maxDate)
        var picked = false
        val dialog = DatePickerDialog(
            context,
            if (dark) android.R.style.Theme_DeviceDefault_Dialog_Alert
            else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert,
            { _, y, m, d ->
                picked = true
                confirm.value(LocalDate.of(y, m + 1, d))
            },
            initial.year, initial.monthValue - 1, initial.dayOfMonth,
        )
        val zone = ZoneId.systemDefault()
        dialog.datePicker.minDate = minDate.atStartOfDay(zone).toInstant().toEpochMilli()
        dialog.datePicker.maxDate = maxDate.atStartOfDay(zone).toInstant().toEpochMilli()
        dialog.setOnDismissListener { if (!picked) dismiss.value() }
        dialog.show()
        onDispose {
            // 调用方先把 show 改成 false（比如确定以后）时，这里把系统弹窗收掉；
            // 已经关掉的弹窗再 dismiss 是空操作
            picked = true
            if (dialog.isShowing) dialog.dismiss()
        }
    }
}

private fun LocalDate.coerceIn(min: LocalDate, max: LocalDate): LocalDate =
    if (isBefore(min)) min else if (isAfter(max)) max else this
