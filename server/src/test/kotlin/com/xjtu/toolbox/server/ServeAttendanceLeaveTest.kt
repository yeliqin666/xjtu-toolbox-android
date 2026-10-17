package com.xjtu.toolbox.server

import com.xjtu.toolbox.attendance.AttendanceFakeUpstream
import com.xjtu.toolbox.library.LibraryFakeUpstream
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray

/** `/api/attendance/leave*` 的契约测试（`docs/api-contract.md` §5.5.1）。 */
class ServeAttendanceLeaveTest {

    private var started: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    @AfterTest
    fun stopServer() {
        started?.stop(gracePeriodMillis = 100, timeoutMillis = 500)
        started = null
    }

    @Test
    fun `请假：六条端点未登录一律 401 · 参数形状 400 · 一枪都不打上游`() = withFakeProxy { fake ->
        val token = AccessToken.newToken()
        val session = ServeSession()
        val (server, http) = startEndpointServer(token) {
            sessionRoutes(session, token)
            attendanceRoutes(session)
        }
        started = server

        // 三读 + 三写都不套「没登录也能拿」：它们都要 `new_attendance` 站的会话
        for (path in listOf(WINDOW_PATH, "$PREVIEW_PATH?type=SICK", RECORDS_PATH, LEAVE_PATH)) {
            val anonymous = if (path == LEAVE_PATH) http.postJson(path, validCreateBody()) else http.get(path)
            assertEquals(401, anonymous.statusCode(), path)
            assertEquals(ApiErrors.UNAUTHORIZED, envelopeCode(anonymous), path)
            assertTrue(envelopeMessage(anonymous).orEmpty().contains("登录"), "401 要指路「先登录」：$path")
        }
        assertEquals(401, http.postJson(WITHDRAW_PATH, """{"leaveId":"L-假-0001"}""").statusCode())
        assertEquals(401, http.postJson(CANCEL_PATH, """{"leaveId":"L-假-0001"}""").statusCode())
        assertEquals(0, fake.attendance.createLeaveBodies.size, "未登录那几枪不该到取数层")

        assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

        // `?type=` 只认 SICK / PERSONAL（认不出的值不许静默当成私事假）
        val badType = http.get("$PREVIEW_PATH?type=VACATION")
        assertEquals(400, badType.statusCode())
        assertEquals(ApiErrors.BAD_REQUEST, envelopeCode(badType))
        assertTrue(envelopeMessage(badType).orEmpty().contains("SICK"), "400 要把合法取值列出来")
        assertEquals(400, http.get("$PREVIEW_PATH").statusCode(), "type 缺了也是 400（不能默认成某一种）")

        // 分页那两个：只认正整数
        for (query in listOf("?page=0", "?page=x", "?size=0", "?size=-3")) {
            val bad = http.get(RECORDS_PATH + query)
            assertEquals(400, bad.statusCode(), query)
            assertEquals(ApiErrors.BAD_REQUEST, envelopeCode(bad), query)
        }

        // 提交这一发：不是 JSON / 缺格 / 空白 / 时间不成形，一律 400
        val badBodies = listOf(
            "not json at all" to "请求体不是 JSON",
            """{"startTime":"2026-10-20 08:00:00","endTime":"2026-10-20 12:00:00","reason":"发烧","nextApproverUserId":"U-假-1001","requestId":"R1"}""" to "缺 type",
            """{"type":"SICK","endTime":"2026-10-20 12:00:00","reason":"发烧","nextApproverUserId":"U-假-1001","requestId":"R1"}""" to "缺 startTime",
            """{"type":"SICK","startTime":"2026-10-20 08:00:00","endTime":"2026-10-20 12:00:00","reason":"发烧","nextApproverUserId":"U-假-1001"}""" to "缺 requestId（幂等键）",
            """{"type":"SICK","startTime":"2026-10-20 08:00:00","endTime":"2026-10-20 12:00:00","reason":"   ","nextApproverUserId":"U-假-1001","requestId":"R1"}""" to "reason 空白",
            """{"type":"SICK","startTime":"2026-10-20T08:00","endTime":"2026-10-20 12:00:00","reason":"发烧","nextApproverUserId":"U-假-1001","requestId":"R1"}""" to "时间不是那一种写法",
            """{"type":"VACATION","startTime":"2026-10-20 08:00:00","endTime":"2026-10-20 12:00:00","reason":"发烧","nextApproverUserId":"U-假-1001","requestId":"R1"}""" to "type 不认识",
        )
        for ((body, why) in badBodies) {
            val bad = http.postJson(LEAVE_PATH, body)
            assertEquals(400, bad.statusCode(), why)
            assertEquals(ApiErrors.BAD_REQUEST, envelopeCode(bad), why)
            assertTrue(!envelopeMessage(bad).isNullOrBlank(), "$why ⇒ message 要给中文短句")
        }
        // 撤回 / 销假：leaveId 缺或空白 ⇒ 400
        for (path in listOf(WITHDRAW_PATH, CANCEL_PATH)) {
            assertEquals(400, http.postJson(path, "{}").statusCode(), path)
            assertEquals(400, http.postJson(path, """{"leaveId":"  "}""").statusCode(), path)
        }
        assertEquals(0, fake.attendance.createLeaveBodies.size, "参数不对的那几枪不该到上游")
    }

