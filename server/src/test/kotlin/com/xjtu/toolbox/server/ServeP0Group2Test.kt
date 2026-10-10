package com.xjtu.toolbox.server

import com.xjtu.toolbox.FakeCampusProxy
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.card.CampusCardFakeUpstream
import com.xjtu.toolbox.jwxt.JwxtFakeUpstream
import com.xjtu.toolbox.library.LibraryFakeUpstream
import com.xjtu.toolbox.platform.JvmCredentialStore
import com.xjtu.toolbox.platform.dataRootOverride
import com.xjtu.toolbox.platform.wipeSecureStore
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import java.nio.file.Files
import com.xjtu.toolbox.venue.VenueFakeUpstream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * **P0 端点组二（需登录六域）的契约测试**：`/api/jwxt*`、`/api/library*`、`/api/card*`、
 * `/api/venue*` 共 16 个端点（`docs/api-contract.md` §5）。
 *
 * ## 独立 wiring（不动 `ServeModule.kt`）
 *
 * 与组一同一套脚手架（[startEndpointServer]）：`embeddedServer` + `route("/api")` +
 * `accessTokenGate` + 四个扩展函数（`sessionRoutes` / `jwxtRoutes` / `libraryRoutes` /
 * `cardRoutes` / `venueRoutes`）—— serve 进程的真装配（`ServeSession`：九站点注册 + 落盘凭据
 * + 冷启动恢复）照用，HTTP 外壳在测试里现搭。
 *
 * ## 手法与纪律
 *
 * - **真登录**：`POST /api/session/login` 走完整 CAS（`:testkit` 的 `FakeCampusProxy` 当代理），
 *   全部站点随登录链预热（`SESSION_SITE_KEYS`）—— 这些端点吃的站点会话是**真的**；
 * - **期望值全部来自夹具原文**（`:testkit` 的 `JwxtFakeUpstream` / `LibraryFakeUpstream` /
 *   `CampusCardFakeUpstream` / `VenueFakeUpstream` 的常量），不是编的；
 * - **红线**：每一个响应都过 [assertNoIdentity]（学号/姓名/账号/手机号/令牌一律不许出现）。
 *   卡面那三个身份字段（`account`/`name`/`studentNo`）要在**夹具里明明有**的前提下证明
 *   没被投影出来 —— 不能「夹具没有所以没出现」；
 * - **令牌闸门**：无令牌 401 / 错令牌 401 / 对令牌放行 / 无会话（登出后）401；
 * - **错误码**：400 参数形状不对、502 上游故障 —— 都走信封（`code == HTTP 状态码`）。
 */
class ServeP0Group2Test {

    private var started: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    @AfterTest
    fun stopServer() {
        started?.stop(gracePeriodMillis = 100, timeoutMillis = 500)
        started = null
    }

    // ── jwxt：terms / term ─────────────────────────────────────────────

    @Test
    fun `jwxt terms 与 term：逐字段`() = withServe { serve ->
        val http = serve.http
        assertEquals(200, serve.login().statusCode())

        val terms = http.get("/api/jwxt/terms")
        assertEquals(200, terms.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(terms))
        assertNoIdentity(terms.body(), "GET /api/jwxt/terms", IDENTITY)
        val termRows = envelopeData(terms).getValue("terms").jsonArray
        assertEquals(3, termRows.size, "夹具三行（倒序；第三行只有 DM）")
        val first = termRows[0] as JsonObject
        assertEquals(JwxtFakeUpstream.TERM_NEW, first.optString("code"))
        assertEquals(JwxtFakeUpstream.TERM_NEW_NAME, first.optString("name"))
        val second = termRows[1] as JsonObject
        assertEquals(JwxtFakeUpstream.TERM_OLD, second.optString("code"))
        assertEquals(JwxtFakeUpstream.TERM_OLD_NAME, second.optString("name"))
        // 第三行上游只给 DM ⇒ :data 的名字兜底是 DM 本身（name = MC ?: DM，SchoolCourseApi.getTermList）
        val third = termRows[2] as JsonObject
        assertEquals(JwxtFakeUpstream.TERM_BARE, third.optString("code"))
        assertEquals(JwxtFakeUpstream.TERM_BARE, third.optString("name"))

        val term = http.get("/api/jwxt/term")
        assertEquals(200, term.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(term))
        assertNoIdentity(term.body(), "GET /api/jwxt/term", IDENTITY)
        assertEquals(JwxtFakeUpstream.TERM_NEW, envelopeData(term).optString("term"))
    }

    // ── jwxt：grades ───────────────────────────────────────────────────

