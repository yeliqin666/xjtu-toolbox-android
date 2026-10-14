package com.xjtu.toolbox.server

import com.xjtu.toolbox.jwxt.JwxtFakeUpstream
import com.xjtu.toolbox.library.LibraryFakeUpstream
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject

/**
 * `GET /api/jwxt/schedule?merged=1` 的契约测试（`docs/api-contract.md` §5.9）——
 * **批①收尾**（2026-10-14）新开的那一档：端出 `:data` 的 `JwxtScheduleApi.schedule()`
 * 已经合过调停补课的课表（Android / 桌面端课表屏吃的那一张）。
 *
 * ## 为什么这一批要它
 *
 * `:web` 的日程屏此前给的是**排课原样**（接线批如实不合并，缺口写在页顶）：`/api/jwxt/changes`
 * 端的是说明面的 `ScheduleChangeEvent`，而 `:core` 的合并入口 `JwxtChanges.apply` 吃的是带
 * `jxbid`（认原课）与 `appliedAt`（定先后）的 `JwxtChange` ⇒ 消费方拿到那一条也合不了
 * （`docs/desktop-port-plan.md` §16.5 的第 1 条）。本批按那一条决策**不新增语义面字段**，
 * 而是让这个端点多一档 `?merged=1`。
 *
 * ## 这一组钉的是什么
 *
 * - **默认那一档一字未改**：不带 `merged` 时形状仍是 `{term, rows, count}`、行仍是上游那八列
 *   （`ZCMC` 文本给的是**排课原样**），而且**一次 `xsdkkc.do` 都不打**（`changesCalls == 0`）
 *   —— 「默认行为不变」这条不靠读代码，靠这一条断言；
 * - **`merged=1` 是真合并**：`:data` 的 `JwxtChanges.apply` 拿 [JwxtFakeUpstream.CHANGE_JXBID]
 *   那条调课记录把 `SCHEDULE_1` 第 [JwxtFakeUpstream.CHANGE_WEEK] 周从 [SCHEDULE_1_ROOM] 挖掉，
 *   并在 [CHANGE_NEW_ROOM] 多出一条只上那一周的课 ⇒ `count` 从 3 变 4，两边的 `weekBits` 逐位
 *   可对（不是「多了一条」就算过）；
 * - **形状**：`courses` 是 `:core` `CourseItem` 的**九格**（那两格分钟级时间不投影，§4），
 *   `changes` 与 `/api/jwxt/changes` 同一族十二格；
 * - **参数形状**：`merged` 给了但不是 `1`（含空白串）⇒ 400，且不打上游；
 * - **红线**：响应过 [assertNoIdentity]（调停补课报文里的上课教师名 `SCHEDULE_1_TEACHER`
 *   明明在夹具里，但它进的是 `teacher` 那一格 —— 那是**课程**的教师名，不是身份）。
 *
 * 夹具是 `:testkit` 里早就有的那份 `JwxtFakeUpstream`（`xskcb.do` + `xsdkkc.do` 两个样本），
 * **本批一个常量都没加、一个都没改**。
 */
class ServeJwxtScheduleMergedTest {

    private var started: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    @AfterTest
    fun stopServer() {
        started?.stop(gracePeriodMillis = 100, timeoutMillis = 500)
        started = null
    }