    @Test
    fun `请假三读：窗口六格 · 流程预览 · 列表一页；两类姓名按例外 ④ 端出来`() = withFakeProxy { fake ->
        val token = AccessToken.newToken()
        val session = ServeSession()
        val (server, http) = startEndpointServer(token) {
            sessionRoutes(session, token)
            attendanceRoutes(session)
        }
        started = server
        assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

        val window = http.get(WINDOW_PATH)
        assertEquals(200, window.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(window))
        assertNoIdentity(window.body(), "GET $WINDOW_PATH", IDENTITY)
        val windowData = envelopeData(window)
        assertEquals(
            setOf("semesterId", "academicYear", "semesterName", "startDate", "endDate", "maxStudentLeaveMinutes"),
            windowData.keys,
        )
        assertEquals(AttendanceFakeUpstream.TERM_CURRENT_BH, windowData.optString("semesterId"))
        assertEquals(1440, windowData.optInt("maxStudentLeaveMinutes"), "单次上限是分钟数")

        val preview = http.get("$PREVIEW_PATH?type=SICK")
        assertEquals(200, preview.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(preview))
        assertNoIdentity(preview.body(), "GET $PREVIEW_PATH", IDENTITY)
        val previewData = envelopeData(preview)
        assertEquals(setOf("nextNodeNo", "nextApprovers", "nodes"), previewData.keys)
        assertEquals(1, previewData.optInt("nextNodeNo"))
        val approver = previewData.getValue("nextApprovers").jsonArray.single() as JsonObject
        assertEquals(setOf("userId", "name"), approver.keys)
        assertEquals("U-假-1001", approver.optString("userId"), "下拉框的句柄是 userId（提交时要原样带回）")
        assertEquals(
            AttendanceFakeUpstream.LEAVE_APPROVER_NAME,
            approver.optString("name"),
            "审批人姓名在 §5.2 例外 ④ 里 ⇒ 真投影（屏上那个下拉框画的就是它）",
        )
        val node = previewData.getValue("nodes").jsonArray.single() as JsonObject
        assertEquals(setOf("nodeNo", "nodeRole", "nodeName", "configured", "approvers"), node.keys)
        assertEquals(true, node.optBoolean("configured"))

        val records = http.get(RECORDS_PATH)
        assertEquals(200, records.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(records))
        assertNoIdentity(records.body(), "GET $RECORDS_PATH", IDENTITY)
        val page = envelopeData(records)
        assertEquals(setOf("page", "size", "total", "records"), page.keys, "分页形状（§4）")
        assertEquals(1, page.optInt("page"), "请求没给页码就是 1")
        assertEquals(20, page.optInt("size"), "= `:core` 那一个默认页大小")
        assertEquals(1, page.optInt("total"))
        val row = page.getValue("records").jsonArray.single() as JsonObject
        assertEquals(AttendanceFakeUpstream.LEAVE_ID, row.optString("leaveId"))
        assertEquals("SICK", row.optString("leaveType"))
        assertEquals(true, row.optBoolean("withdrawable"), "能不能撤回是 `:data` 的判据，原样端出来")
        assertEquals(false, row.optBoolean("cancellable"))
        // 附件那一格在夹具里就是空的：形状在、条数是 0（不是编一条）；课程那一格有 1 条
        assertEquals(0, row.getValue("evidenceFiles").jsonArray.size)
        val course = row.getValue("courses").jsonArray.single() as JsonObject
        assertEquals(
            setOf("courseId", "courseName", "courseDate", "startSection", "endSection", "teacherNames"),
            course.keys,
            "六格与 `:core` 的 `LeaveCourse` 逐字对齐",
        )
        assertEquals(1, course.optInt("startSection"), "节次（不是时刻）")
        assertEquals(
            AttendanceFakeUpstream.LEAVE_COURSE_TEACHER,
            course.optString("teacherNames"),
            "任课教师姓名在 §5.2 例外 ④ 里 ⇒ 真投影（例外的根据是「我自己的课」）",
        )

        // 三读都真打了上游（`/leaves/page` 那一发是 POST）
        // 三读那三发都从同一个取数实例走（`new_attendance` 那一站的会话由 `:data` 注入令牌）
        assertTrue(fake.attendance.lastBusinessToken != null, "业务请求带上了 `:data` 那一层注入的令牌")
    }

