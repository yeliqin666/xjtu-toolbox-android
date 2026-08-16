package com.xjtu.toolbox.schedule

import com.google.gson.Gson
import com.xjtu.toolbox.util.DataCache
import java.time.LocalDate

/**
 * 课表/教材缓存层。
 *
 * ### TTL 策略
 * - 默认 TTL = 90 天：`Long.MAX_VALUE` 会让旧学期课表一直留下来，9 月开学后
 *   仍然显示上学期的课表，bug 排查极难定位。用 90 天保险，学期内持续有效，
 *   跨学期后自动重新拉取。
 * - 显式传入的 ttlMs 仍接受（便于测试 / 临时覆盖）。
 */
object ScheduleCache {
    /** 学期内稳定数据的 TTL。90 天足以覆盖任何正常学期的最大长度。 */
    private const val TERM_TTL_MS = 90L * 24 * 60 * 60 * 1000L

    fun optimizedScheduleKey(termCode: String): String = "schedule_optimized_$termCode"
    fun textbookKey(termCode: String): String = "schedule_textbooks_$termCode"

    fun readOptimizedCourses(
        cache: DataCache,
        gson: Gson,
        termCode: String,
        ttlMs: Long = TERM_TTL_MS
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
        ttlMs: Long = TERM_TTL_MS
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
        ttlMs: Long = TERM_TTL_MS
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
