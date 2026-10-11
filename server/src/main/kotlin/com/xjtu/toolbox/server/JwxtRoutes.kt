package com.xjtu.toolbox.server

import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.core.net.ScheduleData
import com.xjtu.toolbox.core.net.TermStartData
import com.xjtu.toolbox.judge.JudgeApi
import com.xjtu.toolbox.judge.Questionnaire
import com.xjtu.toolbox.schedule.AppSchoolCourseSource
import com.xjtu.toolbox.schedule.JwxtScheduleApi
import com.xjtu.toolbox.schedule.SchoolCourseQuery
import com.xjtu.toolbox.schedule.isTermCode
import com.xjtu.toolbox.score.ReportedGrade
import com.xjtu.toolbox.score.scoreReportSource
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/**
 * `/api/jwxt*` —— 八个端点（`docs/api-contract.md` §5 的 P0）：
 *
 * | 端点 | 对应端口（`:core` 模型 / `:data` 实现） |
 * |---|---|
 * | `GET /api/jwxt/terms` | `SchoolCourseSource.terms()`（`TermOption`） |
 * | `GET /api/jwxt/term` | `SchoolCourseSource.currentTerm()` |
 * | `GET /api/jwxt/term-start` | `JwxtScheduleApi.termStart()`（`:data` 的 `wdkb` 取数） |
 * | `GET /api/jwxt/schedule` | `JwxtScheduleApi.rows()`（同上） |
 * | `GET /api/jwxt/grades` | `ScoreReportSource.grades()`（`ReportedGrade`） |
 * | `GET /api/jwxt/school-courses` | `SchoolCourseSource.query()`（`SchoolCourseResult`） |
 * | `GET /api/jwxt/evaluations` `…/status` | `JudgeApi`（`Questionnaire`） |
 *
 * 契约 §5 写「terms/term 的对应端口是 ScheduleSource 一族」—— `:data` 里没有单独的
 * `AppScheduleSource`，课表那一族能取「学期列表 / 当前学期」的就是 `SchoolCourseSource`
 * （`kcbcx` 应用，`AppSchoolCourseSource` 两行适配），所以这两个端点走它。
 *
 * ## 逐字段映射（判据是真源：`:data` 的解析 + `:core` 的模型）
 *
 * - **terms**：`TermOption(code, name)` 原样投影，顺序 = 上游顺序（`xnxqcx.do` 按 `*order=-DM`
 *   倒序给，夹具三行里第三行只有 `DM` ⇒ `:data` 的 `getTermList` 把名字兜底成 `DM` 本身 ——
 *   同样的解析，同样的投影）；
 * - **term**：当前学期号（`dqxnxq.do` 的 `DM`）。这一格刻意不带 `name`：
 *   `/api/jwxt/term` 的语义是「当前学期号」，名字在 terms 列表里（与旧 campus-api
 *   `TermData(term)` 同一个形状）；
 * - **term-start**：`?term=`（省略 = 当前学期，与 `/api/jwxt/term` 同一个来源）。数据是
 *   `:core` 的 `TermStartData(term, startDate, totalWeeks)` —— `startDate` = 上游 `XQKSRQ` 的
 *   日期那一段、`totalWeeks` = `ZZC`（只在 `1..TermWeeks.MAX_REASONABLE` 里算数，否则 0）。
 *   刻意**没有**「今天是第几周」那种服务端算出来的字段：那是消费方拿 `startDate` 自己算的
 *   （契约 §4 的模型分工），端出去只会多一个要同步的口径；
 * - **schedule**：`?term=`（省略 = 当前学期，同上）。数据是 `:core` 的
 *   `ScheduleData(term, rows, count)` —— `rows` 就是 `ScheduleRow` 钉住的那 8 列，键名 = 上游列名
 *   （`KCM`/`SKJS`/`JASMC`/`SKXQ`/`KSJC`/`JSJC`/`ZCMC`/`JXBID`），上游给数字的三格读成文本、
 *   缺键是空串（口径在 `JwxtScheduleApi.rows`）。两个端点的 `?term=` 形状不对都答 400。
 *   ⚠️ 调停补课**没有**合进这些行（`:app` 的 `ScheduleApi.getSchedule` 会合）—— 见
 *   `JwxtScheduleApi.rows` 的 TODO，不要当成两端已经一致；
 * - **grades**：`ReportedGrade(courseName, coursePoint, score, gpa, term)` 原样投影。
 *   `gpa` 是**可空的**：等级制课程（如「优秀」）不参与绩点 ⇒ `null`（§4：null = 上游明确说没有）。
 *   学号只在**请求教务报表的 URL**（`xh=`）里出现，响应里**没有** —— 红线（不投影身份）；
 * - **school-courses**：`SchoolCourse` 的字段**全量**投影 —— `:data` 直连教务、97 列什么都有，
 *   所以契约 §5「要改：补人数/学时、YPSJDD、开课单位、公选筛选」这一条在这里**能补齐**
 *   （campus-api 刻意不投影的那几项：`totalHours`/`lectureHours`/`labHours`/`practiceHours`/
 *   `enrollCount`/`capacity`/`weeklyHours`/`maleEnrollCount`/`femaleEnrollCount`/
 *   `scheduleLocation`(=YPSJDD)/`department`/`electiveCategory`，全都有值，缺哪个键 `:data` 给
 *   默认值/空串 —— 与 `SchoolCourseApiJvmTest` 钉的同一套默认值）。两个筛选项开关
 *   （`supportsDepartmentFilter` / `supportsElectiveFilter`）如实报 true：直连侧两张下拉表都在。
 *   带分页（§4）：`{page, size, total, courses[]}`；
 * - **evaluations**：`?terms=&type=&finished=` 查询，`JudgeApi.getQuestionnaires(type, term, finished)`
 *   直接支持这三格。**canSubmit 恒为 false**：本层只投影读（写端点 `submit/undo` 是 P1，
 *   不实现也不投影 —— 契约 §5「只看不提交仍是默认」的如实写法，字段必须出现）；
 * - **evaluations/status**：`{canSubmit: false}`。
 *
 * ## 错误码
 *
 * 400 参数形状不对（`page`/`size` 不是正整数、`weekday` 不是 1..7、`finished` 不是 0/1/all、`terms`
 * 超过 4 个）· 401 令牌对但没会话 · 502 上游/网络故障（`FriendlyError` 中文短句）。
 *
 * @param session 会话装配；站点与学号都从它那里取（学号 = `AccountContext.activeAccountId`，
 *   登录/恢复时由 [ServeSession] 设好 —— 只在请求教务的 URL 里用，**不进**响应）。
 */
