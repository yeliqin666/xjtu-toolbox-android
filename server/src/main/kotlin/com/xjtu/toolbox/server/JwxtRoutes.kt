package com.xjtu.toolbox.server

import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.core.net.ScheduleData
import com.xjtu.toolbox.core.net.TermStartData
import com.xjtu.toolbox.judge.QmsException
import com.xjtu.toolbox.judge.QmsJudgeApi
import com.xjtu.toolbox.judge.QmsJudgeSource
import com.xjtu.toolbox.judge.QmsQuestionnaire
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
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/** `/api/jwxt*` 端点（`docs/api-contract.md`）。 */
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

        // ── 评教问卷（QMS：进行中的学生评教任务，?finished=0|1|all）────────
        get(JWXT_EVALUATIONS) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            val finishedSpec = call.request.queryParameters["finished"]?.trim()?.ifBlank { null } ?: "0"
            if (finishedSpec !in setOf("0", "1", "all")) {
                return@get call.respondBadRequestMessage("finished 只能是 0（未评）/ 1（已评）/ all")
            }
            try {
                val source = QmsJudgeSource(session.qmsSite())
                val (unfinished, finished) = source.load()
                val items = buildList {
                    if (finishedSpec != "1") unfinished.forEach { add(it.toDto(source)) }
                    if (finishedSpec != "0") finished.forEach { add(it.toDto(source)) }
                }
                call.respond(
                    ApiEnvelope.ok(
                        EvaluationsData(canSubmit = EVALUATIONS_CAN_SUBMIT, comment = QmsJudgeApi.DEFAULT_COMMENT, items = items),
                    ),
                )
            } catch (e: Exception) {
                call.respondUpstreamFailure(e, "加载评教问卷")
            }
        }

        // ── 评教模块状态 ─────────────────────────────────────────────
        get(JWXT_EVALUATIONS_STATUS) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            // 撤回（undo）未开放。canSubmit 字段必须出现
            call.respond(ApiEnvelope.ok(EvaluationsStatusData(canSubmit = EVALUATIONS_CAN_SUBMIT)))
        }

        // ── 评教提交（QMS：按最好一档填答后 PUT）────────────────────────
        //
        // 幂等判据是目标状态：这张答卷已经不是 DRAFT ⇒ 回 `submitted=false`，不再提交。
        post(JWXT_EVALUATIONS_SUBMIT) {
            if (!session.authenticated) return@post call.respondLoginRequired()
            val body = call.receiveOrNull<EvaluationSubmitRequest>()
                ?: return@post call.respondBadRequestMessage(EVALUATION_SUBMIT_BODY_MESSAGE)
            val responseId = body.responseId.trim()
            if (responseId.isEmpty()) return@post call.respondBadRequestMessage(EVALUATION_SUBMIT_BODY_MESSAGE)
            try {
                val source = QmsJudgeSource(session.qmsSite())
                val (unfinished, finished) = source.load()
                val target = unfinished.firstOrNull { it.responseId == responseId }
                if (target == null) {
                    if (finished.any { it.responseId == responseId }) {
                        return@post call.respond(
                            ApiEnvelope.ok(EvaluationSubmitResult(submitted = false, message = EVALUATION_ALREADY_MESSAGE)),
                        )
                    }
                    return@post call.respond(
                        HttpStatusCode.NotFound,
                        ApiEnvelope<Unit>(ApiErrors.NOT_FOUND, message = EVALUATION_NOT_FOUND_MESSAGE),
                    )
                }
                try {
                    source.judge(target)
                } catch (e: QmsException) {
                    return@post call.respondActionRejected(e.message.orEmpty().ifBlank { EVALUATION_REJECTED_MESSAGE })
                }
                call.respond(
                    ApiEnvelope.ok(EvaluationSubmitResult(submitted = true, message = EVALUATION_SUBMITTED_MESSAGE)),
                )
            } catch (e: Exception) {
                call.respondUpstreamFailure(e, "提交评教")
            }
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

/** [CourseItem] 的线上形状。 */
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

/** `GET /api/jwxt/school-courses` 的 `data`：分页形状 + 两个筛选项开关。 */
@Serializable
internal data class SchoolCoursePage(
    val page: Int,
    val size: Int,
    val total: Int,
    val courses: List<SchoolCourseDto>,
    val supportsDepartmentFilter: Boolean,
    val supportsElectiveFilter: Boolean,
)

/** `SchoolCourse` 的全量投影（人数、学时、上课时间地点、开课单位等都在）。 */
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

/** `GET /api/jwxt/evaluations` 的 `data`：`canSubmit` 必须出现。 */
@Serializable
internal data class EvaluationsData(
    val canSubmit: Boolean,
    /** 提交时主观题统一填的那句话（屏上的确认文案要告诉用户）。 */
    val comment: String,
    val items: List<EvaluationDto>,
)

/** `POST /api/jwxt/evaluations/submit` 的请求体：`responseId` 取自 `/api/jwxt/evaluations` 的同名字段。 */
@Serializable
internal data class EvaluationSubmitRequest(val responseId: String = "")

/**
 * `POST /api/jwxt/evaluations/submit` 的 `data`。
 *
 * [submitted] = 这一发真的提交了；`false` = 幂等命中（这张答卷本来就已评过）。
 */
@Serializable
internal data class EvaluationSubmitResult(val submitted: Boolean, val message: String)

/** 一份 QMS 答卷的投影（`key` / `teacher` / `tag` 与 `QmsJudgeSource.card` 同源）。 */
@Serializable
internal data class EvaluationDto(
    /** 与 `JudgeCard.key` 相同，就是 [responseId]。 */
    val key: String,
    val responseId: String,
    val course: String,
    /** 授课教师用「、」连起来；一个都没有时是「未知教师」。 */
    val teacher: String,
    val lecturers: List<String>,
    /** 评教任务名（如「2026-2027-1 期末评教」）。 */
    val plan: String,
    val tag: String,
    /** QMS 原始状态：`DRAFT` 未评，其余算已评。 */
    val status: String,
    val finished: Boolean,
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

private fun QmsQuestionnaire.toDto(source: QmsJudgeSource): EvaluationDto {
    val card = source.card(this)
    return EvaluationDto(
        key = card.key,
        responseId = responseId,
        course = card.course,
        teacher = card.teacher,
        lecturers = lecturers,
        plan = planTitle,
        tag = card.tag,
        status = status,
        finished = finished,
    )
}

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
internal const val JWXT_EVALUATIONS_SUBMIT = "evaluations/submit"
internal const val JWXT_PARAM_TERM = "term"

/** `?merged=`：只有 `1` 算数（`/api/jwxt/schedule` 那一条）。 */
internal const val JWXT_PARAM_MERGED = "merged"

/** 本层有没有提交评教的能力（撤回 undo 未开放）。 */
private const val EVALUATIONS_CAN_SUBMIT = true

/** `POST /api/jwxt/evaluations/submit` 的形状不对。 */
private const val EVALUATION_SUBMIT_BODY_MESSAGE =
    "请求体需要 responseId（取自 /api/jwxt/evaluations 的同名字段）"

/** 幂等命中：这张答卷已经评过了。 */
internal const val EVALUATION_ALREADY_MESSAGE = "这张问卷已经评过了"

/** 进行中的评教任务里没有这张答卷（可能已截止，或 responseId 传错了）。 */
internal const val EVALUATION_NOT_FOUND_MESSAGE = "问卷不在进行中的评教任务里：可能已截止，或 responseId 传错了"

/** 上游拒收但没给理由时的那句。 */
internal const val EVALUATION_REJECTED_MESSAGE = "评教系统没有接受这次提交"

internal const val EVALUATION_SUBMITTED_MESSAGE = "评教已提交"

/** 教务站点会话（`ServeSession` 注册过的那个）。 */
internal fun ServeSession.jwxtSite(): SiteSession = sessionManager.getSite(ServeSession.JWXT_SITE_KEY)

/** 学生评教（QMS）站点会话。 */
internal fun ServeSession.qmsSite(): SiteSession = sessionManager.getSite(ServeSession.QMS_SITE_KEY)

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

