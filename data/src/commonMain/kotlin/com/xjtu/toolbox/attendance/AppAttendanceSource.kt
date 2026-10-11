package com.xjtu.toolbox.attendance

import com.xjtu.toolbox.auth.SiteSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlinx.serialization.json.JsonObject

/**
 * `:core` 的 [AttendanceSource] 在 `:data` 里的实现（Android 与桌面侧共用）—— 包住原来的
 * [AttendanceApi] + [LeaveApi]（okhttp 抓 `kq.xjtu.edu.cn` 考勤系统）。
 *
 * 它从 `:app` 搬进 `:data`（桌面端第 16 条真数据路由：桌面要自己登 `kq` 站、自己取考勤流水与请假）。
 * 取数的类名与包路径一字未改，两件事各动一半：
 *
 * 1. **调度器**：搬迁前是 ViewModel 每次调用自己 `withContext(Dispatchers.IO)`，现在 VM 不再替
 *    实现挑调度器（见 [AttendanceSource] 的 KDoc），由本类自己包 —— **同一层、同一个调度器，
 *    行为不变**；
 * 2. **幂等键**：`createLeave` / `withdrawLeave` / `cancelLeave` 的 `requestId` 在 `:core` 的端口
 *    上是空串默认（commonMain 没有 `java.util.UUID`），这里补回 `UUID.randomUUID()` —— 与搬迁前
 *    `LeaveApi` 的默认值同一语义，实际打出去的请求体一个字没变。
 *
 * 会话仍然是 `:data` 的 [SiteSession]（`new_attendance`，见 `AttendanceSession` 与
 * `AttendanceLogin`）：业务令牌与 `X-System` 头由 `AttendanceSession.decorateRequest` 自动带上，
 * 与搬迁前逐字相同。
 */
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

    override fun computeCourseStatsFromRecords(records: List<AttendanceWaterRecord>): List<CourseAttendanceStat> =
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