    @Test
    fun `merged=1：真合过调停补课的那一份（挖原周次 + 新教室多一条）+ 省略 term 用当前学期`() =
        withFakeProxy { fake ->
            val token = AccessToken.newToken()
            val session = ServeSession()
            val (server, http) = startEndpointServer(token) {
                sessionRoutes(session, token)
                jwxtRoutes(session)
            }
            started = server
            assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

            val response = http.get("$JWXT_SCHEDULE_PATH?merged=1")
            assertEquals(200, response.statusCode())
            assertEquals(ApiEnvelope.CODE_OK, envelopeCode(response))
            assertNoIdentity(response.body(), "GET $JWXT_SCHEDULE_PATH?merged=1", IDENTITY)

            val data = envelopeData(response)
            assertEquals(setOf("term", "count", "courses", "changes"), data.keys)
            assertEquals(JwxtFakeUpstream.TERM_NEW, data.optString("term"), "省略 ?term= ⇒ 当前学期")

            // 排课原样是 3 行（夹具三行），合并后是 4 条：调课把第 5 周从原行挖走 + 多出一条新教室
            assertEquals(4, data.optInt("count"), "挖剩的 + 新位置那一条 = 4 条")
            val courses = data.arrOf("courses")
            assertEquals(4, courses.size)

            // 每一条都是 `:core` `CourseItem` 的九格（那两格分钟级时间不投影 —— §4）
            for (row in courses) {
                assertEquals(
                    setOf(
                        "courseName", "teacher", "location", "weekBits", "dayOfWeek",
                        "startSection", "endSection", "courseCode", "courseType",
                    ),
                    (row as JsonObject).keys,
                    "`CourseItem` 投影出来的九格",
                )
            }

            // 整张表逐条对：行序是 `JwxtChanges.apply` 给的（先 remove 原课再 append 拆出来的那几条，
            // 新位置那一条最后 append）。第三、四条正是同门课被拆成的两半 —— 一条在原教室上剩下的周，
            // 一条只在新教室上第 CHANGE_WEEK 周。
            assertEquals(
                listOf(
                    Triple(JwxtFakeUpstream.SCHEDULE_2_COURSE, JwxtFakeUpstream.SCHEDULE_2_ROOM, "1010101010101010"),
                    Triple(JwxtFakeUpstream.SCHEDULE_3_COURSE, "", ""),
                    Triple(JwxtFakeUpstream.SCHEDULE_1_COURSE, JwxtFakeUpstream.SCHEDULE_1_ROOM, "1111011111111111"),
                    Triple(JwxtFakeUpstream.SCHEDULE_1_COURSE, JwxtFakeUpstream.CHANGE_NEW_ROOM, JwxtFakeUpstream.CHANGE_BITS),
                ),
                courses.map {
                    val row = it as JsonObject
                    Triple(row.optString("courseName").orEmpty(), row.optString("location").orEmpty(), row.optString("weekBits").orEmpty())
                },
                "第 5 周那一格从原行挖掉（第 16 位全 1 里第 5 位变 0），新位置那一条只在第 5 周",
            )

            val moved = courses[3] as JsonObject
            assertEquals(JwxtFakeUpstream.SCHEDULE_1_TEACHER, moved.optString("teacher"), "新位置继承同教学班的教师")
            assertEquals(JwxtFakeUpstream.SCHEDULE_1_DAY, moved.optInt("dayOfWeek"))
            assertEquals(JwxtFakeUpstream.SCHEDULE_1_START, moved.optInt("startSection"))
            assertEquals(JwxtFakeUpstream.SCHEDULE_1_END, moved.optInt("endSection"))
            // 课程号：scheduleJson 的 KCH 与 changesJson 的 KCH 在夹具里是同一个值（MATH1001）
            assertEquals(JwxtFakeUpstream.EXAM_CODE, moved.optString("courseCode"))

            // `changes` 与 `/api/jwxt/changes` 同一族（同一份报文，不另发一次请求的第二份解析）
            val changes = data.arrOf("changes")
            assertEquals(1, changes.size)
            val change = changes[0] as JsonObject
            assertEquals(
                setOf(
                    "courseName", "courseCode", "kind", "fromDay", "fromStartSection", "fromEndSection",
                    "toDay", "toStartSection", "toEndSection", "toLocation", "weeks", "toWeeks",
                ),
                change.keys,
                "`ScheduleChangeEvent` 的十二格",
            )
            assertEquals("MOVED", change.optString("kind"))

            // 合并要两条报文：既有排课（xskcb.do）也有调停补课（xsdkkc.do）
            assertEquals(1, fake.jwxt.scheduleCalls.get())
            assertEquals(1, fake.jwxt.changesCalls.get(), "merged=1 要打 xsdkkc.do")
            assertTrue(
                fake.jwxt.lastChangesForm.orEmpty().contains("XNXQDM=${JwxtFakeUpstream.TERM_NEW}"),
                "省略 ?term= 时带上去的是当前学期：${fake.jwxt.lastChangesForm}",
            )
        }

