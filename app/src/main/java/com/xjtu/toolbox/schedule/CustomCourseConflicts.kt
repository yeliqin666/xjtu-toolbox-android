package com.xjtu.toolbox.schedule

/**
 * 自定义日程的冲突判定。
 *
 * 以前只看「同学期 + 同星期 + 节次有交集」，不看周次：第 4 周周四 14–18 点的实验
 * 和第 8 周同一时段的实验被当成冲突，旧的被直接删掉。实验课、答疑这种一周一条单独加的
 * 日程几乎必中。现在三样都要重叠才算：周次有交集、同星期、时间有交集。
 */
object CustomCourseConflicts {

    /** [a] 与 [b] 是否真的撞车。星期、学期由调用方（DAO 查询）先筛过，这里再兜一遍。 */
    fun conflicts(a: CustomCourseEntity, b: CustomCourseEntity): Boolean =
        a.termCode == b.termCode &&
            a.dayOfWeek == b.dayOfWeek &&
            sharedWeeks(a.weekBits, b.weekBits).isNotEmpty() &&
            timeOverlaps(a, b)

    /** 两个周次位图（第 1 位 = 第 1 周）都为 1 的周，升序。长度不同时按短的比。 */
    fun sharedWeeks(a: String, b: String): List<Int> =
        (0 until minOf(a.length, b.length))
            .filter { a[it] == '1' && b[it] == '1' }
            .map { it + 1 }

    /**
     * 两边都设了分钟级时间就按分钟比（首尾相接不算重叠：14:00 结束和 14:00 开始可以并存）；
     * 否则按节次比，节次是闭区间（3–4 节和 4–5 节共用第 4 节，算重叠）。
     */
    fun timeOverlaps(a: CustomCourseEntity, b: CustomCourseEntity): Boolean {
        val minutesKnown = a.startMinuteOfDay >= 0 && a.endMinuteOfDay >= 0 &&
            b.startMinuteOfDay >= 0 && b.endMinuteOfDay >= 0
        return if (minutesKnown) {
            a.startMinuteOfDay < b.endMinuteOfDay && b.startMinuteOfDay < a.endMinuteOfDay
        } else {
            a.startSection <= b.endSection && b.startSection <= a.endSection
        }
    }

    /** 冲突提示里用的周次描述：连续的周合并成区间，如「第 3–5、8 周」。 */
    fun describeWeeks(weeks: List<Int>): String {
        if (weeks.isEmpty()) return ""
        val parts = mutableListOf<String>()
        var start = weeks.first()
        var prev = start
        for (w in weeks.drop(1) + Int.MIN_VALUE) {
            if (w == prev + 1) {
                prev = w
                continue
            }
            parts += if (start == prev) "$start" else "$start–$prev"
            start = w
            prev = w
        }
        return "第 ${parts.joinToString("、")} 周"
    }
}
