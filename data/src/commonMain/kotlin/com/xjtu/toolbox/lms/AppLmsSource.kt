package com.xjtu.toolbox.lms

import com.xjtu.toolbox.auth.SiteSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `:core` 的 [LmsSource] 在 `:data` 里的实现（Android 与桌面共用）—— 包住搬进 `:data` 的
 * [LmsApi]（okhttp 抓 `lms.xjtu.edu.cn` 思源学堂），并把「下载/回放队列」交给宿主槽位
 * [LmsDownloadSink]（`:app` 的 `AppLmsSink` / 桌面 `DesktopLmsSaver`）。
 *
 * 两件事各动一半：
 *
 * 1. **取数**（[LmsApi]）的类名与包路径一字未改，只是从 `:app/lms/` 挪到同包的 `:data/lms/`；
 *    它本来就用 `:data` 的 [SiteSession]（`lms` 站点，见
 *    [com.xjtu.toolbox.auth.LmsSession] 与 [com.xjtu.toolbox.lms.LmsLogin]），所以一行调用都不用改。
 * 2. **调度器**：搬迁前是屏（`LmsPageCache` 等）每次调用自己 `withContext(Dispatchers.IO)`，
 *    现在屏不再替实现挑调度器（见 [LmsSource] 的 KDoc），所以由本类自己包 ——
 *    **同一层、同一个调度器，行为不变**。
 *
 * **落盘**也收进本实现（[saveDownload]）：从上游拉字节那一步仍然是 [LmsApi] 的
 * `downloadUpload` / `downloadToStream`（走会话、判断 403 关闭下载的语义一行未改），
 * 「写到哪儿、记不登记」由 [sink] 决定。桌面没有「我的 · 下载管理」，所以 Android 与
 * 桌面看到的 [LmsSource.publicPathLabel] / [LmsSource.supportsVideoQueue] 不同 —— 屏据此
 * 老实显示本端的位置与可选档位。
 */
class AppLmsSource(
    private val site: SiteSession,
    private val sink: LmsDownloadSink = LmsDownloadSink.Unsupported,
) : LmsSource {

    private val api = LmsApi(site)

    override suspend fun myCourses(): List<LmsCourseSummary> =
        withContext(Dispatchers.IO) { api.getMyCourses() }

    override suspend fun courseActivities(courseId: Int): List<LmsActivity> =
        withContext(Dispatchers.IO) { api.getCourseActivities(courseId) }

    override suspend fun activityDetail(activityId: Int, brief: LmsActivity?): LmsActivity =
        withContext(Dispatchers.IO) { api.getActivityDetail(activityId, brief) }

    override suspend fun userSubmitCount(activityId: Int): Int =
        withContext(Dispatchers.IO) { api.getUserSubmitCount(activityId) }

    override suspend fun htmlToPlainText(html: String): String =
        withContext(Dispatchers.IO) { com.xjtu.toolbox.lms.htmlToPlainText(html) }

    override suspend fun uploadBytes(upload: LmsUpload): ByteArray? =
        withContext(Dispatchers.IO) { api.downloadUploadBytes(upload) }

    override suspend fun saveDownload(job: LmsDownloadJob): LmsDownloadResult =
        withContext(Dispatchers.IO) {
            // `job` 的类型在 `:core`（另一个模块）⇒ 它的 `upload` / `url` 是 public API 属性，
            // 跨模块拿不到 smart cast，只能先取成局部 val 再判空（语义一字不变）。
            val upload = job.upload
            val url = job.url
            sink.save(job.name, job.mimeType, job.folder) { out ->
                when {
                    upload != null -> api.downloadUpload(upload, out)
                    url != null ->
                        if (api.downloadToStream(url, out)) LmsDownloadResult.Ok else LmsDownloadResult.Failed
                    else -> LmsDownloadResult.Failed
                }
            }
        }

    override val supportsVideoQueue: Boolean get() = sink.supportsVideoQueue

    override suspend fun queueReplayVideos(
        courseName: String,
        activityTitle: String,
        activityId: Int,
        videos: List<LmsReplayVideo>,
    ): Int = withContext(Dispatchers.IO) {
        sink.queueReplayVideos(courseName, activityTitle, activityId, videos)
    }

    override fun publicPathLabel(): String = sink.publicPathLabel()
}