internal fun Route.jwxtRoutes(session: ServeSession) {
    route(JWXT_SEGMENT) {

        // ── 学期列表（kcbcx 的 xnxqcx.do）────────────────────────────
        get(JWXT_TERMS) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            try {
                val terms = AppSchoolCourseSource(session.jwxtSite()).terms()
                call.respond(ApiEnvelope.ok(TermsData(terms.map { TermOptionDto(it.code, it.name) })))
            } catch (e: Exception) {
                call.respondUpstreamFailure(e, "加载学期列表")
            }
        }

        // ── 当前学期号 ────────────────────────────────────────────────
        get(JWXT_TERM) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            try {
                call.respond(ApiEnvelope.ok(CurrentTermData(AppSchoolCourseSource(session.jwxtSite()).currentTerm())))
            } catch (e: Exception) {
                call.respondUpstreamFailure(e, "加载当前学期")
            }
        }

        // ── 学期起点（wdkb 的 jshkcb/cxjcs.do：XQKSRQ + ZZC）────────────
        get(JWXT_TERM_START) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            val requested = call.request.queryParameters[JWXT_PARAM_TERM]?.trim()?.takeIf { it.isNotEmpty() }
            if (requested != null && !isTermCode(requested)) {
                return@get call.respondBadRequestMessage(JWXT_TERM_SHAPE_MESSAGE)
            }
            try {
                val term = requested ?: currentTerm(session)
                call.respond(ApiEnvelope.ok(JwxtScheduleApi(session.jwxtSite()).termStart(term)))
            } catch (e: Exception) {
                call.respondUpstreamFailure(e, "加载学期起点")
            }
        }

        // ── 本学期课表（wdkb 的 xskcb.do，调停补课未合并：见类 KDoc）────────
        get(JWXT_SCHEDULE) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            val requested = call.request.queryParameters[JWXT_PARAM_TERM]?.trim()?.takeIf { it.isNotEmpty() }
            if (requested != null && !isTermCode(requested)) {
                return@get call.respondBadRequestMessage(JWXT_TERM_SHAPE_MESSAGE)
            }
            try {
                val term = requested ?: currentTerm(session)
                val rows = JwxtScheduleApi(session.jwxtSite()).rows(term)
                call.respond(ApiEnvelope.ok(ScheduleData(term = term, rows = rows, count = rows.size)))
            } catch (e: Exception) {
                call.respondUpstreamFailure(e, "加载课表")
            }
        }

        // ── 成绩报表（帆软报表，全学期一页一页拉完）────────────────────
        //
        // `?term=` 过滤某一学期；`?all=1` 显式要全部；都不给时默认全部（旧 campus-api 的默认就是
        // `all=1`，这里沿用）。过滤在**取数之后**做（端口 `ScoreReportSource.grades()` 不接收
        // 学期参数，那是它该有的形状 —— 过滤是投影不是解析）。
        get(JWXT_GRADES) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            val term = call.request.queryParameters[JWXT_PARAM_TERM]?.trim()?.takeIf { it.isNotEmpty() }
            val params = call.request.queryParameters
            if (params["all"] != null && params["all"] != "1") {
                return@get call.respondBadRequestMessage("all 参数只能是 1")
            }
            try {
                val studentId = AccountContext.activeAccountId
                    ?: return@get call.respondLoginRequired()
                val grades = scoreReportSource(session.jwxtSite(), studentId).grades()
                    .let { all -> if (term == null) all else all.filter { it.term == term } }
                call.respond(ApiEnvelope.ok(GradesData(grades.map { it.toDto() })))
            } catch (e: Exception) {
                call.respondUpstreamFailure(e, "加载成绩")
            }
        }

        // ── 全校课表（kcbcx 的 qxfbkccx.do，开课任务级）────────────────
        get(JWXT_SCHOOL_COURSES) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            val params = call.request.queryParameters
            val page = params.positiveInt("page", 1) ?: return@get call.respondBadRequestMessage("page 需要是正整数")
            val size = params.positiveInt("size", 20)?.coerceAtMost(200)
                ?: return@get call.respondBadRequestMessage("size 需要是正整数（上限 200）")
            val weekday = params["weekday"]?.toIntOrNull()?.takeIf { it in 1..7 }
            if (params["weekday"] != null && weekday == null) {
                return@get call.respondBadRequestMessage("weekday 需要是 1..7")
            }
            val from = params["from"]?.toIntOrNull()?.takeIf { it > 0 }
            val to = params["to"]?.toIntOrNull()?.takeIf { it > 0 }
            try {
                val source = AppSchoolCourseSource(session.jwxtSite())
                val termCode = params[JWXT_PARAM_TERM]?.trim()?.takeIf { it.isNotEmpty() }
                    ?: source.currentTerm()
                val result = source.query(
                    SchoolCourseQuery(
                        termCode = termCode,
                        courseName = params["course"].orEmpty(),
                        courseCode = params["code"].orEmpty(),
                        teacher = params["teacher"].orEmpty(),
                        campusCode = params["campus"].orEmpty(),
                        weekday = weekday ?: 0,
                        startSection = from ?: 0,
                        endSection = to ?: 0,
                    ),
                    page = page,
                    pageSize = size,
                )
                call.respond(
                    ApiEnvelope.ok(
                        SchoolCoursePage(
                            page = result.pageNumber,
                            size = result.pageSize,
                            total = result.totalSize,
                            courses = result.courses.map { it.toDto() },
                            supportsDepartmentFilter = source.supportsDepartmentFilter,
                            supportsElectiveFilter = source.supportsElectiveFilter,
                        ),
                    ),
                )
            } catch (e: Exception) {
                call.respondUpstreamFailure(e, "查询全校课表")
            }
        }

        // ── 评教问卷（wspjyyapp：未评 / 已评，可 ?terms=?type=?finished=）────
        get(JWXT_EVALUATIONS) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            val params = call.request.queryParameters
            // `?terms=`：逗号分隔、最多 4 个；没给 ⇒ 用当前学期（emptyList 哨兵）
            val termsSpec = params.evalTerms() ?: return@get call.respondBadRequestMessage("terms 最多 4 个学期，逗号分隔")
            val type = params["type"]?.trim()
            val finishedSpec = params["finished"]?.trim()?.ifBlank { null } ?: "0"
            if (finishedSpec !in setOf("0", "1", "all")) {
                return@get call.respondBadRequestMessage("finished 只能是 0（未评）/ 1（已评）/ all")
            }
            if (type != null && type !in setOf("01", "05")) {
                return@get call.respondBadRequestMessage("type 只能是 01（期末）或 05（过程）")
            }
            try {
                val api = JudgeApi(session.jwxtSite())
                val terms = termsSpec.ifEmpty { listOf(api.getCurrentTerm()) }
                val items = mutableListOf<EvaluationDto>()
                for (term in terms) {
                    val wantFinished = finishedSpec == "1" || finishedSpec == "all"
                    val wantUnfinished = finishedSpec == "0" || finishedSpec == "all"
                    if (type == null) {
                        // 与 :data 的 UndergraduateJudgeSource.load 同一个查询顺序：先过程（05）再期末（01）
                        if (wantUnfinished) items += api.getQuestionnaires("05", term, finished = false).map { it.toDto(false) }
                        if (wantUnfinished) items += api.getQuestionnaires("01", term, finished = false).map { it.toDto(false) }
                        if (wantFinished) items += api.getQuestionnaires("05", term, finished = true).map { it.toDto(true) }
                        if (wantFinished) items += api.getQuestionnaires("01", term, finished = true).map { it.toDto(true) }
                    } else {
                        if (wantUnfinished) items += api.getQuestionnaires(type, term, finished = false).map { it.toDto(false) }
                        if (wantFinished) items += api.getQuestionnaires(type, term, finished = true).map { it.toDto(true) }
                    }
                }
                call.respond(ApiEnvelope.ok(EvaluationsData(canSubmit = EVALUATIONS_CAN_SUBMIT, items = items)))
            } catch (e: Exception) {
                call.respondUpstreamFailure(e, "加载评教问卷")
            }
        }

        // ── 评教模块状态 ─────────────────────────────────────────────
        get(JWXT_EVALUATIONS_STATUS) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            // 只读投影：写端点（提交/撤回）是 P1，不实现 —— canSubmit 字段必须出现（契约 §5）
            call.respond(ApiEnvelope.ok(EvaluationsStatusData(canSubmit = EVALUATIONS_CAN_SUBMIT)))
        }
    }
}

