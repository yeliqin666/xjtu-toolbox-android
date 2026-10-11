package com.xjtu.toolbox.dzpz

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import android.widget.Toast
import com.xjtu.toolbox.error.FriendlyError
import com.xjtu.toolbox.lms.LmsDownloadRecord
import com.xjtu.toolbox.lms.LmsDownloadStore

/**
 * 成绩单 PDF 的**落盘实现**（本端宿主能力）—— `:core` 的 [TranscriptSaveSink] 在 Android 侧的填法。
 *
 * 方法体是搬屏时从 `:app/dzpz/TranscriptScreen.kt` 的 `savePdfToDownloads(context, …)` **原样搬出来**的
 * （MediaStore 写进 `Download/岱宗盒子/`、记进「我的 · 下载管理」、Toast 文案三处逐字一致），
 * 三句界面文案（按钮字、「将保存到 X」的位置、按钮下那句说明）也一起搬到这里 —— 它们说的都是
 * 本端存到哪儿、怎么打开，不属于共享屏。
 *
 * 这样 `:core` 的屏编到 wasm/JVM 都没有 `MediaStore` 的影子，而 Android 侧的行为一行未变。
 * 桌面端另有一份（写用户的下载目录，见 `:desktop` 的 `DesktopTranscriptSaver`）。
 */
class AppTranscriptSaver(context: Context) : TranscriptSaveSink {

    private val appContext = context.applicationContext

    override val actionLabel: String = "保存到下载"

    override val hint: String = "保存在「我的 · 下载管理」，可用文件管理器或 PDF 阅读器打开。"

    override val locationLabel: String get() = LmsDownloadStore.publicDisplayPath()

    override fun save(filename: String, bytes: ByteArray) {
        try {
            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(MediaStore.Downloads.RELATIVE_PATH, LmsDownloadStore.RELATIVE_PATH)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = appContext.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                contentValues.clear()
                contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
                LmsDownloadStore.add(
                    appContext,
                    LmsDownloadRecord(
                        name = filename,
                        mimeType = "application/pdf",
                        uri = uri.toString(),
                        savedAt = System.currentTimeMillis(),
                        category = LmsDownloadStore.CATEGORY_TRANSCRIPT
                    )
                )
                Toast.makeText(appContext, "已保存到下载管理", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(appContext, "保存失败", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(appContext, FriendlyError.of(e, "保存"), Toast.LENGTH_SHORT).show()
        }
    }
}
