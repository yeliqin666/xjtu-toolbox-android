package com.xjtu.toolbox.server

import com.xjtu.toolbox.attendance.AttendanceFakeUpstream
import com.xjtu.toolbox.coupon.CouponFakeUpstream
import com.xjtu.toolbox.dormpower.DormPowerFakeUpstream
import com.xjtu.toolbox.dzpz.DzpzFakeUpstream
import com.xjtu.toolbox.library.LibraryFakeUpstream
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray

/**
 * **只读四域**（成绩单 / 考勤 / 加餐券 / 宿舍电费）的契约测试 ——
 * `GET /api/dzpz/transcript` · `GET /api/attendance/{terms,records}` ·
 * `GET /api/coupon/{list,detail}` · `GET /api/dorm/{rooms,reading}`
 * （`docs/api-contract.md` §5.5）。
 *
 * ## 脚手架
 *
 * 与 [ServeFitnessTest] / [ServeVenueActionTest] 同一条（[withFakeProxy] + [startEndpointServer]）：
 * 真 socket（端口 0）、真令牌闸门、真 `ServeSession`，四个 `*Routes` 扩展挂进 `route("/api")`。
 * 夹具是 `:testkit` 的四份假上游（`DzpzFakeUpstream` / `AttendanceFakeUpstream` /
 * `CouponFakeUpstream` / `DormPowerFakeUpstream`），期望值全部来自它们的常量。
 *
 * ## 这一组钉的是什么
 *
 * - **闸门**：四个域各自进门先判会话 ⇒ 未登录 401（不是让取数拿 502 蒙混）；
 * - **形状**：每个 `data` 的键集合逐格钉住（多一格少一格都算投影漂了）；
 * - **两处如实缺口**（不是 bug，是这一批的边界）：
 *   ① `dzpz` 只端了七步里的**第一步**（建单表单）—— 出 PDF 那半条链没有端点；
 *   ② 考勤的 `teacher`、成绩单的 `params`/签章密钥、券的 `amountYuan` **不投影**；
 * - **红线**：每个响应都过 [assertNoIdentity]，而且是在**夹具里明明有那些值**的前提下证明的
 *   （学号 `2021000001` 出现在成绩单/加餐券的登录链与表单里，教师名出现在考勤流水里）——
 *   不能「夹具没有所以没出现」。
 */
class ServeReadonlyFourDomainsTest {

    private var started: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    @AfterTest
    fun stopServer() {
        started?.stop(gracePeriodMillis = 100, timeoutMillis = 500)
        started = null
    }

    // ── 成绩单（dzpz）：只有第一步 ──────────────────────────────────────

    @Test
    fun `dzpz transcript：未登录 401 + 逐字段（四格，且不投影 params 与签章密钥）`() = withFakeProxy { fake ->
        val token = AccessToken.newToken()
        val session = ServeSession()
        val (server, http) = startEndpointServer(token) {
            sessionRoutes(session, token)
            dzpzRoutes(session)
        }
        started = server

        // 令牌对、但没登录 ⇒ 401 + 中文短句指路（不是 502）
        val anonymous = http.get(DZPZ_TRANSCRIPT_PATH)
        assertEquals(401, anonymous.statusCode())
        assertEquals(ApiErrors.UNAUTHORIZED, envelopeCode(anonymous))
        assertTrue(envelopeMessage(anonymous).orEmpty().contains("登录"), "401 要指路「先登录」")
        assertEquals(0, fake.dzpz.loadFormBodies.size, "未登录那一枪不该到取数层")

        assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

        val response = http.get(DZPZ_TRANSCRIPT_PATH)
        assertEquals(200, response.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(response))
        assertNoIdentity(response.body(), "GET $DZPZ_TRANSCRIPT_PATH", IDENTITY)

        val data = envelopeData(response)
        assertEquals(
            setOf("workflowId", "typeOptions", "defaultDate", "defaultRequestName"),
            data.keys,
            "建单表单就是这四格（`params`/`submitParams`/`maindata`/`linkageUUID`/签名密钥一律不端）",
        )
        assertEquals(DzpzFakeUpstream.WORKFLOW_ID, data.optInt("workflowId"), "本端点只端成绩单这一个 workflowId")
        assertEquals(DzpzFakeUpstream.DEFAULT_DATE, data.optString("defaultDate"), "field7249")
        assertEquals(DzpzFakeUpstream.DEFAULT_REQUEST_NAME, data.optString("defaultRequestName"), "field-1")

        // 类型选项：夹具给了两个，`cancel=1` 那个由 :data 的 parseTypeOptions 滤掉 ⇒ 只剩一个
        val options = data.getValue("typeOptions").jsonArray
        assertEquals(1, options.size, "取消过的类型不进选项表（:data 就滤掉了）")
        val option = options[0] as JsonObject
        assertEquals(setOf("name", "value"), option.keys, "没有 `cancelled`：它端出去恒 false（见路由 KDoc）")
        assertEquals(DzpzFakeUpstream.TYPE_NAME, option.optString("name"))
        assertEquals(DzpzFakeUpstream.TYPE_VALUE, option.optInt("value"))

        // 夹具里明明带着这些东西 —— 它们必须一个都没被投影（红线 + 写路径原料）
        val body = response.body()
        for (leaked in FORBIDDEN_DZPZ) {
            assertFalse(body.contains(leaked), "响应里不该出现「$leaked」（夹具里有值）")
        }
    }

