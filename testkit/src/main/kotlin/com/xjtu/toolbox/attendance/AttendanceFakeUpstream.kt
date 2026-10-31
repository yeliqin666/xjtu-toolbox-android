package com.xjtu.toolbox.attendance

import com.sun.net.httpserver.HttpExchange
import java.util.concurrent.atomic.AtomicInteger

/** 假的考勤系统（`bk-kq.xjtu.edu.cn/sa`，本科生业务站）。 */
class AttendanceFakeUpstream {

    companion object {
        /** 业务站（`AttendanceLogin.UNDERGRAD_HOST` / BASE_URL 的 host）。 */
        const val HOST = "bk-kq.xjtu.edu.cn"

        /** 直连业务站的登录入口。 */
        const val ENTRY_PATH = "/sa/auth/cas/login/student-pc"

        // ── 夹具样本 ──

        /** CAS 回调里换票要的两个参数（第二个由 CAS 夹具签成 `ticket=ST-n`）。 */
        const val REQUEST_ID = "REQ-fake-1"

        const val TOKEN = "KQ-fake-business-token"

        const val NAME = "示例同学（假）"
        const val SNO = "2021000001"
        const val COLLEGE = "示例学院（假）"

        /** 两个学期：第一个是当前学期（接口按当前在前排）。 */
        const val TERM_CURRENT_BH = "646"
        const val TERM_CURRENT_CODE = "2026-2027-1"
        const val TERM_CURRENT_NAME = "2026-2027 第一学期"
        const val TERM_LAST_BH = "645"
        const val TERM_LAST_CODE = "2025-2026-2"

        const val COURSE_NAME = "光电子学（假）"
        const val COURSE_CODE = "PHYS999999"
        /**
         * 上课教师名（**姓名**）：考勤流水里带着它。按契约 §5.2 例外 ⑤（2026-10-19 批准），
         * `/api/attendance/records` 现在**投影**这一格；其余端点仍拿它当红线的钉子。
         */
        const val TEACHER_NAME = "高宏（假）"
        const val ROOM = "西2楼-假-307"

        const val LEAVE_ID = "L-假-0001"
        const val LEAVE_REASON = "发烧（假样例）"

        /** 请假流程预览里那位审批人的姓名（编造）。 */
        const val LEAVE_APPROVER_NAME = "辅导员甲（假）"

        /** 请假单里那节课的任课教师姓名（编造；与考勤流水那条 `TEACHER_NAME` 是两个不同的人）。 */
        const val LEAVE_COURSE_TEACHER = "李四（假）"

        const val EVIDENCE_ID = "E-假-0001"

        /** 上游拒收一次请假提交时给的那句 message（[leaveCreateRejected] 打开后用）。 */
        const val LEAVE_CREATE_REJECTED_MSG = "当前不在可请假时段（夹具）"
    }

    /** 打开后：提交请假那一发回 `{"code":500,"message":…}`（HTTP 仍是 200） */
    @Volatile
    var leaveCreateRejected: Boolean = false

    // ── 断言用：请求原文与计数 ──

    /** 换票请求的 body（`loginRequestId` / `ticket` 两个参数钉在这里）。 */
    val exchangeBodies: MutableList<String> = mutableListOf()

    /** 考勤流水分页请求的 body（学期、起止日期钉在这里）。 */
    val recordsBodies: MutableList<String> = mutableListOf()

    /** 请假提交请求的 body。 */
    val createLeaveBodies: MutableList<String> = mutableListOf()

    /** 最近一次业务请求带的 `X-Business-Token` / `X-System`。 */
    @Volatile
    var lastBusinessToken: String? = null
        private set

    @Volatile
    var lastSystemHeader: String? = null
        private set

    /** 开课列表 / 流水 / 统计被要了几次（多次重试会重复计数，断言用 >=）。 */
    val termCalls = AtomicInteger(0)
    val recordsCalls = AtomicInteger(0)

    fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val query = exchange.requestURI.query.orEmpty()
        when {
            path == ENTRY_PATH && exchange.requestMethod == "GET" && "ticket=" in query && "loginRequestId=" !in query ->
                // CAS 签完票回来：把 `loginRequestId` 补上（真站点的回调就是这两个参数一起给）
                redirect(exchange, "https://$HOST$ENTRY_PATH?loginRequestId=$REQUEST_ID&ticket=${param(query, "ticket")}")
            path == ENTRY_PATH && exchange.requestMethod == "GET" && "ticket=" !in query ->
                // 入口第一次进来：302 到统一认证（service = 入口自己，CAS 签完票带 ticket 回来）——
                // 与真站点同一条链（`AttendanceLogin` 的直连路径不开门户）。
                redirect(
                    exchange,
                    "http://${com.xjtu.toolbox.library.LibraryFakeUpstream.CAS_HOST}/cas/login?service=" +
                        encode("https://$HOST$ENTRY_PATH"),
                )
            path == ENTRY_PATH && exchange.requestMethod == "GET" -> {
                // 双参数齐了：落在这里就是消费点（`consumeLanding` 从 finalUrl 读参）
                respondHtml(exchange, "<html><body>ack</body></html>")
            }
            path == "/sa/auth/cas/exchange" && exchange.requestMethod == "POST" -> {
                exchangeBodies += exchange.requestBody.readBytes().decodeToString()
                respondJson(exchange, """{"code":0,"msg":"ok","data":{"tokenValue":"$TOKEN"}}""")
            }
            path == "/sa/student/home" -> respondJson(exchange, """{"code":0,"data":{}}""")
            path == "/sa/student/profile" -> respondJson(
                exchange,
                """{"code":0,"data":{"user":{"name":"$NAME","studentNo":"$SNO","collegeName":"$COLLEGE"}}}""",
            )
            path == "/sa/student/service/timetable/semesters" -> {
                termCalls.incrementAndGet()
                respondJson(exchange, termsJson)
            }
            path == "/sa/student/pc/home/attendance-statistics" -> respondJson(exchange, statsJson)
            path == "/sa/student/pc/attendance-records/page" && exchange.requestMethod == "POST" -> {
                recordsBodies += exchange.requestBody.readBytes().decodeToString()
                recordsCalls.incrementAndGet()
                captureHeaders(exchange)
                respondJson(exchange, recordsPageJson)
            }
            path == "/sa/student/pc/attendance-streams/page" && exchange.requestMethod == "POST" -> {
                exchange.requestBody.readBytes()
                captureHeaders(exchange)
                respondJson(exchange, streamsJson)
            }
            path == "/sa/student/leaves/semester-window" -> respondJson(exchange, windowJson)
            path == "/sa/student/leaves/flow-preview" && exchange.requestMethod == "GET" -> {
                captureHeaders(exchange)
                respondJson(exchange, flowPreviewJson)
            }
            path == "/sa/student/leaves/page" && exchange.requestMethod == "POST" -> {
                exchange.requestBody.readBytes()
                captureHeaders(exchange)
                respondJson(exchange, leavePageJson)
            }
            path == "/sa/student/leaves/evidence/upload" && exchange.requestMethod == "POST" -> {
                exchange.requestBody.readBytes()
                captureHeaders(exchange)
                respondJson(exchange, """{"code":0,"data":{"evidenceId":"$EVIDENCE_ID","fileName":"票（假）.pdf"}}""")
            }
            path == "/sa/student/leaves" && exchange.requestMethod == "POST" -> {
                createLeaveBodies += exchange.requestBody.readBytes().decodeToString()
                if (leaveCreateRejected) {
                    respondJson(exchange, """{"code":500,"message":"$LEAVE_CREATE_REJECTED_MSG"}""")
                    return
                }
                captureHeaders(exchange)
                respondJson(exchange, """{"code":0,"data":{"leaveId":"$LEAVE_ID"}}""")
            }
            path.startsWith("/sa/student/leaves/") && (exchange.requestMethod == "POST") &&
                ("/withdraw" in path || "/cancel" in path) -> {
                exchange.requestBody.readBytes()
                captureHeaders(exchange)
                respondJson(exchange, """{"code":0,"data":{}}""")
            }
            else -> respond(exchange, 404, "text/plain", "夹具没有这条路径：$path".toByteArray())
        }
    }

    private fun captureHeaders(exchange: HttpExchange) {
        lastBusinessToken = exchange.requestHeaders.getFirst("X-Business-Token")
        lastSystemHeader = exchange.requestHeaders.getFirst("X-System")
    }

    // ── 页面原文（改一处所有消费者一起动）─────────────────────────────

    private val termsJson = """
        {"code":0,"msg":"ok","data":[
          {"semesterId":"$TERM_CURRENT_BH","academicYear":"2026-2027","semesterName":"第一学期",
           "startDate":"2026-09-14","endDate":"2027-02-21"},
          {"semesterId":"$TERM_LAST_BH","academicYear":"2025-2026","semesterName":"第二学期",
           "startDate":"2026-02-23","endDate":"2026-07-05"}
        ]}
    """.trimIndent()

    private val recordsPageJson = """
        {"code":0,"msg":"ok","data":{"total":2,"rows":[
          {"resultId":"r-假-1","semesterId":"$TERM_CURRENT_BH","courseName":"$COURSE_NAME","courseCode":"$COURSE_CODE",
           "courseDate":"2026-09-16","startSection":1,"endSection":2,"classroomName":"$ROOM",
           "teacherName":"$TEACHER_NAME","attendanceStatus":"NORMAL"},
          {"resultId":"r-假-2","semesterId":"$TERM_CURRENT_BH","courseName":"$COURSE_NAME","courseCode":"$COURSE_CODE",
           "courseDate":"2026-09-18","startSection":3,"endSection":4,"classroomName":"$ROOM",
           "teacherName":"$TEACHER_NAME","attendanceStatus":"LATE"}
        ]}}
    """.trimIndent()

    private val streamsJson = """
        {"code":0,"msg":"ok","data":{"total":1,"rows":[
          {"id":"s-假-1","classroomName":"$ROOM","collectTime":"2026-09-16 08:05:00","effective":true}
        ]}}
    """.trimIndent()

    private val statsJson = """
        {"code":0,"msg":"ok","data":[
          {"courseName":"$COURSE_NAME","courseCode":"$COURSE_CODE","normalCount":1,"lateCount":1,
           "absenceCount":0,"leaveEarlyCount":0,"leaveCount":0,"total":2}
        ]}
    """.trimIndent()

    private val windowJson = """
        {"code":0,"msg":"ok","data":{"semesterId":"$TERM_CURRENT_BH","academicYear":"2026-2027",
          "semesterName":"第一学期","startDate":"2026-09-14","endDate":"2027-02-21","maxStudentLeaveMinutes":1440}}
    """.trimIndent()

    private val flowPreviewJson = """
        {"code":0,"msg":"ok","data":{
          "nextNodeNo":1,
          "nextApprovers":[{"approverUserId":"U-假-1001","approverName":"$LEAVE_APPROVER_NAME"}],
          "nodes":[{"nodeNo":1,"nodeRole":"辅导员","nodeName":"辅导员审批","configured":true,
                    "approvers":[{"approverUserId":"U-假-1001","approverName":"$LEAVE_APPROVER_NAME"}]}]}}
    """.trimIndent()

    private val leavePageJson = """
        {"code":0,"msg":"ok","data":{"total":1,"pageNum":1,"pageSize":20,"rows":[
          {"leaveId":"$LEAVE_ID","leaveType":"SICK","reason":"$LEAVE_REASON","status":"PENDING",
           "displayStatus":"PENDING","approvalStatus":"PENDING","cancelStatus":"",
           "startTime":"2026-09-20 08:00:00","endTime":"2026-09-20 18:00:00",
           "applyTime":"2026-09-19 20:00:00","durationMinutes":600,"durationText":"10 小时",
           "withdrawable":true,"cancellable":false,
           "evidenceFiles":[],"approvalNodes":[],
           "courses":[{"courseId":"C-假-0001","courseName":"$COURSE_NAME","courseDate":"2026-09-20",
                      "startSection":1,"endSection":2,"teacherName":"$LEAVE_COURSE_TEACHER"}]}
        ]}}
    """.trimIndent()

    // ── 小工具 ───────────────────────────────────────────────────

    private fun param(raw: String, name: String): String? =
        raw.split('&').firstOrNull { it.substringBefore('=') == name }?.substringAfter('=', "")

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    private fun redirect(exchange: HttpExchange, location: String) {
        exchange.responseHeaders.add("Location", location)
        exchange.sendResponseHeaders(302, -1)
        exchange.close()
    }

    private fun respondJson(exchange: HttpExchange, json: String) =
        respond(exchange, 200, "application/json; charset=utf-8", json.toByteArray())

    private fun respondHtml(exchange: HttpExchange, html: String) =
        respond(exchange, 200, "text/html; charset=utf-8", html.toByteArray())

    private fun respond(exchange: HttpExchange, code: Int, contentType: String, body: ByteArray) {
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(code, body.size.toLong())
        exchange.responseBody.write(body)
        exchange.close()
    }
}