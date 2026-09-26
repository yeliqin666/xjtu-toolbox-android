package com.xjtu.toolbox.schedule

import com.xjtu.toolbox.data.DataCache
import java.time.LocalDate

/**
 * 课表 / 考试 / 教材 / 学期的本地缓存，键名集中在这里。
 * 学期内的数据按 90 天过期：永不过期会让上学期的课表在开学后一直留着。
 */
object ScheduleCache {
    private const val TERM_TTL_MS = 90L * 24 * 60 * 60 * 1000L
    private const val FOREVER = Long.MAX_VALUE

    /**
     * 「当前学期」：教务给的本学期。「上次看的学期」在翻历史学期时会被改写，
     * 要「本学期」的地方一律读这个键。
     */
    private const val CURRENT_TERM_KEY = "schedule_current_term"
    private const val LAST_TERM_KEY = "schedule_last_term"
    private const val TERM_LIST_KEY = "schedule_term_list"

    fun optimizedScheduleKey(termCode: String): String = "schedule_optimized_$termCode"
    fun textbookKey(termCode: String): String = "schedule_textbooks_$termCode"
    private fun rawKey(termCode: String) = "schedule_$termCode"
    private fun examsKey(termCode: String) = "exams_$termCode"
    private fun startKey(termCode: String) = "start_date_$termCode"

    fun writeCurrentTerm(cache: DataCache, termCode: String) {
        if (termCode.isNotBlank()) runCatching { cache.write(CURRENT_TERM_KEY, termCode) }
    }

    /** 老版本升级上来还没打开过日程页时这个键是空的，依次退回上次看的学期、学期列表第一个。 */
    fun readCurrentTerm(cache: DataCache): String? =
        cache.read<String>(CURRENT_TERM_KEY, FOREVER)?.takeIf { it.isNotBlank() }
            ?: readLastTerm(cache)
            ?: readTermList(cache).firstOrNull()

    fun readLastTerm(cache: DataCache): String? = cache.read<String>(LAST_TERM_KEY, FOREVER)?.takeIf { it.isNotBlank() }
    fun writeLastTerm(cache: DataCache, termCode: String) { runCatching { cache.write(LAST_TERM_KEY, termCode) } }

    fun readTermList(cache: DataCache): List<String> = cache.read<List<String>>(TERM_LIST_KEY, FOREVER).orEmpty()
    fun writeTermList(cache: DataCache, terms: List<String>) { runCatching { cache.write(TERM_LIST_KEY, terms) } }

    fun readStartDate(cache: DataCache, termCode: String): LocalDate? =
        cache.read<String>(startKey(termCode), FOREVER)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    fun writeStartDate(cache: DataCache, termCode: String, date: LocalDate) {
        runCatching { cache.write(startKey(termCode), date.toString()) }
    }

    fun readExams(cache: DataCache, termCode: String, ttlMs: Long = FOREVER): List<ExamItem>? =
        cache.read<List<ExamItem>>(examsKey(termCode), ttlMs)
    fun writeExams(cache: DataCache, termCode: String, exams: List<ExamItem>) {
        runCatching { cache.write(examsKey(termCode), exams) }
    }

    fun readOptimizedCourses(cache: DataCache, termCode: String, ttlMs: Long = TERM_TTL_MS): List<CourseItem>? =
        if (termCode.isBlank()) null
        else cache.read<List<CourseItem>>(optimizedScheduleKey(termCode), ttlMs)?.map { it.normalized() }

    fun writeOptimizedCourses(cache: DataCache, termCode: String, courses: List<CourseItem>) {
        if (termCode.isNotBlank()) runCatching { cache.write(optimizedScheduleKey(termCode), courses) }
    }

    /** 教务原样的课表（未剔除节假日），变更检测、封存判断用。 */
    fun readRawCourses(cache: DataCache, termCode: String, ttlMs: Long = TERM_TTL_MS): List<CourseItem>? =
        if (termCode.isBlank()) null
        else cache.read<List<CourseItem>>(rawKey(termCode), ttlMs)?.map { it.normalized() }

    fun writeRawCourses(cache: DataCache, termCode: String, courses: List<CourseItem>) {
        if (termCode.isNotBlank()) runCatching { cache.write(rawKey(termCode), courses) }
    }

    /** 优先剔除过节假日的版本，没有再用原样的。 */
    fun readCourses(cache: DataCache, termCode: String): List<CourseItem>? =
        readOptimizedCourses(cache, termCode, FOREVER) ?: readRawCourses(cache, termCode, FOREVER)

    /** 本学期的课表和开学日期：首页、屁岱、桌面小组件共用。 */
    data class TermSchedule(val code: String, val courses: List<CourseItem>, val start: LocalDate?)

    fun readCurrentTermSchedule(cache: DataCache): TermSchedule? {
        val code = readCurrentTerm(cache) ?: return null
        return TermSchedule(code, readCourses(cache, code).orEmpty(), readStartDate(cache, code))
    }

    fun readTextbooks(cache: DataCache, termCode: String, ttlMs: Long = TERM_TTL_MS): List<TextbookItem>? =
        if (termCode.isBlank()) null else cache.read<List<TextbookItem>>(textbookKey(termCode), ttlMs)

    fun writeTextbooks(cache: DataCache, termCode: String, textbooks: List<TextbookItem>) {
        if (termCode.isNotBlank()) runCatching { cache.write(textbookKey(termCode), textbooks) }
    }

    fun filterByHolidays(
        courses: List<CourseItem>,
        startOfTerm: LocalDate?,
        holidayDates: Map<LocalDate, String>
    ): List<CourseItem> {
        if (startOfTerm == null || holidayDates.isEmpty()) return courses
        return courses.mapNotNull { course ->
            if (course.isUserCreated) return@mapNotNull course
            var changed = false
            val newBits = StringBuilder(course.weekBits)
            for (i in newBits.indices) {
                if (newBits[i] == '1') {
                    val courseDate = TermWeeks.dateOf(startOfTerm, i + 1, course.dayOfWeek)
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

    /**
     * 按学期自己的起止判断有没有结束（多留一周缓冲：最后一周还可能补录考勤、传回放）。
     * 不依赖「当前学期是哪个」：冷启动时它会被正在画的学期覆盖。
     */
    fun isFinishedByDate(startOfTerm: LocalDate?, weeks: Int, today: LocalDate = LocalDate.now()): Boolean {
        if (startOfTerm == null || weeks <= 0) return false
        return TermWeeks.weekOf(startOfTerm, today) > weeks + 1
    }

    /** 已结束且本地该有的都有：封存后不再为它发请求。 */
    fun isSealed(cache: DataCache, term: String): Boolean {
        if (term.isBlank()) return false
        val start = readStartDate(cache, term) ?: return false
        val courses = readCourses(cache, term) ?: return false
        return isFinishedByDate(start, courses.maxOfOrNull { it.weekBits.length } ?: 0)
    }
}
