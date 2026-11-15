package com.xjtu.toolbox.home

import com.xjtu.toolbox.util.nowOnCampus
import kotlinx.datetime.LocalDate

/**
 * 二十四节气。寿星公式：日 = ⌊Y·0.2422 + C⌋ − ⌊L/4⌋，Y 取年份后两位，小寒到雨水的 L 用 Y−1；
 * C 是 21 世纪的常数，再按已知特例修正。只对 2000–2099 年成立，范围外返回 null。
 */
object SolarTerms {
    private val NAMES = listOf(
        "小寒", "大寒", "立春", "雨水", "惊蛰", "春分", "清明", "谷雨", "立夏", "小满", "芒种", "夏至",
        "小暑", "大暑", "立秋", "处暑", "白露", "秋分", "寒露", "霜降", "立冬", "小雪", "大雪", "冬至",
    )
    private val C = doubleArrayOf(
        5.4055, 20.12, 3.87, 18.73, 5.63, 20.646, 4.81, 20.1, 5.52, 21.04, 5.678, 21.37,
        7.108, 22.83, 7.5, 23.13, 7.646, 23.042, 8.318, 23.438, 7.438, 22.36, 7.18, 21.94,
    )

    /** 公式算偏的年份：（节气序号, 年份）→ 修正天数。 */
    private val FIXES = mapOf(
        (0 to 2019) to -1, (1 to 2082) to 1, (3 to 2026) to -1, (5 to 2084) to 1, (9 to 2008) to 1,
        (12 to 2016) to 1, (14 to 2002) to 1, (19 to 2089) to 1, (20 to 2089) to 1, (23 to 2021) to -1,
    )

    /** [date] 当天是哪个节气，不是节气日返回 null。 */
    fun of(date: LocalDate): String? {
        if (date.year !in 2000..2099) return null
        val first = (date.month.ordinal) * 2
        return (first..first + 1).firstOrNull { dayOf(date.year, it) == date.day }?.let(NAMES::get)
    }

    fun today(): String? = of(nowOnCampus().date)

    private fun dayOf(year: Int, index: Int): Int {
        val y = year % 100
        val leapBase = if (index < 4) y - 1 else y
        return (y * 0.2422 + C[index]).toInt() - leapBase.floorDiv(4) + (FIXES[index to year] ?: 0)
    }
}
