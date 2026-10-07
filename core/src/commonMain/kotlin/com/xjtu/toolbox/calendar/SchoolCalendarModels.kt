package com.xjtu.toolbox.calendar

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate

/**
 * 校历模型（学期 + 假期/节点）。
 *
 * 从 `:app/calendar/SchoolCalendarApi.kt` 搬进 `:core`：**方法体一行未动**，只把日期类型
 * `java.time.LocalDate` 换成 `kotlinx.datetime.LocalDate`（`:core` 全层用后者，见
 * `schedule/TermWeeks.kt`），三处随之变化的写法：
 *  - `toEpochDay()` → `toEpochDays()`；
 *  - `plusDays(n)` → [LocalDate.plusDays]（本文件的手写扩展：`kotlinx-datetime` 0.8 只有
 *    `plus(DatePeriod)`，写法上等价且更省一次分配）；
 *  - `DayOfWeek.SATURDAY/SUNDAY` 用 `kotlinx.datetime.DayOfWeek` —— ⚠️ 它在 JVM 上就是
 *    `java.time.DayOfWeek`（跨模块 typealias），所以这里只比较枚举常量、不碰任何
 *    `java.time` 专属成员（`TermWeeks` 里那段注释记过同一个坑）。
 *
 * 为什么值得搬：校历屏原本整块钉在 `:app`（数据取 `workflow.xjtu.edu.cn` 的公开接口、
 * 不需要登录），它是「Web 与 App 完全一致」里最容易做到真一致的一屏 —— 模型与算法共享，
 * 两端只各自注入取数实现（Android 直连学校，Web 走 campus-api 同源反代）。
 */

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

/**
 * 进来先看哪个学期：正在进行的 → 最近要开始的 → 最后一个。
 *
 * 原来只找"正在进行"，找不到就落回 `terms[0]`。而取数是按开学日期升序排的，
 * `terms[0]` 是**最老**的那个学期——于是寒暑假、开学前这些不在任何学期区间内的日子，
 * 校历页一打开显示的是好几年前的校历，看着就像"新校历没加进来"。
 * 学校其实早就发了（这个页面本来就是实时拉的门户数据，没有任何本地写死的年份）。
 */
fun defaultTermIndex(terms: List<SchoolTerm>, today: LocalDate): Int {
    if (terms.isEmpty()) return 0
    terms.indexOfFirst { it.currentWeek(today) > 0 }.takeIf { it >= 0 }?.let { return it }
    terms.indexOfFirst { today < it.startDate }.takeIf { it >= 0 }?.let { return it }
    return terms.lastIndex
}

/**
 * 校历的**取数端口**。屏幕只认这个；两端各自注入实现：
 *  - `:app` = `SchoolCalendarApi`（直连 `workflow.xjtu.edu.cn`，okhttp，行为与搬迁前一致）；
 *  - Web    = [com.xjtu.toolbox.core.net.CampusSchoolCalendarApi]（走 campus-api 同源反代，
 *             浏览器不能直连学校域名：没有 CORS 头）。
 *
 * 两条路的上游是**同一个**门户接口，所以模型与展示口径完全共享。
 */
interface SchoolCalendarSource {
    /** 全部学期，按开学日期升序 —— 顺序是屏幕的「学期切换」与默认选中的前提。 */
    suspend fun terms(): List<SchoolTerm>
}

/** `kotlinx-datetime` 0.8 没有 `plusDays`，只有 `plus(DatePeriod)`；语义相同的等价写法。 */
internal fun LocalDate.plusDays(days: Int): LocalDate =
    LocalDate.fromEpochDays(toEpochDays().toLong() + days)

/** 本机时区的「今天」。与 `util/Today.kt` 同一条口径，这里只是不想让模型文件多一个 import 面。 */
private fun todayInZone(): LocalDate = com.xjtu.toolbox.util.todayInSystemZone()

/** 该日期是不是周末 —— 原实现写的是 `dayOfWeek != SATURDAY && dayOfWeek != SUNDAY`。 */
internal fun LocalDate.isWeekend(): Boolean =
    dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY
