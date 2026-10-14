package com.xjtu.toolbox.server

import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.lms.AppLmsSource
import com.xjtu.toolbox.lms.LmsAcademicYear
import com.xjtu.toolbox.lms.LmsActivity
import com.xjtu.toolbox.lms.LmsActivityType
import com.xjtu.toolbox.lms.LmsCourseAttributes
import com.xjtu.toolbox.lms.LmsCourseSummary
import com.xjtu.toolbox.lms.LmsDepartment
import com.xjtu.toolbox.lms.LmsSemester
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/**
 * `/api/lms*` —— 思源学堂的**三个只读端点**（`docs/api-contract.md` §5.6），走 `:data` 的
 * [AppLmsSource]（它包住搬进 `:data` 的 `LmsApi`；解析口径一行不重做）。
 *
 * | 端点 | 对应端口方法 |
 * |---|---|
 * | `GET /api/lms/courses` | `LmsSource.myCourses()` |
 * | `GET /api/lms/activities` | `LmsSource.courseActivities(courseId)` |
 * | `GET /api/lms/activity` | `LmsSource.courseActivities(courseId)` + `LmsSource.activityDetail(activityId, brief)` |
 *
 * ## 端了什么、刻意不端什么
 *
 * | 端口方法 | 本轮端不端 |
 * |---|---|
 * | `myCourses` / `courseActivities` / `activityDetail` | **端**（三个端点） |
 * | `userSubmitCount` | 不端：它的值已经在详情里（`userSubmitCount` 那一格），单独再开一条只会多一个口径 |
 * | `htmlToPlainText` | 不端：那是**把 HTML 变成屏上的几行**（一个纯函数），不是取数。正文原文随详情端出去 |
 * | `uploadBytes` / `saveDownload` | **不端**：取附件字节 = 「下载」那半条链，要站点会话；浏览器里没有它 |
 * | `queueReplayVideos` / `supportsVideoQueue` / `publicPathLabel` | **不端**：那是宿主的下载队列与落盘位置（Android 的 MediaStore / 桌面的下载目录），serve 这一层没有等价物 |
 * | 作业的**提交**（`POST /api/activities/{id}/submit` 那一类） | 学堂的写路径在 `:app` 的屏里，**本批只端只读** |
 *
 * ## 逐字段映射（判据是真源：`:data` 的解析 + `:core` 的 `LmsModels`）
 *
 * 三层形状都**原样沿用 `:core` 的模型**（字段名一个不改 —— 它们是 `LmsCourseSummary` /
 * `LmsActivity` 的字段），只有三处**不投影**：
 *
 * 1. ⚠️ **教师名不投影**：`LmsCourseSummary.instructors` 是
 *    `[{id, name}]`，而 `name` 是**教师姓名** —— 契约 §5.2 的例外清单里没有它（例外只有
 *    ① `fitness/score` 本人姓名/学号 ② 黄页/教师检索的通讯录 ③ `emptyroom/rooms` 实时档的教师名）
 *    ⇒ 整个 `instructors` 数组**不端**。`:core` 屏上那句「教师名列表拼接」
 *    （`LmsCourseSummary.instructorNames`）在 serve 模式下拿不到；`:web` 若要显示教师名，
 *    得先拍板把它加进例外清单（那是改契约，不是改这一层）。
 *    同理 `LmsActivity.liveInstructorNames`（直播那半的教师名单）不端；
 * 2. **附件只端元数据**（`{id, name, type, size}`）：`downloadUrl` / `previewUrl` / `attachmentUrl`
 *    三个地址**都不投影** —— 拿它们要站点的会话 cookie，浏览器直接打开只会拿到登录页
 *    （与 §5.5 里加餐券 `imageUrl` 那条已知缺口同型，但那边端了、这边不端：这里连「图片预览」
 *    都要 `uploadBytes` 那条没有端点的链）；
 * 3. **回放 / 直播的地址不投影**：`lesson` 的 `replayVideos` / `replayDownloadUrls`、
 *    `lecture_live` 的 `liveStreams` / `liveReplayVideos` 都不端（播放与下载那半条链在 `:web` 上
 *    没有端点）⇒ 只端 `replayVideoCount` 那个**条数**。
 *
 * ## 请求参数与错误码
 *
 * - `courses` 不收参数；
 * - `?courseId=`（`activities` 必填，正整数）；缺 / 不是正整数 ⇒ 400；
 * - `?courseId=&activityId=`（`activity` 两个都必填，正整数）⇒ 缺 / 不是正整数 ⇒ 400。
 *  ⚠️ **`courseId` 在详情里也是必需的**，不是可选提示：`deadline` **只有列表接口给**
 *  （详情 79 个键里没有它，见 `LmsApi.mergeBrief` 与 `LmsActivity.deadline` 的 KDoc），
 *  要照 `:core` 那条口径并进来，就得先按 `courseId` 拉一次活动列表、把 `activityId` 那一条
 *  当作 `brief` 传给 `activityDetail`。少了它，`deadline` 会退回 `endTime`（实测最多差 20 天）
 *  ⇒ 屏上那个「截止时间」会悄悄变成另一个值。所以本端点**多打一枪列表**，换的是同一个数；
 * - 401 令牌对但没会话 · 502 上游 / 网络故障（`FriendlyError` 中文短句，含会话失效那档）。
 *
 * @param session 会话装配；思源学堂站点（key = `lms`，`:data` 的 `LmsSession`）从它那里取。
 */
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
    }
}

