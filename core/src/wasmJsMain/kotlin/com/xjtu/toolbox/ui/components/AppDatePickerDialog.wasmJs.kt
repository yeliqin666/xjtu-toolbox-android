package com.xjtu.toolbox.ui.components

import androidx.compose.runtime.Composable
import kotlinx.datetime.LocalDate

/**
 * Web（Kotlin/Wasm）端：用 [FallbackDatePickerDialog]。
 *
 * 浏览器其实有 `<input type="date">`（原生日期选择器），但 Compose/Wasm 里嵌原生 input 要走
 * DOM interop，而这几端目前没有真实用户 —— 先用输入框，等 :web 真需要时只改这个文件。
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
) = FallbackDatePickerDialog(show, title, date, minDate, maxDate, onDismiss, onConfirm)
