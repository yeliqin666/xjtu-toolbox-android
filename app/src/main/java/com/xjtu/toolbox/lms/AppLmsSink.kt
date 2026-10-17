package com.xjtu.toolbox.lms

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.OutputStream

/** 思源学堂落盘/回放队列在 Android 侧的填法 —— `:data` 的 [LmsDownloadSink] 在 `:app` 的实现。 */
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
        // 带 `/` 的原类型直接用，否则按扩展名猜。
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