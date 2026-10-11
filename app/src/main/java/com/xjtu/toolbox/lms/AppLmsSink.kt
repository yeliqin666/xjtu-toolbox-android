package com.xjtu.toolbox.lms

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.OutputStream

/**
 * 思源学堂**落盘/回放队列在 Android 侧的填法** —— `:data` 的 [LmsDownloadSink] 在 `:app` 的实现。
 *
 * 方法体是搬屏时从 `:app/lms/LmsScreen.kt` 的 `saveToDownloads` 和
 * `:app/lms/LmsBatchDownload.kt` 的 `save` **原样搬出来的**（MediaStore 写公共
 * 「下载/XJTUToolBox/」、登记进「我的 · 下载管理」、`IS_PENDING` 两段、失败删掉半成品，
 * 三处逐字一致）；回放排队就是原来 `media.DownloadManager.enqueueDownloads` 那一句，
 * 机位命名（`instructor` / `encoder`）照旧按视频标签判。
 *
 * 这样 `:core` 的屏编到 wasm/JVM 都没有 `MediaStore` 的影子，而 Android 侧的行为一行未变。
 * 桌面端另有一份（写用户的下载目录，见 `:desktop` 的 `DesktopLmsSaver`）。
 */
class AppLmsSink(context: Context) : LmsDownloadSink {

    private val appContext = context.applicationContext

    override fun publicPathLabel(): String = LmsDownloadStore.publicDisplayPath()

    override val supportsVideoQueue: Boolean = true

    override suspend fun save(
        name: String,
        mimeType: String,
        folder: String,
        write: suspend (OutputStream) -> LmsDownloadResult,
    ): LmsDownloadResult {
        // 与搬迁前同一条 mime 口径：带 `/` 的原类型直接用（单文件那条路的 `ifBlank` 兜底），
        // 没有（批量下载的批阅附件与未知类型）按扩展名猜 —— 那是 `LmsBatchDownload.guessMime`
        // 的原样；单文件路径上游类型为空属罕见，猜出的类型只会比 `application/octet-stream` 更准。
        val mime = mimeType.takeIf { '/' in it }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        val relative = if (folder.isBlank()) LmsDownloadStore.RELATIVE_PATH
        else "${LmsDownloadStore.RELATIVE_PATH}/$folder"
        val cv = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, relative)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = appContext.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
            ?: return LmsDownloadResult.Failed
        return try {
            val result = resolver.openOutputStream(uri)?.use { out -> write(out) }
                ?: LmsDownloadResult.Failed
            if (result == LmsDownloadResult.Ok) {
                cv.clear()
                cv.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, cv, null, null)
                LmsDownloadStore.add(
                    appContext,
                    LmsDownloadRecord(
                        name = name,
                        mimeType = mime,
                        uri = uri.toString(),
                        savedAt = System.currentTimeMillis(),
                        category = LmsDownloadStore.CATEGORY_LMS,
                    ),
                )
            } else {
                resolver.delete(uri, null, null)
            }
            result
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            LmsDownloadResult.Failed
        }
    }

    override suspend fun queueReplayVideos(
        courseName: String,
        activityTitle: String,
        activityId: Int,
        videos: List<LmsReplayVideo>,
    ): Int {
        val tasks = com.xjtu.toolbox.media.AppDownloadManager.getInstance(appContext)
            .enqueueDownloads(
                courseName = courseName,
                activityTitle = activityTitle,
                activityId = activityId,
                videos = videos.map {
                    com.xjtu.toolbox.media.DownloadItem(
                        cameraType = if (it.label.contains("instructor", true)) "instructor" else "encoder",
                        url = it.downloadUrl,
                    )
                },
            )
        return tasks.size
    }
}