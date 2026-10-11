package com.xjtu.toolbox.desktop.media

import com.xjtu.toolbox.desktop.lms.DesktopLmsSaver
import com.xjtu.toolbox.media.DownloadDirInfo
import com.xjtu.toolbox.media.DownloadManager
import com.xjtu.toolbox.media.JvmDownloadEngine
import com.xjtu.toolbox.media.JvmDownloadFileSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 桌面端把 `:core` 的 [DownloadManager] 装起来的那一处（与 `:app` 的 `AppDownloadManager` 对位）。
 *
 * 三个端口都在这儿填：
 *  - [DesktopDownloadTaskStore] —— 一份 0600 的 JSON 落盘（`:core` 的 `DownloadTaskStore`）；
 *  - [JvmDownloadEngine]（`:data` 的 jvm 侧）—— okhttp 真下载，`Range` 续传 / 416 / 进度 / 取消；
 *  - [JvmDownloadFileSystem]（`:data` 的 jvm 侧）—— `~/Downloads/ClassReplay`。
 *
 * 作用域与 Android 那条同型（`Dispatchers.IO + SupervisorJob()`）：搬运与落盘都不该占 UI 线程。
 */
object DesktopDownloads {

    val manager: DownloadManager by lazy {
        DownloadManager(
            store = DesktopDownloadTaskStore(),
            engine = JvmDownloadEngine(),
            fileSystem = JvmDownloadFileSystem(),
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        )
    }

    /**
     * 下载目录信息弹窗的**桌面文案**（与 `:app` 的 `appDownloadDirInfo()` 对位）。
     *
     * 「文件」那一句直接取 [DesktopLmsSaver.publicPathLabel] —— 同一个「下载到哪儿」的口径只写一次，
     * 免得屏上的说明与真实落脚点各说一套。
     */
    val dirInfo: DownloadDirInfo get() = DownloadDirInfo(
        filesLocation = DesktopLmsSaver.publicPathLabel(),
        notes = listOf(
            "成绩单、思源课件、资料站文件保存到 ~/Downloads 下（与系统约定的默认下载目录一致）",
            "课堂回放视频保存在 ~/Downloads/ClassReplay（目录名与 Android 那条一样），可以用任意播放器打开",
            "删除记录只删除这里的记录，不删除视频文件",
            "清理模式下可勾选\"同时删除文件\"彻底删除",
            // 如实交代一处缺口：桌面上还没有「文件类下载」的登记表（见 `:core` 的 DownloadedFilesHost）
            "⚠️ 桌面端还没有文件类下载的登记表 ⇒ 这一页只列排队下载的回放；文件请到文件管理器里看 ~/Downloads",
        ),
    )
    /**
     * 用系统默认程序打开一个**已下载到本地**的文件 —— 桌面上的「播放」就是这一件事
     * （没有内置播放器，见 `DesktopVideoPlayerHost` 的 KDoc）。返回「有没有把这件事交出去」。
     */
    fun openDownloadedFile(path: String): Boolean {
        if (path.isBlank() || !java.io.File(path).exists()) return false
        return openWithSystemProgram(path)
    }
}