    @Test
    fun `jwxt grades：默认全量、term 过滤、gpa 可空`() = withServe { serve ->
        val http = serve.http
        assertEquals(200, serve.login().statusCode())

        val all = http.get("/api/jwxt/grades")
        assertEquals(200, all.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(all))
        assertNoIdentity(all.body(), "GET /api/jwxt/grades", IDENTITY)
        val grades = envelopeData(all).getValue("grades").jsonArray
        assertEquals(3, grades.size, "报表两页：春季 2 门 + 夏季小学期 1 门")

        val spring = grades[0] as JsonObject
        assertEquals(JwxtFakeUpstream.SCORE_1_NAME, spring.optString("courseName"))
        assertEquals(5.0, spring.optString("coursePoint")?.toDouble())
        assertEquals("95", spring.optString("score"))
        assertEquals(4.3, spring.optString("gpa")?.toDouble(), "95 → 4.3（:core 的 ScoreCalculator）")
        assertEquals(JwxtFakeUpstream.SCORE_TERM_SPRING, spring.optString("term"))

        val excellent = grades[1] as JsonObject
        assertEquals(JwxtFakeUpstream.SCORE_2_NAME, excellent.optString("courseName"))
        assertEquals("优秀", excellent.optString("score"))
        assertNull(excellent.optString("gpa"), "等级制课程不参与绩点 ⇒ gpa:null（§4：null ≠ 字段缺失）")
        assertEquals(4.0, excellent.optString("coursePoint")?.toDouble())

        val summer = grades[2] as JsonObject
        assertEquals(JwxtFakeUpstream.SCORE_3_NAME, summer.optString("courseName"))
        assertEquals("88", summer.optString("score"))
        assertEquals(JwxtFakeUpstream.SCORE_TERM_SUMMER, summer.optString("term"))

        // ?term= 过滤：只留那个学期的两门
        val filtered = http.get("/api/jwxt/grades?term=${JwxtFakeUpstream.SCORE_TERM_SPRING}")
        assertEquals(200, filtered.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(filtered))
        assertNoIdentity(filtered.body(), "GET /api/jwxt/grades?term=…", IDENTITY)
        val rows = envelopeData(filtered).getValue("grades").jsonArray
        assertEquals(2, rows.size)
        assertEquals(
            listOf(JwxtFakeUpstream.SCORE_1_NAME, JwxtFakeUpstream.SCORE_2_NAME),
            rows.map { (it as JsonObject).optString("courseName") },
        )

        // all 参数只认 1
        val badAll = http.get("/api/jwxt/grades?all=2")
        assertEquals(400, badAll.statusCode())
        assertEquals(ApiErrors.BAD_REQUEST, envelopeCode(badAll))
    }

    // ── jwxt：school-courses ───────────────────────────────────────────

    @Test
    fun `jwxt school-courses：契约 §5 要补的字段全投影 + 分页 + 筛选项开关`() = withServe { serve ->
        val http = serve.http
        assertEquals(200, serve.login().statusCode())

        val response = http.get("/api/jwxt/school-courses?term=${JwxtFakeUpstream.TERM_NEW}&page=1&size=20")
        assertEquals(200, response.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(response))
        assertNoIdentity(response.body(), "GET /api/jwxt/school-courses", IDENTITY)
        val data = envelopeData(response)
        assertEquals(1, data.optInt("page"))
        assertEquals(20, data.optInt("size"))
        assertEquals(JwxtFakeUpstream.TOTAL_SIZE, data.optInt("total"))
        assertEquals(true, data.optBoolean("supportsDepartmentFilter"), "直连侧有开课单位下拉表 ⇒ 如实 true")
        assertEquals(true, data.optBoolean("supportsElectiveFilter"), "直连侧有公选类别筛 ⇒ 如实 true")

        val courses = data.getValue("courses").jsonArray
        assertEquals(JwxtFakeUpstream.TOTAL_SIZE, courses.size)

        // 第一行：人数/学时/周学时/YPSJDD 全都在（campus-api 刻意不投影的那批）
        val math = courses[0] as JsonObject
        assertEquals("MATH1001", math.optString("courseCode"))
        assertEquals(JwxtFakeUpstream.COURSE_1_NAME, math.optString("courseName"))
        assertEquals("01", math.optString("sectionNumber"))
        assertEquals("示例甲", math.optString("teacher"))
        assertEquals(JwxtFakeUpstream.DEPT_MATH, math.optString("department"), "开课单位")
        assertEquals(5.0, math.optString("credit")?.toDouble())
        assertEquals(80.0, math.optString("totalHours")?.toDouble(), "总学时 XS")
        assertEquals(80.0, math.optString("lectureHours")?.toDouble(), "授课学时 SKXS")
        assertEquals(0.0, math.optString("labHours")?.toDouble())
        assertEquals(0.0, math.optString("practiceHours")?.toDouble())
        assertEquals(120, math.optInt("enrollCount"), "选课人数 XKZRS")
        assertEquals(150, math.optInt("capacity"), "课容量 KRL")
        assertEquals("电气2401-2402", math.optString("className"))
        assertEquals("兴庆校区 主楼A101 周一 1-2节", math.optString("scheduleLocation"), "YPSJDD")
        assertEquals("兴庆校区", math.optString("campus"))
        assertEquals(false, math.optBoolean("isPublicElective"))
        assertEquals("", math.optString("electiveCategory"))
        assertEquals(5.0, math.optString("weeklyHours")?.toDouble(), "周学时 KNZXS")
        assertEquals(80, math.optInt("maleEnrollCount"))
        assertEquals(40, math.optInt("femaleEnrollCount"))
        assertEquals("JXB-1001", math.optString("teachingClassId"))
        assertEquals(JwxtFakeUpstream.TERM_NEW, math.optString("termCode"))
        assertEquals(30, math.optInt("remaining"), "150-120 ⇒ 剩余容量也投影")
        assertEquals(0.8, math.optString("fillRatio")?.toDouble(), "120/150 ⇒ 容量比例")

        // 第二行：缺 XKZRS/SJXS/NSXKRS/NVSXKRS ⇒ :data 的 safeInt 默认 0（钉住的默认值）
        val physics = courses[1] as JsonObject
        assertEquals(JwxtFakeUpstream.COURSE_2_NAME, physics.optString("courseName"))
        assertEquals(0, physics.optInt("enrollCount"), "缺键 ⇒ 0（:data 的默认，SchoolCourseApiJvmTest 同判据）")
        assertEquals(120, physics.optInt("capacity"))
        assertEquals(true, physics.optBoolean("isPublicElective"))
        assertEquals("基础通识类核心课", physics.optString("electiveCategory"))

        // 第三行：缺 YPSJDD/KNZXS ⇒ 空串 / 0.0
        val art = courses[2] as JsonObject
        assertEquals(JwxtFakeUpstream.COURSE_3_NAME, art.optString("courseName"))
        assertEquals("", art.optString("scheduleLocation"))
        assertEquals(0.0, art.optString("weeklyHours")?.toDouble())
        assertEquals(0, art.optInt("remaining"), "80-80 ⇒ 0（填满，不是不知道）")

        // weekday 形状不对 ⇒ 400
        val badWeekday = http.get("/api/jwxt/school-courses?weekday=9")
        assertEquals(400, badWeekday.statusCode())
        assertEquals(ApiErrors.BAD_REQUEST, envelopeCode(badWeekday))
    }

