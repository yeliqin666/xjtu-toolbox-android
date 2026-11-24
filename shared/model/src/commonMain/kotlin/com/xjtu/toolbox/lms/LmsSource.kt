package com.xjtu.toolbox.lms

// `lmsHomeworkSubmissionUrl` 的 KDoc 里按名字引用了 `:feature:lms` 那个函数：跨模块不能用 KDoc
// 链接语法（`[lmsHomeworkSubmissionUrl]` 在这里解析不到，dokka 会报 unresolved），只写名字。

/** 思源学堂（`lms.xjtu.edu.cn`）的取数端口 —— 屏与批量下载的编排都在 `:core`，「谁来取数、谁把文件落到哪儿」由各端自己实现。 */
interface LmsSource {

    /** 我的课程列表（`POST /api/my-courses`）。 */
    suspend fun myCourses(): List<LmsCourseSummary>

    /** 某门课的活动列表（`GET /api/courses/{id}/activities`）。 */
    suspend fun courseActivities(courseId: Int): List<LmsActivity>

    /** 活动详情（`GET /api/activities/{id}`）。 */
    suspend fun activityDetail(activityId: Int, brief: LmsActivity? = null): LmsActivity

    /** 课程公告（`GET /api/courses/{id}/bulletins`）；本端没接这个接口时恒空（列表里就只有活动）。 */
    suspend fun courseBulletins(courseId: Int): List<LmsBulletin> = emptyList()

    /** 讨论区某分区下的帖子（`GET /api/forum/categories/{id}`）；本端没接时恒空。 */
    suspend fun forumCategoryTopics(categoryId: Int, page: Int = 1): LmsForumCategoryResult = LmsForumCategoryResult()

    /** 帖子的回复楼层（`GET /api/forum/topics/{id}/replies`）；本端没接时恒空。 */
    suspend fun forumTopicReplies(topicId: Int): List<LmsForumReply> = emptyList()

    /** 正文里内嵌图片的字节（学堂自己的图走登录态）；取不到或本端不支持返回 null，屏上退化成链接。 */
    suspend fun imageBytes(url: String): ByteArray? = null

    /** 作业已提交次数（只有详情接口给 `user_submit_count`）。 */
    suspend fun userSubmitCount(activityId: Int): Int

    /**
     * 当前登录者的用户 id（`/user/index` 的 `globalData.user.id`）。
     *
     * 只为一件事存在：拼官方 h5 的作业提交页 `/course/{c}/homework/{h}/submission/create/{committerId}`
     * ——个人作业那一格要的就是这个 id（见 `feature/lms` 的 `lmsHomeworkSubmissionUrl`）。
     * 拿不到就返回 null，调用方退到作业详情页，不拿猜出来的号去拼地址。
     */
    suspend fun currentUserId(): Int? = null

    /** 活动正文（HTML）转纯文本。 */
    suspend fun htmlToPlainText(html: String): String

    /** 一份附件的整段字节（课件里的图片预览用；取不到返回 null，屏退化成图标）。 */
    suspend fun uploadBytes(upload: LmsUpload): ByteArray?

    /** 把一份东西落到本端下载位置（[LmsDownloadJob.upload] 走 `/api/uploads/{id}/blob`， [LmsDownloadJob.url] 是直链）。 */
    suspend fun saveDownload(job: LmsDownloadJob): LmsDownloadResult

    /** 本端能不能把「课堂回放」排进下载队列。 */
    val supportsVideoQueue: Boolean

    /** 本端能不能把附件落下来（[uploadBytes] 的图片预览与 [saveDownload] 的下载那一整档）。 */
    val supportsDownload: Boolean get() = true

    /** 本端能不能拿到作业的提交记录（详情里的 `submissionList`）。 */
    val supportsSubmissionList: Boolean get() = true

    /** 把回放视频排进本端的下载队列，返回排进去的条数（本端不支持时恒 0）。 */
    suspend fun queueReplayVideos(
        courseName: String,
        activityTitle: String,
        activityId: Int,
        videos: List<LmsReplayVideo>,
    ): Int

    /**
     * 这项活动的回放在本端下载队列里的状态：键是 [replayDownloadKey]，值是队列的状态串
     * （`pending` / `downloading` / `paused` / `completed` / `failed` / `cancelled`）。没排过的不在表里。
     */
    suspend fun replayDownloadStates(activityId: Int): Map<String, String> = emptyMap()

    /** 「下载到哪儿」的展示用前缀（批下对话框与结果卡上那句话要它），各端给各自的真实位置。 */
    fun publicPathLabel(): String
}

/** 回放地址在下载队列里的对照键：去掉查询串（签名每次取都不一样，路径才是同一个文件）。 */
fun replayDownloadKey(url: String): String = url.substringBefore('?')

/** 一份要落盘的东西。 */
data class LmsDownloadJob(
    val name: String,
    val mimeType: String,
    /** 课程目录下的相对路径（`课程名` 或 `课程名/活动名`）；空串 = 直接放在下载根目录。 */
    val folder: String,
    val upload: LmsUpload? = null,
    val url: String? = null,
)

/** 课程列表 / 活动列表的落盘缓存缝。 */
interface LmsCacheStore {
    suspend fun readCourses(): List<LmsCourseSummary>?
    suspend fun writeCourses(courses: List<LmsCourseSummary>)
    suspend fun readActivities(courseId: Int): List<LmsActivity>?
    suspend fun writeActivities(courseId: Int, activities: List<LmsActivity>)
    suspend fun readBulletins(courseId: Int): List<LmsBulletin>? = null
    suspend fun writeBulletins(courseId: Int, bulletins: List<LmsBulletin>) {}

    /**
     * 作业 id → 交过没有。
     *
     * 列表接口**不给** `user_submit_count`，只能一条作业一次请求去问详情（见
     * `LmsSource.userSubmitCount`）。不落盘的话每次重进思源学堂都要重新问一遍：网一慢或某条
     * 查询失败，「已提交」那几个字就整片消失，用户以为还要再交一次。所以查到的结论留一份。
     */
    suspend fun readSubmitted(): Map<Int, Boolean>? = null
    suspend fun writeSubmitted(byId: Map<Int, Boolean>) {}
}
