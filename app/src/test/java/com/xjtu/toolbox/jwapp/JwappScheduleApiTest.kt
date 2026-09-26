package com.xjtu.toolbox.jwapp

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObject
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.schedule.ScheduleChangeEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 调停补课合并。规则对照官方 App 前端 `pages-timetable-index`（见 [JwappScheduleApi.mergeWeeks]）：
 * 调课按 `skzc`/`xskzc` 位串定周、原始行按 `kbid` + 节次包含关系定位并只挖掉被调的节次；
 * 停课、补课不看周次，只作用于返回它的那一周。
 */
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
        val event = result.changeEvents.single()
        assertEquals(listOf(4), event.weeks)
        assertEquals(listOf(1), event.toWeeks)
    }

    @Test
    fun `real bit-string week fields decide both ends of a move`() {
        // 官方响应里的写法：长度等于学期周数的位串
        val bits = { w: Int -> String(CharArray(18) { if (it == w - 1) '1' else '0' }) }
        val source = occurrence(day = 1, start = 5, end = 6)
        val change = move(bits(3), bits(5), 1, 6, 5, 6, 1, 2, "主楼 A101", "KB-1")

        val weeks = (1..18).map { w -> w to week(listOf(source), change) }
        val result = api.mergeWeeks(weeks, maxWeekNum = 18)

        assertEquals("110" + "1".repeat(15), result.courses.single { it.dayOfWeek == 1 }.weekBits)
        assertEquals(bits(5), result.courses.single { it.dayOfWeek == 6 }.weekBits)
        assertEquals("由第3周周一第5-6节调至第5周周六第1-2节，主楼 A101", result.changeEvents.single().describe())
    }

    @Test
    fun `short week numbers are not mistaken for bit strings`() {
        // "10" 是第 10 周，不是"第 1 周"的位串
        val source = occurrence()
        val cancelTen = move("10", "10", 1, 1, 1, 2, 1, 2, "主楼 A101", "KB-1")
        val result = api.mergeWeeks(
            (1..12).map { w -> w to week(listOf(source), cancelTen) },
            maxWeekNum = 12,
        )
        assertEquals("111111111011", result.courses.single { it.location == "西一楼" }.weekBits)
        assertEquals("000000000100", result.courses.single { it.location == "主楼 A101" }.weekBits)
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
        val change = move("2", "2", 1, 1, 1, 2, 1, 2, "主楼 A101", "KB-1")

        val result = api.mergeWeeks(
            listOf(
                1 to week(listOf(occurrence())),
                2 to week(listOf(occurrence()), change),
            ),
            maxWeekNum = 2,
        )

        assertEquals("10", result.courses.single { it.location == "西一楼" }.weekBits)
        val moved = result.courses.single { it.location == "主楼 A101" }
        assertEquals("01", moved.weekBits)
        // 只换教室，钟点沿用
        assertEquals(480, moved.startMinuteOfDay)
    }

    @Test
    fun `partial move only carves the moved sections out of the original row`() {
        // 原始行第 1-4 节，只把第 3-4 节调到周五
        val source = occurrence(start = 1, end = 4)
        val change = move("1", "1", 1, 5, 3, 4, 3, 4, "主楼 A101", "KB-1")

        val result = api.mergeWeeks(listOf(1 to week(listOf(source), change)), maxWeekNum = 1)

        val rest = result.courses.single { it.dayOfWeek == 1 }
        assertEquals(1, rest.startSection)
        assertEquals(2, rest.endSection)
        assertEquals(-1, rest.startMinuteOfDay)
        val moved = result.courses.single { it.dayOfWeek == 5 }
        assertEquals(3, moved.startSection)
        assertEquals(4, moved.endSection)
    }

    @Test
    fun `partial cancellation in the middle splits the original row`() {
        val source = occurrence(start = 1, end = 4)
        val result = api.mergeWeeks(
            listOf(1 to week(listOf(source), cancel(start = 2, end = 3))),
            maxWeekNum = 1,
        )
        assertEquals(listOf(1 to 1, 4 to 4), result.courses.map { it.startSection to it.endSection }.sortedBy { it.first })
    }

    @Test
    fun `cancellation ignores week fields and only affects the week that returned it`() {
        // 官方 suspendCourse 不看 skzc：哪怕字段写了别的周，也只作用于返回它的周
        val result = api.mergeWeeks(
            listOf(
                1 to week(listOf(occurrence())),
                2 to week(listOf(occurrence()), cancel(skzc = "1")),
            ),
            maxWeekNum = 2,
        )

        assertEquals("10", result.courses.single().weekBits)
        val event = result.changeEvents.single()
        assertEquals(listOf(2), event.weeks)
        assertEquals("第2周停课（周一第1-2节）", event.describe())
    }

    @Test
    fun `cancellation of a class that is not on the schedule leaves other classes alone`() {
        val other = occurrence(kbid = "KB-2", code = "MATH1", name = "高数")
        val result = api.mergeWeeks(listOf(1 to week(listOf(other), cancel())), maxWeekNum = 1)
        assertEquals("高数", result.courses.single().courseName)
    }

    @Test
    fun `makeup class lands in the week whose response returned it`() {
        val addition = buildJsonObject {
            put("tklxdm", "03")
            put("xskzc", "1")
            put("kcm", "临时补课")
            put("kch", "MAKEUP")
            put("skxq", 3)
            put("ksjc", 5)
            put("jsjc", 6)
            put("xskxq", 7)
            put("xksjc", 9)
            put("xjsjc", 10)
            put("xjasmc", "中三 3301")
        }

        val result = api.mergeWeeks(
            listOf(1 to week(), 2 to week(changes = arrayOf(addition))),
            maxWeekNum = 2,
        )

        val course = result.courses.single()
        assertEquals("01", course.weekBits)
        assertEquals(7, course.dayOfWeek)
        assertEquals(9, course.startSection)
        assertEquals("中三 3301", course.location)
        val event = result.changeEvents.single()
        assertEquals("临时补课", event.courseName)
        assertEquals(listOf(2), event.toWeeks)
    }

    @Test
    fun `makeup without teacher borrows it from the same course`() {
        val addition = buildJsonObject {
            put("tklxdm", "03")
            put("kcm", "光电子学")
            put("kch", "OE101")
            put("skxq", 1)
            put("ksjc", 1)
            put("jsjc", 2)
            put("xskxq", 6)
            put("xksjc", 1)
            put("xjsjc", 2)
        }
        val result = api.mergeWeeks(listOf(1 to week(listOf(occurrence()), addition)), maxWeekNum = 1)
        assertEquals("张老师", result.courses.single { it.dayOfWeek == 6 }.teacher)
    }

    @Test
    fun `moved-in row is never taken as the original of another change`() {
        // 第 1-2 节调到第 3-4 节，另一条停课恰好指向同一 kbid 的第 3-4 节：
        // 官方跳过调课加出来的行，停课落空，调进来的课保留
        val source = occurrence(start = 1, end = 2)
        val moveDown = move("1", "1", 1, 1, 1, 2, 3, 4, "西一楼", "KB-1")
        val result = api.mergeWeeks(
            listOf(1 to week(listOf(source), moveDown, cancel(start = 3, end = 4))),
            maxWeekNum = 1,
        )
        assertEquals(3, result.courses.single().startSection)
    }

    @Test
    fun `move event survives when theory response already removed origin`() {
        val base = move("2", "1", 2, 7, 1, 2, 3, 4, "主楼 A101", "KB-1")
        val change = JsonObject(base + ("bz" to JsonPrimitive("国庆节调课")))

        val result = api.mergeWeeks(
            listOf(1 to week(), 2 to week(changes = arrayOf(change))),
            maxWeekNum = 2,
        )

        assertEquals("10", result.courses.single().weekBits)
        assertEquals("国庆节调课", result.changeEvents.single().reason)
    }

    @Test
    fun `standard timetable clock times are dropped so the ui follows summer or winter time`() {
        // 抓包：10 月以后 jwapp 仍给周一第 5 节 14:30（夏令时），要交给 UI 按日期换算
        assertEquals(-1 to -1, api.clockTimes(5, 6, 14 * 60 + 30, 16 * 60 + 20))
        assertEquals(-1 to -1, api.clockTimes(5, 6, 14 * 60, 15 * 60 + 50))
        // 连堂课 endtime 只给第一小节的下课时间，也交给 UI 按节次补全
        assertEquals(-1 to -1, api.clockTimes(1, 2, 8 * 60, 8 * 60 + 50))
        assertEquals(-1 to -1, api.clockTimes(5, 6, 14 * 60 + 30, 15 * 60 + 20))
        // 作息表外的特殊钟点保留
        assertEquals(13 * 60 to 15 * 60, api.clockTimes(5, 6, 13 * 60, 15 * 60))
        assertEquals(-1 to -1, api.clockTimes(5, 6, -1, 900))
    }

    @Test
    fun `event without weeks from an older version still describes itself`() {
        val legacy = ScheduleChangeEvent(
            courseName = "光电子学", courseCode = "OE101", kind = ScheduleChangeEvent.Kind.CANCELLED,
            fromDay = 1, fromStartSection = 1, fromEndSection = 2,
            toDay = 0, toStartSection = 0, toEndSection = 0, toLocation = "", reason = "",
        )
        assertTrue(legacy.describe().startsWith("停课"))
    }

    private fun week(
        theory: List<JwappScheduleApi.Occurrence> = emptyList(),
        vararg changes: JsonObject,
    ) = JwappScheduleApi.WeekRaw(theory, buildJsonArray { changes.forEach(::add) })

    private fun occurrence(
        day: Int = 1,
        start: Int = 1,
        end: Int = 2,
        room: String = "西一楼",
        kbid: String = "KB-1",
        code: String = "OE101",
        name: String = "光电子学",
    ) = JwappScheduleApi.Occurrence(
        courseName = name,
        teacher = "张老师",
        location = room,
        dayOfWeek = day,
        startSection = start,
        endSection = end,
        courseCode = code,
        courseType = "专业课",
        startMinute = 480,
        endMinute = 570,
        kbid = kbid,
    )

    private fun cancel(start: Int = 1, end: Int = 2, skzc: String? = null) = buildJsonObject {
        put("tklxdm", "02")
        put("kbid", "KB-1")
        put("kcm", "光电子学")
        put("kch", "OE101")
        put("skxq", 1)
        put("ksjc", start)
        put("jsjc", end)
        skzc?.let { put("skzc", it) }
    }

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
    ) = buildJsonObject {
        put("tklxdm", "01")
        put("skzc", originWeek)
        put("xskzc", targetWeek)
        put("kbid", kbid)
        put("kcm", "光电子学")
        put("kch", "OE101")
        put("skjs", "张老师")
        put("jasmc", "西一楼")
        put("skxq", fromDay)
        put("ksjc", fromStart)
        put("jsjc", fromEnd)
        put("xskxq", toDay)
        put("xksjc", toStart)
        put("xjsjc", toEnd)
        put("xjasmc", room)
    }
}
