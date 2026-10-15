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

/**
 * 考勤（`kq.xjtu.edu.cn`）的 **serve 版取数**：给 `:web` 用，实现 [AttendanceSource]。
 *
 * | 端点 | 本类实现的方法 |
 * |---|---|
 * | `GET /api/attendance/terms` | [getTermList] |
 * | `GET /api/attendance/records` | [getTermBh]（不带参数问一次，读响应里的 `term`）+ [getWaterRecords] |
 * | `GET /api/attendance/leave/window` | [getSemesterWindow] |
 * | `GET /api/attendance/leave/preview?type=` | [getFlowPreview] |
 * | `GET /api/attendance/leave/records?page=&size=&status=&type=` | [getLeavePage] |
 * | `POST /api/attendance/leave` | [createLeave]（**写**） |
 * | `POST /api/attendance/leave/withdraw` | [withdrawLeave]（**写**） |
 * | `POST /api/attendance/leave/cancel` | [cancelLeave]（**写**） |
 *
 * 契约 §5.5；`:server` 那一侧是 `AttendanceRoutes.kt`（薄包装 `:data` 的 `AppAttendanceSource`）。
 *
 * ## 端了哪几栏（2026-10-15 批③-b / D7 ② 之后）
 *
 * | 端口方法 | 端不端 |
 * |---|---|
 * | [getTermList] / [getTermBh] / [getWaterRecords] | **端**（学期 + 流水两个端点） |
 * | 请假那一半（[getSemesterWindow] / [getFlowPreview] / [getLeavePage] / [createLeave] / [withdrawLeave] / [cancelLeave]） | ✅ **已端**（三读 + 三写，见上表） |
 * | [uploadEvidence] | 不端：它是 `multipart/form-data`，而**这一端没有文件选择宿主**（`:web` 的 `AttendanceScreen` 用的是 `pickEvidenceFiles` 默认值 = 空表）⇒ 屏上根本不会调它 —— 与桌面同一条 |
 * | [getStudentInfo] | 不端：姓名是红线（人不端名，也不端别的东西）⇒ 如实给空表 |
 * | [getKqtjCurrentWeek] / [getKqtjByTime] / [computeCourseStatsFromRecords] | 不端：统计那一栏 |
 * | [getStreams] | 不端：打卡原始流水那一栏 |
 *
 * 于是 [capabilities] 只剩 `stats` / `streams` 两档报 `false`（`leave` 已翻 `true`），
 * 屏上那两栏画的是「本端拿不到 …」而不是「暂无 …」（后者会把缺口说成事实）。
 *
 * 三处**刻意不做**的事（都能做，但做了就是把缺口藏起来）：
 *  - 统计不拿流水在**本机**算一份（`computeCourseStatsFromRecords` 返回空表）：那份口径在 `:data`
 *    里，再实现一遍就是第二份口径，迟早与 App / 桌面分叉；
 *  - 打卡流水不编（空表 + `capabilities.streams = false`）；
 *  - [getStudentInfo] 不给假名字（空表 ⇒ 屏上姓名那一行自然不画）。
 *
 * ## 两个口径都**不复制一份**
 *
 * - 当前学期：[getTermBh] 不带 `?term=` 问一次 `/records` —— 服务端用的就是 `:data` 的 `getTermBh()`
 *   算出来的那个学期，响应里的 `term` 原样就是答案（本端不按学期表 + 今天再算一遍）；
 * - 缺字段的行：`courseName` 为空的行不列（`id` 为空的行也不列 —— `:data` 那边同样把上游缺 `roomId`
 *   那一类丢掉）；`status` 用 `WaterType.fromValue` 映射，`-1` 落到 `UNKNOWN`（显示名「未知」，
 *   与响应里的 `statusText` 逐字一致 —— §5.5 要的就是这一条），**不许**悄悄算成正常。
 *
 * ## 姓名：哪一格端、哪一格不端（§5.2 的红线与例外）
 *
 * 考勤记录里的上课教师名是**姓名**，契约 §5.2 的例外清单里没有它 ⇒ 本端如实留空
 *（[AttendanceWaterRecord.teacher] 给空串），屏上也不画那一格（§5.5）。
 *
 * 请假那一族的两类姓名在例外 ④ 里（2026-10-15 上一级拍板）：审批人的 `name`
 *（**我自己的申请上的审批人姓名**）与每条记录里 `LeaveCourse.teacherNames`（**我自己的课的任课
 * 教师姓名**）—— 两格都按 `:server` 端出来的原文收下（`:server` 那一层是唯一的判据点，
 * 本端不再拦第二道）。除了这两类，其余姓名一律仍不投影。
 *
 * ## 默认形态（campus-api）
 *
 * 考勤在 campus-api 里没有对应端点（`:app` / 桌面走 `:data` 的 okhttp）⇒ 非 serve 分支一律
 * [serveOnly]（抛错，不返回一份空数据）。
 *
 * @param baseUrl 留空 ⇒ 相对路径即**同源**（`:web` 由 `:server` 托管，必须是空）
 * @param mode 见 [ApiMode]：serve 支才走本契约的端点
 */
