package com.xjtu.toolbox.jwapp

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.xjtu.toolbox.auth.SiteSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JwappScheduleApiTest {

    private val api = JwappScheduleApi(object : SiteSession("test", "test") {
        override suspend fun runLogin(username: String, password: String) = Unit
    })

    @Test
    fun `cross-week move is added when change is returned only with origin week`() {
        val source = occurrence(day = 2, start = 1, end = 2, room = "西一楼", kbid = "KB-1")
        val change = move(
            originWeek = "4",
            targetWeek = "1",
            fromDay = 2,
            toDay = 7,
            fromStart = 1,
            fromEnd = 2,
            toStart = 3,
            toEnd = 4,
            room = "主楼 A101",
            kbid = "KB-1",
        )

        val result = api.mergeWeeks(
            listOf(
                1 to week(),
                2 to week(),
                3 to week(),
                4 to week(listOf(source), change),
            ),
            maxWeekNum = 4,
        )

        assertFalse(result.courses.any { it.dayOfWeek == 2 && it.weekBits[3] == '1' })
        val makeup = result.courses.single { it.dayOfWeek == 7 }
        assertEquals("1000", makeup.weekBits)
        assertEquals(3, makeup.startSection)
        assertEquals(4, makeup.endSection)
        assertEquals("主楼 A101", makeup.location)
        assertEquals(-1, makeup.startMinuteOfDay)
        assertEquals(-1, makeup.endMinuteOfDay)
        assertEquals(1, result.changeEvents.size)
    }

    @Test
    fun `same move returned by multiple weeks is applied once`() {
        val source = occurrence(day = 2, start = 1, end = 2, room = "西一楼", kbid = "KB-1")
        val change = move("2", "1", 2, 7, 1, 2, 3, 4, "主楼 A101", "KB-1")

        val result = api.mergeWeeks(
            listOf(
                1 to week(changes = arrayOf(change)),
                2 to week(listOf(source), change),
            ),
            maxWeekNum = 2,
        )

        assertEquals(1, result.courses.count { it.dayOfWeek == 7 })
        assertEquals(1, result.changeEvents.size)
    }

    @Test
    fun `room-only adjustment remains visible for its own week`() {
        val normalWeek1 = occurrence(room = "西一楼", kbid = "KB-1")
        val normalWeek2 = occurrence(room = "西一楼", kbid = "KB-1")
        val change = move("2", "2", 1, 1, 1, 2, 1, 2, "主楼 A101", "KB-1")

        val result = api.mergeWeeks(
            listOf(
                1 to week(listOf(normalWeek1)),
                2 to week(listOf(normalWeek2), change),
            ),
            maxWeekNum = 2,
        )

        assertEquals("10", result.courses.single { it.location == "西一楼" }.weekBits)
        assertEquals("01", result.courses.single { it.location == "主楼 A101" }.weekBits)
    }

    @Test
    fun `missing week fields only affect week where server returned change`() {
        val week1Course = occurrence(kbid = "KB-1")
        val week2Course = occurrence(kbid = "KB-1")
        val cancellation = JsonObject().apply {
            addProperty("tklxdm", "02")
            addProperty("kbid", "KB-1")
            addProperty("kcm", "光电子学")
            addProperty("kch", "OE101")
            addProperty("skxq", 1)
            addProperty("ksjc", 1)
            addProperty("jsjc", 2)
        }

        val result = api.mergeWeeks(
            listOf(
                1 to week(listOf(week1Course)),
                2 to week(listOf(week2Course), cancellation),
            ),
            maxWeekNum = 2,
        )

        assertEquals("10", result.courses.single().weekBits)
        assertEquals(1, result.changeEvents.size)
    }

    @Test
    fun `added course can target another week than response week`() {
        val addition = JsonObject().apply {
            addProperty("tklxdm", "03")
            addProperty("xskzc", "1")
            addProperty("kcm", "临时补课")
            addProperty("kch", "MAKEUP")
            addProperty("skxq", 3)
            addProperty("ksjc", 5)
            addProperty("jsjc", 6)
            addProperty("xskxq", 7)
            addProperty("xksjc", 9)
            addProperty("xjsjc", 10)
            addProperty("xjasmc", "中三 3301")
        }

        val result = api.mergeWeeks(
            listOf(1 to week(), 2 to week(changes = arrayOf(addition))),
            maxWeekNum = 2,
        )

        val course = result.courses.single()
        assertEquals("10", course.weekBits)
        assertEquals(7, course.dayOfWeek)
        assertEquals(9, course.startSection)
        assertEquals("中三 3301", course.location)
        assertTrue(result.changeEvents.single().courseName == "临时补课")
    }

    @Test
    fun `move event survives when theory response already removed origin`() {
        val change = move("2", "1", 2, 7, 1, 2, 3, 4, "主楼 A101", "KB-1").apply {
            addProperty("bz", "国庆节调课")
        }

        val result = api.mergeWeeks(
            listOf(1 to week(), 2 to week(changes = arrayOf(change))),
            maxWeekNum = 2,
        )

        assertEquals("10", result.courses.single().weekBits)
        assertEquals("国庆节调课", result.changeEvents.single().reason)
    }

    private fun week(
        theory: List<JwappScheduleApi.Occurrence> = emptyList(),
        vararg changes: JsonObject,
    ) = JwappScheduleApi.WeekRaw(theory, JsonArray().apply { changes.forEach(::add) })

    private fun occurrence(
        day: Int = 1,
        start: Int = 1,
        end: Int = 2,
        room: String = "西一楼",
        kbid: String = "KB-1",
    ) = JwappScheduleApi.Occurrence(
        courseName = "光电子学",
        teacher = "张老师",
        location = room,
        dayOfWeek = day,
        startSection = start,
        endSection = end,
        courseCode = "OE101",
        courseType = "专业课",
        startMinute = 480,
        endMinute = 570,
        kbid = kbid,
    )

    private fun move(
        originWeek: String,
        targetWeek: String,
        fromDay: Int,
        toDay: Int,
        fromStart: Int,
        fromEnd: Int,
        toStart: Int,
        toEnd: Int,
        room: String,
        kbid: String,
    ) = JsonObject().apply {
        addProperty("tklxdm", "01")
        addProperty("skzc", originWeek)
        addProperty("xskzc", targetWeek)
        addProperty("kbid", kbid)
        addProperty("kcm", "光电子学")
        addProperty("kch", "OE101")
        addProperty("skjs", "张老师")
        addProperty("jasmc", "西一楼")
        addProperty("skxq", fromDay)
        addProperty("ksjc", fromStart)
        addProperty("jsjc", fromEnd)
        addProperty("xskxq", toDay)
        addProperty("xksjc", toStart)
        addProperty("xjsjc", toEnd)
        addProperty("xjasmc", room)
    }
}
