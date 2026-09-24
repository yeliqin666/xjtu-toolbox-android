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

    /** 冬季时间表（10月-4月），每节50分钟 */
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

    /** 夏季时间表（5月-9月）：1-4节与冬季相同，5-11节各推迟30分钟 */
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

    /**
     * 这对钟点是不是作息表上的标准时间（夏令或冬令任一套）。
     *
     * 连堂课的结束钟点可能只是第一小节的下课时间（1–2 节给 08:00–08:50），
     * 所以结束钟点落在区间内任一节的标准下课时间都算。
     */
    fun isStandardSpan(startSection: Int, endSection: Int, startMinute: Int, endMinute: Int): Boolean =
        listOf(true, false).any { summer ->
            val s = getClassTime(startSection, summer)?.start
            s != null && s.hour * 60 + s.minute == startMinute &&
                (startSection..endSection).any { section ->
                    val e = getClassTime(section, summer)?.end
                    e != null && e.hour * 60 + e.minute == endMinute
                }
        }

    /** 全天时间表（用于 UI 侧栏显示） */
    fun getAllTimes(summer: Boolean = isSummerTime()): List<Pair<Int, ClassTime>> {
        val schedule = if (summer) SUMMER_SCHEDULE else WINTER_SCHEDULE
        return schedule.entries.sortedBy { it.key }.map { it.key to it.value }
    }

    /**
     * 把「当天第几分钟」换算成**节次刻度**：第 n 节这一行 = `[n, n+1)`，小数部分就是节内比例。
     *
     * 课表网格按节次排版、行高等距（见 ui/ScheduleComponents.kt 的 ScheduleGrid），
     * 而自定义日程、体育课这类条目带的是钟点，需要这个刻度把它们落到行内的正确位置。
     *
     * - 落在某节课的起止之间 → 节号 + 节内分钟比例；
     * - 落在课间（午休/晚休）或作息之外 → 贴到最近的那一侧节边界（保证块仍然可见）；
     * - 第一节之前 → 1.0；最后一节之后 → 末节 + 1。
     */
    fun sectionScaleOf(minuteOfDay: Int, summer: Boolean = isSummerTime()): Float {
        val ordered = (if (summer) SUMMER_SCHEDULE else WINTER_SCHEDULE).entries.sortedBy { it.key }
        if (ordered.isEmpty()) return 1f
        for ((section, t) in ordered) {
            val start = t.start.hour * 60 + t.start.minute
            val end = t.end.hour * 60 + t.end.minute
            if (minuteOfDay in start..end) {
                val span = (end - start).coerceAtLeast(1)
                return section + (minuteOfDay - start).toFloat() / span
            }
        }
        val lastSection = ordered.last().key
        val firstStart = ordered.first().let { it.value.start.hour * 60 + it.value.start.minute }
        if (minuteOfDay < firstStart) return ordered.first().key.toFloat()
        // 课间/晚休/作息之后：取「最后一个已开始的节次 + 1」作为落点
        var floorSection = ordered.first().key
        for ((section, t) in ordered) {
            if (t.start.hour * 60 + t.start.minute <= minuteOfDay) floorSection = section else break
        }
        return (floorSection + 1).toFloat().coerceAtMost((lastSection + 1).toFloat())
    }

    /**
     * 当前学年的起始年份。学年从 9 月起算，用起始年命名——
     * 2025-2026 学年即「2025 学年」。
     *
     * 例：2026-08-17 仍属 2025 学年（新学年 9 月才开始）；2026-09-01 起为 2026 学年。
     *
     * 各系统的年份下拉多数只给 `2025` 这样的起始年，需要默认选中"本学年"时用它，
     * 不要各页面各自 `LocalDate.now().year` ——那样 1–8 月会整体错一年。
     */
    fun currentAcademicYear(today: java.time.LocalDate = java.time.LocalDate.now()): Int =
        if (today.monthValue >= 9) today.year else today.year - 1

    /**
     * 按日期推算「此刻应该在哪个学期」的教务代码，如 `2026-2027-1`。
     *
     * 9–1 月是秋季学期（1 月归上一学年），2–6 月是春季学期；7、8 月短学期和暑假
     * 分不清，返回 null。只当兜底：教务的「当前学期」接口在换季那几周常常还指着
     * 上一学期，或者干脆失败。
     */
    fun expectedTermCode(today: java.time.LocalDate = java.time.LocalDate.now()): String? {
        val year = currentAcademicYear(today)
        return when (today.monthValue) {
            9, 10, 11, 12, 1 -> "$year-${year + 1}-1"
            2, 3, 4, 5, 6 -> "$year-${year + 1}-2"
            else -> null
        }
    }

    /**
     * 把教务学年学期代码（如 `2025-2026-4`）译成可读名称。
     * 末位：1 秋、2 春、3 短学期、4 暑假。接口没给 MC 时用这个，不必再登 JWAPP。
     */
    fun displayTerm(code: String): String {
        val parts = code.split("-")
        if (parts.size != 3) return code
        val season = when (parts[2]) {
            "1" -> "秋季学期"
            "2" -> "春季学期"
            "3" -> "短学期"
            "4" -> "暑假"
            else -> "第${parts[2]}学期"
        }
        return "${parts[0]}-${parts[1]} $season"
    }
}