// ── 数据模型（客户端形状；字段名与 :core 模型逐字对应）──────────────

/** `GET /api/jwxt/terms` 的 `data`。 */
@Serializable
internal data class TermsData(val terms: List<TermOptionDto>)

@Serializable
internal data class TermOptionDto(val code: String, val name: String)

/** `GET /api/jwxt/term` 的 `data`：只报学期号（`TermData` 旧形状）。 */
@Serializable
internal data class CurrentTermData(val term: String)

/** `GET /api/jwxt/grades` 的 `data`。 */
@Serializable
internal data class GradesData(val grades: List<GradeDto>)

/** `ReportedGrade` 的形状。`gpa` 可空：等级制课程（「优秀」）不参与绩点。 */
@Serializable
internal data class GradeDto(
    val courseName: String,
    val coursePoint: Double,
    val score: String,
    val gpa: Double? = null,
    val term: String,
)

/** `GET /api/jwxt/school-courses` 的 `data`：分页形状（§4）+ 两个筛选项开关。 */
@Serializable
internal data class SchoolCoursePage(
    val page: Int,
    val size: Int,
    val total: Int,
    val courses: List<SchoolCourseDto>,
    val supportsDepartmentFilter: Boolean,
    val supportsElectiveFilter: Boolean,
)

/** `SchoolCourse` 的全量投影（契约 §5「要补」的那批字段都在）。 */
@Serializable
internal data class SchoolCourseDto(
    val courseCode: String,
    val courseName: String,
    val sectionNumber: String,
    val teacher: String,
    val department: String,
    val credit: Double,
    val totalHours: Double? = null,
    val lectureHours: Double? = null,
    val labHours: Double? = null,
    val practiceHours: Double? = null,
    val enrollCount: Int? = null,
    val capacity: Int? = null,
    val className: String = "",
    val scheduleLocation: String = "",
    val campus: String = "",
    val isPublicElective: Boolean = false,
    val electiveCategory: String = "",
    val weeklyHours: Double? = null,
    val maleEnrollCount: Int? = null,
    val femaleEnrollCount: Int? = null,
    val teachingClassId: String = "",
    val termCode: String = "",
    val remaining: Int? = null,
    val fillRatio: Float? = null,
)

