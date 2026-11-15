package com.xjtu.toolbox.web

import com.xjtu.toolbox.core.net.CampusAttendanceApi
import com.xjtu.toolbox.core.net.CampusLmsApi
import com.xjtu.toolbox.lms.LmsCourseSummary
import com.xjtu.toolbox.schedule.AttendanceMarks
import com.xjtu.toolbox.schedule.CourseItem
import com.xjtu.toolbox.schedule.CourseLinkAccess
import com.xjtu.toolbox.schedule.attendanceMarksOf

/**
 * 课程详情那两项下钻（思源学堂入口 / 本次考勤 + 课表格子上的考勤角标）在**浏览器这一端**的取数。
 *
 * 判据一个都不在这里：配对与建索引用的是 `:shared:model` 那份纯函数（`lmsCourseMatched` 由 serve 的
 * `/api/lms/course-match` 跑、索引走本地 `attendanceMarksOf`）—— 与 Android / 桌面同一套口径
 * （见 `CourseLinkMatching.kt` 的文件头）。
 *
 * 两条如实边界：
 *
 * 1. **取数失败 = 那一格不画**（返回 null / 空），不往 composition 里抛：屏上这两处在
 *    `LaunchedEffect` 里裸调（`:data` 那一份自带站点级失败冷却，不会抛），所以这一份自己吞掉。
 *    「没配到课」与「这一发没取到」在屏上是同一种表现：不给入口 —— 给错课比不给更糟。
 * 2. **学期号要换一次**：屏给的是学期代码（`2025-2026-2`），而 serve 的 `/api/attendance/records`
 *    只认学期号（`bh`，纯数字）⇒ 先由 `/api/attendance/terms` 换一次（一个学期一次，屏只在打开详情时调）。
 */
internal class WebCourseLinkAccess(
    private val attendance: CampusAttendanceApi,
    private val lms: CampusLmsApi,
) : CourseLinkAccess {

    override suspend fun attendanceMarks(
        termCode: String,
        finishedTerms: Collection<String>,
        userInitiated: Boolean,
    ): AttendanceMarks? = runCatching {
        val term = attendance.getTermList().firstOrNull { it.code == termCode } ?: return@runCatching null
        val records = attendance.getWaterRecords(
            termBh = term.bh,
            startDate = term.startDate,
            endDate = term.endDate,
        )
        attendanceMarksOf(records)
    }.getOrNull()

    override suspend fun lmsCourseFor(course: CourseItem): LmsCourseSummary? = runCatching {
        // 服务端那一条要求「课程号与课程名至少给一个」；两个都空就干脆不问（400 是参数错，不是「配不上」）
        if (course.courseCode.isBlank() && course.courseName.isBlank()) return@runCatching null
        lms.courseMatch(
            courseCode = course.courseCode,
            courseName = course.courseName,
            teacher = course.teacher,
        )
    }.getOrNull()
}
