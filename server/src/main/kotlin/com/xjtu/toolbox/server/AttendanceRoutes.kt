package com.xjtu.toolbox.server

import com.xjtu.toolbox.attendance.AppAttendanceSource
import com.xjtu.toolbox.attendance.AttendanceWaterRecord
import com.xjtu.toolbox.attendance.LeaveApprovalNode
import com.xjtu.toolbox.attendance.LeaveApprover
import com.xjtu.toolbox.attendance.LeaveCourse
import com.xjtu.toolbox.attendance.LeaveEvidence
import com.xjtu.toolbox.attendance.LeaveFlowNode
import com.xjtu.toolbox.attendance.LeaveFlowPreview
import com.xjtu.toolbox.attendance.LeaveRecord
import com.xjtu.toolbox.attendance.LeaveSemesterWindow
import com.xjtu.toolbox.attendance.LeaveType
import com.xjtu.toolbox.attendance.TermInfo
import com.xjtu.toolbox.auth.SiteSession
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

/** `/api/attendance*`。 */
internal fun Route.attendanceRoutes(session: ServeSession) {
    route(ATTENDANCE_SEGMENT) {

        // ── 学期列表（屏上的学期选择器）──────────────────────────────
        get(ATTENDANCE_TERMS) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            try {
                val terms = AppAttendanceSource(session.attendanceSite()).getTermList()
                call.respond(ApiEnvelope.ok(AttendanceTermsData(terms.map { it.toDto() })))
            } catch (e: Exception) {
                call.respondUpstreamFailure(e, "加载考勤学期")
            }
        }

        // ── 某学期的考勤流水 ─────────────────────────────────────────
        get(ATTENDANCE_RECORDS) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            val params = call.request.queryParameters
            val requestedTerm = params[ATTENDANCE_PARAM_TERM]?.trim()?.takeIf { it.isNotEmpty() }
            if (requestedTerm != null && !requestedTerm.all { it.isDigit() }) {
                return@get call.respondBadRequestMessage(ATTENDANCE_TERM_SHAPE_MESSAGE)
            }
            val from = params[ATTENDANCE_PARAM_FROM]?.trim().orEmpty()
            val to = params[ATTENDANCE_PARAM_TO]?.trim().orEmpty()
            for ((name, value) in listOf(ATTENDANCE_PARAM_FROM to from, ATTENDANCE_PARAM_TO to to)) {
                if (value.isNotEmpty() && !isIsoDate(value)) {
                    return@get call.respondBadRequestMessage("$name 需要形如 2026-09-14")
                }
            }
            try {
                val source = AppAttendanceSource(session.attendanceSite())
                // 与 `:app`/桌面的取法逐条相同：没指定学期就用 `getTermBh()`（:data 那边按学期
                // 列表 + 今天算），并把定下来的那个学期原样回给客户端。
                val term = requestedTerm ?: source.getTermBh()
                val records = source.getWaterRecords(termBh = term, startDate = from, endDate = to)
                call.respond(
                    ApiEnvelope.ok(
                        AttendanceRecordsData(term = term, count = records.size, records = records.map { it.toDto() }),
                    ),
                )
            } catch (e: Exception) {
                call.respondUpstreamFailure(e, "加载考勤流水")
            }
        }

        // ── 请假 / 销假：二次确认在屏上，本层不再加一层 ──

        // 请假学期窗口（读）
        get(ATTENDANCE_LEAVE_WINDOW) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            try {
                val window = AppAttendanceSource(session.attendanceSite()).getSemesterWindow()
                call.respond(ApiEnvelope.ok(window.toDto()))
            } catch (e: Exception) {
                call.respondUpstreamFailure(e, "加载请假学期窗口")
            }
        }

        // 审批流程预览（读；`?type=` 只认 SICK / PERSONAL）
        get(ATTENDANCE_LEAVE_PREVIEW) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            val raw = call.request.queryParameters[ATTENDANCE_PARAM_TYPE]?.trim()
            val leaveType = LeaveType.entries.firstOrNull { it.code.equals(raw, ignoreCase = true) }
                ?: return@get call.respondBadRequestMessage(ATTENDANCE_LEAVE_TYPE_MESSAGE)
            try {
                val preview = AppAttendanceSource(session.attendanceSite()).getFlowPreview(leaveType)
                call.respond(ApiEnvelope.ok(preview.toDto()))
            } catch (e: Exception) {
                call.respondUpstreamFailure(e, "加载审批流程")
            }
        }

        // 请假记录（读）
        get(ATTENDANCE_LEAVE_RECORDS) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            val params = call.request.queryParameters
            val page = params.positiveInt(ATTENDANCE_PARAM_PAGE, 1)
                ?: return@get call.respondBadRequestMessage("page 需要是正整数")
            val size = params.positiveInt(ATTENDANCE_PARAM_SIZE, ATTENDANCE_LEAVE_PAGE_SIZE)
                ?: return@get call.respondBadRequestMessage("size 需要是正整数")
            val status = params[ATTENDANCE_PARAM_STATUS]?.trim().orEmpty()
            val rawType = params[ATTENDANCE_PARAM_TYPE]?.trim().orEmpty()
            if (rawType.isNotEmpty() && LeaveType.entries.none { it.code.equals(rawType, ignoreCase = true) }) {
                return@get call.respondBadRequestMessage(ATTENDANCE_LEAVE_TYPE_MESSAGE)
            }
            try {
                val leavePage = AppAttendanceSource(session.attendanceSite())
                    .getLeavePage(pageNum = page, pageSize = size, status = status, leaveType = rawType.uppercase())
                call.respond(
                    ApiEnvelope.ok(
                        AttendanceLeavePageData(
                            // 页码 / 页大小回的是**请求给的那两个**（与 `/api/coupon/list` 同一条；
                            // 上游回显的那两个本层不回，消费方一个都不读）
                            page = page,
                            size = size,
                            total = leavePage.total,
                            records = leavePage.records.map { it.toDto() },
                        ),
                    ),
                )
            } catch (e: Exception) {
                call.respondUpstreamFailure(e, "加载请假记录")
            }
        }

        // 提交请假申请（写）
        post(ATTENDANCE_LEAVE) {
            if (!session.authenticated) return@post call.respondLoginRequired()
            val body = call.receiveOrNull<AttendanceLeaveCreateRequest>()
                ?: return@post call.respondBadRequestMessage(ATTENDANCE_LEAVE_CREATE_BODY_MESSAGE)
            val leaveType = LeaveType.entries.firstOrNull { it.code.equals(body.type.trim(), ignoreCase = true) }
                ?: return@post call.respondBadRequestMessage(ATTENDANCE_LEAVE_TYPE_MESSAGE)
            for ((name, value) in listOf(
                ATTENDANCE_PARAM_START_TIME to body.startTime,
                ATTENDANCE_PARAM_END_TIME to body.endTime,
                ATTENDANCE_PARAM_NEXT_APPROVER to body.nextApproverUserId,
                ATTENDANCE_PARAM_REQUEST_ID to body.requestId,
            )) {
                if (value.isBlank()) return@post call.respondBadRequestMessage("$name 必填（不能是空白）")
            }
            if (body.reason.isBlank()) {
                return@post call.respondBadRequestMessage("reason 必填（不能是空白）")
            }
            // 两个时间要的是 `:data` 要的那一种形状（`yyyy-MM-dd HH:mm:ss`）——
            // 服务端不替它猜一种别的写法（猜错就是把「时区/格式不对」变成一条奇怪的申请）
            for ((name, value) in listOf(
                ATTENDANCE_PARAM_START_TIME to body.startTime,
                ATTENDANCE_PARAM_END_TIME to body.endTime,
            )) {
                if (!LEAVE_TIME_RE.matches(value.trim())) {
                    return@post call.respondBadRequestMessage(ATTENDANCE_LEAVE_TIME_MESSAGE.replace("X", name))
                }
            }
            try {
                val leaveId = AppAttendanceSource(session.attendanceSite()).createLeave(
                    type = leaveType,
                    startTime = body.startTime.trim(),
                    endTime = body.endTime.trim(),
                    reason = body.reason.trim(),
                    nextApproverUserId = body.nextApproverUserId.trim(),
                    // 附件这一格固定空表：`uploadEvidence` 在本层没有端点（见文件头 KDoc）
                    evidenceFiles = emptyList(),
                    requestId = body.requestId.trim(),
                )
                call.respond(ApiEnvelope.ok(AttendanceLeaveCreatedData(leaveId = leaveId)))
            } catch (e: Exception) {
                call.respondUpstreamFailure(e, "提交请假申请")
            }
        }

        // 撤回请假（写）
        post(ATTENDANCE_LEAVE_WITHDRAW) {
            if (!session.authenticated) return@post call.respondLoginRequired()
            val body = call.receiveOrNull<AttendanceLeaveWithdrawRequest>()
                ?: return@post call.respondBadRequestMessage(ATTENDANCE_LEAVE_WITHDRAW_BODY_MESSAGE)
            val leaveId = body.leaveId.trim()
            if (leaveId.isEmpty()) {
                return@post call.respondBadRequestMessage("leaveId 必填（取自 /api/attendance/leave/records 的 leaveId）")
            }
            try {
                AppAttendanceSource(session.attendanceSite())
                    .withdrawLeave(leaveId, body.reason.trim(), body.requestId.trim())
            } catch (e: Exception) {
                return@post call.respondUpstreamFailure(e, "撤回请假")
            }
            call.respond(ApiEnvelope.ok(AttendanceLeaveActionResult(ok = true, message = LEAVE_WITHDRAWN_MESSAGE)))
        }

        // 销假（写）
        post(ATTENDANCE_LEAVE_CANCEL) {
            if (!session.authenticated) return@post call.respondLoginRequired()
            val body = call.receiveOrNull<AttendanceLeaveCancelRequest>()
                ?: return@post call.respondBadRequestMessage(ATTENDANCE_LEAVE_CANCEL_BODY_MESSAGE)
            val leaveId = body.leaveId.trim()
            if (leaveId.isEmpty()) {
                return@post call.respondBadRequestMessage("leaveId 必填（取自 /api/attendance/leave/records 的 leaveId）")
            }
            try {
                AppAttendanceSource(session.attendanceSite()).cancelLeave(leaveId, body.requestId.trim())
            } catch (e: Exception) {
                return@post call.respondUpstreamFailure(e, "申请销假")
            }
            call.respond(ApiEnvelope.ok(AttendanceLeaveActionResult(ok = true, message = LEAVE_CANCELLED_MESSAGE)))
        }
    }
}

