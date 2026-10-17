package com.xjtu.toolbox.desktop

import androidx.compose.ui.graphics.asSkiaBitmap
import com.xjtu.toolbox.card.MealReportExport
import com.xjtu.toolbox.platform.showBriefMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/** 「干饭人账单」海报在桌面上的存图：弹保存对话框，写成 PNG。 */
val DesktopMealReportExport = MealReportExport("保存图片") { image ->
    val dialog = FileDialog(null as Frame?, "保存干饭人账单", FileDialog.SAVE).apply { file = "meal-report.png" }
    dialog.isVisible = true
    val name = dialog.file ?: return@MealReportExport
    val target = File(dialog.directory, if (name.endsWith(".png", ignoreCase = true)) name else "$name.png")
    val ok = runCatching {
        val png = Image.makeFromBitmap(image.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)?.bytes
            ?: error("PNG 编码失败")
        withContext(Dispatchers.IO) { target.writeBytes(png) }
    }.isSuccess
    showBriefMessage(if (ok) "已保存到 ${target.path}" else "图片没存下来，再试一次")
}