package com.xjtu.toolbox.agent

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.DayOfWeek
import org.junit.Assert.assertEquals

class ChatterPoolTest {

    @Test
    fun linesFitBubbleWithoutEllipsis() {
        val long = ChatterPool.lines.filter { it.text.length > ChatterPool.MAX_CHARS }
        assertTrue("超字: ${long.map { it.id to it.text }}", long.isEmpty())
    }

    @Test
    fun idsAreUnique() {
        val dup = ChatterPool.lines.groupingBy { it.id }.eachCount().filter { it.value > 1 }
        assertTrue(dup.isEmpty())
    }

    @Test
    fun everyHourHasLines() {
        val day = LocalDateTime.of(2026, 8, 23, 0, 0) // Sunday
        for (hour in 0..23) {
            val now = day.withHour(hour)
            val n = ChatterPool.eligible(now).size
            assertTrue("hour $hour only $n", n >= 3)
        }
    }

    @Test
    fun pickNeverTruncates() {
        val now = LocalDateTime.of(2026, 8, 23, 19, 30)
        val line = ChatterPool.pick(now, emptyList(), null, null)
        assertTrue(line != null && line.text.length <= ChatterPool.MAX_CHARS)
    }

    @Test
    fun selectedSkinPoolCanMixInWithContextAndAction() {
        val now = LocalDateTime.of(2026, 9, 14, 9, 0)
        val skinLine = ChatterLine(
            id = "narcissus:sun",
            text = "晒会儿太阳吧",
            hours = 8..10,
            weekdays = setOf(DayOfWeek.MONDAY),
            action = "bloom",
        )
        val picked = ChatterPool.pick(now, emptyList(), null, null, listOf(skinLine), skinMix = 1.0)
        assertEquals("narcissus:sun", picked?.id)
        assertEquals("bloom", picked?.action)
    }
}