    @Test
    fun `dzpz transcript：请求是 iscreate=1 的那一枪，且不投影联动拿到的学号`() = withFakeProxy { fake ->
        val token = AccessToken.newToken()
        val session = ServeSession()
        val (server, http) = startEndpointServer(token) {
            sessionRoutes(session, token)
            dzpzRoutes(session)
        }
        started = server

        assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())
        assertEquals(200, http.get(DZPZ_TRANSCRIPT_PATH).statusCode())

        // 真打到建单表单那一枪（`:data` 的 TranscriptApi.loadCreateForm 拼的表单体）
        val form = fake.dzpz.loadFormBodies.singleOrNull().orEmpty()
        assertTrue(form.isNotEmpty(), "loadForm 应当被真打一次，实际：${fake.dzpz.loadFormBodies}")
        assertTrue(form.contains("iscreate=1"), "建单那一枪要带 iscreate=1，实际：$form")
        assertTrue(form.contains("workflowid=${DzpzFakeUpstream.WORKFLOW_ID}"), "workflowid 就是成绩单那个，实际：$form")
        // 联动那一枪（它的返回值里有学号）属于第 2 步，本端点不该顺带打它
        assertEquals(0, fake.dzpz.linkageBodies.size, "第 2 步（联动）不在本端点的路径上")
        assertEquals(0, fake.dzpz.submitBodies.size, "第 4/5 步（提交）是写路径，本端点不碰")
    }

    // ── 考勤（new_attendance）：terms + records ─────────────────────────

    @Test
    fun `attendance terms 与 records：未登录 401 + 逐字段（teacher 不投影）`() = withFakeProxy { fake ->
        val token = AccessToken.newToken()
        val session = ServeSession()
        val (server, http) = startEndpointServer(token) {
            sessionRoutes(session, token)
            attendanceRoutes(session)
        }
        started = server

        val anonymous = http.get(ATTENDANCE_TERMS_PATH)
        assertEquals(401, anonymous.statusCode())
        assertEquals(ApiErrors.UNAUTHORIZED, envelopeCode(anonymous))
        assertTrue(envelopeMessage(anonymous).orEmpty().contains("登录"), "401 要指路「先登录」")
        assertEquals(401, http.get(ATTENDANCE_RECORDS_PATH).statusCode())
        assertEquals(0, fake.attendance.termCalls.get(), "未登录那一枪不该到取数层")

        assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

        // ── terms：两个学期，顺序 = :data 的顺序（当前学期在前）──
        val termsResponse = http.get(ATTENDANCE_TERMS_PATH)
        assertEquals(200, termsResponse.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(termsResponse))
        assertNoIdentity(termsResponse.body(), "GET $ATTENDANCE_TERMS_PATH", IDENTITY)
        val terms = envelopeData(termsResponse).getValue("terms").jsonArray
        assertEquals(2, terms.size)
        val current = terms[0] as JsonObject
        assertEquals(
            setOf("bh", "name", "code", "startDate", "endDate"),
            current.keys,
            "`TermInfo` 的五格（多一格就说明投影漂了）",
        )
        assertEquals(AttendanceFakeUpstream.TERM_CURRENT_BH, current.optString("bh"))
        assertEquals(AttendanceFakeUpstream.TERM_CURRENT_NAME, current.optString("name"))
        assertEquals(AttendanceFakeUpstream.TERM_CURRENT_CODE, current.optString("code"), "教务风格学期码由 :data 算")
        assertEquals("2026-09-14", current.optString("startDate"))
        assertEquals("2027-02-21", current.optString("endDate"))
        assertEquals(AttendanceFakeUpstream.TERM_LAST_BH, (terms[1] as JsonObject).optString("bh"))
        assertEquals(AttendanceFakeUpstream.TERM_LAST_CODE, (terms[1] as JsonObject).optString("code"))

        // ── records：省略 term ⇒ 当前学期（`:data` 的 getTermBh）──
        val recordsResponse = http.get(ATTENDANCE_RECORDS_PATH)
        assertEquals(200, recordsResponse.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(recordsResponse))
        assertNoIdentity(recordsResponse.body(), "GET $ATTENDANCE_RECORDS_PATH", IDENTITY)
        val recordsData = envelopeData(recordsResponse)
        assertEquals(
            setOf("term", "count", "records"),
            recordsData.keys,
            "响应不回 from/to（兜底口径在 :data，本层不复制一份）",
        )
        assertEquals(AttendanceFakeUpstream.TERM_CURRENT_BH, recordsData.optString("term"), "省略 term ⇒ 当前学期")
        assertEquals(2, recordsData.optInt("count"))
        val rows = recordsData.getValue("records").jsonArray
        assertEquals(2, rows.size)

        // 按日期降序：最近那条（09-18 迟到）在前
        val late = rows[0] as JsonObject
        assertEquals(
            setOf("id", "courseName", "courseCode", "term", "date", "week", "startSection", "endSection", "location", "status", "statusText"),
            late.keys,
            "`teacher` 刻意不投影（考勤记录里的教师名是姓名）",
        )
        assertEquals("r-假-2", late.optString("id"))
        assertEquals(AttendanceFakeUpstream.COURSE_NAME, late.optString("courseName"))
        assertEquals(AttendanceFakeUpstream.COURSE_CODE, late.optString("courseCode"))
        assertEquals(AttendanceFakeUpstream.TERM_CURRENT_CODE, late.optString("term"), "term ← :data 算的学期码")
        assertEquals("2026-09-18", late.optString("date"))
        assertEquals(1, late.optInt("week"), "第 1 周（学期起点 2026-09-14）")
        assertEquals(3, late.optInt("startSection"), "改名的那个：startSection ← startTime（是节次不是时刻）")
        assertEquals(4, late.optInt("endSection"))
        assertEquals(AttendanceFakeUpstream.ROOM, late.optString("location"))
        assertEquals(2, late.optInt("status"), "LATE")
        assertEquals("迟到", late.optString("statusText"))

        val normal = rows[1] as JsonObject
        assertEquals("r-假-1", normal.optString("id"))
        assertEquals("2026-09-16", normal.optString("date"))
        assertEquals(1, normal.optInt("startSection"))
        assertEquals(2, normal.optInt("endSection"))
        assertEquals(1, normal.optInt("status"), "NORMAL")
        assertEquals("正常", normal.optString("statusText"))

        // 夹具每一行都带着 teacherName —— 它必须一个都没端出去（红线）
        val body = recordsResponse.body()
        assertFalse(body.contains(AttendanceFakeUpstream.TEACHER_NAME), "教师名（姓名）不该出现在考勤流水里")
        assertFalse(body.contains(AttendanceFakeUpstream.NAME), "本人姓名不该出现")
        assertFalse(body.contains(AttendanceFakeUpstream.SNO), "本人学号不该出现")
    }

    @Test
    fun `attendance records：term 与 from、to 形状 400，显式 term 原样带回`() = withFakeProxy { fake ->
        val token = AccessToken.newToken()
        val session = ServeSession()
        val (server, http) = startEndpointServer(token) {
            sessionRoutes(session, token)
            attendanceRoutes(session)
        }
        started = server

        assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

        // term 不是纯数字（它是学期号 bh，形如 646）⇒ 400
        val badTerm = http.get("$ATTENDANCE_RECORDS_PATH?term=2026-2027-1")
        assertEquals(400, badTerm.statusCode())
        assertEquals(ApiErrors.BAD_REQUEST, envelopeCode(badTerm))
        assertTrue(envelopeMessage(badTerm).orEmpty().contains("term"), "400 要说出是哪一格")
        assertEquals(0, fake.attendance.recordsCalls.get(), "400 那一枪没到取数层")

        // from/to 不成形 ⇒ 400（文案说出是哪一格）
        val badFrom = http.get("$ATTENDANCE_RECORDS_PATH?from=2026-9-1")
        assertEquals(400, badFrom.statusCode())
        assertEquals(ApiErrors.BAD_REQUEST, envelopeCode(badFrom))
        assertTrue(envelopeMessage(badFrom).orEmpty().contains("from"), "400 要说出是哪一格")
        assertEquals(400, http.get("$ATTENDANCE_RECORDS_PATH?to=not-a-date").statusCode())
        assertEquals(0, fake.attendance.recordsCalls.get(), "400 那几枪都没到取数层")

        // 显式给学期号：原样回给客户端（不做「猜学期」那种二次解析）
        val explicit = http.get("$ATTENDANCE_RECORDS_PATH?term=${AttendanceFakeUpstream.TERM_LAST_BH}")
        assertEquals(200, explicit.statusCode())
        assertEquals(AttendanceFakeUpstream.TERM_LAST_BH, envelopeData(explicit).optString("term"))
        // 那两格真打到了流水接口（学期与日期都在请求体里）
        val recordsBody = fake.attendance.recordsBodies.last()
        assertTrue(
            recordsBody.contains(AttendanceFakeUpstream.TERM_LAST_BH),
            "流水请求要带显式学期，实际：$recordsBody",
        )
        // 业务请求带了会话装饰器的头（`:data` 的 AttendanceSession）
        assertEquals(AttendanceFakeUpstream.TOKEN, fake.attendance.lastBusinessToken)
        assertEquals("WEB", fake.attendance.lastSystemHeader)
    }

    // ── 加餐券（coupon）：list + detail ─────────────────────────────────

    @Test
    fun `coupon list：未登录 401 + 默认档 usable 逐字段（金额是分）`() = withFakeProxy { fake ->
        val token = AccessToken.newToken()
        val session = ServeSession()
        val (server, http) = startEndpointServer(token) {
            sessionRoutes(session, token)
            couponRoutes(session)
        }
        started = server

        val anonymous = http.get(COUPON_LIST_PATH)
        assertEquals(401, anonymous.statusCode())
        assertEquals(ApiErrors.UNAUTHORIZED, envelopeCode(anonymous))
        assertTrue(envelopeMessage(anonymous).orEmpty().contains("登录"), "401 要指路「先登录」")
        assertEquals(401, http.get("$COUPON_DETAIL_PATH?showCardId=${CouponFakeUpstream.USABLE_CARD_ID}").statusCode())
        assertEquals(0, fake.coupon.pageBodies.size, "未登录那一枪不该到取数层")

        assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

        // 省略 filter ⇒ 可使用（`:core` 的 CouponViewModel 初始档），不是「全部」
        val response = http.get(COUPON_LIST_PATH)
        assertEquals(200, response.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(response))
        assertNoIdentity(response.body(), "GET $COUPON_LIST_PATH", IDENTITY)
        val data = envelopeData(response)
        assertEquals(
            setOf("filter", "page", "size", "total", "records"),
            data.keys,
            "分页形状（§4）加上本次用的档",
        )
        assertEquals("usable", data.optString("filter"), "省略 filter ⇒ usable（ASCII 码，与请求参数同一套写法）")
        assertEquals(1, data.optInt("page"))
        assertEquals(20, data.optInt("size"), "默认页大小照 :core 的 CouponViewModel（20）")
        assertEquals(2, data.optInt("total"))

        val rows = data.getValue("records").jsonArray
        assertEquals(2, rows.size, "夹具 status=1 那两批")
        val usable = rows[0] as JsonObject
        assertEquals(
            setOf("sendId", "showCardId", "voucherName", "typeName", "amountFen", "leftAmountFen", "leftCount", "startDate", "endDate", "imageUrl"),
            usable.keys,
            "`amountYuan`/`leftAmountYuan` 是 :core 的便利属性不是模型字段 ⇒ 不端（除 100 是消费方的事）",
        )
        assertEquals(CouponFakeUpstream.USABLE_SEND_ID, usable.optString("sendId"))
        assertEquals(CouponFakeUpstream.USABLE_CARD_ID, usable.optString("showCardId"))
        assertEquals(CouponFakeUpstream.USABLE_NAME, usable.optString("voucherName"))
        assertEquals("加餐券", usable.optString("typeName"))
        assertEquals(500L, usable.optLong("amountFen"), "单位是分")
        assertEquals(500L, usable.optLong("leftAmountFen"))
        assertEquals(1, usable.optInt("leftCount"))
        assertEquals(CouponFakeUpstream.DATE_START, usable.optString("startDate"))
        assertEquals(CouponFakeUpstream.DATE_END, usable.optString("endDate"))
        assertEquals(
            "https://${CouponFakeUpstream.HOST}${CouponFakeUpstream.PIC_PATH}",
            usable.optString("imageUrl"),
            "绝对地址由 :data 的 normalizeImageUrl 拼好（⚠️ 浏览器直接打它会拿到登录页，见路由 KDoc）",
        )

        // 第二行 `pic:""` ⇒ 空串（不编地址）
        val bare = rows[1] as JsonObject
        assertEquals("CARD-假-0003", bare.optString("showCardId"))
        assertEquals(300L, bare.optLong("amountFen"))
        assertEquals(120L, bare.optLong("leftAmountFen"))
        assertEquals("", bare.optString("imageUrl"), "空 pic 就是空串（null≠空串那条不适用于这里：上游明确给了空）")

        // 业务请求带着会话装饰器加的 Authorization
        assertEquals(CouponFakeUpstream.AUTH_TOKEN, fake.coupon.lastBusinessAuthorization)
        // 夹具里有 employeeNo（=`2021000001`），响应里不许出现
        assertFalse(response.body().contains(CouponFakeUpstream.EMPLOYEE_NO), "employeeNo 不该被投影")
    }

    @Test
    fun `coupon list 与 detail：filter 与分页 400、showCardId 缺 400、detail 十二格`() = withFakeProxy { fake ->
        val token = AccessToken.newToken()
        val session = ServeSession()
        val (server, http) = startEndpointServer(token) {
            sessionRoutes(session, token)
            couponRoutes(session)
        }
        started = server

        assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

        // filter 不认识的档 ⇒ 400（文案把合法取值列出来）；中文名不当参数收
        val badFilter = http.get("$COUPON_LIST_PATH?filter=all")
        assertEquals(400, badFilter.statusCode())
        assertEquals(ApiErrors.BAD_REQUEST, envelopeCode(badFilter))
        val filterMessage = envelopeMessage(badFilter).orEmpty()
        for (name in listOf("available", "usable", "used_up", "expired")) {
            assertTrue(filterMessage.contains(name), "400 文案要把合法取值列出来，实际：$filterMessage")
        }

        // page / size 不是正整数 ⇒ 400
        assertTrue(envelopeMessage(http.get("$COUPON_LIST_PATH?page=0")).orEmpty().contains("page"))
        assertEquals(400, http.get("$COUPON_LIST_PATH?page=0").statusCode())
        assertEquals(400, http.get("$COUPON_LIST_PATH?size=abc").statusCode())
        // showCardId 缺 ⇒ 400
        val noCardId = http.get(COUPON_DETAIL_PATH)
        assertEquals(400, noCardId.statusCode())
        assertEquals(ApiErrors.BAD_REQUEST, envelopeCode(noCardId))
        assertTrue(envelopeMessage(noCardId).orEmpty().contains("showCardId"), "400 要说出缺什么")
        assertEquals(0, fake.coupon.detailBodies.size, "那两枪都没到取数层")

        // 明确给一档：原样回，且真按那一档查（请求体里 status 跟着换）
        val available = http.get("$COUPON_LIST_PATH?filter=available&page=2&size=5")
        assertEquals(200, available.statusCode())
        val availableData = envelopeData(available)
        assertEquals("available", availableData.optString("filter"))
        assertEquals(2, availableData.optInt("page"))
        assertEquals(5, availableData.optInt("size"))
        assertEquals(CouponFakeUpstream.AVAILABLE_CARD_ID, (availableData.getValue("records").jsonArray[0] as JsonObject).optString("showCardId"))
        assertTrue(
            fake.coupon.pageBodies.last().contains("\"status\": \"0\""),
            "available 那一档的请求体 status 该是 0，实际：${fake.coupon.pageBodies.last()}",
        )

        // ── detail：十二格逐字段 ──
        val detailResponse = http.get("$COUPON_DETAIL_PATH?showCardId=${CouponFakeUpstream.USABLE_CARD_ID}")
        assertEquals(200, detailResponse.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(detailResponse))
        assertNoIdentity(detailResponse.body(), "GET $COUPON_DETAIL_PATH", IDENTITY)
        val detail = envelopeData(detailResponse)
        assertEquals(
            setOf(
                "showCardId", "voucherName", "title", "description", "amountFen", "leftAmountFen",
                "startDate", "endDate", "batchId", "imageUrl", "closedPacketImageUrl", "openPacketImageUrl",
            ),
            detail.keys,
            "`CouponDetail` 的十二格",
        )
        assertEquals(CouponFakeUpstream.USABLE_CARD_ID, detail.optString("showCardId"), "请求给的那个原样回")
        assertEquals(CouponFakeUpstream.USABLE_NAME, detail.optString("voucherName"))
        assertEquals(CouponFakeUpstream.DETAIL_TITLE, detail.optString("title"), "destitle")
        assertEquals(CouponFakeUpstream.DETAIL_DESC, detail.optString("description"), "describes")
        assertEquals(500L, detail.optLong("amountFen"))
        assertEquals(CouponFakeUpstream.BATCH_ID, detail.optString("batchId"))
        assertEquals(
            "https://${CouponFakeUpstream.HOST}${CouponFakeUpstream.CLOSED_PIC}",
            detail.optString("closedPacketImageUrl"),
            "rclose 也由 normalizeImageUrl 拼成绝对地址",
        )
        assertEquals(
            "https://${CouponFakeUpstream.HOST}${CouponFakeUpstream.OPEN_PIC}",
            detail.optString("openPacketImageUrl"),
        )
        assertTrue(
            fake.coupon.detailBodies.single().contains(""""cardId":"${CouponFakeUpstream.USABLE_CARD_ID}""""),
            "详情那一枪要带 cardId，实际：${fake.coupon.detailBodies}",
        )

        // 领券（写路径）没有端点：本批只端只读（`activateCoupon` 会真改上游状态）
        assertEquals(0, fake.coupon.activateBodies.size, "本批不端领券")
    }

    // ── 宿舍电费（ssn）：rooms + reading ────────────────────────────────

    @Test
    fun `dorm rooms 与 reading：未登录 401 + 逐字段（kwh 是度、没有时间戳）`() = withFakeProxy { fake ->
        val token = AccessToken.newToken()
        val session = ServeSession()
        val (server, http) = startEndpointServer(token) {
            sessionRoutes(session, token)
            dormRoutes(session)
        }
        started = server

        val anonymous = http.get(DORM_ROOMS_PATH)
        assertEquals(401, anonymous.statusCode())
        assertEquals(ApiErrors.UNAUTHORIZED, envelopeCode(anonymous))
        assertTrue(envelopeMessage(anonymous).orEmpty().contains("登录"), "401 要指路「先登录」")
        assertEquals(401, http.get("$DORM_READING_PATH?roomId=${DormPowerFakeUpstream.ROOM_ID}").statusCode())
        assertEquals(0, fake.dormPower.roomCalls.get(), "未登录那一枪不该到取数层")

        assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

        // ── rooms：一间 ──
        val roomsResponse = http.get(DORM_ROOMS_PATH)
        assertEquals(200, roomsResponse.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(roomsResponse))
        assertNoIdentity(roomsResponse.body(), "GET $DORM_ROOMS_PATH", IDENTITY)
        val roomsData = envelopeData(roomsResponse)
        assertEquals(setOf("rooms"), roomsData.keys)
        val rooms = roomsData.getValue("rooms").jsonArray
        assertEquals(1, rooms.size)
        val room = rooms[0] as JsonObject
        assertEquals(setOf("id", "name"), room.keys, "`DormRoom` 就两格（id 是要原样带回来的句柄）")
        assertEquals(DormPowerFakeUpstream.ROOM_ID, room.optString("id"))
        assertEquals(DormPowerFakeUpstream.ROOM_NAME, room.optString("name"))

        // ── reading：拿 rooms 给的 id 去查 ──
        val readingResponse = http.get("$DORM_READING_PATH?roomId=${DormPowerFakeUpstream.ROOM_ID}")
        assertEquals(200, readingResponse.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(readingResponse))
        assertNoIdentity(readingResponse.body(), "GET $DORM_READING_PATH", IDENTITY)
        val reading = envelopeData(readingResponse)
        assertEquals(
            setOf("roomId", "kwh"),
            reading.keys,
            "没有时间戳：`:core` 的 DormReading.at 是本机记的，上游不给 ⇒ 本层不编（契约 §4）",
        )
        assertEquals(DormPowerFakeUpstream.ROOM_ID, reading.optString("roomId"))
        assertEquals(DormPowerFakeUpstream.KWH, reading.optString("kwh")?.toDouble() ?: Double.NaN, 1e-9)

        // 会话里的 cid 真被带上了（`SsnLogin` 登录时从缴费页 HTML 里取的那个）
        assertEquals(DormPowerFakeUpstream.CID, fake.dormPower.lastListCid, "列表请求要带会话里的 cid")
        assertEquals(DormPowerFakeUpstream.ROOM_ID, fake.dormPower.lastKwhRoomId)
    }

    @Test
    fun `dorm reading：roomId 缺 400，rooms 不收参数`() = withFakeProxy { fake ->
        val token = AccessToken.newToken()
        val session = ServeSession()
        val (server, http) = startEndpointServer(token) {
            sessionRoutes(session, token)
            dormRoutes(session)
        }
        started = server

        assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

        // roomId 缺 / 空白 ⇒ 400（文案说出缺什么）
        val missing = http.get(DORM_READING_PATH)
        assertEquals(400, missing.statusCode())
        assertEquals(ApiErrors.BAD_REQUEST, envelopeCode(missing))
        assertTrue(envelopeMessage(missing).orEmpty().contains("roomId"), "400 要说出缺什么")
        assertEquals(400, http.get("$DORM_READING_PATH?roomId=%20").statusCode())
        assertEquals(0, fake.dormPower.kwhCalls.get(), "400 那两枪都没到取数层")

        // rooms 不收参数：随便塞一个也不影响（列表照常出来）
        val rooms = http.get("$DORM_ROOMS_PATH?roomId=whatever")
        assertEquals(200, rooms.statusCode())
        assertEquals(1, envelopeData(rooms).getValue("rooms").jsonArray.size)
        assertEquals(1, fake.dormPower.roomCalls.get())

        // rooms 的响应里没有 null 字段可挑：`kwh` 才是唯一可空的那一格
        assertNull(envelopeData(rooms).optString("kwh"), "rooms 的形状里没有 kwh")
    }

    // ── 脚手架 ─────────────────────────────────────────────────────────

    private companion object {
        val LOGIN_BODY =
            """{"username":"${LibraryFakeUpstream.USERNAME}","password":"${LibraryFakeUpstream.PASSWORD}"}"""

        const val DZPZ_TRANSCRIPT_PATH = "/api/dzpz/transcript"
        const val ATTENDANCE_TERMS_PATH = "/api/attendance/terms"
        const val ATTENDANCE_RECORDS_PATH = "/api/attendance/records"
        const val COUPON_LIST_PATH = "/api/coupon/list"
        const val COUPON_DETAIL_PATH = "/api/coupon/detail"
        const val DORM_ROOMS_PATH = "/api/dorm/rooms"
        const val DORM_READING_PATH = "/api/dorm/reading"

        /** §5.2 红线的钉子组（编造值，非真人）：学号 / 姓名 / 令牌一律不许出现。 */
        val IDENTITY = listOf(
            "username",
            "studentId",
            "学号",
            "姓名",
            LibraryFakeUpstream.USERNAME,
            AttendanceFakeUpstream.NAME,
            AttendanceFakeUpstream.TEACHER_NAME,
            CouponFakeUpstream.EMPLOYEE_NO,
            AttendanceFakeUpstream.TOKEN,
            CouponFakeUpstream.AUTH_TOKEN,
        )

        /**
         * 成绩单响应**不许**出现的东西 —— 全部**在夹具里有值**（所以这条断言是有意义的）：
         * 联动/重载表单里的学号、提交表单体与那串**签章密钥**（端出去等于把密钥交给浏览器）。
         */
        val FORBIDDEN_DZPZ = listOf(
            DzpzFakeUpstream.STUDENT_ID,
            DzpzFakeUpstream.LINKAGE_UUID,
            DzpzFakeUpstream.TEMPLATE_PATH,
            DzpzFakeUpstream.CATEGORY_NAME,
            "SIG-KEY-FAKE",
            "SIG-ATTR-FAKE",
            "submitParams",
            "maindata",
        )
    }
}
