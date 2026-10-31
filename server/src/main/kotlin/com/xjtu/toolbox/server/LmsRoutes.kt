package com.xjtu.toolbox.server

import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.lms.AppLmsSource
import com.xjtu.toolbox.lms.LmsAcademicYear
import com.xjtu.toolbox.lms.LmsActivity
import com.xjtu.toolbox.lms.LmsActivityType
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.lms.LmsDue
import com.xjtu.toolbox.lms.LmsDueStore
import com.xjtu.toolbox.lms.LmsCourseAttributes
import com.xjtu.toolbox.schedule.CourseItem
import com.xjtu.toolbox.schedule.lmsCourseMatched
import com.xjtu.toolbox.lms.LmsCourseSummary
import com.xjtu.toolbox.lms.LmsDepartment
import com.xjtu.toolbox.lms.LmsSemester
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/** `/api/lms*` —— 思源学堂的五个只读端点（`docs/api-contract.md` §5.6；后两个是 B-5 作业截止与 B-4 课程配对）。 */
internal fun Route.lmsRoutes(session: ServeSession) {
    route(LMS_SEGMENT) {

        // ── 我的课程列表 ──────────────────────────────────────────────
        get(LMS_COURSES) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            try {
                val courses = session.lmsSource().myCourses()
                call.respond(ApiEnvelope.ok(LmsCoursesData(courses.map { it.toDto() })))
            } catch (e: Exception) {
                call.respondUpstreamFailure(e, "加载学堂课程")
            }
        }

        // ── 某门课的活动列表 ──────────────────────────────────────────
        get(LMS_ACTIVITIES) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            val courseId = call.requireIntParam(LMS_PARAM_COURSE_ID, "课程 id（取自 /api/lms/courses 的 id）")
                ?: return@get
            try {
                val activities = session.lmsSource().courseActivities(courseId)
                call.respond(
                    ApiEnvelope.ok(
                        LmsActivitiesData(
                            courseId = courseId,
                            count = activities.size,
                            activities = activities.map { it.toBriefDto() },
                        ),
                    ),
                )
            } catch (e: Exception) {
                call.respondUpstreamFailure(e, "加载课程活动")
            }
        }

        // ── 单个活动详情 ──────────────────────────────────────────────
        get(LMS_ACTIVITY) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            val courseId = call.requireIntParam(LMS_PARAM_COURSE_ID, "课程 id（取自 /api/lms/courses 的 id）")
                ?: return@get
            val activityId = call.requireIntParam(LMS_PARAM_ACTIVITY_ID, "活动 id（取自 /api/lms/activities 的 id）")
                ?: return@get
            try {
                val source = session.lmsSource()
                // 先在列表里找那一条当 `brief`：`deadline` 只在列表接口里给（见文件头 KDoc）
                val brief = source.courseActivities(courseId).firstOrNull { it.id == activityId }
                call.respond(ApiEnvelope.ok(source.activityDetail(activityId, brief).toDetailDto()))
            } catch (e: Exception) {
                call.respondUpstreamFailure(e, "加载学堂活动详情")
            }
        }

        // ── 作业截止（B-5）：读 `:data` 那份落盘缓存，不为作业再发请求 ──
        get(LMS_DUE) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            val account = AccountContext.activeAccountId?.trim().orEmpty()
            if (account.isEmpty()) return@get call.respondLoginRequired()
            // `load` 自己吞错（读不到就是空表），所以这里不会因为存储没装而 502
            val items = LmsDueStore.load(account)
            call.respond(ApiEnvelope.ok(LmsDueData(count = items.size, items = items.map { it.toDto() })))
        }

        // ── 思源课程配对（B-4）：web 的课表下钻靠它（判据还是 `:model` 那份纯函数）──
        get(LMS_COURSE_MATCH) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            val params = call.request.queryParameters
            val courseName = params[LMS_PARAM_COURSE_NAME]?.trim().orEmpty()
            val courseCode = params[LMS_PARAM_COURSE_CODE]?.trim().orEmpty()
            val teacher = params[LMS_PARAM_TEACHER]?.trim().orEmpty()
            if (courseCode.isEmpty() && courseName.isEmpty()) {
                return@get call.respondBadRequestMessage(LMS_COURSE_MATCH_QUERY_MESSAGE)
            }
            try {
                val course = CourseItem(
                    courseName = courseName,
                    courseCode = courseCode,
                    teacher = teacher,
                )
                val matched = lmsCourseMatched(session.lmsSource().myCourses(), course)
                call.respond(
                    ApiEnvelope.ok(
                        LmsCourseMatchData(matched = matched != null, course = matched?.toDto()),
                    ),
                )
            } catch (e: Exception) {
                call.respondUpstreamFailure(e, "配学堂课程")
            }
        }
    }
}

