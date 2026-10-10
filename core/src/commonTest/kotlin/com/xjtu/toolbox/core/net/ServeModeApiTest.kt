package com.xjtu.toolbox.core.net

import com.xjtu.toolbox.emptyroom.RoomSource
import com.xjtu.toolbox.faculty.FacultySearchQuery
import com.xjtu.toolbox.fitness.FitnessSource
import com.xjtu.toolbox.schedule.SchoolCourseQuery
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * serve 模式的解析（[ApiMode.SERVE]）—— 契约 `docs/api-contract.md` §4/§5 的**客户端那一侧**。
 *
 * 为什么值得写：serve 模式把信封口径改了（`code` 就是 HTTP 状态码）、把「要改」的那批字段补齐了，
 * 而 serve 模式的取数在浏览器里只能用真 `:server` 验证 —— 这里用 MockEngine 喂**服务端实现
 * 的形状**（`server/src/main/kotlin/com/xjtu/toolbox/server/` 的 DTO 就是这些字段），
 * 把「错误信封怎么读、新字段怎么接、能力开关怎么消费」钉死，不依赖真服务器。
 *
 * 默认形态（[ApiMode.CAMPUS_API]）由既有测试钉着（`CampusEmptyRoomApiTest` 等），
 * 这里最后一条再补一个回归：**不传 mode 时还吃得下旧的 campus-api 形状**。
 */
class ServeModeApiTest {

    /** 一个只按路径答夹具的 MockEngine（与既有测试同一套写法）。 */
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
     * 契约 §4 最要紧的一条：失败时 `code` **就是 HTTP 状态码**（这里是 401），
     * `message` 是能直接给用户看的中文短句。这条钉的是「不许把 HTTP 状态码当业务码解」：
     * `code != 0` → 失败并显示 `message`（旧路是 `code != 0` + 读 `msg`/`error` 两个字段）。
     */
    @Test
    fun serveErrorEnvelopeCodeIsHttpStatusAndMessageIsShown() = runTest {
        val engine = engineFor(
            "/api/info/faculty" to
                """{"code":401,"data":null,"message":"请先登录：POST /api/session/login"}""",
        )
        val api = CampusFacultyApi(createToolboxClient(engine = engine), "", ApiMode.SERVE)
        val e = assertFailsWith<IllegalStateException> {
            api.search(FacultySearchQuery(name = "刘"), 1)
        }
        assertTrue(
            e.message?.contains("请先登录") == true,
            "失败文案要能直接给用户看（就是信封里的 message），实际：${e.message}",
        )
    }

    /**
     * 契约 §5.2 例外①：`fitness/score` 是**本人的数据**，姓名/学号可投影 —— 旧路两行写死的空串
     * 是 campus-api 隐私口径的降级。另外分项**直接用服务端给的名字**（不再按 key + sex 重推一遍）。
     */
    @Test
    fun serveFitnessScoreCarriesStudentNameAndNumber() = runTest {
        val body = """
            {"code":0,"data":{"studentNumber":"2253415556","studentName":"测试同学",
              "totalScore":"83.4","totalGrade":"良好","reportType":"正常","reportStatus":"已审核",
              "sex":"女","grade":"2025",
              "items":[{"name":"身高 / 体重","value":"165.0cm/52.0kg","grade":"正常","tone":"green"}]}}
        """.trimIndent()
        val source: FitnessSource =
            CampusFitnessApi(createToolboxClient(engine = engineFor("/api/fitness/score" to body)), "", ApiMode.SERVE)
        val score = source.score("2026")
        assertEquals("测试同学", score.studentName)
        assertEquals("2253415556", score.studentNumber)
        assertEquals("良好", score.totalGrade)
        assertEquals("身高 / 体重", score.items.single().name)
    }

