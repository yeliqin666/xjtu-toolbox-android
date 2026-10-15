package com.xjtu.toolbox.schedule

import com.xjtu.toolbox.util.formatTwoDigits
import kotlinx.datetime.LocalDate

/** ICS 日历文件的文本生成（纯逻辑，不落盘、不分享）。 */
object ScheduleExport {

    // ════════════════════════════════════════════
    //  ICS 日历文件导出
    // ════════════════════════════════════════════

    /**
     * 生成 ICS 日历内容
     * @param courses 课程列表
     * @param startOfTerm 教务下发的学期开始日期（不必是周一，按所在周的周一算）
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

        for (course in courses) {
            val weeks = course.getWeeks()
            if (weeks.isEmpty()) continue

            for (week in weeks) {
                val courseDate = TermWeeks.dateOf(startOfTerm, week, course.dayOfWeek)

                // 节假日过滤：停的只是教务的课，自建日程照常导出
                if (!course.isUserCreated && holidayDates.contains(courseDate)) continue

                // 自建日程带分钟级时间，按节次换算会把 14:00 的事导成整节的钟点
                val startTime = course.startMinuteOfDay.takeIf { it >= 0 }?.let { it / 60 to it % 60 }
                    ?: sectionToTime(course.startSection, isStart = true, courseDate)
                val endTime = course.endMinuteOfDay.takeIf { it >= 0 }?.let { it / 60 to it % 60 }
                    ?: sectionToTime(course.endSection, isStart = false, courseDate)

                sb.appendLine("BEGIN:VEVENT")
                // UID 由课程和日期决定：重复导出、重复导入时日历软件能识别为同一个事件而不是叠加
                sb.appendLine("UID:${stableUid(course, courseDate)}@xjtu-toolbox")
                sb.appendLine("DTSTART;TZID=Asia/Shanghai:${icsStamp(courseDate, startTime.first, startTime.second)}")
                sb.appendLine("DTEND;TZID=Asia/Shanghai:${icsStamp(courseDate, endTime.first, endTime.second)}")
                sb.appendLine("SUMMARY:${escapeIcs(course.courseName)}")
                sb.appendLine("LOCATION:${escapeIcs(course.location)}")
                val desc = listOfNotNull(
                    "教师: ${course.teacher}",
                    course.courseType.takeIf { it.isNotEmpty() }?.let { "类型: $it" },
                    "节次: 第${course.startSection}-${course.endSection}节",
                    "周次: 第${week}周",
                ).joinToString("\n")
                sb.appendLine("DESCRIPTION:${escapeIcs(desc)}")
                // 上课前 15 分钟提醒
                sb.appendLine("BEGIN:VALARM")
                sb.appendLine("ACTION:DISPLAY")
                sb.appendLine("DESCRIPTION:${escapeIcs(course.courseName)} 即将上课")
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
        val isSummer = XjtuTime.isSummerTime(courseDate.monthNumber)
        val ct = XjtuTime.getClassTime(section, isSummer)
        return if (ct != null) {
            val t = if (isStart) ct.start else ct.end
            t.hour to t.minute
        } else {
            if (isStart) 8 to 0 else 8 to 50  // fallback
        }
    }

    /** `yyyyMMdd'T'HHmmss`（秒按 0 补）。 */
    private fun icsStamp(date: LocalDate, hour: Int, minute: Int): String =
        "${date.year}${formatTwoDigits(date.monthNumber)}${formatTwoDigits(date.dayOfMonth)}" +
            "T${formatTwoDigits(hour)}${formatTwoDigits(minute)}00"

    /**
     * 一条事件的稳定 ID：同一条课（同一课程号 + 课名 + 起始节次）在同一天必然得到同一个串。
     *
     * 只留下「字母/数字/`-._~`」：课名里可能有逗号、分号、括号，直接塞进 UID 会让某些
     * 日历软件在解析那一行时出错。
     */
    private fun stableUid(course: CourseItem, courseDate: LocalDate): String {
        val raw = "${course.courseCode}|${course.courseName}|$courseDate|${course.startSection}"
        return raw.map { if (it.isLetterOrDigit() || it in "-._~") it else '_' }.joinToString("")
    }

    private fun escapeIcs(text: String): String =
        text.replace("\\", "\\\\").replace(",", "\\,").replace(";", "\\;").replace("\n", "\\n")
}