    // ── jwxt：evaluations / status ─────────────────────────────────────

    @Test
    fun `jwxt evaluations：未评逐字段 + canSubmit 恒 false + status`() = withServe { serve ->
        val http = serve.http
        assertEquals(200, serve.login().statusCode())

        // 默认：当前学期、未评（finished=0）⇒ 过程 1 行 + 期末 2 行
        val response = http.get("/api/jwxt/evaluations")
        assertEquals(200, response.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(response))
        assertNoIdentity(response.body(), "GET /api/jwxt/evaluations", IDENTITY)
        val data = envelopeData(response)
        assertEquals(false, data.optBoolean("canSubmit"), "只读投影 ⇒ canSubmit 必须出现且为 false（契约 §5）")
        val items = data.getValue("items").jsonArray
        assertEquals(3, items.size)

        val mid = items[0] as JsonObject // PGLXDM=05（过程），夹具未评·过程一行
        assertEquals(JwxtFakeUpstream.JUDGE_WJDM_MID, mid.optString("wjdm"))
        assertEquals(JwxtFakeUpstream.JUDGE_JXBID_MID, mid.optString("jxbid"))
        assertEquals(JwxtFakeUpstream.JUDGE_COURSE_MID, mid.optString("course"))
        assertEquals(JwxtFakeUpstream.JUDGE_TEACHER_MID, mid.optString("teacher"))
        assertEquals("05", mid.optString("type"))
        assertEquals("过程评教", mid.optString("tag"))
        assertEquals(JwxtFakeUpstream.JUDGE_TERM, mid.optString("term"))
        assertEquals(false, mid.optBoolean("finished"))
        assertEquals("2026-10-01 08:00:00", mid.optString("startTime"))
        assertEquals("2026-10-25 23:59:00", mid.optString("endTime"))
        assertEquals(
            "${JwxtFakeUpstream.JUDGE_WJDM_MID}_${JwxtFakeUpstream.JUDGE_JXBID_MID}_${JwxtFakeUpstream.JUDGE_TEACHER_MID}",
            mid.optString("key"),
        )

        val final1 = items[1] as JsonObject // PGLXDM=01（期末）
        assertEquals(JwxtFakeUpstream.JUDGE_WJDM_FINAL, final1.optString("wjdm"))
        assertEquals(JwxtFakeUpstream.JUDGE_COURSE_FINAL, final1.optString("course"))
        assertEquals(JwxtFakeUpstream.JUDGE_TEACHER_FINAL, final1.optString("teacher"))
        assertEquals("01", final1.optString("type"))
        assertEquals("期末评教", final1.optString("tag"))
        assertEquals("2027-01-10 23:59:00", final1.optString("endTime"), "夹具期末行 JSSJ")

        // ?finished=1：已评（过程空表 + 期末 1 行）
        val done = http.get("/api/jwxt/evaluations?finished=1")
        assertEquals(200, done.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(done))
        assertNoIdentity(done.body(), "GET /api/jwxt/evaluations?finished=1", IDENTITY)
        val doneItems = envelopeData(done).getValue("items").jsonArray
        assertEquals(1, doneItems.size, "已评只有期末那一条（过程是空表）")
        val doneRow = doneItems[0] as JsonObject
        assertEquals(JwxtFakeUpstream.JUDGE_WJDM_DONE, doneRow.optString("wjdm"))
        assertEquals(JwxtFakeUpstream.JUDGE_COURSE_DONE, doneRow.optString("course"))
        assertEquals(true, doneRow.optBoolean("finished"))
        assertEquals("", doneRow.optString("teacher"), "夹具已评行缺 BPJS ⇒ 空串")

        // ?type=05：只查过程
        val midOnly = http.get("/api/jwxt/evaluations?type=05")
        assertEquals(200, midOnly.statusCode())
        val midItems = envelopeData(midOnly).getValue("items").jsonArray
        assertEquals(1, midItems.size)
        assertEquals("05", (midItems[0] as JsonObject).optString("type"))

        // 参数形状：terms 超过 4 个 ⇒ 400；finished 不是 0/1/all ⇒ 400
        assertEquals(400, http.get("/api/jwxt/evaluations?terms=a,b,c,d,e").statusCode())
        assertEquals(400, http.get("/api/jwxt/evaluations?finished=2").statusCode())

        val status = http.get("/api/jwxt/evaluations/status")
        assertEquals(200, status.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(status))
        assertNoIdentity(status.body(), "GET /api/jwxt/evaluations/status", IDENTITY)
        assertEquals(
            setOf("canSubmit"),
            envelopeData(status).keys,
            "status 的 data 就该是这一个小巧的形状：{ canSubmit }",
        )
        assertEquals(false, envelopeData(status).optBoolean("canSubmit"))
    }

