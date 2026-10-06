package com.xjtu.toolbox.widget

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class TodoWidgetTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int) = LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `截止时间：今天明天带钟点，更远只给日期，没有就空`() {
        val now = at(2026, 10, 6, 9, 0)
        assertEquals("今天 23:59", TodoWidgetUpdater.dueLabel(at(2026, 10, 6, 23, 59), now, zone))
        assertEquals("明天 08:00", TodoWidgetUpdater.dueLabel(at(2026, 10, 7, 8, 0), now, zone))
        assertEquals("10/12", TodoWidgetUpdater.dueLabel(at(2026, 10, 12, 12, 0), now, zone))
        assertEquals("", TodoWidgetUpdater.dueLabel(0L, now, zone))
    }
}
