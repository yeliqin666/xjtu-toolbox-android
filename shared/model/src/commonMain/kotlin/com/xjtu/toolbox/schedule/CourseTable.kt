package com.xjtu.toolbox.schedule

import kotlinx.datetime.LocalDate

/** 一张学期课表：学期号 + 第 1 周周一 + 全部课程位置。 */
data class CourseTable(
    val term: String = "",
    /** 第 1 周周一。 */
    val termStart: LocalDate,
    /** 学期总周数（campus-api 的 `totalWeeks`，实测 18）。 */
    val totalWeeks: Int = 0,
    val slots: List<CourseSlot> = emptyList(),
)

/** `2026-10-06` → 距 1970-01-01 的天数。 */
fun parseIsoDateToEpochDay(iso: String): Long = LocalDate.parse(iso).toEpochDays().toLong()

/** 距 1970-01-01 的天数 → 日期。 */
fun civilFromEpochDay(epochDay: Long): LocalDate = LocalDate.fromEpochDays(epochDay.toInt())

/** 以本日期为第 1 周周一时，[iso] 落在第几周（从 1 开始，早于开学日则夹到 1）。 */
fun LocalDate.weekOf(iso: String): Int {
    val diff = parseIsoDateToEpochDay(iso) - toEpochDays().toLong()
    if (diff < 0) return 1
    return (diff / 7 + 1).toInt()
}

/** 第 [week] 周、星期 [dayOfWeek]（1=周一）对应的日期。 */
fun LocalDate.dateOf(week: Int, dayOfWeek: Int): LocalDate =
    civilFromEpochDay(toEpochDays().toLong() + (week - 1) * 7L + (dayOfWeek - 1))