    // ── library：campus / areas / seats / my ───────────────────────────

    @Test
    fun `library campus 与 areas：逐字段 + canBook 如实`() = withServe { serve ->
        val http = serve.http
        assertEquals(200, serve.login().statusCode())

        val campus = http.get("/api/library/campus")
        assertEquals(200, campus.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(campus))
        assertNoIdentity(campus.body(), "GET /api/library/campus", IDENTITY)
        val campusData = envelopeData(campus)
        val current = campusData.getValue("current").jsonObject
        assertEquals("east", current.optString("code"), "夹具 /modify 的 rplace 选中项就是 east")
        assertEquals("兴庆", current.optString("name"), "displayName（不是那串长名字）")
        val campuses = campusData.getValue("campuses").jsonArray
        assertEquals(3, campuses.size)
        assertEquals("east", (campuses[0] as JsonObject).optString("code"))
        assertEquals("west", (campuses[1] as JsonObject).optString("code"))
        assertEquals("inno", (campuses[2] as JsonObject).optString("code"))
        val floors = campusData.getValue("queryableFloors").jsonArray
        assertEquals(
            listOf("xingqing2floor", "xingqing3floor", "xingqing4floor"),
            floors.map { it.jsonPrimitive.content },
        )
        assertEquals(true, campusData.optBoolean("canBook"), "能力开关：:data 的写路径是真的 ⇒ 如实 true")
        assertEquals(true, campusData.optBoolean("hasSeatPlan"))

        val areas = http.get("/api/library/areas?floor=xingqing2floor")
        assertEquals(200, areas.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(areas))
        assertNoIdentity(areas.body(), "GET /api/library/areas", IDENTITY)
        val areasData = envelopeData(areas)
        assertEquals("xingqing2floor", areasData.optString("floor"))
        assertEquals(2, areasData.optInt("areaCount"))
        val areaRows = areasData.getValue("areas").jsonArray
        val north2east = areaRows[0] as JsonObject
        assertEquals("north2east", north2east.optString("code"))
        assertEquals("北楼二层外文库（东）", north2east.optString("name"))
        assertEquals("xingqing2floor", north2east.optString("floor"))
        assertEquals(9, north2east.optInt("available"), "scount [24,9] ⇒ total=24, available=9")
        assertEquals(24, north2east.optInt("total"))
        val north2west = areaRows[1] as JsonObject
        assertEquals("north2west", north2west.optString("code"))
        assertEquals("北楼二层外文库（西）", north2west.optString("name"))
        assertEquals(2, north2west.optInt("available"))
        assertEquals(18, north2west.optInt("total"))
        // totals{} 与 areas[] 同源（同一份 cachedAreaStats）
        val totals = areasData.getValue("totals").jsonObject
        assertEquals(9, totals.getValue("north2east").jsonObject.optInt("available"))
        assertEquals(24, totals.getValue("north2east").jsonObject.optInt("total"))
        assertEquals(2, totals.getValue("north2west").jsonObject.optInt("available"))

        // 不给 floor ⇒ 当前校区全部楼层，逐层拉一次后合并（两区去重）
        val allFloors = http.get("/api/library/areas")
        assertEquals(200, allFloors.statusCode())
        assertEquals(2, envelopeData(allFloors).optInt("areaCount"))
        assertNull(envelopeData(allFloors).optString("floor"))
    }