    /**
     * 契约 §5「要改」的 school-courses：人数/学时、`YPSJDD`（[SchoolCourse.scheduleLocation]）补齐；
     * 两个能力开关（`supportsDepartmentFilter` / `supportsElectiveFilter`）进响应 —— 但客户端报的是
     * 「能力 true × 选项表端点不在契约里」的**与** ⇒ 还是 false（见 CampusSchoolCourseApi 的 KDoc）。
     */
    @Test
    fun serveSchoolCourseReadsNewFieldsAndSwitches() = runTest {
        val body = """
            {"code":0,"data":{"page":1,"size":20,"total":1,
              "supportsDepartmentFilter":true,"supportsElectiveFilter":true,
              "courses":[{"courseCode":"COMP1001","courseName":"程序设计","sectionNumber":"1",
                "teacher":"张三","department":"计算机学院","credit":3.0,
                "totalHours":32.0,"lectureHours":24.0,"labHours":8.0,
                "enrollCount":98,"capacity":100,
                "className":"计算机2301","scheduleLocation":"兴庆校区 主楼A-404 周一1-2节",
                "campus":"兴庆校区","isPublicElective":false,"electiveCategory":"",
                "teachingClassId":"jxb123","termCode":"2025-2026-1"}]}}
        """.trimIndent()
        val api = CampusSchoolCourseApi(
            createToolboxClient(engine = engineFor("/api/jwxt/school-courses" to body)),
            "",
            ApiMode.SERVE,
        )
        val result = api.query(SchoolCourseQuery(termCode = "2025-2026-1"), 1, 20)
        val course = result.courses.single()
        assertEquals(1, result.totalSize)
        assertEquals(32.0, course.totalHours)          // 人数/学时补齐
        assertEquals(8.0, course.labHours)
        assertEquals(98, course.enrollCount)
        assertEquals(100, course.capacity)
        assertEquals("兴庆校区 主楼A-404 周一1-2节", course.scheduleLocation)  // YPSJDD
        // 开关读进了响应（能力 true），但那一张 id 表端点不在契约里 ⇒ 客户端仍报 false（如实降级）
        assertEquals(false, api.supportsDepartmentFilter)
        assertEquals(false, api.supportsElectiveFilter)
    }

    /**
     * 契约 §5「要改」的 emptyroom：`availableSources` 是**响应的一部分**（未登录 [cdn]、登录后
     * [live,cdn,direct]）⇒ 客户端的档位表随响应更新，不再是硬编码的 [CDN]。
     */
    @Test
    fun serveEmptyRoomReadsAvailableSourcesAndRooms() = runTest {
        val body = """
            {"code":0,"data":{"availableSources":[
                {"key":"live","name":"实时状态"},{"key":"cdn","name":"CDN 课表"},{"key":"direct","name":"直查教务"}],
              "source":"cdn","campus":"兴庆校区","buildings":["主楼A"],"date":"2026-10-08",
              "noData":false,"note":null,
              "rooms":[{"campus":"兴庆校区","building":"主楼A","room":"主楼A-102","seats":96,
                "status":[1,1,0,0,1,1,1,1,1,1,1]}]}}
        """.trimIndent()
        val api = CampusEmptyRoomApi(
            createToolboxClient(engine = engineFor("/api/emptyroom/cdn" to body)),
            "",
            ApiMode.SERVE,
        )
        val rooms = api.rooms("兴庆校区", setOf("主楼A"), "2026-10-08", direct = false, force = false) { _, _ -> }
        assertEquals(listOf(RoomSource.LIVE, RoomSource.CDN, RoomSource.DIRECT), api.availableSources)
        assertEquals(listOf("主楼A-102"), rooms.map { it.name })
        assertEquals(96, rooms.single().size)
    }

    /** 契约 §5.2：校历一次给全部学期（不再是 campus-api 那种「一学期一拉再拼」），事件照读。 */
    @Test
    fun serveCalendarIsOneShotFullList() = runTest {
        val body = """
            {"code":0,"data":{"terms":[{"id":"1","startDate":"2025-09-08","endDate":"2026-01-18",
              "termName":"2025-2026学年第一学期","yearName":"2025-2026","totalWeeks":20,"workDays":95,
              "events":[{"id":"国庆-2025-10-01","startDate":"2025-10-01","endDate":"2025-10-07",
                "name":"国庆节","remark":"按国家规定","days":7,"colorHex":"#196dd0"}]}]}}
        """.trimIndent()
        val api = CampusSchoolCalendarApi(
            createToolboxClient(engine = engineFor("/api/calendar/school" to body)),
            "",
            ApiMode.SERVE,
        )
        val term = api.terms().single()
        assertEquals("2025-2026学年第一学期", term.termName)
        assertEquals(95, term.workDays)
        assertEquals("国庆节", term.events.single().name)
    }

    /** C1 红线：不传 [ApiMode] 时，老形状（campus-api）**一字不改**地照旧解析。 */
    @Test
    fun defaultModeStillReadsLegacyCampusShape() = runTest {
        val body = """
            {"code":0,"error":null,"data":{"date":"2026-10-08","campuses":["兴庆校区"],
              "total":1,"matched":1,"truncated":false,
              "rooms":[{"campus":"兴庆校区","building":"主楼A","room":"主楼A-102","seats":96,
                 "status":[1,1,0,0,1,1,1,1,1,1,1],"free":[3,4]}]}}
        """.trimIndent()
        // 注意：不传 mode（默认 = CAMPUS_API）
        val api = CampusEmptyRoomApi(createToolboxClient(engine = engineFor("/api/emptyroom/cdn" to body)))
        val rooms = api.rooms("兴庆校区", setOf("主楼A"), "2026-10-08", direct = false, force = false) { _, _ -> }
        assertEquals(listOf(RoomSource.CDN), api.availableSources)  // 默认：仍然只有 CDN 一档
        assertEquals(listOf("主楼A-102"), rooms.map { it.name })
    }
}