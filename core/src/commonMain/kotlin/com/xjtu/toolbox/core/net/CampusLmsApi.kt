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

/**
 * 思源学堂（`lms.xjtu.edu.cn`）的 **serve 版取数**：给 `:web` 用，实现 [LmsSource]。
 *
 * | 端点 | 本类实现的方法 |
 * |---|---|
 * | `GET /api/lms/courses` | [myCourses] |
 * | `GET /api/lms/activities?courseId=` | [courseActivities] |
 * | `GET /api/lms/activity?courseId=&activityId=` | [activityDetail] |
 *
 * 契约 §5.6；`:server` 那一侧是 `LmsRoutes.kt`（薄包装 `:data` 的 `AppLmsSource`）。
 *
 * ## `deadline` 由服务端并进来（所以详情端点要 `courseId`）
 *
 * `deadline` **只有列表接口给**（详情里没有那一格）⇒ 服务端每答一枪详情都会先按 `courseId`
 * 拉一次列表、把 `activityId` 那一条当 `brief` 并进来（`:data` 的 `LmsActivity.mergeBrief` 那条
 * 口径）。所以本类的 [activityDetail] **不自己并**：响应的 `deadline` 已经是并过的那一个；
 * 代价是 `courseId` 必须给 —— 它只能从列表里那一条 brief 拿（见 [activityDetail] 的说明）。
 *
 * ## 三处**如实不画**（都在端口上留了开关）
 *
 * | 端口方法 | 端不端 |
 * |---|---|
 * | [myCourses] / [courseActivities] / [activityDetail] | **端**（上表三个端点） |
 * | [userSubmitCount] | 不端：那个值本来就在详情里（`homework.userSubmitCount`），单独再开一条只会多一个口径。屏上列表的「已提交」标签靠 `runCatching` 拿到 null ⇒ 不画（详情打开过之后按详情里的数画） |
 * | [uploadBytes] / [saveDownload] | 不端：附件**元数据**端得出来（名字/类型/大小），但三个地址（`downloadUrl` / `previewUrl` / `attachmentUrl`）不投影 —— 那一枪要站点会话，浏览器直接打开只会拿到登录页。⇒ [supportsDownload] 报 `false`：详情页的附件卡只展示名字与大小、**不画**预览与下载那两个入口，批量下载那一整档也不出现 |
 * | [queueReplayVideos] / [supportsVideoQueue] / [publicPathLabel] | 不端：那是**宿主的**下载队列与落盘位置（Android 的 MediaStore / 桌面的下载目录），serve 这一层没有等价物。屏据 `supportsVideoQueue = false` 不露出「课堂回放」那一档（与桌面端同一条 seam） |
 * | 播放器（`LmsScreen` 的 `videoPlayer` 槽位） | 不端：回放与直播的**地址**都不投影（`lesson.replayVideos` / `live.liveStreams` 那些），所以屏上根本没有可点的播放动作 —— 地址不在，那一档不出现 |
 * | 作业的提交记录（`submissionList`） | 不端：那一块里有提交人的姓名与学号（§5.2 红线）⇒ `LmsActivity.submissionList` 恒 `null`，[supportsSubmissionList] 报 `false`：屏上**整块不画**提交记录，也不画「还没有提交记录」那张卡（后者会把「拿不到」说成「真没交」） |
 *
 * ## 教师姓名不投影（如实缺口，不是 bug）
 *
 * `LmsCourseSummary.instructors` 是**教师姓名**（§5.2 红线的例外清单里没有这一族）⇒ 整个数组
 * 不端。`:core` 的 `instructorNames` 因此在 serve 形态下显示「未知」（那是模型自己的兜底文案），
 * 与 `LmsActivity.liveInstructorNames` 同理。
 *
 * ## 正文：契约不端那个**纯函数**，本端按纯文本近似
 *
 * [htmlToPlainText] 在 `:data` 那一份用的是 jsoup（只有 JVM 的 jar，wasm 编不过），契约 §5.6
 * 也刻意不端它（它不是取数，正文原文随详情端出来）⇒ 本类自己去掉标签，见
 * [serveHtmlToPlainText] 的说明（与 jsoup 那份**不是**逐字相同）。
 *
 * ## 默认形态（campus-api）
 *
 * 思源学堂在 campus-api 里没有对应端点（`:app` / 桌面走 `:data` 的 okhttp）⇒ 非 serve 分支一律
 * [serveOnly]（抛错，不返回一份空数据）。
 *
 * @param baseUrl 留空 ⇒ 相对路径即**同源**（`:web` 由 `:server` 托管，必须是空）
 * @param mode 见 [ApiMode]：serve 支才走本契约的端点
 */
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

    /** 见类 KDoc：提交记录里有提交人姓名与学号（红线）⇒ 详情页整块不画。 */
    override val supportsSubmissionList: Boolean get() = false

    override suspend fun myCourses(): List<LmsCourseSummary> {
        requireServe()
        val data = client.serveData("加载学堂课程", baseUrl, COURSES_PATH)
        val courses = data.arr("courses") ?: return emptyList()
        // 课程那一层直接按 `:core` 的模型解码：服务端的 DTO 用的就是同一批 `@Serializable` 模型类
        // （字段名一个不差，嵌套四格也是它们自己）—— 手抄一遍只会多一份会漂的字段表。
        // ⚠️ `instructors` 不在响应里 ⇒ 解出来是空表（`instructorNames` 显示「未知」，见类 KDoc）。
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
        requireServe()
        // `?courseId=` 是**必需**的（服务端要靠它多打一枪列表把 `deadline` 并进来，见类 KDoc）：
        // 它只能从列表里那一条 brief 拿 —— 调用方（`:core` 的详情页与批量下载）都是这么传的。
        // 拿不到就不猜一个课程号：猜错会把另一个活动的 `deadline` 画到这一页上。
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

    /**
     * 见类 KDoc：契约没有「单问已提交次数」的端点（那个值就在详情里）。屏上唯一的调用点
     * （`LmsScreen` 里给列表同步「已提交」标签那一发）把它包在 `runCatching` 里，拿不到就不画
     * 那个标签；详情打开过之后按详情里的 `userSubmitCount` 画。
     */
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

/**
 * 列表里的一条活动。`type` 是小写字符串，[LmsActivityType.fromString] 吃的就是同一个字符串
 * （认不出是 `unknown`）⇒ 零转换。
 */
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

/**
 * 详情：通用格 + 三个**按类型只出现一格**的块（`homework` / `lesson` / `live`，另两块是 `null`）
 * 摊回 `:core` 那个扁平模型（`LmsActivity` 就是扁的）。
 *
 * 不投影的那几格如实留空：`instructors` / `liveInstructorNames`（姓名，红线）、
 * `replayVideos` / `liveStreams` / `replayDownloadUrls`（播放与下载那半条链）、`submissionList`
 * （提交记录里有姓名与学号）—— 留的是**模型自己的默认值**（空表 / null），不是编出来的条目。
 */
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

/**
 * 详情正文（HTML）转纯文本 —— serve 形态下的**降级读法**。
 *
 * 为什么本端要自己做：`:data` 那份 `htmlToPlainText` 用的是 jsoup（只有一份 JVM 的 jar，
 * 进不了 wasm），而契约 §5.6 **刻意不端**这个纯函数（它不是取数；正文原文随详情端出来）。
 *
 * 读法：`<br>` 与块级闭标签当换行，其余标签去掉，`&nbsp;` 那几个实体还原，一个换行内的空白
 * 收成单个空格，连续空行收成一空行。
 *
 * ⚠️ 与 jsoup 那份**不是逐字相同**（jsoup 会按 DOM 拍平行内元素、给块级元素补换行）：屏上
 * 换行位置可能与 App / 桌面略有出入，但不会多出标签。这是如实缺口，不是 bug。搬迁时钉下来的
 * 那四个形状（见 `:app` 的 `LmsHtmlTextTest`）本函数逐字都对得上。
 */
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
