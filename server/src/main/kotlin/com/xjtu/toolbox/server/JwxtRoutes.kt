package com.xjtu.toolbox.server

import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.core.net.ScheduleData
import com.xjtu.toolbox.core.net.TermStartData
import com.xjtu.toolbox.judge.JudgeApi
import com.xjtu.toolbox.judge.Questionnaire
import com.xjtu.toolbox.schedule.AppSchoolCourseSource
import com.xjtu.toolbox.schedule.CourseItem
import com.xjtu.toolbox.schedule.ExamItem
import com.xjtu.toolbox.schedule.JwxtScheduleApi
import com.xjtu.toolbox.schedule.ScheduleChangeEvent
import com.xjtu.toolbox.schedule.SchoolCourseQuery
import com.xjtu.toolbox.schedule.TextbookItem
import com.xjtu.toolbox.schedule.isTermCode
import com.xjtu.toolbox.score.ReportedGrade
import com.xjtu.toolbox.score.scoreReportSource
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/** `/api/jwxt*` —— 十一个端点（`docs/api-contract.md` §5 的 P0）。 */
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

        // ── 本学期课表（wdkb 的 xskcb.do；`?merged=1` 额外合进 xsdkkc.do 的调停补课）──
        get(JWXT_SCHEDULE) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            val params = call.request.queryParameters
            val requested = params[JWXT_PARAM_TERM]?.trim()?.takeIf { it.isNotEmpty() }
            if (requested != null && !isTermCode(requested)) {
                return@get call.respondBadRequestMessage(JWXT_TERM_SHAPE_MESSAGE)
            }
            // 与 `grades` 的 `all` 同一条读法：给了但不是 `1` 就是参数形状不对（不静默当成默认那一档）
            val merged = params[JWXT_PARAM_MERGED]
            if (merged != null && merged != "1") {
                return@get call.respondBadRequestMessage(JWXT_MERGED_SHAPE_MESSAGE)
            }
            try {
                val term = requested ?: currentTerm(session)
                val api = JwxtScheduleApi(session.jwxtSite())
                if (merged == "1") {
                    // 已合并的那一份（调停补课已合进课表）。合并规则在 `:data` 的 `JwxtChanges.apply`，
                    // 本层只投影、一行合并规则都不重写；`term` 是已经解析好的学期号 ⇒ 不再问一次当前学期。
                    val schedule = api.schedule(term)
                    call.respond(
                        ApiEnvelope.ok(
                            MergedScheduleData(
                                term = term,
                                count = schedule.courses.size,
                                courses = schedule.courses.map { it.toDto() },
                                changes = schedule.changes,
                            ),
                        ),
                    )
                } else {
                    val rows = api.rows(term)
                    call.respond(ApiEnvelope.ok(ScheduleData(term = term, rows = rows, count = rows.size)))
                }
            } catch (e: Exception) {
                call.respondUpstreamFailure(e, "加载课表")
            }
        }

        // ── 调停补课（wdkb 的 xskcb/xsdkkc.do：只是记录，不掺课表）──────────
        get(JWXT_CHANGES) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            val requested = call.request.queryParameters[JWXT_PARAM_TERM]?.trim()?.takeIf { it.isNotEmpty() }
            if (requested != null && !isTermCode(requested)) {
                return@get call.respondBadRequestMessage(JWXT_TERM_SHAPE_MESSAGE)
            }
            try {
                val term = requested ?: currentTerm(session)
                val changes = JwxtScheduleApi(session.jwxtSite()).changes(term)
                call.respond(ApiEnvelope.ok(ChangesData(term = term, count = changes.size, changes = changes)))
            } catch (e: Exception) {
                call.respondUpstreamFailure(e, "加载调停补课")
            }
        }

        // ── 考试安排（studentWdksapApp 的 wdksap.do）────────────────────
        get(JWXT_EXAMS) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            val requested = call.request.queryParameters[JWXT_PARAM_TERM]?.trim()?.takeIf { it.isNotEmpty() }
            if (requested != null && !isTermCode(requested)) {
                return@get call.respondBadRequestMessage(JWXT_TERM_SHAPE_MESSAGE)
            }
            try {
                val term = requested ?: currentTerm(session)
                val exams = JwxtScheduleApi(session.jwxtSite()).examSchedule(term)
                call.respond(ApiEnvelope.ok(ExamsData(term = term, count = exams.size, exams = exams)))
            } catch (e: Exception) {
                call.respondUpstreamFailure(e, "加载考试安排")
            }
        }

        get(JWXT_TEXTBOOKS) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            val requested = call.request.queryParameters[JWXT_PARAM_TERM]?.trim()?.takeIf { it.isNotEmpty() }
            if (requested != null && !isTermCode(requested)) {
                return@get call.respondBadRequestMessage(JWXT_TERM_SHAPE_MESSAGE)
            }
            try {
                val studentId = AccountContext.activeAccountId
                    ?: return@get call.respondLoginRequired()
                val term = requested ?: currentTerm(session)
                val books = JwxtScheduleApi(session.jwxtSite()).textbooks(studentId, term).map { it.toDto() }
                call.respond(ApiEnvelope.ok(TextbooksData(term = term, count = books.size, books = books)))
            } catch (e: Exception) {
                call.respondUpstreamFailure(e, "加载教材")
            }
        }

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