// ── 数据模型（客户端形状）──────────────────────────────────────────

/** `GET /api/attendance/terms` 的 `data`。 */
@Serializable
internal data class AttendanceTermsData(val terms: List<AttendanceTermDto>)

/** [TermInfo] 的线上形状（`:core` 的五个字段，一个不多）。 */
@Serializable
internal data class AttendanceTermDto(
    val bh: String,
    val name: String,
    /** 教务风格学期码（`2026-2027-1`）；取不到是空串（`:data` 不编）。 */
    val code: String,
    val startDate: String,
    val endDate: String,
)

/** `GET /api/attendance/records` 的 `data`。 */
@Serializable
internal data class AttendanceRecordsData(
    /** 本次查询用的学期号（请求没给就是当前学期）。 */
    val term: String,
    val count: Int,
    val records: List<AttendanceRecordDto>,
)

/** 一条考勤流水的线上形状。 */
@Serializable
internal data class AttendanceRecordDto(
    val id: String,
    val courseName: String,
    val courseCode: String,
    /** 学期码（`:data` 取不到码时兜底成学期名）。 */
    val term: String,
    /** 上课日期，`YYYY-MM-DD`。 */
    val date: String,
    /** 第几周（0 = 算不出来，`:data` 不编）。 */
    val week: Int,
    /** 起始节次（`:core` 的 `startTime`：是节次不是时刻）。 */
    val startSection: Int,
    /** 结束节次（`:core` 的 `endTime`）。 */
    val endSection: Int,
    val location: String,
    /** `WaterType.value`：1 正常 / 2 迟到 / 3 缺勤 / 5 请假 / **-1 未识别**。 */
    val status: Int,
    /** 配套中文：正常 / 迟到 / 缺勤 / 请假 / **未知**（`-1` 必须按这一格显示）。 */
    val statusText: String,
)

