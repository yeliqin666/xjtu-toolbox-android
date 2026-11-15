package com.xjtu.toolbox.server

import com.xjtu.toolbox.degree.DegreeCurrentCourse
import com.xjtu.toolbox.degree.DegreeGrade
import com.xjtu.toolbox.degree.DegreePlanPage
import com.xjtu.toolbox.degree.DegreePlanSummary
import com.xjtu.toolbox.degree.DegreeRaw
import com.xjtu.toolbox.degree.DegreeSource
import com.xjtu.toolbox.library.LibraryFakeUpstream
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 学业进度 `/api/degree/…` 的契约测试（§5.16）。上游用假的 [DegreeSource]，教务那串请求不在这里测。 */
class ServeDegreeTest {

    private var started: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    @AfterTest
    fun stopServer() {
        started?.stop(gracePeriodMillis = 100, timeoutMillis = 500)
        started = null
    }

    @Test
    fun `未登录 401 且不碰取数层`() = withFakeProxy {
        val token = AccessToken.newToken()
        val session = ServeSession()
        val fake = FakeDegreeSource()
        val (server, http) = startEndpointServer(token) {
            sessionRoutes(session, token)
            degreeRoutes(session) { fake }
        }
        started = server

        for (path in listOf(PROGRESS, "$PLANS?keyword=计算机", "$PLAN?code=P2", CURRENT)) {
            val response = http.get(path)
            assertEquals(401, response.statusCode(), path)
            assertEquals(ApiErrors.UNAUTHORIZED, envelopeCode(response))
        }
        assertEquals(401, http.postJson(RECALC, "{}").statusCode())
        assertEquals(0, fake.calls, "未登录那一枪不该到取数层")
    }

    @Test
    fun `progress 逐字段且剥掉本人身份`() = withFakeProxy {
        val (http, fake) = loggedIn()

        val response = http.get(PROGRESS)
        assertEquals(200, response.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(response))
        assertNoIdentity(response.body(), "GET $PROGRESS", listOf(STUDENT_ID, STUDENT_NAME, PHONE))

        val data = envelopeData(response)
        assertEquals(
            setOf("planCode", "planName", "required", "notes", "grade", "groups", "courses", "report", "grades", "currentCourses"),
            data.keys,
        )
        assertEquals("P1", data.optString("planCode"))
        assertEquals("2023", data.optString("grade"))
        val group = data.arrOf("groups")[0].jsonObject
        assertEquals("G1", group.optString("KZH"))
        assertTrue("XH" !in group.keys && "XM" !in group.keys)
        val report = data.getValue("report").jsonObject
        assertEquals(setOf("fanbx", "calculatedTime"), report.keys)
        val node = report.arrOf("fanbx")[0].jsonObject
        assertTrue("XH" !in node.keys, "嵌套的身份字段也要剥")
        assertEquals("必修", node.optString("courseCategoryName"))
        val grade = data.arrOf("grades")[0].jsonObject
        assertEquals(setOf("code", "name", "credit", "score", "passed", "gpa", "term"), grade.keys)
        assertEquals(true, grade.optBoolean("passed"))
        assertEquals("C1", data.arrOf("currentCourses")[0].jsonObject.optString("code"))
        assertEquals(1, fake.calls)
    }

    @Test
    fun `plans 与 plan：参数校验和换方案`() = withFakeProxy {
        val (http, fake) = loggedIn()

        assertEquals(400, http.get(PLANS).statusCode(), "keyword 和 grade 都没给")
        assertEquals(400, http.get(PLAN).statusCode(), "code 必填")
        assertEquals(0, fake.calls)

        val plans = http.get("$PLANS?keyword=%E8%AE%A1%E7%AE%97%E6%9C%BA&grade=2023")
        assertEquals(200, plans.statusCode())
        val page = envelopeData(plans)
        assertEquals(setOf("total", "plans"), page.keys)
        assertEquals(1, page.optInt("total"))
        assertEquals(setOf("code", "name", "college", "grade"), page.arrOf("plans")[0].jsonObject.keys)
        assertEquals("计算机" to "2023", fake.lastSearch)

        val plan = http.get("$PLAN?code=P2&name=X&college=Y&grade=2023")
        assertEquals(200, plan.statusCode())
        assertEquals(DegreePlanSummary("P2", "X", "Y", "2023"), fake.lastPlan)
        val data = envelopeData(plan)
        assertEquals("P2", data.optString("planCode"))
        assertTrue(data["report"] == null || data["report"].toString() == "null", "别的方案没有教务报告")
    }

