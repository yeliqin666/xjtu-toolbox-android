package com.xjtu.toolbox.lms

/**
 * 思源学堂（`lms.xjtu.edu.cn`）的**取数端口** —— 屏与批量下载的编排都在 `:core`，
 * 「谁来取数、谁把文件落到哪儿」由各端自己实现。
 *
 * ## 为什么切在「取数 + 落盘」这一层
 *
 * 搬进 `:core` 之前，这一族的三个类绑着 Android：
 *  - `LmsApi` 直接打 okhttp（`site.executeWithReAuth`）；
 *  - 屏上的下载走 `MediaStore`（写公共「下载/XJTUToolBox/」并登记进「我的 · 下载管理」）；
 *  - 批量下载里的回放视频进 `media.DownloadManager` 队列（WorkManager + MediaStore）。
 *
 * 前两件是三端都要的（桌面也要能取数、也要把课件存下来），第三件在桌面上没有等价物
 * （没有 MediaStore，也没有那一屏下载管理）—— 所以端口把**「落盘这件事有没有」**也交出去
 *（[supportsVideoQueue]），屏据此决定要不要给用户露出「课堂回放」那一档，而不是让用户点了才发现不行。
 *
 * ## 各端实现
 *
 *  - `AppLmsSource`（`:data`，Android 与桌面共用）= 包住搬进 `:data` 的 [LmsApi]，自己包 IO；
 *    「文件落到哪儿」交给宿主槽位 `LmsDownloadSink`：Android 是 `AppLmsSink`（MediaStore +
 *    下载管理），桌面是 `DesktopLmsSaver`（写用户的下载目录）；
 *  - Web 端还没有这一条链（`AppRoute.Lms` 在 Web 上仍是未搬占位页）。
 *
 * ## IO 调度
 *
 * 与其余搬进 `:core` 的域同一条口径：**取数实现的 IO 调度由实现方自己负责**，屏只在自己的
 * 可取消作用域里调这些挂起函数，不替实现挑调度器（`LmsApi` 是阻塞式 okhttp，
 * `AppLmsSource` 自己 `withContext(Dispatchers.IO)`）。
 */
interface LmsSource {

    /** 我的课程列表（`POST /api/my-courses`）。 */
    suspend fun myCourses(): List<LmsCourseSummary>

    /** 某门课的活动列表（`GET /api/courses/{id}/activities`）。 */
    suspend fun courseActivities(courseId: Int): List<LmsActivity>

    /**
     * 活动详情（`GET /api/activities/{id}`）。[brief] 传列表里那条：**详情接口不返回 `deadline`**，
     * 要由实现把列表里那个值并进来（原来写在 `LmsApi.getActivityDetail` 上的那条口径，一行未改）。
     */
    suspend fun activityDetail(activityId: Int, brief: LmsActivity? = null): LmsActivity

    /** 作业已提交次数（只有详情接口给 `user_submit_count`）。 */
    suspend fun userSubmitCount(activityId: Int): Int

    /**
     * 活动正文（HTML）转纯文本。
     *
     * 为什么在端口上：转义要用 jsoup（只有 JVM 那一份 jar，进不了 `:core`，见 `:data` 的
     * build 脚本），而「正文展示成几行」是屏的事。实现方把搬迁前 `htmlToPlainText` 的读法原样搬过去，
     * 所以屏上看到的仍然是同一段文字。
     */
    suspend fun htmlToPlainText(html: String): String

    /**
     * 一份附件的整段字节（课件里的图片预览用；取不到返回 null，屏退化成图标）。
     *
     * 为什么是整段字节而不是流：图片本来就是小图，屏要把它交给 `:core` 的图片缝解成位图；
     * 大文件（回放、几百 MB 的课件）走不这条路 —— 它们是 [saveDownload]。
     */
    suspend fun uploadBytes(upload: LmsUpload): ByteArray?