// ── 投影 helpers ─────────────────────────────────────────────────────

private fun TermInfo.toDto() = AttendanceTermDto(bh = bh, name = name, code = code, startDate = startDate, endDate = endDate)

private fun AttendanceWaterRecord.toDto() = AttendanceRecordDto(
    id = sbh,
    courseName = courseName,
    courseCode = courseCode,
    term = termString,
    date = date,
    week = week,
    startSection = startTime,
    endSection = endTime,
    location = location,
    status = status.value,
    statusText = status.displayName,
)

/** `YYYY-MM-DD` 的形状判据（与 `/api/card/transactions` 的 `from`/`to` 同一条）。 */
private fun isIsoDate(value: String): Boolean =
    value.length == 10 && runCatching { kotlinx.datetime.LocalDate.parse(value) }.isSuccess

// ── 常量 ─────────────────────────────────────────────────────────────

/** 考勤站点会话（key = `new_attendance`，`:data` 的 `AttendanceSession`）。 */
internal fun ServeSession.attendanceSite(): SiteSession = sessionManager.getSite(ServeSession.ATTENDANCE_SITE_KEY)

internal const val ATTENDANCE_SEGMENT = "attendance"
internal const val ATTENDANCE_TERMS = "terms"
internal const val ATTENDANCE_RECORDS = "records"
internal const val ATTENDANCE_PARAM_TERM = "term"
internal const val ATTENDANCE_PARAM_FROM = "from"
internal const val ATTENDANCE_PARAM_TO = "to"