    @Test
    fun `recalculate 与上游失败 502`() = withFakeProxy {
        val (http, fake) = loggedIn()

        val ok = http.postJson(RECALC, "{}")
        assertEquals(200, ok.statusCode())
        assertEquals(true, envelopeData(ok).optBoolean("done"))

        fake.fail = true
        val failed = http.get(PROGRESS)
        assertEquals(502, failed.statusCode())
        assertEquals(502, envelopeCode(failed))
    }

    private fun loggedIn(): Pair<EndpointHarness, FakeDegreeSource> {
        val token = AccessToken.newToken()
        val session = ServeSession()
        val fake = FakeDegreeSource()
        val (server, http) = startEndpointServer(token) {
            sessionRoutes(session, token)
            degreeRoutes(session) { fake }
        }
        started = server
        assertEquals(200, http.postJson("$SESSION_PATH/login", LOGIN_BODY).statusCode())
        return http to fake
    }

    private class FakeDegreeSource : DegreeSource {
        var calls = 0
        var fail = false
        var lastSearch: Pair<String, String>? = null
        var lastPlan: DegreePlanSummary? = null

        private fun hit() {
            calls++
            if (fail) error("教务不可达")
        }

        override suspend fun fetch(): DegreeRaw { hit(); return raw("P1", withReport = true) }
        override suspend fun recalculate(): Boolean { hit(); return true }
        override suspend fun searchPlans(keyword: String, grade: String): DegreePlanPage {
            hit(); lastSearch = keyword to grade
            return DegreePlanPage(listOf(DegreePlanSummary("P2", "计算机科学与技术", "计算机学院", grade)), 1)
        }
        override suspend fun fetchPlan(plan: DegreePlanSummary): DegreeRaw {
            hit(); lastPlan = plan
            return raw(plan.code, withReport = false)
        }
    }

    private companion object {
        const val PROGRESS = "/api/degree/progress"
        const val PLANS = "/api/degree/plans"
        const val PLAN = "/api/degree/plan"
        const val CURRENT = "/api/degree/current-courses"
        const val RECALC = "/api/degree/recalculate"
        const val STUDENT_ID = "2230000001"
        const val STUDENT_NAME = "张三丰"
        const val PHONE = "13800000000"

        val LOGIN_BODY =
            """{"username":"${LibraryFakeUpstream.USERNAME}","password":"${LibraryFakeUpstream.PASSWORD}"}"""

        fun raw(code: String, withReport: Boolean) = DegreeRaw(
            planCode = code,
            planName = "方案$code",
            required = 160.0,
            notes = "说明",
            groups = listOf(buildJsonObject {
                put("KZH", "G1"); put("KZM", "通识"); put("XH", STUDENT_ID); put("XM", STUDENT_NAME)
            }),
            courses = listOf(buildJsonObject { put("KCH", "C1"); put("KCM", "高数"); put("SJH", PHONE) }),
            report = if (!withReport) null else buildJsonObject {
                put("fanbx", buildJsonArray {
                    add(buildJsonObject { put("courseCategoryName", "必修"); put("XH", STUDENT_ID) })
                })
                put("calculatedTime", "2026-11-15 10:00")
                put("XM", STUDENT_NAME)
            },
            grades = listOf(DegreeGrade("C1", "高数", 5.0, "90", true, 4.0, "2025-2026-1")),
            grade = "2023",
            currentCourses = listOf(DegreeCurrentCourse("C1", "高数", "2026-2027-1")),
        )
    }
}
