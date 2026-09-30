package com.xjtu.toolbox.attendance

import com.xjtu.toolbox.util.safeParseJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 形状取自 2026-09 考勤门户 `/sa/auth/portal/semester` 的真实响应，课程行按门户前端的读法补。 */
class KqPortalTest {

    private fun side(scope: String, courses: String, available: Boolean = true) = """
        {"scope":"$scope","status":"${if (available) "AVAILABLE" else "NOT_LAUNCHED"}","available":$available,
         "payload":${if (!available) "null" else """{"semester":{"semesterId":"1","academicYear":"2026-2027","semesterName":"第一学期",
           "startDate":"2026-09-14","endDate":"2027-02-21","scheduleName":"冬令时","week":null},"periods":[],"courses":$courses}"""}}
    """

    private fun portal(undergraduate: String, graduate: String) =
        """{"terminal":"STUDENT","undergraduate":$undergraduate,"graduate":$graduate}""".safeParseJsonObject()

    private val optics = """{"courseName":"光电子学","teacherName":"高宏","classroomName":"西2西-307","courseCode":"PHYS401009",
        "dayOfWeek":2,"startSection":1,"endSection":2,"weekRanges":"1-8"}"""

    @Test
    fun `研究生没上线时只取本科一侧`() {
        val sides = KqPortal.parse(portal(side("BK", "[$optics]"), side("YJS", "[]", available = false)))
        val bk = sides.single()
        assertEquals("BK", bk.scope)
        assertEquals("2026-2027-1", bk.termCode)
        assertEquals("光电子学", bk.rows.single().courseName)
    }

    @Test
    fun `本科研究生两侧都取，缺星期或节次的行丢掉`() {
        val broken = """{"courseName":"没排时间","dayOfWeek":0,"startSection":0}"""
        val seminar = """{"courseName":"研究生讨论班","dayOfWeek":5,"startSection":9,"endSection":10,"weekRanges":"1-16"}"""
        val sides = KqPortal.parse(portal(side("BK", "[$optics,$broken]"), side("YJS", "[$seminar]")))
        assertEquals(listOf("BK", "YJS"), sides.map { it.scope })
        assertEquals(listOf("光电子学"), sides[0].rows.map { it.courseName })
        assertEquals(10, sides[1].rows.single().endSection)
    }

    @Test
    fun `同一门课跨周段的几行合成一条，位串长到最大周`() {
        val later = optics.replace("\"1-8\"", "\"10-16\"")
        val other = optics.replace("\"dayOfWeek\":2", "\"dayOfWeek\":4").replace("\"1-8\"", "\"1-18\"")
        val rows = KqPortal.parse(portal(side("BK", "[$optics,$later,$other]"), side("YJS", "[]", available = false))).single().rows
        val courses = KqTimetableRow.toCourses(rows)
        assertEquals(2, courses.size)
        val tuesday = courses.single { it.dayOfWeek == 2 }
        assertEquals((1..8) + (10..16), tuesday.getWeeks())
        assertEquals(18, tuesday.weekBits.length)
        assertTrue(courses.single { it.dayOfWeek == 4 }.isInWeek(18))
    }
}
