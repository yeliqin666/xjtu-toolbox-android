package com.xjtu.toolbox.attendance

import kotlinx.serialization.json.JsonObject

/**
 * 考勤的**取数端口**：屏与 ViewModel 都在 `:core`，两端各自填同一批字段。
 *
 * 方法就是搬迁前 `AttendanceApi` + `LeaveApi`（`:app`，okhttp 抓 `kq.xjtu.edu.cn`）那批动作的
 * 并集 —— 名称、参数、返回形状与搬迁前逐字相同，所以「姓名 → 学期列表 → 选学期 → 流水/统计/请假
 * → 撤回/销假」的编排（[AttendanceViewModel]）与请假表单的流程预览 / 附件上传 / 提交在两端是
 * 同一条代码路径。
 *
 * | 端 | 实现 | 备注 |
 * |---|---|---|
 * | Android / 桌面 | `:data` 的 `AppAttendanceSource`（包住 `AttendanceApi` + `LeaveApi`） | 走自己的站点会话（`new_attendance`） |
 * | Web | `:core` 的 `CampusAttendanceApi`（`ApiMode.SERVE`） | **只有学期列表 + 流水表**：`GET /api/attendance/terms` + `/records`。另外三栏（统计 / 打卡流水 / 请假）契约 §5.5 没有端点 ⇒ [AttendanceCapabilities] 三档全报 `false` |
 *
 * ## 为什么 IO 调度不在端口上
 *
 * `:data` 那一份是阻塞式 okhttp，实现里自己 `withContext(Dispatchers.IO)`（搬迁前是 VM 里
 * `withContext(Dispatchers.IO)`，同一层同一个调度器）。VM 只在自己的可取消作用域里调用
 *（与 `CouponSource` / `FitnessSource` 同一条约定）。
 *
 * ## 会话失效
 *
 * 这些方法都可能抛 `SessionExpiredFailure`（`:app`/`:data` 的 `AuthExpiredException` 是它的
 * 实现）—— VM 与屏据此回登录/退回，与评教/成绩/校园卡同一条缝。
 */
/**
 * 本端这一份取数**真拿得到哪几栏**：屏按它决定那一栏画什么。
 *
 * 默认三档都是 `true` = 搬迁前的行为（`:data` 的 `AppAttendanceSource` 三栏都在）。serve 契约
 * §5.5 只端了「学期列表 + 考勤流水」⇒ `:web` 那一份三档全报 `false`。
 *
 * 报了 `false` 的那几栏，屏上**不画「暂无…」**（那会被读成「真没有」，是把缺口说成了事实），
 * 而是画一句指名道姓的缺口说明（说清服务端契约里没有对应端点）。
 *
 * 「学生姓名」不在这里：契约 §5.5 不端 `getStudentInfo`（姓名是红线）⇒ 那一格由空串自然不画
 *（`AttendanceUiState.studentName.isNotBlank()`），没有开关可翻。
 */
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

    /** 学生信息（只有 `name` 要被 VM 用到，其余键保持搬迁前的样子）。 */
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
        /** 幂等键。`:core` 没有 `java.util.UUID`（wasm 编不过），空串由实现方补一枚（与搬迁前 `UUID.randomUUID()` 同一语义）。 */
        requestId: String = "",
    ): String

    suspend fun withdrawLeave(leaveId: String, reason: String, requestId: String = "")

    suspend fun cancelLeave(leaveId: String, requestId: String = "")
}