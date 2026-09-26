package com.xjtu.toolbox.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class NextHolidayTest {

    private fun days(name: String, from: String, to: String): Map<LocalDate, String> {
        val start = LocalDate.parse(from)
        val end = LocalDate.parse(to)
        return generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.associateWith { name }
    }

    private val holidays = days("中秋节", "2026-09-25", "2026-09-27") + days("国庆节", "2026-10-01", "2026-10-07")

    @Test
    fun `放假当中不把这段假期剩下的日子当成下一个假期`() {
        assertEquals("国庆节" to 5, ChatterFactsLoader.nextHoliday(holidays, LocalDate.parse("2026-09-26")))
    }

    @Test
    fun `假期前一天算 1 天`() {
        assertEquals("中秋节" to 1, ChatterFactsLoader.nextHoliday(holidays, LocalDate.parse("2026-09-24")))
    }

    @Test
    fun `两周内没有假期返回 null`() {
        assertNull(ChatterFactsLoader.nextHoliday(holidays, LocalDate.parse("2026-10-08")))
    }
}