/** `GET /api/jwxt/evaluations` 的 `data`：`canSubmit` 必须出现（契约 §5）。 */
@Serializable
internal data class EvaluationsData(
    val canSubmit: Boolean,
    val items: List<EvaluationDto>,
)

/** 一份问卷卡片的投影（字段名与 `Questionnaire` / `JudgeCard` 同源，不重起名）。 */
@Serializable
internal data class EvaluationDto(
    /** 与 `JudgeCard.key` 同一个键公式：`${WJDM}_${JXBID}_${BPR}`。 */
    val key: String,
    val wjdm: String,
    val jxbid: String,
    val course: String,
    val teacher: String,
    /** `PGLXDM` 原文：`01` 期末 / `05` 过程。 */
    val type: String,
    /** `PGLXDM` 的显示名（判据与 `UndergraduateJudgeSource.card` 相同）。 */
    val tag: String,
    val term: String,
    val finished: Boolean,
    val startTime: String,
    val endTime: String,
)

/** `GET /api/jwxt/evaluations/status` 的 `data`。 */
@Serializable
internal data class EvaluationsStatusData(val canSubmit: Boolean)

// ── 投影 helpers ──────────────────────────────────────────────────

private fun ReportedGrade.toDto() = GradeDto(courseName, coursePoint, score, gpa, term)

