package com.xjtu.toolbox.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.xjtu.toolbox.error.ErrorReporting
import java.io.File

/**
 * 通用分享工具：写文件到 cache/exports/，触发 ACTION_SEND chooser。
 *
 * 复用 [com.xjtu.toolbox.schedule.ScheduleExport] 走过的路径
 * （FileProvider authorities = `${applicationId}.fileprovider`），不再加新权限。
 */
object ShareUtils {

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
            ErrorReporting.report(
                "ShareUtils.shareFile", e,
                mapOf("fileName" to file.name, "mime" to mime)
            )
            Toast.makeText(context, "分享失败：${e.message}", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            clipData = ClipData.newRawUri(title, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(intent, title).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(chooser)
        } catch (e: Exception) {
            ErrorReporting.report(
                "ShareUtils.shareFile", e,
                mapOf("fileName" to file.name, "mime" to mime)
            )
            Toast.makeText(context, "没有可用的分享应用", Toast.LENGTH_SHORT).show()
        }
    }
}