    @Test
    fun `library seats 与 my：逐字段 + my 无预约是 null 不是空对象`() = withServe { serve ->
        val http = serve.http
        assertEquals(200, serve.login().statusCode())

        // area 缺了 ⇒ 400（信封）
        val missing = http.get("/api/library/seats")
        assertEquals(400, missing.statusCode())
        assertEquals(ApiErrors.BAD_REQUEST, envelopeCode(missing))

        val seats = http.get("/api/library/seats?area=north2east")
        assertEquals(200, seats.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(seats))
        assertNoIdentity(seats.body(), "GET /api/library/seats", IDENTITY)
        val seatsData = envelopeData(seats)
        assertEquals("north2east", seatsData.optString("area"))
        assertEquals(3, seatsData.optInt("total"))
        assertEquals(2, seatsData.optInt("available"), "qseat 里 0=空位：A10/A01 空、A02 被占")
        val rows = seatsData.getValue("seats").jsonArray
        assertEquals(listOf("A01", "A02", "A10"), rows.map { (it as JsonObject).optString("seatId") }, "排序与 :app 同（字母→数字）")
        assertEquals(true, (rows[0] as JsonObject).optBoolean("available"))
        assertEquals(false, (rows[1] as JsonObject).optBoolean("available"))
        assertEquals(true, (rows[2] as JsonObject).optBoolean("available"))

        // my：有预约 ⇒ 逐字段（actionUrls 刻意不投影，P1 写端点）
        val my = http.get("/api/library/my")
        assertEquals(200, my.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(my))
        assertNoIdentity(my.body(), "GET /api/library/my", IDENTITY)
        val myData = envelopeData(my)
        val booking = myData.getValue("my").jsonObject
        assertEquals("A01", booking.optString("seatId"))
        assertEquals("北楼二层外文库（东）", booking.optString("area"))
        assertEquals("已预约", booking.optString("statusText"))
        assertFalse(booking.containsKey("actionUrls"), "动作按钮 P1 才接线，现在投影出来就是点了会失败的按钮")

        // my：页面明确说没有预约 ⇒ my:null（§4：null = 明确说没有，≠ 空对象）
        serve.fake.library.cancelled.set(true)
        val none = http.get("/api/library/my")
        assertEquals(200, none.statusCode())
        assertNoIdentity(none.body(), "GET /api/library/my（无预约）", IDENTITY)
        assertEquals(JsonNull, envelopeData(none).getValue("my"), "null 要写成 null，不是字段缺失")
    }

    // ── card：balance / transactions ───────────────────────────────────

    @Test
    fun `card balance：逐字段且不投影身份`() = withServe { serve ->
        val http = serve.http
        assertEquals(200, serve.login().statusCode())
        val response = http.get("/api/card/balance")
        assertEquals(200, response.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(response))
        // 红线：卡面三个身份字段（account/name/studentNo）在夹具里都有值，但不能出现
        assertNoIdentity(response.body(), "GET /api/card/balance", IDENTITY + CampusCardFakeUpstream.USER_NAME)

        val data = envelopeData(response)
        assertEquals(
            setOf("balance", "pendingAmount", "lostFlag", "frozenFlag", "expireDate", "cardType", "department"),
            data.keys,
            "卡面形状就是这七个字段（account/name/studentNo 不投影）",
        )
        assertEquals(CampusCardFakeUpstream.BALANCE_CENTS / 100.0, data.optString("balance")?.toDouble())
        assertEquals(CampusCardFakeUpstream.PENDING_CENTS / 100.0, data.optString("pendingAmount")?.toDouble())
        assertEquals(false, data.optBoolean("lostFlag"), "barflag=0 ⇒ 没挂失（挂失口径沿用 :data 的 barflag 读法）")
        assertEquals(false, data.optBoolean("frozenFlag"))
        assertEquals(CampusCardFakeUpstream.EXPIRE_DATE, data.optString("expireDate"))
        assertEquals(CampusCardFakeUpstream.CARD_TYPE, data.optString("cardType"), "cardType ← 上游 cardname（trim 后）")
        assertEquals("", data.optString("department"), "ncard JSON 里没有学院 ⇒ 空串，不编")
    }