/** `?term=` 的形状（学期号 = `bh`，形如 `646`；不是就 400）。 */
private const val ATTENDANCE_TERM_SHAPE_MESSAGE = "term 需要是学期号，形如 646（取自 /api/attendance/terms 的 bh）"


/** `GET /api/attendance/leave/window` 的 `data`：`LeaveSemesterWindow` 的六格原样投影。 */
@Serializable
internal data class AttendanceLeaveWindowData(
    val semesterId: String,
    val academicYear: String,
    val semesterName: String,
    val startDate: String,
    val endDate: String,
    val maxStudentLeaveMinutes: Int,
)

/** `GET /api/attendance/leave/preview` 的 `data`。 */
@Serializable
internal data class AttendanceLeavePreviewData(
    val nextNodeNo: Int,
    /** 这一轮能选的下一个审批人（屏上的下拉框就是它）。 */
    val nextApprovers: List<AttendanceLeaveApproverDto>,
    val nodes: List<AttendanceLeaveFlowNodeDto>,
)

/** 一个审批人（`LeaveApprover` 的两格）。 */
@Serializable
internal data class AttendanceLeaveApproverDto(val userId: String, val name: String)

/** 流程上的一个节点（`configured = false` 时上游还没配好）。 */
@Serializable
internal data class AttendanceLeaveFlowNodeDto(
    val nodeNo: Int,
    val nodeRole: String,
    val nodeName: String,
    val configured: Boolean,
    val approvers: List<AttendanceLeaveApproverDto>,
)

/** `GET /api/attendance/leave/records` 的 `data`：分页形状（§4）。 */
@Serializable
internal data class AttendanceLeavePageData(
    /** 请求给的那个页码（上游回显的两个本层不回，与 `/api/coupon/list` 同一条）。 */
    val page: Int,
    val size: Int,
    val total: Int,
    val records: List<AttendanceLeaveRecordDto>,
)

