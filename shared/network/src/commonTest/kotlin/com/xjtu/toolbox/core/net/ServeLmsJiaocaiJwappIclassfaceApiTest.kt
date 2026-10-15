package com.xjtu.toolbox.core.net

import com.xjtu.toolbox.jwapp.CourseGroup
import com.xjtu.toolbox.jwapp.ScoreSource
import com.xjtu.toolbox.lms.LmsActivity
import com.xjtu.toolbox.lms.LmsActivityType
import com.xjtu.toolbox.lms.LmsDownloadJob
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.request.HttpRequestData
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
import kotlinx.datetime.LocalDate

/** serve 契约 §5.6（思源学堂 / 教材中心 / 移动教务成绩 / 人脸识别签到）的客户端那一侧。 */

/** 一发请求的快照：`路径 to 参数表`（与 `ServeReadonlyFourDomainsApiTest` 同一套读法）。 */
private typealias Call = Pair<String, Map<String, String>>

class ServeLmsJiaocaiJwappIclassfaceApiTest {

    /** 一个只按路径答夹具的 MockEngine。 */
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

    /** 记一发请求（路径 + 全部查询参数），与 [engineFor] 同一条路子。 */
    private fun record(calls: MutableList<Call>, request: io.ktor.client.request.HttpRequestData) {
        calls += request.url.encodedPath to
            request.url.parameters.names().associateWith { request.url.parameters[it].orEmpty() }
    }

    /** 学堂的课程与活动：嵌套四格直接按 `:core` 模型解码（服务端的 DTO 用的就是它们自己），`type` 是小写字符串零转换，`deadline` 用服务端并进来的那一个。 */
    @Test
    fun serveLmsCoursesAndActivitiesFollowCoreModels() = runTest {
        val coursesBody = """
            {"code":0,"data":{"courses":[
              {"id":101,"name":"高等数学（上）","courseCode":"MATH1001","courseType":1,"credit":"5",
               "compulsory":true,"startDate":"2026-09-14","endDate":"2027-01-18",
               "academicYear":{"id":1,"code":"2026","name":"2026-2027学年","sort":1},
               "semester":{"id":3,"code":"2026-2027-1","name":"第一学期","realName":"2026-2027学年第一学期","sort":1},
               "department":{"id":7,"name":"数学与统计学院","code":"MATH"},
               "courseAttributes":{"published":true,"studentCount":120,"teachingClassName":"高数上-01班"}}]}}
        """.trimIndent()
        val activitiesBody = """
            {"code":0,"data":{"courseId":101,"count":2,"activities":[
              {"id":9001,"courseId":101,"type":"homework","title":"第一章作业","moduleId":12,
               "startTime":"2026-09-20T08:00:00Z","visibleStartAt":null,"endTime":"2026-09-30T15:59:00Z",
               "deadline":"2026-10-10T15:59:00Z","submitByGroup":false,"published":true,
               "createdAt":"2026-09-18T02:00:00Z","updatedAt":"2026-09-19T02:00:00Z","isClosed":false},
              {"id":9002,"courseId":101,"type":"lecture_live","title":"第 3 次直播","moduleId":null,
               "startTime":null,"visibleStartAt":null,"endTime":null,"deadline":null,
               "submitByGroup":false,"published":true,"createdAt":"2026-09-21T02:00:00Z",
               "updatedAt":"2026-09-21T02:00:00Z","isClosed":true}]}}
        """.trimIndent()
        val calls = mutableListOf<Call>()
        val engine = MockEngine { request ->
            record(calls, request)
            val body = when {
                request.url.encodedPath.endsWith("/api/lms/courses") -> coursesBody
                request.url.encodedPath.endsWith("/api/lms/activities") -> activitiesBody
                else -> error("没有为 ${request.url.encodedPath} 准备响应")
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType to listOf("application/json")))
        }
        val api = CampusLmsApi(createToolboxClient(engine = engine), "", ApiMode.SERVE)

