package com.xjtu.toolbox.core.net

import com.xjtu.toolbox.judge.CampusJudgeApi
import com.xjtu.toolbox.judge.CampusQuestionnaire
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * serve 契约 §5.12（C-2 那一批）的客户端那一侧：作业截止 / 思源课程配对 / 评教提交。
 *
 * 夹具一律编造，不打真实站点。
 */
class ServeC2EndpointsApiTest {

    /** 一发请求：路径 + 方法 + 查询参数 + 请求体。 */
    private data class Call(
        val path: String,
        val method: HttpMethod,
        val query: Map<String, String>,
        val body: String?,
    )

    private fun engineFor(
        vararg bodies: Pair<String, String>,
        calls: MutableList<Call>? = null,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): MockEngine {
        val byPath = bodies.toMap()
        return MockEngine { request ->
            calls?.add(
                Call(
                    path = request.url.encodedPath,
                    method = request.method,
                    query = request.url.parameters.names().associateWith { request.url.parameters[it].orEmpty() },
                    body = (request.body as? TextContent)?.text,
                ),
            )
            val body = byPath.entries.firstOrNull { request.url.encodedPath.endsWith(it.key) }?.value
                ?: error("测试没有为 ${request.url.encodedPath} 准备响应")
            respond(body, status, headersOf(HttpHeaders.ContentType to listOf("application/json")))
        }
    }

    private fun lms(engine: MockEngine, mode: ApiMode = ApiMode.SERVE) =
        CampusLmsApi(createToolboxClient(engine = engine), "", mode)

    // ── 作业截止（B-5）────────────────────────────────────────────────

    /** `GET /api/lms/due` 的八格：作业标题 / 课程名不是个人数据，`fetchedAt` 是 ISO-8601。 */
    private val dueBody = """
        {"code":0,"data":{"count":2,"items":[
          {"courseId":101,"courseName":"高等数学（上）","activityId":9001,"title":"第 3 次作业",
           "deadline":"2026-10-25 23:59","submitted":false,"exam":false,"fetchedAt":"2026-10-21T10:00:00+08:00"},
          {"courseId":102,"courseName":"大学物理","activityId":9002,"title":"单元测试",
           "deadline":"","submitted":false,"exam":true,"fetchedAt":null}]}}
    """.trimIndent()

    @Test
    fun `dueHomeworks 逐字段：八格、fetchedAt 的 ISO 真转成毫秒、exam 那一格原样带过来`() = runTest {
        val calls = mutableListOf<Call>()
        val items = lms(engineFor("/api/lms/due" to dueBody, calls = calls)).dueHomeworks()

        assertEquals(2, items.size)
        val first = items[0]
        assertEquals(101, first.courseId)
        assertEquals("高等数学（上）", first.courseName)
        assertEquals(9001, first.activityId)
        assertEquals("第 3 次作业", first.title)
        assertEquals("2026-10-25 23:59", first.deadline, "上游给的截止文本原样留，本层不编一个时刻")
        assertFalse(first.submitted)
        assertFalse(first.exam)
        assertEquals(1_792_548_000_000L, first.fetchedAt, "2026-10-21T10:00:00+08:00 的毫秒数")

        // `fetchedAt: null` ⇒ 0（= 不知道什么时候拉的），不是编一个此刻
        assertEquals(0L, items[1].fetchedAt)
        assertTrue(items[1].exam, "学堂的「测试」那一格原样带过来（只进待办、不进日程是消费方的事）")

        assertEquals(HttpMethod.Get, calls.single().method)
        assertEquals("/api/lms/due", calls.single().path)
        assertTrue(calls.single().query.isEmpty(), "这一条不收参数（按服务端自己的账号读）")
    }

    // ── 思源课程配对（B-4）──────────────────────────────────────────────

    private val matchedBody = """
        {"code":0,"data":{"matched":true,"course":{
          "id":101,"name":"高等数学（上）","courseCode":"MATH1001","courseType":1,"credit":"5",
          "compulsory":true,"startDate":"2026-09-14","endDate":"2027-01-18",
          "academicYear":{"id":1,"code":"2026","name":"2026-2027学年","sort":1},
          "semester":{"id":3,"code":"2026-2027-1","name":"第一学期","realName":"2026-2027学年第一学期","sort":1},
          "department":{"id":7,"name":"数学与统计学院","code":"MATH"},
          "courseAttributes":{"published":true,"studentCount":120,"teachingClassName":"高数上-01班"}}}}
    """.trimIndent()