private fun com.xjtu.toolbox.schedule.SchoolCourse.toDto() = SchoolCourseDto(
    courseCode = courseCode,
    courseName = courseName,
    sectionNumber = sectionNumber,
    teacher = teacher,
    department = department,
    credit = credit,
    totalHours = totalHours,
    lectureHours = lectureHours,
    labHours = labHours,
    practiceHours = practiceHours,
    enrollCount = enrollCount,
    capacity = capacity,
    className = className,
    scheduleLocation = scheduleLocation,
    campus = campus,
    isPublicElective = isPublicElective,
    electiveCategory = electiveCategory,
    weeklyHours = weeklyHours,
    maleEnrollCount = maleEnrollCount,
    femaleEnrollCount = femaleEnrollCount,
    teachingClassId = teachingClassId,
    termCode = termCode,
    remaining = remaining,
    fillRatio = fillRatio,
)

private fun Questionnaire.toDto(finished: Boolean) = EvaluationDto(
    key = "${WJDM}_${JXBID}_${BPR}",
    wjdm = WJDM,
    jxbid = JXBID,
    course = KCM,
    teacher = BPJS,
    type = PGLXDM,
    tag = when (PGLXDM) {
        "01" -> "期末评教"
        "05" -> "过程评教"
        else -> "评教"
    },
    // 学期取问卷行自己的 XNXQDM（多学期查询时逐行如实）
    term = XNXQDM,
    finished = finished,
    startTime = KSSJ,
    endTime = JSSJ,
)