    @Test
    fun `card transactions：分页形状 + 逐字段（amount 带符号、description 是 channel）`() = withServe { serve ->
        val http = serve.http
        assertEquals(200, serve.login().statusCode())

        val missing = http.get("/api/card/transactions")
        assertEquals(400, missing.statusCode())
        assertEquals(ApiErrors.BAD_REQUEST, envelopeCode(missing))

        val response = http.get("/api/card/transactions?from=2026-10-01&to=2026-10-31&page=1&size=50")
        assertEquals(200, response.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(response))
        assertNoIdentity(response.body(), "GET /api/card/transactions", IDENTITY)
        val data = envelopeData(response)
        assertEquals(1, data.optInt("page"))
        assertEquals(50, data.optInt("size"))
        assertEquals(CampusCardFakeUpstream.TOTAL, data.optInt("total"))
        val rows = data.getValue("transactions").jsonArray
        assertEquals(CampusCardFakeUpstream.TOTAL, rows.size)

        val lunch = rows[0] as JsonObject
        assertEquals(CampusCardFakeUpstream.TX_LUNCH, lunch.optString("time"))
        assertEquals(-CampusCardFakeUpstream.TX_LUNCH_AMOUNT_CENTS / 100.0, lunch.optString("amount")?.toDouble(), "支出 = 负（signed 的方向判据在 :data 已合成）")
        assertEquals(CampusCardFakeUpstream.TX_LUNCH_BALANCE_CENTS / 100.0, lunch.optString("balance")?.toDouble())
        assertEquals(CampusCardFakeUpstream.MERCHANT_CANTEEN, lunch.optString("merchant"))
        assertEquals("消费", lunch.optString("type"))
        assertEquals("${CampusCardFakeUpstream.MERCHANT_CANTEEN}-电子账户消费", lunch.optString("description"), "description ← 上游 resume")

        val recharge = rows[2] as JsonObject
        assertEquals(CampusCardFakeUpstream.TX_RECHARGE, recharge.optString("time"))
        assertEquals(CampusCardFakeUpstream.TX_RECHARGE_AMOUNT_CENTS / 100.0, recharge.optString("amount")?.toDouble(), "充值 = 正")
        assertEquals("充值", recharge.optString("merchant"), "toMerchant 空 ⇒ merchantFromResume(resume 第一段)")

        val refund = rows[6] as JsonObject
        assertEquals(CampusCardFakeUpstream.TX_REFUND_AMOUNT_CENTS / 100.0, refund.optString("amount")?.toDouble(), "夹具该行 tranamt 就是负数（-1500）⇒ 原样 -15.0")
        assertEquals(CampusCardFakeUpstream.MERCHANT_REFUND, refund.optString("merchant"))

        // 日期区间反了 ⇒ 400
        assertEquals(400, http.get("/api/card/transactions?from=2026-10-31&to=2026-10-01").statusCode())
    }

    // ── venue：products / slots / orders / status ──────────────────────

    @Test
    fun `venue products 与 status：逐字段 + 能力开关如实`() = withServe { serve ->
        val http = serve.http
        assertEquals(200, serve.login().statusCode())

        val products = http.get("/api/venue/products")
        assertEquals(200, products.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(products))
        assertNoIdentity(products.body(), "GET /api/venue/products", IDENTITY)
        val venues = envelopeData(products).getValue("venues").jsonArray
        // 夹具两页：第一页 8 行（id=0 跳过、缺名读空串）⇒ 7 个实体，第二页 2 个 ⇒ 9
        assertEquals(9, venues.size)
        val first = venues[0] as JsonObject
        assertEquals(VenueFakeUpstream.VENUE_A_ID, first.optInt("id"))
        assertEquals(VenueFakeUpstream.VENUE_A_NAME, first.optString("name"))
        assertEquals(VenueFakeUpstream.VENUE_A_ADDRESS, first.optString("address"))
        assertEquals(VenueFakeUpstream.VENUE_A_ICON, first.optString("iconType"))
        assertEquals(VenueFakeUpstream.VENUE_A_ADVANCE_DAY, first.optInt("advanceDay"))
        assertEquals(VenueFakeUpstream.VENUE_A_ADVANCE_NUM, first.optInt("advanceNum"))
        // 缺 name 的行：id=105 读成空串（不跳过也不编）
        assertEquals("", (venues[3] as JsonObject).optString("name"))
        assertEquals(VenueFakeUpstream.VENUE_GBK_NAME, (venues[8] as JsonObject).optString("name"), "第二页 GBK 那行也解出来了")

        val status = http.get("/api/venue/status")
        assertEquals(200, status.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(status))
        assertNoIdentity(status.body(), "GET /api/venue/status", IDENTITY)
        val statusData = envelopeData(status)
        assertEquals(
            setOf("canBook", "canCancel", "browserLoginUrl"),
            statusData.keys,
        )
        assertEquals(false, statusData.optBoolean("canBook"), "serve 没有滑块宿主 ⇒ canBook 如实 false")
        assertEquals(true, statusData.optBoolean("canCancel"), "取消不需要滑块 ⇒ 如实 true")
        assertTrue(statusData.optString("browserLoginUrl").orEmpty().isNotEmpty())
    }

