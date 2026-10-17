package com.xjtu.toolbox.core.net

import com.xjtu.toolbox.lms.LmsActivity
import com.xjtu.toolbox.lms.LmsActivityType
import com.xjtu.toolbox.lms.LmsCourseSummary
import com.xjtu.toolbox.lms.LmsDownloadJob
import com.xjtu.toolbox.lms.LmsDownloadResult
import com.xjtu.toolbox.lms.LmsReplayVideo
import com.xjtu.toolbox.lms.LmsSource
import com.xjtu.toolbox.lms.LmsUpload
import com.xjtu.toolbox.util.AppJson
import com.xjtu.toolbox.util.arr
import com.xjtu.toolbox.util.obj
import com.xjtu.toolbox.util.safeBoolean
import com.xjtu.toolbox.util.safeDoubleOrNull
import com.xjtu.toolbox.util.safeInt
import com.xjtu.toolbox.util.safeIntOrNull
import com.xjtu.toolbox.util.safeString
import com.xjtu.toolbox.util.safeStringOrNull
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/** 思源学堂（`lms.xjtu.edu.cn`）的 serve 版取数：给 `:web` 用，实现 [LmsSource]。 */
class CampusLmsApi(
    private val client: HttpClient,
    private val baseUrl: String = "",
    /** 见 [ApiMode]：这一份取数只认 serve 契约（§5.6）。 */
    private val mode: ApiMode = ApiMode.CAMPUS_API,
) : LmsSource {

    /** 见类 KDoc：回放 / 直播的地址不投影，本端也没有下载队列 ⇒ 与桌面端同一条 seam。 */
    override val supportsVideoQueue: Boolean get() = false
    /** 见类 KDoc：附件的三个地址都不投影 ⇒ 预览与下载那一档整个不画。 */
    override val supportsDownload: Boolean get() = false

    /** 见类 KDoc：提交记录里有提交人姓名与学号 ⇒ 详情页整块不画。 */
    override val supportsSubmissionList: Boolean get() = false

    override suspend fun myCourses(): List<LmsCourseSummary> {
        requireServe()
        val data = client.serveData("加载学堂课程", baseUrl, COURSES_PATH)
        val courses = data.arr("courses") ?: return emptyList()
        // ⚠️ `instructors` 不在响应里 ⇒ 解出来是空表。
        return AppJson.decodeFromJsonElement<List<LmsCourseSummary>>(courses)
    }

    override suspend fun courseActivities(courseId: Int): List<LmsActivity> {
        requireServe()
        val data = client.serveData(
            "加载课程活动",
            baseUrl,
            ACTIVITIES_PATH,
            listOf(COURSE_ID_PARAM to courseId.toString()),
        )
        return data.arr("activities").orEmpty().mapNotNull { element ->
            (element as? JsonObject)?.toBriefActivity()
        }
    }

    override suspend fun activityDetail(activityId: Int, brief: LmsActivity?): LmsActivity {
        // 测试（type=exam）的详情在 /api/exams/{id}，不是活动详情：和 `:shared:data` 的 LmsApi 一样只用列表那条并指路网页
        if (brief?.type == LmsActivityType.EXAM) {
            return brief.copy(description = "测试需要在思源学堂网页端作答，这里只显示时间安排。")
        }
        requireServe()
        // `?courseId=` 必需且只能取自列表那条 brief：猜错会把别的活动的 `deadline` 画到这一页。
        val courseId = brief?.courseId?.takeIf { it > 0 }
            ?: throw UnsupportedOperationException(
                "serve 契约的详情端点要 courseId（GET $ACTIVITY_PATH?$COURSE_ID_PARAM=&$ACTIVITY_ID_PARAM=）：" +
                    "它按 courseId 把列表里那条 `deadline` 并进来（docs/api-contract.md §5.6）——" +
                    "这一枪没有 brief，本端拿不到课程号，不猜一个",
            )
        val data = client.serveData(
            "加载学堂活动详情",
            baseUrl,
            ACTIVITY_PATH,
            listOf(
                COURSE_ID_PARAM to courseId.toString(),
                ACTIVITY_ID_PARAM to activityId.toString(),
            ),
        )
        return data.toDetailActivity()
    }

    /** 见类 KDoc：契约没有「单问已提交次数」的端点（那个值就在详情里）。 */
    override suspend fun userSubmitCount(activityId: Int): Int = throw UnsupportedOperationException(
        "serve 契约没有「已提交次数」这个端点：它就在详情的 `homework.userSubmitCount` 里" +
            "（docs/api-contract.md §5.6 的 lms 那一节）—— 列表上那个「已提交」标签按详情里的数画，本端不单开一枪",
    )

    override suspend fun htmlToPlainText(html: String): String = serveHtmlToPlainText(html)

    /** 见类 KDoc：附件的三个地址都不投影 ⇒ 那一枪没有可打的地方（屏上也不画入口）。 */
    override suspend fun uploadBytes(upload: LmsUpload): ByteArray? = throw UnsupportedOperationException(
        "serve 契约不端附件的字节：三个地址（downloadUrl / previewUrl / attachmentUrl）都不投影" +
            "（要站点会话，浏览器直接打开只会拿到登录页，docs/api-contract.md §5.6）——" +
            "屏按 LmsSource.supportsDownload = false 不画预览入口",
    )

    /** 见类 KDoc：落盘是宿主的活（`saveDownload` = 取字节 + 写下载位置），serve 这一层两样都没有。 */
    override suspend fun saveDownload(job: LmsDownloadJob): LmsDownloadResult = throw UnsupportedOperationException(
        "serve 契约不端「下载到本端」：附件的地址与字节都没有端点，落盘位置更是宿主的活" +
            "（docs/api-contract.md §5.6）—— 屏按 LmsSource.supportsDownload = false 不画下载入口",
    )

    /** 见类 KDoc：那是宿主的下载队列（Android 的下载管理）。 */
    override suspend fun queueReplayVideos(
        courseName: String,
        activityTitle: String,
        activityId: Int,
        videos: List<LmsReplayVideo>,
    ): Int = throw UnsupportedOperationException(
        "serve 契约不端回放的下载队列：那是宿主的能力（Android 的 media.DownloadManager），" +
            "本端的 `supportsVideoQueue = false` 已经把那一档挡在对话框外（docs/api-contract.md §5.6）",
    )

    /** 见类 KDoc：没有落盘位置，就没有「下载到哪儿」这句话可显示。 */
    override fun publicPathLabel(): String = throw UnsupportedOperationException(
        "serve 契约没有落盘位置可报：本端不下载（LmsSource.supportsDownload = false，" +
            "docs/api-contract.md §5.6）",
    )

    private fun requireServe() {
        if (mode != ApiMode.SERVE) serveOnly("思源学堂取数", "$COURSES_PATH + $ACTIVITIES_PATH + $ACTIVITY_PATH")
    }
}

