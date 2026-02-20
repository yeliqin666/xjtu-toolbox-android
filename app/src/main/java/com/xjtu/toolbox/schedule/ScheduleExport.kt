package com.xjtu.toolbox.schedule

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import com.xjtu.toolbox.util.XjtuTime

private const val TAG = "ScheduleExport"

object ScheduleExport {

    // ════════════════════════════════════════════
    //  ICS 日历文件导出
    // ════════════════════════════════════════════

    /**
     * 生成 ICS 日历内容
     * @param courses 课程列表
     * @param startOfTerm 学期第一周的周一日期
     * @param termName 学期名称（用于日历名）
     */
    fun generateIcs(courses: List<CourseItem>, startOfTerm: LocalDate, termName: String): String {
        val sb = StringBuilder()
        sb.appendLine("BEGIN:VCALENDAR")
        sb.appendLine("VERSION:2.0")
        sb.appendLine("PRODID:-//XJTUToolBox//Schedule//CN")
        sb.appendLine("CALSCALE:GREGORIAN")
        sb.appendLine("X-WR-CALNAME:$termName 课表")
        sb.appendLine("X-WR-TIMEZONE:Asia/Shanghai")

        // 嵌入时区定义
        sb.appendLine("BEGIN:VTIMEZONE")
        sb.appendLine("TZID:Asia/Shanghai")
        sb.appendLine("BEGIN:STANDARD")
        sb.appendLine("DTSTART:19700101T000000")
        sb.appendLine("TZOFFSETFROM:+0800")
        sb.appendLine("TZOFFSETTO:+0800")
        sb.appendLine("END:STANDARD")
        sb.appendLine("END:VTIMEZONE")

        val dtFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

        for (course in courses) {
            val weeks = course.getWeeks()
            if (weeks.isEmpty()) continue

            for (week in weeks) {
                // 计算该周该天的具体日期
                val weekStartMonday = startOfTerm.plusWeeks((week - 1).toLong())
                val courseDate = weekStartMonday.plusDays((course.dayOfWeek - 1).toLong())

                val startTime = sectionToTime(course.startSection, isStart = true, courseDate)
                val endTime = sectionToTime(course.endSection, isStart = false, courseDate)

                val dtStart = courseDate.atTime(startTime.first, startTime.second)
                val dtEnd = courseDate.atTime(endTime.first, endTime.second)

                sb.appendLine("BEGIN:VEVENT")
                sb.appendLine("UID:${UUID.randomUUID()}@xjtu-toolbox")
                sb.appendLine("DTSTART;TZID=Asia/Shanghai:${dtFormat.format(dtStart)}")
                sb.appendLine("DTEND;TZID=Asia/Shanghai:${dtFormat.format(dtEnd)}")
                sb.appendLine("SUMMARY:${escapeIcs(course.courseName)}")
                sb.appendLine("LOCATION:${escapeIcs(course.location)}")
                val desc = buildString {
                    append("教师: ${course.teacher}")
                    if (course.courseType.isNotEmpty()) append("\\n类型: ${course.courseType}")
                    append("\\n节次: 第${course.startSection}-${course.endSection}节")
                    append("\\n周次: 第${week}周")
                }
                sb.appendLine("DESCRIPTION:$desc")
                sb.appendLine("END:VEVENT")
            }
        }

        sb.appendLine("END:VCALENDAR")
        return sb.toString()
    }

    /**
     * 节次 → 时间映射（复用 XjtuTime，自动区分冬/夏时间）
     * @param courseDate 上课日期（用于判断冬夏时间）
     */
    private fun sectionToTime(section: Int, isStart: Boolean, courseDate: LocalDate): Pair<Int, Int> {
        val isSummer = XjtuTime.isSummerTime(courseDate.monthValue)
        val ct = XjtuTime.getClassTime(section, isSummer)
        return if (ct != null) {
            val t = if (isStart) ct.start else ct.end
            t.hour to t.minute
        } else {
            if (isStart) 8 to 0 else 8 to 50  // fallback
        }
    }

    private fun escapeIcs(text: String): String =
        text.replace("\\", "\\\\").replace(",", "\\,").replace(";", "\\;").replace("\n", "\\n")

    // ════════════════════════════════════════════
    //  CSV 表格导出
    // ════════════════════════════════════════════

