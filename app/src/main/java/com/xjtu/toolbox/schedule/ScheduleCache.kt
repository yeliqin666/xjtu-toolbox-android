package com.xjtu.toolbox.schedule

import com.google.gson.Gson
import com.xjtu.toolbox.util.DataCache
import java.time.LocalDate

object ScheduleCache {
    fun optimizedScheduleKey(termCode: String): String = "schedule_optimized_$termCode"
    fun textbookKey(termCode: String): String = "schedule_textbooks_$termCode"

    fun readOptimizedCourses(
        cache: DataCache,
        gson: Gson,
        termCode: String,
        ttlMs: Long = Long.MAX_VALUE
    ): List<CourseItem>? {
        if (termCode.isBlank()) return null
        val json = cache.get(optimizedScheduleKey(termCode), ttlMs) ?: return null
        return runCatching {
            gson.fromJson(json, Array<CourseItem>::class.java)?.toList().orEmpty()
        }.getOrNull()
    }

    fun writeOptimizedCourses(
        cache: DataCache,
        gson: Gson,
        termCode: String,
        courses: List<CourseItem>
    ) {
        if (termCode.isBlank()) return
        cache.put(optimizedScheduleKey(termCode), gson.toJson(courses))
    }

    fun readTextbooks(
        cache: DataCache,
        gson: Gson,
        termCode: String,
        ttlMs: Long = Long.MAX_VALUE
    ): List<TextbookItem>? {
        if (termCode.isBlank()) return null
        val json = cache.get(textbookKey(termCode), ttlMs) ?: return null
        return runCatching {
            gson.fromJson(json, Array<TextbookItem>::class.java)?.toList().orEmpty()
        }.getOrNull()
    }

    fun writeTextbooks(
        cache: DataCache,
        gson: Gson,
        termCode: String,
        textbooks: List<TextbookItem>
    ) {
        if (termCode.isBlank()) return
        cache.put(textbookKey(termCode), gson.toJson(textbooks))
    }

    fun readRawCourses(
        cache: DataCache,
        gson: Gson,
        termCode: String,
        ttlMs: Long = Long.MAX_VALUE
    ): List<CourseItem>? {
        if (termCode.isBlank()) return null
        val json = cache.get("schedule_$termCode", ttlMs) ?: return null
        return runCatching {
            gson.fromJson(json, Array<CourseItem>::class.java)?.toList().orEmpty()
        }.getOrNull()
    }

    fun filterByHolidays(
        courses: List<CourseItem>,
        startOfTerm: LocalDate?,
        holidayDates: Map<LocalDate, String>
    ): List<CourseItem> {
        if (startOfTerm == null || holidayDates.isEmpty()) return courses
        return courses.mapNotNull { course ->
            var changed = false
            val newBits = StringBuilder(course.weekBits)
            for (i in newBits.indices) {
                if (newBits[i] == '1') {
                    val courseDate = startOfTerm
                        .plusWeeks(i.toLong())
                        .plusDays((course.dayOfWeek - 1).toLong())
                    if (holidayDates.containsKey(courseDate)) {
                        newBits.setCharAt(i, '0')
                        changed = true
                    }
                }
            }
            if (changed) {
                if (newBits.contains('1')) course.copy(weekBits = newBits.toString()) else null
            } else {
                course
            }
        }
    }
}
