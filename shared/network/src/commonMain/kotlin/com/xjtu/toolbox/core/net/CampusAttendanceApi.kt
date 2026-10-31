package com.xjtu.toolbox.core.net

import com.xjtu.toolbox.attendance.AttendanceCapabilities
import com.xjtu.toolbox.attendance.AttendanceSource
import com.xjtu.toolbox.attendance.AttendanceStream
import com.xjtu.toolbox.attendance.AttendanceWaterRecord
import com.xjtu.toolbox.attendance.CourseAttendanceStat
import com.xjtu.toolbox.attendance.LeaveFlowPreview
import com.xjtu.toolbox.attendance.LeavePage
import com.xjtu.toolbox.attendance.LeaveSemesterWindow
import com.xjtu.toolbox.attendance.LeaveType
import com.xjtu.toolbox.attendance.TermInfo
import com.xjtu.toolbox.attendance.WaterType
import com.xjtu.toolbox.attendance.LeaveApprovalNode
import com.xjtu.toolbox.attendance.LeaveApprover
import com.xjtu.toolbox.attendance.LeaveCourse
import com.xjtu.toolbox.attendance.LeaveEvidence
import com.xjtu.toolbox.attendance.LeaveFlowNode
import com.xjtu.toolbox.attendance.LeaveRecord
import com.xjtu.toolbox.platform.secureRandomInt
import com.xjtu.toolbox.util.arr
import com.xjtu.toolbox.util.safeBoolean
import com.xjtu.toolbox.util.safeLong
import com.xjtu.toolbox.util.safeInt
import com.xjtu.toolbox.util.safeString
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 考勤（`kq.xjtu.edu.cn`）的 serve 版取数：给 `:web` 用，实现 [AttendanceSource]。 */
class CampusAttendanceApi(
    private val client: HttpClient,
    private val baseUrl: String = "",
    /** 见 [ApiMode]：这一份取数只认 serve 契约（§5.5）。 */
    private val mode: ApiMode = ApiMode.CAMPUS_API,
) : AttendanceSource {

    /** 三栏都在（2026-10-22 批5：统计与打卡流水也端了，契约 §5.12）—— 请假那一栏更早就有。 */
    override val capabilities: AttendanceCapabilities = AttendanceCapabilities(
        stats = true,
        streams = true,
        leave = true,
    )

    /** 服务端定下来的当前学期号（`/records` 响应里的 `term`），问到过才记。 */
    private var currentTermBh: String? = null

    override suspend fun getStudentInfo(): Map<String, Any> {
        requireServe()
        return emptyMap()
    }

    override suspend fun getTermList(): List<TermInfo> {
        requireServe()
        val data = client.serveData("加载考勤学期", baseUrl, TERMS_PATH)
        return data.arr("terms").orEmpty().mapNotNull { element ->
            val term = element as? JsonObject ?: return@mapNotNull null
            val bh = term["bh"].safeString().trim()
            if (bh.isEmpty()) return@mapNotNull null
            // name / code 都是 `:data` 拼好的（本层不再拼第二份），缺就是空串
            TermInfo(
                bh = bh,
                name = term["name"].safeString(),
                startDate = term["startDate"].safeString(),
                endDate = term["endDate"].safeString(),
                code = term["code"].safeString(),
            )
        }
    }

    override suspend fun getTermBh(): String {
        requireServe()
        currentTermBh?.let { return it }
        val term = client.serveData("加载当前学期", baseUrl, RECORDS_PATH)["term"].safeString().trim()
        if (term.isEmpty()) error("加载当前学期：服务端返回缺少 term（契约 §5.5）")
        currentTermBh = term
        return term
    }

    override suspend fun getWaterRecords(
        termBh: String?,
        startDate: String,
        endDate: String,
    ): List<AttendanceWaterRecord> {
        requireServe()
        val data = client.serveData(
            "加载考勤流水",
            baseUrl,
            RECORDS_PATH,
            // 空值参数不发：省略 `?term=` 由服务端算当前学期。
            listOf(
                "term" to termBh.orEmpty(),
                "from" to startDate,
                "to" to endDate,
            ),
        )
        if (currentTermBh == null) {
            data["term"].safeString().trim().takeIf { it.isNotEmpty() }?.let { currentTermBh = it }
        }
        return data.arr("records").orEmpty().mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val id = row["id"].safeString().trim()
            val courseName = row["courseName"].safeString().trim()
            if (id.isEmpty() || courseName.isEmpty()) return@mapNotNull null
            AttendanceWaterRecord(
                sbh = id,
                termString = row["term"].safeString(),
                // 契约把 `:core` 那两个 `Time` 字段名改成了 `startSection` / `endSection`
                // （它们其实是节次，1..11 —— §5.5），这里改回来就是原字段
                startTime = row["startSection"].safeInt(),
                endTime = row["endSection"].safeInt(),
                week = row["week"].safeInt(),
                location = row["location"].safeString(),
                courseName = courseName,
                courseCode = row["courseCode"].safeString(),
                teacher = "",
                status = WaterType.fromValue(row["status"].safeInt(WaterType.UNKNOWN.value)),
                date = row["date"].safeString(),
            )
        }
    }

    // ── 统计 / 打卡流水：契约 §5.5 没有端点 ────────────────────────────

    // ── 统计 / 打卡流水（契约 §5.12：两条端点都只认学期号）────────────────

    override suspend fun getKqtjCurrentWeek(): List<CourseAttendanceStat> = statsOf(currentTermBh)

    /**
     * 端点只认学期号（不认一对日期）—— 这两个参数是屏上用来算学期窗口的，同一个学期算出来的是同一份统计。
     */
    override suspend fun getKqtjByTime(startDate: String, endDate: String): List<CourseAttendanceStat> =
        statsOf(currentTermBh)

    /**
     * 按课程统计那一栏。
     *
     * [records] **不参与**：算路在 `:data`（只有 JVM/Android 变体，浏览器里没有它），所以 serve 这一份
     * 让服务端按**同一个学期**跑同一条算路（`/api/attendance/stats` 与 `/records` 同一口径，见契约 §5.12
     * 那句「缺勤按学期全量算」）。调用方总是刚拿完这个学期的流水（`currentTermBh` 于是已知），
     * 所以这里不会再发一次学期发现请求。
     */
    override suspend fun computeCourseStatsFromRecords(records: List<AttendanceWaterRecord>): List<CourseAttendanceStat> =
        statsOf(currentTermBh)

    override suspend fun getStreams(startDate: String, endDate: String): List<AttendanceStream> {
        requireServe()
        // 同样只认学期号：日期窗由服务端按该学期的起止推（契约 §5.12）。
        val term = currentTermBh ?: getTermBh()
        val data = client.serveData("加载打卡流水", baseUrl, STREAMS_PATH, listOf("term" to term))
        return data.arr("streams").orEmpty().mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            AttendanceStream(
                id = row["id"].safeString(),
                location = row["location"].safeString(),
                // 上游给的 `2026-09-16 08:05:00` 原样投影：本层不编一个时区（契约 §5.12）
                collectTime = row["collectTime"].safeString(),
                effective = row["effective"].safeBoolean(),
            )
        }
    }

    /** `GET /api/attendance/stats`（`?term=` 省略 = 服务端算当前学期）。 */
    private suspend fun statsOf(term: String?): List<CourseAttendanceStat> {
        requireServe()
        val data = client.serveData("统计考勤", baseUrl, STATS_PATH, listOf("term" to term.orEmpty()))
        return data.arr("stats").orEmpty().mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val name = row["courseName"].safeString().trim()
            if (name.isEmpty()) return@mapNotNull null
            CourseAttendanceStat(
                subjectName = name,
                subjectCode = row["courseCode"].safeString(),
                normalCount = row["normalCount"].safeInt(),
                lateCount = row["lateCount"].safeInt(),
                absenceCount = row["absenceCount"].safeInt(),
                leaveEarlyCount = row["leaveEarlyCount"].safeInt(),
                leaveCount = row["leaveCount"].safeInt(),
                total = row["total"].safeInt(),
            )
        }
    }

    // ── 请假那一半（三读 + 三写；附件上传没有端点）────────────────────

    override suspend fun getSemesterWindow(): LeaveSemesterWindow {
        requireServe()
        val data = client.serveData("加载请假学期窗口", baseUrl, LEAVE_WINDOW_PATH)
        return LeaveSemesterWindow(
            semesterId = data["semesterId"].safeString(),
            academicYear = data["academicYear"].safeString(),
            semesterName = data["semesterName"].safeString(),
            startDate = data["startDate"].safeString(),
            endDate = data["endDate"].safeString(),
            maxStudentLeaveMinutes = data["maxStudentLeaveMinutes"].safeInt(),
        )
    }

    override suspend fun getFlowPreview(type: LeaveType): LeaveFlowPreview {
        requireServe()
        val data = client.serveData(
            "加载审批流程",
            baseUrl,
            LEAVE_PREVIEW_PATH,
            listOf("type" to type.code),
        )
        return LeaveFlowPreview(
            nextNodeNo = data["nextNodeNo"].safeInt(),
            nextApprovers = data.arr("nextApprovers").orEmpty().mapNotNull { it.toApprover() },
            nodes = data.arr("nodes").orEmpty().mapNotNull { element ->
                val node = element as? JsonObject ?: return@mapNotNull null
                LeaveFlowNode(
                    nodeNo = node["nodeNo"].safeInt(),
                    nodeRole = node["nodeRole"].safeString(),
                    nodeName = node["nodeName"].safeString(),
                    configured = node["configured"].safeBoolean(),
                    approvers = node.arr("approvers").orEmpty().mapNotNull { it.toApprover() },
                )
            },
        )
    }

    /** 请假列表（那一栏的卡）。 */
    override suspend fun getLeavePage(pageNum: Int, pageSize: Int, status: String, leaveType: String): LeavePage {
        requireServe()
        val data = client.serveData(
            "加载请假记录",
            baseUrl,
            LEAVE_RECORDS_PATH,
            listOf(
                "page" to pageNum.toString(),
                "size" to pageSize.toString(),
                "status" to status,
                "type" to leaveType,
            ),
        )
        return LeavePage(
            records = data.arr("records").orEmpty().mapNotNull { element ->
                val row = element as? JsonObject ?: return@mapNotNull null
                val leaveId = row["leaveId"].safeString().trim()
                if (leaveId.isEmpty()) return@mapNotNull null
                row.toLeaveRecord(leaveId)
            },
            total = data["total"].safeInt(),
            pageNum = data["page"].safeInt(pageNum),
            pageSize = data["size"].safeInt(pageSize),
        )
    }

    /** 附件上传：这一端没有端点。 */
    override suspend fun uploadEvidence(fileName: String, contentType: String, bytes: ByteArray): JsonObject =
        throw UnsupportedOperationException(
            "serve 契约没有「上传请假附件」的端点（multipart/form-data）：docs/api-contract.md §5.5.1 ——" +
                "这一端的 `AttendanceScreen.pickEvidenceFiles` 给的是空表，屏上不会调到这里",
        )
    /** 提交请假申请（写）。 */
    override suspend fun createLeave(
        type: LeaveType,
        startTime: String,
        endTime: String,
        reason: String,
        nextApproverUserId: String,
        evidenceFiles: List<JsonObject>,
        requestId: String,
    ): String {
        requireServe()
        val id = requestId.trim().ifEmpty { newRequestId() }
        val body = buildJsonObject {
            put("type", type.code)
            put("startTime", startTime)
            put("endTime", endTime)
            put("reason", reason)
            put("nextApproverUserId", nextApproverUserId)
            put("requestId", id)
        }.toString()
        // `evidenceFiles` 派不上：本端没有附件端点，屏上那一个槽位给的也是空表 ——
        // 不把它发出去（那会被读成「附件就是空的」）。
        val data = client.serveLeaveAction("提交请假申请", baseUrl, LEAVE_PATH, body)
        val leaveId = data["leaveId"].safeString().trim()
        if (leaveId.isEmpty()) error("提交请假申请：服务端返回缺少 leaveId（契约 §5.5.1）")
        return leaveId
    }

    /** 撤回请假（**写**）：屏上那一次 `OverlayDialog` 确认之后才到这里。 */
    override suspend fun withdrawLeave(leaveId: String, reason: String, requestId: String) {
        requireServe()
        val body = buildJsonObject {
            put("leaveId", leaveId)
            put("reason", reason)
            put("requestId", requestId.trim().ifEmpty { newRequestId() })
        }.toString()
        client.serveLeaveAction("撤回请假", baseUrl, LEAVE_WITHDRAW_PATH, body)
    }

    /** 销假（**写**）：同样先过屏上那次确认。 */
    override suspend fun cancelLeave(leaveId: String, requestId: String) {
        requireServe()
        val body = buildJsonObject {
            put("leaveId", leaveId)
            put("requestId", requestId.trim().ifEmpty { newRequestId() })
        }.toString()
        client.serveLeaveAction("申请销假", baseUrl, LEAVE_CANCEL_PATH, body)
    }

    private fun requireServe() {
        if (mode != ApiMode.SERVE) {
            serveOnly(
                "考勤取数",
                "$TERMS_PATH + $RECORDS_PATH + $LEAVE_WINDOW_PATH + $LEAVE_PREVIEW_PATH + $LEAVE_RECORDS_PATH + $LEAVE_PATH",
            )
        }
    }

    /** 一次性幂等键（`:core` 没有 `java.util.UUID`）：与 [FeedbackStore] 同一个字母表与长度。 */
    private fun newRequestId(): String = buildString {
        repeat(16) { append(REQUEST_ID_ALPHABET[secureRandomInt(REQUEST_ID_ALPHABET.length)]) }
    }
}

