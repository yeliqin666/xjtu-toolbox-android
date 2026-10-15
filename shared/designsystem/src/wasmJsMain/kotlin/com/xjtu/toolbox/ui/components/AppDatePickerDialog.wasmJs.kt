package com.xjtu.toolbox.ui.components

import androidx.compose.runtime.Composable
import kotlinx.datetime.LocalDate

/** Web（Kotlin/Wasm）端：用 [FallbackDatePickerDialog]。 */
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
