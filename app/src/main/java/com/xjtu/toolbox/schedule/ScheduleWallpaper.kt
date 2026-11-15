package com.xjtu.toolbox.schedule

import android.app.WallpaperManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import kotlin.math.min

/** 壁纸底色。 */
internal enum class WallpaperTheme(val label: String) { LIGHT("浅色"), DARK("深色"), ACCENT("主题色") }

/**
 * @param top 课表卡片上沿在屏幕高度上的位置（0–1），默认让开锁屏时钟。
 * @param scale 卡片缩放，1 = 默认大小。
 */
internal data class WallpaperOptions(
    val theme: WallpaperTheme = WallpaperTheme.LIGHT,
    val weekdaysOnly: Boolean = false,
    val daytimeOnly: Boolean = false,
    val top: Float = 0.3f,
    val scale: Float = 1f,
)

/** 一周课表画成整屏壁纸：渐变底上一张圆角卡片，左边节次与钟点，课程色与课表页一致。 */
internal object ScheduleWallpaper {
    private val DAY_NAMES = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    private const val DAYTIME_SECTIONS = 8

    fun render(
        courses: List<CourseItem>,
        week: Int,
        width: Int,
        height: Int,
        options: WallpaperOptions,
        accent: Int,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        draw(Canvas(bitmap), courses, week, width, height, options, accent)
        return bitmap
    }

