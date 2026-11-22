package com.xjtu.toolbox.schedule

import com.xjtu.toolbox.util.CampusTimeZone
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 系统日历日程（#462）：读出来的行怎么换算、过滤，以及合进今日与「接下来」的切分规则。 */
class SystemCalendarScheduleTest {

    private val today = LocalDate(2027, 1, 10)
    private val now = LocalDateTime(2027, 1, 10, 9, 0)

    private fun at(day: Int, hour: Int, minute: Int = 0) =
        LocalDateTime(2027, 1, day, hour, minute).toInstant(CampusTimeZone)

    private fun event(title: String, begin: kotlin.time.Instant, end: kotlin.time.Instant, allDay: Boolean = false) =
        SystemCalendarEvent(title = title, begin = begin, end = end, allDay = allDay)

    @Test
    fun 当天的定时日程换成分钟段() {
        val slice = calendarSlicesOn(today, listOf(event("组会", at(10, 14), at(10, 15, 30)))).single()
        assertEquals(14 * 60, slice.startMinute)
        assertEquals(15 * 60 + 30, slice.endMinute)
        assertEquals(false, slice.allDay)
    }

    @Test
    fun 别的日子的不算() {
        val slices = calendarSlicesOn(today, listOf(event("明天", at(11, 9), at(11, 10)), event("昨天", at(9, 9), at(9, 10))))
        assertTrue(slices.isEmpty())
    }

    @Test
    fun 正好在零点结束的前一天日程不算进今天() {
        assertTrue(calendarSlicesOn(today, listOf(event("通宵", at(9, 22), at(10, 0)))).isEmpty())
    }

    @Test
    fun 跨天日程按天切开() {
        val e = event("出差", at(9, 20), at(12, 8))
        assertEquals(true, calendarSlicesOn(today, listOf(e)).single().allDay)
        val first = calendarSlicesOn(LocalDate(2027, 1, 9), listOf(e)).single()
        assertEquals(20 * 60 to 24 * 60, first.startMinute to first.endMinute)
        val last = calendarSlicesOn(LocalDate(2027, 1, 12), listOf(e)).single()
        assertEquals(0 to 8 * 60, last.startMinute to last.endMinute)
    }

    @Test
    fun 全天日程记成全天() {
        val slice = calendarSlicesOn(today, listOf(event("生日", at(10, 0), at(11, 0), allDay = true))).single()
        assertTrue(slice.allDay)
        assertTrue(calendarSlicesOn(LocalDate(2027, 1, 11), listOf(event("生日", at(10, 0), at(11, 0), allDay = true))).isEmpty())
    }

    @Test
    fun 零时长日程只看开始时刻() {
        val slice = calendarSlicesOn(today, listOf(event("提醒", at(10, 18), at(10, 18)))).single()
        assertEquals(18 * 60 to 0, slice.startMinute to slice.endMinute)
    }

    @Test
    fun 接下来只放明天起七天内的() {
        val events = listOf(
            event("今天的", at(10, 14), at(10, 15)),
            event("后天的", at(12, 19), at(12, 20)),
            event("太远了", at(20, 9), at(20, 10)),
        )
        val result = buildUpcoming(emptyList(), emptyList(), now, events)
        assertEquals(listOf("后天的"), result.map { it.title })
        assertEquals(UpcomingKind.CALENDAR, result.single().kind)
        assertTrue(result.single().subtitle.startsWith("周二 19:00"))
    }

    // ── 全天日程（CalendarContract：ALL_DAY=1 时 BEGIN/END 是 UTC 0 点，END 是开区间） ──

    private val shanghai = TimeZone.of("Asia/Shanghai")

    private fun utcMidnight(month: Int, day: Int) =
        LocalDateTime(2027, month, day, 0, 0).toInstant(TimeZone.UTC).toEpochMilliseconds()

    private fun allDayRow(title: String, fromDay: Int, toDayExclusive: Int, month: Int = 1) =
        SystemCalendarRow(title = title, beginMillis = utcMidnight(month, fromDay), endMillis = utcMidnight(month, toDayExclusive), allDay = true)