    @Test
    fun `venue slots：surplus 用 status==1 可订读法 + orders 分页逐字段`() = withServe { serve ->
        val http = serve.http
        assertEquals(200, serve.login().statusCode())

        // 参数缺 ⇒ 400
        assertEquals(400, http.get("/api/venue/slots").statusCode())

        val slots = http.get("/api/venue/slots?serviceId=${VenueFakeUpstream.SERVICE_ID}&date=2026-10-12")
        assertEquals(200, slots.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(slots))
        assertNoIdentity(slots.body(), "GET /api/venue/slots", IDENTITY)
        val slotsData = envelopeData(slots)
        assertEquals(VenueFakeUpstream.SERVICE_ID, slotsData.optInt("serviceId"))
        assertEquals("2026-10-12", slotsData.optString("date"))
        val slotRows = slotsData.getValue("slots").jsonArray
        assertEquals(7, slotRows.size, "可订 5 + 已占用 2，按 (时段, 场地名) 排")

        // 可订第一格：all=10/using=3 ⇒ surplus=7（status==1 读法）
        val early = slotRows[0] as JsonObject
        assertEquals(VenueFakeUpstream.SLOT_EARLY, early.optString("timeSlot"))
        assertEquals("场地1", early.optString("areaName"))
        assertEquals(10, early.optInt("allCount"))
        assertEquals(3, early.optInt("usingNum"))
        assertEquals(7, early.optInt("surplus"))
        assertEquals(true, early.optBoolean("isAvailable"))
        assertEquals(20.0, early.optString("price")?.toDouble())
        assertEquals(9101L, early.optLong("stockId"))

        // all==used 仍可订 ⇒ surplus 兜到 1（不是旧口径的 0）
        val full = slotRows[1] as JsonObject
        assertEquals("场地2", full.optString("areaName"))
        assertEquals(10, full.optInt("usingNum"))
        assertEquals(1, full.optInt("surplus"), "all==used 时「可订」的读法是至少 1（:data 的 coerceAtLeast(1)）")

        // 空 sname ⇒ 退化成「预订」；¥ 与逗号会被 :data strip 掉
        assertEquals(VenueFakeUpstream.AREA_FALLBACK_NAME, (slotRows[4] as JsonObject).optString("areaName"))
        assertEquals(30.0, (slotRows[3] as JsonObject).optString("price")?.toDouble(), "¥30 → 30.0")

        // 已占用那一路 status 0/2 ⇒ surplus 0（屏据此画「已满」）
        val occupied = slotRows[5] as JsonObject
        assertEquals(VenueFakeUpstream.SLOT_OCCUPIED, occupied.optString("timeSlot"))
        assertEquals(0, occupied.optInt("surplus"))
        assertEquals(false, occupied.optBoolean("isAvailable"))
        assertEquals(0, (slotRows[6] as JsonObject).optInt("surplus"))

        // 订单：分页 + 逐字段
        val orders = http.get("/api/venue/orders?page=1&size=20")
        assertEquals(200, orders.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(orders))
        assertNoIdentity(orders.body(), "GET /api/venue/orders", IDENTITY)
        val ordersData = envelopeData(orders)
        assertEquals(1, ordersData.optInt("page"))
        assertEquals(20, ordersData.optInt("size"))
        assertEquals(VenueFakeUpstream.ORDER_TOTAL, ordersData.optInt("total"))
        assertEquals(false, ordersData.optBoolean("hasMore"))
        val orderRows = ordersData.getValue("orders").jsonArray
        assertEquals(2, orderRows.size, "夹具两条（非对象元素与空 orderid 都被 :data 滤掉）")

        val paid = orderRows[0] as JsonObject
        assertEquals(VenueFakeUpstream.ORDER_PAID_ID, paid.optString("orderId"))
        assertEquals(1, paid.optInt("status"))
        assertEquals("预订成功", paid.optString("statusText"))
        assertEquals("2026-10-12 08:05:00", paid.optString("createdAt"))
        assertEquals(20.0, paid.optString("price")?.toDouble())
        assertEquals(VenueFakeUpstream.VENUE_A_NAME, paid.optString("venueName"))
        assertEquals(false, paid.optBoolean("canPay"))
        assertEquals(true, paid.optBoolean("canCancel"))
        val detail = paid.getValue("details").jsonArray[0].jsonObject
        assertEquals("2026-10-12", detail.optString("date"))
        assertEquals(VenueFakeUpstream.SLOT_EARLY, detail.optString("timeSlot"))
        assertEquals("场地1", detail.optString("areaName"))
        assertEquals(20.0, detail.optString("price")?.toDouble())
        assertEquals("${VenueFakeUpstream.SERVICE_ID}", detail.optString("serviceId"))

        val pending = orderRows[1] as JsonObject
        assertEquals(VenueFakeUpstream.ORDER_PENDING_ID, pending.optString("orderId"))
        assertEquals(0, pending.optInt("status"))
        assertEquals("预订中", pending.optString("statusText"))
        assertEquals(30.0, pending.optString("price")?.toDouble())
        assertEquals(true, pending.optBoolean("canPay"), "status==0 ⇒ 待支付")
        assertEquals(2, pending.getValue("details").jsonArray.size, "第二条订单两个明细（第二个只有场地名）")
    }