        val course = api.myCourses().single()
        assertEquals("/api/lms/courses", calls[0].first)
        assertTrue(calls[0].second.isEmpty(), "课程列表不收参数")
        assertEquals(101, course.id)
        assertEquals("高等数学（上）", course.name)
        assertEquals("5", course.credit, "学分是字符串（上游给的是文本）")
        assertEquals(1, course.academicYear.sort)
        assertEquals("2026-2027学年第一学期", course.semester.realName)
        assertEquals("数学与统计学院", course.department.name)
        assertEquals(120, course.courseAttributes.studentCount)
        // 教师姓名（`instructors`）不投影 ⇒ 模型自己的兜底文案，屏上会显示「未知」
        assertTrue(course.instructors.isEmpty(), "instructors 不在响应里 ⇒ 空表，不编一个教师名")
        assertEquals("未知", course.instructorNames)

        val activities = api.courseActivities(101)
        assertEquals(mapOf("courseId" to "101"), calls[1].second, "列表那一发只带 courseId")
        assertEquals(LmsActivityType.HOMEWORK, activities[0].type)
        assertEquals("2026-10-10T15:59:00Z", activities[0].deadline)
        assertEquals(LmsActivityType.LECTURE_LIVE, activities[1].type)
        assertNull(activities[1].moduleId, "JSON null 的 moduleId 是「不知道」，不是 0")
        assertTrue(activities[1].isClosed)
    }

    /** 学堂的活动详情：三个块按类型摊回扁平模型，不投影的那几格留空，三个开关报 `false`。 */
    @Test
    fun serveLmsActivityDetailLeavesUnprojectedCellsEmpty() = runTest {
        val detailBody = """
            {"code":0,"data":{"id":9001,"courseId":101,"type":"homework","title":"第一章作业",
              "description":"<p>完成习题<br>并提交报告</p>","moduleId":12,
              "startTime":"2026-09-20T08:00:00Z","visibleStartAt":null,"endTime":"2026-09-30T15:59:00Z",
              "deadline":"2026-10-10T15:59:00Z","published":true,"createdAt":"2026-09-18T02:00:00Z",
              "updatedAt":"2026-09-19T02:00:00Z","isClosed":false,
              "uploads":[{"id":77,"name":"题目.pdf","type":"application/pdf","size":2048}],
              "homework":{"submitByGroup":false,"groupId":null,"groupSetName":null,"userSubmitCount":2,
                "averageScore":88.5,"highestScore":95.0,"lowestScore":70.0,"hasScoreCount":30,
                "submitTimes":3,"nonSubmitTimes":false},
              "lesson":null,"live":null}}
        """.trimIndent()
        val calls = mutableListOf<Call>()
        val engine = MockEngine { request ->
            record(calls, request)
            respond(detailBody, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType to listOf("application/json")))
        }
        val api = CampusLmsApi(createToolboxClient(engine = engine), "", ApiMode.SERVE)

        val brief = LmsActivity(id = 9001, courseId = 101)
        val detail = api.activityDetail(9001, brief)
        assertEquals(mapOf("courseId" to "101", "activityId" to "9001"), calls.single().second, "详情那一发两个参数都要带")
        assertEquals(LmsActivityType.HOMEWORK, detail.type)
        assertEquals("2026-10-10T15:59:00Z", detail.deadline, "服务端并进来的那个 deadline（本端不自己并）")
        assertEquals(2, detail.userSubmitCount)
        assertEquals(88.5, detail.averageScore)
        assertEquals(3, detail.submitTimes)
        assertEquals(false, detail.nonSubmitTimes)
        // 附件只有元数据：三个地址不投影 ⇒ 留空，屏上也不画预览/下载入口
        val upload = detail.uploads.single()
        assertEquals("题目.pdf", upload.name)
        assertEquals(2048, upload.size)
        assertEquals("", upload.downloadUrl)
        assertEquals("", upload.previewUrl)
        assertEquals("", upload.attachmentUrl)
        assertEquals(0, upload.referenceId)
        // 回放 / 直播那些地址与提交记录都不投影 ⇒ 空
        assertTrue(detail.replayVideos.isEmpty())
        assertTrue(detail.liveStreams.isEmpty())
        assertTrue(detail.liveReplayVideos.isEmpty())
        assertNull(detail.submissionList, "提交记录里有姓名与学号（红线）⇒ 不端")
        assertTrue(detail.liveInstructorNames.isEmpty(), "直播的教师名单同样是姓名（红线）")

        // 能力开关：屏按它们不画那几处
        assertFalse(api.supportsVideoQueue, "回放队列是宿主的能力（桌面端也报 false）")
        assertFalse(api.supportsDownload, "附件的地址与字节都没有端点 ⇒ 预览/下载与批量下载整档不画")
        assertFalse(api.supportsSubmissionList, "提交记录拿不到 ⇒ 整块不画（也不画「还没有提交记录」）")
        assertFailsWith<UnsupportedOperationException> { api.saveDownload(LmsDownloadJob("a.pdf", "application/pdf", "")) }
        assertFailsWith<UnsupportedOperationException> { api.uploadBytes(upload) }
        assertFailsWith<UnsupportedOperationException> { api.publicPathLabel() }
        assertFailsWith<UnsupportedOperationException> { api.userSubmitCount(9001) }
    }

    /** `?courseId=` 是必需的（服务端要靠它把列表里那条 `deadline` 并进来）：拿不到 brief 就 不猜一个课程号。 */
    @Test
    fun serveLmsActivityDetailWithoutBriefRefusesToGuessCourseId() = runTest {
        val engine = engineFor("/api/lms/activity" to """{"code":0,"data":{}}""")
        val api = CampusLmsApi(createToolboxClient(engine = engine), "", ApiMode.SERVE)
        val e = assertFailsWith<UnsupportedOperationException> { api.activityDetail(9001, null) }
        assertTrue(e.message?.contains("courseId") == true, "理由要写在错误里，实际：${e.message}")
    }

    /** 正文那半：契约刻意不端 `htmlToPlainText` 这个纯函数（`:data` 那份用 jsoup，wasm 编不过） ⇒ 本端自己去掉标签。 */
    @Test
    fun serveLmsHtmlToPlainTextKeepsTheFourPinnedShapes() = runTest {
        val api = CampusLmsApi(createToolboxClient(engine = engineFor("unused" to "{}")), "", ApiMode.SERVE)
        assertEquals("1. 完成习题\n2. 提交报告", api.htmlToPlainText("<p>1. 完成习题<br>2. 提交报告</p>"))
        assertEquals("第一行\n第二行", api.htmlToPlainText("<p>第一行<br>第二行</p>"))
        assertEquals("截止 周五", api.htmlToPlainText("<p>截止\n      周五</p>"))
        assertEquals(
            "上\n\n下",
            api.htmlToPlainText("<p>上</p><p></p><p>&nbsp;</p><p></p><div><p>下</p></div>"),
        )
    }

    /** 错误信封：`code` 是 HTTP 状态码，失败文案只有 `message` 一处（§4）。 */
    @Test
    fun serveLmsFailureReadsMessage() = runTest {
        val api = CampusLmsApi(
            createToolboxClient(
                engine = engineFor(
                    "/api/lms/courses" to """{"code":502,"data":null,"message":"学堂暂时连不上，请稍后再试"}""",
                ),
            ),
            "",
            ApiMode.SERVE,
        )
        val e = assertFailsWith<IllegalStateException> { api.myCourses() }
        assertTrue(e.message?.contains("学堂暂时连不上") == true, "实际：${e.message}")
    }

    /** 教材检索：九格照§5.6（`fields` 是上游原文的键值对，键名不翻译），`ssno` 在检索里恒 `null`、要它得对那本书再打一枪（三格参数全部带出去）；没有 `id` 的行不列（打不通详情页）。 */
    @Test
    fun serveJiaocaiSearchKeepsUpstreamFieldsAndSsnoNeedsASecondCall() = runTest {
        val searchBody = """
            {"code":0,"data":{"keyword":"高等数学","count":2,"books":[
              {"id":"general_55258665","appId":13,"engineInstanceId":2079,"title":"高等数学（第七版）上册",
               "author":"同济大学数学系","summary":"课程名称：高等数学\n获取方式一：本地全文",
               "ssno":null,"hasFullText":true,
               "fields":{"课程名称":"高等数学","教材名称":"高等数学（第七版）上册","获取方式一":"本地全文","ISBN号":"978-7-04-039663-4"}},
              {"id":"","appId":0,"engineInstanceId":0,"title":"上游缺 id 的那一本","author":"","summary":"",
               "ssno":null,"hasFullText":false,"fields":{}}]}}
        """.trimIndent()
        val ssnoBody = """{"code":0,"data":{"id":"general_55258665","ssno":"SSNO-1"}}"""
        val calls = mutableListOf<Call>()
        val engine = MockEngine { request ->
            record(calls, request)
            val body = when {
                request.url.encodedPath.endsWith("/api/jiaocai/search") -> searchBody
                request.url.encodedPath.endsWith("/api/jiaocai/ssno") -> ssnoBody
                else -> error("没有为 ${request.url.encodedPath} 准备响应")
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType to listOf("application/json")))
        }
        val api = CampusJiaocaiApi(createToolboxClient(engine = engine), "", ApiMode.SERVE)

        val books = api.search("高等数学")
        assertEquals(
            mapOf("keyword" to "高等数学"),
            calls[0].second,
            "检索只有一个关键词输入：没有院系/教师筛选，也没有 page/pageSize",
        )
        assertEquals(1, books.size, "没有 id 的行打不通详情页 ⇒ 不列")
        val book = books.single()
        assertEquals("general_55258665", book.id)
        assertEquals(13, book.appId)
        assertEquals(2079, book.engineInstanceId)
        assertEquals("高等数学（第七版）上册", book.title)
        assertEquals("同济大学数学系", book.author)
        assertEquals("978-7-04-039663-4", book.fields["ISBN号"], "fields 是上游原文的键值对，键名不翻译")
        assertNull(book.ssno, "检索结果里 ssno 恒 null（链接只在详情页里）")
        // `hasFullText` 两边判据相同（ssno 非空或 fields 含「本地全文」），这里把文本放 fields 里。
        assertTrue(book.hasFullText, "fields 里那句「本地全文」⇒ 模型现算出来是 true")

        // 要全文号就得对这本书再打一枪：三格参数取自检索结果
        assertEquals("SSNO-1", api.fetchSsno(book))
        assertEquals(
            mapOf("id" to "general_55258665", "appId" to "13", "engineInstanceId" to "2079"),
            calls[1].second,
        )
        // `ssno: null` = 这本书没有开放本地全文（不是错误、也不编一个书目号）
        val missing = CampusJiaocaiApi(
            createToolboxClient(engine = engineFor("/api/jiaocai/ssno" to """{"code":0,"data":{"id":"x","ssno":null}}""")),
            "",
            ApiMode.SERVE,
        )
        assertNull(missing.fetchSsno(book))
    }

    /** 成绩那两发：`scores`（移动教务，`terms` 直接按 `:core` 的 `TermScore` 解码）与`precise-scores`（教务那一站，字段名就是教务列名）。 */
    @Test
    fun serveJwappScoresAndPreciseScoresAreTwoEndpoints() = runTest {
        val scoresBody = """
            {"code":0,"data":{"term":null,"count":1,"terms":[
              {"termCode":"2026-2027-1","termName":"2026-2027学年第一学期","scoreList":[
                {"id":"S1","termCode":"2026-2027-1","courseName":"高等数学（上）","score":"95",
                 "scoreValue":95.0,"passFlag":true,"specificReason":null,"coursePoint":5.0,
                 "examType":"考试","majorFlag":"必修","examProp":"初修","replaceFlag":false,"gpa":4.0,
                 "source":"JWAPP","courseCategory":null,"courseCode":"MATH1001","courseGroup":"GEN_CORE"}]}]}}
        """.trimIndent()
        val preciseBody = """
            {"code":0,"data":{"count":1,"scores":[
              {"courseName":"高等数学（上）","termCode":"2026-2027-1","zcj":95.0,"xfjd":4.0,"xf":5.0,
               "djcjlxdm":"","djcjmc":null,"pscj":88.0,"pscjxs":"88","qmcj":98.0,"qmcjxs":"98",
               "kch":"MATH1001","jxbid":"JXB-1","passFlag":true,"examProp":"必修","kclbdm":"01"}]}}
        """.trimIndent()
        val calls = mutableListOf<Call>()
        val engine = MockEngine { request ->
            record(calls, request)
            val body = when {
                request.url.encodedPath.endsWith("/api/jwapp/scores") -> scoresBody
                request.url.encodedPath.endsWith("/api/jwapp/precise-scores") -> preciseBody
                else -> error("没有为 ${request.url.encodedPath} 准备响应")
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType to listOf("application/json")))
        }
        val api = CampusJwappScoreApi(createToolboxClient(engine = engine), "", ApiMode.SERVE)

        // 省略 term = 全部学期：那一发不许自己编一个学期码
        val terms = api.getGrade(null)
        assertEquals("/api/jwapp/scores", calls[0].first)
        assertTrue(calls[0].second.isEmpty(), "term 省略时那一发不带任何参数")
        val score = terms.single().scoreList.single()
        assertEquals("2026-2027学年第一学期", terms.single().termName)
        assertEquals(95.0, score.scoreValue)
        assertEquals("95", score.score, "score 是上游原文的字符串（等级制课程会是「优秀」）")
        assertEquals(ScoreSource.JWAPP, score.source)
        assertEquals(CourseGroup.GEN_CORE, score.courseGroup)
        assertEquals(4.0, score.gpa)

        // 第二发打的是**教务**那一站（另一个站点、另一条上游）：字段名就是教务列名，本层不翻译
        val precise = api.preciseScores().single()
        assertEquals("/api/jwapp/precise-scores", calls[1].first)
        assertTrue(calls[1].second.isEmpty(), "precise-scores 不收参数")
        assertEquals("高等数学（上）", precise.courseName)
        assertEquals(95.0, precise.zcj)
        assertEquals(4.0, precise.xfjd)
        assertEquals(5.0, precise.xf)
        assertEquals(88.0, precise.pscj)
        assertEquals(98.0, precise.qmcj)
        assertEquals("MATH1001", precise.kch)
        assertEquals("JXB-1", precise.jxbid)
        assertEquals("01", precise.kclbdm)
        assertTrue(precise.passFlag)

        // 哨兵值 `-1.0` 在**服务端那一侧**就归一成 null（`:data` 的 `takeIf { it > -100 }`）：
        // 本端不再判一次 —— 这两格缺就是 null，不是 -1.0 也不是 0
        val sentinel = CampusJwappScoreApi(
            createToolboxClient(
                engine = engineFor(
                    "/api/jwapp/precise-scores" to
                        """{"code":0,"data":{"count":1,"scores":[{"courseName":"大学物理","termCode":"2026-2027-1","zcj":80.0,"xfjd":3.0,"xf":3.0,"djcjlxdm":"","djcjmc":null,"pscj":null,"pscjxs":null,"qmcj":null,"qmcjxs":null,"kch":"PHYS1001","jxbid":"JXB-2","passFlag":true,"examProp":"必修","kclbdm":"01"}]}}""",
                ),
            ),
            "",
            ApiMode.SERVE,
        )
        val noSubItems = sentinel.preciseScores().single()
        assertNull(noSubItems.pscj, "服务端归一过的哨兵值 ⇒ null")
        assertNull(noSubItems.qmcj)

        // 分项成绩已于 2026-10-14 端出（§5.8）：serve 形态下这个开关报 true，屏上那一格能展开，
        // 取数（含「detail:null ⇒ 空明细」那一支）钉在 ServeJwappScoreDetailApiTest 里
        assertTrue(api.canFetchScoreDetail, "§5.8 端了分项成绩 ⇒ serve 形态下屏上那一格能展开")
        assertFailsWith<UnsupportedOperationException> { api.unevaluatedCourseNames() }
        assertFailsWith<UnsupportedOperationException> { api.reportedGrades("20260001") }

        // ⚠️ 第二条端点 502 可容忍（VM catch 住就用 jwapp 那一份）；本层照实抛出服务端那句中文
        val failing = CampusJwappScoreApi(
            createToolboxClient(
                engine = engineFor(
                    "/api/jwapp/precise-scores" to
                        """{"code":502,"data":null,"message":"教务会话已失效，请重新登录"}""",
                ),
            ),
            "",
            ApiMode.SERVE,
        )
        val e = assertFailsWith<IllegalStateException> { failing.preciseScores() }
        assertTrue(e.message?.contains("教务会话已失效") == true, "实际：${e.message}")
    }

    /** 签到流水：三格（时刻/地点/类型）原样 —— ⚠️ `time` 是服务端时区的墙上时间，本端不 parse、不本地化（转一次只会整体平移） */
    @Test
    fun serveIclassfaceKeepsWallClockTimeAndDropsNameAndStudentNo() = runTest {
        val recordsBody = """
            {"code":0,"data":{"date":"2026-10-14","count":2,"records":[
              {"time":"2026-06-04 14:14:37","location":"中3-2306","type":"电子班牌人脸识别"},
              {"time":"2026-06-04 18:02:10","location":"主楼A-101","type":"电子班牌刷卡"}]}}
        """.trimIndent()
        val emptyBody = """{"code":0,"data":{"date":"2026-10-13","count":0,"records":[]}}"""
        val calls = mutableListOf<Call>()
        val engine = MockEngine { request ->
            record(calls, request)
            val body = if (request.url.parameters["date"] == "2026-10-13") emptyBody else recordsBody
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType to listOf("application/json")))
        }
        val api = CampusIclassfaceApi(createToolboxClient(engine = engine), "", ApiMode.SERVE)

        val records = api.fetchRecords(LocalDate(2026, 10, 14))
        assertEquals(mapOf("date" to "2026-10-14"), calls[0].second, "日期按屏上选中的那一天原样发出去")
        assertEquals("2026-06-04 14:14:37", records[0].time, "墙上时间原样：不 parse、不转时区")
        assertEquals("中3-2306", records[0].location)
        assertEquals("电子班牌人脸识别", records[0].type)
        assertEquals("", records[0].name, "姓名不投影（红线）⇒ 空串，屏上也不画那一格")
        assertEquals("", records[0].studentNo)
        assertEquals(2, records.size)

        // 空表 = 那天确实没有记录（含「还没刷上卡」），**不是**错误
        assertTrue(api.fetchRecords(LocalDate(2026, 10, 13)).isEmpty())
    }

    /** 这四个域在 campus-api 里没有端点（它们是 `:app` / 桌面直接走 `:data` 的 okhttp 抓的）。 */
    @Test
    fun campusApiModeThrowsInsteadOfFakingEmptyData() = runTest {
        val engine = engineFor(
            "/api/lms/courses" to """{"code":0,"data":{"courses":[]}}""",
            "/api/jiaocai/search" to """{"code":0,"data":{"books":[]}}""",
            "/api/jwapp/scores" to """{"code":0,"data":{"terms":[]}}""",
            "/api/iclassface/records" to """{"code":0,"data":{"records":[]}}""",
        )
        val client = createToolboxClient(engine = engine)
        // 注意：不传 mode（默认 = CAMPUS_API）
        assertFailsWith<UnsupportedOperationException> { CampusLmsApi(client, "").myCourses() }
        assertFailsWith<UnsupportedOperationException> { CampusJiaocaiApi(client, "").search("高等数学") }
        assertFailsWith<UnsupportedOperationException> { CampusJwappScoreApi(client, "").getGrade(null) }
        assertFailsWith<UnsupportedOperationException> {
            CampusIclassfaceApi(client, "").fetchRecords(LocalDate(2026, 10, 14))
        }
    }
}
