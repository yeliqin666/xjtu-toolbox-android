package com.xjtu.toolbox.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class HolidayFilterTest {

    /** 2026 秋季学期，9 月 14 日（周一）开学；9 月 25 日周五是中秋，落在第 2 周。 */
    private val start = LocalDate.of(2026, 9, 14)
    private val holidays = mapOf(LocalDate.of(2026, 9, 25) to "中秋节")

    private fun course(code: String, weeks: String) = CourseItem(
        courseName = "x", teacher = "", location = "", weekBits = weeks,
        dayOfWeek = 5, startSection = 5, endSection = 6, courseCode = code, courseType = "",
    )

    @Test
    fun `教务课只有假日那一周时整门滤掉`() {
        assertEquals(emptyList<CourseItem>(), ScheduleCache.filterByHolidays(listOf(course("C1", "01")), start, holidays))
    }

    @Test
    fun `教务课假日那周清零、其余周保留`() {
        val out = ScheduleCache.filterByHolidays(listOf(course("C1", "111")), start, holidays).single()
        assertEquals("101", out.weekBits)
    }

    /** 屁岱在中秋那周加的一次性日程：以前被滤掉，表现为「出现半秒就消失」。 */
    @Test
    fun `自建日程落在假日也不滤`() {
        val custom = CustomCourseEntity(
            id = 7, courseName = "实验", weekBits = "01", dayOfWeek = 5,
            startSection = 7, endSection = 8, termCode = "2026-2027-1",
        ).toCourseItem()
        val out = ScheduleCache.filterByHolidays(listOf(custom), start, holidays)
        assertEquals(listOf(custom), out)
    }

    @Test
    fun `只有自建日程带自建前缀`() {
        assertEquals(true, course("custom_3", "1").isUserCreated)
        assertEquals(false, course("CS101", "1").isUserCreated)
        assertNull(ScheduleCache.filterByHolidays(listOf(course("CS101", "01")), start, holidays).firstOrNull())
    }
}