/** 一条请假记录（`LeaveRecord` 的线上形状）。 */
@Serializable
internal data class AttendanceLeaveRecordDto(
    val leaveId: String,
    /** `SICK` / `PERSONAL`（`:data` 原样端出来的上游值）。 */
    val leaveType: String,
    val reason: String,
    val applyTime: String,
    val startTime: String,
    val endTime: String,
    val status: String,
    val displayStatus: String,
    val approvalStatus: String,
    val cancelStatus: String,
    val sourceType: String,
    val durationMinutes: Int,
    val durationText: String,
    val archiveId: String,
    /** 能不能撤回 / 能不能销假（`:data` 的判据；屏上那两个按钮据它画）。 */
    val withdrawable: Boolean,
    val cancellable: Boolean,
    val evidenceFiles: List<AttendanceLeaveEvidenceDto>,
    val approvalNodes: List<AttendanceLeaveNodeDto>,
    val courses: List<AttendanceLeaveCourseDto>,
)

/** 一个附件（**只读**投影：附件本身怎么传上来本层没有端点，见文件头 KDoc）。 */
@Serializable
internal data class AttendanceLeaveEvidenceDto(
    val evidenceId: String,
    val fileName: String,
    val fileType: String,
    val fileSize: Long,
)

/** 一个审批节点（八格原样投影）。 */
@Serializable
internal data class AttendanceLeaveNodeDto(
    val nodeName: String,
    val status: String,
    val approverName: String,
    val processedName: String,
    val processedTime: String,
    val remark: String,
    val actionType: String,
    val autoReason: String,
)

/** 这条请假涉及的一节课（六格）。 */
@Serializable
internal data class AttendanceLeaveCourseDto(
    val courseId: String,
    val courseName: String,
    val courseDate: String,
    val startSection: Int,
    val endSection: Int,
    val teacherNames: String,
)

/** `POST /api/attendance/leave` 的请求体。 */
@Serializable
internal data class AttendanceLeaveCreateRequest(
    /** `SICK` / `PERSONAL`（大小写不敏感）；其他值 ⇒ `400`。 */
    val type: String = "",
    val startTime: String = "",
    val endTime: String = "",
    val reason: String = "",
    /** 取自 `/api/attendance/leave/preview` 的 `nextApprovers[].userId`。 */
    val nextApproverUserId: String = "",
    /** 幂等键（与两端的接口签名同一条）：缺了 ⇒ `400`（重发两次不该变成两条请假）。 */
    val requestId: String = "",
)

/** `POST /api/attendance/leave` 的 `data`：服务端（上游）给的那个单号。 */
@Serializable
internal data class AttendanceLeaveCreatedData(val leaveId: String)

/** `POST /api/attendance/leave/withdraw` 的请求体。 */
@Serializable
internal data class AttendanceLeaveWithdrawRequest(
    val leaveId: String = "",
    /** 撤回理由（屏上写的是固定那一句「学生撤回」）。 */
    val reason: String = "",
    val requestId: String = "",
)

/** `POST /api/attendance/leave/cancel` 的请求体。 */
@Serializable
internal data class AttendanceLeaveCancelRequest(val leaveId: String = "", val requestId: String = "")

/** 两个写动作共用 `{ok, message}`（与 §5.3.1 / §5.3.2 同一个形状）。 */
@Serializable
internal data class AttendanceLeaveActionResult(val ok: Boolean, val message: String)

// ── 投影 helpers ─────────────────────────────────────────────────────

private fun LeaveSemesterWindow.toDto() = AttendanceLeaveWindowData(
    semesterId = semesterId,
    academicYear = academicYear,
    semesterName = semesterName,
    startDate = startDate,
    endDate = endDate,
    maxStudentLeaveMinutes = maxStudentLeaveMinutes,
)

private fun LeaveFlowPreview.toDto() = AttendanceLeavePreviewData(
    nextNodeNo = nextNodeNo,
    nextApprovers = nextApprovers.map { it.toDto() },
    nodes = nodes.map { it.toDto() },
)

private fun LeaveApprover.toDto() =
    AttendanceLeaveApproverDto(userId = userId, name = name)

private fun LeaveFlowNode.toDto() = AttendanceLeaveFlowNodeDto(
    nodeNo = nodeNo,
    nodeRole = nodeRole,
    nodeName = nodeName,
    configured = configured,
    approvers = approvers.map { it.toDto() },
)