// ── 投影（serve DTO → `:core` 模型）────────────────────────────────────

/** 列表里的一条活动。 */
private fun JsonObject.toBriefActivity() = LmsActivity(
    id = this["id"].safeInt(),
    courseId = this["courseId"].safeInt(),
    type = LmsActivityType.fromString(this["type"].safeString()),
    title = this["title"].safeString(),
    // `moduleId` 那一类可空格用 `safeIntOrNull`：JSON null 与缺字段都是「不知道」，不是 0
    moduleId = this["moduleId"].safeIntOrNull(),
    startTime = this["startTime"].safeStringOrNull(),
    visibleStartAt = this["visibleStartAt"].safeStringOrNull(),
    endTime = this["endTime"].safeStringOrNull(),
    deadline = this["deadline"].safeStringOrNull(),
    submitByGroup = this["submitByGroup"].safeBoolean(),
    published = this["published"].safeBoolean(),
    createdAt = this["createdAt"].safeString(),
    updatedAt = this["updatedAt"].safeString(),
    isClosed = this["isClosed"].safeBoolean(),
)

/** 详情：通用格 + 三个按类型只出现一格的块（`homework` / `lesson` / `live`，另两块是 `null`）摊回 `:core` 那个扁平模型（`LmsActivity` 就是扁的）。 */
private fun JsonObject.toDetailActivity(): LmsActivity {
    val homework = obj("homework")
    val lesson = obj("lesson")
    val live = obj("live")
    val uploads = arr("uploads").orEmpty().mapNotNull { element ->
        val row = element as? JsonObject ?: return@mapNotNull null
        // 附件只端元数据：三个地址与 referenceId/status 那些都不投影 ⇒ 留模型的默认值
        LmsUpload(
            id = row["id"].safeInt(),
            name = row["name"].safeString(),
            type = row["type"].safeString(),
            size = row["size"].safeInt(),
        )
    }
    return LmsActivity(
        id = this["id"].safeInt(),
        courseId = this["courseId"].safeInt(),
        type = LmsActivityType.fromString(this["type"].safeString()),
        title = this["title"].safeString(),
        moduleId = this["moduleId"].safeIntOrNull(),
        startTime = this["startTime"].safeStringOrNull(),
        visibleStartAt = this["visibleStartAt"].safeStringOrNull(),
        endTime = this["endTime"].safeStringOrNull(),
        // 服务端并进来的那一个（见类 KDoc）：本端不自己并，也不拿 `endTime` 顶上
        deadline = this["deadline"].safeStringOrNull(),
        published = this["published"].safeBoolean(),
        createdAt = this["createdAt"].safeString(),
        updatedAt = this["updatedAt"].safeString(),
        isClosed = this["isClosed"].safeBoolean(),
        description = this["description"].safeStringOrNull(),
        uploads = uploads,
        // ── homework 块（不是作业时整块是 null ⇒ 这些格保持默认值）──
        submitByGroup = this["submitByGroup"].safeBoolean(),
        groupId = homework?.get("groupId").safeIntOrNull(),
        groupSetName = homework?.get("groupSetName").safeStringOrNull(),
        userSubmitCount = homework?.get("userSubmitCount").safeInt() ?: 0,
        averageScore = homework?.get("averageScore").safeDoubleOrNull(),
        highestScore = homework?.get("highestScore").safeDoubleOrNull(),
        lowestScore = homework?.get("lowestScore").safeDoubleOrNull(),
        hasScoreCount = homework?.get("hasScoreCount").safeIntOrNull(),
        submitTimes = homework?.get("submitTimes").safeIntOrNull(),
        nonSubmitTimes = homework?.get("nonSubmitTimes").safeBoolean() ?: false,
        // ── lesson 块：只给条数，视频列表与地址不投影 ──
        lessonStart = lesson?.get("lessonStart").safeStringOrNull(),
        lessonEnd = lesson?.get("lessonEnd").safeStringOrNull(),
        replayVideoCount = lesson?.get("replayVideoCount").safeInt() ?: 0,
        // ── live 块：房间那几格是，流地址与回放列表不是 ──
        liveRoomName = live?.get("liveRoomName").safeStringOrNull(),
        liveRoomCode = live?.get("liveRoomCode").safeStringOrNull(),
        liveStatus = live?.get("liveStatus").safeStringOrNull(),
        externalLiveId = live?.get("externalLiveId").safeIntOrNull(),
        viewLive = live?.get("viewLive").safeBoolean() ?: false,
        viewRecord = live?.get("viewRecord").safeBoolean() ?: false,
    )
}