/** 请假那三个写动作共用的一枪：`serveAction` + 「不成就是异常」。 */
private suspend fun HttpClient.serveLeaveAction(
    what: String,
    baseUrl: String,
    path: String,
    body: String,
): JsonObject {
    val outcome = serveAction(baseUrl, path, body)
    if (!outcome.ok) error("$what：${outcome.message}")
    return outcome.data ?: error("$what：服务端返回缺少 data（契约 §5.4）")
}

/** 按课程的考勤统计（`?term=`，契约 §5.12）。 */
private const val STATS_PATH = "/api/attendance/stats"

/** 打卡原始流水（`?term=`，契约 §5.12）。 */
private const val STREAMS_PATH = "/api/attendance/streams"

/** 幂等键的字母表（与 `:data` 那边同为十六进制大写 —— 一眼看得出是键不是正文）。 */
private const val REQUEST_ID_ALPHABET = "0123456789ABCDEF"

/** 请假学期窗口（三格读的第一格）。 */
private const val LEAVE_WINDOW_PATH = "/api/attendance/leave/window"

/** 审批流程预览（`?type=`）。 */
private const val LEAVE_PREVIEW_PATH = "/api/attendance/leave/preview"

/** 请假记录（`?page=&size=&status=&type=`）。 */
private const val LEAVE_RECORDS_PATH = "/api/attendance/leave/records"

/** 提交请假申请（**写**）。 */
private const val LEAVE_PATH = "/api/attendance/leave"

/** 撤回请假（**写**）。 */
private const val LEAVE_WITHDRAW_PATH = "/api/attendance/leave/withdraw"

/** 销假（**写**）。 */
private const val LEAVE_CANCEL_PATH = "/api/attendance/leave/cancel"

// ── 请假那一族的投影 helpers ──────────────────────────────────────────

/** 一个审批人（`userId` 为空的那一条不列 —— 屏上那个下拉框要的就是这个句柄）。 */
private fun JsonElement?.toApprover(): LeaveApprover? {
    val row = this as? JsonObject ?: return null
    val userId = row["userId"].safeString().trim()
    if (userId.isEmpty()) return null
    return LeaveApprover(userId = userId, name = row["name"].safeString())
}

/** 一条请假记录（`LeaveRecord` 的十九格）。 */
private fun JsonObject.toLeaveRecord(leaveId: String) = LeaveRecord(
    leaveId = leaveId,
    leaveType = this["leaveType"].safeString(),
    reason = this["reason"].safeString(),
    applyTime = this["applyTime"].safeString(),
    startTime = this["startTime"].safeString(),
    endTime = this["endTime"].safeString(),
    status = this["status"].safeString(),
    displayStatus = this["displayStatus"].safeString(),
    approvalStatus = this["approvalStatus"].safeString(),
    cancelStatus = this["cancelStatus"].safeString(),
    sourceType = this["sourceType"].safeString(),
    durationMinutes = this["durationMinutes"].safeInt(),
    durationText = this["durationText"].safeString(),
    archiveId = this["archiveId"].safeString(),
    withdrawable = this["withdrawable"].safeBoolean(),
    cancellable = this["cancellable"].safeBoolean(),
    evidenceFiles = arr("evidenceFiles").orEmpty().mapNotNull { element ->
        val file = element as? JsonObject ?: return@mapNotNull null
        LeaveEvidence(
            evidenceId = file["evidenceId"].safeString(),
            fileName = file["fileName"].safeString(),
            fileType = file["fileType"].safeString(),
            fileSize = file["fileSize"].safeLong(),
        )
    },
    approvalNodes = arr("approvalNodes").orEmpty().mapNotNull { element ->
        val node = element as? JsonObject ?: return@mapNotNull null
        LeaveApprovalNode(
            nodeName = node["nodeName"].safeString(),
            status = node["status"].safeString(),
            approverName = node["approverName"].safeString(),
            processedName = node["processedName"].safeString(),
            processedTime = node["processedTime"].safeString(),
            remark = node["remark"].safeString(),
            actionType = node["actionType"].safeString(),
            autoReason = node["autoReason"].safeString(),
        )
    },
    courses = arr("courses").orEmpty().mapNotNull { element ->
        val course = element as? JsonObject ?: return@mapNotNull null
        LeaveCourse(
            courseId = course["courseId"].safeString(),
            courseName = course["courseName"].safeString(),
            courseDate = course["courseDate"].safeString(),
            startSection = course["startSection"].safeInt(),
            endSection = course["endSection"].safeInt(),
            teacherNames = course["teacherNames"].safeString(),
        )
    },
)
/** 学期列表（契约 §5.5）。 */
private const val TERMS_PATH = "/api/attendance/terms"
/** 某学期的考勤流水（`?term=` 学期号 / `?from=&to=` 日期，都可省略）。 */
private const val RECORDS_PATH = "/api/attendance/records"
