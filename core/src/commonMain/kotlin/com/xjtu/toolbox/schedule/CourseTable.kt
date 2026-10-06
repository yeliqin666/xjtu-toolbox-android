package com.xjtu.toolbox.schedule

import kotlinx.datetime.LocalDate

/**
 * 一张学期课表：学期号 + 第 1 周周一 + 全部课程位置。
 *
 * `termStart` 是**第 1 周的周一**（campus-api `/api/jwxt/term-start` 的 `startDate`，
 * 实测 `2026-2027-1` → `2026-09-14`）。上游课表本身**不含日期**，只有「周次 × 星期 × 节次」，
 * 所以任何「今天是第几周」「某周某天是几号」都必须从这一天起算 —— 这就是它必须进共享层的原因：
 * 两个端各算一遍，冬夏令、跨月、单双周迟早会算岔。
 */
data class CourseTable(
    val term: String = "",
    /** 第 1 周周一。 */
    val termStart: LocalDate,
    /** 学期总周数（campus-api 的 `totalWeeks`，实测 18）。 */
    val totalWeeks: Int = 0,
    val slots: List<CourseSlot> = emptyList(),
)

/**
 * `2026-10-06` → 距 1970-01-01 的天数。
 *
 * 为什么要有这层转换而不是直接比 `LocalDate`：浏览器里「今天」是 JS 给的字符串
 * （`browserTodayIso()`），跨端只传 ISO 文本最不容易出错；而周次换算本质是整数除法。
 */
fun parseIsoDateToEpochDay(iso: String): Long = LocalDate.parse(iso).toEpochDays().toLong()

/** 距 1970-01-01 的天数 → 日期。 */
fun civilFromEpochDay(epochDay: Long): LocalDate = LocalDate.fromEpochDays(epochDay.toInt())

/**
 * 以本日期为**第 1 周周一**时，[iso] 落在第几周（从 1 开始，早于开学日则夹到 1）。
 *
 * 用整数除法而不是周历库：学期周次是「开学日起算的连续 7 天块」，与 ISO 周历、地区周首日
 * 都无关 —— 引入周历库只会引入分歧。
 */
fun LocalDate.weekOf(iso: String): Int {
    val diff = parseIsoDateToEpochDay(iso) - toEpochDays().toLong()
    if (diff < 0) return 1
    return (diff / 7 + 1).toInt()
}

/** 第 [week] 周、星期 [dayOfWeek]（1=周一）对应的日期。 */
fun LocalDate.dateOf(week: Int, dayOfWeek: Int): LocalDate =
    civilFromEpochDay(toEpochDays().toLong() + (week - 1) * 7L + (dayOfWeek - 1))