    /** 预览直接画在界面画布上，出图画在位图上，两边同一份代码。 */
    fun draw(
        canvas: Canvas,
        courses: List<CourseItem>,
        week: Int,
        width: Int,
        height: Int,
        o: WallpaperOptions,
        accent: Int,
    ) {
        val u = width / 400f
        val s = o.scale
        val dark = o.theme != WallpaperTheme.LIGHT
        val ink = if (dark) 0xFFF2F4F8.toInt() else 0xFF1C1F26.toInt()
        val sub = ColorUtils.setAlphaComponent(ink, if (dark) 150 else 130)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
        val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

        // 底色：浅色、深色是两档灰阶渐变；主题色把主题色压暗做渐变，字一律白色
        val (top, bottom) = when (o.theme) {
            WallpaperTheme.LIGHT -> 0xFFE9EEF6.toInt() to 0xFFF7F8FA.toInt()
            WallpaperTheme.DARK -> 0xFF0F1218.toInt() to 0xFF1A1F2A.toInt()
            WallpaperTheme.ACCENT -> ColorUtils.blendARGB(accent, 0xFF000000.toInt(), 0.35f) to
                ColorUtils.blendARGB(accent, 0xFF000000.toInt(), 0.72f)
        }
        paint.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null

        val days = if (o.weekdaysOnly) 5 else 7
        // 多个来源合并后同一节课会有重复条目，按课名和时段去重
        val weekCourses = courses.filter { it.isInWeek(week) && it.dayOfWeek in 1..days }
            .distinctBy { listOf(it.courseName, it.dayOfWeek, it.startSection, it.endSection) }
        val lastSection = if (o.daytimeOnly) DAYTIME_SECTIONS
        else maxOf(DAYTIME_SECTIONS, weekCourses.maxOfOrNull { it.endSection } ?: 0).coerceAtMost(XjtuTime.getAllTimes().size)
        val shown = weekCourses.filter { it.startSection in 1..lastSection }
        val times = XjtuTime.getAllTimes().toMap()
        val colors = courseColorMap(courses.map { it.courseName })

        // 卡片尺寸：宽度随缩放；默认高度控制在屏高 52% 内，正好落在时钟之下、指纹区之上
        val pad = 14 * u * s
        val cardW = min((width - 32 * u) * s, width - 12 * u)
        val headerH = 40 * u * s
        val dayH = 24 * u * s
        val maxRowH = (height * 0.52f * s - headerH - dayH - pad * 2) / lastSection
        val rowH = min(46 * u * s, maxRowH)
        val cardH = pad * 2 + headerH + dayH + rowH * lastSection
        val left = (width - cardW) / 2
        val cardTop = (o.top * height).coerceIn(12 * u, (height - cardH - 12 * u).coerceAtLeast(12 * u))
        val card = RectF(left, cardTop, left + cardW, cardTop + cardH)
        val radius = 24 * u * s

        paint.color = when (o.theme) {
            WallpaperTheme.LIGHT -> 0xE6FFFFFF.toInt()
            WallpaperTheme.DARK -> 0x14FFFFFF
            WallpaperTheme.ACCENT -> 0x1FFFFFFF
        }
        if (o.theme == WallpaperTheme.LIGHT) paint.setShadowLayer(18 * u, 0f, 6 * u, 0x18000000)
        canvas.drawRoundRect(card, radius, radius, paint)
        paint.clearShadowLayer()

        // 标题行
        text.typeface = bold
        text.color = ink
        text.textSize = 18 * u * s
        val titleBase = card.top + pad + headerH * 0.62f
        canvas.drawText("课表", card.left + pad, titleBase, text)
        text.typeface = Typeface.DEFAULT
        text.textSize = 12 * u * s
        text.color = sub
        text.textAlign = Paint.Align.RIGHT
        canvas.drawText("第 $week 周 · ${shown.size} 节课", card.right - pad, titleBase, text)
        text.textAlign = Paint.Align.LEFT

        val timeW = 34 * u * s
        val gridLeft = card.left + pad + timeW
        val gridTop = card.top + pad + headerH + dayH
        val colW = (card.right - pad - gridLeft) / days

        // 星期
        text.textSize = 11 * u * s
        text.textAlign = Paint.Align.CENTER
        for (d in 0 until days) {
            canvas.drawText(DAY_NAMES[d], gridLeft + colW * (d + 0.5f), gridTop - dayH * 0.35f, text)
        }

        // 节次与开始钟点；中午、傍晚两处断开画一道稍重的线
        paint.strokeWidth = u
        for (sec in 1..lastSection) {
            val y = gridTop + rowH * (sec - 1)
            text.typeface = bold
            text.color = ink
            text.textSize = 11 * u * s
            canvas.drawText("$sec", card.left + pad + timeW / 2, y + rowH * 0.45f, text)
            times[sec]?.let { t ->
                text.typeface = Typeface.DEFAULT
                text.color = sub
                text.textSize = 8.5f * u * s
                canvas.drawText("%d:%02d".format(t.start.hour, t.start.minute), card.left + pad + timeW / 2, y + rowH * 0.45f + 11 * u * s, text)
            }
            if (sec > 1) {
                paint.color = ColorUtils.setAlphaComponent(ink, if (sec == 5 || sec == 9) 40 else 16)
                canvas.drawLine(gridLeft, y, card.right - pad, y, paint)
            }
        }
        text.textAlign = Paint.Align.LEFT

        // 课程块：浅色底是课程色的淡底加左侧色条、字用课程色；深色、主题色底上整块加深、字用白色
        val gap = 1.5f * u * s
        val blockR = 8 * u * s
        val stripe = 3 * u * s
        for ((c, lane, lanes) in lanesOf(shown, lastSection)) {
            val color = colors.colorOf(c.courseName).toArgb()
            val end = c.endSection.coerceIn(c.startSection, lastSection)
            val laneW = colW / lanes
            val x0 = gridLeft + colW * (c.dayOfWeek - 1) + laneW * lane
            val r = RectF(
                x0 + gap,
                gridTop + rowH * (c.startSection - 1) + gap,
                x0 + laneW - gap,
                gridTop + rowH * end - gap,
            )
            paint.color = when (o.theme) {
                WallpaperTheme.LIGHT -> ColorUtils.setAlphaComponent(color, 36)
                WallpaperTheme.DARK -> ColorUtils.setAlphaComponent(color, 150)
                WallpaperTheme.ACCENT -> ColorUtils.setAlphaComponent(ColorUtils.blendARGB(color, 0xFFFFFFFF.toInt(), 0.15f), 170)
            }
            canvas.drawRoundRect(r, blockR, blockR, paint)
            if (o.theme == WallpaperTheme.LIGHT) {
                paint.color = color
                canvas.save()
                canvas.clipRect(r.left, r.top, r.left + stripe, r.bottom)
                canvas.drawRoundRect(r, blockR, blockR, paint)
                canvas.restore()
            }

            val inset = 4 * u * s
            val textW = (r.width() - inset * 2 - if (o.theme == WallpaperTheme.LIGHT) stripe else 0f).toInt()
            if (textW <= 0) continue
            val textLeft = r.left + inset + if (o.theme == WallpaperTheme.LIGHT) stripe else 0f
            val nameColor = if (o.theme == WallpaperTheme.LIGHT) ColorUtils.blendARGB(color, 0xFF000000.toInt(), 0.15f) else 0xFFFFFFFF.toInt()
            val placeColor = if (o.theme == WallpaperTheme.LIGHT) sub else 0xCCFFFFFF.toInt()

            text.typeface = bold
            // 并排的窄块字号小一档
            val narrow = if (lanes > 1) 0.85f else 1f
            text.textSize = 10.5f * u * s * narrow
            text.color = nameColor
            val availH = r.height() - inset * 2
            val placeSize = 9 * u * s * narrow
            val placeLines = if (c.location.isNotBlank() && availH > text.textSize * 3.6f) 2 else 0
            val nameLines = ((availH - placeLines * placeSize * 1.15f) / (text.textSize * 1.18f)).toInt().coerceIn(1, 4)
            val name = layout(c.courseName, text, textW, nameLines)
            canvas.save()
            canvas.translate(textLeft, r.top + inset)
            name.draw(canvas)
            if (placeLines > 0) {
                text.typeface = Typeface.DEFAULT
                text.textSize = placeSize
                text.color = placeColor
                val roomH = availH - name.height
                val lines = (roomH / (placeSize * 1.15f)).toInt().coerceIn(0, placeLines)
                if (lines > 0) {
                    canvas.translate(0f, name.height + 2 * u * s)
                    layout(c.location, text, textW, lines).draw(canvas)
                }
            }
            canvas.restore()
        }
    }

