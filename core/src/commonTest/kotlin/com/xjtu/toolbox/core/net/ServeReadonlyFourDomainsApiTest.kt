package com.xjtu.toolbox.core.net

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

/**
 * serve 契约 §5.5（成绩单 / 考勤 / 加餐券 / 宿舍电费）的**客户端那一侧**。
 *
 * 为什么值得写：这四个域是 2026-10-13 才接进 `:web` 的，屏是 `:core` 的共享屏、取数是这四个新
 * `Campus*Api` —— 它们只能靠「真 `:server` + 真会话」在浏览器里验证，而这里用 MockEngine 喂
 * **服务端实现出来的形状**（`server/…/DzpzRoutes.kt` / `AttendanceRoutes.kt` / `CouponRoutes.kt` /
 * `DormPowerRoutes.kt` 的 DTO 就是这些字段），把三件事钉死：
 *
 *  1. **错误信封的读法**：`code != 0` → 显示 `message`（不读旧路的 `msg`/`error`，不拿 HTTP 状态码分支）；
 *  2. **字段形状**：改名（`startSection ← startTime`）、单位（金额是**分**）、空与缺失的区别；
 *  3. **缺字段时不编造**：不投影的那六格（成绩单的表单体与签章密钥）留空、`teacher` 留空、
 *     `kwh` 缺失是**错误**而不是「没有表」，能力开关一律报 `false`（屏按它不画）。
 *
 * 默认形态（[ApiMode.CAMPUS_API]）不由这里管：这四个域在 campus-api 里**没有**端点，最后一条用例
 * 钉的就是「非 serve 分支抛错，而不是悄悄返回一份空数据」。
 */
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

    /**
     * 契约 §5.5：`GET /api/dzpz/transcript` 只端七步里的第一步。这条钉住**拿得到的四格**
     * （`workflowId` / `typeOptions` / `defaultDate` / `defaultRequestName`）、**拿不到的那六格
     * 如实留空**（不是编一份假表单），以及 `canRunWorkflow = false` + 第 2~7 步抛错。
     */
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

        // 契约刻意不投影的六格：三个 JSON 与三把签章密钥 ⇒ 如实留空（不是编一份假表单）
        assertTrue(form.params.isEmpty(), "params 不投影 ⇒ 空对象")
        assertTrue(form.submitParams.isEmpty(), "submitParams 不投影 ⇒ 空对象")
        assertTrue(form.maindata.isEmpty(), "maindata 不投影 ⇒ 空对象")
        assertEquals("", form.linkageUUID)
        assertEquals("", form.signatureAttributesStr)
        assertEquals("", form.signatureSecretKey)

        // 出 PDF 那半条链没有端点：屏按 canRunWorkflow 不画那个按钮，方法本身也照旧抛错
        assertFalse(api.canRunWorkflow, "serve 只端了第一步 ⇒ 不许许诺「能走完七步」")
        val linkage = assertFailsWith<UnsupportedOperationException> { api.getLinkageData(form, 0) }
        assertTrue(linkage.message?.contains("学号") == true, "第 2 步不端的理由是学号（红线）：${linkage.message}")
        assertFailsWith<UnsupportedOperationException> { api.generatePreviewPdf(29, 0) }
        assertFailsWith<UnsupportedOperationException> { api.downloadPdf("https://dzpz.xjtu.edu.cn/x.pdf") }
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

    /**
     * 考勤：两个端点、三处映射（`startSection ← startTime`、`status: -1 → UNKNOWN`「未知」、
     * `teacher` 不投影留空）、三栏拿不到（能力开关全 `false`），以及**当前学期不自己算**。
     */
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
        val calls = mutableListOf<Map<String, String>>()
        val engine = MockEngine { request ->
            calls += request.url.parameters.names().associateWith { request.url.parameters[it].orEmpty() }
            val body = when {
                request.url.encodedPath.endsWith("/api/attendance/terms") -> termsBody
                request.url.encodedPath.endsWith("/api/attendance/records") -> recordsBody
                else -> error("没有为 ${request.url.encodedPath} 准备响应")
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType to listOf("application/json")))
        }
        val api = CampusAttendanceApi(createToolboxClient(engine = engine), "", ApiMode.SERVE)

        val term = api.getTermList().single()
        assertEquals("646", term.bh)
        assertEquals("2026-2027 第一学期", term.name)
        assertEquals("2026-2027-1", term.code)

        // 「当前学期」的口径在 :data —— 本端不带 ?term= 问一发，读响应里的 term（不自己按日期算）
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

        // 三栏（统计 / 打卡流水 / 请假）契约 §5.5 都没有端点：能力开关全 false，屏据此不画「暂无…」
        assertEquals(
            com.xjtu.toolbox.attendance.AttendanceCapabilities(stats = false, streams = false, leave = false),
            api.capabilities,
        )
        assertTrue(api.getKqtjCurrentWeek().isEmpty(), "统计不端 ⇒ 空表（不拿流水在本机算第二份口径）")
        assertTrue(api.computeCourseStatsFromRecords(records).isEmpty())
        assertTrue(api.getStreams("2026-09-01", "2026-09-30").isEmpty())
        assertTrue(api.getStudentInfo().isEmpty(), "姓名那格不端 ⇒ 空表（屏上姓名那一行自然不画）")
        val leavePage = api.getLeavePage()
        assertTrue(leavePage.records.isEmpty())
        assertFailsWith<UnsupportedOperationException> { api.getSemesterWindow() }
    }

    /**
     * 加餐券：金额一律是**分**（不换算）、封面图**不发那一枪**（直连会拿到登录页）、
     * 领券（写路径）没有端点 ⇒ `canActivate = false` + 方法抛错。
     */
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
        val engine = MockEngine { request ->
            calls += request.url.parameters.names().associateWith { request.url.parameters[it].orEmpty() }
            val body = when {
                request.url.encodedPath.endsWith("/api/coupon/list") -> listBody
                request.url.encodedPath.endsWith("/api/coupon/detail") -> detailBody
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

        // 封面图：那张图要带 egc 的 Authorization 才取得到 ⇒ 如实 null（屏退化成图标），不去拉登录页
        assertNull(api.loadImage(coupon.imageUrl), "取不到图就不画图，不拿登录页当封面")
        assertEquals(2, calls.size, "loadImage 不许发请求")

        // 领券是写路径 ⇒ 没有端点
        assertFalse(api.canActivate, "领券不端 ⇒ 屏上不画那个按钮")
        val e = assertFailsWith<UnsupportedOperationException> { api.activateCoupon("C1") }
        assertTrue(e.message?.contains("写路径") == true, "实际：${e.message}")
    }

    /**
     * 宿舍电费：`rooms` 的空 id 行不列；`kwh` 的 **`null`（没有表）与「字段缺失」（形状不对）**
     * 是两件事；缴费页入口（`freshPayUrl`）不端。
     */
    @Test
    fun serveDormPowerDistinguishesMissingKwhFromNoMeter() = runTest {
        val roomsBody = """
            {"code":0,"data":{"rooms":[{"id":"R1","name":"和园/A01幢/1单元/101"},
              {"id":"","name":"上游缺 roomId 的那一行"}]}}
        """.trimIndent()
        val readingBody = """{"code":0,"data":{"roomId":"R1","kwh":null}}"""
        val calls = mutableListOf<Map<String, String>>()
        val engine = MockEngine { request ->
            calls += request.url.parameters.names().associateWith { request.url.parameters[it].orEmpty() }
            val body = when {
                request.url.encodedPath.endsWith("/api/dorm/rooms") -> roomsBody
                request.url.encodedPath.endsWith("/api/dorm/reading") -> readingBody
                else -> error("没有为 ${request.url.encodedPath} 准备响应")
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType to listOf("application/json")))
        }
        val api = CampusDormPowerApi(createToolboxClient(engine = engine), "", ApiMode.SERVE)

        val rooms = api.rooms()
        assertEquals(listOf("R1"), rooms.map { it.id }, "空 id 的行不列（与 :data 丢掉它同一个口径）")
        assertEquals("和园/A01幢/1单元/101", rooms.single().name)
        // `kwh: null` = 上游明确说这间房没有表（不是 0，也不是「查不到」）
        assertNull(api.kwh("R1"))
        assertEquals("R1", calls[1]["roomId"])

        // 字段缺失 = 这个端点没按契约答 ⇒ 抛错（不拿 null 冒充「没有表」）
        val missing = CampusDormPowerApi(
            createToolboxClient(engine = engineFor("/api/dorm/reading" to """{"code":0,"data":{"roomId":"R1"}}""")),
            "",
            ApiMode.SERVE,
        )
        assertFailsWith<IllegalStateException> { missing.kwh("R1") }

        // 缴费页入口不端（它是宿主的活：OAuth + 「进过这一页」那个标记）
        val payUrl = assertFailsWith<UnsupportedOperationException> { api.freshPayUrl() }
        assertTrue(payUrl.message?.contains("§5.5") == true, "理由要写在错误里，实际：${payUrl.message}")
    }

    /**
     * 这四个域在 campus-api 里**没有端点**（它们是 `:app` / 桌面直接走 `:data` 的 okhttp 抓的）
     * ⇒ 非 serve 分支抛错，**不许**悄悄返回一份空数据（那会把「形态不对」说成「真没有数据」）。
     */
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
        assertFailsWith<UnsupportedOperationException> {
            CampusDormPowerApi(createToolboxClient(engine = engine), "").rooms()
        }
    }
}