    // ── 令牌闸门 / 会话闸门（组二共用的安全口径）────────────────────────

    @Test
    fun `令牌与会话两重闸门：无令牌 401、无会话 401、登出后 401`() = withServe { serve ->
        val http = serve.http
        // 无令牌 ⇒ 401（中文短句、信封）
        val anonymous = http.get("/api/jwxt/terms", bearer = null)
        assertEquals(401, anonymous.statusCode())
        assertEquals(ApiErrors.UNAUTHORIZED, envelopeCode(anonymous))
        assertTrue(envelopeMessage(anonymous).orEmpty().any { it.code in 0x4e00..0x9fff }, "401 也要中文短句")

        // 错令牌 ⇒ 401
        assertEquals(401, http.get("/api/jwxt/terms", bearer = "not-the-token").statusCode())

        // 令牌对、但还没登录 ⇒ 401（「请先登录」不是 502 蒙混）
        val noSession = http.get("/api/library/campus")
        assertEquals(401, noSession.statusCode())
        assertEquals(ApiErrors.UNAUTHORIZED, envelopeCode(noSession))
        assertTrue(envelopeMessage(noSession).orEmpty().contains("登录"), "要指路「先登录」")

        // 登录后放行；登出后同一个端点回到 401
        assertEquals(200, serve.login().statusCode())
        assertEquals(200, http.get("/api/card/balance").statusCode())
        assertEquals(200, http.postJson(SESSION_PATH + "/logout", "{}").statusCode())
        val afterLogout = http.get("/api/jwxt/term")
        assertEquals(401, afterLogout.statusCode())
        assertNoIdentity(afterLogout.body(), "登出后的 GET /api/jwxt/term", IDENTITY)
    }

    // ── 脚手架 ─────────────────────────────────────────────────────────

    /**
     * 组二的通用脚手架：假上游 + 临时数据根 + 真 serve（四组扩展全挂）+ 真登录一次。
     *
     * 顺序上的三个坑与组一/[ServeSessionTest] 一样：
     * 1. `installFakeUpstreams` 必须在构造 [ServeSession] 之前（它一构造就建 OkHttpClient）；
     * 2. cookie jar 与凭据存储是**进程级**缓存 ⇒ 上一轮测试留下的 TGC/凭据要先清掉，
     *    否则「真登录」会变成 SSO 直通（这一轮不数 credentialPosts，但断言更稳）；
     * 3. 数据根一律临时目录，绝不碰 `~/.local/share/xjtu-toolbox/` 里那份真的。
     */
    private fun withServe(block: (Serve) -> Unit) {
        FakeCampusProxy.installFakeUpstreams()
        dataRootOverride = Files.createTempDirectory("xjtu-serve-g2").toFile()
        val fake = FakeCampusProxy(casEnabled = true)
        fake.start()
        try {
            wipeStoredSession()
            val token = AccessToken.newToken()
            val session = ServeSession()
            val (server, http) = startEndpointServer(token) {
                sessionRoutes(session, token)
                jwxtRoutes(session)
                libraryRoutes(session)
                cardRoutes(session)
                venueRoutes(session)
            }
            started = server
            block(Serve(fake, session, http))
        } finally {
            fake.close()
            dataRootOverride = null
        }
    }

    /** 进程级落盘态清一遍（与 [ServeSessionTest] 同口径）。 */
    private fun wipeStoredSession() {
        val suffix = AccountContext.suffixFor(LibraryFakeUpstream.USERNAME)
        for (base in listOf("cookies_normal", "cookies_webvpn", "sites_normal", "sites_webvpn")) {
            wipeSecureStore("${base}_default")
            wipeSecureStore("$base$suffix")
        }
        wipeSecureStore(JvmCredentialStore.FILE_NAME)
    }

    private fun Serve.login(): java.net.http.HttpResponse<String> =
        http.postJson(SESSION_PATH + "/login", LOGIN_BODY)

    private class Serve(val fake: FakeCampusProxy, val session: ServeSession, val http: EndpointHarness)

    private companion object {
        val LOGIN_BODY =
            """{"username":"${LibraryFakeUpstream.USERNAME}","password":"${LibraryFakeUpstream.PASSWORD}"}"""

        /** §3.4 红线的钉子组：键名 + 典型值（学号/姓名都来自夹具备份，不是真人）。 */
        val IDENTITY = listOf("username", "studentId", "学号", "姓名", LibraryFakeUpstream.USERNAME)
    }
}