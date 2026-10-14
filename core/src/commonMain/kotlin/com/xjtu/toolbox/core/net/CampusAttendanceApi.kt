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
import com.xjtu.toolbox.util.arr
import com.xjtu.toolbox.util.safeInt
import com.xjtu.toolbox.util.safeString
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonObject

/**
 * 考勤（`kq.xjtu.edu.cn`）的 **serve 版取数**：给 `:web` 用，实现 [AttendanceSource]。
 *
 * | 端点 | 本类实现的方法 |
 * |---|---|
 * | `GET /api/attendance/terms` | [getTermList] |
 * | `GET /api/attendance/records` | [getTermBh]（不带参数问一次，读响应里的 `term`）+ [getWaterRecords] |
 *
 * 契约 §5.5；`:server` 那一侧是 `AttendanceRoutes.kt`（薄包装 `:data` 的 `AppAttendanceSource`）。
 *
 * ## 只有「学期选择 + 流水表」那一栏（这张屏的**如实缺口**）
 *
 * | 端口方法 | 端不端 |
 * |---|---|
 * | [getTermList] / [getTermBh] / [getWaterRecords] | **端**（上表两个端点） |
 * | [getStudentInfo] | 不端：姓名是红线（人不端名，也不端别的东西）⇒ 如实给空表 |
 * | [getKqtjCurrentWeek] / [getKqtjByTime] / [computeCourseStatsFromRecords] | 不端：统计那一栏 |
 * | [getStreams] | 不端：打卡原始流水那一栏 |
 * | 请假那一半（窗口 / 流程预览 / 列表 / 附件 / 提交 / 撤回 / 销假） | 一律不端：请假 / 销假是写路径 |
 *
 * 于是 [capabilities] 三档全报 `false`，屏上那三栏画的是「本端拿不到 …」而不是「暂无 …」
 *（后者会把缺口说成事实）。
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
 * ## `teacher` 不投影（红线）
 *
 * 考勤记录里的上课教师名是**姓名**，契约 §5.2 的例外清单里没有它 ⇒ 本端如实留空
 *（[AttendanceWaterRecord.teacher] 给空串），屏上也不画那一格。
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

    /** 见类 KDoc：统计 / 打卡流水 / 请假三栏契约 §5.5 都没有端点。 */
    override val capabilities: AttendanceCapabilities = AttendanceCapabilities(
        stats = false,
        streams = false,
        leave = false,
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

    // ── 请假那一半：写路径，§5.5 一律不端 ─────────────────────────────

    override suspend fun getSemesterWindow(): LeaveSemesterWindow = noLeave("请假学期窗口")

    override suspend fun getFlowPreview(type: LeaveType): LeaveFlowPreview = noLeave("审批流程预览")

    /**
     * 请假列表那一栏：契约里没有端点 ⇒ 如实给**空页**（`capabilities.leave = false`，
     * 屏上那一栏写的是「本端拿不到请假记录」）。
     *
     * ⚠️ 这里**不能抛**：`AttendanceViewModel.fetch` 对它是无保护取值（`source.getLeavePage().records`），
     * 抛了会把整屏拖成错误页。`pageNum` / `pageSize` 是请求给的那两个值，原样回（不是编一个页码）。
     */
    override suspend fun getLeavePage(pageNum: Int, pageSize: Int, status: String, leaveType: String): LeavePage {
        requireServe()
        return LeavePage(records = emptyList(), total = 0, pageNum = pageNum, pageSize = pageSize)
    }

    override suspend fun uploadEvidence(fileName: String, contentType: String, bytes: ByteArray): JsonObject =
        noLeave("附件上传")

    override suspend fun createLeave(
        type: LeaveType,
        startTime: String,
        endTime: String,
        reason: String,
        nextApproverUserId: String,
        evidenceFiles: List<JsonObject>,
        requestId: String,
    ): String = noLeave("提交请假申请")

    override suspend fun withdrawLeave(leaveId: String, reason: String, requestId: String) = noLeave("撤回请假")

    override suspend fun cancelLeave(leaveId: String, requestId: String) = noLeave("销假")

    private fun requireServe() {
        if (mode != ApiMode.SERVE) serveOnly("考勤取数", "$TERMS_PATH + $RECORDS_PATH")
    }

    /** 统计那一栏没有端点：**不拿流水在本机算一份**（那会是与 `:data` 分叉的第二份口径）。 */
    private fun noStats(): List<CourseAttendanceStat> {
        requireServe()
        return emptyList()
    }

    private fun noLeave(what: String): Nothing = throw UnsupportedOperationException(
        "serve 契约没有「$what」的端点：请假 / 销假都是写路径（docs/api-contract.md §5.5 一律不端）——" +
            "这一屏只读得了考勤流水",
    )
}

/** 学期列表（契约 §5.5）。 */
private const val TERMS_PATH = "/api/attendance/terms"

/** 某学期的考勤流水（`?term=` 学期号 / `?from=&to=` 日期，都可省略）。 */
private const val RECORDS_PATH = "/api/attendance/records"
