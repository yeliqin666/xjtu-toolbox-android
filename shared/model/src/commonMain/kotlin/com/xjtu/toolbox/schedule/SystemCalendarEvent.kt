package com.xjtu.toolbox.schedule

import com.xjtu.toolbox.util.CampusTimeZone
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * 系统日历里的一条日程（一次发生；重复日程已经按次展开）。只读：岱宗盒子只显示，不改系统日历。
 *
 * 全天日程的 [begin] / [end] 已换成校园时区那天的 0 点（`end` 是最后一天的次日 0 点，开区间），
 * 和定时日程按同一套「这段时间落在哪几天」的规则处理，见 [SystemCalendarRules.toEvents]。
 */
data class SystemCalendarEvent(
    val title: String,
    val begin: Instant,
    val end: Instant,
    val allDay: Boolean,
    val location: String = "",
    /** 来自哪个日历账户（「工作」「生日」…），显示在副标题里。 */
    val calendarName: String = "",
)

/**
 * 宿主从 `CalendarContract.Instances` 读出的一行，原样不加工（`BEGIN` / `END` 是 UTC 毫秒）。
 * 过滤和全天换算都在 [SystemCalendarRules.toEvents] 里做，纯函数，方便单测。
 */
data class SystemCalendarRow(
    val title: String?,
    val beginMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val location: String? = null,
    val calendarName: String? = null,
    val uid: String? = null,
    /** `Events.STATUS`：0 暂定、1 已确认、2 已取消；null = 没填。 */
    val status: Int? = null,
    /** `Events.SELF_ATTENDEE_STATUS`：2 = 自己已拒绝。 */
    val selfAttendeeStatus: Int? = null,
    /** `Calendars.VISIBLE`：用户在日历 App 里把这个日历设为隐藏时为 false。 */
    val visible: Boolean = true,
)

/** 读系统日历的规则：查询窗口、过滤、全天日程换算。和 Android 的 `CalendarContract` 约定一致。 */
object SystemCalendarRules {
    /** `CalendarContract.Events.STATUS_CANCELED`。 */
    const val STATUS_CANCELED = 2

    /** `CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED`。 */
    const val ATTENDEE_STATUS_DECLINED = 2

    /** 本 App「导出日历 (ICS)」写的 UID 后缀（见 ScheduleExport）。导进系统日历的那份课表不再显示一遍。 */
    const val OWN_UID_SUFFIX = "@xjtu-toolbox"

    private const val DAY_MS = 86_400_000L

    /**
     * 要显示 [today] 起 [days] 天时向日历库要的时间段：前后各多要一天。
     * 全天日程存的是 UTC 0 点，和本地 0 点差着时区偏移；只按本地日界去要，窗口边上的全天日程会被漏掉。
     * 多出来的部分由 [toEvents] 之后的按天切分（`calendarSlicesOn`）自然丢掉。
     */
    fun queryWindow(today: LocalDate, days: Int, zone: TimeZone = CampusTimeZone): Pair<Instant, Instant> =
        today.minus(1, DateTimeUnit.DAY).atStartOfDayIn(zone) to
            today.plus(days + 1, DateTimeUnit.DAY).atStartOfDayIn(zone)

    /**
     * 全天日程某个时刻对应的日期。标准写法（`CalendarContract.Events`：全天日程时区为 UTC、时刻在 0 点）
     * 按 UTC 取日期；个别同步账户把全天日程写成设备本地 0 点，这时按 [deviceZone] 取日期，
     * 否则东八区会整体提前一天、今天的全天日程就不见了。
     */
    fun allDayDate(millis: Long, deviceZone: TimeZone): LocalDate {
        val at = Instant.fromEpochMilliseconds(millis)
        val utcMidnight = ((millis % DAY_MS) + DAY_MS) % DAY_MS == 0L
        return at.toLocalDateTime(if (utcMidnight) TimeZone.UTC else deviceZone).date
    }

    /**
     * 原始行 → 要显示的日程，按开始时间排好（同一时刻全天的在前）。
     * 去掉：隐藏日历（VISIBLE=0）、已取消（STATUS_CANCELED）、自己已拒绝的邀请、本 App 导出的课表。
     * 全天日程按日期换成 [campusZone] 那天的 0 点，`END` 是开区间（最后一天的次日 0 点）；
     * 结束不晚于开始的全天日程按一天算。重复日程每次发生是单独一行，各自保留。
     */
    fun toEvents(
        rows: List<SystemCalendarRow>,
        deviceZone: TimeZone,
        campusZone: TimeZone = CampusTimeZone,
    ): List<SystemCalendarEvent> =
        rows.asSequence()
            .filter { it.visible }
            .filter { it.status != STATUS_CANCELED }
            .filter { it.selfAttendeeStatus != ATTENDEE_STATUS_DECLINED }
            .filter { !it.uid.orEmpty().endsWith(OWN_UID_SUFFIX) }
            .map { r ->
                val begin: Instant
                val end: Instant
                if (r.allDay) {
                    val first = allDayDate(r.beginMillis, deviceZone)
                    val endExclusive = allDayDate(r.endMillis, deviceZone)
                        .let { if (it <= first) first.plus(1, DateTimeUnit.DAY) else it }
                    begin = first.atStartOfDayIn(campusZone)
                    end = endExclusive.atStartOfDayIn(campusZone)
                } else {
                    begin = Instant.fromEpochMilliseconds(r.beginMillis)
                    end = Instant.fromEpochMilliseconds(r.endMillis).coerceAtLeast(begin)
                }
                SystemCalendarEvent(
                    title = r.title?.trim().orEmpty().ifEmpty { "（无标题）" },
                    begin = begin,
                    end = end,
                    allDay = r.allDay,
                    location = r.location?.trim().orEmpty(),
                    calendarName = r.calendarName?.trim().orEmpty(),
                )
            }
            .distinct()
            .sortedWith(compareBy<SystemCalendarEvent> { it.begin }.thenBy { !it.allDay })
            .toList()
}