    @Test
    fun 全天日程按UTC日期落在东八区同一天() {
        val e = SystemCalendarRules.toEvents(listOf(allDayRow("生日", 10, 11)), deviceZone = shanghai).single()
        assertEquals(at(10, 0), e.begin)
        assertEquals(at(11, 0), e.end)
        assertTrue(calendarSlicesOn(today, listOf(e)).single().allDay)
        assertTrue(calendarSlicesOn(LocalDate(2027, 1, 9), listOf(e)).isEmpty())
        assertTrue(calendarSlicesOn(LocalDate(2027, 1, 11), listOf(e)).isEmpty())
    }

    @Test
    fun 西半球设备上UTC零点的全天日程也不错天() {
        val e = SystemCalendarRules.toEvents(listOf(allDayRow("生日", 10, 11)), deviceZone = TimeZone.of("America/Los_Angeles")).single()
        assertEquals(at(10, 0), e.begin)
        assertEquals(1, calendarSlicesOn(today, listOf(e)).size)
    }

    @Test
    fun 写成设备本地零点的全天日程按本地日期算不提前一天() {
        // 1 月 10 日东八区 0 点 = 1 月 9 日 16:00 UTC；按 UTC 取日期会变成 9 日，今天就漏了
        val row = SystemCalendarRow(
            title = "调休", beginMillis = at(10, 0).toEpochMilliseconds(), endMillis = at(11, 0).toEpochMilliseconds(), allDay = true,
        )
        val e = SystemCalendarRules.toEvents(listOf(row), deviceZone = shanghai).single()
        assertEquals(at(10, 0), e.begin)
        assertTrue(calendarSlicesOn(today, listOf(e)).single().allDay)
        assertTrue(calendarSlicesOn(LocalDate(2027, 1, 9), listOf(e)).isEmpty())
    }

    @Test
    fun 多天全天日程每天都出现且结束日不算() {
        val e = SystemCalendarRules.toEvents(listOf(allDayRow("春游", 10, 13)), deviceZone = shanghai).single()
        val days = (9..13).map { d -> d to calendarSlicesOn(LocalDate(2027, 1, d), listOf(e)) }
        assertEquals(listOf(10, 11, 12), days.filter { it.second.isNotEmpty() }.map { it.first })
        val middle = calendarSlicesOn(LocalDate(2027, 1, 11), listOf(e)).single()
        assertTrue(middle.allDay)
        assertEquals(2 to 3, middle.dayIndex to middle.dayCount)
        assertEquals("第 2/3 天", middle.spanLabel())
    }

    @Test
    fun 结束不晚于开始的全天日程按一天算() {
        val e = SystemCalendarRules.toEvents(listOf(allDayRow("纪念日", 10, 10)), deviceZone = shanghai).single()
        assertEquals(at(11, 0), e.end)
        assertEquals(1, calendarSlicesOn(today, listOf(e)).size)
    }

    @Test
    fun 已取消的和隐藏日历和已拒绝的和本App导出的都去掉() {
        val base = allDayRow("保留", 10, 11)
        val rows = listOf(
            base,
            base.copy(title = "已取消", status = SystemCalendarRules.STATUS_CANCELED),
            base.copy(title = "隐藏日历", visible = false),
            base.copy(title = "已拒绝", selfAttendeeStatus = SystemCalendarRules.ATTENDEE_STATUS_DECLINED),
            base.copy(title = "导出的课", uid = "abc@xjtu-toolbox"),
            base.copy(title = "暂定", status = 0),
            base.copy(title = "没填状态", status = null),
        )
        val titles = SystemCalendarRules.toEvents(rows, deviceZone = shanghai).map { it.title }.toSet()
        assertEquals(setOf("保留", "暂定", "没填状态"), titles)
    }

    @Test
    fun 重复日程的每一次都各自出现在当天() {
        // Instances 表里每周的组会是两行，开始时间不同
        val rows = listOf(10, 17).map { d ->
            SystemCalendarRow(
                title = "组会", beginMillis = at(d, 14).toEpochMilliseconds(), endMillis = at(d, 15).toEpochMilliseconds(), allDay = false,
            )
        }
        val events = SystemCalendarRules.toEvents(rows, deviceZone = shanghai)
        assertEquals(2, events.size)
        assertEquals(14 * 60, calendarSlicesOn(today, events).single().startMinute)
        assertEquals(14 * 60, calendarSlicesOn(LocalDate(2027, 1, 17), events).single().startMinute)
        assertTrue(calendarSlicesOn(LocalDate(2027, 1, 11), events).isEmpty())
    }

