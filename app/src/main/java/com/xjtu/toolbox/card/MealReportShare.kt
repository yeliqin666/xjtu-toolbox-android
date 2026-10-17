package com.xjtu.toolbox.card

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.content.FileProvider
import com.xjtu.toolbox.platform.showBriefMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 「干饭人账单」海报在 Android 上的存图：写进 cache/exports，再弹系统分享。 */
fun appMealReportExport(context: Context): MealReportExport = MealReportExport("存图分享") { image ->
    val bitmap = image.asAndroidBitmap()
    val file = runCatching {
        withContext(Dispatchers.IO) {
            File(File(context.cacheDir, "exports").apply { mkdirs() }, "meal-report.png").also { f ->
                f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }.getOrNull()
    if (file == null) {
        showBriefMessage("图片没存下来，再试一次")
        return@MealReportExport
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "分享干饭人账单").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}