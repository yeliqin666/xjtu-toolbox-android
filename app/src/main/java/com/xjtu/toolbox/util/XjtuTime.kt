package com.xjtu.toolbox.util

import java.time.LocalTime
import java.time.Month

/**
 * 西安交通大学作息时间表
 * 冬季（10月-次年4月）和夏季（5月-9月）时间不同
 * 移植自 XJTUToolBox 的 xjtu_time.py
 */
object XjtuTime {

    data class ClassTime(
        val start: LocalTime,
        val end: LocalTime,
        val attendanceStart: LocalTime,
        val attendanceEnd: LocalTime
    )

    /** 判断当前是否为夏季时间（5-9月） */
    fun isSummerTime(month: Int = java.time.LocalDate.now().monthValue): Boolean =
        month in 5..9

    /** 冬季课表（10月-4月），每节50分钟 */
    private val WINTER_SCHEDULE = mapOf(
        1 to ClassTime(LocalTime.of(8, 0), LocalTime.of(8, 50), LocalTime.of(7, 20), LocalTime.of(8, 5)),
        2 to ClassTime(LocalTime.of(9, 0), LocalTime.of(9, 50), LocalTime.of(8, 20), LocalTime.of(9, 5)),
        3 to ClassTime(LocalTime.of(10, 10), LocalTime.of(11, 0), LocalTime.of(9, 35), LocalTime.of(10, 15)),
        4 to ClassTime(LocalTime.of(11, 10), LocalTime.of(12, 0), LocalTime.of(10, 35), LocalTime.of(11, 15)),
        5 to ClassTime(LocalTime.of(14, 0), LocalTime.of(14, 50), LocalTime.of(13, 20), LocalTime.of(14, 5)),
        6 to ClassTime(LocalTime.of(15, 0), LocalTime.of(15, 50), LocalTime.of(14, 20), LocalTime.of(15, 5)),
        7 to ClassTime(LocalTime.of(16, 10), LocalTime.of(17, 0), LocalTime.of(15, 35), LocalTime.of(16, 15)),
        8 to ClassTime(LocalTime.of(17, 10), LocalTime.of(18, 0), LocalTime.of(16, 35), LocalTime.of(17, 15)),
        9 to ClassTime(LocalTime.of(19, 10), LocalTime.of(20, 0), LocalTime.of(18, 30), LocalTime.of(19, 15)),
        10 to ClassTime(LocalTime.of(20, 10), LocalTime.of(21, 0), LocalTime.of(19, 35), LocalTime.of(20, 15)),
        11 to ClassTime(LocalTime.of(21, 10), LocalTime.of(22, 0), LocalTime.of(20, 35), LocalTime.of(21, 15))
    )

    /** 夏季课表（5月-9月）：1-4节与冬季相同，5-11节各推迟30分钟 */
    private val SUMMER_SCHEDULE = mapOf(
        1 to ClassTime(LocalTime.of(8, 0), LocalTime.of(8, 50), LocalTime.of(7, 20), LocalTime.of(8, 5)),
        2 to ClassTime(LocalTime.of(9, 0), LocalTime.of(9, 50), LocalTime.of(8, 20), LocalTime.of(9, 5)),
        3 to ClassTime(LocalTime.of(10, 10), LocalTime.of(11, 0), LocalTime.of(9, 35), LocalTime.of(10, 15)),
        4 to ClassTime(LocalTime.of(11, 10), LocalTime.of(12, 0), LocalTime.of(10, 35), LocalTime.of(11, 15)),
        5 to ClassTime(LocalTime.of(14, 30), LocalTime.of(15, 20), LocalTime.of(13, 50), LocalTime.of(14, 35)),
        6 to ClassTime(LocalTime.of(15, 30), LocalTime.of(16, 20), LocalTime.of(14, 50), LocalTime.of(15, 35)),
        7 to ClassTime(LocalTime.of(16, 40), LocalTime.of(17, 30), LocalTime.of(16, 5), LocalTime.of(16, 45)),
        8 to ClassTime(LocalTime.of(17, 40), LocalTime.of(18, 30), LocalTime.of(17, 5), LocalTime.of(17, 45)),
        9 to ClassTime(LocalTime.of(19, 40), LocalTime.of(20, 30), LocalTime.of(19, 0), LocalTime.of(19, 45)),
        10 to ClassTime(LocalTime.of(20, 40), LocalTime.of(21, 30), LocalTime.of(20, 5), LocalTime.of(20, 45)),
        11 to ClassTime(LocalTime.of(21, 40), LocalTime.of(22, 30), LocalTime.of(21, 5), LocalTime.of(21, 45))
    )

    /** 获取指定节次的上课时间 */
    fun getClassTime(section: Int, summer: Boolean = isSummerTime()): ClassTime? =
        if (summer) SUMMER_SCHEDULE[section] else WINTER_SCHEDULE[section]

    /** 获取上课开始时间字符串 (如 "08:00") */
    fun getClassStartStr(section: Int, summer: Boolean = isSummerTime()): String =
        getClassTime(section, summer)?.start?.toString() ?: "--:--"

    /** 获取上课结束时间字符串 (如 "08:45") */
    fun getClassEndStr(section: Int, summer: Boolean = isSummerTime()): String =
        getClassTime(section, summer)?.end?.toString() ?: "--:--"

    /** 获取节次时间范围字符串 (如 "08:00-09:40") */
    fun getTimeRangeStr(startSection: Int, endSection: Int, summer: Boolean = isSummerTime()): String {
        val start = getClassTime(startSection, summer)?.start?.toString() ?: "--:--"
        val end = getClassTime(endSection, summer)?.end?.toString() ?: "--:--"
        return "$start-$end"
    }

    /** 全天时间表（用于 UI 侧栏显示） */
    fun getAllTimes(summer: Boolean = isSummerTime()): List<Pair<Int, ClassTime>> {
        val schedule = if (summer) SUMMER_SCHEDULE else WINTER_SCHEDULE
        return schedule.entries.sortedBy { it.key }.map { it.key to it.value }
    }
}