    @Test
    fun 查询窗口前后各放宽一天() {
        val (from, to) = SystemCalendarRules.queryWindow(today, 8, CampusTimeZone)
        assertEquals(at(9, 0), from)
        assertEquals(at(19, 0), to)
    }

    @Test
    fun 窗口开始前就开始的跨天日程今天也算() {
        val row = SystemCalendarRow(
            title = "出差", beginMillis = at(8, 20).toEpochMilliseconds(), endMillis = at(11, 10).toEpochMilliseconds(), allDay = false,
        )
        val e = SystemCalendarRules.toEvents(listOf(row), deviceZone = shanghai).single()
        val todaySlice = calendarSlicesOn(today, listOf(e)).single()
        assertTrue(todaySlice.allDay)
        assertEquals(3 to 4, todaySlice.dayIndex to todaySlice.dayCount)
        val last = calendarSlicesOn(LocalDate(2027, 1, 11), listOf(e)).single()
        assertEquals(0 to 10 * 60, last.startMinute to last.endMinute)
    }

    @Test
    fun 接下来里全天日程单独标出且排在当天最前() {
        val events = SystemCalendarRules.toEvents(
            listOf(
                SystemCalendarRow(title = "早会", beginMillis = at(11, 8).toEpochMilliseconds(), endMillis = at(11, 9).toEpochMilliseconds(), allDay = false),
                allDayRow("元旦调休", 11, 12),
            ),
            deviceZone = shanghai,
        )
        val result = buildUpcoming(emptyList(), emptyList(), now, events)
        assertEquals(listOf("元旦调休", "早会"), result.map { it.title })
        assertTrue(result[0].allDay)
        assertTrue(result[0].subtitle.startsWith("周一 全天"))
        assertFalse(result[1].allDay)
        assertTrue(result[1].subtitle.startsWith("周一 08:00–09:00"))
    }

    @Test
    fun 接下来里跨天的全天日程每天一行() {
        val events = SystemCalendarRules.toEvents(listOf(allDayRow("春游", 12, 15)), deviceZone = shanghai)
        val result = buildUpcoming(emptyList(), emptyList(), now, events)
        assertEquals(3, result.size)
        assertTrue(result.all { it.allDay })
        assertEquals(
            listOf("周二 全天 · 第 1/3 天", "周三 全天 · 第 2/3 天", "周四 全天 · 第 3/3 天"),
            result.map { it.subtitle },
        )
    }

    @Test
    fun 今天的全天日程不进接下来() {
        val events = SystemCalendarRules.toEvents(listOf(allDayRow("生日", 10, 11)), deviceZone = shanghai)
        assertTrue(buildUpcoming(emptyList(), emptyList(), now, events).isEmpty())
    }

