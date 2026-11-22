package com.xjtu.toolbox.schedule

import com.xjtu.toolbox.util.CampusTimeZone
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 系统日历日程（#462）合进今日时间轴与「接下来」的切分规则。 */
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
}