private fun LeaveRecord.toDto() = AttendanceLeaveRecordDto(
    leaveId = leaveId,
    leaveType = leaveType,
    reason = reason,
    applyTime = applyTime,
    startTime = startTime,
    endTime = endTime,
    status = status,
    displayStatus = displayStatus,
    approvalStatus = approvalStatus,
    cancelStatus = cancelStatus,
    sourceType = sourceType,
    durationMinutes = durationMinutes,
    durationText = durationText,
    archiveId = archiveId,
    withdrawable = withdrawable,
    cancellable = cancellable,
    evidenceFiles = evidenceFiles.map { AttendanceLeaveEvidenceDto(it.evidenceId, it.fileName, it.fileType, it.fileSize) },
    approvalNodes = approvalNodes.map {
        AttendanceLeaveNodeDto(
            nodeName = it.nodeName,
            status = it.status,
            approverName = it.approverName,
            processedName = it.processedName,
            processedTime = it.processedTime,
            remark = it.remark,
            actionType = it.actionType,
            autoReason = it.autoReason,
        )
    },
    courses = courses.map {
        AttendanceLeaveCourseDto(
            courseId = it.courseId,
            courseName = it.courseName,
            courseDate = it.courseDate,
            startSection = it.startSection,
            endSection = it.endSection,
            teacherNames = it.teacherNames,
        )
    },
)

// ── 常量（请假那一族）────────────────────────────────────────────────

internal const val ATTENDANCE_LEAVE = "leave"
internal const val ATTENDANCE_LEAVE_WINDOW = "leave/window"
internal const val ATTENDANCE_LEAVE_PREVIEW = "leave/preview"
internal const val ATTENDANCE_LEAVE_RECORDS = "leave/records"
internal const val ATTENDANCE_LEAVE_WITHDRAW = "leave/withdraw"
internal const val ATTENDANCE_LEAVE_CANCEL = "leave/cancel"
internal const val ATTENDANCE_PARAM_TYPE = "type"
internal const val ATTENDANCE_PARAM_PAGE = "page"
internal const val ATTENDANCE_PARAM_SIZE = "size"
internal const val ATTENDANCE_PARAM_STATUS = "status"
internal const val ATTENDANCE_PARAM_START_TIME = "startTime"
internal const val ATTENDANCE_PARAM_END_TIME = "endTime"
internal const val ATTENDANCE_PARAM_NEXT_APPROVER = "nextApproverUserId"
internal const val ATTENDANCE_PARAM_REQUEST_ID = "requestId"

/** 请假列表那一栏的默认页大小（= `:core` 的 `getLeavePage()` 默认值 20）。 */
private const val ATTENDANCE_LEAVE_PAGE_SIZE = 20

/** `?type=` 不认识时的中文短句（把合法取值列出来）。 */
private const val ATTENDANCE_LEAVE_TYPE_MESSAGE = "type 只能是 SICK / PERSONAL（大小写不敏感）"

/** 两个时间要的形状（`:data` 拼给上游的就是这一种：`formatLeaveTime` 那一行）。 */
private val LEAVE_TIME_RE = Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""")

private const val ATTENDANCE_LEAVE_TIME_MESSAGE = "X 需要形如 2026-10-20 08:00:00（与屏上那两个时间选择器同一种写法）"

internal const val ATTENDANCE_LEAVE_CREATE_BODY_MESSAGE =
    "请求体需要 JSON：{\"type\":\"SICK|PERSONAL\",\"startTime\":\"…\",\"endTime\":\"…\",\"reason\":\"…\",\"nextApproverUserId\":\"…\",\"requestId\":\"…\"}"

internal const val ATTENDANCE_LEAVE_WITHDRAW_BODY_MESSAGE =
    "请求体需要 JSON：{\"leaveId\":\"…\",\"reason\":\"…\",\"requestId\":\"…\"}"

internal const val ATTENDANCE_LEAVE_CANCEL_BODY_MESSAGE =
    "请求体需要 JSON：{\"leaveId\":\"…\",\"requestId\":\"…\"}"

internal const val LEAVE_WITHDRAWN_MESSAGE = "✓ 已撤回；需要重新提交请假申请"
internal const val LEAVE_CANCELLED_MESSAGE = "✓ 销假已提交给审批人确认"
