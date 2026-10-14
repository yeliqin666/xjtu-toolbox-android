package com.xjtu.toolbox.server

import com.xjtu.toolbox.emptyroom.EmptyRoomFakeUpstream
import com.xjtu.toolbox.jwapp.JwappFakeUpstream
import com.xjtu.toolbox.jwxt.JwxtFakeUpstream
import com.xjtu.toolbox.library.LibraryFakeUpstream
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * **第四批端点**（我课表三端点 / 成绩分项 / 教室座位数）的契约测试 ——
 * `GET /api/jwxt/{changes,exams,textbooks}` · `GET /api/jwapp/score-detail` ·
 * `GET /api/emptyroom/seat-count`（`docs/api-contract.md` §5.8）。
 *
 * ## 脚手架
 *
 * 与 [ServeLmsJiaocaiJwappIclassfaceTest] / [ServePayFeedbackMatchWebvpnTest] 同一条
 *（[withFakeProxy] + [startEndpointServer]）：真 socket（端口 0）、真令牌闸门、真 `ServeSession`。
 * 夹具是 `:testkit` 里那三份假上游 —— 本批只往其中两份**加**了东西（既有常量一个没动）：
 *
 * - `JwxtFakeUpstream`：新增教材报表那一枪（POST + `reportlets=[…]`，见它的 [JwxtFakeUpstream.handleTextbookReport]）——
 *   `changes` / `exams` 两条端点用的是**早就在的** `xsdkkc.do` / `wdksap.do` 样本；
 * - `JwappFakeUpstream`：新增「这门课没有分项」那一档（[JwappFakeUpstream.DETAIL_MISSING_ID]）；
 * - `EmptyRoomFakeUpstream`：**零改动** —— `seatCount` 读的就是它早就有的那份当天 CDN 课表
 *   （教室名与座位数与 `/api/emptyroom/rooms` 同一批）。
 *
 * ## 这一组钉的是什么
 *
 * - **闸门**：需要会话的三族（jwxt 三端点 / jwapp）未登录 401，而且**未登录那一枪不该到取数层**
 *   （夹具的调用计数为 0）；`seat-count` 与 `/api/emptyroom/cdn` **同一档取数**（CDN 免登录）
 *   ⇒ 没登录也答得到那一格；
 * - **形状**：每个 `data` 的键集合逐格钉住（多一格少一格都算投影漂了）；
 * - **`?term=` 省略 = 当前学期**：三条 jwxt 端点省略时取的都是 `/api/jwxt/term` 那个来源，
 *   而且**真带进了上游请求**（`XNXQDM=` / reportlet 里的 `xnxqdm` 对着夹具断言）；
 * - **`textbooks` 的 `hasTextbook` 语义**：夹具第二行就是「无教材」那一档（书名 =「无教材」）⇒
 *   那一行 `hasTextbook:false`；同一条响应里第一行是 `true`（两个值都出现，断言才有意义）；
 *   占位 ISBN 原样投影（滤它是 `:core` 的 `realIsbn` 的事，本层不重写第二个判据）；
 * - **`score-detail` 的「暂无分项成绩」映射**：`NoScoreDetailException` ⇒ `200` + `detail:null`
 *   （§4：上游明确说没有），**不是** 502/404 —— 消费方按 `item.asEmptyDetail()` 那条老规矩接；
 * - **`seat-count` 拿不到 → `null`**（不是 0、也不是错误）：`:data` 的 `EmptyRoomSource.seatCount`
 *   三种情况都回 null；
 * - **红线**：每个响应都过 [assertNoIdentity]，而且是在**夹具里明明有那些值**的前提下证明的
 *   （调停补课报文里的上课教师名 `SCHEDULE_1_TEACHER`、教材报表请求里的学号）—— 不能
 *   「夹具没有所以没出现」。
 */
class ServeJwxtChangesExamsTextbooksScoreDetailSeatCountTest {

    private var started: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    @AfterTest
    fun stopServer() {
        started?.stop(gracePeriodMillis = 100, timeoutMillis = 500)
        started = null
    }

