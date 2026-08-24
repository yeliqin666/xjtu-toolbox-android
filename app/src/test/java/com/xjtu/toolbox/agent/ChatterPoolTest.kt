package com.xjtu.toolbox.agent

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

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
}
