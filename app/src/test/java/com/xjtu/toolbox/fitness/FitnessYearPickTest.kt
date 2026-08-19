package com.xjtu.toolbox.fitness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FitnessYearPickTest {

    private val years = listOf(
        FitnessYear("2026", "2026-2027学年", checked = true),
        FitnessYear("2025", "2025-2026学年", checked = false),
        FitnessYear("2024", "2024-2025学年", checked = false),
    )

    @Test
    fun parse_plainYear() {
        assertEquals(2025, parseFitnessAcademicYear("2025"))
    }

    @Test
    fun parse_academicRange() {
        assertEquals(2025, parseFitnessAcademicYear("2025-2026"))
    }

    @Test
    fun parse_termCode() {
        assertEquals(2025, parseFitnessAcademicYear("2025-2026-1"))
        assertEquals(2025, parseFitnessAcademicYear("2025-2026-2"))
        assertEquals(2024, parseFitnessAcademicYear("2024-2025-2"))
    }

    @Test
    fun parse_blank() {
        assertNull(parseFitnessAcademicYear(null))
        assertNull(parseFitnessAcademicYear("  "))
    }

    @Test
    fun pick_termCode_mapsToStartYear() {
        val picked = pickFitnessYear(years, "2025-2026-1")
        assertEquals("2025", picked?.yearNum)
    }

    @Test
    fun pick_blank_skipsUnopenedNextYear() {
        val picked = pickFitnessYear(years, null, academicYear = 2025)
        assertEquals("2025", picked?.yearNum)
    }
}
