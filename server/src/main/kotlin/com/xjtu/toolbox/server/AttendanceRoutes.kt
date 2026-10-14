package com.xjtu.toolbox.server

import com.xjtu.toolbox.attendance.AppAttendanceSource
import com.xjtu.toolbox.attendance.AttendanceWaterRecord
import com.xjtu.toolbox.attendance.TermInfo
import com.xjtu.toolbox.auth.SiteSession
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/**
 * `/api/attendance*` —— 考勤的**两个只读端点**（`docs/api-contract.md` §5.5），走 `:data` 的
 * [AppAttendanceSource]（包住 `AttendanceApi`，解析口径一行不重做）。
 *
 * | 端点 | 对应端口方法 |
 * |---|---|
 * | `GET /api/attendance/terms` | `AttendanceSource.getTermList()`（`TermInfo`） |
 * | `GET /api/attendance/records` | `AttendanceSource.getTermBh()` + `getWaterRecords(bh, from, to)` |
 *
 * ## 端了什么、刻意不端什么
 *
 * | 端口方法 | 本轮端不端 |
 * |---|---|
 * | `getTermList` / `getTermBh` / `getWaterRecords` | **端**（两个端点） |
 * | `getStudentInfo` | 不端：它要 `name` 那格是**姓名**（登录用户本人）——契约 §5.2 红线，例外清单里没有它 |
 * | `getKqtjCurrentWeek` / `getKqtjByTime` / `computeCourseStatsFromRecords` | 不端：屏上「按课程统计」那一栏（TODO，见下） |
 * | `getStreams` | 不端：打卡原始流水那一栏（TODO，见下） |
 * | 请假那一半（`getSemesterWindow` / `getFlowPreview` / `getLeavePage` / `uploadEvidence` / `createLeave` / `withdrawLeave` / `cancelLeave`） | **一律不端**：请假 / 销假是**写路径**（本批只端只读） |
 *
 * ## 逐字段映射（判据是真源：`:data` 的解析 + `:core` 的模型）
 *
 * - **terms**：`TermInfo(bh, name, code, startDate, endDate)` 原样投影，顺序 = `:data` 的顺序
 *   （`sortedByDescending { startDate }`，当前学期在第一个 —— `getTermBh` 的取法就是「第 0 个」）；
 *   `name` 是 `:data` 拼好的人类可读名（`"$year $semesterName"` 那一套）、`code` 是它算的
 *   教务风格学期码（`2026-2027-1`，`TermCodeMapper`），本层不再拼第二份；
 * - **records**：命中 `AttendanceWaterRecord` 的第一批字段。两处刻意的改名（与契约里
 *   `amount ← signed` 同一种做法）：`startSection ← startTime`、`endSection ← endTime`
 *   —— `:core` 那两个字段名里的 `Time` 其实是**节次**（`Int`，`1..11`），照抄会让消费方以为
 *   是时刻；`term ← termString`（学期码，取不到码时 `:data` 兜底成学期名）。
 * - `status` 是 `WaterType.value`（1 正常 / 2 迟到 / 3 缺勤 / 5 请假），`statusText` 是它配套的
 *   中文。⚠️ **`-1` = 未识别的状态码，必须按 `statusText` 的「未知」显示** —— `:data` 的
 *   `WaterType` KDoc 把这条写死了（不许悄悄算成正常，那会把统计数字做成好看的假数字）。
 * - ⚠️ **`teacher` 不投影**：考勤记录里的上课教师名是**姓名**，而契约 §5.2 的例外清单里没有它
 *   （例外只有 ① `fitness/score` 本人姓名/学号 ② 黄页/教师检索的通讯录 ③ `emptyroom/rooms`
 *   实时档的上课教师名）⇒ 本层按红线不端。`:web` 的考勤屏若要显示教师名，得先拍板把它加进
 *   例外清单（那是改契约，不是改这一层）。
 *
 * ## 请求参数与错误码
 *
 * - `?term=`（省略 = 当前学期，`getTermBh()`，与 `:app`/桌面同一条取法）——**学期号**（`bh`，
 *   形如 `646`，取自 `/api/attendance/terms`）；给了但不是纯数字 ⇒ 400；
 * - `?from=&to=`（省略时用 `:data` 的兜底：`from` = 该学期起、`to` = 今天）——`YYYY-MM-DD`；
 *   给了但不成形 ⇒ 400。**响应不回这两格**：兜底口径在 `:data`（`AttendanceApi.getWaterRecords`），
 *   本层不复制一份（要范围就看行的 `date`）；
 * - 401 令牌对但没会话 · 502 上游 / 网络故障（`FriendlyError` 中文短句）。
 *
 * ## TODO（本轮未端，下一批要就先说）
 *
 * `getStudentInfo`（姓名那格过不了红线）/ 统计栏（`getKqtjCurrentWeek` 与
 * `computeCourseStatsFromRecords`）/ 打卡流水（`getStreams`）三块都还没有端点：serve 模式下
 * 考勤屏目前只有「学期选择 + 流水表」。
 *
 * @param session 会话装配；考勤站点（`new_attendance`）从它那里取。
 */
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
    /** 本次查询用的学期号（请求没给就是 `:data` 算出来的当前学期）。 */
    val term: String,
    val count: Int,
    val records: List<AttendanceRecordDto>,
)

/**
 * 一条考勤流水的线上形状。
 *
 * 改名两处（`startSection ← startTime`、`endSection ← endTime`）与**不投影 `teacher`** 的理由
 * 见文件头 KDoc。
 */
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
