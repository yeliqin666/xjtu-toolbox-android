package com.xjtu.toolbox.media

import android.content.Context
import android.content.Intent
import android.util.Log
import android.webkit.MimeTypeMap
import com.xjtu.toolbox.lms.LmsDownloadStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "AppDownloads"

/**
 * 「文件类下载」那一半在 **Android 侧的填法** —— `:core` 的 [DownloadedFilesHost] 实现。
 *
 * 三句话就是搬迁前下载管理屏里那三处 Android 调用，方法体原样搬出：
 *  - 列表 = `LmsDownloadStore.getAll(context)`（SharedPreferences 登记 + MediaStore 里发现真实文件）；
 *  - 打开 = `Intent.ACTION_VIEW` + `FileProvider` 兜底（`openDownloadedFile`）；
 *  - 删除 = `contentResolver.delete(...)` + `LmsDownloadStore.remove(...)`（原来写在屏上）。
 *
 * 这一层之所以必须留在 `:app`：MediaStore、`Intent`、`ContentResolver` 都是 Android 专属，
 * `:core` 还要编到 wasm。
 */
class AppDownloadedFilesHost(context: Context) : DownloadedFilesHost {

    private val appContext = context.applicationContext

    override suspend fun list(): List<DownloadedFile> = withContext(Dispatchers.IO) {
        LmsDownloadStore.getAll(appContext)
    }

    override fun open(record: DownloadedFile) = openDownloadedFile(appContext, record)

    override suspend fun delete(record: DownloadedFile) {
        withContext(Dispatchers.IO) {
            runCatching {
                appContext.contentResolver.delete(android.net.Uri.parse(record.uri), null, null)
            }
            LmsDownloadStore.remove(appContext, record.uri)
        }
    }
}

/**
 * 下载目录信息弹窗里那两句位置与几条说明（**Android 的口径**）。
 *
 * 搬迁前它们写在屏上；第 2 条说的是「应用专属外部目录」的性质（卸载 App 一并清除），
 * 桌面端没有这条性质 ⇒ 这类文案跟着**实现**走、不跟着屏走，见 [DownloadDirInfo]。
 */
fun appDownloadDirInfo(): DownloadDirInfo = DownloadDirInfo(
    filesLocation = LmsDownloadStore.publicDisplayPath(),
    notes = listOf(
        "成绩单、思源课件、资料站文件统一保存到公共下载目录，系统文件管理器可见，卸载 App 不会丢",
        "课堂回放视频保存在应用专属目录（无需存储权限），支持外部播放器播放；卸载 App 会一并清除",
        "删除记录仅删除数据库记录，不删除视频文件",
        "清理模式下可勾选\"同时删除文件\"彻底删除",
    ),
)

/**
 * 打开一个**文件类下载**（搬迁前 `DownloadManagerScreen.openDownloadedFile`，逐字搬出）。
 */
private fun openDownloadedFile(context: Context, record: DownloadedFile) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(
                android.net.Uri.parse(record.uri),
                record.mimeType.ifBlank { "*/*" },
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "打开文件"))
    }.onFailure {
        android.widget.Toast.makeText(context, "文件已被移动或删除", android.widget.Toast.LENGTH_SHORT).show()
    }
}

/**
 * 播放一个**已下载完的回放文件**（搬迁前 `DownloadManagerScreen.openReplayFile`，逐字搬出）：
 * 先按文件 `Uri` 交给系统选播放器，失败再走 `FileProvider` 给一个能授权的 content Uri。
 */
fun openReplayFile(context: Context, task: DownloadTask) {
    try {
        val file = File(task.filePath)
        if (!file.exists()) {
            android.widget.Toast.makeText(context, "视频文件不存在", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val mime = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase())
            ?: "*/*"
        try {
            val uri = android.net.Uri.fromFile(file)
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, mime)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    "选择播放器",
                )
            )
        } catch (_: Exception) {
            try {
                val contentUri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
                context.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(contentUri, mime)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        },
                        "打开文件",
                    )
                )
            } catch (_: Exception) {
                android.widget.Toast.makeText(
                    context,
                    "无法启动播放器，请使用系统文件管理器查看",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Play video error", e)
        android.widget.Toast.makeText(
            context,
            com.xjtu.toolbox.error.FriendlyError.of(e, "播放视频"),
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }
}
