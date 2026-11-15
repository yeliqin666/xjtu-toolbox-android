package com.xjtu.toolbox.server

import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.degree.AppDegreeSource
import com.xjtu.toolbox.degree.DegreeCurrentCourse
import com.xjtu.toolbox.degree.DegreeGrade
import com.xjtu.toolbox.degree.DegreePlanSummary
import com.xjtu.toolbox.degree.DegreeRaw
import com.xjtu.toolbox.degree.DegreeSource
import com.xjtu.toolbox.degree.degreeCurrentCourses
import com.xjtu.toolbox.platform.secureKeyValueStore
import com.xjtu.toolbox.schedule.KeyValueScheduleCacheStore
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 学业进度（契约 §5.16）：取数就是 `:data` 的 [AppDegreeSource]，这里只投影成 JSON。
 * 方案树、课程行、教务报告按上游原样转发（屏的 `DegreeBuilder` 读的就是这些原始字段），
 * 只把其中的本人身份字段剥掉（§5.2 红线）。
 */
internal fun Route.degreeRoutes(
    session: ServeSession,
    sourceOf: (ServeSession) -> DegreeSource = { it.degreeSource() },
) {
    route(DEGREE_SEGMENT) {

        get(DEGREE_PROGRESS) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            try {
                call.respond(ApiEnvelope.ok(sourceOf(session).fetch().toDto()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                call.respondUpstreamFailure(e, "加载学业进度")
            }
        }

        get(DEGREE_PLANS) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            val keyword = call.request.queryParameters[DEGREE_PARAM_KEYWORD]?.trim().orEmpty()
            val grade = call.request.queryParameters[DEGREE_PARAM_GRADE]?.trim().orEmpty()
            if (keyword.isEmpty() && grade.isEmpty()) {
                return@get call.respondBadRequestMessage("keyword 和 grade 至少给一个")
            }
            try {
                val page = sourceOf(session).searchPlans(keyword, grade)
                call.respond(ApiEnvelope.ok(DegreePlansData(total = page.total, plans = page.plans.map { it.toDto() })))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                call.respondUpstreamFailure(e, "搜索培养方案")
            }
        }

        // 换方案：带上用户自己的成绩对照别的方案；教务报告只针对本人方案，这里恒为 null
        get(DEGREE_PLAN) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            val params = call.request.queryParameters
            val code = params[DEGREE_PARAM_CODE]?.trim().orEmpty()
            if (code.isEmpty()) return@get call.respondBadRequestMessage("code 必填（取自 plans 那一行）")
            val plan = DegreePlanSummary(
                code = code,
                name = params[DEGREE_PARAM_NAME]?.trim().orEmpty(),
                college = params[DEGREE_PARAM_COLLEGE]?.trim().orEmpty(),
                grade = params[DEGREE_PARAM_GRADE]?.trim().orEmpty(),
            )
            try {
                call.respond(ApiEnvelope.ok(sourceOf(session).fetchPlan(plan).toDto()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                call.respondUpstreamFailure(e, "加载培养方案")
            }
        }

        get(DEGREE_CURRENT_COURSES) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            try {
                val courses = degreeCurrentCourses(session.sessionManager, session.degreeScheduleCache())
                call.respond(ApiEnvelope.ok(DegreeCurrentCoursesData(courses = courses.map { it.toDto() })))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                call.respondUpstreamFailure(e, "加载在修课程")
            }
        }

        // 教务的「重新计算」：会改教务那边的报告时间，所以是 POST；false = 等超时
        post(DEGREE_RECALCULATE) {
            if (!session.authenticated) return@post call.respondLoginRequired()
            try {
                call.respond(ApiEnvelope.ok(DegreeRecalculateData(done = sourceOf(session).recalculate())))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                call.respondUpstreamFailure(e, "重新计算学业进度")
            }
        }
    }
}