// ── 数据模型（客户端形状）──────────────────────────────────────────

/** `GET /api/lms/course-match` 的 `data`：配上的那门（`matched=false` ⇒ `course=null`）。 */
@Serializable
internal data class LmsCourseMatchData(val matched: Boolean, val course: LmsCourseDto?)

/** `GET /api/lms/courses` 的 `data`。 */
@Serializable
internal data class LmsCoursesData(val courses: List<LmsCourseDto>)

/** 一门课（`:core` 的 [LmsCourseSummary] 的线上形状）。 */
@Serializable
internal data class LmsCourseDto(
    val id: Int,
    val name: String,
    val courseCode: String,
    val courseType: Int,
    /** 学分，**字符串**（上游给的是文本，`:data` 原样留）。 */
    val credit: String,
    val compulsory: Boolean,
    val startDate: String?,
    val endDate: String?,
    val academicYear: LmsAcademicYear,
    val semester: LmsSemester,
    val department: LmsDepartment,
    val courseAttributes: LmsCourseAttributes,
)

/** `GET /api/lms/due` 的 `data`：日程页「接下来」那一栏（未提交 + 有截止）。 */
@Serializable
internal data class LmsDueData(val count: Int, val items: List<LmsDueDto>)

/** 一条作业截止（`:model` 的 [LmsDue] 的线上形状；作业标题/课程名不是个人数据）。 */
@Serializable
internal data class LmsDueDto(
    val courseId: Int,
    val courseName: String,
    val activityId: Int,
    val title: String,
    /** 上游给的截止文本，原样留（`:model` 的 `LmsDue.deadline`）。 */
    val deadline: String,
    val submitted: Boolean,
    /** 学堂的「测试」：只进待办，不进日程。 */
    val exam: Boolean,
    /** 这一条是什么时候拉到的（ISO-8601；0 ⇒ null）。 */
    val fetchedAt: String?,
)

internal fun LmsDue.toDto(): LmsDueDto = LmsDueDto(
    courseId = courseId,
    courseName = courseName,
    activityId = activityId,
    title = title,
    deadline = deadline,
    submitted = submitted,
    exam = exam,
    fetchedAt = fetchedAt.takeIf { it > 0L }?.let { java.time.Instant.ofEpochMilli(it).toString() },
)
/** `GET /api/lms/activities` 的 `data`。 */
@Serializable
internal data class LmsActivitiesData(
    /** 请求里那个课程 id，原样回。 */
    val courseId: Int,
    val count: Int,
    val activities: List<LmsActivityBriefDto>,
)

/** 列表里的一条活动（`:core` 的 [LmsActivity] 在列表接口里能给出来的那些格）。 */
@Serializable
internal data class LmsActivityBriefDto(
    val id: Int,
    val courseId: Int,
    val type: String,
    val title: String,
    val moduleId: Int?,
    val startTime: String?,
    val visibleStartAt: String?,
    val endTime: String?,
    /** 截止时间：**只有列表接口给**（详情里没有），见文件头 KDoc 与 [LmsActivity.deadline]。 */
    val deadline: String?,
    val submitByGroup: Boolean,
    val published: Boolean,
    val createdAt: String,
    val updatedAt: String,
    /** 活动是否已结束（学堂关掉后附件 `/blob` 会 403）。 */
    val isClosed: Boolean,
)

/** `GET /api/lms/activity` 的 `data`。 */
@Serializable
internal data class LmsActivityData(
    val id: Int,
    val courseId: Int,
    val type: String,
    val title: String,
    /** 正文原文（HTML）：作业/课件在 `data.description`，页面型在 `data.content`（`:data` 挑好的那个）。 */
    val description: String?,
    val moduleId: Int?,
    val startTime: String?,
    val visibleStartAt: String?,
    val endTime: String?,
    val deadline: String?,
    val published: Boolean,
    val createdAt: String,
    val updatedAt: String,
    val isClosed: Boolean,
    /** 附件**元数据**；三个下载/预览地址不投影（见文件头 KDoc）。 */
    val uploads: List<LmsUploadDto>,
    /** `type == "homework"` 时的那一块。 */
    val homework: LmsHomeworkDto?,
    /** `type == "lesson"` 时的那一块。 */
    val lesson: LmsLessonDto?,
    /** `type == "lecture_live"` 时的那一块。 */
    val live: LmsLiveDto?,
)

/** 一份附件的元数据（`{id, name, type, size}`，**没有地址**）。 */
@Serializable
internal data class LmsUploadDto(val id: Int, val name: String, val type: String, val size: Int)

