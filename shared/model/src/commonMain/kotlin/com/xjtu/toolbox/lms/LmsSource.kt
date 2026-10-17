package com.xjtu.toolbox.lms

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

    /** 「下载到哪儿」的展示用前缀（批下对话框与结果卡上那句话要它），各端给各自的真实位置。 */
    fun publicPathLabel(): String
}

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
}
