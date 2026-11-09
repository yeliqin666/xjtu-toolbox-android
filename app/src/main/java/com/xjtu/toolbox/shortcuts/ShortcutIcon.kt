package com.xjtu.toolbox.shortcuts

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Icon
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath
import com.xjtu.toolbox.R

private const val ICON_PX = 432

// 角标落在自适应图标的安全区内（圆心距中心 + 半径 ≤ 边长的 0.306），圆形遮罩也裁不到
private const val BADGE_CENTER = 0.635f
private const val BADGE_RADIUS = 0.115f
private const val BADGE_RING = 0.012f
private const val BADGE_GLYPH = 1.25f
private const val BADGE_FILL = 0xFF2F6BFF.toInt()

/** 快捷方式图标：App 图标打底，右下角一枚该功能图标的角标。 */
internal fun shortcutIcon(context: Context, glyph: ImageVector): Icon {
    val bitmap = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    listOf(R.mipmap.ic_launcher_background, R.mipmap.ic_launcher_foreground).forEach { res ->
        context.getDrawable(res)?.apply {
            setBounds(0, 0, ICON_PX, ICON_PX)
            draw(canvas)
        }
    }
    val center = ICON_PX * BADGE_CENTER
    val radius = ICON_PX * BADGE_RADIUS
    val inner = radius - ICON_PX * BADGE_RING
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(center, center, radius, paint)
    paint.color = BADGE_FILL
    canvas.drawCircle(center, center, inner, paint)

    val side = inner * BADGE_GLYPH
    canvas.save()
    canvas.translate(center - side / 2f, center - side / 2f)
    canvas.scale(side / glyph.viewportWidth, side / glyph.viewportHeight)
    paint.color = android.graphics.Color.WHITE
    drawGroup(canvas, glyph.root, paint)
    canvas.restore()
    return Icon.createWithAdaptiveBitmap(bitmap)
}

/** Material 图标只有路径，不带组变换，这里直接按路径填充。 */
private fun drawGroup(canvas: Canvas, group: VectorGroup, paint: Paint) {
    for (node in group) {
        when (node) {
            is VectorGroup -> drawGroup(canvas, node, paint)
            is VectorPath -> {
                val path = node.pathData.toPath().asAndroidPath()
                path.fillType = if (node.pathFillType == PathFillType.EvenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
                canvas.drawPath(path, paint)
            }
        }
    }
}
