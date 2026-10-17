package com.xjtu.toolbox.schedule

import com.xjtu.toolbox.core.net.TermStartData
import kotlinx.datetime.LocalDate

/** 「教务课表取数」这条缝，实现在 `:data` 的 [com.xjtu.toolbox.schedule.JwxtScheduleApi]。 */
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

    /** 学期起点日（第 1 周周一），[termStart] 的日期那一段。 */
    suspend fun termStartDate(term: String): LocalDate {
        val iso = termStart(term).startDate?.trim().orEmpty()
        if (iso.isEmpty()) throw IllegalStateException("教务未返回学期开始日期（$term）")
        return LocalDate.parse(iso)
    }
    /** 可选学期，新的在前。拿不到时实现自己兜底，不抛。 */
    suspend fun termList(): List<String>

    /** 这一端的教材接口要不要学号（默认要 —— `:data` 的帆软报表就是拿 `xh=` 去查的）。 */
    val textbooksWithoutStudentId: Boolean get() = false

    /** 教材（帆软报表）。[term] 为空取当前学期。 */
    suspend fun textbooks(studentId: String, term: String? = null): List<TextbookItem>
}
