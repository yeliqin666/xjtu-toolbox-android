package com.xjtu.toolbox.desktop.media

import com.xjtu.toolbox.desktop.lms.DesktopLmsSaver
import com.xjtu.toolbox.media.DownloadDirInfo
import com.xjtu.toolbox.media.DownloadManager
import com.xjtu.toolbox.media.JvmDownloadEngine
import com.xjtu.toolbox.media.JvmDownloadFileSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** 桌面端把 `:core` 的 [DownloadManager] 装起来的那一处（与 `:app` 的 `AppDownloadManager` 对位）。 */
object DesktopDownloads {

    val manager: DownloadManager by lazy {
        DownloadManager(
            store = DesktopDownloadTaskStore(),
            engine = JvmDownloadEngine(),
            fileSystem = JvmDownloadFileSystem(),
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        )
    }

    /** 下载目录信息弹窗的桌面文案（与 `:app` 的 `appDownloadDirInfo()` 对位）。 */
    val dirInfo: DownloadDirInfo get() = DownloadDirInfo(
        filesLocation = DesktopLmsSaver.publicPathLabel(),
        notes = listOf(
            "成绩单、思源课件、资料站文件保存到 ~/Downloads 下（与系统约定的默认下载目录一致）",
            "课堂回放视频保存在 ~/Downloads/ClassReplay（目录名与 Android 那条一样），可以用任意播放器打开",
            "删除记录只删除这里的记录，不删除视频文件",
            "清理模式下可勾选\"同时删除文件\"彻底删除",
            "⚠️ 桌面端还没有文件类下载的登记表 ⇒ 这一页只列排队下载的回放；文件请到文件管理器里看 ~/Downloads",
        ),
    )
    /** 用系统默认程序打开一个已下载到本地的文件。 */
    fun openDownloadedFile(path: String): Boolean {
        if (path.isBlank() || !java.io.File(path).exists()) return false
        return openWithSystemProgram(path)
    }
}
