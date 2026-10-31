package com.xjtu.toolbox.schedule

import kotlin.test.Test
import kotlin.test.assertEquals

class SchoolCourseQueryTest {
    @Test
    fun termAloneIsNotAFilter() {
        assertEquals(0, SchoolCourseQuery(termCode = TERM).activeFilterCount)
    }

    @Test
    fun sectionRangeCountsOnce() {
        val query = SchoolCourseQuery(
            termCode = TERM,
            teacher = "张三",
            isPublicElective = false,
            startSection = 1,
            endSection = 2,
        )
        assertEquals(3, query.activeFilterCount)
    }

    @Test
    fun blankStringsAreNotFilters() {
        val query = SchoolCourseQuery(termCode = TERM, courseName = "  ", teacher = " ", className = "\t")
        assertEquals(0, query.activeFilterCount)
    }

    @Test
    fun eitherSectionBoundCountsOnce() {
        assertEquals(1, SchoolCourseQuery(termCode = TERM, startSection = 3).activeFilterCount)
        assertEquals(1, SchoolCourseQuery(termCode = TERM, endSection = 5).activeFilterCount)
    }

    @Test
    fun allFieldsFilled() {
        val query = SchoolCourseQuery(
            termCode = TERM,
            courseName = "高等数学",
            courseCode = "MATH1001",
            teacher = "张三",
            departmentCode = "01",
            className = "计算机01",
            campusCode = "1",
            isPublicElective = true,
            electiveCategoryCode = "A",
            weekday = 1,
            startSection = 1,
            endSection = 2,
        )
        assertEquals(10, query.activeFilterCount)
    }

    private companion object {
        const val TERM = "2026-2027-1"
    }
}