class CampusAttendanceApi(
    private val client: HttpClient,
    private val baseUrl: String = "",
    /** 见 [ApiMode]：这一份取数只认 serve 契约（§5.5）。 */
    private val mode: ApiMode = ApiMode.CAMPUS_API,
) : AttendanceSource {

    /**
     * 见类 KDoc：只剩统计 / 打卡流水两栏没端点（`leave` 已于 2026-10-15 批③-b / D7 ② 翻 `true`）。
     *
     * 假点：改这三档时**同时**要看屏上哪几栏会跟着出现 —— 翻 `leave` 会让「请假」那张页签
     * 真的可点（那是本轮要的），而 `stats` / `streams` 两档报 `false` 时屏上写的是「本端拿不到…」。
     */
    override val capabilities: AttendanceCapabilities = AttendanceCapabilities(
        stats = false,
        streams = false,
        leave = true,
    )

    /** 服务端定下来的当前学期号（`/records` 响应里的 `term`），问到过才记。 */
    private var currentTermBh: String? = null

    override suspend fun getStudentInfo(): Map<String, Any> {
        requireServe()
        // 契约 §5.5 不端 getStudentInfo：那要返回**本人的姓名**，而 §5.2 的例外清单里没有它。
        // 如实给空表 ⇒ `AttendanceUiState.studentName` 是空串 ⇒ 屏上姓名那一行不画（不是画个占位名字）。
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
        // 见类 KDoc：「当前学期」的口径在 `:data`（`getTermBh()`）—— 本端不复制那份口径，
        // 不带参数问一次 /records，读它用的那个学期。代价是多一发请求（只在第一次）。
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
            // 空值的参数不发（`serveData` 的判据，与旧路 `getData` 一致）：
            // `?term=` 省略 = 服务端自己算的当前学期，`?from=&to=` 省略 = `:data` 的兜底
            listOf(
                "term" to termBh.orEmpty(),
                "from" to startDate,
                "to" to endDate,
            ),
        )
        // 任何一次 /records 的响应都带着本次用的学期号（§5.5）⇒ 顺手记下来，getTermBh 就不用再问一发
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
                // 不投影（红线）：见类 KDoc
                teacher = "",
                status = WaterType.fromValue(row["status"].safeInt(WaterType.UNKNOWN.value)),
                date = row["date"].safeString(),
            )
        }
    }

    // ── 统计 / 打卡流水：契约 §5.5 没有端点 ────────────────────────────

    override suspend fun getKqtjCurrentWeek(): List<CourseAttendanceStat> = noStats()

    override suspend fun getKqtjByTime(startDate: String, endDate: String): List<CourseAttendanceStat> = noStats()

    override fun computeCourseStatsFromRecords(records: List<AttendanceWaterRecord>): List<CourseAttendanceStat> =
        noStats()

    override suspend fun getStreams(startDate: String, endDate: String): List<AttendanceStream> {
        requireServe()
        return emptyList()
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

    /**
     * 请假列表（那一栏的卡）。⚠️ `AttendanceViewModel.fetch` 对它是无保护取值
     *（`source.getLeavePage().records`）⇒ 取不到就是**异常**，不是空页（与本方法之前的形状同一个理由：
     * 拿空页冒充「真没有请假记录」是把缺口说成事实）。
     */
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
            // 本层回的就是**请求给的那两个**（/api/attendance/leave/records 那一格同一条口径）
            pageNum = data["page"].safeInt(pageNum),
            pageSize = data["size"].safeInt(pageSize),
        )
    }

    /**
     * 附件上传：**这一端没有端点**（`multipart/form-data`，而屏上也没有取文件槽位——
     * `pickEvidenceFiles` 用的是默认值）。屏不会调它：与桌面同一条。
     */
    override suspend fun uploadEvidence(fileName: String, contentType: String, bytes: ByteArray): JsonObject =
        throw UnsupportedOperationException(
            "serve 契约没有「上传请假附件」的端点（multipart/form-data）：docs/api-contract.md §5.5.1 ——" +
                "这一端的 `AttendanceScreen.pickEvidenceFiles` 给的是空表，屏上不会调到这里",
        )
    /**
     * 提交请假申请（**写**）。
     *
     * `requestId` 空串时由本实现补一枚（`:core` 没有 `java.util.UUID`，用 [secureRandomInt]
     * 拼一个与本机时间无关的随机串 —— 与搬迁前 `UUID.randomUUID()` 同一语义：**每次提交一次性的
     * 幂等键**，重发同一串不该变成两条申请）。
     *
     * `evidenceFiles` 原样发（本端屏上总是空表，见 [uploadEvidence]）。
     */
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
    /** 统计那一栏没有端点：**不拿流水在本机算一份**（那会是与 `:data` 分叉的第二份口径）。 */
    private fun noStats(): List<CourseAttendanceStat> {
        requireServe()
        return emptyList()
    }

    /**
     * 一次性幂等键（`:core` 没有 `java.util.UUID`）：与 [FeedbackStore] 同一个字母表与长度。
     *
     * 用 [secureRandomInt] 而不是本机时间：两个进程在同一毫秒里发出两串不该撞上（幂等键撞了
     * 就变成「第二次提交被当成重发」）。
     */
    private fun newRequestId(): String = buildString {
        repeat(16) { append(REQUEST_ID_ALPHABET[secureRandomInt(REQUEST_ID_ALPHABET.length)]) }
    }
}

/**
 * 请假那三个写动作共用的一枪：`serveAction` + 「不成就是异常」。
 *
 * 为什么要包一层：`serveAction` 把业务拒绝**返回**而不是抛（那是给 library / venue 那种要显示
 * 结果的写端点用的）；请假这一族屏上接的是 `FriendlyError.of(e, …)` 那一句（屏自己也不读 `message`），
 * 所以这里就地在端点这一层完成判定 —— 不把「没成」悄悄当成成了。
 */
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

/**
 * 一条请假记录（`LeaveRecord` 的十九格）。
 *
 * `leaveId` 从调用方传进来（它已经非空 —— 空的那一条在列表那一层就丢掉了）。
 */
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
            // 任课教师姓名在 §5.2 例外 ④ 里（我自己的课）⇒ 照 `:server` 端出来的原文收下
            teacherNames = course["teacherNames"].safeString(),
        )
    },
)
/** 学期列表（契约 §5.5）。 */
private const val TERMS_PATH = "/api/attendance/terms"
/** 某学期的考勤流水（`?term=` 学期号 / `?from=&to=` 日期，都可省略）。 */
private const val RECORDS_PATH = "/api/attendance/records"
