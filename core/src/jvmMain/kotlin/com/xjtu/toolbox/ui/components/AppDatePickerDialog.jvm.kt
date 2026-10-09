package com.xjtu.toolbox.ui.components

import androidx.compose.runtime.Composable
import kotlinx.datetime.LocalDate

/** jvm / 桌面端：没有系统月历，用 [FallbackDatePickerDialog]（一个 YYYY-MM-DD 输入框）。 */
@Composable
actual fun AppDatePickerDialog(
    show: Boolean,
    title: String,
    date: LocalDate,
    minDate: LocalDate,
    maxDate: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit,
) = FallbackDatePickerDialog(show, title, date, minDate, maxDate, onDismiss, onConfirm)