/** 详情正文（HTML）转纯文本 —— serve 形态下的降级读法。 */
private fun serveHtmlToPlainText(html: String): String {
    // 先把「换行」标出来（用一个正文里不会出现的哨兵字符），免得后面的空白归一化把它吃掉
    val marked = html
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), NEWLINE_MARK)
        .replace(Regex("</(p|div|li|h[1-6]|tr|blockquote)\\s*>", RegexOption.IGNORE_CASE), NEWLINE_MARK)
    val stripped = marked.replace(Regex("<[^>]*>"), "")
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&amp;", "&")
    return stripped.split(NEWLINE_MARK)
        .joinToString("\n") { it.replace(Regex("[ \\t\\r\\n\\u00A0]+"), " ").trim() }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}

/** 见 [serveHtmlToPlainText]：标换行用的哨兵（HTML 正文里不会出现的控制字符）。 */
private const val NEWLINE_MARK = "\u0000"

/** 我的课程列表（契约 §5.6 的三个 lms 端点之一）。 */
private const val COURSES_PATH = "/api/lms/courses"

/** 某门课的活动列表。 */
private const val ACTIVITIES_PATH = "/api/lms/activities"

/** 单个活动详情。 */
private const val ACTIVITY_PATH = "/api/lms/activity"

private const val COURSE_ID_PARAM = "courseId"
private const val ACTIVITY_ID_PARAM = "activityId"
