package com.xjtu.toolbox.core.net

import com.xjtu.toolbox.attendance.LeaveType
import com.xjtu.toolbox.attendance.WaterType
import com.xjtu.toolbox.coupon.CouponFilter
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ServeReadonlyFourDomainsApiTest {

    /** 一个只按路径答夹具的 MockEngine（与 `ServeModeApiTest` 同一套写法）。 */
    private fun engineFor(vararg bodies: Pair<String, String>): MockEngine {
        val byPath = bodies.toMap()
        return MockEngine { request ->
            val body = byPath.entries.firstOrNull { request.url.encodedPath.endsWith(it.key) }?.value
                ?: error("测试没有为 ${request.url.encodedPath} 准备响应")
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType to listOf("application/json")),
            )
        }
    }

    /** 契约 §5.5：`GET /api/dzpz/transcript` 只端七步里的第一步。 */
    @Test
    fun serveTranscriptReadsFirstStepOnly() = runTest {
        val body = """
            {"code":0,"data":{"workflowId":29,
              "typeOptions":[{"name":"本科生中文成绩单","value":0},{"name":"只有名字没有取值的那一项"}],
              "defaultDate":"2026-10-13","defaultRequestName":"成绩单申请"}}
        """.trimIndent()
        val api = CampusTranscriptApi(
            createToolboxClient(engine = engineFor("/api/dzpz/transcript" to body)),
            "",
            ApiMode.SERVE,
        )

        val form = api.loadCreateForm(29)
        assertEquals(29, form.workflowId)
        // 缺 `value` 的那一项不列（不拿 0 冒充它的取值）；`cancelled` 不投影（服务端已滤掉 cancel=1）
        assertEquals(listOf("本科生中文成绩单"), form.typeOptions.map { it.name })
        assertEquals(0, form.typeOptions.single().value)
        assertEquals("2026-10-13", form.defaultDate)
        assertEquals("成绩单申请", form.defaultRequestName)
        assertFalse(form.typeOptions.single().cancelled)

        assertTrue(form.params.isEmpty(), "params 不投影 ⇒ 空对象")
        assertTrue(form.submitParams.isEmpty(), "submitParams 不投影 ⇒ 空对象")
        assertTrue(form.maindata.isEmpty(), "maindata 不投影 ⇒ 空对象")
        assertEquals("", form.linkageUUID)
        assertEquals("", form.signatureAttributesStr)
        assertEquals("", form.signatureSecretKey)

        // 出 PDF 那条链在 2026-10-22（批6）起**真接上了**（两段式，契约 §5.12）：
        // 逐发的校在 `ServeTranscriptWorkflowApiTest` 里；这一例只看「屏会不会画那个按钮」。
        assertTrue(api.canRunWorkflow, "serve 的两段式能走完七步 ⇒ 屏上那个按钮真画")
        assertTrue(api.getLinkageData(form, 0).studentId.isEmpty(), "第 2 步是空壳（服务端在建单那一发里自己做，返回值里有学号）")
        // 每一步的逐发校（两段式、幂等、票据过期、409）在 `ServeTranscriptWorkflowApiTest` 里 ——
        // 这一例只关心「表单那一半 + 屏会不会画按钮」，不在这里重做一遍。
    }

    /** 契约 §4/§5.5 的错误信封：`code` 是 HTTP 状态码，失败文案只有 `message` 一处。 */
    @Test
    fun serveTranscriptFailureReadsMessage() = runTest {
        val api = CampusTranscriptApi(
            createToolboxClient(
                engine = engineFor(
                    "/api/dzpz/transcript" to
                        """{"code":401,"data":null,"message":"请先登录：POST /api/session/login"}""",
                ),
            ),
            "",
            ApiMode.SERVE,
        )
        val e = assertFailsWith<IllegalStateException> { api.loadCreateForm(29) }
        assertTrue(e.message?.contains("请先登录") == true, "失败文案要能直接给用户看，实际：${e.message}")
    }

    /** 考勤：五个端点、三处映射（`startSection ← startTime`、`status: -1 → UNKNOWN`「未知」、`teacher` 不投影留空）、三栏都在（能力开关三项 `true`，2026-10-22 批5 起），以及当前学期不自己算。 */
    @Test
    fun serveAttendanceOnlyHasTermsAndRecords() = runTest {
        val termsBody = """
            {"code":0,"data":{"terms":[{"bh":"646","name":"2026-2027 第一学期","code":"2026-2027-1",
              "startDate":"2026-09-14","endDate":"2027-01-18"}]}}
        """.trimIndent()
        // 不带 ?term= 的那一发与带 ?term=646 的那一发都答这个（服务端定的学期就是 646）
        val recordsBody = """
            {"code":0,"data":{"term":"646","count":2,"records":[
              {"id":"1001","courseName":"高等数学（上）","courseCode":"MATH1001","term":"2026-2027-1",
               "date":"2026-10-09","week":4,"startSection":1,"endSection":2,"location":"主楼A-101",
               "status":1,"statusText":"正常"},
              {"id":"1002","courseName":"大学物理","courseCode":"PHYS1001","term":"2026-2027-1",
               "date":"2026-10-10","week":4,"startSection":3,"endSection":4,"location":"中2-3201",
              "status":-1,"statusText":"未知"}]}}
        """.trimIndent()
        // 统计那一栏（契约 §5.12，2026-10-22 批5）：服务端按整学期流水算出来的八格
        val statsBody = """
            {"code":0,"data":{"term":"646","count":1,"stats":[
              {"courseName":"高等数学（上）","courseCode":"MATH1001","normalCount":12,
               "lateCount":1,"absenceCount":2,"leaveEarlyCount":0,"leaveCount":1,"total":16}]}}
        """.trimIndent()
        // 打卡原始流水（四条原样投影）
        val streamsBody = """
            {"code":0,"data":{"term":"646","count":2,"streams":[
              {"id":"S1","location":"主楼A-101","collectTime":"2026-09-16 08:05:00","effective":true},
              {"id":"S2","location":"中2-3201","collectTime":"2026-09-16 14:05:00","effective":false}]}}
        """.trimIndent()
        val calls = mutableListOf<Map<String, String>>()
        val engine = MockEngine { request ->
            calls += request.url.parameters.names().associateWith { request.url.parameters[it].orEmpty() }
            val body = when {
                request.url.encodedPath.endsWith("/api/attendance/terms") -> termsBody
                request.url.encodedPath.endsWith("/api/attendance/records") -> recordsBody
                request.url.encodedPath.endsWith("/api/attendance/stats") -> statsBody
                request.url.encodedPath.endsWith("/api/attendance/streams") -> streamsBody
                else -> error("没有为 ${request.url.encodedPath} 准备响应")
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType to listOf("application/json")))
        }
        val api = CampusAttendanceApi(createToolboxClient(engine = engine), "", ApiMode.SERVE)

        val term = api.getTermList().single()
        assertEquals("646", term.bh)
        assertEquals("2026-2027 第一学期", term.name)
        assertEquals("2026-2027-1", term.code)

        assertEquals("646", api.getTermBh(), "当前学期要听服务端的")
        assertEquals(2, calls.size, "问当前学期就一发：不带任何参数的 /records")
        assertTrue(calls[1].isEmpty(), "那一发不许自己带参数（带上自己算的学期就是第二份口径），实际：${calls[1]}")
        // 学期号进了缓存：再问一次不再发请求（屏上拉一次会调用它两次）
        assertEquals("646", api.getTermBh())
        assertEquals(2, calls.size, "第二次问当前学期不该再发请求")

        val records = api.getWaterRecords("646", "2026-09-01", "2026-09-30")
        assertEquals(listOf("1001", "1002"), records.map { it.sbh })
        assertEquals(1, records[0].startTime, "startSection ← startTime（是节次不是时刻）")
        assertEquals(2, records[0].endTime)
        assertEquals(WaterType.NORMAL, records[0].status)
        assertEquals("正常", records[0].status.displayName)
        // status=-1 必须落到「未知」那一档（不许悄悄算成正常），契约 §5.5 写死了这一条
        assertEquals(WaterType.UNKNOWN, records[1].status)
        assertEquals("未知", records[1].status.displayName)
        assertEquals("", records[0].teacher, "teacher 不投影（红线）⇒ 如实留空，屏上也不画")
        assertEquals("646", calls[2]["term"], "流水那一发才带学期号")
        assertEquals("2026-09-01", calls[2]["from"])
        assertEquals("2026-09-30", calls[2]["to"])

        // 三栏都在（2026-10-22 批5 补齐了统计与打卡流水两条端点，契约 §5.12）
        assertEquals(
            com.xjtu.toolbox.attendance.AttendanceCapabilities(stats = true, streams = true, leave = true),
            api.capabilities,
        )

        val stats = api.computeCourseStatsFromRecords(records)
        assertEquals(1, stats.size)
        val stat = stats.single()
        assertEquals("高等数学（上）", stat.subjectName, "线上 `courseName` 就是模型里的 `subjectName`")
        assertEquals("MATH1001", stat.subjectCode)
        assertEquals(12, stat.normalCount)
        assertEquals(1, stat.lateCount)
        assertEquals(2, stat.absenceCount)
        assertEquals(0, stat.leaveEarlyCount)
        assertEquals(1, stat.leaveCount)
        assertEquals(16, stat.total)
        assertEquals(13, stat.actualCount, "actualCount 是换属性，不进线上形状")
        assertEquals(3, stat.abnormalCount)
        // 统计那一发带的是**学期号**（不是一对日期）：端点只认学期号，而 currentTermBh 由上一发流水记下了
        assertEquals("646", calls[3]["term"])
        assertEquals(1, api.getKqtjCurrentWeek().size, "本周那一格也走同一条端点（它只是同一份统计的另一条入口）")

        val streams = api.getStreams("2026-09-01", "2026-09-30")
        assertEquals(listOf("S1", "S2"), streams.map { it.id })
        assertEquals("主楼A-101", streams[0].location)
        assertEquals("2026-09-16 08:05:00", streams[0].collectTime, "上游原文原样投影（本层不编一个时区）")
        assertTrue(streams[0].effective)
        assertTrue(!streams[1].effective)
        assertEquals("646", calls.last()["term"], "打卡流水那一发也只带学期号")
        assertTrue(api.getStudentInfo().isEmpty(), "姓名那格不端 ⇒ 空表（屏上姓名那一行自然不画）")
    }

    /** 请假那一半：三读 + 三写都真走；附件上传仍然没有端点。 */
    @Test
    fun serveAttendanceLeaveReadsAndWritesFollowContract() = runTest {
        val windowBody =
            """{"code":0,"data":{"semesterId":"646","academicYear":"2026-2027",""" +
                """"semesterName":"第一学期","startDate":"2026-09-14","endDate":"2027-02-21",""" +
                """"maxStudentLeaveMinutes":2880}}"""
        val previewBody =
            """{"code":0,"data":{"nextNodeNo":1,"nextApprovers":[{"userId":"A1","name":"李四（假）"}],""" +
                """"nodes":[{"nodeNo":1,"nodeRole":"TEACHER","nodeName":"辅导员","configured":true,""" +
                """"approvers":[{"userId":"A1","name":"李四（假）"}]}]}}"""
        val recordsBody =
            """{"code":0,"data":{"page":1,"size":20,"total":1,"records":[""" +
                """{"leaveId":"L1","leaveType":"SICK","reason":"发烧","applyTime":"2026-10-10 08:00",""" +
                """"startTime":"2026-10-11 08:00:00","endTime":"2026-10-11 12:00:00",""" +
                """"status":"PENDING","displayStatus":"","approvalStatus":"PENDING","cancelStatus":"",""" +
                """"sourceType":"STUDENT","durationMinutes":240,"durationText":"4 小时","archiveId":"AR1",""" +
                """"withdrawable":true,"cancellable":false,"evidenceFiles":[],"approvalNodes":[],""" +
                """"courses":[{"courseId":"C1","courseName":"光电子学","courseDate":"2026-10-11",""" +
                """"startSection":1,"endSection":2,"teacherNames":"张三（假）"}]}]}}"""
        val paths = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            paths += path
            val body = when {
                path.endsWith("/api/attendance/leave/window") -> windowBody
                path.endsWith("/api/attendance/leave/preview") -> previewBody
                path.endsWith("/api/attendance/leave/records") -> recordsBody
                path.endsWith("/api/attendance/leave/withdraw") || path.endsWith("/api/attendance/leave/cancel") -> {
                    bodies += (request.body as io.ktor.content.TextContent).text
                    """{"code":0,"data":{"ok":true,"message":"ok"}}"""
                }
                path.endsWith("/api/attendance/leave") -> {
                    bodies += (request.body as io.ktor.content.TextContent).text
                    """{"code":0,"data":{"leaveId":"L-NEW"}}"""
                }
                else -> error("没有为 $path 准备响应")
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType to listOf("application/json")))
        }
        val api = CampusAttendanceApi(createToolboxClient(engine = engine), "", ApiMode.SERVE)

        val window = api.getSemesterWindow()
        assertEquals("646", window.semesterId)
        assertEquals(2880, window.maxStudentLeaveMinutes)

        val preview = api.getFlowPreview(LeaveType.SICK)
        assertEquals(1, preview.nextNodeNo)
        assertEquals("A1", preview.nextApprovers.single().userId, "下拉框的句柄是 userId（提交时要原样带回）")
        assertEquals(
            "李四（假）",
            preview.nextApprovers.single().name,
            "审批人姓名在 §5.2 例外 ④ 里 ⇒ 真投影（屏上那个下拉框画的就是它）",
        )
        assertTrue(preview.nodes.single().configured)

        val page = api.getLeavePage()
        assertEquals(1, page.total)
        val record = page.records.single()
        assertEquals("L1", record.leaveId)
        assertEquals("发烧", record.reason)
        assertTrue(record.withdrawable)
        assertEquals(
            "张三（假）",
            record.courses.single().teacherNames,
            "任课教师姓名在 §5.2 例外 ④ 里 ⇒ 真投影（例外的根据是「我自己的课」）",
        )

        val leaveId = api.createLeave(
            type = LeaveType.SICK,
            startTime = "2026-10-20 08:00:00",
            endTime = "2026-10-20 12:00:00",
            reason = "发烧",
            nextApproverUserId = "A1",
            evidenceFiles = emptyList(),
            requestId = "",
        )
        assertEquals("L-NEW", leaveId, "服务端给的单号原样带回")
        assertEquals("/api/attendance/leave", paths.last())
        val createBody = bodies.first()
        assertTrue(createBody.contains(""""type":"SICK""""), "type 发的是枚举码：$createBody")
        assertTrue(createBody.contains(""""startTime":"2026-10-20 08:00:00""""), createBody)
        assertTrue(createBody.contains(""""nextApproverUserId":"A1""""), createBody)
        assertTrue(createBody.contains("requestId"), createBody)
        assertTrue(!createBody.contains("evidenceFiles"), "附件那一格不发出去（本端没有那个端点）：$createBody")
        val requestId = Regex(""""requestId":"([0-9A-F]+)""").find(createBody)?.groupValues?.get(1)
        assertTrue(requestId != null && requestId.length == 16, "补出来的幂等键是 16 位十六进制：$requestId")

        api.withdrawLeave("L1", "学生撤回")
        assertEquals("/api/attendance/leave/withdraw", paths.last())
        api.cancelLeave("L1")
        assertEquals("/api/attendance/leave/cancel", paths.last())
        assertEquals(3, bodies.size, "提交 / 撤回 / 销假各一发")

        // 附件上传：本端没有端点（屏上那个槽位给的是空表，不会调到这里）
        assertFailsWith<UnsupportedOperationException> { api.uploadEvidence("a.pdf", "application/pdf", byteArrayOf()) }
        // ⚠️ 统计与打卡流水那一栏 2026-10-22（批5）起**都有端点**了：它们逐字段的核在
        // `serveAttendanceOnlyHasTermsAndRecords` 里（那里准备了 stats / streams 两条夹具）；
        // 这个用例只准备请假那几个端点，所以不再拿「空表」当它们的断言。
    }

    /** 加餐券：金额一律是分（不换算）、封面图不发那一枪（直连会拿到登录页）、领券（写路径）已经端了：`POST /api/coupon/activate`（`canActivate = true`）。 */
    @Test
    fun serveCouponKeepsFenAndSkipsLoginPageImage() = runTest {
        val listBody = """
            {"code":0,"data":{"filter":"available","page":1,"size":20,"total":1,"records":[
              {"sendId":"S1","showCardId":"C1","voucherName":"加餐券","typeName":"加餐券",
               "amountFen":500,"leftAmountFen":300,"leftCount":1,
               "startDate":"2026-10-01","endDate":"2026-11-01",
               "imageUrl":"https://egc.xjtu.edu.cn/y.jpg"}]}}
        """.trimIndent()
        val detailBody = """
            {"code":0,"data":{"showCardId":"C1","voucherName":"加餐券","title":"五元加餐券",
              "description":"仅限校内食堂使用","amountFen":500,"leftAmountFen":300,
              "startDate":"2026-10-01","endDate":"2026-11-01","batchId":"B1",
              "imageUrl":"https://egc.xjtu.edu.cn/y.jpg",
              "closedPacketImageUrl":"https://egc.xjtu.edu.cn/c.jpg",
              "openPacketImageUrl":"https://egc.xjtu.edu.cn/o.jpg"}}
        """.trimIndent()
        val calls = mutableListOf<Map<String, String>>()
        val paths = mutableListOf<String>()
        val activateBodies = mutableListOf<String>()
        val engine = MockEngine { request ->
            paths += request.url.encodedPath
            calls += request.url.parameters.names().associateWith { request.url.parameters[it].orEmpty() }
            val body = when {
                request.url.encodedPath.endsWith("/api/coupon/list") -> listBody
                request.url.encodedPath.endsWith("/api/coupon/detail") -> detailBody
                request.url.encodedPath.endsWith("/api/coupon/activate") -> {
                    activateBodies += (request.body as io.ktor.content.TextContent).text
                    """{"code":0,"data":{"ok":true,"message":"✓ 已领取"}}"""
                }
                else -> error("没有为 ${request.url.encodedPath} 准备响应")
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType to listOf("application/json")))
        }
        val api = CampusCouponApi(createToolboxClient(engine = engine), "", ApiMode.SERVE)

        val page = api.queryCoupons(CouponFilter.AVAILABLE, 1, 20)
        assertEquals(1, page.total)
        val coupon = page.records.single()
        assertEquals(500L, coupon.amountFen, "金额单位是分（不换算成元）")
        assertEquals(300L, coupon.leftAmountFen)
        assertEquals("available", calls[0]["filter"], "参数只认一套 ASCII 写法（枚举名小写）")
        assertEquals("20", calls[0]["size"])

        val detail = api.getCouponDetail("C1")
        assertEquals("C1", detail.showCardId)
        assertEquals("五元加餐券", detail.title)
        assertEquals("https://egc.xjtu.edu.cn/c.jpg", detail.closedPacketImageUrl)

        assertNull(api.loadImage(coupon.imageUrl), "取不到图就不画图，不拿登录页当封面")
        assertEquals(2, calls.size, "loadImage 不许发请求")

        // 领券：端子在 ⇒ 屏上那个按钮照旧画；这一发真打出去（`{showCardId}`）
        assertTrue(api.canActivate, "领券端了（POST /api/coupon/activate）⇒ 屏上那块「领取」照旧画")
        api.activateCoupon("C1")
        assertEquals("/api/coupon/activate", paths.last(), "第三发是领券那一条路径")
        assertEquals(1, activateBodies.size, "领券真发了一枪")
        assertEquals("""{"showCardId":"C1"}""", activateBodies.single(), "body 只有契约那一格")
    }


    /** 这四个域在 campus-api 里没有端点（它们是 `:app` / 桌面直接走 `:data` 的 okhttp 抓的）。 */
    @Test
    fun campusApiModeThrowsInsteadOfFakingEmptyData() = runTest {
        val engine = engineFor("/api/dzpz/transcript" to """{"code":0,"data":{}}""")
        // 注意：不传 mode（默认 = CAMPUS_API）
        assertFailsWith<UnsupportedOperationException> {
            CampusTranscriptApi(createToolboxClient(engine = engine), "").loadCreateForm(29)
        }
        assertFailsWith<UnsupportedOperationException> {
            CampusAttendanceApi(createToolboxClient(engine = engine), "").getTermList()
        }
        assertFailsWith<UnsupportedOperationException> {
            CampusCouponApi(createToolboxClient(engine = engine), "").queryCoupons(CouponFilter.USABLE, 1, 20)
        }
    }
}
