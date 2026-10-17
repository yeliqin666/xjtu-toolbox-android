package com.xjtu.toolbox.desktop.lms

import com.xjtu.toolbox.lms.LmsDownloadResult
import com.xjtu.toolbox.lms.LmsDownloadSink
import com.xjtu.toolbox.lms.LmsReplayVideo
import java.io.File
import java.io.OutputStream

/** 思源学堂课件/作业附件在桌面端的落盘 —— `:data` 的 [LmsDownloadSink] 在桌面侧的填法。 */
object DesktopLmsSaver : LmsDownloadSink {

    override fun publicPathLabel(): String = "~/Downloads/"

    override val supportsVideoQueue: Boolean = false

    override suspend fun save(
        name: String,
        mimeType: String,
        folder: String,
        write: suspend (OutputStream) -> LmsDownloadResult,
    ): LmsDownloadResult = try {
        val dir = dirFor(folder)
        if (!dir.exists() && !dir.mkdirs()) error("下载目录不存在也建不出来：$dir")
        // 服务端给的文件名里可能有 `/`（理论上），只取最后一段，别把文件写到别处去
        val fileName = name.substringAfterLast('/').ifBlank { "file" }
        val target = File(dir, fileName)
        val result = target.outputStream().use { write(it) }
        if (result == LmsDownloadResult.Ok) {
            LmsDownloadResult.Ok
        } else {
            // 与 Android 的 `resolver.delete(uri)` 同一句语义：没落成的东西不留半成品
            runCatching { target.delete() }
            result
        }
    } catch (e: Exception) {
        LmsDownloadResult.Failed
    }

    override suspend fun queueReplayVideos(
        courseName: String,
        activityTitle: String,
        activityId: Int,
        videos: List<LmsReplayVideo>,
    ): Int = 0

    private fun dirFor(folder: String): File {
        val segments = folder.split('/').filter { it.isNotBlank() && it != "." && it != ".." }
        return File(saveDirectory, segments.joinToString(File.separator))
    }

    /** 用户下载目录：`$HOME/Downloads`（与系统约定的默认值一致，不额外查 xdg 配置）。 */
    private val saveDirectory: File
        get() = File(System.getProperty("user.home") ?: ".", "Downloads")
}