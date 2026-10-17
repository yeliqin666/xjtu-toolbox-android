package com.xjtu.toolbox.web

import com.xjtu.toolbox.core.net.ApiMode
import com.xjtu.toolbox.core.net.CampusApi
import com.xjtu.toolbox.core.net.CampusJwxtScheduleApi
import com.xjtu.toolbox.core.net.CampusSchoolCourseApi
import com.xjtu.toolbox.core.net.ScheduleRow
import com.xjtu.toolbox.core.net.TermStartData
import com.xjtu.toolbox.schedule.CourseItem
import com.xjtu.toolbox.schedule.ExamItem
import com.xjtu.toolbox.schedule.JwxtScheduleSource
import com.xjtu.toolbox.schedule.ScheduleChangeEvent
import com.xjtu.toolbox.schedule.SourceSchedule
import com.xjtu.toolbox.schedule.TextbookItem
import com.xjtu.toolbox.schedule.parseWeeksText
import io.ktor.client.HttpClient

/** Web 这一端的教务取数口。 */
internal class WebScheduleSource(
    private val client: HttpClient,
    private val mode: ApiMode,
) : JwxtScheduleSource {

    private val api = CampusApi(client, API_BASE, mode)

    /** §5.8 那三条只读端点（调停补课 / 考试 / 教材）+ §5.9 的 `?merged=1` —— 它们只存在于 serve 契约里。 */
    private val jwxt = CampusJwxtScheduleApi(client, API_BASE, mode)

    /** 学期号 → 可读名（`/api/jwxt/terms`；serve 给名字，campus-api 只给号 ⇒ 由库里那份按号推）。 */
    private var names: Map<String, String> = emptyMap()

    /** 学期号 → 总周数（`term-start` 的 `totalWeeks`，问到过才记）。 */
    private val weeks = mutableMapOf<String, Int>()

    private var current: String? = null

    override fun termNames(): Map<String, String> = names

    override fun termWeeksOf(term: String): Int? = weeks[term]

    override suspend fun currentTerm(): String = current ?: api.term().also { current = it }

    override suspend fun schedule(term: String?): SourceSchedule {
        val code = term?.trim().orEmpty().ifEmpty { currentTerm() }
        // serve 形态：直接问 `/api/jwxt/schedule?merged=1` —— 服务端端的是 `:data` 的
        // `JwxtScheduleApi.schedule()`，调停补课（调/停/补）已经合进格子（见类 KDoc）。
        if (serve) return jwxt.mergedSchedule(code)
        // campus-api 形态：那一头不认 `merged`，只拿得到排课原样（行是上游那八列）。
        val data = api.schedule(code)
        // 总周数只用来定 weekBits 的长度（课表行自己是「第几周」的文本，见下面的映射）
        val totalWeeks = runCatching { termStart(code).totalWeeks }.getOrDefault(0)
        return SourceSchedule(
            courses = data.rows.map { it.toCourseItem(totalWeeks) },
            // 这一格在本端**没有任何消费方**（`ScheduleViewModel` 只用 `.courses`；serve 形态下那
            // 一批事件随 [schedule] 一起回来的就够），不为它多发一发请求。
            changes = emptyList(),
        )
    }

    /** 这一端的教材接口**不要学号**：`/api/jwxt/textbooks` 由服务端从会话里取（§5.8）。 */
    override val textbooksWithoutStudentId: Boolean get() = serve

    // 下面三条都先看 `serve`：`CampusJwxtScheduleApi` 只在 serve 形态答数（campus-api 里没有这三条）
    /** 调停补课（`/api/jwxt/changes?term=`，§5.8）：说明面记录（`ScheduleChangeEvent`）。 */
    override suspend fun changes(term: String): List<ScheduleChangeEvent> =
        if (serve) jwxt.changes(term) else emptyList()

    /** 考试安排（`/api/jwxt/exams?term=`，§5.8）：六格原样，行序 = 上游序。 */
    override suspend fun examSchedule(term: String?): List<ExamItem> =
        if (serve) jwxt.examSchedule(term) else emptyList()

    override suspend fun termStart(term: String): TermStartData {
        val code = term.trim()
        val now = currentTerm()
        // 见类 KDoc：`/api/jwxt/term-start` 只答当前学期（`?term=` 非当前学期两个后端都会
        // 把当前学期的开学日当成它答回来）⇒ 静默错答不如抛错。
        if (code.isNotEmpty() && code != now) {
            throw UnsupportedOperationException(
                "Web 这一端只拿得到当前学期（$now）的开学日：服务端的 /api/jwxt/term-start 不对历史学期作答",
            )
        }
        val start = api.termStart(code)
        if (code.isNotEmpty() && start.totalWeeks > 0) weeks[code] = start.totalWeeks
        return start
    }

    override suspend fun termList(): List<String> {
        loadTerms()
        return names.keys.toList()
    }

    /** 教材（`/api/jwxt/textbooks?term=`，§5.8） */
    override suspend fun textbooks(studentId: String, term: String?): List<TextbookItem> =
        if (serve) jwxt.textbooks(term) else emptyList()

    private suspend fun loadTerms() {
        if (names.isNotEmpty()) return
        names = CampusSchoolCourseApi(client, API_BASE, mode).terms().associate { it.code to it.name }
    }

    /** 这一份取数在对谁说话：serve 契约里才有 §5.8 那三条端点（见类 KDoc）。 */
    private val serve: Boolean get() = mode == ApiMode.SERVE
}

/** campus-api 的一行（`KCM`/`SKJS`/`JASMC`/`SKXQ`/`KSJC`/`JSJC`/`ZCMC`/`JXBID` 八列，值一律是字符串）→ `:core` 的课表模型 [CourseItem]。 */
private fun ScheduleRow.toCourseItem(totalWeeks: Int): CourseItem {
    val courseWeeks = parseWeeksText(weeksText)
    val span = maxOf(totalWeeks, courseWeeks.maxOrNull() ?: 0)
    return CourseItem(
        courseName = courseName.orEmpty(),
        teacher = teacher.orEmpty(),
        location = classroom.orEmpty(),
        weekBits = CharArray(span) { i -> if (i + 1 in courseWeeks) '1' else '0' }.concatToString(),
        dayOfWeek = dayOfWeek?.trim()?.toIntOrNull() ?: 1,
        startSection = startSection?.trim()?.toIntOrNull() ?: 1,
        endSection = endSection?.trim()?.toIntOrNull() ?: 1,
        // 契约里没有课程号与课类别 ⇒ 留空，不拿课程名冒充。
        courseCode = "",
        courseType = "",
    )
}
