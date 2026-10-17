package com.xjtu.toolbox.util

import com.xjtu.toolbox.home.Greeting
import com.xjtu.toolbox.inbox.SelfTodoRules
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** `beijingEpochMs` 是 :core 里唯一解析「校园上游东八区时间串」的地方。 */
class BeijingTimeTest {

    @Test
    fun parsesUpstreamShape() {
        // 2026-10-07 20:00:02 +08:00 == 2026-10-07T12:00:02Z
        assertEquals(1_791_374_402_000L, beijingEpochMs("2026-10-07 20:00:02"))
        // 2026-10-07 01:43:11 +08:00 == 2026-10-06T17:43:11Z
        assertEquals(1_791_308_591_000L, beijingEpochMs("2026-10-07 01:43:11"))
    }

    @Test
    fun matchesAppZoneForTheseTimestamps() {
        val samples = listOf(
            "2026-10-07 20:00:02",
            "2026-10-07 01:43:11",
            "2026-01-01 00:00:00",
            "2026-12-31 23:59:59",
        )
        for (s in samples) {
            val viaApp = LocalDateTime.parse(s.replace(' ', 'T'))
                .toInstant(TimeZone.of("Asia/Shanghai"))
                .toEpochMilliseconds()
            assertEquals(viaApp, beijingEpochMs(s), "与 :app 的 Asia/Shanghai 口径不一致：$s")
        }
    }

    @Test
    fun toleratesTrailingFractionAndRejectsGarbage() {
        // 上游偶尔带毫秒；只取前 19 位，值不变
        assertEquals(beijingEpochMs("2026-10-07 20:00:02"), beijingEpochMs("2026-10-07 20:00:02.123"))
        // 认不出来就 0（调用方按「没有时间」处理，不要抛）
        assertEquals(0L, beijingEpochMs(null))
        assertEquals(0L, beijingEpochMs(""))
        assertEquals(0L, beijingEpochMs("2026-10-07"))
        assertEquals(0L, beijingEpochMs("None"))
        assertEquals(0L, beijingEpochMs("不是时间"))
    }

    private fun at(iso: String) = object : Clock { override fun now() = Instant.parse(iso) }
    private val la = TimeZone.of("America/Los_Angeles")
    private val london = TimeZone.of("Europe/London")
    private fun epoch(iso: String) = Instant.parse(iso).toEpochMilliseconds()
    private fun Long.wallIn(zone: TimeZone) = Instant.fromEpochMilliseconds(this).toLocalDateTime(zone)

    @Test
    fun detectsDeviceZoneByCurrentOffset() {
        val summer = at("2026-07-01T00:00:00Z")
        val winter = at("2026-01-15T00:00:00Z")
        assertTrue(isDeviceOnCampusTime(summer, TimeZone.of("Asia/Shanghai")))
        assertTrue(isDeviceOnCampusTime(winter, TimeZone.of("Asia/Taipei")))
        assertFalse(isDeviceOnCampusTime(summer, la))
        assertFalse(isDeviceOnCampusTime(summer, london))
        assertFalse(isDeviceOnCampusTime(winter, london))
        assertNull(campusTimeHint(summer, TimeZone.of("Asia/Shanghai")))
        assertEquals("已按北京时间显示", campusTimeHint(winter, la))
    }

    @Test
    fun campusWallClockDoesNotFollowDeviceZone() {
        // 北京 10-19 08:00 == 00:00Z；洛杉矶（PDT）是前一天 17:00，伦敦（BST）01:00
        val summerish = campusEpochMsOf(LocalDateTime(2026, 10, 19, 8, 0))
        assertEquals(epoch("2026-10-19T00:00:00Z"), summerish)
        assertEquals(LocalDateTime(2026, 10, 19, 8, 0), campusDateTimeOf(summerish))
        assertEquals("08:00", campusClockText(summerish))
        assertEquals(LocalDateTime(2026, 10, 18, 17, 0), summerish.wallIn(la))
        assertEquals(LocalDateTime(2026, 10, 19, 1, 0), summerish.wallIn(london))
        // 欧美夏令时结束后：同一个北京 08:00 在伦敦 00:00（GMT）、洛杉矶前一天 16:00（PST），北京那侧不变
        val winter = campusEpochMsOf(LocalDateTime(2026, 12, 1, 8, 0))
        assertEquals(epoch("2026-12-01T00:00:00Z"), winter)
        assertEquals(LocalDateTime(2026, 12, 1, 0, 0), winter.wallIn(london))
        assertEquals(LocalDateTime(2026, 11, 30, 16, 0), winter.wallIn(la))
        assertEquals("08:00", campusClockText(winter))
    }

    @Test
    fun todayRollsOverAtBeijingMidnight() {
        assertEquals(LocalDate(2026, 10, 18), todayOnCampus(at("2026-10-18T15:59:00Z")))
        assertEquals(LocalDate(2026, 10, 19), todayOnCampus(at("2026-10-18T16:00:00Z")))
    }

    @Test
    fun greetingFollowsBeijingClock() {
        // 00:00Z = 北京 08:00；洛杉矶此时是前一天傍晚，问候仍按北京
        assertEquals("早上好", Greeting.of(nowOnCampus(at("2026-10-19T00:00:00Z")).time))
        assertEquals("晚上好", Greeting.of(nowOnCampus(at("2026-10-19T12:30:00Z")).time))
    }

    @Test
    fun todoReminderFiresAtBeijingInstant() {
        val now = epoch("2026-10-17T00:00:00Z")
        val (due, allDay) = SelfTodoRules.parseDue("2026-10-19 08:00")!!
        val timed = SelfTodoRules.newTodo("早八", dueDate = due, allDay = allDay, remind = true, now = now)
        assertEquals(epoch("2026-10-19T00:00:00Z"), SelfTodoRules.nextReminderAt(timed, now))
        val (day, isAllDay) = SelfTodoRules.parseDue("2026-10-19")!!
        val whole = SelfTodoRules.newTodo("交表", dueDate = day, allDay = isAllDay, remind = true, now = now)
        // 全天待办当天 9 点（北京）提醒 == 01:00Z
        assertEquals(epoch("2026-10-19T01:00:00Z"), SelfTodoRules.nextReminderAt(whole, now))
    }
}
