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

/** serve 模式的解析（[ApiMode.SERVE]）—— 契约 `docs/api-contract.md` §4/§5 的客户端那一侧。 */
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

    /** 契约 §4 最要紧的一条：失败时 `code` 就是 HTTP 状态码（这里是 401），`message` 是能直接给用户看的中文短句。 */
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

    /** 契约 §5.2 例外①：`fitness/score` 是本人的数据，姓名/学号可投影。 */
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

    /** 契约 §5「要改」的 school-courses：人数/学时、`YPSJDD`（[SchoolCourse.scheduleLocation]）补齐。 */
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
        assertEquals(false, api.supportsDepartmentFilter)
        assertEquals(false, api.supportsElectiveFilter)
    }

    /** 契约 §5「要改」的 emptyroom。 */
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

    /** 契约 §5.3 的两个课表端点（2026-10-11 落地） */
    @Test
    fun serveScheduleAndTermStart() = runTest {
        val scheduleBody = """
            {"code":0,"data":{"term":"2026-2027-1","count":2,"rows":[
              {"KCM":"高等数学（上）","SKJS":"示例甲","JASMC":"主楼A-101","SKXQ":"1","KSJC":"1",
               "JSJC":"2","ZCMC":"1-16周","JXBID":"JXB-8001","SKZC":"1111111111111111"},
              {"KCM":"大学物理","SKJS":"示例乙","JASMC":"中2-3201","SKXQ":"3","KSJC":"3",
               "JSJC":"4","ZCMC":"1-15周(单)","JXBID":"JXB-8002"}]}}
        """.trimIndent()
        val termStartBody = """
            {"code":0,"data":{"term":"2026-2027-1","startDate":"2026-09-14","totalWeeks":18}}
        """.trimIndent()
        val api = CampusApi(
            createToolboxClient(
                engine = engineFor(
                    "/api/jwxt/schedule" to scheduleBody,
                    "/api/jwxt/term-start" to termStartBody,
                ),
            ),
            "",
            ApiMode.SERVE,
        )

        val start = api.termStart()
        assertEquals("2026-2027-1", start.term)
        assertEquals("2026-09-14", start.startDate)
        assertEquals(18, start.totalWeeks)

        val schedule = api.schedule("2026-2027-1")
        assertEquals("2026-2027-1", schedule.term)
        val rows = schedule.rows
        assertEquals(2, rows.size)
        assertEquals("高等数学（上）", rows[0].courseName)
        assertEquals("示例甲", rows[0].teacher)
        assertEquals("主楼A-101", rows[0].classroom)
        assertEquals("1", rows[0].dayOfWeek, "服务端给的就是文本（数字也按文本投影）")
        assertEquals("1-16周", rows[0].weeksText)
        assertEquals("1-15周(单)", rows[1].weeksText)

        // 失败那一支：读 message（新契约里没有 msg/error），中文短句原样带上
        val failing = CampusApi(
            createToolboxClient(
                engine = engineFor(
                    "/api/jwxt/term-start" to
                        """{"code":401,"data":null,"message":"请先登录：POST /api/session/login"}""",
                ),
            ),
            "",
            ApiMode.SERVE,
        )
        val e = assertFailsWith<IllegalStateException> { failing.termStart() }
        assertTrue(e.message?.contains("请先登录") == true, "实际：${e.message}")
    }

    /** 课表那两条端点不传 [ApiMode] 时按 campus-api 的老形状解析。 */
    @Test
    fun defaultModeStillReadsLegacyScheduleShape() = runTest {
        val body = """
            {"code":0,"msg":null,"data":{"term":"2026-2027-1","count":1,"rows":[
              {"KCM":"高等数学（上）","SKJS":"示例甲","JASMC":"主楼A-101","SKXQ":"1","KSJC":"1",
               "JSJC":"2","ZCMC":"1-16周","JXBID":"JXB-8001",
               "KCH":"MATH1001","KCXZMC":"必修","YPSJDD":"兴庆校区 主楼A-101 周一1-2节"}]}}
        """.trimIndent()
        // 注意：不传 mode（默认 = CAMPUS_API）
        val api = CampusApi(createToolboxClient(engine = engineFor("/api/jwxt/schedule" to body)), "")
        val rows = api.schedule("2026-2027-1").rows
        assertEquals(1, rows.size)
        assertEquals("高等数学（上）", rows[0].courseName)
        assertEquals("1-16周", rows[0].weeksText)
    }

    /** 不传 [ApiMode] 时，老形状（campus-api）照旧解析。 */

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