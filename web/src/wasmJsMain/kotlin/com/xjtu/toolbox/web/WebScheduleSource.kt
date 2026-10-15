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

/**
 * Web 这一端的**教务取数口** —— `:core` 的日程页只认 [JwxtScheduleSource] 这条缝
 *（`:data` 那份是 okhttp + jsoup + `SiteSession`，一步都进不了浏览器），这里用**服务端契约里
 * 真有的端点**拼出同一套语义。
 *
 * ## 用的哪七个端点（`docs/api-contract.md` §5）
 *
 * | 方法 | 端点 | 备注 |
 * |---|---|---|
 * | [currentTerm] | `GET /api/jwxt/term` | 两个后端形状相同 |
 * | [termStart] | `GET /api/jwxt/term-start?term=` | `?term=` 是契约里就有的参数（省略 = 当前学期） |
 * | [termList] / [termNames] / [termWeeksOf] | `GET /api/jwxt/terms` | 走 `:core` 的 `CampusSchoolCourseApi.terms()`（两种信封的读法它都写了） |
 * | [schedule] | `GET /api/jwxt/schedule?term=&merged=1` | serve 形态：**已合过调停补课**的课表（九格 `CourseItem` + 同一批 `ScheduleChangeEvent`，契约 §5.9）；campus-api 形态：行是上游原始列（`ScheduleRow` 那八列）—— **排课原样** |
 * | [changes] | `GET /api/jwxt/changes?term=` | 调停补课的**说明面记录**（`ScheduleChangeEvent`，第四批 §5.8） |
 * | [examSchedule] | `GET /api/jwxt/exams?term=` | 考试六格原样（`ExamItem`，同上） |
 * | [textbooks] | `GET /api/jwxt/textbooks?term=` | 九格 = `TextbookItem` 八格 + `hasTextbook`；**学号由服务端从会话取**（请求参数里没有那一格） |
 *
 * 后三条是 2026-10-14 第四批（§5.8 的消费方接线）接上的 —— 在那之前它们恒回空表，日程页顶部那行
 * 说明写的就是缺这三条。⚠️ 它们**只在 serve 形态里取得到**：campus-api 那一头没有本端认的这三条
 *（同一个路径在那边的字段名不同，而契约 §7 第 2 条写明 `:web` 只认本文档、不认 campus-api 的字段名）
 * ⇒ 非 serve 形态下照旧回空表 / 空记录，屏上那两栏因此仍是空的。
 *
 * ## 格子已经是「合过调停补课」的那一张（与 Android 同口径）
 *
 * 「今天这门课在哪上」要把排课原样与调停补课记录合起来（`:core` 的 `JwxtChanges.apply`）。本端
 * **不自己合**，也不按课程名猜 —— 直接问 §5.9 的那一档（`?merged=1`）：服务端端的就是 `:data`
 * 的 `JwxtScheduleApi.schedule()`，合并已经在那一层做完（Android / 桌面端吃的就是它）。
 *
 * 为什么不是「拿 `/api/jwxt/changes` 在本端自己 apply」：那个端点端的是**说明面**的
 * `ScheduleChangeEvent`，而 `apply` 吃的是带 `jxbid`（认原课）与 `appliedAt`（定先后）的
 * `JwxtChange` —— 按课程名 + 时段猜着合会在多班同课同时段时**改错格子**。契约里那一档就是为
 * 这件事开的（§5.9 写明了这一点），所以本端只用它。
 *
 * campus-api 形态下没有这一档（那一头不认 `merged`）⇒ 格子照旧是**排课原样**，日程页顶部
 * 那行说明写的就是它。
 *
 * ⚠️ [changes] 在本端**目前没有屏上消费方**：`:app` 里它是屁岱回答「最近调了什么课」的输入，
 * 而浏览器这一端的屁岱**循环在 serve**（D10 的 S-d：工具层是 `:server` 的 `ServeAgentTools`，直接读
 * `:data` 的 `JwxtScheduleApi.schedule()`），本端这一格没有人读。它是接口的一格，读法接上，
 * 等本端出现第二个消费方（例如某个屏要单独列调停补课）才有人读。
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
    /**
     * 调停补课（`/api/jwxt/changes?term=`，§5.8）：**说明面**记录（`ScheduleChangeEvent`）。
     *
     * 它**不改课表格子** —— 改格子的是 [schedule] 那一档（`?merged=1`，见类 KDoc）。
     * campus-api 形态取不到这一条（字段面本端不认，§7 第 2 条）⇒ 空表。
     */
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

    /**
     * 教材（`/api/jwxt/textbooks?term=`，§5.8）：九格里 `hasTextbook` 由服务端算好，本端按
     * `TextbookItem` 的八格解码（`hasSubstantiveTextbook` 是同一个判据，屏自己会用）。
     *
     * ⚠️ [studentId] **本端不用也不编**：真实学号在服务端会话里（请求参数里没有那一格，§5.2 红线），
     * 本端传的是空串；参数留着是为了与 `:data` 那一份签名一致。
     */
    override suspend fun textbooks(studentId: String, term: String?): List<TextbookItem> =
        if (serve) jwxt.textbooks(term) else emptyList()

    private suspend fun loadTerms() {
        if (names.isNotEmpty()) return
        names = CampusSchoolCourseApi(client, API_BASE, mode).terms().associate { it.code to it.name }
    }

    /** 这一份取数在对谁说话：serve 契约里才有 §5.8 那三条端点（见类 KDoc）。 */
    private val serve: Boolean get() = mode == ApiMode.SERVE
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