    fun generateCsv(courses: List<CourseItem>): String {
        val sb = StringBuilder()
        sb.appendLine("课程名称,教师,教室,星期,开始节次,结束节次,上课周次,课程类型,课程代码")
        for (c in courses.sortedWith(compareBy({ it.dayOfWeek }, { it.startSection }))) {
            val dayName = when (c.dayOfWeek) {
                1 -> "周一"; 2 -> "周二"; 3 -> "周三"; 4 -> "周四"
                5 -> "周五"; 6 -> "周六"; 7 -> "周日"; else -> "?"
            }
            val weeks = formatWeeksCompact(c.getWeeks())
            sb.appendLine("${csvEscape(c.courseName)},${csvEscape(c.teacher)},${csvEscape(c.location)},${csvEscape(dayName)},${c.startSection},${c.endSection},${csvEscape(weeks)},${csvEscape(c.courseType)},${csvEscape(c.courseCode)}")
        }
        return sb.toString()
    }

    private fun csvEscape(text: String): String {
        val s = text.replace("\"", "\"\"")
        return if (s.contains(",") || s.contains("\"") || s.contains("\n")) "\"$s\"" else s
    }

    private fun formatWeeksCompact(weeks: List<Int>): String {
        if (weeks.isEmpty()) return ""
        val sorted = weeks.sorted()
        val ranges = mutableListOf<String>()
        var start = sorted[0]; var end = sorted[0]
        for (i in 1 until sorted.size) {
            if (sorted[i] == end + 1) end = sorted[i]
            else { ranges.add(if (start == end) "$start" else "$start-$end"); start = sorted[i]; end = sorted[i] }
        }
        ranges.add(if (start == end) "$start" else "$start-$end")
        return ranges.joinToString(",")
    }

    // ════════════════════════════════════════════
    //  文件保存与分享
    // ════════════════════════════════════════════

