package com.xjtu.toolbox.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomCourseConflictsTest {

    private fun weeks(vararg w: Int, total: Int = 20): String =
        (1..total).joinToString("") { if (it in w) "1" else "0" }

    private fun course(
        weekBits: String,
        day: Int = 4,
        start: Int = 3,
        end: Int = 6,
        startMin: Int = 14 * 60,
        endMin: Int = 18 * 60,
        term: String = "2026-2027-1",
    ) = CustomCourseEntity(
        courseName = "实验",
        weekBits = weekBits,
        dayOfWeek = day,
        startSection = start,
        endSection = end,
        startMinuteOfDay = startMin,
        endMinuteOfDay = endMin,
        termCode = term,
    )

    /** 反馈的原始场景：第 4 周和第 8 周周四 14–18 点的实验，不该互相挤掉。 */
    @Test
    fun differentWeeksSameSlot_noConflict() {
        assertFalse(CustomCourseConflicts.conflicts(course(weeks(4)), course(weeks(8))))
    }

    @Test
    fun sameWeekSameSlot_conflict() {
        assertTrue(CustomCourseConflicts.conflicts(course(weeks(4, 5)), course(weeks(5, 6))))
    }

    @Test
    fun sameWeekDifferentDay_noConflict() {
        assertFalse(CustomCourseConflicts.conflicts(course(weeks(4), day = 4), course(weeks(4), day = 5)))
    }

    @Test
    fun differentTerm_noConflict() {
        assertFalse(CustomCourseConflicts.conflicts(course(weeks(4)), course(weeks(4), term = "2026-2027-2")))
    }

    @Test
    fun minutesTouching_noConflict() {
        // 14:00–16:00 与 16:00–18:00 首尾相接，可以并存
        val a = course(weeks(4), startMin = 14 * 60, endMin = 16 * 60)
        val b = course(weeks(4), startMin = 16 * 60, endMin = 18 * 60)
        assertFalse(CustomCourseConflicts.conflicts(a, b))
    }

    @Test
    fun minutesOverlapping_conflict() {
        val a = course(weeks(4), startMin = 14 * 60, endMin = 16 * 60 + 30)
        val b = course(weeks(4), startMin = 16 * 60, endMin = 18 * 60)
        assertTrue(CustomCourseConflicts.conflicts(a, b))
    }

    @Test
    fun sectionsFallback_whenMinutesMissing() {
        val a = course(weeks(4), start = 3, end = 4, startMin = -1, endMin = -1)
        val sharesSection = course(weeks(4), start = 4, end = 5, startMin = -1, endMin = -1)
        val adjacent = course(weeks(4), start = 5, end = 6, startMin = -1, endMin = -1)
        assertTrue(CustomCourseConflicts.conflicts(a, sharesSection))
        assertFalse(CustomCourseConflicts.conflicts(a, adjacent))
    }

    @Test
    fun sharedWeeks_handlesDifferentLengths() {
        assertEquals(listOf(2, 3), CustomCourseConflicts.sharedWeeks("0111", "011"))
    }

    @Test
    fun describeWeeks_mergesRuns() {
        assertEquals("第 3–5、8 周", CustomCourseConflicts.describeWeeks(listOf(3, 4, 5, 8)))
        assertEquals("第 4 周", CustomCourseConflicts.describeWeeks(listOf(4)))
    }
}
