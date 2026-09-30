package com.xjtu.toolbox.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ScheduleExportTest {

    /** 开学日期给的是周三：导出要按所在周的周一算，不能从周三起算。 */
    private val start = LocalDate.of(2026, 9, 16)

    private val course = CourseItem(
        courseName = "高等数学", teacher = "张三", location = "主楼A-101", weekBits = "11",
        dayOfWeek = 1, startSection = 1, endSection = 2, courseCode = "MATH1001",
    )

    private fun ics(vararg courses: CourseItem) = ScheduleExport.generateIcs(courses.toList(), start, "2026-2027-1")

    @Test
    fun `提醒文案里的课名被展开`() {
        val text = ics(course)
        assertTrue(text.contains("DESCRIPTION:高等数学 即将上课"))
        assertFalse(text.contains("\${"))
        assertFalse(text.contains("\\\$"))
    }

    @Test
    fun `日期锚定到开学那周的周一`() {
        val text = ics(course)
        // 第 1 周周一是 9 月 14 日，第 2 周周一是 9 月 21 日
        assertTrue(text.contains("DTSTART;TZID=Asia/Shanghai:20260914T"))
        assertTrue(text.contains("DTSTART;TZID=Asia/Shanghai:20260921T"))
    }

    @Test
    fun `重复导出得到同样的事件 UID`() {
        assertEquals(uids(ics(course)), uids(ics(course)))
        assertEquals(2, uids(ics(course)).toSet().size)
    }

    @Test
    fun `描述里的逗号被转义`() {
        val text = ics(course.copy(teacher = "张三,李四"))
        assertTrue(text.contains("教师: 张三\\,李四"))
    }

    private fun uids(text: String) = text.lines().filter { it.startsWith("UID:") }
}
