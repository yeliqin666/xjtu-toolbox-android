package com.xjtu.toolbox.schedule

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 学期周次计算。
 *
 * 原来这段逻辑在日程页抄了三份、小部件和屁岱工具各一份，各自都写成
 * `((ChronoUnit.DAYS.between(start, today) / 7) + 1).toInt()`，于是同一个 bug 也抄了五份：
 *
 * 1. **负数向零截断**。Kotlin 的 `/` 对负数截断到 0，开学前 1~6 天算出 `-3 / 7 == 0`，
 *    周次变成 1——明明还没开学，页面却当成"已经在第 1 周"。再叠上下面第 2 条，
 *    就是 issue #44 里"开学前夕显示学期已结束"。用 [Math.floorDiv] 向下取整才对。
 * 2. **周次没锚到周一**。教务的 `XQKSRQ` 正常是周一，但不保证；一旦给的是周中某天，
 *    之后每个"周次"的分界线都跟着歪，跨周那天会整体差一周。这里统一锚到开学那周的周一。
 *
 * 学期总周数（`totalWeeks`）取 `weekBits` 的长度，**课表没拉到时是 0**。0 是"不知道"，
 * 不是"零周"——[statusOf] 对 0 一律返回 [Status.Unknown]，绝不说"学期已结束"。
 */
object TermWeeks {

    /**
     * 从开学日期算周次。开学当周为 1，开学前为 0、-1、……
     *
     * @param startOfTerm 教务下发的学期开始日期
     */
    fun weekOf(startOfTerm: LocalDate, date: LocalDate = LocalDate.now()): Int {
        val anchor = startOfTerm.with(DayOfWeek.MONDAY)
        val days = ChronoUnit.DAYS.between(anchor, date)
        return Math.floorDiv(days, 7L).toInt() + 1
    }

    /** 学期相对今天处在哪个阶段。 */
    sealed interface Status {
        /** 还没开学，还差 [weeksAhead] 周（至少 1）。 */
        data class BeforeTerm(val weeksAhead: Int) : Status

        /** 学期中，当前第 [week] 周。 */
        data class InTerm(val week: Int) : Status

        /** 已进入学期，但第一门课要到第 [firstTeachWeek] 周才开。 */
        data class NotStartedYet(val week: Int, val firstTeachWeek: Int) : Status

        /** 学期已结束。 */
        data object AfterTerm : Status

        /**
         * 说不准。课表还没拉到（`totalWeeks <= 0`）时就是这个。
         *
         * 调用方该做的是**什么都不提示**，按 [fallbackWeek] 正常显示——
         * 在"不知道"的时候断言"学期已结束"，正是 #44 的表现。
         */
        data class Unknown(val fallbackWeek: Int) : Status
    }

    /**
     * @param totalWeeks 学期周数，`<= 0` 表示未知（课表还没拉到）
     * @param firstTeachWeek 第一门课所在周，没有课表时传 null
     */
    fun statusOf(
        startOfTerm: LocalDate,
        totalWeeks: Int,
        firstTeachWeek: Int? = null,
        today: LocalDate = LocalDate.now(),
    ): Status {
        val raw = weekOf(startOfTerm, today)
        return when {
            raw <= 0 -> Status.BeforeTerm(1 - raw)
            totalWeeks <= 0 -> Status.Unknown(raw)
            raw > totalWeeks -> Status.AfterTerm
            firstTeachWeek != null && raw < firstTeachWeek -> Status.NotStartedYet(raw, firstTeachWeek)
            else -> Status.InTerm(raw)
        }
    }

    /** 页面顶部那行提示。学期中、以及学期未知时都不提示。 */
    fun noteOf(status: Status): String? = when (status) {
        is Status.BeforeTerm -> "距开学还有 ${status.weeksAhead} 周"
        is Status.AfterTerm -> "学期已结束"
        is Status.NotStartedYet -> "尚未开课 · 第${status.firstTeachWeek}周开始上课"
        is Status.InTerm, is Status.Unknown -> null
    }

    /** 该定位到第几周。未开学落在第 1 周，已结束落回第 1 周（由调用方顺便翻成总览）。 */
    fun displayWeekOf(status: Status): Int = when (status) {
        is Status.BeforeTerm -> 1
        is Status.InTerm -> status.week
        is Status.NotStartedYet -> status.firstTeachWeek
        is Status.AfterTerm -> 1
        is Status.Unknown -> status.fallbackWeek.coerceAtLeast(1)
    }

    /**
     * 第 [week] 周、星期 [dayOfWeek]（1=周一）对应的日期。
     *
     * 与 [weekOf] 用同一个周一锚点——两边只要有一边不锚，"今天是第几周"和
     * "第几周是哪天"就会对不上，表现为课表整体错位一天或一周。
     */
    fun dateOf(startOfTerm: LocalDate, week: Int, dayOfWeek: Int): LocalDate =
        startOfTerm.with(DayOfWeek.MONDAY)
            .plusWeeks((week - 1).toLong())
            .plusDays((dayOfWeek - 1).toLong())

    /** 课表里第一门课在第几周；没课返回 null。 */
    fun firstTeachWeekOf(courses: List<CourseItem>): Int? = courses
        .asSequence()
        .flatMap { c -> c.weekBits.asSequence().mapIndexedNotNull { i, bit -> if (bit == '1') i + 1 else null } }
        .minOrNull()
}
