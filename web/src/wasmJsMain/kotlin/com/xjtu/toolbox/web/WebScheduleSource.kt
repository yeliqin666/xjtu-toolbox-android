package com.xjtu.toolbox.web

import com.xjtu.toolbox.core.net.ApiMode
import com.xjtu.toolbox.core.net.CampusApi
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

/**
 * Web 这一端的**教务取数口** —— `:core` 的日程页只认 [JwxtScheduleSource] 这条缝
 *（`:data` 那份是 okhttp + jsoup + `SiteSession`，一步都进不了浏览器），这里用**服务端契约里
 * 真有的端点**拼出同一套语义。
 *
 * ## 用的哪四个端点（`docs/api-contract.md` §5 的八个之四）
 *
 * | 方法 | 端点 | 备注 |
 * |---|---|---|
 * | [currentTerm] | `GET /api/jwxt/term` | 两个后端形状相同 |
 * | [termStart] | `GET /api/jwxt/term-start?term=` | `?term=` 是契约里就有的参数（省略 = 当前学期） |
 * | [termList] / [termNames] / [termWeeksOf] | `GET /api/jwxt/terms` | 走 `:core` 的 `CampusSchoolCourseApi.terms()`（两种信封的读法它都写了） |
 * | [schedule] | `GET /api/jwxt/schedule?term=` | 行是上游原始列（`ScheduleRow` 那八列） |
 *
 * ## 三个**没有**的方法（如实返回空 / 抛错，不编）
 *
 * - [changes]（调停补课）：契约里**没有**这个端点（实测 serve 对 `/api/jwxt/changes` 答 404）。
 *   所以 [schedule] 给的是**排课原样** —— 与 `/api/jwxt/schedule` 端出去的那一份逐字相同
 *   （那个端点的 KDoc 就写着「调停补课没有合进这些行」）。日程页顶部那一行说明写的就是这件事
 *   （见 `WebFallbackScreens.WebScheduleGapNote`）。
 * - [examSchedule]（考试）：同一个理由（serve 没有这个端点）。返回空表 ⇒ 考试那一栏空着。
 * - [textbooks]（教材）：同一个理由。返回空表 ⇒ 教材那一栏空着。
 *
 * 这三条**都是服务端契约的缺口**，不是本端偷懒：campus-api 那边虽然有 `/api/jwxt/changes` 与
 * `/api/jwxt/exams`，但本端没有对应客户端（`:core` 里没有它们的模型与读法），而 serve 契约里
 * 三个都不存在 —— 编一个形状出来只会让「偶发 404 / 解析失败」变成看不见的错。
 *
 * ## 历史学期
 *
 * `schedule` 认任意学期（`?term=` 两个后端都吃），但 [termStart] 只对**当前学期**能给出真日期：
 * 拿别的学期去问只会领回当前学期的开学日（那是**静默错答**，比报错更糟），所以非当前学期一律抛错
 * —— 屏那边本来就 `try { source.termStartDate(term) } catch { null }`（`ScheduleViewModel.loadTerm`），
 * 于是历史学期退化成「没有开学日、周次按学期表/日期推」，而不是显示一个错的周次。
 *
 * ## 语气与纪律
 *
 * 实例在同一个会话期间**必须稳定**（`ScheduleViewModel` 靠对象身份判断「会话换了没有」，见
 * `ScheduleSession.source` 的 KDoc）：[WebScheduleHost] 每次 `remember` 一个，屏不重建外壳就不换。
 */
internal class WebScheduleSource(
    private val client: HttpClient,
    private val mode: ApiMode,
) : JwxtScheduleSource {

    private val api = CampusApi(client, API_BASE, mode)

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
        val data = api.schedule(code)
        // 总周数只用来定 weekBits 的长度（课表行自己是「第几周」的文本，见下面的映射）
        val totalWeeks = runCatching { termStart(code).totalWeeks }.getOrDefault(0)
        return SourceSchedule(
            courses = data.rows.map { it.toCourseItem(totalWeeks) },
            // 调停补课没有端点 ⇒ 这里恒为空表（见类 KDoc：给的是排课原样）
            changes = emptyList(),
        )
    }

    override suspend fun changes(term: String): List<ScheduleChangeEvent> = emptyList()

    override suspend fun examSchedule(term: String?): List<ExamItem> = emptyList()

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

    override suspend fun textbooks(studentId: String, term: String?): List<TextbookItem> = emptyList()

    private suspend fun loadTerms() {
        if (names.isNotEmpty()) return
        names = CampusSchoolCourseApi(client, API_BASE, mode).terms().associate { it.code to it.name }
    }
}

/**
 * campus-api 的一行（`KCM`/`SKJS`/`JASMC`/`SKXQ`/`KSJC`/`JSJC`/`ZCMC`/`JXBID` 八列，值一律是字符串）
 * → `:core` 的课表模型 [CourseItem]。
 *
 * 与 `:data` 那份 `JwxtScheduleApi.rows()` 的映射**逐字段对齐**（同一批上游列、同一个课类别名），
 * 唯一的差别是周次：上游 47 列里有 `SKZC` 位串，而契约投影的这八列只给 `ZCMC` 文本
 *（`1-16周` / `1-16周(单)` 那种）⇒ 这里用 `:core` 早就有的 `parseWeeksText` 翻成位串
 *（[CourseItem.getWeeks] 与 `isInWeek` 认的就是位串）。
 *
 * @param totalWeeks 这个学期的总周数（`term-start` 的 `ZZC`）；0 = 不知道 ⇒ 位串长度按文本里
 *   最大的那周定。位串**只会短不会长**：`CourseItem` 对超出长度的周一律当作没课。
 */
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
        // 契约投影的八列里没有课程号与课类别（那是上游另外那几十列）⇒ 如实留空，不拿课程名冒充。
        courseCode = "",
        courseType = "",
    )
}
