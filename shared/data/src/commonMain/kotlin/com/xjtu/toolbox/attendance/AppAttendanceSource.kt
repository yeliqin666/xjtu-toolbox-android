package com.xjtu.toolbox.attendance

import com.xjtu.toolbox.auth.SiteSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlinx.serialization.json.JsonObject

/** `:core` 的 [AttendanceSource] 在 `:data` 里的实现（Android 与桌面侧共用） */
class AppAttendanceSource(private val site: SiteSession) : AttendanceSource {

    private val api = AttendanceApi(site)
    private val leaveApi = LeaveApi(site)

    // ── 考勤流水（AttendanceApi 那半）────────────────────────────────

    override suspend fun getStudentInfo(): Map<String, Any> = withContext(Dispatchers.IO) { api.getStudentInfo() }

    override suspend fun getTermList(): List<TermInfo> = withContext(Dispatchers.IO) { api.getTermList() }

    override suspend fun getTermBh(): String = withContext(Dispatchers.IO) { api.getTermBh() }

    override suspend fun getWaterRecords(
        termBh: String?,
        startDate: String,
        endDate: String,
    ): List<AttendanceWaterRecord> = withContext(Dispatchers.IO) { api.getWaterRecords(termBh, startDate, endDate) }

    override suspend fun getKqtjCurrentWeek(): List<CourseAttendanceStat> =
        withContext(Dispatchers.IO) { api.getKqtjCurrentWeek() }

    override suspend fun getKqtjByTime(startDate: String, endDate: String): List<CourseAttendanceStat> =
        withContext(Dispatchers.IO) { api.getKqtjByTime(startDate, endDate) }

    override suspend fun getStreams(startDate: String, endDate: String): List<AttendanceStream> =
        withContext(Dispatchers.IO) { api.getStreams(startDate, endDate) }

    /** 端口改 suspend 只是为了让 serve 那一份能打端点；本机这一份仍是同一个纯函数（语义一字未改）。 */
    override suspend fun computeCourseStatsFromRecords(records: List<AttendanceWaterRecord>): List<CourseAttendanceStat> =
        api.computeCourseStatsFromRecords(records)

    // ── 请假（LeaveApi 那半）─────────────────────────────────────────

    override suspend fun getSemesterWindow(): LeaveSemesterWindow =
        withContext(Dispatchers.IO) { leaveApi.getSemesterWindow() }

    override suspend fun getFlowPreview(type: LeaveType): LeaveFlowPreview =
        withContext(Dispatchers.IO) { leaveApi.getFlowPreview(type) }

    override suspend fun getLeavePage(pageNum: Int, pageSize: Int, status: String, leaveType: String): LeavePage =
        withContext(Dispatchers.IO) { leaveApi.getLeavePage(pageNum, pageSize, status, leaveType) }

    override suspend fun uploadEvidence(fileName: String, contentType: String, bytes: ByteArray): JsonObject =
        withContext(Dispatchers.IO) { leaveApi.uploadEvidence(fileName, contentType, bytes) }

    override suspend fun createLeave(
        type: LeaveType,
        startTime: String,
        endTime: String,
        reason: String,
        nextApproverUserId: String,
        evidenceFiles: List<JsonObject>,
        requestId: String,
    ): String = withContext(Dispatchers.IO) {
        leaveApi.createLeave(
            type = type,
            startTime = startTime,
            endTime = endTime,
            reason = reason,
            nextApproverUserId = nextApproverUserId,
            evidenceFiles = evidenceFiles,
            requestId = requestId.ifBlank { UUID.randomUUID().toString() },
        )
    }

    override suspend fun withdrawLeave(leaveId: String, reason: String, requestId: String) =
        withContext(Dispatchers.IO) {
            leaveApi.withdrawLeave(leaveId, reason, requestId.ifBlank { UUID.randomUUID().toString() })
        }

    override suspend fun cancelLeave(leaveId: String, requestId: String) = withContext(Dispatchers.IO) {
        leaveApi.cancelLeave(leaveId, requestId.ifBlank { UUID.randomUUID().toString() })
    }
}