package com.xjtu.toolbox.lms

import com.xjtu.toolbox.auth.SiteSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** `:core` 的 [LmsSource] 在 `:data` 里的实现（Android 与桌面共用） */
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

    override suspend fun courseBulletins(courseId: Int): List<LmsBulletin> =
        withContext(Dispatchers.IO) { api.getCourseBulletins(courseId) }

    /** 学堂（`*.xjtu.edu.cn`）的图走登录态取；外站图不带学堂的凭据，用一个干净的客户端取。 */
    override suspend fun imageBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        val host = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase() ?: return@withContext null
        if (host == "xjtu.edu.cn" || host.endsWith(".xjtu.edu.cn")) api.downloadBytes(url)
        else runCatching {
            externalImageClient.newCall(okhttp3.Request.Builder().url(url).get().build()).execute().use { r ->
                if (r.isSuccessful) r.body?.bytes() else null
            }
        }.getOrNull()
    }

    private val externalImageClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .callTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

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