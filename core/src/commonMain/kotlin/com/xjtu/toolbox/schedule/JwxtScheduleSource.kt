package com.xjtu.toolbox.schedule

import com.xjtu.toolbox.core.net.TermStartData
import kotlinx.datetime.LocalDate

/**
 * 「教务课表取数」这条缝：`:core` 的日程页 / ViewModel 只认这个接口，`：data` 给出实现
 * （[com.xjtu.toolbox.schedule.JwxtScheduleApi]，okhttp + jsoup + java.time 那一份）。
 *
 * 为什么必须有它：屏与 ViewModel 要搬进 `:core`（三端同一份），而取数只能待在 `:data`
 * （okhttp / jsoup 进不了 commonMain）。接口就是那条切口 —— 方法与语义照搬迁前**逐字相同**，
 * 只是名字从 `getXxx` 收成领域词：`getSchedule` → [schedule]、`getCurrentTerm` → [currentTerm]、
 * `getExamSchedule` → [examSchedule]、`getStartOfTerm` → [termStart]、`getTermList` → [termList]、
 * `getTextbooks` → [textbooks]。
 *
 * ⚠️ [termStart] 与 `:data` 的 `JwxtScheduleApi.rows` 是 **serve 模式的契约面**
 * （`:server` 的 `/api/jwxt/term-start`、`/api/jwxt/schedule` 直接调它们），形状与语义逐字冻结：
 * 改这里的返回类型等于改 `:server` 对外发的 JSON。
 *
 * ⚠️ [schedule] 返回的课表**已经合过调停补课**（`JwxtChanges.apply`）—— 这正是它与
 * `:data` 那个只给排课原样的 `rows()` 的区别，两者不要混用。
 */
interface JwxtScheduleSource {

    /** 学期号 → 可读名（教务学期接口里的 `MC`），没查到的学期不在表里。 */
    fun termNames(): Map<String, String>

    /** 某个学期教务给的总周数（含考试周）；没查到过为 null。 */
    fun termWeeksOf(term: String): Int?

    /** 当前学期号（教务 `dqxnxq.do`）；拿不到或形状不对时由实现决定抛错还是兜底。 */
    suspend fun currentTerm(): String

    /** 整学期课表，调课、停课、补课已合进去（见 `JwxtChanges`）。[term] 为空取当前学期。 */
    suspend fun schedule(term: String? = null): SourceSchedule

    /** 这学期最近一次拉到的调课/停课/补课记录（与 [schedule] 同一份数据的说明面）。 */
    suspend fun changes(term: String): List<ScheduleChangeEvent>

    /** 考试安排。[term] 为空取当前学期。 */
    suspend fun examSchedule(term: String? = null): List<ExamItem>

    /** 学期起点（第 1 周周一 + 总周数）。**serve 契约面**，见接口 KDoc。 */
    suspend fun termStart(term: String): TermStartData

    /**
     * 学期起点日（第 1 周周一），[termStart] 的日期那一段。
     *
     * 只要日期、不要总周数的调用方（`saveTermStart`、首页 Hero、桌面小组件）用这一条。
     * `startDate` 缺失时的口径与 [termStart] 一致：抛错，不编一个日期。
     */
    suspend fun termStartDate(term: String): LocalDate {
        val iso = termStart(term).startDate?.trim().orEmpty()
        if (iso.isEmpty()) throw IllegalStateException("教务未返回学期开始日期（$term）")
        return LocalDate.parse(iso)
    }
    /** 可选学期，新的在前。拿不到时实现自己兜底，不抛。 */
    suspend fun termList(): List<String>

    /** 教材（帆软报表）。[term] 为空取当前学期。 */
    suspend fun textbooks(studentId: String, term: String? = null): List<TextbookItem>
}
