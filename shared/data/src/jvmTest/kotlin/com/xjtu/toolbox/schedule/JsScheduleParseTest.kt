package com.xjtu.toolbox.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsScheduleParseTest {

    /** 结构照 XJTUToolBox 的 JsSession.get_schedule_lessons 取字段的方式构造。 */
    private fun week(vararg days: String) =
        """{"code":0,"data":{"dataList":[${days.joinToString(",")}]}}"""

    private fun day(vararg slots: String) = """{"classInfo":[${slots.joinToString(",")}]}"""
    private fun slot(jc: Int, name: String? = null, room: String = "主楼A-101") =
        if (name == null) """{"classJc":$jc,"classData":null}"""
        else """{"classJc":$jc,"classData":{"kcm":"$name","xm":"老师","jasmc":"$room"}}"""

    @Test
    fun splitsNonContiguousSectionsOfSameCourse() {
        // 周一 1-2 节和 5-6 节同一门课、同一教室：应是两段，不能并成 1-6
        val cells = parseJsWeek(
            week(day(slot(1, "高数"), slot(2, "高数"), slot(3), slot(5, "高数"), slot(6, "高数"))),
            week = 1,
        )
        val courses = mergeJsWeeks(listOf(1 to cells))
        assertEquals(listOf(1 to 2, 5 to 6), courses.map { it.startSection to it.endSection }.sortedBy { it.first })
    }

    @Test
    fun mergesWeeksIntoWeekBits() {
        val w = week(day(), day(slot(3, "物理", "东2-130"), slot(4, "物理", "东2-130")))
        val weekly = listOf(1 to parseJsWeek(w, 1), 2 to emptyList(), 3 to parseJsWeek(w, 3))
        val course = mergeJsWeeks(weekly).single()
        assertEquals("物理", course.courseName)
        assertEquals("东2-130", course.location)
        assertEquals(2, course.dayOfWeek)
        assertEquals("101", course.weekBits)
        assertEquals(listOf(1, 3), course.getWeeks())
    }

    @Test
    fun code400IsAnEmptyWeek() {
        assertTrue(parseJsWeek("""{"code":400,"message":"暂无数据"}""", 5).isEmpty())
    }

    @Test(expected = java.io.IOException::class)
    fun unexpectedCodeThrows() {
        parseJsWeek("""{"code":500,"message":"x"}""", 1)
    }

    @Test(expected = java.io.IOException::class)
    fun absurdSectionThrows() {
        parseJsWeek(week(day(slot(40, "高数"))), 1)
    }
}