    // ── 调停补课（jwxt/changes）：只是记录，不掺课表 ──────────────────────

    @Test
    fun `changes：未登录 401 + 逐字段（上课教师名不在响应里）`() = withFakeProxy { fake ->
        val token = AccessToken.newToken()
        val session = ServeSession()
        val (server, http) = startEndpointServer(token) {
            sessionRoutes(session, token)
            jwxtRoutes(session)
        }
        started = server

        val anonymous = http.get(JWXT_CHANGES_PATH)
        assertEquals(401, anonymous.statusCode())
        assertEquals(ApiErrors.UNAUTHORIZED, envelopeCode(anonymous))
        assertTrue(envelopeMessage(anonymous).orEmpty().contains("登录"), "401 要指路「先登录」")
        assertEquals(0, fake.jwxt.changesCalls.get(), "未登录那一枪不该到取数层")

        assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

        val response = http.get(JWXT_CHANGES_PATH)
        assertEquals(200, response.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(response))
        assertNoIdentity(response.body(), "GET $JWXT_CHANGES_PATH", IDENTITY)

        val data = envelopeData(response)
        assertEquals(setOf("term", "count", "changes"), data.keys)
        assertEquals(JwxtFakeUpstream.TERM_NEW, data.optString("term"), "省略 ?term= ⇒ 当前学期")
        assertEquals(1, data.optInt("count"))

        // 一条 MOVED：第 5 周、周一第 1-2 节的高等数学从主楼A-101 换到主楼B-202
        val change = data.arrOf("changes")[0] as JsonObject
        assertEquals(
            setOf(
                "courseName", "courseCode", "kind", "fromDay", "fromStartSection", "fromEndSection",
                "toDay", "toStartSection", "toEndSection", "toLocation", "weeks", "toWeeks",
            ),
            change.keys,
            "`ScheduleChangeEvent` 的十二格（字段名一个不改）",
        )
        assertEquals(JwxtFakeUpstream.CHANGE_COURSE, change.optString("courseName"))
        assertEquals(JwxtFakeUpstream.EXAM_CODE, change.optString("courseCode"), "KCH 那一格")
        assertEquals("MOVED", change.optString("kind"), "TKLXDM=1 ⇒ 枚举名 MOVED")
        assertEquals(JwxtFakeUpstream.SCHEDULE_1_DAY, change.optInt("fromDay"))
        assertEquals(JwxtFakeUpstream.SCHEDULE_1_START, change.optInt("fromStartSection"))
        assertEquals(JwxtFakeUpstream.SCHEDULE_1_END, change.optInt("fromEndSection"))
        assertEquals(JwxtFakeUpstream.SCHEDULE_1_DAY, change.optInt("toDay"))
        assertEquals(JwxtFakeUpstream.CHANGE_NEW_ROOM, change.optString("toLocation"))
        assertEquals(
            listOf(JwxtFakeUpstream.CHANGE_WEEK),
            change.arrOf("weeks").map { it.jsonPrimitive.content.toInt() },
            "SKZC 位串里只有一个 1 ⇒ weeks 只有第 5 周",
        )

        assertEquals(1, fake.jwxt.changesCalls.get(), "这一枪真打到了 xsdkkc.do")
        assertTrue(
            fake.jwxt.lastChangesForm.orEmpty().contains("XNXQDM=${JwxtFakeUpstream.TERM_NEW}"),
            "省略 ?term= 时带上去的是当前学期：${fake.jwxt.lastChangesForm}",
        )
    }

