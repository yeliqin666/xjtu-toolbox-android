package com.xjtu.toolbox.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** 下载管理那一屏在 Android 侧的装配 —— `:core` 的 [DownloadManagerScreen] 由这里填上宿主能力。 */
@Composable
fun AppDownloadManagerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val manager = remember(context) { AppDownloadManager.getInstance(context) }
    val files = remember(context) { AppDownloadedFilesHost(context) }
    val dirInfo = remember { appDownloadDirInfo() }

    DownloadManagerScreen(
        host = manager,
        onBack = onBack,
        dirInfo = dirInfo,
        files = files,
        onPlayTask = { openReplayFile(context, it) },
    )
}
