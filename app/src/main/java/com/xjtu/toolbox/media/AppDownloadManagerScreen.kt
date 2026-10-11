package com.xjtu.toolbox.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * 下载管理那一屏在 **Android 侧的装配** —— `:core` 的 [DownloadManagerScreen] 由这里填上宿主能力。
 *
 * 搬迁前「填宿主能力」是屏自己做的（它直接 `DownloadManager.getInstance(context)`、
 * 直接读 `LmsDownloadStore`、直接发 `Intent`）；现在这些留在这里，屏只认 `:core` 的三个缝：
 * [DownloadManager]（队列 / 状态机）、[DownloadedFilesHost]（文件类下载那半）、
 * [DownloadDirInfo]（目录说明文案）。这一步与 `AppLmsSink` / `AppTranscriptSaver` 是同一个口径：
 * **屏往共享内核搬，Android 那几句调用留在装配点**。
 */
@Composable
fun AppDownloadManagerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val manager = remember(context) { AppDownloadManager.getInstance(context) }
    val files = remember(context) { AppDownloadedFilesHost(context) }
    val dirInfo = remember { appDownloadDirInfo() }

    DownloadManagerScreen(
        manager = manager,
        onBack = onBack,
        dirInfo = dirInfo,
        files = files,
        onPlayTask = { openReplayFile(context, it) },
    )
}
