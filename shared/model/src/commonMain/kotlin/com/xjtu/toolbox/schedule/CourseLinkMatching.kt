package com.xjtu.toolbox.schedule

import com.xjtu.toolbox.attendance.AttendanceWaterRecord
import com.xjtu.toolbox.attendance.WaterType
import com.xjtu.toolbox.lms.LmsCourseSummary
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber

// 课程详情的两项下钻（思源学堂入口、考勤角标）里**不含取数**的那一半：判据与索引。
//
// 这里全是纯函数：吃一份已经拿到手的数据、吐一个判据，不碰会话、网络、落盘、时钟。
// 放在 `:shared:model` 的 commonMain ⇒ android / jvm / wasm 三端同一份判据（以前这两段和
// `java.time` + `SessionManager` 缠在一起长在 `:shared:data`，只有 JVM 变体，web 编不出来）。
//
// 取数那一侧由各端自己给（见 [CourseLinkSource]）：Android / 桌面直连校园站点，
// web 走 serve 端点。三端拿到的数据进这里，配对与角标判据逐字一致。

/** 考勤流水与课表格子的联合键。 */
data class AttendanceSlotKey(val week: Int, val dayOfWeek: Int, val startSection: Int)

/**
 * 一学期的考勤流水，按 [AttendanceSlotKey] 建索引。
 *
 * 这三个字段两边同源于教务排课数据，逐字相等。教室不能当键：考勤拼的是
 * 「楼宇名-房间号」，课表是教务原始的 `JASMC`，格式不同。
 */
class SlotAttendanceMarks(
    private val byKey: Map<AttendanceSlotKey, AttendanceWaterRecord>,
) : AttendanceMarks {

    override fun statusOf(week: Int, dayOfWeek: Int, startSection: Int): WaterType? =
        statusRecordOf(week, dayOfWeek, startSection)?.status

    fun statusRecordOf(week: Int, dayOfWeek: Int, startSection: Int): AttendanceWaterRecord? =
        byKey[AttendanceSlotKey(week, dayOfWeek, startSection)]

    /** 指定周次的那一次考勤。没有记录 = 还没上，或这门课不考勤。 */
    override fun recordOn(course: CourseItem, week: Int): AttendanceWaterRecord? =
        statusRecordOf(week, course.dayOfWeek, course.startSection)
}

/**
 * 拉到的考勤流水 → 课表格子索引。
 *
 * 同一格重复上报时保留「更坏」的那条：缺勤 > 迟到 > 请假 > 正常 —— 把异常盖成正常等于丢信息。
 * 周次/节次说不清的行直接跳过（脏数据不该占住一格）。
 */
fun attendanceMarksOf(records: List<AttendanceWaterRecord>): SlotAttendanceMarks {
    val byKey = HashMap<AttendanceSlotKey, AttendanceWaterRecord>()
    for (r in records) {
        val dow = r.date.dayOfWeekOfDate() ?: continue
        if (r.week <= 0 || r.startTime <= 0) continue
        val key = AttendanceSlotKey(r.week, dow, r.startTime)
        val old = byKey[key]
        if (old == null || r.status.severity() > old.status.severity()) byKey[key] = r
    }
    return SlotAttendanceMarks(byKey = byKey)
}

/**
 * 这门课在思源学堂对应哪门。
 *
 * 先按课程号精确配——两个系统用的是同一套教务课程号，这是唯一可靠的判据；
 * 课程号常带班号后缀（`MATH100101-05`），所以再退一步比主段。
 * 都不中才按课程名，且只认归一化后完全相同的，不做包含匹配：
 * 「大学物理」能包含到「大学物理实验」，那是两门课，给错比不给更糟。
 */
fun lmsCourseMatched(all: List<LmsCourseSummary>, course: CourseItem): LmsCourseSummary? {
    if (all.isEmpty()) return null
    val code = course.courseCode.trim()
    if (code.isNotEmpty()) {
        all.firstOrNull { it.courseCode.trim().equals(code, ignoreCase = true) }?.let { return it }
        val stem = code.substringBefore('-').trim()
        if (stem.length >= 4) {
            all.firstOrNull {
                it.courseCode.trim().substringBefore('-').equals(stem, ignoreCase = true)
            }?.let { return it }
        }
    }
    val target = course.courseName.normalizedCourseName()
    if (target.isEmpty()) return null
    return all.firstOrNull { it.name.normalizedCourseName() == target }
}

/**
 * 「取数」那一侧：本端怎么把思源课程表、一学期考勤流水拿到手。
 *
 * Android / 桌面 = 直连校园站点（`Connection` 那一套）；web = serve 的两个端点。
 * 判据不在这里 —— 拿到什么就交给 [SourceBackedCourseLinkAccess] 去配、去建索引。
 */
interface CourseLinkSource {

    /** 我正在上的思源课程。拿不到返回 null（界面当作没这一格，不画错的东西）。 */
    suspend fun lmsCourses(): List<LmsCourseSummary>?

    /** 某个教务学期（`2025-2026-2`）的考勤流水。拿不到返回 null。 */
    suspend fun attendanceRecords(termCode: String, userInitiated: Boolean): List<AttendanceWaterRecord>?
}

/**
 * 只差「取数」的 [CourseLinkAccess]：接入方给两个端点，配对与索引全用共享判据。
 *
 * 已经接了这一格的两端（Android / 桌面）在 `:shared:data` 的 `CourseLinks` 里有自己那份
 * （带进程内缓存、学期封存、考勤落盘策略），用的是同一套判据函数；这里的实现是给**还没有**那一层
 * 的端（web）抄近路的：它只做「取数 → 判据」，不缓存也不落盘。
 */
class SourceBackedCourseLinkAccess(private val source: CourseLinkSource) : CourseLinkAccess {

    override suspend fun attendanceMarks(
        termCode: String,
        finishedTerms: Collection<String>,
        userInitiated: Boolean,
    ): AttendanceMarks? = source.attendanceRecords(termCode, userInitiated)?.let { attendanceMarksOf(it) }

    override suspend fun lmsCourseFor(course: CourseItem): LmsCourseSummary? =
        source.lmsCourses()?.let { lmsCourseMatched(it, course) }
}

/** 考勤状态的轻重：数字越大越「坏」。未识别状态按最坏处理，避免被一条正常记录悄悄盖掉。 */
private fun WaterType.severity(): Int = when (this) {
    WaterType.ABSENCE -> 3
    WaterType.LATE -> 2
    WaterType.LEAVE -> 1
    WaterType.NORMAL -> 0
    WaterType.UNKNOWN -> 4
}

/** 考勤的 `checkdate` 形如 `2025-09-15`，可能带时间后缀，取前 10 位解析。 */
private fun String.dayOfWeekOfDate(): Int? = runCatching {
    LocalDate.parse(take(10)).dayOfWeek.isoDayNumber
}.getOrNull()
