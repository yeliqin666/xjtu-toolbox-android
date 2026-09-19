package com.xjtu.toolbox.newattendance

/**
 * 把新版考勤 (academicYear, semesterName) 拼成 jwxt 课表风格的学期码，如
 * "2026-2027-1"。[com.xjtu.toolbox.schedule.CourseLinks] 靠这个码对齐教务课表的
 * termCode——以前直接用人类可读名"2026-2027 第一学期"，跟课表那边的
 * "2026-2027-1"怎么比都对不上，考勤记录整学期全部被过滤掉。单独抽出来是为了能
 * 脱离 [NewAttendanceApi]（需要 SiteSession）单独做 JVM 单测。
 */
object TermCodeMapper {
    fun termCodeOf(year: String, semesterName: String): String {
        if (year.isBlank()) return ""
        val ordinal = when {
            "一" in semesterName || "第1" in semesterName || "第一" in semesterName -> "1"
            "二" in semesterName || "第2" in semesterName || "第二" in semesterName -> "2"
            "三" in semesterName || "第3" in semesterName || "第三" in semesterName -> "3"
            "四" in semesterName || "第4" in semesterName || "第四" in semesterName -> "4"
            else -> return ""
        }
        return "$year-$ordinal"
    }
}
