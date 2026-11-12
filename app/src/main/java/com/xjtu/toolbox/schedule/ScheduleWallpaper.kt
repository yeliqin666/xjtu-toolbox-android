package com.xjtu.toolbox.schedule

import android.app.WallpaperManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.xjtu.toolbox.schedule.CourseItem
import kotlin.math.abs

/**
 * 一周课表画成一张全屏图，设为锁屏或桌面壁纸。
 * 按本机屏幕分辨率出图；主体避开顶部约 36%（锁屏时钟、通知）和底部约 18%（指纹区、快捷入口）。
 */
internal object ScheduleWallpaper {
    private val PALETTE = intArrayOf(
        0xFF5B8DEF.toInt(), 0xFF4CAF8E.toInt(), 0xFFE8914F.toInt(), 0xFFB275D9.toInt(),
        0xFFE0607E.toInt(), 0xFF3FA7C6.toInt(), 0xFFC4A23A.toInt(), 0xFF7C8CA0.toInt(),
    )
    private val DAY_NAMES = listOf("一", "二", "三", "四", "五", "六", "日")

    fun apply(context: Context, courses: List<CourseItem>, week: Int, lock: Boolean): Boolean {
        val metrics = context.resources.displayMetrics
        val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val bitmap = render(courses, week, metrics.widthPixels, metrics.heightPixels, night)
        return try {
            val flag = if (lock) WallpaperManager.FLAG_LOCK else WallpaperManager.FLAG_SYSTEM
            WallpaperManager.getInstance(context).setBitmap(bitmap, null, true, flag)
            true
        } catch (_: Exception) {
            false
        } finally {
            bitmap.recycle()
        }
    }

    fun render(courses: List<CourseItem>, week: Int, width: Int, height: Int, dark: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bg = if (dark) 0xFF111318.toInt() else 0xFFF6F7F9.toInt()
        val ink = if (dark) 0xFFE6E8EE.toInt() else 0xFF1C1F26.toInt()
        val sub = if (dark) 0xFF8A90A0.toInt() else 0xFF7A8090.toInt()
        canvas.drawColor(bg)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val weekCourses = courses.filter { it.isInWeek(week) && it.dayOfWeek in 1..7 }
        val sections = (weekCourses.maxOfOrNull { it.endSection } ?: 0).coerceIn(8, 12)

        val left = width * 0.06f
        val right = width * 0.94f
        val top = height * 0.36f
        val bottom = height * 0.82f
        val headerH = height * 0.028f
        val colW = (right - left) / 7f
        val rowH = (bottom - top - headerH) / sections

        paint.textAlign = Paint.Align.CENTER
        paint.textSize = width * 0.026f
        paint.color = sub
        DAY_NAMES.forEachIndexed { i, name ->
            canvas.drawText(name, left + colW * (i + 0.5f), top + headerH * 0.8f, paint)
        }

        val pad = colW * 0.05f
        val radius = colW * 0.12f
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = colW * 0.17f
        for (course in weekCourses) {
            val start = course.startSection.coerceIn(1, sections)
            val end = course.endSection.coerceIn(start, sections)
            val x0 = left + colW * (course.dayOfWeek - 1) + pad
            val x1 = left + colW * course.dayOfWeek - pad
            val y0 = top + headerH + rowH * (start - 1) + pad
            val y1 = top + headerH + rowH * end - pad
            paint.style = Paint.Style.FILL
            paint.color = PALETTE[abs(course.courseName.hashCode()) % PALETTE.size]
            canvas.drawRoundRect(RectF(x0, y0, x1, y1), radius, radius, paint)
            paint.color = 0xFFFFFFFF.toInt()
            val inset = colW * 0.08f
            val maxW = x1 - x0 - inset * 2
            val nameLine = paint.breakText(course.courseName, true, maxW, null).let { course.courseName.take(it) }
            canvas.drawText(nameLine, x0 + inset, y0 + paint.textSize * 1.4f, paint)
            if (course.location.isNotBlank() && y1 - y0 > paint.textSize * 3.2f) {
                val place = course.location.take(paint.breakText(course.location, true, maxW, null))
                canvas.drawText(place, x0 + inset, y0 + paint.textSize * 2.7f, paint)
            }
        }

        paint.textAlign = Paint.Align.CENTER
        paint.textSize = width * 0.03f
        paint.color = ink
        canvas.drawText("第 $week 周 · 课表", width / 2f, height * 0.86f, paint)
        return bitmap
    }
}
