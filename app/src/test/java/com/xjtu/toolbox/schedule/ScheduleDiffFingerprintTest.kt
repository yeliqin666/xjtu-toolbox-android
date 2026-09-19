package com.xjtu.toolbox.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * [ScheduleDiff.groupFingerprint]：同一节课因临时换教室被数据源拆成几条时，快照要把它们合成
 * 一份，而不是只留最后一条。
 */
class ScheduleDiffFingerprintTest {

    private fun course(location: String, weeks: IntRange, total: Int = 16) = CourseItem(
        courseName = "高等数学",
        teacher = "张老师",
        location = location,
        weekBits = String(CharArray(total) { if (it + 1 in weeks) '1' else '0' }),
        dayOfWeek = 1,
        startSection = 1,
        endSection = 2,
        courseCode = "MATH1001",
        courseType = "必修",
    )

    @Test
    fun singleItemKeepsLegacyFingerprint() {
        // 老快照就是这个格式；没拆分的课升级后不能被误报成"变了"
        val c = course("主楼A-101", 1..16)
        assertEquals("主楼A-101|${c.weekBits}|2|张老师", ScheduleDiff.groupFingerprint(listOf(c)))
    }

    @Test
    fun splitItemsMergeLocationsAndUnionWeeks() {
        val regular = course("主楼A-101", 1..4)
        val moved = course("主楼B-202", 5..5)
        val rest = regular.copy(weekBits = course("主楼A-101", 6..16).weekBits)
        val fp = ScheduleDiff.groupFingerprint(listOf(rest, moved, regular))
        assertEquals(
            "主楼A-101[1-4]、主楼B-202[5]、主楼A-101[6-16]|${"1".repeat(16)}|2|张老师",
            fp,
        )
    }

    @Test
    fun orderOfItemsDoesNotMatter() {
        val a = course("主楼A-101", 1..4)
        val b = course("主楼B-202", 5..16)
        assertEquals(ScheduleDiff.groupFingerprint(listOf(a, b)), ScheduleDiff.groupFingerprint(listOf(b, a)))
    }

    @Test
    fun temporaryRoomMovingToAnotherWeekIsDetected() {
        // 周次并集与教室集合都没变，只是临时换教室从第 5 周挪到第 6 周
        val before = listOf(course("主楼A-101", 1..4), course("主楼B-202", 5..5), course("主楼A-101", 6..16))
        val after = listOf(course("主楼A-101", 1..5), course("主楼B-202", 6..6), course("主楼A-101", 7..16))
        assertNotEquals(ScheduleDiff.groupFingerprint(before), ScheduleDiff.groupFingerprint(after))
    }
}
