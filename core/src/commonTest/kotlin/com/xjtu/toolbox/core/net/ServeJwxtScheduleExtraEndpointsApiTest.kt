package com.xjtu.toolbox.core.net

import com.xjtu.toolbox.schedule.ScheduleChangeEvent
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * serve 契约 §5.8 的**客户端那一侧**：「我的课表」那三条只读端点
 *（`/api/jwxt/changes` + `/exams` + `/textbooks`，[CampusJwxtScheduleApi]）。
 *
 * 为什么值得写：这三条是 2026-10-14 才接进 `WebScheduleSource` 的，而 `:web` **没有测试源集**
 * （D14 才建）⇒ 形状与纪律只能在这里钉。MockEngine 喂的是 `:server` 实现出来的形状
 *（`server/…/JwxtRoutes.kt` 的 DTO 就是这些字段），钉三件事：
 *
 *  1. **行就是 `:core` 的模型**：`kind` 是枚举名、`weeks` 是周次数组、教材九格里的 `hasTextbook`
 *     就是 `TextbookItem.hasSubstantiveTextbook`（不是第二份判据）；
 *  2. **`?term=` 的读法与 `/api/jwxt/schedule` 同一条**：显式传就带上、空/空白就整个不发；
 *  3. **不编也不滤**：占位 ISBN 原样透传（滤它是屏的事）、失败只读 `message`、非 serve 分支抛错。
 */
class ServeJwxtScheduleExtraEndpointsApiTest {

    /** 一条发出去的请求：路径 + 完整 URL（判参数有没有发出去靠它）。 */
    private class Call(val path: String, val url: String)

