package com.xjtu.toolbox.attendance

import org.junit.Assert.assertEquals
import org.junit.Test

class KqWeekRangesTest {

    @Test
    fun `parses comma separated ranges and single weeks`() {
        assertEquals((1..8).toSet() + (10..16).toSet(), KqWeekRanges.parse("1-8,10-16"))
        assertEquals(setOf(3), KqWeekRanges.parse("3"))
        assertEquals(setOf(1, 2, 3, 5), KqWeekRanges.parse("1-3, 5"))
    }

    @Test
    fun `blank or malformed segments are skipped, not fatal`() {
        assertEquals(emptySet<Int>(), KqWeekRanges.parse(""))
        assertEquals(setOf(1, 2), KqWeekRanges.parse("1-2,,garbage,-,3-1"))
    }

    @Test
    fun `merges multiple rows of the same course into one week-bit string`() {
        val bits = KqWeekRanges.mergeToBits(listOf("1-8", "10-16"), 16)
        assertEquals("1111111101111111", bits)
        assertEquals('1', bits[0])
        assertEquals('0', bits[8])
        assertEquals('1', bits[9])
        assertEquals('1', bits[15])
    }

    @Test
    fun `weeks beyond maxWeekNum are dropped, not out of bounds`() {
        val bits = KqWeekRanges.mergeToBits(listOf("1-20"), 16)
        assertEquals(16, bits.length)
        assertEquals('1', bits[15])
    }
}
