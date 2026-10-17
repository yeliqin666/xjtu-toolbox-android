package com.xjtu.toolbox.attendance

import kotlinx.serialization.json.JsonObject

/** 考勤的取数端口：屏与 ViewModel 都在 `:core`，两端各自填同一批字段。 */
/** 本端这一份取数真拿得到哪几栏：屏按它决定那一栏画什么。 */
data class AttendanceCapabilities(
    /** 按课程的统计那一栏（`getKqtjCurrentWeek` / `getKqtjByTime` / `computeCourseStatsFromRecords`）。 */
    val stats: Boolean = true,
    /** 打卡原始流水那一栏（`getStreams`）。 */
    val streams: Boolean = true,
    /** 请假那一栏（`getLeavePage` 等 —— 请假 / 销假是写路径，契约 §5.5 一律不端）。 */
    val leave: Boolean = true,
)

interface AttendanceSource {

    /** 见 [AttendanceCapabilities]：本端这一份取数真拿得到哪几栏（默认三栏都在）。 */
    val capabilities: AttendanceCapabilities get() = AttendanceCapabilities()

    // ── 考勤流水（AttendanceApi 那半）────────────────────────────────

    /** 学生信息（VM 只用 `name`）。 */
    suspend fun getStudentInfo(): Map<String, Any>

    suspend fun getTermList(): List<TermInfo>

    suspend fun getTermBh(): String

    suspend fun getWaterRecords(termBh: String? = null, startDate: String = "", endDate: String = ""): List<AttendanceWaterRecord>

    suspend fun getKqtjCurrentWeek(): List<CourseAttendanceStat>

    suspend fun getKqtjByTime(startDate: String, endDate: String): List<CourseAttendanceStat>

    suspend fun getStreams(startDate: String, endDate: String): List<AttendanceStream>

    fun computeCourseStatsFromRecords(records: List<AttendanceWaterRecord>): List<CourseAttendanceStat>

    // ── 请假（LeaveApi 那半）─────────────────────────────────────────

    suspend fun getSemesterWindow(): LeaveSemesterWindow

    suspend fun getFlowPreview(type: LeaveType): LeaveFlowPreview

    suspend fun getLeavePage(
        pageNum: Int = 1,
        pageSize: Int = 20,
        status: String = "",
        leaveType: String = "",
    ): LeavePage

    /** 附件上传，返回服务端的附件信息（`createLeave` 的 `evidenceFiles` 就填它）。 */
    suspend fun uploadEvidence(fileName: String, contentType: String, bytes: ByteArray): JsonObject

    suspend fun createLeave(
        type: LeaveType,
        startTime: String,
        endTime: String,
        reason: String,
        nextApproverUserId: String,
        evidenceFiles: List<JsonObject>,
        /** 幂等键；空串由实现方补一枚（`:core` 没有 `java.util.UUID`）。 */
        requestId: String = "",
    ): String

    suspend fun withdrawLeave(leaveId: String, reason: String, requestId: String = "")

    suspend fun cancelLeave(leaveId: String, requestId: String = "")
}