    private fun engineFor(
        vararg bodies: Pair<String, String>,
        calls: MutableList<Call>? = null,
    ): MockEngine = MockEngine { request ->
        calls?.add(Call(request.url.encodedPath, request.url.toString()))
        val body = bodies.toMap().entries.firstOrNull { request.url.encodedPath.endsWith(it.key) }?.value
            ?: error("测试没有为 ${request.url.encodedPath} 准备响应")
        respond(
            content = body,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType to listOf("application/json")),
        )
    }

    private val changesBody = """
        {"code":0,"data":{"term":"2026-2027-1","count":1,"changes":[
          {"courseName":"高等数学（上）","courseCode":"MATH1001","kind":"MOVED",
           "fromDay":2,"fromStartSection":1,"fromEndSection":2,
           "toDay":2,"toStartSection":1,"toEndSection":2,
           "toLocation":"主楼A-103","weeks":[8],"toWeeks":[8]}]}}
    """.trimIndent()

    private val examsBody = """
        {"code":0,"data":{"term":"2026-2027-1","count":1,"exams":[
          {"courseName":"高等数学（上）","courseCode":"MATH1001","examDate":"2027-01-15",
           "examTime":"14:30-16:30","location":"主楼A-101","seatNumber":"12"}]}}
    """.trimIndent()

    private val textbooksBody = """
        {"code":0,"data":{"term":"2026-2027-1","count":2,"books":[
          {"courseName":"高等数学（上）","textbookName":"高等数学（第七版）","author":"同济大学",
           "publisher":"高等教育出版社","isbn":"9787040396614","price":"45.00","edition":"7",
           "courseCode":"MATH1001","hasTextbook":true},
          {"courseName":"体育","textbookName":"无教材","author":"","publisher":"",
           "isbn":"9780000000000","price":"","edition":"","courseCode":"PE1001","hasTextbook":false}]}}
    """.trimIndent()

    private fun api(calls: MutableList<Call>? = null) = CampusJwxtScheduleApi(
        createToolboxClient(
            engine = engineFor(
                "/api/jwxt/changes" to changesBody,
                "/api/jwxt/exams" to examsBody,
                "/api/jwxt/textbooks" to textbooksBody,
                calls = calls,
            ),
        ),
        "",
        ApiMode.SERVE,
    )

    /**
     * 三条端点各自的行形状：调停补课的十二格（`kind` 枚举名 / 周次数组 / 换到的那间教室）、
     * 考试的六格、教材的八格 + `hasTextbook`（占位 ISBN **原样**透传，滤它是屏的事）。
     */
    @Test
    fun serveThreeEndpointsFollowCoreModels() = runTest {
        val calls = mutableListOf<Call>()
        val api = api(calls)

        val change = api.changes("2026-2027-1").single()
        assertEquals("/api/jwxt/changes", calls[0].path)
        assertTrue(calls[0].url.endsWith("?term=2026-2027-1"), "显式学期要原样传下去：${calls[0].url}")
        assertEquals(ScheduleChangeEvent.Kind.MOVED, change.kind, "kind 是枚举名，本端零转换")
        assertEquals("高等数学（上）", change.courseName)
        assertEquals(listOf(8), change.weeks)
        assertEquals("主楼A-103", change.toLocation, "换到的那间教室")
        assertEquals(2, change.fromDay)

        val exam = api.examSchedule("2026-2027-1").single()
        assertEquals("/api/jwxt/exams", calls[1].path)
        assertEquals("2027-01-15", exam.examDate, "examDate 是上游剥好的原文，不是 ISO-8601 里的时刻")
        assertEquals("14:30-16:30", exam.examTime)
        assertEquals("12", exam.seatNumber)

        val books = api.textbooks("2026-2027-1")
        assertEquals("/api/jwxt/textbooks", calls[2].path)
        assertEquals(2, books.size)
        val first = books.first()
        assertEquals("高等数学（第七版）", first.textbookName)
        assertEquals("9787040396614", first.isbn)
        assertEquals("MATH1001", first.courseCode)
        assertTrue(first.hasSubstantiveTextbook)
        // 第九格 `hasTextbook:false` 的那一行：本端按同一个判据得 false，占位 ISBN 也原样在（不在这里滤）
        val placeholder = books.last()
        assertFalse(placeholder.hasSubstantiveTextbook, "书名是「无教材」⇒ :core 的判据自己就能认出来")
        assertEquals("9780000000000", placeholder.isbn, "占位 ISBN 原样透传：滤它是 CourseLinkSections.realIsbn 那一处")
    }

    /** `?term=` 的读法与 `/api/jwxt/schedule` 同一个：空 / 空白 = 省略（服务端拿当前学期），不自己造一个学期码。 */
    @Test
    fun serveTermIsOmittedWhenBlankAndSentWhenGiven() = runTest {
        val calls = mutableListOf<Call>()
        val api = api(calls)

        api.changes("   ")
        assertFalse(calls[0].url.contains("term="), "空白串 = 省略，不该发出去：${calls[0].url}")
        api.changes("2026-2027-1")
        assertTrue(calls[1].url.endsWith("?term=2026-2027-1"))
        api.examSchedule(null)
        assertFalse(calls[2].url.contains("term="))
    }

    /** 失败只读 `message`（旧路的 `msg` 在新契约里不存在），抛的是能直接给用户看的那句中文。 */
    @Test
    fun serveFailureReadsMessageOnly() = runTest {
        val engine = engineFor(
            "/api/jwxt/changes" to
                """{"code":502,"data":null,"message":"教务会话已失效，请重新登录","msg":"旧路的 msg 不该被读"}""",
        )
        val api = CampusJwxtScheduleApi(createToolboxClient(engine = engine), "", ApiMode.SERVE)
        val e = assertFailsWith<IllegalStateException> { api.changes(null) }
        assertTrue(e.message?.contains("教务会话已失效") == true, "实际：${e.message}")
    }

    /**
     * 非 serve 分支抛错而不是编一份空数据：这三条在 campus-api 里没有（那一头的字段名本端也不认，
     * 契约 §7 第 2 条）—— 空表会把「形态不对」说成「真没有」。调用方（`WebScheduleSource`）自己
     * 在这一形态下回空表，并在日程页顶部写明那两栏为什么是空的。
     */
    @Test
    fun campusApiModeThrowsInsteadOfFakingEmptyData() = runTest {
        val engine = engineFor(
            "/api/jwxt/changes" to """{"code":0,"data":{"changes":[]}}""",
            "/api/jwxt/exams" to """{"code":0,"data":{"exams":[]}}""",
            "/api/jwxt/textbooks" to """{"code":0,"data":{"books":[]}}""",
        )
        // 注意：不传 mode（默认 = CAMPUS_API）
        val api = CampusJwxtScheduleApi(createToolboxClient(engine = engine), "")
        assertFailsWith<UnsupportedOperationException> { api.changes(null) }
        assertFailsWith<UnsupportedOperationException> { api.examSchedule(null) }
        assertFailsWith<UnsupportedOperationException> { api.textbooks(null) }
    }
}
