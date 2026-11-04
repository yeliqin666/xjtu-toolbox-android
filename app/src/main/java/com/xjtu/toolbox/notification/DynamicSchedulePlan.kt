package com.xjtu.toolbox.notification

import com.xjtu.toolbox.schedule.CourseItem
import com.xjtu.toolbox.schedule.TermWeeks
import com.xjtu.toolbox.schedule.XjtuTime
import com.xjtu.toolbox.util.CampusZoneId
import com.xjtu.toolbox.util.toKx
import java.time.LocalDate
import java.time.ZonedDateTime

/** 只算本地日程，不登录、不抓考勤；通知、闹钟与测试共用同一份时间边界。 */
internal data class DynamicScheduleItem(
    val course: CourseItem,
    val startAt: Long,
    val endAt: Long,
    val startMinute: Int,
    val endMinute: Int,
) {
    val isAgenda: Boolean get() = course.courseType == "日程" && course.isUserCreated
}

internal data class DynamicSchedulePlan(
    val current: DynamicScheduleItem?,
    val ongoing: Boolean,
    /** 尚未结束的条目，含正在展示的一项；同一门课不同时间段分别计数。 */
    val coursesLeft: Int,
    val agendasLeft: Int,
    val nextRefreshAt: Long,
)

internal fun planDynamicSchedule(
    courses: List<CourseItem>,
    termStart: LocalDate?,
    now: ZonedDateTime,
    holidays: Set<LocalDate> = emptySet(),
): DynamicSchedulePlan {
    val campusNow = now.withZoneSameInstant(CampusZoneId)
    val today = campusNow.toLocalDate()
    val nowMs = campusNow.toInstant().toEpochMilli()
    val midnight = today.plusDays(1).atStartOfDay(CampusZoneId).toInstant().toEpochMilli()
    // 不猜周次：缺开学日期时等用户同步课表，而不是把整个学期的同星期课程都推出来。
    val week = termStart?.let { TermWeeks.weekOf(it.toKx(), today.toKx()) }
    val dayStart = today.atStartOfDay(CampusZoneId)
    val remaining = courses.asSequence()
        .filter { week != null && it.isInWeek(week) && it.dayOfWeek == today.dayOfWeek.value }
        .filter { today !in holidays || it.isUserCreated }
        .filter { it.courseName.isNotBlank() }
        .mapNotNull { course ->
            val (start, end) = course.clockMinutes(XjtuTime.isSummerTime(today.monthValue))
            DynamicScheduleItem(
                course, dayStart.plusMinutes(start.toLong()).toInstant().toEpochMilli(),
                dayStart.plusMinutes(end.toLong()).toInstant().toEpochMilli(), start, end,
            ).takeIf { it.endAt > nowMs }
        }
        .sortedWith(compareBy<DynamicScheduleItem> { it.startAt }.thenBy { it.endAt }.thenBy { it.course.courseCode })
        .toList()
    // 正在进行的优先；有重叠时先展示最早结束的一项，结束后自然切到另一项。
    val ongoing = remaining.filter { it.startAt <= nowMs }.minByOrNull { it.endAt }
    val current = ongoing ?: remaining.firstOrNull()
    val nextBoundary = remaining.flatMap { listOf(it.startAt, it.endAt) }
        .filter { it > nowMs }.minOrNull() ?: midnight
    return DynamicSchedulePlan(
        current = current,
        ongoing = ongoing != null,
        coursesLeft = remaining.count { !it.isAgenda },
        agendasLeft = remaining.count { it.isAgenda },
        nextRefreshAt = minOf(midnight, nextBoundary),
    )
}
