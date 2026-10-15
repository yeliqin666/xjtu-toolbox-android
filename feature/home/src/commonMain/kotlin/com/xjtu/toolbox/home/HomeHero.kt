package com.xjtu.toolbox.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.ui.graphics.vector.ImageVector
import com.xjtu.toolbox.schedule.CourseItem
import com.xjtu.toolbox.schedule.TermWeeks
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.schedule.XjtuTime
import com.xjtu.toolbox.schedule.civilFromEpochDay
import com.xjtu.toolbox.util.nowInSystemZone
import com.xjtu.toolbox.util.todayInSystemZone
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

/** 首页 Hero 里那两项本地推算：「第 N 周」与「下一项安排」。 */
object HomeHero {

    /** 首页 Hero 里「第 N 周」认的周次范围：1..25 之外一律当作算不出来（0）。 */
    private const val WEEK_MIN = 1
    private const val WEEK_MAX = 25

    /** 「接下来两周都没课」那个窗口：从今天起算 15 天（含今天）。 */
    const val LOOKAHEAD_DAYS = 15

    /** 没登录时 Hero 左下角那个图标。 */
    val LOGIN_ICON: ImageVector get() = Icons.AutoMirrored.Filled.Login

    /** 「正在读取今日安排…」。 */
    val LOADING_ICON: ImageVector get() = Icons.Default.CalendarMonth

    /** 「课表还没同步」。 */
    val MISSING_ICON: ImageVector get() = Icons.Default.CloudOff

    /** 「接下来两周都没课」。 */
    val EMPTY_ICON: ImageVector get() = Icons.Default.EventAvailable

    /** 考试倒计时那一格用的图标（与 `HomeIcons.of(AppRoute.SchoolCalendar)` 同一个）。 */
    val EXAM_ICON: ImageVector get() = Icons.AutoMirrored.Filled.EventNote

    /** 教学周。 */
    fun weekNumber(startOfTerm: LocalDate?, today: LocalDate = todayInSystemZone()): Int {
        val start = startOfTerm ?: return 0
        return TermWeeks.weekOf(start, today).takeIf { it in WEEK_MIN..WEEK_MAX } ?: 0
    }

    /** 从 [courses] 里挑出「从现在起还没开始」的那一节。 */
    fun find(
        courses: List<CourseItem>,
        startOfTerm: LocalDate?,
        holidayDates: Set<LocalDate> = emptySet(),
        now: LocalDateTime = nowInSystemZone(),
        days: Int = LOOKAHEAD_DAYS,
        /** 点这一格去哪儿；由宿主挑（三端都是「我的课表」那条路由）。 */
        route: AppRoute? = null,
    ): HomeNextUp? {
        val start = startOfTerm ?: return null
        val today = now.date
        for (offset in 0 until days) {
            val targetDate = today.plusDays(offset)
            // 放假停的是教务的课，自建日程照常提醒
            val isHoliday = holidayDates.contains(targetDate)
            val targetWeek = TermWeeks.weekOf(start, targetDate)
            if (targetWeek <= 0) continue
            val isSummer = XjtuTime.isSummerTime(targetDate.month.ordinal + 1)
            val dayCourses = courses
                .filter { it.dayOfWeek == targetDate.dayOfWeek.ordinal + 1 && it.isInWeek(targetWeek) }
                .filter { !isHoliday || it.isUserCreated }
                .sortedBy { it.resolveStartMinute(isSummer) ?: Int.MAX_VALUE }
            for (course in dayCourses) {
                val startMinute = course.resolveStartMinute(isSummer) ?: continue
                val safeStartMinute = startMinute.coerceIn(0, 24 * 60 - 1)
                val startAt = LocalDateTime(targetDate, LocalTime(safeStartMinute / 60, safeStartMinute % 60))
                if (startAt <= now) continue
                val endMinute = course.resolveEndMinute(isSummer)
                val dayLabel = dateLabel(targetDate, today)
                val minutesUntil = startAt.minutesFromEpoch() - now.minutesFromEpoch()
                return HomeNextUp(
                    icon = EMPTY_ICON,
                    title = course.courseName,
                    timeLabel = clock(safeStartMinute),
                    timeCaption = when {
                        endMinute == null -> dayLabel
                        endMinute >= 24 * 60 -> "至 00:00"
                        else -> "至 " + clock(endMinute)
                    },
                    eta = eta(minutesUntil),
                    detail = buildString {
                        if (dayLabel != "今天" && endMinute != null) append(" · $dayLabel")
                        if (course.location.isNotBlank()) append(" · ${course.location}")
                    }.trimStart(' ', '·').takeIf { it.isNotBlank() },
                    startAt = startAt,
                    route = route,
                )
            }
        }
        return null
    }

    /** 自建日程给的是分钟；教务课程按作息表换算（见 [XjtuTime.getClassTime]）。 */
    private fun CourseItem.resolveStartMinute(isSummer: Boolean): Int? {
        if (startMinuteOfDay in 0 until 24 * 60) return startMinuteOfDay
        val startTime = XjtuTime.getClassTime(startSection, isSummer)?.start ?: return null
        return startTime.hour * 60 + startTime.minute
    }

    /** 同上，取结束那一边；没有结束节次与分钟时返回 null（Hero 上就显示日期而不是「至 HH:MM」）。 */
    private fun CourseItem.resolveEndMinute(isSummer: Boolean): Int? {
        if (endMinuteOfDay in 1..(24 * 60)) return endMinuteOfDay
        val endTime = XjtuTime.getClassTime(endSection, isSummer)?.end ?: return null
        return endTime.hour * 60 + endTime.minute
    }

    /** `HH:MM`（`24:00` 是上限）。 */
    fun clock(minuteOfDay: Int): String = when {
        minuteOfDay >= 24 * 60 -> "24:00"
        minuteOfDay < 0 -> "00:00"
        else -> "${two(minuteOfDay / 60)}:${two(minuteOfDay % 60)}"
    }

    /** 「2小时后」/「3天后」那一行倒计时。 */
    fun eta(minutesUntil: Long): String {
        if (minutesUntil <= 0) return "即将开始"
        if (minutesUntil < 60) return "${minutesUntil}分钟后"
        val hours = minutesUntil / 60
        val remainMinutes = minutesUntil % 60
        if (hours < 24) {
            return if (remainMinutes == 0L) "${hours}小时后" else "${hours}小时${remainMinutes}分钟后"
        }
        val days = hours / 24
        val remainHours = hours % 24
        return if (remainHours == 0L) "${days}天后" else "${days}天${remainHours}小时后"
    }

    /** 「今天 / 明天 / 后天 / 周三 / 10月12日」。 */
    fun dateLabel(targetDate: LocalDate, today: LocalDate): String =
        when ((targetDate.toEpochDays() - today.toEpochDays()).toInt()) {
            0 -> "今天"
            1 -> "明天"
            2 -> "后天"
            in 3..6 -> WEEKDAY_LABELS[targetDate.dayOfWeek.ordinal]
            else -> "${targetDate.month.ordinal + 1}月${targetDate.day}日"
        }

    private val WEEKDAY_LABELS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    private fun two(value: Int): String = value.toString().padStart(2, '0')

    /** kotlinx-datetime 没有 `plusDays`（见 `ScheduleScreen` 同一句注释），按距 1970 的天数走。 */
    private fun LocalDate.plusDays(days: Int): LocalDate =
        civilFromEpochDay(toEpochDays().toLong() + days)

    /** 「距 1970 的分钟数」（commonMain 的 `LocalDateTime` 没有不带时区的 `toEpochSeconds`）。 */
    private fun LocalDateTime.minutesFromEpoch(): Long =
        date.toEpochDays().toLong() * 24L * 60L + hour * 60L + minute
}