    /**
     * 保存文本文件并通过 Share Sheet 分享
     * @param content 文件内容
     * @param fileName 文件名（含扩展名）
     * @param mimeType MIME 类型
     */
    fun shareTextFile(context: Context, content: String, fileName: String, mimeType: String) {
        try {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            val file = File(dir, fileName)
            file.writeText(content, Charsets.UTF_8)

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "导出 $fileName"))
            Log.d(TAG, "Shared: $fileName (${content.length} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "Share failed: $fileName", e)
            Toast.makeText(context, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 保存图片到相册并分享
     */
    fun shareBitmap(context: Context, bitmap: Bitmap, fileName: String) {
        try {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            val file = File(dir, fileName)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            // 压缩完成后立即回收，释放 native 内存
            bitmap.recycle()

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "分享课表图片"))
            Log.d(TAG, "Shared image: $fileName")
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Share image OOM", e)
            Toast.makeText(context, "内存不足，无法导出图片", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Share image failed", e)
            Toast.makeText(context, "导出图片失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ════════════════════════════════════════════
    //  课表图片渲染
    // ════════════════════════════════════════════

    // 柔和的课程卡片颜色
    private val CARD_COLORS = intArrayOf(
        0xFF42A5F5.toInt(), 0xFF66BB6A.toInt(), 0xFFFF7043.toInt(), 0xFFAB47BC.toInt(),
        0xFF26C6DA.toInt(), 0xFFFFCA28.toInt(), 0xFFEC407A.toInt(), 0xFF5C6BC0.toInt(),
        0xFF8D6E63.toInt(), 0xFF78909C.toInt(), 0xFF29B6F6.toInt(), 0xFFFFA726.toInt()
    )

    /**
     * 将课表渲染为 Bitmap 图片
     * @param courses 课程列表（已合并自定义课程）
     * @param week 当前周次（showAll=false 时筛选）
     * @param termName 学期名称（显示在标题）
     * @param showAll 是否显示全部周次（总览模式）
     */
    fun renderScheduleBitmap(
        courses: List<CourseItem>,
        week: Int,
        termName: String,
        showAll: Boolean
    ): Bitmap {
        val displayCourses = if (showAll) courses else courses.filter { it.isInWeek(week) }

        // 尺寸参数
        val headerH = 80f     // 标题栏高度
        val dayBarH = 48f     // 星期栏高度
        val sectionW = 40f    // 节次列宽
        val cellW = 148f      // 每天列宽
        val cellH = 64f       // 每节课高度
        val maxSection = 12
        val days = 7
        val totalW = (sectionW + cellW * days).toInt()
        val totalH = (headerH + dayBarH + cellH * maxSection).toInt()

        // OOM 防御：创建 Bitmap 可能因内存不足抛出 OutOfMemoryError
        val bitmap = try {
            Bitmap.createBitmap(totalW, totalH, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            // 触发 GC 后重试一次
            System.gc()
            Bitmap.createBitmap(totalW, totalH, Bitmap.Config.ARGB_8888)
        }
        val canvas = Canvas(bitmap)

        // 背景
        canvas.drawColor(Color.WHITE)

        // 画笔
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 28f; typeface = Typeface.DEFAULT_BOLD; color = Color.BLACK
            textAlign = Paint.Align.CENTER
        }
        val dayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 20f; typeface = Typeface.DEFAULT_BOLD; color = 0xFF37474F.toInt()
            textAlign = Paint.Align.CENTER
        }
        val sectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 16f; color = 0xFF78909C.toInt(); textAlign = Paint.Align.CENTER
        }
        val cardTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 16f; color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD
        }
        val cardSubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 13f; color = 0xDDFFFFFF.toInt()
        }
        val linePaint = Paint().apply { color = 0xFFE0E0E0.toInt(); strokeWidth = 1f }
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        // ── 标题栏 ──
        val titleText = if (showAll) "$termName 课表（总览）" else "$termName · 第${week}周"
        canvas.drawText(titleText, totalW / 2f, headerH * 0.6f, titlePaint)
        canvas.drawLine(0f, headerH, totalW.toFloat(), headerH, linePaint)

        // ── 星期栏 ──
        val dayNames = arrayOf("一", "二", "三", "四", "五", "六", "日")
        val dayBarTop = headerH
        for (d in 0 until days) {
            val cx = sectionW + cellW * d + cellW / 2
            canvas.drawText("周${dayNames[d]}", cx, dayBarTop + dayBarH * 0.65f, dayPaint)
        }
        canvas.drawLine(0f, dayBarTop + dayBarH, totalW.toFloat(), dayBarTop + dayBarH, linePaint)

        // ── 节次列 + 网格线 ──
        val gridTop = headerH + dayBarH
        for (s in 0 until maxSection) {
            val cy = gridTop + cellH * s + cellH / 2
            canvas.drawText("${s + 1}", sectionW / 2, cy + 6f, sectionPaint)
            // 水平线
            canvas.drawLine(sectionW, gridTop + cellH * s, totalW.toFloat(), gridTop + cellH * s, linePaint)
        }
        // 垂直线
        for (d in 0..days) {
            val x = sectionW + cellW * d
            canvas.drawLine(x, dayBarTop, x, totalH.toFloat(), linePaint)
        }

        // ── 课程卡片 ──
        val allNames = displayCourses.map { it.courseName }.distinct().sorted()
        val nameToColor = allNames.withIndex().associate { (i, name) -> name to CARD_COLORS[i % CARD_COLORS.size] }

        val padding = 3f
        val cornerRadius = 8f
        for (course in displayCourses) {
            val d = course.dayOfWeek - 1
            if (d !in 0..6) continue
            val color = nameToColor[course.courseName] ?: CARD_COLORS[0]
            cardPaint.color = color

            val left = sectionW + cellW * d + padding
            val right = sectionW + cellW * (d + 1) - padding
            val top = gridTop + cellH * (course.startSection - 1) + padding
            val bottom = gridTop + cellH * course.endSection - padding
            val rect = RectF(left, top, right, bottom)
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, cardPaint)

            // 课程名
            val availW = (right - left - 8f).toInt()
            val nameLines = wrapText(course.courseName, cardTextPaint, availW)
            val locLines = wrapText(course.location, cardSubPaint, availW)
            val lineH = 20f
            val subLineH = 16f
            val totalTextH = nameLines.size * lineH + locLines.size * subLineH
            var textY = top + ((bottom - top - totalTextH) / 2f).coerceAtLeast(4f) + lineH - 2f

            for (line in nameLines) {
                canvas.drawText(line, left + 4f, textY, cardTextPaint)
                textY += lineH
            }
            for (line in locLines) {
                canvas.drawText(line, left + 4f, textY, cardSubPaint)
                textY += subLineH
            }
        }

        return bitmap
    }

    /** 简单文本换行 */
    private fun wrapText(text: String, paint: Paint, maxWidth: Int): List<String> {
        if (text.isEmpty()) return emptyList()
        val lines = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val count = paint.breakText(text, start, text.length, true, maxWidth.toFloat(), null)
            if (count == 0) break
            lines.add(text.substring(start, start + count))
            start += count
        }
        return lines
    }
}