    /** 红线（§5.2 例外 ④）：请假响应里只允许这两类真人姓名。 */
    @Test
    fun `请假红线：只允许这两类姓名，别的一律不许`() = withFakeProxy { fake ->
        val token = AccessToken.newToken()
        val session = ServeSession()
        val (server, http) = startEndpointServer(token) {
            sessionRoutes(session, token)
            attendanceRoutes(session)
        }
        started = server
        assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

        val preview = http.get("$PREVIEW_PATH?type=SICK")
        val records = http.get(RECORDS_PATH)
        assertEquals(200, preview.statusCode())
        assertEquals(200, records.statusCode())

        // 「只开这两类」：各在自己那一格上…
        assertTrue(
            preview.body().contains(AttendanceFakeUpstream.LEAVE_APPROVER_NAME),
            "审批人姓名（例外 ④ 的第①类）要端出来：${preview.body()}",
        )
        assertTrue(
            records.body().contains(AttendanceFakeUpstream.LEAVE_COURSE_TEACHER),
            "任课教师姓名（例外 ④ 的第②类）要端出来：${records.body()}",
        )
        // …而且不在别格上（清单是两类，不是「有名字就放行」）
        assertTrue(
            !preview.body().contains(AttendanceFakeUpstream.LEAVE_COURSE_TEACHER),
            "任课教师姓名不属于 preview 那一格：${preview.body()}",
        )
        assertTrue(
            !records.body().contains(AttendanceFakeUpstream.LEAVE_APPROVER_NAME),
            "审批人姓名不属于 records 那一格（夹具那条请假单的审批节点是空的）：${records.body()}",
        )

        // 「别的一律不许」：六个响应体逐个过钉子组（且都是在夹具里明明有那些值的前提下）
        val responses = listOf(
            "GET $WINDOW_PATH" to http.get(WINDOW_PATH),
            "GET $PREVIEW_PATH" to preview,
            "GET $RECORDS_PATH" to records,
            "POST $LEAVE_PATH" to http.postJson(LEAVE_PATH, validCreateBody()),
            "POST $WITHDRAW_PATH" to http.postJson(
                WITHDRAW_PATH,
                """{"leaveId":"${AttendanceFakeUpstream.LEAVE_ID}","reason":"学生撤回","requestId":"R2"}""",
            ),
            "POST $CANCEL_PATH" to http.postJson(
                CANCEL_PATH,
                """{"leaveId":"${AttendanceFakeUpstream.LEAVE_ID}","requestId":"R3"}""",
            ),
        )
        for ((where, response) in responses) {
            assertEquals(200, response.statusCode(), where)
            assertNoIdentity(response.body(), where, IDENTITY)
        }

        // 两个写响应的形状里根本没有姓名的位置（只回 「{ok, message}」/「{leaveId}」）
        assertTrue(!responses.last().second.body().contains(AttendanceFakeUpstream.LEAVE_APPROVER_NAME))
        assertTrue(!responses.last().second.body().contains(AttendanceFakeUpstream.LEAVE_COURSE_TEACHER))
        assertEquals(1, fake.attendance.createLeaveBodies.size, "提交那一发真打了上游")
    }

