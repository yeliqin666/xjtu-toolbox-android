package com.xjtu.toolbox.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
class XjtuTimeTest {

    @Test
    fun `九月开学推到秋季学期`() {
        assertEquals("2026-2027-1", XjtuTime.expectedTermCode(LocalDate.of(2026, 9, 14)))
    }

    @Test
    fun `一月仍算上一学年的秋季学期`() {
        assertEquals("2025-2026-1", XjtuTime.expectedTermCode(LocalDate.of(2026, 1, 10)))
    }

    @Test
    fun `三月推到春季学期`() {
        assertEquals("2025-2026-2", XjtuTime.expectedTermCode(LocalDate.of(2026, 3, 2)))
    }

    @Test
    fun `六月底仍是春季学期`() {
        assertEquals("2025-2026-2", XjtuTime.expectedTermCode(LocalDate.of(2026, 6, 30)))
    }

    @Test
    fun `七八月分不清短学期与暑假返回空`() {
        assertNull(XjtuTime.expectedTermCode(LocalDate.of(2026, 7, 15)))
        assertNull(XjtuTime.expectedTermCode(LocalDate.of(2026, 8, 20)))
    }
}
