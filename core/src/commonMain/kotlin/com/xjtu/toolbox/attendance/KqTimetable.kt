package com.xjtu.toolbox.attendance

import com.xjtu.toolbox.schedule.CourseItem
import com.xjtu.toolbox.util.safeInt
import com.xjtu.toolbox.util.safeString
import kotlinx.serialization.json.JsonObject

/**
 * 考勤课表的一行（门户合并课表与业务站课表同一套字段，见 [KqPortal]）。
 * 同一门课跨越不同周段时会拆成多行，`weekRanges` 只覆盖这一行自己的周次。
 *
 * 从 :app 的 `attendance/KqTimetable.kt` 搬进 commonMain：只把取值从 `KqHttp.str/int`
 * （:app 的门户 HTTP 小工具）换成 :core `util/JsonExt` 的 `safeString/safeInt`。两者语义一致：
 * 缺字段/null → 默认值、非标量 → 默认值、字符串数字（`"4"` 这种真实报文形状）都能解析
 * —— `safeInt` 内部就是 `toLongOrNull() ?: toDoubleOrNull()?.toLong()`，与 `KqHttp.int` 同一套回退。
 * `KqHttp` 的多键名查找（`str(row, "a", "b")`）这里用不到（每个字段只取一个键）。
 * 本文件自带 `KqWeekRanges`（周次区间解析与合并），两个测试（`KqWeekRangesTest` / `KqPortalTest`，
 * 报文形状取自 2026-09 门户真实响应）也跟着搬过来了。
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
) {
    companion object {
        /** 缺课程名、星期或节次的行返回 null。 */
        fun of(row: JsonObject): KqTimetableRow? {
            val name = row["courseName"].safeString()
            val day = row["dayOfWeek"].safeInt()
            val start = row["startSection"].safeInt()
            if (name.isBlank() || day !in 1..7 || start <= 0) return null
            return KqTimetableRow(
                courseName = name,
                teacherName = row["teacherName"].safeString(),
                classroomName = row["classroomName"].safeString(),
                courseCode = row["courseCode"].safeString(),
                dayOfWeek = day,
                startSection = start,
                endSection = row["endSection"].safeInt().takeIf { it >= start } ?: start,
                weekRanges = row["weekRanges"].safeString(),
            )
        }

        /** 除周次外相同的行合成一门课，周次位串长到出现过的最大周。 */
        fun toCourses(rows: List<KqTimetableRow>): List<CourseItem> {
            val maxWeek = rows.maxOfOrNull { KqWeekRanges.parse(it.weekRanges).maxOrNull() ?: 0 } ?: 0
            return rows.groupBy { it.copy(weekRanges = "") }.map { (key, group) ->
                CourseItem(
                    courseName = key.courseName,
                    teacher = key.teacherName,
                    location = key.classroomName,
                    weekBits = KqWeekRanges.mergeToBits(group.map { it.weekRanges }, maxWeek),
                    dayOfWeek = key.dayOfWeek,
                    startSection = key.startSection,
                    endSection = key.endSection,
                    courseCode = key.courseCode,
                )
            }
        }
    }
}

/**
 * [KqTimetableRow.weekRanges] 的解析与多行合并。抽成独立对象方便脱离网络层单测。
 */
object KqWeekRanges {
    /** "1-8,10-16" -> {1,2,...,8,10,...,16}。格式不对的片段直接跳过，不整体失败。 */
    fun parse(text: String): Set<Int> {
        if (text.isBlank()) return emptySet()
        // 用 mutableSetOf + sorted()（迭代顺序与原来的 TreeSet 一致）：`sortedSetOf` 是 JVM 专属，
        // Wasm 上编不过 —— 这是搬进 commonMain 后才暴露的（JVM 编译完全绿）
        val result = mutableSetOf<Int>()
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
        return result.sorted().toSet()
    }

    /**
     * 同一门课（按 name/teacher/room/day/start/end 分组）的多行 weekRanges 合并为
     * 一个位串，长度为 [maxWeekNum]（不足的周次一律置 0）。
     */
    fun mergeToBits(weekRangesList: List<String>, maxWeekNum: Int): String {
        val weeks = mutableSetOf<Int>()
        weekRangesList.forEach { weeks += parse(it) }
        val bits = CharArray(maxWeekNum.coerceAtLeast(0)) { '0' }
        for (w in weeks) {
            if (w in 1..bits.size) bits[w - 1] = '1'
        }
        // 同上一处：`String(CharArray)` 在 Wasm 上是 DeprecationLevel.ERROR
        return bits.concatToString()
    }
}