    @Test
    fun `请假三写：提交的八格逐格对上 · 撤回与销假都是 ok`() = withFakeProxy { fake ->
        val token = AccessToken.newToken()
        val session = ServeSession()
        val (server, http) = startEndpointServer(token) {
            sessionRoutes(session, token)
            attendanceRoutes(session)
        }
        started = server
        assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

        val created = http.postJson(LEAVE_PATH, validCreateBody())
        assertEquals(200, created.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(created))
        assertNoIdentity(created.body(), "POST $LEAVE_PATH", IDENTITY)
        val createdData = envelopeData(created)
        assertEquals(setOf("leaveId"), createdData.keys, "提交的 data 只有上游给的那个单号")
        assertEquals(AttendanceFakeUpstream.LEAVE_ID, createdData.optString("leaveId"))

        // 上游收到的八格（`:data` 的 `LeaveApi.createLeave` 拼的那一份，一个不多一个不少）
        assertEquals(1, fake.attendance.createLeaveBodies.size, "提交真打了上游")
        val body = fake.attendance.createLeaveBodies.single()
        for (needle in listOf(
            "\"requestId\":\"REQ-fake-1\"",
            "\"leaveType\":\"SICK\"",
            "\"startTime\":\"2026-10-20 08:00:00\"",
            "\"endTime\":\"2026-10-20 12:00:00\"",
            "\"reason\":\"发烧（假样例）\"",
            "\"nextApproverUserId\":\"U-假-1001\"",
            "\"evidenceFiles\":[]",
        )) {
            assertTrue(body.contains(needle), "上游请求体里少了 $needle：$body")
        }

        val withdrawn = http.postJson(WITHDRAW_PATH, """{"leaveId":"${AttendanceFakeUpstream.LEAVE_ID}","reason":"学生撤回","requestId":"R2"}""")
        assertEquals(200, withdrawn.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(withdrawn))
        assertNoIdentity(withdrawn.body(), "POST $WITHDRAW_PATH", IDENTITY)
        assertEquals(setOf("ok", "message"), envelopeData(withdrawn).keys, "写端点的形状与 §5.3.1 那两个一致")

        val cancelled = http.postJson(CANCEL_PATH, """{"leaveId":"${AttendanceFakeUpstream.LEAVE_ID}","requestId":"R3"}""")
        assertEquals(200, cancelled.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(cancelled))
        assertNoIdentity(cancelled.body(), "POST $CANCEL_PATH", IDENTITY)
        assertEquals(setOf("ok", "message"), envelopeData(cancelled).keys)
        assertFalse(envelopeMessage(cancelled).isNullOrBlank(), "message 是给 curl / 排障看的中文短句")
    }

    @Test
    fun `请假：上游拒收提交 ⇒ 502 且把上游那句中文交给用户`() = withFakeProxy { fake ->
        val token = AccessToken.newToken()
        val session = ServeSession()
        val (server, http) = startEndpointServer(token) {
            sessionRoutes(session, token)
            attendanceRoutes(session)
        }
        started = server
        assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

        fake.attendance.leaveCreateRejected = true
        val rejected = http.postJson(LEAVE_PATH, validCreateBody())
        assertEquals(502, rejected.statusCode())
        assertEquals(ApiErrors.UPSTREAM_FAILED, envelopeCode(rejected))
        assertTrue(
            envelopeMessage(rejected).orEmpty().contains(AttendanceFakeUpstream.LEAVE_CREATE_REJECTED_MSG),
            "上游那句中文要交回用户：${envelopeMessage(rejected)}",
        )
        assertEquals(1, fake.attendance.createLeaveBodies.size, "这一枪确实打到了上游")

        // 夹具恢复 ⇒ 立刻能提交（证明上一条是上游那一档，不是配置漂了）
        fake.attendance.leaveCreateRejected = false
        assertEquals(200, http.postJson(LEAVE_PATH, validCreateBody()).statusCode())
        assertEquals(2, fake.attendance.createLeaveBodies.size)
    }

    // ── 脚手架 ─────────────────────────────────────────────────────────

    private fun validCreateBody(): String =
        """{"type":"SICK","startTime":"2026-10-20 08:00:00","endTime":"2026-10-20 12:00:00",""" +
            """"reason":"${AttendanceFakeUpstream.LEAVE_REASON}",""" +
            """"nextApproverUserId":"U-假-1001","requestId":"REQ-fake-1"}"""

    private companion object {
        const val WINDOW_PATH = "/api/attendance/leave/window"
        const val PREVIEW_PATH = "/api/attendance/leave/preview"
        const val RECORDS_PATH = "/api/attendance/leave/records"
        const val LEAVE_PATH = "/api/attendance/leave"
        const val WITHDRAW_PATH = "/api/attendance/leave/withdraw"
        const val CANCEL_PATH = "/api/attendance/leave/cancel"

        val LOGIN_BODY =
            """{"username":"${LibraryFakeUpstream.USERNAME}","password":"${LibraryFakeUpstream.PASSWORD}"}"""

        /** §5.2 的红线钉子组（全是夹具里的编造值，不是真人） */
        val IDENTITY = listOf(
            "studentId",
            "学号",
            "姓名",
            AttendanceFakeUpstream.NAME,
            AttendanceFakeUpstream.SNO,
            AttendanceFakeUpstream.TEACHER_NAME,
        )
    }
}
