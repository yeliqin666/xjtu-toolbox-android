package com.xjtu.toolbox.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import java.time.LocalDate
import java.time.YearMonth

@Composable
fun AppDatePickerDialog(
    show: Boolean,
    title: String,
    date: LocalDate,
    minDate: LocalDate,
    maxDate: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit,
) {
    val years = remember(minDate, maxDate) { (minDate.year..maxDate.year).toList() }
    var year by remember(show, date) { mutableIntStateOf(date.year.coerceIn(minDate.year, maxDate.year)) }
    var month by remember(show, date) { mutableIntStateOf(date.monthValue) }
    var day by remember(show, date) { mutableIntStateOf(date.dayOfMonth) }

    val monthStart = if (year == minDate.year) minDate.monthValue else 1
    val monthEnd = if (year == maxDate.year) maxDate.monthValue else 12
    val months = (monthStart..monthEnd).toList()
    val safeMonth = month.coerceIn(monthStart, monthEnd)

    val dim = YearMonth.of(year, safeMonth).lengthOfMonth()
    val dayStart = if (year == minDate.year && safeMonth == minDate.monthValue) minDate.dayOfMonth else 1
    val dayEnd = if (year == maxDate.year && safeMonth == maxDate.monthValue) minOf(maxDate.dayOfMonth, dim) else dim
    val days = (dayStart..dayEnd).toList()
    val safeDay = day.coerceIn(dayStart, dayEnd)

    BackHandler(enabled = show) { onDismiss() }
    OverlayDialog(
        show = show,
        title = title,
        onDismissRequest = onDismiss
    ) {
        OverlaySpinnerPreference(
            title = "年",
            items = remember(years) { years.map { DropdownItem(text = "${it}年") } },
            selectedIndex = years.indexOf(year).coerceAtLeast(0),
            onSelectedIndexChange = { year = years[it] },
            modifier = Modifier.fillMaxWidth()
        )
        OverlaySpinnerPreference(
            title = "月",
            items = remember(months) { months.map { DropdownItem(text = "${it}月") } },
            selectedIndex = months.indexOf(safeMonth).coerceAtLeast(0),
            onSelectedIndexChange = { month = months[it] },
            modifier = Modifier.fillMaxWidth()
        )
        OverlaySpinnerPreference(
            title = "日",
            items = remember(days) { days.map { DropdownItem(text = "${it}日") } },
            selectedIndex = days.indexOf(safeDay).coerceAtLeast(0),
            onSelectedIndexChange = { day = days[it] },
            modifier = Modifier.fillMaxWidth()
        )
        Row(Modifier.fillMaxWidth()) {
            TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(20.dp))
            TextButton(
                text = "确定",
                onClick = { onConfirm(LocalDate.of(year, safeMonth, safeDay)) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary()
            )
        }
    }
}