    /**
     * 把一份东西**落到本端下载位置**（[LmsDownloadJob.upload] 走 `/api/uploads/{id}/blob`，
     * [LmsDownloadJob.url] 是直链）。落盘位置、是否登记进下载管理都由实现方决定。
     *
     * 实现方自己从上游拉字节（那是取数那一半），所以端口这一层不出现流类型 ——
     * `:core` 还要编到 wasm，而 `OutputStream` 是 JVM 的。
     */
    suspend fun saveDownload(job: LmsDownloadJob): LmsDownloadResult

    /**
     * 本端能不能把「课堂回放」排进下载队列。
     *
     * 桌面端没有那一套（回放动辄几百 MB，桌面这一轮直接写下载目录也不合适）⇒ `false`，
     * 批量下载对话框就不会露出那一档（见 `LmsActivityList` 里的 `hasLesson`）。
     */
    val supportsVideoQueue: Boolean

    /**
     * 把回放视频排进本端的下载队列，返回排进去的条数（本端不支持时恒 0）。
     *
     * 搬迁前这一句写在 `LmsBatchDownload` 里（`media.DownloadManager.enqueueDownloads`）——
     * 它是 Android 的下载管理（能暂停、断点续传、看进度），所以整句收进宿主的槽位。
     */
    suspend fun queueReplayVideos(
        courseName: String,
        activityTitle: String,
        activityId: Int,
        videos: List<LmsReplayVideo>,
    ): Int

    /**
     * 「下载到哪儿」的展示用前缀（批下对话框与结果卡上那句话要它），各端给各自的真实位置：
     * Android = `Download/XJTUToolBox/`（与搬迁前那句逐字一致），桌面 = `~/Downloads/`。
     */
    fun publicPathLabel(): String
}

/**
 * 一份要落盘的东西。
 *
 * 两个来源（与搬迁前的两条支路一一对应）：[upload] 是服务端登记的附件（按 id 拉 blob）、
 * [url] 是直链（回放视频、老师批阅后的标注版）。名字与类型由调用方给全 ——
 * 批量下载会先把它 sanitize 并去重（见 `LmsBatchDownload.sanitize` / `dedupe`）。
 */
data class LmsDownloadJob(
    val name: String,
    val mimeType: String,
    /** 课程目录下的相对路径（`课程名` 或 `课程名/活动名`）；空串 = 直接放在下载根目录。 */
    val folder: String,
    val upload: LmsUpload? = null,
    val url: String? = null,
)

/**
 * 课程列表 / 活动列表的**落盘缓存缝**。
 *
 * 搬迁前这两份缓存在屏上的 `LmsPageCache` 里，落盘用 Android 的 `DataCache`（按账号分命名空间、
 * 带 TTL 的 SharedPreferences）。桌面端没有那份宿主存储 —— 与校园卡 / 空闲教室同一条口径：
 * **传 null 就是不缓存**（取数照常，只是少了「下次进来先显示上次的」那一档）。
 *
 * 方法是挂起的：实现方是**阻塞式**存储（SharedPreferences 读写），搬迁前那些调用都在
 * `withContext(Dispatchers.IO)` 里，所以由实现方自己包（与 [LmsSource] 那条 IO 口径一致）——
 * `:core` commonMain 里连 `Dispatchers.IO` 都不存在（wasm 没有它）。
 *
 * ⚠️ 键名与 TTL 是**可观测行为**（老缓存要读得出来），实现方照搬迁前那三个键写：
 * `lms_courses` / `lms_activities_<courseId>`，TTL 用 `DataCache.TERM_TTL_MS`。
 */
interface LmsCacheStore {
    suspend fun readCourses(): List<LmsCourseSummary>?
    suspend fun writeCourses(courses: List<LmsCourseSummary>)
    suspend fun readActivities(courseId: Int): List<LmsActivity>?
    suspend fun writeActivities(courseId: Int, activities: List<LmsActivity>)
}