// ── 常量与小工具 ───────────────────────────────────────────────────

internal const val JWXT_SEGMENT = "jwxt"
internal const val JWXT_TERMS = "terms"
internal const val JWXT_TERM = "term"
internal const val JWXT_TERM_START = "term-start"
internal const val JWXT_SCHEDULE = "schedule"
internal const val JWXT_GRADES = "grades"
internal const val JWXT_SCHOOL_COURSES = "school-courses"
internal const val JWXT_EVALUATIONS = "evaluations"
internal const val JWXT_EVALUATIONS_STATUS = "evaluations/status"
internal const val JWXT_PARAM_TERM = "term"

/**
 * 本层有没有提交评教的能力。
 *
 * **恒为 false 的如实理由**：`/api/jwxt/evaluations` 只投影读（写端点是 P1，不实现），
 * 而且 serve 的浏览器侧**没有**「确认提交」的宿主（提交要拼整卷答案 + 防 CSRF 表单回提，
 * 那套 UI 编排留到 P1 写端点一起做）。字段本身必须出现（契约 §5「要改」那一行）。
 */
private const val EVALUATIONS_CAN_SUBMIT = false

/** `?page=&size=` 的正整数读法；不是正整数（或没给）返回 null。 */
private fun io.ktor.http.Parameters.positiveInt(name: String, default: Int): Int? {
    val raw = get(name)?.trim() ?: return default
    return raw.toIntOrNull()?.takeIf { it > 0 }
}

/** `?terms=` 的读法：逗号分隔、最多 4 个学期（与 :core 客户端先例同一个上限）；没给 = 空表。 */
private fun io.ktor.http.Parameters.evalTerms(): List<String>? {
    val raw = get("terms")?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
    val terms = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    if (terms.isEmpty() || terms.size > 4) return null
    return terms
}


/** 教务站点会话（`ServeSession` 注册过的那个）。 */
internal fun ServeSession.jwxtSite(): SiteSession = sessionManager.getSite(ServeSession.JWXT_SITE_KEY)

/**
 * 「这个请求问的是哪个学期」（`/api/jwxt/term-start` 与 `/api/jwxt/schedule` 共用）。
 *
 * 只走「没给 `?term=`」那一支：给了的话端点已经判过形状（[isTermCode]，不对就 400）。
 * 当前学期与 `/api/jwxt/term` **同一个来源**（`:data` 的 `AppSchoolCourseSource.currentTerm()`）。
 *
 * 拿到空串时抛错，**不拿空学期去查**：那样课表与学期起点全按空学期查，页面一片空白还没有任何报错
 * —— 与 `:app` 的 `ScheduleApi.getCurrentTerm`（「接口偶发给回空行」那句）同一个判断。
 */
private suspend fun currentTerm(session: ServeSession): String {
    val term = AppSchoolCourseSource(session.jwxtSite()).currentTerm()
    if (term.isBlank()) throw IllegalStateException("教务未返回当前学期代码")
    return term
}

/** `?term=` 的形状（两个新端点共用一条口径）：`2026-2027-1`，不对就 400。 */
private const val JWXT_TERM_SHAPE_MESSAGE = "term 需要形如 2026-2027-1"

