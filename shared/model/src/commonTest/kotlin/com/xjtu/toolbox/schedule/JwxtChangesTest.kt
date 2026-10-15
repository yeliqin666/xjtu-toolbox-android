package com.xjtu.toolbox.schedule

import com.xjtu.toolbox.schedule.ScheduleChangeEvent.Kind
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 形状取自 2026-09 教务 `xsdkkc.do` 的真实记录。 */
class JwxtChangesTest {

    private fun bits(vararg weeks: Int, len: Int = 19) = String(CharArray(len) { if (it + 1 in weeks) '1' else '0' })

    private fun row(
        jxbid: String = "JXB1",
        day: Int = 2,
        start: Int = 1,
        end: Int = 2,
        weeks: String = bits(*(1..12).toList().toIntArray()),
        location: String = "西2西-307",
        name: String = "光电子学",
    ) = JwxtRow(
        jxbid,
        CourseItem(
            courseName = name, teacher = "高宏", location = location, weekBits = weeks,
            dayOfWeek = day, startSection = start, endSection = end, courseCode = "PHYS401009", courseType = "必修",
        ),
    )

    private fun change(
        type: String,
        jxbid: String = "JXB1",
        weeks: String = "",
        day: Int? = null, start: Int? = null, end: Int? = null,
        toWeeks: String = "",
        toDay: Int? = null, toStart: Int? = null, toEnd: Int? = null,
        toLocation: String = "",
        appliedAt: String = "2025-10-26 18:44:25",
    ) = JwxtChange.of(buildJsonObject {
        put("TKLXDM", type)
        put("JXBID", jxbid)
        put("KCM", "光电子学")
        put("KCH", "PHYS401009")
        put("SKZC", weeks)
        day?.let { put("SKXQ", it) }
        start?.let { put("KSJC", it) }
        end?.let { put("JSJC", it) }
        put("JASMC", "西2西-307")
        put("YSKJS", "高宏/0002009003")
        put("XSKZC", toWeeks)
        toDay?.let { put("XSKXQ", it) }
        toStart?.let { put("XKSJC", it) }
        toEnd?.let { put("XJSJC", it) }
        put("XJASMC", toLocation)
        put("XSKJS", "高宏/0002009003")
        put("SQSJ", appliedAt)
    })!!

    @Test
    fun `调课挖掉原来那周，在新周补上，同一课位合成一条`() {
        val moved = change("01", weeks = bits(8), day = 2, start = 1, end = 2, toWeeks = bits(13), toDay = 2, toStart = 1, toEnd = 2, toLocation = "西2西-307")
        val result = JwxtChanges.apply(listOf(row()), listOf(moved))
        val course = result.courses.single()
        assertEquals((1..7) + (9..13), course.getWeeks())
        assertEquals("高宏", course.teacher)
        assertEquals(Kind.MOVED, result.changes.single().kind)
        assertEquals(listOf(8), result.changes.single().weeks)
        assertEquals(listOf(13), result.changes.single().toWeeks)
    }

    @Test
    fun `停课只挖掉那一周`() {
        val cancelled = change("02", weeks = bits(7), day = 2, start = 1, end = 2)
        val course = JwxtChanges.apply(listOf(row()), listOf(cancelled)).courses.single()
        assertEquals((1..6) + (8..12), course.getWeeks())
    }

    @Test
    fun `补课没有原课，性质从同教学班的课补`() {
        val added = change("03", toWeeks = bits(12), toDay = 6, toStart = 3, toEnd = 4, toLocation = "中2-3204")
        val result = JwxtChanges.apply(listOf(row()), listOf(added))
        val extra = result.courses.single { it.dayOfWeek == 6 }
        assertEquals(listOf(12), extra.getWeeks())
        assertEquals("中2-3204", extra.location)
        assertEquals("必修", extra.courseType)
        assertEquals("第12周补课，周六第3-4节，中2-3204", result.changes.single().describe())
    }

    @Test
    fun `只换教室：原课那周挖掉，新教室单独一条`() {
        val roomOnly = change("01", weeks = bits(8), day = 2, start = 1, end = 2, toWeeks = bits(8), toDay = 2, toStart = 1, toEnd = 2, toLocation = "主楼A-103")
        val result = JwxtChanges.apply(listOf(row()), listOf(roomOnly))
        assertEquals((1..7) + (9..12), result.courses.single { it.location == "西2西-307" }.getWeeks())
        assertEquals(listOf(8), result.courses.single { it.location == "主楼A-103" }.getWeeks())
        assertEquals("第8周周二第1-2节换到主楼A-103", result.changes.single().describe())
    }

    @Test
    fun `四节连堂只调走后两节，前两节照常上`() {
        val base = row(start = 1, end = 4)
        val moved = change("01", weeks = bits(2), day = 2, start = 3, end = 4, toWeeks = bits(2), toDay = 4, toStart = 3, toEnd = 4)
        val courses = JwxtChanges.apply(listOf(base), listOf(moved)).courses
        val week2 = courses.filter { it.isInWeek(2) }.map { it.dayOfWeek to (it.startSection..it.endSection) }.toSet()
        assertEquals(setOf(2 to 1..2, 4 to 3..4), week2)
        assertTrue(courses.single { it.dayOfWeek == 2 && it.endSection == 4 }.getWeeks().none { it == 2 })
    }

    @Test
    fun `只认同一教学班、同一天、节次包含的原课`() {
        val other = row(jxbid = "JXB2", name = "别的课")
        val sameClassOtherDay = row(day = 4, start = 5, end = 6)
        val cancelled = change("02", weeks = bits(3), day = 2, start = 1, end = 2)
        val courses = JwxtChanges.apply(listOf(row(), other, sameClassOtherDay), listOf(cancelled)).courses
        assertTrue(courses.single { it.courseName == "别的课" }.isInWeek(3))
        assertTrue(courses.single { it.dayOfWeek == 4 }.isInWeek(3))
        assertTrue(!courses.single { it.courseName == "光电子学" && it.dayOfWeek == 2 }.isInWeek(3))
    }

    @Test
    fun `按申请时间先调走再停课`() {
        val moved = change("01", weeks = bits(5), day = 2, start = 1, end = 2, toWeeks = bits(14), toDay = 3, toStart = 1, toEnd = 2, appliedAt = "2025-10-01 10:00:00")
        val cancelled = change("02", weeks = bits(14), day = 3, start = 1, end = 2, appliedAt = "2025-10-20 10:00:00")
        val courses = JwxtChanges.apply(listOf(row()), listOf(cancelled, moved)).courses
        assertTrue(courses.none { it.dayOfWeek == 3 })
        assertTrue(!courses.single().isInWeek(5))
    }

    @Test
    fun `认不出的类型丢掉，教师去掉工号`() {
        assertNull(JwxtChange.of(buildJsonObject { put("TKLXDM", "09") }))
        val c = change("01", weeks = bits(8), day = 2, start = 1, end = 2)
        assertEquals("高宏", c.teacher)
    }
}
