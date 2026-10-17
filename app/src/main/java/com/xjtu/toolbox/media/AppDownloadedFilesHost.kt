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

/** 「文件类下载」那一半在 Android 侧的填法 —— `:core` 的 [DownloadedFilesHost] 实现。 */
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

/** 下载目录信息弹窗里的位置与说明（Android）。 */
fun appDownloadDirInfo(): DownloadDirInfo = DownloadDirInfo(
    filesLocation = LmsDownloadStore.publicDisplayPath(),
    notes = listOf(
        "成绩单、思源课件、资料站文件统一保存到公共下载目录，系统文件管理器可见，卸载 App 不会丢",
        "课堂回放视频保存在应用专属目录（无需存储权限），支持外部播放器播放；卸载 App 会一并清除",
        "删除记录仅删除数据库记录，不删除视频文件",
        "清理模式下可勾选\"同时删除文件\"彻底删除",
    ),
)

/** 打开一个文件类下载。 */
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

/** 播放一个已下载完的回放文件。 */
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