    @Test
    fun `changes：term 形状不对 400（不打上游）+ 显式学期原样回那一格`() = withFakeProxy { fake ->
        val token = AccessToken.newToken()
        val session = ServeSession()
        val (server, http) = startEndpointServer(token) {
            sessionRoutes(session, token)
            jwxtRoutes(session)
        }
        started = server
        assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

        val bad = http.get("$JWXT_CHANGES_PATH?term=26-27")
        assertEquals(400, bad.statusCode())
        assertEquals(ApiErrors.BAD_REQUEST, envelopeCode(bad))
        assertTrue(envelopeMessage(bad).orEmpty().contains("term"), "文案要说清是哪个参数")
        assertEquals(0, fake.jwxt.changesCalls.get(), "形状不对那一枪不该到取数层")

        val explicit = http.get("$JWXT_CHANGES_PATH?term=${JwxtFakeUpstream.TERM_OLD}")
        assertEquals(200, explicit.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(explicit))
        assertEquals(JwxtFakeUpstream.TERM_OLD, envelopeData(explicit).optString("term"), "请求给的那个学期原样回")
        assertTrue(
            fake.jwxt.lastChangesForm.orEmpty().contains("XNXQDM=${JwxtFakeUpstream.TERM_OLD}"),
            "实际：${fake.jwxt.lastChangesForm}",
        )
        assertEquals(1, fake.jwxt.changesCalls.get())
    }

    // ── 考试安排（jwxt/exams）───────────────────────────────────────────

    @Test
    fun `exams：未登录 401 + 逐字段（日期与时段是上游剥过的两格）`() = withFakeProxy { fake ->
        val token = AccessToken.newToken()
        val session = ServeSession()
        val (server, http) = startEndpointServer(token) {
            sessionRoutes(session, token)
            jwxtRoutes(session)
        }
        started = server

        val anonymous = http.get(JWXT_EXAMS_PATH)
        assertEquals(401, anonymous.statusCode())
        assertEquals(ApiErrors.UNAUTHORIZED, envelopeCode(anonymous))
        assertEquals(0, fake.jwxt.examCalls.get(), "未登录那一枪不该到取数层")

        assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

        val response = http.get(JWXT_EXAMS_PATH)
        assertEquals(200, response.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(response))
        assertNoIdentity(response.body(), "GET $JWXT_EXAMS_PATH", IDENTITY)

        val data = envelopeData(response)
        assertEquals(setOf("term", "count", "exams"), data.keys)
        assertEquals(JwxtFakeUpstream.TERM_NEW, data.optString("term"), "省略 ?term= ⇒ 当前学期")
        assertEquals(1, data.optInt("count"))

        val exam = data.arrOf("exams")[0] as JsonObject
        assertEquals(
            setOf("courseName", "courseCode", "examDate", "examTime", "location", "seatNumber"),
            exam.keys,
            "`ExamItem` 的六格（字段名一个不改）",
        )
        assertEquals(JwxtFakeUpstream.EXAM_COURSE, exam.optString("courseName"))
        assertEquals(JwxtFakeUpstream.EXAM_CODE, exam.optString("courseCode"))
        assertEquals(JwxtFakeUpstream.EXAM_DATE, exam.optString("examDate"), "上游 KSRQ 带着 00:00:00 ⇒ 只取日期那一段")
        assertEquals(JwxtFakeUpstream.EXAM_TIME, exam.optString("examTime"), "KSSJMS 里那段日期已被剥掉")
        assertEquals(JwxtFakeUpstream.EXAM_ROOM, exam.optString("location"))
        assertEquals(JwxtFakeUpstream.EXAM_SEAT, exam.optString("seatNumber"))

        assertEquals(1, fake.jwxt.examCalls.get(), "这一枪真打到了 wdksap.do")
        val form = fake.jwxt.lastExamForm.orEmpty()
        assertTrue(form.contains("XNXQDM=${JwxtFakeUpstream.TERM_NEW}") && form.contains("*order="), "实际：$form")
    }

    @Test
    fun `exams：term 形状不对 400（不打上游）+ 空白串当省略`() = withFakeProxy { fake ->
        val token = AccessToken.newToken()
        val session = ServeSession()
        val (server, http) = startEndpointServer(token) {
            sessionRoutes(session, token)
            jwxtRoutes(session)
        }
        started = server
        assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

        val bad = http.get("$JWXT_EXAMS_PATH?term=2026")
        assertEquals(400, bad.statusCode())
        assertEquals(ApiErrors.BAD_REQUEST, envelopeCode(bad))
        assertEquals(0, fake.jwxt.examCalls.get(), "形状不对那一枪不该到取数层")

        // 空白串 = 省略（与 `/api/jwxt/schedule` 同一个读法），不是 400
        val blank = http.get("$JWXT_EXAMS_PATH?term=%20")
        assertEquals(200, blank.statusCode())
        assertEquals(JwxtFakeUpstream.TERM_NEW, envelopeData(blank).optString("term"))
        assertEquals(1, fake.jwxt.examCalls.get())
    }