    /**
     * 同一天时间重叠的课并排画：按重叠的传递闭包分组，组内贪心分道，每道等宽。
     * 返回（课、第几道、这一组共几道）。
     */
    private fun lanesOf(courses: List<CourseItem>, lastSection: Int): List<Triple<CourseItem, Int, Int>> {
        val out = mutableListOf<Triple<CourseItem, Int, Int>>()
        courses.groupBy { it.dayOfWeek }.values.forEach { day ->
            val sorted = day.sortedWith(compareBy({ it.startSection }, { -it.endSection }))
            var group = mutableListOf<CourseItem>()
            var groupEnd = 0
            fun flush() {
                if (group.isEmpty()) return
                val laneEnds = mutableListOf<Int>()
                val placed = group.map { c ->
                    val i = laneEnds.indexOfFirst { it < c.startSection }
                    val lane = if (i >= 0) i else laneEnds.size.also { laneEnds += 0 }
                    laneEnds[lane] = c.endSection.coerceAtMost(lastSection)
                    c to lane
                }
                placed.forEach { (c, lane) -> out += Triple(c, lane, laneEnds.size) }
                group = mutableListOf()
                groupEnd = 0
            }
            for (c in sorted) {
                if (c.startSection > groupEnd) flush()
                group += c
                groupEnd = maxOf(groupEnd, c.endSection)
            }
            flush()
        }
        return out
    }

    private fun layout(value: String, paint: TextPaint, width: Int, maxLines: Int): StaticLayout =
        StaticLayout.Builder.obtain(value, 0, value.length, TextPaint(paint), width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.05f)
            .setIncludePad(false)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

    /** 设成锁屏（[lock]）或桌面壁纸。 */
    fun apply(context: Context, bitmap: Bitmap, lock: Boolean): Boolean = runCatching {
        val flag = if (lock) WallpaperManager.FLAG_LOCK else WallpaperManager.FLAG_SYSTEM
        WallpaperManager.getInstance(context).setBitmap(bitmap, null, true, flag)
    }.isSuccess

    /** 存进相册「Pictures/XJTU Toolbox」。 */
    fun save(context: Context, bitmap: Bitmap): Boolean = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "课表壁纸_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/XJTU Toolbox")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("insert failed")
        resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: error("open failed")
    }.isSuccess
}