// ── 数据模型（客户端形状）──────────────────────────────────────────

/** `GET /api/lms/courses` 的 `data`。 */
@Serializable
internal data class LmsCoursesData(val courses: List<LmsCourseDto>)

/**
 * 一门课（`:core` 的 [LmsCourseSummary] 的线上形状）。
 *
 * 嵌套的四格直接复用 `:core` 的模型类（它们本来就是 `@Serializable`），本层不重写一份。
 * ⚠️ **没有 `instructors`**：那是教师姓名，按红线不投影（见文件头 KDoc）。
 */
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

/** `GET /api/lms/activities` 的 `data`。 */
@Serializable
internal data class LmsActivitiesData(
    /** 请求里那个课程 id，原样回。 */
    val courseId: Int,
    val count: Int,
    val activities: List<LmsActivityBriefDto>,
)

/**
 * 列表里的一条活动（`:core` 的 [LmsActivity] 在列表接口里能给出来的那些格）。
 *
 * `type` 是 `LmsActivityType.value`（`homework` / `material` / `lesson` / `lecture_live` / `page` /
 * `forum` / `questionnaire` / `online_video`；认不出是 `unknown`）—— `:core` 的
 * `LmsActivityType.fromString` 吃的就是同一个字符串，消费方零转换。
 */
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

/**
 * `GET /api/lms/activity` 的 `data`。
 *
 * 三段：**通用格** + [uploads] + 三个**按类型只出现一格**的可空块（[homework] / [lesson] /
 * [live]）—— `type` 是哪一个，就只有哪一块是对象，另两块是 `null`（§5.6 写了这条）。
 */
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
    // `instructors` 刻意不投影：那是教师姓名（红线），见文件头 KDoc
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
    // `liveInstructorNames` 不在这一块里：那是教师姓名（红线），见文件头 KDoc
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

/**
 * 思源学堂站点会话（key = `lms`，`:data` 的 [com.xjtu.toolbox.auth.LmsSession]）。
 *
 * 取数实例用 [AppLmsSource]（桌面端吃的也是它）而不是直接 `LmsApi(site)`：它自己包 IO 调度，
 * 且「落盘」那一格由 `LmsDownloadSink` 决定 —— serve 这一层用默认的 `Unsupported` 是**如实**的
 * （本批没有下载端点，写路径不进这张图）。
 */
internal fun ServeSession.lmsSite(): SiteSession = sessionManager.getSite(ServeSession.LMS_SITE_KEY)

internal fun ServeSession.lmsSource(): AppLmsSource = AppLmsSource(lmsSite())

internal const val LMS_SEGMENT = "lms"
internal const val LMS_COURSES = "courses"
internal const val LMS_ACTIVITIES = "activities"
internal const val LMS_ACTIVITY = "activity"
internal const val LMS_PARAM_COURSE_ID = "courseId"
internal const val LMS_PARAM_ACTIVITY_ID = "activityId"