    // ── 本学期教材（jwxt/textbooks）─────────────────────────────────────

    @Test
    fun `textbooks：未登录 401 + 逐字段（hasTextbook 语义 + 学号只在请求里）`() = withFakeProxy { fake ->
        val token = AccessToken.newToken()
        val session = ServeSession()
        val (server, http) = startEndpointServer(token) {
            sessionRoutes(session, token)
            jwxtRoutes(session)
        }
        started = server

        val anonymous = http.get(JWXT_TEXTBOOKS_PATH)
        assertEquals(401, anonymous.statusCode())
        assertEquals(ApiErrors.UNAUTHORIZED, envelopeCode(anonymous))
        assertEquals(0, fake.jwxt.textbookReportCalls.get(), "未登录那一枪不该到取数层")

        assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

        val response = http.get(JWXT_TEXTBOOKS_PATH)
        assertEquals(200, response.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(response))
        // 学号进了请求（下面那条断言证明），但没有进响应 —— §5.2 红线
        assertNoIdentity(response.body(), "GET $JWXT_TEXTBOOKS_PATH", IDENTITY)

        val data = envelopeData(response)
        assertEquals(setOf("term", "count", "books"), data.keys)
        assertEquals(JwxtFakeUpstream.TERM_NEW, data.optString("term"), "省略 ?term= ⇒ 当前学期")
        assertEquals(2, data.optInt("count"), "夹具两行：一门有教材 + 一门「无教材」")

        val book = data.arrOf("books")[0] as JsonObject
        assertEquals(
            setOf(
                "courseName", "textbookName", "author", "publisher", "isbn", "price", "edition",
                "courseCode", "hasTextbook",
            ),
            book.keys,
            "`TextbookItem` 的八格 + hasTextbook",
        )
        assertEquals(JwxtFakeUpstream.TEXTBOOK_COURSE, book.optString("courseName"))
        assertEquals(JwxtFakeUpstream.TEXTBOOK_NAME, book.optString("textbookName"))
        assertEquals(JwxtFakeUpstream.TEXTBOOK_AUTHOR, book.optString("author"))
        assertEquals(JwxtFakeUpstream.TEXTBOOK_PUBLISHER, book.optString("publisher"))
        assertEquals(JwxtFakeUpstream.TEXTBOOK_ISBN, book.optString("isbn"))
        assertEquals(JwxtFakeUpstream.TEXTBOOK_PRICE, book.optString("price"))
        assertEquals(JwxtFakeUpstream.TEXTBOOK_EDITION, book.optString("edition"))
        assertEquals(JwxtFakeUpstream.TEXTBOOK_COURSE_CODE, book.optString("courseCode"))
        assertEquals(true, book.optBoolean("hasTextbook"), "这一行是正常教材")

        // 「无教材」那一档：hasTextbook=false 就是 `TextbookItem.hasSubstantiveTextbook` 的判据
        val none = data.arrOf("books")[1] as JsonObject
        assertEquals(JwxtFakeUpstream.TEXTBOOK_NONE_COURSE, none.optString("courseName"))
        assertEquals(JwxtFakeUpstream.TEXTBOOK_NONE_NAME, none.optString("textbookName"))
        assertEquals(false, none.optBoolean("hasTextbook"), "书名 =「无教材」⇒ hasTextbook:false")
        assertEquals(
            JwxtFakeUpstream.TEXTBOOK_PLACEHOLDER_ISBN,
            none.optString("isbn"),
            "占位 ISBN 原样投影（滤它是 :core 的 realIsbn 那一处，本层不重写第二份判据）",
        )

        assertEquals(1, fake.jwxt.textbookReportCalls.get(), "这一枪真打到了教材报表")
        val reportlets = fake.jwxt.lastTextbookReportlet.orEmpty()
        assertTrue(
            reportlets.contains("'xh':'${JwxtFakeUpstream.STUDENT_ID}'"),
            "学号只在这条请求里（响应里没有它）：$reportlets",
        )
        assertTrue(
            reportlets.contains("'xnxqdm':'${JwxtFakeUpstream.TERM_NEW}'"),
            "省略 ?term= 时带上去的是当前学期：$reportlets",
        )
        assertTrue(reportlets.contains(JwxtFakeUpstream.TEXTBOOK_REPORTLET), "reportlet 是教材那一张：$reportlets")
    }