    @Test
    fun `courseMatch 配上了就回那门课，三个参数都进 query`() = runTest {
        val calls = mutableListOf<Call>()
        val course = lms(engineFor("/api/lms/course-match" to matchedBody, calls = calls)).courseMatch(
            courseCode = "MATH1001",
            courseName = "高等数学（上）",
            teacher = "示例教师",
        )

        assertEquals(101, course?.id)
        assertEquals("MATH1001", course?.courseCode)
        assertEquals("高等数学（上）", course?.name)

        assertEquals("/api/lms/course-match", calls.single().path)
        assertEquals("MATH1001", calls.single().query["courseCode"])
        assertEquals("高等数学（上）", calls.single().query["courseName"])
        assertEquals("示例教师", calls.single().query["teacher"])
    }

    @Test
    fun `courseMatch 没配上回 null（「没有对应」不是错误）`() = runTest {
        val course = lms(
            engineFor("/api/lms/course-match" to """{"code":0,"data":{"matched":false,"course":null}}"""),
        ).courseMatch(courseCode = "", courseName = "没有这门课")
        assertNull(course)
    }

    // ── 评教提交（B-12 / C-2）──────────────────────────────────────────

    @Test
    fun `serve 形态下评教能提交：请求体四格、已经在已评名单也当成功（幂等）`() = runTest {
        val calls = mutableListOf<Call>()
        val api = CampusJudgeApi(
            createToolboxClient(engine = engineFor("/api/jwxt/evaluations/submit" to """{"code":0,"data":{"submitted":true}}""", calls = calls)),
            "",
            ApiMode.SERVE,
            term = "2026-2027-1",
        )
        assertTrue(api.canSubmit, "serve 形态下真能提交（契约 §5.12）")

        api.judge(CampusQuestionnaire(wjdm = "W1", jxbid = "J1", courseName = "高等数学（上）", teacher = "示例教师", pgType = "01", finished = false))

        val call = calls.single()
        assertEquals(HttpMethod.Post, call.method)
        assertEquals("/api/jwxt/evaluations/submit", call.path)
        val body = call.body.orEmpty()
        for (needle in listOf("\"term\":\"2026-2027-1\"", "\"type\":\"01\"", "\"wjdm\":\"W1\"", "\"jxbid\":\"J1\"")) {
            assertTrue(body.contains(needle), "请求体里该有 $needle，实际：$body")
        }

        // `submitted:false`（这门课已经在已评名单里）也是成功：契约写明那一档是幂等的 200
        calls.clear()
        val again = CampusJudgeApi(
            createToolboxClient(engine = engineFor("/api/jwxt/evaluations/submit" to """{"code":0,"data":{"submitted":false}}""", calls = calls)),
            "",
            ApiMode.SERVE,
        )
        again.judge(CampusQuestionnaire("W1", "J1", "高等数学（上）", "示例教师", "01", true))
        assertEquals(1, calls.size, "幂等那一档照旧接受，不抛错")
    }

    @Test
    fun `评教提交被上游拒（409）时是异常，文案原样带上`() = runTest {
        val api = CampusJudgeApi(
            createToolboxClient(
                engine = engineFor(
                    "/api/jwxt/evaluations/submit" to """{"code":409,"message":"这门课当前不在评教窗口里"}""",
                ),
            ),
            "",
            ApiMode.SERVE,
        )
        val error = assertFailsWith<IllegalStateException> {
            api.judge(CampusQuestionnaire("W1", "J1", "高等数学（上）", "示例教师", "01", false))
        }
        assertTrue(error.message.orEmpty().contains("评教窗口"), "实际：${error.message}")
    }

    @Test
    fun `campus-api 形态下：评教只读（canSubmit 为 false，交了就抛）、lms 那两条一律 serveOnly`() = runTest {
        val api = CampusJudgeApi(createToolboxClient(engine = engineFor()), "", ApiMode.CAMPUS_API)
        assertFalse(api.canSubmit, "campus-api 那一形态没有提交端点 ⇒ 屏上那个块也不画")
        assertFailsWith<IllegalStateException> {
            api.judge(CampusQuestionnaire("W1", "J1", "高等数学（上）", "示例教师", "01", false))
        }

        val lmsApi = lms(engineFor(), ApiMode.CAMPUS_API)
        for (block in listOf<suspend () -> Unit>(
            { lmsApi.dueHomeworks() },
            { lmsApi.courseMatch("MATH1001", "高等数学（上）") },
        )) {
            val error = assertFailsWith<UnsupportedOperationException> { block() }
            assertTrue(error.message.orEmpty().contains("serve"), "要说清「只在 serve 模式下有」：${error.message}")
        }
    }
}
