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

/**
 * Android 侧真实现 —— 与搬迁前 :app 的 `ui/components/AppDatePickerDialog.kt` **逐字一致**：
 * 同样的系统月历、同样的深浅色主题选择（`Theme_DeviceDefault_(Light_)Dialog_Alert`）、
 * 同样的 `minDate`/`maxDate` 毫秒换算、同样的 `DisposableEffect` 收尾与「已经关掉的弹窗再
 * dismiss 是空操作」。
 *
 * 三处随搬迁做的替换（都只是类型层面）：
 * - 入口/出口的 `java.time.LocalDate` → `kotlinx.datetime.LocalDate`；
 * - `minDate.atStartOfDay(zone).toInstant().toEpochMilli()` → `minDate.atStartOfDayIn(zone)
 *   .toEpochMilliseconds()`（同为「该日 00:00 的墙上时刻」）；
 * - `ZoneId.systemDefault()` → `TimeZone.currentSystemDefault()`。
 * `initial.monthValue - 1` 变成 `initial.month.ordinal`：都是 0 基月份，与系统的
 * `DatePickerDialog(..., monthOfYear, ...)` 参数一致。
 */
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
