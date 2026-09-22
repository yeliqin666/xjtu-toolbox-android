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
     * @param holidayDates 忽略的节假日（若不忽略则传空或过滤为 null）
     */
    fun generateIcs(
        courses: List<CourseItem>, 
        startOfTerm: LocalDate, 
        termName: String,
        holidayDates: Set<LocalDate> = emptySet()
    ): String {
        val sb = StringBuilder()
        sb.appendLine("BEGIN:VCALENDAR")
        sb.appendLine("VERSION:2.0")
        sb.appendLine("PRODID:-//XJTUToolBox//Schedule//CN")
        sb.appendLine("CALSCALE:GREGORIAN")
        sb.appendLine("X-WR-CALNAME:$termName 日程")
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

                // ★ 节假日过滤：停的只是教务的课，自建日程照常导出
                if (!course.isUserCreated && holidayDates.contains(courseDate)) {
                    Log.d(TAG, "ICS Export: Skipped course '\${course.courseName}' on holiday \$courseDate")
                    continue
                }

                // 自建日程带分钟级时间，按节次换算会把 14:00 的事导成整节的钟点
                val startTime = course.startMinuteOfDay.takeIf { it >= 0 }?.let { it / 60 to it % 60 }
                    ?: sectionToTime(course.startSection, isStart = true, courseDate)
                val endTime = course.endMinuteOfDay.takeIf { it >= 0 }?.let { it / 60 to it % 60 }
                    ?: sectionToTime(course.endSection, isStart = false, courseDate)

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
                // ★ 添加上课前 15 分钟提醒
                sb.appendLine("BEGIN:VALARM")
                sb.appendLine("ACTION:DISPLAY")
                sb.appendLine("DESCRIPTION:\${escapeIcs(course.courseName)} 即将上课")
                sb.appendLine("TRIGGER:-PT15M")
                sb.appendLine("END:VALARM")
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
}