    @Test
    fun `textbooks：term 形状不对 400 + 显式学期进 reportlets`() = withFakeProxy { fake ->
        val token = AccessToken.newToken()
        val session = ServeSession()
        val (server, http) = startEndpointServer(token) {
            sessionRoutes(session, token)
            jwxtRoutes(session)
        }
        started = server
        assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

        val bad = http.get("$JWXT_TEXTBOOKS_PATH?term=2026-2027")
        assertEquals(400, bad.statusCode())
        assertEquals(ApiErrors.BAD_REQUEST, envelopeCode(bad))
        assertEquals(0, fake.jwxt.textbookReportCalls.get(), "形状不对那一枪不该到取数层")

        val explicit = http.get("$JWXT_TEXTBOOKS_PATH?term=${JwxtFakeUpstream.TERM_OLD}")
        assertEquals(200, explicit.statusCode())
        assertEquals(JwxtFakeUpstream.TERM_OLD, envelopeData(explicit).optString("term"))
        assertTrue(
            fake.jwxt.lastTextbookReportlet.orEmpty().contains("'xnxqdm':'${JwxtFakeUpstream.TERM_OLD}'"),
            "实际：${fake.jwxt.lastTextbookReportlet}",
        )
        assertEquals(1, fake.jwxt.textbookReportCalls.get())
    }

    // ── 成绩分项（jwapp/score-detail）──────────────────────────────────

    @Test
    fun `score-detail：未登录 401 + 逐字段（分项是比例，请求体就是 id）`() = withFakeProxy { fake ->
        val token = AccessToken.newToken()
        val session = ServeSession()
        val (server, http) = startEndpointServer(token) {
            sessionRoutes(session, token)
            jwappRoutes(session)
        }
        started = server

        val anonymous = http.get("$JWAPP_SCORE_DETAIL_PATH?courseId=${JwappFakeUpstream.COURSE_ID}")
        assertEquals(401, anonymous.statusCode())
        assertEquals(ApiErrors.UNAUTHORIZED, envelopeCode(anonymous))
        assertEquals(0, fake.jwapp.detailCalls.get(), "未登录那一枪不该到取数层")

        assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

        val response = http.get("$JWAPP_SCORE_DETAIL_PATH?courseId=${JwappFakeUpstream.COURSE_ID}")
        assertEquals(200, response.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(response))
        assertNoIdentity(response.body(), "GET $JWAPP_SCORE_DETAIL_PATH", IDENTITY)

        val data = envelopeData(response)
        assertEquals(setOf("courseId", "detail"), data.keys)
        assertEquals(JwappFakeUpstream.COURSE_ID, data.optString("courseId"), "请求给的那个 id 原样回")

        val detail = data.getValue("detail") as JsonObject
        assertEquals(
            setOf(
                "courseName", "coursePoint", "examType", "majorFlag", "examProp", "replaceFlag", "score",
                "scoreValue", "gpa", "passFlag", "specificReason", "itemList",
            ),
            detail.keys,
            "`ScoreDetail` 的十二格（字段名一个不改）",
        )
        assertEquals(JwappFakeUpstream.COURSE_NAME, detail.optString("courseName"))
        assertEquals(JwappFakeUpstream.COURSE_POINT, detail.optString("coursePoint")?.toDouble())
        assertEquals(JwappFakeUpstream.COURSE_EXAM_TYPE, detail.optString("examType"))
        assertEquals(JwappFakeUpstream.COURSE_EXAM_PROP, detail.optString("examProp"))
        assertEquals(JwappFakeUpstream.DETAIL_SCORE, detail.optString("score"))
        assertEquals(88.0, detail.optString("scoreValue")?.toDouble(), "`score` 解析出来的数字")
        assertEquals(JwappFakeUpstream.DETAIL_GPA, detail.optString("gpa")?.toDouble())
        assertEquals(true, detail.optBoolean("passFlag"))
        assertNull(detail.optString("majorFlag"), "夹具那一格没给 ⇒ null（§4：null ≠ 字段缺失）")

        val items = detail.arrOf("itemList")
        assertEquals(2, items.size)
        val first = items[0] as JsonObject
        assertEquals(setOf("itemName", "itemPercent", "itemScore", "itemScoreValue"), first.keys)
        assertEquals(JwappFakeUpstream.ITEM_1_NAME, first.optString("itemName"))
        assertEquals(
            0.3,
            first.optString("itemPercent")?.toDouble(),
            "上游那个「30%」在 :data 已经变成比例 0.3（本层不除第二次）",
        )
        assertEquals(JwappFakeUpstream.ITEM_1_SCORE, first.optString("itemScore"))
        assertEquals(85.0, first.optString("itemScoreValue")?.toDouble())

        assertEquals(1, fake.jwapp.detailCalls.get(), "这一枪真打到了 scoreDetail")
        assertEquals(
            """{"id":"${JwappFakeUpstream.COURSE_ID}"}""",
            fake.jwapp.lastDetailBody,
            "请求体就是 {id} 一格（与 :data 的 JwappApi.getDetail 同一形状）",
        )
    }

