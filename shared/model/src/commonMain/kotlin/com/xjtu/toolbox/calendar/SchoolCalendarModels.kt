package com.xjtu.toolbox.calendar

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate

/** 校历模型（学期 + 假期/节点）。 */

/** 校历事件（假期/重要节点）。 */
data class CalendarEvent(
    val id: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val name: String,
    val remark: String,
    val days: Int,
    val colorHex: String,
)

/** 学期校历数据。 */
data class SchoolTerm(
    val id: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val termName: String,    // e.g. "2025-2026学年第一学期"
    val yearName: String,    // e.g. "2025-2026"
    val totalWeeks: Int,
    val workDays: Int,
    val events: List<CalendarEvent>,
) {
    /** 计算今天是第几学习周（1-based），不在学期内返回 0 */
    fun currentWeek(today: LocalDate = todayInZone()): Int {
        if (today < startDate || today > endDate) return 0
        return ((today.toEpochDays() - startDate.toEpochDays()) / 7 + 1).toInt()
    }

    /** 计算今天是本学期第几天 */
    fun currentDay(today: LocalDate = todayInZone()): Int {
        if (today < startDate) return 0
        return (today.toEpochDays() - startDate.toEpochDays() + 1).toInt()
    }

    /** 学期总天数 */
    fun totalDays(): Int = (endDate.toEpochDays() - startDate.toEpochDays() + 1).toInt()

    /** 剩余天数 */
    fun daysRemaining(today: LocalDate = todayInZone()): Int {
        if (today > endDate) return 0
        val from = if (today < startDate) startDate else today
        return (endDate.toEpochDays() - from.toEpochDays()).toInt()
    }

    /** 学期进度 (0f ~ 1f) */
    fun progress(today: LocalDate = todayInZone()): Float {
        if (today <= startDate) return 0f
        if (today >= endDate) return 1f
        val total = totalDays().toFloat()
        val elapsed = currentDay(today).toFloat()
        return (elapsed / total).coerceIn(0f, 1f)
    }

    /** 今天所在的事件（假期/节日/考试周等），可能为 null */
    fun todayEvent(today: LocalDate = todayInZone()): CalendarEvent? {
        return events.firstOrNull { today >= it.startDate && today <= it.endDate }
    }
}

/** 进来先看哪个学期：正在进行的 → 最近要开始的 → 最后一个。 */
fun defaultTermIndex(terms: List<SchoolTerm>, today: LocalDate): Int {
    if (terms.isEmpty()) return 0
    terms.indexOfFirst { it.currentWeek(today) > 0 }.takeIf { it >= 0 }?.let { return it }
    terms.indexOfFirst { today < it.startDate }.takeIf { it >= 0 }?.let { return it }
    return terms.lastIndex
}

/** 校历的取数端口。 */
interface SchoolCalendarSource {
    /** 全部学期，按开学日期升序 —— 顺序是屏幕的「学期切换」与默认选中的前提。 */
    suspend fun terms(): List<SchoolTerm>
}

/** `kotlinx-datetime` 0.8 没有 `plusDays`，只有 `plus(DatePeriod)`；语义相同的等价写法。 */
internal fun LocalDate.plusDays(days: Int): LocalDate =
    LocalDate.fromEpochDays(toEpochDays().toLong() + days)

/** 本机时区的「今天」。 */
private fun todayInZone(): LocalDate = com.xjtu.toolbox.util.todayInSystemZone()

/** 该日期是不是周末 —— 原实现写的是 `dayOfWeek != SATURDAY && dayOfWeek != SUNDAY`。 */
internal fun LocalDate.isWeekend(): Boolean =
    dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY
