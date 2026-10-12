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

/**
 * 首页 Hero 里那两项**本地推算**：「第 N 周」与「下一项安排」。
 *
 * ## 为什么它在 `:core` 而不是在某一端的屏里
 *
 * 它俩的输入只有三样：课表缓存里的课程、教务下发的开学日期、节假日表 —— 三样都已经在共享层
 * （`ScheduleCache` / `TermWeeks` / `HolidaySource`）。搬迁前这一百来行写在 `:app` 的 `HomeTab`
 * 里，别端要用就得整段抄一遍，而「同一个开学日期在两端口径不同」这种事一旦发生只会显得诡异。
 * 屏（[HomeScreen]）只负责把算出来的东西排版，这里一行判定都没改。
 *
 * ## 两处形态调整（判定未动）
 *
 *  - `java.time` → `kotlinx-datetime`（与 `TermWeeks` / `ScheduleCache` 同一条搬迁口径）；
 *  - 原来它内联在 `LaunchedEffect` 里，现在收成纯函数：输入是课程表与「现在」，输出是
 *    [HomeNextUp] —— 于是宿主只需要把课表缓存读出来，不必自己再写一遍 15 天扫描。
 */
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

    /**
     * 教学周。开学日期锚到那一周的周一（见 [TermWeeks.weekOf]），落在
     * [WEEK_MIN]..[WEEK_MAX] 之外（开学前、学期早已结束、教务给的日期离谱）算不出来，返回 0。
     */
    fun weekNumber(startOfTerm: LocalDate?, today: LocalDate = todayInSystemZone()): Int {
        val start = startOfTerm ?: return 0
        return TermWeeks.weekOf(start, today).takeIf { it in WEEK_MIN..WEEK_MAX } ?: 0
    }

    /**
     * 从 [courses] 里挑出「从现在起还没开始」的那一节。
     *
     * 判定与搬迁前逐条一致：
     *  - 只看今天起 [days] 天（默认 [LOOKAHEAD_DAYS]）；
     *  - 周次从 [startOfTerm] 算（`<= 0` 的日期跳过）；
     *  - 节假日停的是**教务排的课**，自建日程照常（`isUserCreated`）；
     *  - 开始时间：自建日程给的是分钟（`startMinuteOfDay`），教务课程按当天是夏季/冬季作息表换算；
     *  - 同一天里按开始时间排序，第一个「还没开始」的就是下一项。
     *
     * [startOfTerm] 为 null（学期代码 / 开学日期缺失）时返回 null —— 那是「算不出来」，
     * 与「这两周真没课」不是一件事，屏上那两句话不同。
     */
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
                    // 跨零点那一节（自建日程可能给到 24:00 之后）与搬迁前一致：右边写「至 00:00」
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

    /** `HH:MM`。与搬迁前 `formatMinuteClock` 逐字对应（`24:00` 是上限那一条）。 */
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

    /** 「今天 / 明天 / 后天 / 周三 / 10月12日」。与搬迁前 `formatScheduleReminderDateLabel` 同一套说法。 */
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

    /**
     * 「距 1970 的分钟数」：差值只需要用到「分」，所以不必把 `LocalDateTime` 送去带时区换算
     *（commonMain 的 `LocalDateTime` 上没有 `toEpochSeconds`，那要一个 `TimeZone`）。
     * 与搬迁前 `Duration.between(now, startAt).toMinutes()` 同一个数。
     */
    private fun LocalDateTime.minutesFromEpoch(): Long =
        date.toEpochDays().toLong() * 24L * 60L + hour * 60L + minute
}