    @Test
    fun `score-detail：没有分项 ⇒ 200 且 detail 是 null（不是 502 或 404）+ courseId 缺或空白 400`() =
        withFakeProxy { fake ->
            val token = AccessToken.newToken()
            val session = ServeSession()
            val (server, http) = startEndpointServer(token) {
                sessionRoutes(session, token)
                jwappRoutes(session)
            }
            started = server
            assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

            for (query in listOf("", "?courseId=", "?courseId=%20%20")) {
                val bad = http.get(JWAPP_SCORE_DETAIL_PATH + query)
                assertEquals(400, bad.statusCode(), query)
                assertEquals(ApiErrors.BAD_REQUEST, envelopeCode(bad))
                assertTrue(envelopeMessage(bad).orEmpty().contains("courseId"), "文案要说清缺哪个参数：$query")
            }
            assertEquals(0, fake.jwapp.detailCalls.get(), "参数不对那一枪不该到取数层")

            val missing = http.get("$JWAPP_SCORE_DETAIL_PATH?courseId=${JwappFakeUpstream.DETAIL_MISSING_ID}")
            assertEquals(200, missing.statusCode(), "「这门课没有分项」是上游明确说没有，不是故障")
            assertEquals(ApiEnvelope.CODE_OK, envelopeCode(missing))
            assertNoIdentity(missing.body(), "GET $JWAPP_SCORE_DETAIL_PATH（没有分项那一档）", IDENTITY)
            val data = envelopeData(missing)
            assertEquals(JwappFakeUpstream.DETAIL_MISSING_ID, data.optString("courseId"))
            assertTrue(data.getValue("detail") is JsonNull, "detail 是显式的 null（§4：null ≠ 字段缺失）")
            assertEquals(1, fake.jwapp.detailCalls.get())
        }

    // ── 教室座位数（emptyroom/seat-count）──────────────────────────────