    @Test
    fun 周视图里一个月后的全天日程标在列头定时的画成格子() {
        // 真机上 12 月 23 日那条全天日程的原样存储（小米日历：UTC 0 点，END 开区间）
        val rows = listOf(
            SystemCalendarRow(title = "jadek", beginMillis = 1797984000000, endMillis = 1798070400000, allDay = true),
            SystemCalendarRow(
                title = "组会", location = "主楼",
                beginMillis = LocalDateTime(2026, 12, 24, 14, 0).toInstant(shanghai).toEpochMilliseconds(),
                endMillis = LocalDateTime(2026, 12, 24, 15, 30).toInstant(shanghai).toEpochMilliseconds(), allDay = false,
            ),
            // 跨午夜：周五晚到周六早，两天各画一段
            SystemCalendarRow(
                title = "通宵",
                beginMillis = LocalDateTime(2026, 12, 25, 22, 0).toInstant(shanghai).toEpochMilliseconds(),
                endMillis = LocalDateTime(2026, 12, 26, 2, 0).toInstant(shanghai).toEpochMilliseconds(), allDay = false,
            ),
        )
        val events = SystemCalendarRules.toEvents(rows, deviceZone = shanghai)
        val monday = LocalDate(2026, 12, 21)
        val week = (0..6).map { LocalDate.fromEpochDays(monday.toEpochDays() + it) }
        val cal = weekCalendar(week, 15, events)

        assertEquals(mapOf(LocalDate(2026, 12, 23) to listOf("jadek")), cal.allDay)
        val meeting = cal.slots.single { it.courseName == "组会" }
        assertEquals(4, meeting.dayOfWeek)
        assertEquals(14 * 60 to 15 * 60 + 30, meeting.startMinuteOfDay to meeting.endMinuteOfDay)
        assertTrue(meeting.isInWeek(15))
        assertFalse(meeting.isInWeek(14))
        assertTrue(meeting.courseCode.startsWith(SYSTEM_CALENDAR_CODE_PREFIX))
        val overnight = cal.slots.filter { it.courseName == "通宵" }.map { it.dayOfWeek to (it.startMinuteOfDay to it.endMinuteOfDay) }
        assertEquals(listOf(5 to (22 * 60 to 24 * 60), 6 to (0 to 2 * 60)), overnight)
    }

    @Test
    fun 今天以后几天的全天日程按真实存储格式都进接下来() {
        // 2026-11-22（周日）晚上；Instances 行按日历库的真实写法：Google 等是 UTC 0 点，部分账户是本地 0 点
        val day = LocalDate(2026, 11, 22)
        val evening = LocalDateTime(2026, 11, 22, 22, 30)
        fun utc(d: Int) = LocalDateTime(2026, 11, d, 0, 0).toInstant(TimeZone.UTC).toEpochMilliseconds()
        fun local(d: Int) = LocalDateTime(2026, 11, d, 0, 0).toInstant(shanghai).toEpochMilliseconds()
        val stored = listOf(
            SystemCalendarRow(title = "今天", beginMillis = utc(22), endMillis = utc(23), allDay = true),
            SystemCalendarRow(title = "本地零点", beginMillis = local(23), endMillis = local(24), allDay = true),
            SystemCalendarRow(title = "UTC零点", beginMillis = utc(24), endMillis = utc(25), allDay = true),
            // 每年重复的生日：Instances 已按窗口展开成这一次
            SystemCalendarRow(title = "生日", beginMillis = utc(26), endMillis = utc(27), allDay = true),
            SystemCalendarRow(title = "运动会", beginMillis = utc(27), endMillis = utc(29), allDay = true),
        )
        // 日历库按查询窗口返回与之有交集的行（BEGIN <= to 且 END >= from）
        val (from, to) = SystemCalendarRules.queryWindow(day, 8)
        val returned = stored.filter { it.beginMillis <= to.toEpochMilliseconds() && it.endMillis >= from.toEpochMilliseconds() }
        assertEquals(stored, returned)
        val events = SystemCalendarRules.toEvents(returned, deviceZone = shanghai)
        assertEquals(listOf("今天"), calendarSlicesOn(day, events).map { it.event.title })
        assertEquals(
            listOf(
                "本地零点" to "周一 全天",
                "UTC零点" to "周二 全天",
                "生日" to "周四 全天",
                "运动会" to "周五 全天 · 第 1/2 天",
                "运动会" to "周六 全天 · 第 2/2 天",
            ),
            buildUpcoming(emptyList(), emptyList(), evening, events).map { it.title to it.subtitle },
        )
    }

    @Test
    fun 连续几天的全天日程并成一根横条并错开行() {
        val week = (0..6).map { LocalDate(2026, 12, 21).plus(it, kotlinx.datetime.DateTimeUnit.DAY) }
        val events = mapOf(
            week[1] to listOf("考试周", "jadek"),
            week[2] to listOf("jadek", "考试周"),
            week[3] to listOf("考试周"),
            week[5] to listOf("jadek"),
        )
        assertEquals(
            listOf(
                AllDayBar("考试周", 1, 3, 0),
                AllDayBar("jadek", 1, 2, 1),
                AllDayBar("jadek", 5, 5, 0),
            ),
            allDayBars(week, events),
        )
    }
}
