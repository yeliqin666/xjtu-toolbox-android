package com.xjtu.toolbox.desktop.lms

import com.xjtu.toolbox.lms.LmsDownloadResult
import com.xjtu.toolbox.lms.LmsDownloadSink
import com.xjtu.toolbox.lms.LmsReplayVideo
import java.io.File
import java.io.OutputStream

/**
 * 思源学堂课件/作业附件在桌面端的落盘 —— `:data` 的 [LmsDownloadSink] 在桌面侧的填法。
 *
 * 与 Android 那份（`:app` 的 `AppLmsSink`，MediaStore + 「我的 · 下载管理」）形状相同、
 * 落脚点不同：桌面**没有** MediaStore，也没有「我的 · 下载管理」那一屏 ⇒ 写用户的下载目录
 *（`$HOME/Downloads/<课程名>/…`，按活动分文件夹与 Android 同结构；Linux 上
 * `xdg-user-dir DOWNLOAD` 的默认值就是它）。
 *
 * 「课堂回放那份队列」（能暂停、断点续传、看进度；Android 的回放动辄几百 MB，都交给它）
 * 在桌面上没有等价物 ⇒ [supportsVideoQueue] = false：批量下载对话框不露出「课堂回放」那一档
 *（见 `:core` 的 `LmsActivityList`），活动页的单条回放点下载会说一句「本端不支持排队下载」——
 * 如实，不假装。
 *
 * 落盘失败（目录不可写、磁盘满）返回 [LmsDownloadResult.Failed]，由屏上的那句
 * 「下载失败」照常接住 —— 与 Android 同一个出口，不额外打自己的提示。
 */
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