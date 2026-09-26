package com.xjtu.toolbox.agent

import android.content.Context
import com.xjtu.toolbox.schedule.CourseItem
import com.xjtu.toolbox.schedule.HolidayApi
import com.xjtu.toolbox.schedule.ScheduleCache
import com.xjtu.toolbox.schedule.TermWeeks
import com.xjtu.toolbox.data.DataCache
import java.time.LocalDate

/**
 * 从本地缓存拼出 [ChatterFacts]。只读磁盘缓存：不联网、不读 Room（点一下屁岱就要出气泡，
 * 不能等），所以自建日程不算在内——闲话说错一句「今天没课」的代价很小。
 * 任何一步读不到就返回对应字段为空，绝不抛异常。
 */
internal object ChatterFactsLoader {

    fun load(ctx: Context, today: LocalDate = LocalDate.now()): ChatterFacts = runCatching {
        val cache = DataCache(ctx)
        val holidays = HolidayApi.peekCached(ctx)
        val nextHoliday = nextHoliday(holidays, today)

        val schedule = ScheduleCache.readCurrentTermSchedule(cache)
        val courses: List<CourseItem> = schedule?.courses.orEmpty()
        val start = schedule?.start

        fun coursesOn(date: LocalDate): List<CourseItem> {
            if (start == null || holidays.containsKey(date)) return emptyList()
            val week = TermWeeks.weekOf(start, date)
            return courses.filter { it.dayOfWeek == date.dayOfWeek.value && it.isInWeek(week) }
                .sortedBy { it.startSection }
        }

        ChatterFacts(
            today = coursesOn(today).map { ChatterFacts.Slot(it.courseName, it.startSection, it.endSection) },
            tomorrowFirstSection = coursesOn(today.plusDays(1)).firstOrNull()?.startSection,
            todayHoliday = holidays[today],
            nextHoliday = nextHoliday,
        )
    }.getOrDefault(ChatterFacts())

    /**
     * 两周内下一段假期的名字和距今天数。假期表是逐日展开的，只认每段假期的第一天，
     * 否则放假当中会算出「距中秋节 1 天」。
     */
    internal fun nextHoliday(holidays: Map<LocalDate, String>, today: LocalDate): Pair<String, Int>? =
        (1..14).firstNotNullOfOrNull { d ->
            val date = today.plusDays(d.toLong())
            val name = holidays[date] ?: return@firstNotNullOfOrNull null
            if (holidays[date.minusDays(1)] == name) null else name to d
        }
}
