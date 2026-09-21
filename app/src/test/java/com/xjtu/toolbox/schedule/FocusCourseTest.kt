package com.xjtu.toolbox.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * 宽屏「今日」栏右边默认展开哪节课（[focusCourseOf]）。
 * 分钟数直接写进课里，不依赖冬夏作息换算，边界更好钉。
 */
class FocusCourseTest {
    // 2026-09-21 是周一
    private val monday = LocalDate.of(2026, 9, 21)

    private fun course(name: String, day: Int, start: Int, end: Int) = CourseItem(
        courseName = name, teacher = "", location = "", weekBits = "1",
        dayOfWeek = day, startSection = 1, endSection = 2, courseCode = name, courseType = "",
        startMinuteOfDay = start, endMinuteOfDay = end,
    )

    private val courses = listOf(
        course("高数", 1, 8 * 60, 9 * 60 + 40),
        course("物理", 1, 14 * 60, 15 * 60 + 40),
        course("周二的课", 2, 8 * 60, 9 * 60 + 40),
    )

    @Test
    fun `上课中返回正在上`() {
        val f = focusCourseOf(courses, monday, LocalTime.of(8, 30))!!
        assertEquals("高数", f.course.courseName)
        assertTrue(f.ongoing)
    }

    @Test
    fun `课间返回下一节`() {
        val f = focusCourseOf(courses, monday, LocalTime.of(12, 0))!!
        assertEquals("物理", f.course.courseName)
        assertFalse(f.ongoing)
    }

    @Test
    fun `下课那一分钟已经不算正在上`() {
        val f = focusCourseOf(courses, monday, LocalTime.of(9, 40))!!
        assertEquals("物理", f.course.courseName)
    }

    @Test
    fun `今天的课都上完了返回 null`() {
        assertNull(focusCourseOf(courses, monday, LocalTime.of(18, 0)))
    }

    @Test
    fun `今天没课返回 null，不会拿别的日子的课凑数`() {
        assertNull(focusCourseOf(courses, monday.plusDays(2), LocalTime.of(8, 0)))
    }
}