    @Test
    fun `不带 merged：默认那一档一字未改（排课原样、不打调停补课那一枪）+ merged 形状不对 400`() =
        withFakeProxy { fake ->
            val token = AccessToken.newToken()
            val session = ServeSession()
            val (server, http) = startEndpointServer(token) {
                sessionRoutes(session, token)
                jwxtRoutes(session)
            }
            started = server
            assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

            // ── 默认那一档：与 §5.3 / ServeP0Group2Test 钉的同一形状，一行未改 ──
            val plain = http.get(JWXT_SCHEDULE_PATH)
            assertEquals(200, plain.statusCode())
            assertEquals(ApiEnvelope.CODE_OK, envelopeCode(plain))
            val plainData = envelopeData(plain)
            assertEquals(setOf("term", "rows", "count"), plainData.keys, "`ScheduleData` 还是那三格")
            assertEquals(JwxtFakeUpstream.TERM_NEW, plainData.optString("term"), "省略 ?term= ⇒ 当前学期")
            assertEquals(3, plainData.optInt("count"), "排课原样就是夹具那三行（不合并 ⇒ 不会多出那一条）")
            val rows = plainData.arrOf("rows")
            assertEquals(3, rows.size)
            assertEquals(
                setOf("KCM", "SKJS", "JASMC", "SKXQ", "KSJC", "JSJC", "ZCMC", "JXBID"),
                (rows[0] as JsonObject).keys,
                "行还是上游那八列（键名 = 上游列名）",
            )
            assertEquals(
                JwxtFakeUpstream.SCHEDULE_1_WEEKS,
                (rows[0] as JsonObject).optString("ZCMC"),
                "周次那一格是 ZCMC 文本（**排课原样**）—— 合并后的位串只出现在 ?merged=1 那一档",
            )
            assertEquals(JwxtFakeUpstream.SCHEDULE_1_ROOM, (rows[0] as JsonObject).optString("JASMC"), "原教室，没有第 5 周那一条")
            assertEquals(1, fake.jwxt.scheduleCalls.get())
            assertEquals(0, fake.jwxt.changesCalls.get(), "默认那一档**一次调停补课都不打**（行为不变）")

            // ── `?merged=` 的形状：只有 `1` 算数（与 `grades` 的 `all` 同一条读法）──
            for (query in listOf("?merged=0", "?merged=", "?merged=true", "?merged=%20")) {
                val bad = http.get(JWXT_SCHEDULE_PATH + query)
                assertEquals(400, bad.statusCode(), query)
                assertEquals(ApiErrors.BAD_REQUEST, envelopeCode(bad))
                assertTrue(envelopeMessage(bad).orEmpty().contains("merged"), "文案要说清是哪个参数：$query")
            }
            assertEquals(1, fake.jwxt.scheduleCalls.get(), "形状不对那一枪不该到取数层")
            assertEquals(0, fake.jwxt.changesCalls.get())

            // ── 显式 term：两个档都原样回那一格，而且真带进上游请求 ──
            val explicit = http.get("$JWXT_SCHEDULE_PATH?term=${JwxtFakeUpstream.TERM_OLD}&merged=1")
            assertEquals(200, explicit.statusCode())
            assertEquals(JwxtFakeUpstream.TERM_OLD, envelopeData(explicit).optString("term"))
            assertTrue(
                fake.jwxt.lastScheduleForm.orEmpty().contains("XNXQDM=${JwxtFakeUpstream.TERM_OLD}") &&
                    fake.jwxt.lastChangesForm.orEmpty().contains("XNXQDM=${JwxtFakeUpstream.TERM_OLD}"),
                "两条报文都要带上请求给的学期：${fake.jwxt.lastScheduleForm} / ${fake.jwxt.lastChangesForm}",
            )
            assertEquals(2, fake.jwxt.scheduleCalls.get())
            assertEquals(1, fake.jwxt.changesCalls.get())
        }

    private companion object {
        val LOGIN_BODY =
            """{"username":"${LibraryFakeUpstream.USERNAME}","password":"${LibraryFakeUpstream.PASSWORD}"}"""

        const val JWXT_SCHEDULE_PATH = "/api/jwxt/schedule"

        /** §5.2 红线的钉子组（编造值，非真人）：学号 / 姓名 / 教师名一律不许出现。 */
        val IDENTITY = listOf(
            "username",
            "studentId",
            "学号",
            "姓名",
            LibraryFakeUpstream.USERNAME,
            JwxtFakeUpstream.STUDENT_ID,
        )
    }
}
