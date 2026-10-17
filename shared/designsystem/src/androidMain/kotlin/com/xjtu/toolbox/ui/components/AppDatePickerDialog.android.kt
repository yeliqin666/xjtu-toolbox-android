package com.xjtu.toolbox.ui.components

import android.app.DatePickerDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.xjtu.toolbox.ui.theme.LocalIsDarkTheme
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn

@Composable
actual fun AppDatePickerDialog(
    show: Boolean,
    title: String,
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
        val initial = maxOf(minDate, minOf(maxDate, date))
        var picked = false
        val dialog = DatePickerDialog(
            context,
            if (dark) android.R.style.Theme_DeviceDefault_Dialog_Alert
            else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert,
            { _, y, m, d ->
                picked = true
                confirm.value(LocalDate(y, m + 1, d))
            },
            initial.year, initial.month.ordinal, initial.day,
        )
        // 系统 DatePicker 按本机时区解读 min/max 毫秒；这里只是把（北京时间的）日期原样往返，必须用本机时区
        val zone = TimeZone.currentSystemDefault()
        dialog.datePicker.minDate = minDate.atStartOfDayIn(zone).toEpochMilliseconds()
        dialog.datePicker.maxDate = maxDate.atStartOfDayIn(zone).toEpochMilliseconds()
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
