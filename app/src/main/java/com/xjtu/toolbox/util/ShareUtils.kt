package com.xjtu.toolbox.util

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * 通用分享工具：写文件到 cache/exports/，触发 ACTION_SEND chooser。
 *
 * 复用 [com.xjtu.toolbox.schedule.ScheduleExport] 走过的路径
 * （FileProvider authorities = `${applicationId}.fileprovider`），不再加新权限。
 */
object ShareUtils {

    /** 暴露目录：cache/exports/。FileProvider xml 里已声明。 */
    private const val EXPORT_DIR = "exports"

    /**
     * 分享文本（非文件）。常用于 Agent 回复复制 + 通知文本。
     * 会先把内容写入临时 .txt 再走 ACTION_SEND——这样系统的"分享到"列表里
     * 能看到"保存到文件"等选项，体验比纯文本 chooser 丰富。
     */
    fun shareText(
        context: Context,
        title: String,
        text: String,
        fileBaseName: String = "share.txt",
        mime: String = "text/plain",
    ) {
        val file = writeToCache(context, fileBaseName, text)
        shareFile(context, title, file, mime)
    }

    /**
     * 分享文件。
     */
    fun shareFile(
        context: Context,
        title: String,
        file: File,
        mime: String,
    ) {
        val uri = try {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
        } catch (e: Exception) {
            Toast.makeText(context, "分享失败：${e.message}", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(intent, title).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(context, "没有可用的分享应用", Toast.LENGTH_SHORT).show()
        }
    }

    /** 写文件到 cache/exports/，自动 mkdirs。返回写入后的 File。 */
    fun writeToCache(context: Context, fileName: String, content: String): File {
        val dir = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
        val file = File(dir, fileName)
        file.writeText(content, Charsets.UTF_8)
        return file
    }
}