    @Test
    fun `seat-count：逐字段（免登录那一档；拿不到就是 null 不是 0）`() = withFakeProxy { fake ->
        val token = AccessToken.newToken()
        val session = ServeSession()
        val (server, http) = startEndpointServer(token) {
            sessionRoutes(session, token)
            serveEndpointRoutes(session)
        }
        started = server

        // 与 `/api/emptyroom/cdn` 同一档（CDN 免登录）⇒ 没登录也答得到这一格
        val response = http.get("$EMPTY_ROOM_SEAT_COUNT_PATH?room=${EmptyRoomFakeUpstream.ROOM_A101}")
        assertEquals(200, response.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(response))
        assertNoIdentity(response.body(), "GET $EMPTY_ROOM_SEAT_COUNT_PATH", IDENTITY)

        val data = envelopeData(response)
        assertEquals(setOf("room", "seats"), data.keys)
        assertEquals(EmptyRoomFakeUpstream.ROOM_A101, data.optString("room"), "教室名原样回")
        assertEquals(EmptyRoomFakeUpstream.SEATS_A101, data.optInt("seats"), "座位数来自当天那份 CDN 课表")
        assertTrue(
            fake.emptyRoom.hasDataOn(fake.emptyRoom.lastCdnDate.orEmpty()),
            "读的是有数据的那一天：${fake.emptyRoom.lastCdnDate}",
        )

        // 名字对不上 ⇒ null（不是 0、也不是 502）
        val unknown = http.get("$EMPTY_ROOM_SEAT_COUNT_PATH?room=$UNKNOWN_ROOM")
        assertEquals(200, unknown.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(unknown))
        val unknownData = envelopeData(unknown)
        assertEquals(UNKNOWN_ROOM, unknownData.optString("room"))
        assertTrue(
            unknownData.getValue("seats") is JsonNull,
            "拿不到就是 null（§4：null = 上游明确说没有），不拿 0 冒充",
        )
    }

    @Test
    fun `seat-count：room 缺或空白 400（参数不对不打上游）`() = withFakeProxy { fake ->
        val token = AccessToken.newToken()
        val session = ServeSession()
        val (server, http) = startEndpointServer(token) { serveEndpointRoutes(session) }
        started = server

        for (query in listOf("", "?room=", "?room=%20")) {
            val bad = http.get(EMPTY_ROOM_SEAT_COUNT_PATH + query)
            assertEquals(400, bad.statusCode(), query)
            assertEquals(ApiErrors.BAD_REQUEST, envelopeCode(bad))
            assertTrue(envelopeMessage(bad).orEmpty().contains("room"), "文案要说清缺哪个参数：$query")
        }
        assertEquals(0, fake.emptyRoom.cdnCalls.get(), "参数不对那一枪不该到取数层")
    }

    // ── 脚手架 ─────────────────────────────────────────────────────────

    private companion object {
        val LOGIN_BODY =
            """{"username":"${LibraryFakeUpstream.USERNAME}","password":"${LibraryFakeUpstream.PASSWORD}"}"""

        const val JWXT_CHANGES_PATH = "/api/jwxt/changes"
        const val JWXT_EXAMS_PATH = "/api/jwxt/exams"
        const val JWXT_TEXTBOOKS_PATH = "/api/jwxt/textbooks"
        const val JWAPP_SCORE_DETAIL_PATH = "/api/jwapp/score-detail"
        const val EMPTY_ROOM_SEAT_COUNT_PATH = "/api/emptyroom/seat-count"

        /** 夹具那两份样本里都没有这间教室 ⇒ `seatCount` 查不到（编造值）。 */
        const val UNKNOWN_ROOM = "主楼Z-999"

        /**
         * §5.2 红线的钉子组（编造值，非真人）：学号 / 姓名 / 教师名一律不许出现。
         *
         * `SCHEDULE_1_TEACHER` 是**调停补课报文里真有**的上课教师名（`YSKJS`/`XSKJS` 那两格，
         * 形状是「姓名/工号」）—— 那一格没进 `ScheduleChangeEvent`，所以这条断言证明的是
         * 「红线在，而不是夹具里没有」。
         */
        val IDENTITY = listOf(
            "username",
            "studentId",
            "学号",
            "姓名",
            LibraryFakeUpstream.USERNAME,
            JwxtFakeUpstream.STUDENT_ID,
            JwxtFakeUpstream.SCHEDULE_1_TEACHER,
            JwxtFakeUpstream.SCHEDULE_2_TEACHER,
        )
    }
}