/** `GET /api/jwxt/changes` 的 `data`：行直接用 `:core` 的 [ScheduleChangeEvent]（`@Serializable`）。 */
@Serializable
internal data class ChangesData(
    val term: String,
    val count: Int,
    val changes: List<ScheduleChangeEvent>,
)

/** `GET /api/jwxt/schedule?merged=1` 的 `data`。 */
@Serializable
internal data class MergedScheduleData(
    val term: String,
    val count: Int,
    val courses: List<MergedCourseDto>,
    val changes: List<ScheduleChangeEvent>,
)

/** [CourseItem] 的线上形状（`docs/api-contract.md` §5.9）。 */
@Serializable
internal data class MergedCourseDto(
    val courseName: String,
    val teacher: String,
    val location: String,
    val weekBits: String,
    val dayOfWeek: Int,
    val startSection: Int,
    val endSection: Int,
    val courseCode: String,
    val courseType: String,
)

/** `GET /api/jwxt/exams` 的 `data`：行直接用 `:core` 的 [ExamItem]（`@Serializable`）。 */
@Serializable
internal data class ExamsData(
    val term: String,
    val count: Int,
    val exams: List<ExamItem>,
)

/** `GET /api/jwxt/textbooks` 的 `data`。 */
@Serializable
internal data class TextbooksData(
    val term: String,
    val count: Int,
    val books: List<TextbookDto>,
)

/** [TextbookItem] 的线上形状：八格原样（字段名一个不改）+ `hasTextbook`。 */
@Serializable
internal data class TextbookDto(
    val courseName: String,
    val textbookName: String,
    val author: String,
    val publisher: String,
    val isbn: String,
    val price: String,
    val edition: String,
    val courseCode: String,
    val hasTextbook: Boolean,
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

private fun CourseItem.toDto() = MergedCourseDto(
    courseName = courseName,
    teacher = teacher,
    location = location,
    weekBits = weekBits,
    dayOfWeek = dayOfWeek,
    startSection = startSection,
    endSection = endSection,
    courseCode = courseCode,
    courseType = courseType,
)

private fun TextbookItem.toDto() = TextbookDto(
    courseName = courseName,
    textbookName = textbookName,
    author = author,
    publisher = publisher,
    isbn = isbn,
    price = price,
    edition = edition,
    courseCode = courseCode,
    hasTextbook = hasSubstantiveTextbook,
)

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
internal const val JWXT_CHANGES = "changes"
internal const val JWXT_EXAMS = "exams"
internal const val JWXT_TEXTBOOKS = "textbooks"
internal const val JWXT_GRADES = "grades"
internal const val JWXT_SCHOOL_COURSES = "school-courses"
internal const val JWXT_EVALUATIONS = "evaluations"
internal const val JWXT_EVALUATIONS_STATUS = "evaluations/status"
internal const val JWXT_PARAM_TERM = "term"

/** `?merged=`：只有 `1` 算数（`/api/jwxt/schedule` 那一条，见 §5.9）。 */
internal const val JWXT_PARAM_MERGED = "merged"

/** 本层有没有提交评教的能力。 */
private const val EVALUATIONS_CAN_SUBMIT = false

/** `?terms=` 的读法：逗号分隔、最多 4 个学期（与 :core 客户端先例同一个上限）；没给 = 空表。 */
private fun io.ktor.http.Parameters.evalTerms(): List<String>? {
    val raw = get("terms")?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
    val terms = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    if (terms.isEmpty() || terms.size > 4) return null
    return terms
}


/** 教务站点会话（`ServeSession` 注册过的那个）。 */
internal fun ServeSession.jwxtSite(): SiteSession = sessionManager.getSite(ServeSession.JWXT_SITE_KEY)

/** 「这个请求问的是哪个学期」（`/api/jwxt/term-start` 与 `/api/jwxt/schedule` 共用）。 */
private suspend fun currentTerm(session: ServeSession): String {
    val term = AppSchoolCourseSource(session.jwxtSite()).currentTerm()
    if (term.isBlank()) throw IllegalStateException("教务未返回当前学期代码")
    return term
}

/** `?term=` 的形状：`2026-2027-1`，不对就 400。 */
private const val JWXT_TERM_SHAPE_MESSAGE = "term 需要形如 2026-2027-1"

/** `?merged=` 的形状（`/api/jwxt/schedule` 那一条）：给了但不是 `1` 就 400，不静默当成默认那一档。 */
private const val JWXT_MERGED_SHAPE_MESSAGE = "merged 参数只能是 1"