/** 课表缓存与小岱的 `get_schedule` 同一份店（按账号）：在修课程不必再拉一次课表。 */
internal fun ServeSession.degreeScheduleCache(): KeyValueScheduleCacheStore =
    KeyValueScheduleCacheStore(secureKeyValueStore(DEGREE_SCHEDULE_CACHE_STORE + AccountContext.safeSuffix()))

internal fun ServeSession.degreeSource(): DegreeSource =
    AppDegreeSource(sessionManager) { degreeCurrentCourses(sessionManager, degreeScheduleCache()) }

// ── 线上形状 ──

@Serializable
internal data class DegreeRawDto(
    val planCode: String,
    val planName: String,
    val required: Double?,
    val notes: String,
    val grade: String?,
    val groups: List<JsonObject>,
    val courses: List<JsonObject>,
    val report: JsonObject?,
    val grades: List<DegreeGradeDto>,
    val currentCourses: List<DegreeCurrentCourseDto>,
)

@Serializable
internal data class DegreeGradeDto(
    val code: String,
    val name: String,
    val credit: Double,
    val score: String,
    val passed: Boolean,
    val gpa: Double?,
    val term: String,
)

@Serializable
internal data class DegreeCurrentCourseDto(val code: String, val name: String, val term: String)

@Serializable
internal data class DegreePlanDto(val code: String, val name: String, val college: String, val grade: String)

@Serializable
internal data class DegreePlansData(val total: Int, val plans: List<DegreePlanDto>)

@Serializable
internal data class DegreeCurrentCoursesData(val courses: List<DegreeCurrentCourseDto>)

@Serializable
internal data class DegreeRecalculateData(val done: Boolean)

internal fun DegreeRaw.toDto() = DegreeRawDto(
    planCode = planCode,
    planName = planName,
    required = required,
    notes = notes,
    grade = grade,
    groups = groups.map { it.withoutIdentity() },
    courses = courses.map { it.withoutIdentity() },
    report = report?.withoutIdentity(),
    grades = grades.map { it.toDto() },
    currentCourses = currentCourses.map { it.toDto() },
)

private fun DegreeGrade.toDto() = DegreeGradeDto(code, name, credit, score, passed, gpa, term)

private fun DegreeCurrentCourse.toDto() = DegreeCurrentCourseDto(code, name, term)

private fun DegreePlanSummary.toDto() = DegreePlanDto(code, name, college, grade)

/** 上游行里的本人身份字段（学号、姓名、手机号、证件号等），任意深度剥掉。 */
internal val DEGREE_IDENTITY_KEYS = setOf(
    "XH", "XM", "XSXM", "XSBH", "SJH", "LXDH", "SFZJH", "DZYX",
    "xh", "xm", "studentId", "studentName", "studentCode", "phone", "mobile",
)

private fun JsonObject.withoutIdentity(): JsonObject =
    JsonObject(filterKeys { it !in DEGREE_IDENTITY_KEYS }.mapValues { (_, v) -> v.withoutIdentity() })

private fun JsonElement.withoutIdentity(): JsonElement = when (this) {
    is JsonObject -> withoutIdentity()
    is JsonArray -> JsonArray(map { it.withoutIdentity() })
    else -> this
}

internal const val DEGREE_SEGMENT = "degree"
internal const val DEGREE_PROGRESS = "progress"
internal const val DEGREE_PLANS = "plans"
internal const val DEGREE_PLAN = "plan"
internal const val DEGREE_CURRENT_COURSES = "current-courses"
internal const val DEGREE_RECALCULATE = "recalculate"
internal const val DEGREE_PARAM_KEYWORD = "keyword"
internal const val DEGREE_PARAM_GRADE = "grade"
internal const val DEGREE_PARAM_CODE = "code"
internal const val DEGREE_PARAM_NAME = "name"
internal const val DEGREE_PARAM_COLLEGE = "college"

/** 与 `ServeAgent` 里课表缓存同名同后缀。 */
private const val DEGREE_SCHEDULE_CACHE_STORE = "schedule_cache"
