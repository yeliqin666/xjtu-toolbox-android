package com.xjtu.toolbox.newattendance

/**
 * 新版考勤 `/student/service/timetable/weekly` 返回的一行原始课表数据。
 * 同一门课跨越不同周段时会拆成多行，`weekRanges` 只覆盖这一行自己的周次。
 */
data class KqTimetableRow(
    val courseName: String,
    val teacherName: String,
    val classroomName: String,
    val courseCode: String,
    val dayOfWeek: Int,
    val startSection: Int,
    val endSection: Int,
    /** 如 "1-8,10-16"，逗号分隔的闭区间或单周。 */
    val weekRanges: String,
)

/**
 * [KqTimetableRow.weekRanges] 的解析与多行合并。抽成独立对象方便脱离网络层单测。
 */
object KqWeekRanges {
    /** "1-8,10-16" -> {1,2,...,8,10,...,16}。格式不对的片段直接跳过，不整体失败。 */
    fun parse(text: String): Set<Int> {
        if (text.isBlank()) return emptySet()
        val result = sortedSetOf<Int>()
        for (part in text.split(',')) {
            val seg = part.trim()
            if (seg.isEmpty()) continue
            val dash = seg.indexOf('-')
            if (dash < 0) {
                seg.toIntOrNull()?.let { result += it }
                continue
            }
            val start = seg.substring(0, dash).trim().toIntOrNull()
            val end = seg.substring(dash + 1).trim().toIntOrNull()
            if (start != null && end != null && start <= end) {
                result += (start..end)
            }
        }
        return result
    }

    /**
     * 同一门课（按 name/teacher/room/day/start/end 分组）的多行 weekRanges 合并为
     * 一个位串，长度为 [maxWeekNum]（不足的周次一律置 0）。
     */
    fun mergeToBits(weekRangesList: List<String>, maxWeekNum: Int): String {
        val weeks = sortedSetOf<Int>()
        weekRangesList.forEach { weeks += parse(it) }
        val bits = CharArray(maxWeekNum.coerceAtLeast(0)) { '0' }
        for (w in weeks) {
            if (w in 1..bits.size) bits[w - 1] = '1'
        }
        return String(bits)
    }
}