/** 作业专有格。 */
@Serializable
internal data class LmsHomeworkDto(
    val submitByGroup: Boolean,
    val groupId: Int?,
    val groupSetName: String?,
    val userSubmitCount: Int,
    val averageScore: Double?,
    val highestScore: Double?,
    val lowestScore: Double?,
    /** 已批阅人数。 */
    val hasScoreCount: Int?,
    /** 允许提交次数；[nonSubmitTimes] 为 true 表示不限次。 */
    val submitTimes: Int?,
    val nonSubmitTimes: Boolean,
)

/** 课堂专有格。视频列表与地址不投影（见文件头 KDoc），只给条数。 */
@Serializable
internal data class LmsLessonDto(val lessonStart: String?, val lessonEnd: String?, val replayVideoCount: Int)

/** 直播专有格。流地址与回放列表不投影（见文件头 KDoc）。 */
@Serializable
internal data class LmsLiveDto(
    val liveRoomName: String?,
    val liveRoomCode: String?,
    val liveStatus: String?,
    val externalLiveId: Int?,
    val viewLive: Boolean,
    val viewRecord: Boolean,
)

// ── 投影 helpers ─────────────────────────────────────────────────────

private fun LmsCourseSummary.toDto() = LmsCourseDto(
    id = id,
    name = name,
    courseCode = courseCode,
    courseType = courseType,
    credit = credit,
    compulsory = compulsory,
    startDate = startDate,
    endDate = endDate,
    academicYear = academicYear,
    semester = semester,
    department = department,
    courseAttributes = courseAttributes,
)

private fun LmsActivity.toBriefDto() = LmsActivityBriefDto(
    id = id,
    courseId = courseId,
    type = type.value,
    title = title,
    moduleId = moduleId,
    startTime = startTime,
    visibleStartAt = visibleStartAt,
    endTime = endTime,
    deadline = deadline,
    submitByGroup = submitByGroup,
    published = published,
    createdAt = createdAt,
    updatedAt = updatedAt,
    isClosed = isClosed,
)

private fun LmsActivity.toDetailDto() = LmsActivityData(
    id = id,
    courseId = courseId,
    type = type.value,
    title = title,
    description = description,
    moduleId = moduleId,
    startTime = startTime,
    visibleStartAt = visibleStartAt,
    endTime = endTime,
    deadline = deadline,
    published = published,
    createdAt = createdAt,
    updatedAt = updatedAt,
    isClosed = isClosed,
    uploads = uploads.map { LmsUploadDto(id = it.id, name = it.name, type = it.type, size = it.size) },
    homework = if (type == LmsActivityType.HOMEWORK) {
        LmsHomeworkDto(
            submitByGroup = submitByGroup,
            groupId = groupId,
            groupSetName = groupSetName,
            userSubmitCount = userSubmitCount,
            averageScore = averageScore,
            highestScore = highestScore,
            lowestScore = lowestScore,
            hasScoreCount = hasScoreCount,
            submitTimes = submitTimes,
            nonSubmitTimes = nonSubmitTimes,
        )
    } else {
        null
    },
    lesson = if (type == LmsActivityType.LESSON) {
        LmsLessonDto(lessonStart = lessonStart, lessonEnd = lessonEnd, replayVideoCount = replayVideoCount)
    } else {
        null
    },
    live = if (type == LmsActivityType.LECTURE_LIVE) {
        LmsLiveDto(
            liveRoomName = liveRoomName,
            liveRoomCode = liveRoomCode,
            liveStatus = liveStatus,
            externalLiveId = externalLiveId,
            viewLive = viewLive,
            viewRecord = viewRecord,
        )
    } else {
        null
    },
)

// ── 常量 ─────────────────────────────────────────────────────────────

/** 思源学堂站点会话（key = `lms`，`:data` 的 [com.xjtu.toolbox.auth.LmsSession]）。 */
internal fun ServeSession.lmsSite(): SiteSession = sessionManager.getSite(ServeSession.LMS_SITE_KEY)

internal fun ServeSession.lmsSource(): AppLmsSource = AppLmsSource(lmsSite())

internal const val LMS_SEGMENT = "lms"
internal const val LMS_COURSES = "courses"
internal const val LMS_ACTIVITIES = "activities"
internal const val LMS_ACTIVITY = "activity"
internal const val LMS_DUE = "due"
internal const val LMS_COURSE_MATCH = "course-match"
internal const val LMS_PARAM_COURSE_NAME = "courseName"
internal const val LMS_PARAM_COURSE_CODE = "courseCode"
internal const val LMS_PARAM_TEACHER = "teacher"
internal const val LMS_PARAM_COURSE_ID = "courseId"
internal const val LMS_PARAM_ACTIVITY_ID = "activityId"

/** 配对查询至少要给课程号或课名之一。 */
internal const val LMS_COURSE_MATCH_QUERY_MESSAGE = "缺查询条件：至少给 courseCode 或 courseName"
