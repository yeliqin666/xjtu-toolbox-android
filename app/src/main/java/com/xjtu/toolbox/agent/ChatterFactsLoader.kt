package com.xjtu.toolbox.agent

import android.content.Context
import com.google.gson.Gson
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

    private val gson = Gson()

    fun load(ctx: Context, today: LocalDate = LocalDate.now()): ChatterFacts = runCatching {
        val cache = DataCache(ctx)
        val holidays = HolidayApi.peekCached(ctx)
        val nextHoliday = (1..14).firstNotNullOfOrNull { d ->
            holidays[today.plusDays(d.toLong())]?.let { it to d }
        }

        val term = ScheduleCache.readCurrentTerm(cache, gson)
        val courses: List<CourseItem> = term?.let {
            ScheduleCache.readOptimizedCourses(cache, gson, it) ?: ScheduleCache.readRawCourses(cache, gson, it)
        }.orEmpty()
        val start = term?.let {
            runCatching {
                cache.get("start_date_$it", Long.MAX_VALUE)?.let { raw -> LocalDate.parse(gson.fromJson(raw, String::class.java)) }
            }.getOrNull()
        }

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
}
