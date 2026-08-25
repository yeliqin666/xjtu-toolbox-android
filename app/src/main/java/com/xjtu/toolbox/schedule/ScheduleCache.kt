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

    // ── 学期终态 ─────────────────────────────────────────

    /**
     * 按**学期自己的起止**判断有没有结束，不依赖"当前学期是哪个"。
     *
     * 更可靠：`currentTermCode` 在冷启动阶段会被 `paintCache` 覆盖成正在画的那个学期，
     * 拿它做比较会一直判成"没结束"。而开学日期 + 周数是这个学期自带的事实，
     * 什么时候算都对。
     *
     * @param weeks 学期周数，取 `weekBits` 的长度（见日程页的 totalWeeks）。
     */
    fun isFinishedByDate(startOfTerm: LocalDate?, weeks: Int, today: LocalDate = LocalDate.now()): Boolean {
        if (startOfTerm == null || weeks <= 0) return false
        // 多留一周缓冲：最后一周还可能补录考勤、传回放。
        return today.isAfter(startOfTerm.plusWeeks((weeks + 1).toLong()))
    }

    /**
     * 这个学期的数据是否已经封存：结束了，而且本地该有的都有。
     *
     * 封存之后不再为它发任何请求——课表、考试、开学日期都不会变了。
     * 判据是"还会不会变"，不是"过了多久"，所以不涉及 TTL。
     */
    fun isSealed(dataCache: DataCache, gson: Gson, term: String): Boolean {
        if (term.isBlank()) return false
        val start = dataCache.get("start_date_$term", Long.MAX_VALUE)
            ?.let { runCatching { LocalDate.parse(it.trim('"')) }.getOrNull() } ?: return false
        val courses = readOptimizedCourses(dataCache, gson, term)
            ?: dataCache.get("schedule_$term", Long.MAX_VALUE)?.let {
                runCatching { gson.fromJson(it, Array<CourseItem>::class.java).toList() }.getOrNull()
            } ?: return false
        val weeks = courses.maxOfOrNull { it.weekBits.length } ?: 0
        return isFinishedByDate(start, weeks)
    }
}
