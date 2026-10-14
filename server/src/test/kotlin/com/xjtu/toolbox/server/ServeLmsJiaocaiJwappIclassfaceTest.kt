package com.xjtu.toolbox.server

import com.xjtu.toolbox.iclassface.IclassfaceFakeUpstream
import com.xjtu.toolbox.jiaocai1.Jiaocai1FakeUpstream
import com.xjtu.toolbox.jwapp.JwappFakeUpstream
import com.xjtu.toolbox.jwxt.JwxtFakeUpstream
import com.xjtu.toolbox.library.LibraryFakeUpstream
import com.xjtu.toolbox.lms.LmsFakeUpstream
import com.xjtu.toolbox.util.todayInSystemZone
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * **第二批只读域**（思源学堂 / 教材中心 / 移动教务成绩 / 人脸识别签到）的契约测试 ——
 * `GET /api/lms/{courses,activities,activity}` · `GET /api/jiaocai/{search,ssno}` ·
 * `GET /api/jwapp/{scores,precise-scores}` · `GET /api/iclassface/records`
 * （`docs/api-contract.md` §5.6）。
 *
 * ## 脚手架
 *
 * 与 [ServeReadonlyFourDomainsTest] 同一条（[withFakeProxy] + [startEndpointServer]）：
 * 真 socket（端口 0）、真令牌闸门、真 `ServeSession`、四族的 `*Routes` 挂进 `route("/api")`。
 * 夹具是 `:testkit` 的四份假上游（`LmsFakeUpstream` / `Jiaocai1FakeUpstream`（它同时扮教材中心
 * `jiaocai.lib` 与全文库 `jiaocai1.lib`）/ `JwappFakeUpstream` + `JwxtFakeUpstream`（成绩的第二条
 * 上游在教务）/ `IclassfaceFakeUpstream`），期望值全部来自它们的常量 —— **一个都不手写**。
 *
 * ## 这一组钉的是什么
 *
 * - **闸门**：四族八条路径各自进门先判会话 ⇒ 未登录 401（不是让取数拿 502 蒙混），
 *   而且**未登录那一枪不该到取数层**（夹具的调用计数为 0）；
 * - **形状**：每个 `data` 的键集合逐格钉住（多一格少一格都算投影漂了），
 *   包括「按类型只出现一块」的 `homework` / `lesson` / `live`；
 * - **两个站点的成绩**：`scores` 打 `jwapp`、`precise-scores` 打 `jwxt`（两条上游的请求体/表单
 *   分别对着夹具断言）；
 * - **红线**：每个响应都过 [assertNoIdentity]，而且是在**夹具里明明有那些值**的前提下证明的
 *   （`LmsFakeUpstream.INSTRUCTOR_NAME` 是课程教师名、`USER_INDEX_HTML` 里有姓名与学号、
 *   `IclassfaceFakeUpstream.RECORD_NAME` / `RECORD_STUDENT_NO` 是签到表里的姓名与学号）
 *   —— 不能「夹具没有所以没出现」；
 * - **如实缺口**：附件的三个地址、回放/直播的地址、成绩分项、真实姓名与学号**都不在**响应里
 *   （逐条断言）。
 */
class ServeLmsJiaocaiJwappIclassfaceTest {

    private var started: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    @AfterTest
    fun stopServer() {
        started?.stop(gracePeriodMillis = 100, timeoutMillis = 500)
        started = null
    }

    // ── 思源学堂（lms）：courses + activities + activity ────────────────

    @Test
    fun `lms courses 与 activities：未登录 401 + 逐字段（教师名与附件地址都不投影）`() = withFakeProxy { fake ->
        val token = AccessToken.newToken()
        val session = ServeSession()
        val (server, http) = startEndpointServer(token) {
            sessionRoutes(session, token)
            lmsRoutes(session)
        }
        started = server

        // 令牌对、但没登录 ⇒ 401 + 中文短句指路（不是 502）
        for (path in listOf(LMS_COURSES_PATH, "$LMS_ACTIVITIES_PATH?courseId=${LmsFakeUpstream.COURSE_ID}")) {
            val anonymous = http.get(path)
            assertEquals(401, anonymous.statusCode(), path)
            assertEquals(ApiErrors.UNAUTHORIZED, envelopeCode(anonymous))
            assertTrue(envelopeMessage(anonymous).orEmpty().contains("登录"), "401 要指路「先登录」：$path")
        }
        assertEquals(0, fake.lms.courseListCalls.get(), "未登录那一枪不该到取数层")
        assertEquals(0, fake.lms.activityListCalls.get(), "未登录那一枪不该到取数层")

        assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

        // ── 课程列表 ──
        val courses = http.get(LMS_COURSES_PATH)
        assertEquals(200, courses.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(courses))
        assertNoIdentity(courses.body(), "GET $LMS_COURSES_PATH", IDENTITY)

        val courseData = envelopeData(courses)
        assertEquals(setOf("courses"), courseData.keys)
        val course = courseData.arrOf("courses")[0] as JsonObject
        assertEquals(
            setOf(
                "id", "name", "courseCode", "courseType", "credit", "compulsory", "startDate", "endDate",
                "academicYear", "semester", "department", "courseAttributes",
            ),
            course.keys,
            "一门课的十二格（`:core` 的 LmsCourseSummary 去掉 instructors —— 那是教师姓名）",
        )
        assertEquals(LmsFakeUpstream.COURSE_ID, course.optInt("id"))
        assertEquals(LmsFakeUpstream.COURSE_NAME, course.optString("name"))
        assertEquals(LmsFakeUpstream.COURSE_CODE, course.optString("courseCode"))
        assertEquals("3.0", course.optString("credit"), "学分是字符串（上游给的是文本）")
        assertEquals(true, course.optBoolean("compulsory"))
        assertEquals("2026-09-01", course.optString("startDate"))
        assertEquals("2027-01-15", course.optString("endDate"))

        val academicYear = course.getValue("academicYear") as JsonObject
        assertEquals(setOf("id", "code", "name", "sort"), academicYear.keys)
        assertEquals("2026-2027", academicYear.optString("code"))
        val department = course.getValue("department") as JsonObject
        assertEquals(setOf("id", "name", "code"), department.keys)
        assertEquals("计算机学院", department.optString("name"))
        val semester = course.getValue("semester") as JsonObject
        assertEquals(setOf("id", "code", "name", "realName", "sort"), semester.keys)
        assertEquals("2026-2027-1", semester.optString("name"))
        val attributes = course.getValue("courseAttributes") as JsonObject
        assertEquals(setOf("published", "studentCount", "teachingClassName"), attributes.keys)
        assertEquals(60, attributes.optInt("studentCount"))
        assertEquals("计算机01", attributes.optString("teachingClassName"))

        // 夹具里明明有课程教师名（`instructors[].name`）—— 它必须一个都没被投影
        assertFalse(courses.body().contains("instructors"), "`instructors` 整个数组不端（教师姓名，红线）")
        assertFalse(courses.body().contains(LmsFakeUpstream.INSTRUCTOR_NAME), "响应里不该出现「${LmsFakeUpstream.INSTRUCTOR_NAME}」")

        // ── 活动列表 ──
        val activities = http.get("$LMS_ACTIVITIES_PATH?courseId=${LmsFakeUpstream.COURSE_ID}")
        assertEquals(200, activities.statusCode())
        assertNoIdentity(activities.body(), "GET $LMS_ACTIVITIES_PATH", IDENTITY)
        assertEquals(LmsFakeUpstream.COURSE_ID, fake.lms.lastActivityListCourseId, "打出去的 courseId 就是请求里那个")

        val activityData = envelopeData(activities)
        assertEquals(setOf("courseId", "count", "activities"), activityData.keys)
        assertEquals(LmsFakeUpstream.COURSE_ID, activityData.optInt("courseId"))
        assertEquals(3, activityData.optInt("count"))

        val list = activityData.arrOf("activities")
        assertEquals(3, list.size)
        val brief = list[0] as JsonObject
        assertEquals(
            setOf(
                "id", "courseId", "type", "title", "moduleId", "startTime", "visibleStartAt", "endTime",
                "deadline", "submitByGroup", "published", "createdAt", "updatedAt", "isClosed",
            ),
            brief.keys,
            "列表里一条活动的十四格",
        )
        assertEquals(LmsFakeUpstream.HOMEWORK_ID, brief.optInt("id"))
        assertEquals("homework", brief.optString("type"), "type 是 LmsActivityType.value（消费方零转换）")
        assertEquals(LmsFakeUpstream.HOMEWORK_TITLE, brief.optString("title"))
        assertEquals(LmsFakeUpstream.HOMEWORK_DEADLINE, brief.optString("deadline"), "deadline 只在列表接口里给")
        assertEquals(true, brief.optBoolean("isClosed"))
        // 三类活动各来一条（夹具给的就是 homework / material / page）
        assertEquals(
            listOf("homework", "material", "page"),
            list.map { (it as JsonObject).optString("type") },
        )
    }

    @Test
    fun `lms activity：详情按类型只出现一格，deadline 从列表并进来，参数错 400`() = withFakeProxy { fake ->
        val token = AccessToken.newToken()
        val session = ServeSession()
        val (server, http) = startEndpointServer(token) {
            sessionRoutes(session, token)
            lmsRoutes(session)
        }
        started = server

        assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

        // ── 参数形状：courseId / activityId 两个都必填、都必须是正整数 ──
        val missingCourse = http.get("$LMS_ACTIVITY_PATH?activityId=${LmsFakeUpstream.HOMEWORK_ID}")
        assertEquals(400, missingCourse.statusCode())
        assertEquals(ApiErrors.BAD_REQUEST, envelopeCode(missingCourse))
        assertTrue(envelopeMessage(missingCourse).orEmpty().contains("courseId"), "400 要说出缺什么")

        val missingActivity = http.get("$LMS_ACTIVITY_PATH?courseId=${LmsFakeUpstream.COURSE_ID}")
        assertEquals(400, missingActivity.statusCode())
        assertTrue(envelopeMessage(missingActivity).orEmpty().contains("activityId"), "400 要说出缺什么")

        assertEquals(400, http.get("$LMS_ACTIVITY_PATH?courseId=abc&activityId=1").statusCode(), "非整数也是 400")
        assertEquals(400, http.get("$LMS_ACTIVITY_PATH?courseId=0&activityId=1").statusCode(), "0 不是合法课程 id")
        assertEquals(0, fake.lms.detailCalls.get(), "400 那几枪都没到取数层")

        // ── 作业详情：通用格 + uploads 元数据 + 只有 homework 那一块 ──
        val homework = http.get("$LMS_ACTIVITY_PATH?courseId=${LmsFakeUpstream.COURSE_ID}&activityId=${LmsFakeUpstream.HOMEWORK_ID}")
        assertEquals(200, homework.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(homework))
        assertNoIdentity(homework.body(), "GET $LMS_ACTIVITY_PATH（homework）", IDENTITY)

        val data = envelopeData(homework)
        assertEquals(
            setOf(
                "id", "courseId", "type", "title", "description", "moduleId", "startTime", "visibleStartAt",
                "endTime", "deadline", "published", "createdAt", "updatedAt", "isClosed",
                "uploads", "homework", "lesson", "live",
            ),
            data.keys,
            "详情 = 通用十四格 + uploads + 三个按类型只出现一格的块",
        )
        assertEquals(LmsFakeUpstream.HOMEWORK_ID, data.optInt("id"))
        assertEquals("homework", data.optString("type"))
        // 详情接口**没有** deadline 键：这一格是照 `:data` 的 mergeBrief 从列表并进来的
        assertEquals(LmsFakeUpstream.HOMEWORK_DEADLINE, data.optString("deadline"), "deadline 从列表并进来")
        assertEquals("<p>完成习题并提交报告</p>", data.optString("description"), "正文在 data.description")

        val uploads = data.arrOf("uploads")
        assertEquals(1, uploads.size)
        val upload = uploads[0] as JsonObject
        assertEquals(setOf("id", "name", "type", "size"), upload.keys, "附件只端元数据（三个地址都要站点会话）")
        assertEquals(LmsFakeUpstream.UPLOAD_ID, upload.optInt("id"))
        assertEquals(LmsFakeUpstream.UPLOAD_NAME, upload.optString("name"))
        assertEquals(2048, upload.optInt("size"))
        for (leaked in FORBIDDEN_LMS) {
            assertFalse(homework.body().contains(leaked), "响应里不该出现「$leaked」（夹具里有值）")
        }

        val homeworkBlock = assertNotNull(data.optObject("homework"), "作业详情必须带 homework 那一块")
        assertEquals(
            setOf(
                "submitByGroup", "groupId", "groupSetName", "userSubmitCount", "averageScore",
                "highestScore", "lowestScore", "hasScoreCount", "submitTimes", "nonSubmitTimes",
            ),
            homeworkBlock.keys,
        )
        assertEquals(1, homeworkBlock.optInt("userSubmitCount"))
        assertEquals(90.5, homeworkBlock.optString("averageScore")?.toDouble() ?: Double.NaN, 1e-9)
        assertEquals(3, homeworkBlock.optInt("submitTimes"), "允许提交 3 次（data.submit_times）")
        assertNull(data.optObject("lesson"), "作业不是课堂：lesson 那一格是 null")
        assertNull(data.optObject("live"), "作业不是直播：live 那一格是 null")

        // ── 页面型详情：三段块全是 null，正文来自 data.content ──
        val page = http.get("$LMS_ACTIVITY_PATH?courseId=${LmsFakeUpstream.COURSE_ID}&activityId=${LmsFakeUpstream.PAGE_ID}")
        assertEquals(200, page.statusCode())
        val pageData = envelopeData(page)
        assertEquals("page", pageData.optString("type"))
        assertEquals("<p>课程考核构成：期中 40%，期末 60%</p>", pageData.optString("description"), "页面型的正文在 data.content")
        assertNull(pageData.optObject("homework"), "页面型没有作业那一块")
        assertNull(pageData.optObject("lesson"), "页面型没有课堂那一块")
        assertNull(pageData.optObject("live"), "页面型没有直播那一块")

        // 挂在 `:data` 的 activityDetail 上：作业详情会顺带打一次提交列表（那是取数那一半的事），
        // 但**提交记录本身不投影**（里面有提交人姓名与学号）
        assertTrue(fake.lms.detailCalls.get() >= 2, "两次详情都真打了取数层")
        assertFalse(page.body().contains("我完成了"), "提交记录里的正文不许出现")
    }

    // ── 教材中心（jiaocai）：search + ssno ─────────────────────────────

    @Test
    fun `jiaocai search：未登录 401 + 逐字段（ssno 列表里恒 null）与空结果那一档`() = withFakeProxy { fake ->
        val token = AccessToken.newToken()
        val session = ServeSession()
        val (started2, http) = startEndpointServer(token) {
            sessionRoutes(session, token)
            jiaocaiRoutes(session)
        }
        started = started2

        val anonymous = http.get("$JIAOCAI_SEARCH_PATH?keyword=%E5%BE%AE%E7%A7%AF%E5%88%86")
        assertEquals(401, anonymous.statusCode())
        assertEquals(ApiErrors.UNAUTHORIZED, envelopeCode(anonymous))
        assertEquals(0, fake.jiaocai1.jiaocaiSearchCalls.get(), "未登录那一枪不该到取数层")

        assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

        // keyword 缺 / 空白 ⇒ 400（这一屏的全部输入就是一个关键词）
        val noKeyword = http.get(JIAOCAI_SEARCH_PATH)
        assertEquals(400, noKeyword.statusCode())
        assertEquals(ApiErrors.BAD_REQUEST, envelopeCode(noKeyword))
        assertTrue(envelopeMessage(noKeyword).orEmpty().contains("keyword"), "400 要说出缺什么")
        assertEquals(400, http.get("$JIAOCAI_SEARCH_PATH?keyword=%20").statusCode())
        assertEquals(0, fake.jiaocai1.jiaocaiSearchCalls.get(), "400 那两枪都没到取数层")

        val keyword = "高等数学"
        val found = http.get("$JIAOCAI_SEARCH_PATH?keyword=${encode(keyword)}")
        assertEquals(200, found.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(found))
        assertNoIdentity(found.body(), "GET $JIAOCAI_SEARCH_PATH", IDENTITY)
        assertEquals(keyword, fake.jiaocai1.lastJiaocaiKeyword, "检索词原样打给上游")

        val data = envelopeData(found)
        assertEquals(setOf("keyword", "count", "books"), data.keys)
        assertEquals(keyword, data.optString("keyword"))
        assertEquals(1, data.optInt("count"))
        val book = data.arrOf("books")[0] as JsonObject
        assertEquals(
            setOf("id", "appId", "engineInstanceId", "title", "author", "summary", "ssno", "hasFullText", "fields"),
            book.keys,
            "一本书就是 `:core` 的 JiaocaiBook 那九格",
        )
        assertEquals(Jiaocai1FakeUpstream.SEARCH_BOOK_TITLE, book.optString("title"), "title 已剥掉 <b> 那些标签")
        assertEquals(Jiaocai1FakeUpstream.SEARCH_BOOK_AUTHOR, book.optString("author"))
        assertEquals(Jiaocai1FakeUpstream.SEARCH_BOOK_APP_ID, book.optInt("appId"))
        assertEquals(Jiaocai1FakeUpstream.SEARCH_BOOK_ENGINE_ID, book.optInt("engineInstanceId"))
        assertNull(book.optString("ssno"), "列表接口没有链接 ⇒ ssno 恒 null（要它得打 /api/jiaocai/ssno）")
        assertEquals(true, book.optBoolean("hasFullText"), "描述里有「本地全文」那句 ⇒ 判据为真")

        val fields = book.getValue("fields") as JsonObject
        assertEquals(Jiaocai1FakeUpstream.SEARCH_BOOK_AUTHOR, fields.optString("编者"), "fields 的键名是上游原文")
        assertEquals("示例出版社", fields.optString("出版社"))
        assertEquals("978-7-0000-0000-1", fields.optString("ISBN号"))

        // 未命中：`dataList` 空 ⇒ count 0 + 空数组（不是错误）
        val miss = http.get("$JIAOCAI_SEARCH_PATH?keyword=${encode(Jiaocai1FakeUpstream.MISS_SEARCH_KEYWORD)}")
        assertEquals(200, miss.statusCode())
        assertEquals(0, envelopeData(miss).optInt("count"))
        assertTrue(envelopeData(miss).arrOf("books").isEmpty())
    }

    @Test
    fun `jiaocai ssno：三格必填 + 逐字段（那个书目号真取到了）`() = withFakeProxy { fake ->
        val token = AccessToken.newToken()
        val session = ServeSession()
        val (server, http) = startEndpointServer(token) {
            sessionRoutes(session, token)
            jiaocaiRoutes(session)
        }
        started = server

        val anonymous = http.get(JIAOCAI_SSNO_PATH)
        assertEquals(401, anonymous.statusCode())
        assertEquals(0, fake.jiaocai1.jiaocaiDetailCalls.get(), "未登录那一枪不该到取数层")

        assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

        // id 缺 ⇒ 400；appId / engineInstanceId 不是整数 ⇒ 400（详情页 URL 就靠这三格拼）
        val noId = http.get("$JIAOCAI_SSNO_PATH?appId=7&engineInstanceId=3")
        assertEquals(400, noId.statusCode())
        assertTrue(envelopeMessage(noId).orEmpty().contains("id"), "400 要说出缺什么")
        val badAppId = http.get("$JIAOCAI_SSNO_PATH?id=general_55258665&appId=x&engineInstanceId=3")
        assertEquals(400, badAppId.statusCode())
        assertTrue(envelopeMessage(badAppId).orEmpty().contains("appId"), "400 要说出是哪个参数")
        assertEquals(0, fake.jiaocai1.jiaocaiDetailCalls.get(), "400 那两枪都没到取数层")

        val bookId = "general_55258665"
        val response = http.get("$JIAOCAI_SSNO_PATH?id=$bookId&appId=${Jiaocai1FakeUpstream.SEARCH_BOOK_APP_ID}&engineInstanceId=${Jiaocai1FakeUpstream.SEARCH_BOOK_ENGINE_ID}")
        assertEquals(200, response.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(response))
        assertNoIdentity(response.body(), "GET $JIAOCAI_SSNO_PATH", IDENTITY)
        assertEquals(1, fake.jiaocai1.jiaocaiDetailCalls.get(), "真进了一次书目详情页")

        val data = envelopeData(response)
        assertEquals(setOf("id", "ssno"), data.keys, "只回两格（不回 hasFullText：这里没有 fields，重算等于第二份判据）")
        assertEquals(bookId, data.optString("id"))
        assertEquals(Jiaocai1FakeUpstream.SSNO, data.optString("ssno"))
    }

    // ── 移动教务成绩（jwapp）：scores + precise-scores（两个站点）──────

    @Test
    fun `jwapp scores：未登录 401 + 逐字段 + term 省略与给出两种`() = withFakeProxy { fake ->
        val token = AccessToken.newToken()
        val session = ServeSession()
        val (server, http) = startEndpointServer(token) {
            sessionRoutes(session, token)
            jwappRoutes(session)
        }
        started = server

        val anonymous = http.get(JWAPP_SCORES_PATH)
        assertEquals(401, anonymous.statusCode())
        assertEquals(ApiErrors.UNAUTHORIZED, envelopeCode(anonymous))
        assertEquals(0, fake.jwapp.gradeCalls.get(), "未登录那一枪不该到取数层")

        assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

        // 省略 term = 全部学期（请求体里是 {"termCode":"*"}）
        val all = http.get(JWAPP_SCORES_PATH)
        assertEquals(200, all.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(all))
        assertNoIdentity(all.body(), "GET $JWAPP_SCORES_PATH", IDENTITY)

        val data = envelopeData(all)
        assertEquals(setOf("term", "count", "terms"), data.keys)
        assertNull(data.optString("term"), "省略 ?term= ⇒ term 是 null（= 全部学期）")
        assertEquals(1, data.optInt("count"))

        val term = data.arrOf("terms")[0] as JsonObject
        assertEquals(setOf("termCode", "termName", "scoreList"), term.keys, "`:core` 的 TermScore 原样端出")
        assertEquals(JwappFakeUpstream.TERM_CODE, term.optString("termCode"))
        assertEquals(JwappFakeUpstream.TERM_NAME, term.optString("termName"))

        val score = term.arrOf("scoreList")[0] as JsonObject
        assertEquals(
            setOf(
                "id", "termCode", "courseName", "score", "scoreValue", "passFlag", "specificReason",
                "coursePoint", "examType", "majorFlag", "examProp", "replaceFlag", "gpa",
                "source", "courseCategory", "courseCode", "courseGroup",
            ),
            score.keys,
            "`:core` 的 ScoreItem 原样端出（枚举的线上形状是枚举名）",
        )
        assertEquals(JwappFakeUpstream.COURSE_ID, score.optString("id"))
        assertEquals(JwappFakeUpstream.COURSE_NAME, score.optString("courseName"))
        assertEquals(JwappFakeUpstream.COURSE_SCORE, score.optString("score"), "score 是字符串（上游原文可能是「优秀」）")
        assertEquals("JWAPP", score.optString("source"))
        assertEquals(JwappFakeUpstream.COURSE_EXAM_PROP, score.optString("examProp"))
        assertEquals(
            JwappFakeUpstream.COURSE_POINT,
            score.optString("coursePoint")?.toDouble() ?: Double.NaN,
            1e-9,
        )

        val firstBody = fake.jwapp.lastGradeBody.orEmpty()
        assertTrue(firstBody.contains("\"termCode\":\"*\""), "省略 term 打给上游的是 `*`，实际：$firstBody")

        // 给了 term：原样回，且请求体里是那个学期码
        val one = http.get("$JWAPP_SCORES_PATH?term=${JwappFakeUpstream.TERM_CODE}")
        assertEquals(200, one.statusCode())
        assertEquals(JwappFakeUpstream.TERM_CODE, envelopeData(one).optString("term"))
        assertTrue(
            fake.jwapp.lastGradeBody.orEmpty().contains("\"termCode\":\"${JwappFakeUpstream.TERM_CODE}\""),
            "学期码原样打给上游，实际：${fake.jwapp.lastGradeBody}",
        )

        // 给了个空白串是「形状不对」，不是「省略」
        val blank = http.get("$JWAPP_SCORES_PATH?term=%20")
        assertEquals(400, blank.statusCode())
        assertEquals(ApiErrors.BAD_REQUEST, envelopeCode(blank))
        assertTrue(envelopeMessage(blank).orEmpty().contains("term"), "400 要说出是哪个参数")
    }

    @Test
    fun `jwapp precise-scores：第二站点（教务）逐字段，未登录 401`() = withFakeProxy { fake ->
        val token = AccessToken.newToken()
        val session = ServeSession()
        val (server, http) = startEndpointServer(token) {
            sessionRoutes(session, token)
            jwappRoutes(session)
        }
        started = server

        val anonymous = http.get(JWAPP_PRECISE_SCORES_PATH)
        assertEquals(401, anonymous.statusCode())
        assertEquals(0, fake.jwxt.cjcxCalls.get(), "未登录那一枪不该到取数层")

        assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

        val response = http.get(JWAPP_PRECISE_SCORES_PATH)
        assertEquals(200, response.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(response))
        assertNoIdentity(response.body(), "GET $JWAPP_PRECISE_SCORES_PATH", IDENTITY)

        val data = envelopeData(response)
        assertEquals(setOf("count", "scores"), data.keys)
        assertEquals(2, data.optInt("count"), "夹具的 xscjcx.do 给两行")

        val score = data.arrOf("scores")[0] as JsonObject
        assertEquals(
            setOf(
                "courseName", "termCode", "zcj", "xfjd", "xf", "djcjlxdm", "djcjmc", "pscj", "pscjxs",
                "qmcj", "qmcjxs", "kch", "jxbid", "passFlag", "examProp", "kclbdm",
            ),
            score.keys,
            "`:data` 的 CjcxScore 原样端出（字段名 = 教务列名，缩写对照在契约 §5.6）",
        )
        assertEquals(JwxtFakeUpstream.CJCX_KCM, score.optString("courseName"))
        assertEquals(JwxtFakeUpstream.CJCX_TERM, score.optString("termCode"))
        assertEquals(JwxtFakeUpstream.CJCX_ZCJ, score.optString("zcj")?.toDouble() ?: Double.NaN, 1e-9)
        assertEquals(JwxtFakeUpstream.CJCX_XFJD, score.optString("xfjd")?.toDouble() ?: Double.NaN, 1e-9)
        assertEquals(JwxtFakeUpstream.CJCX_XF, score.optString("xf")?.toDouble() ?: Double.NaN, 1e-9)
        assertEquals(JwxtFakeUpstream.CJCX_KCH, score.optString("kch"))
        assertEquals("JXB-9101", score.optString("jxbid"))
        assertEquals(true, score.optBoolean("passFlag"))
        assertEquals("初修", score.optString("examProp"), "CXCKDM_DISPLAY")
        assertEquals("必修", score.optString("kclbdm"), "KCLBDM_DISPLAY")
        // 夹具里 PSCJ / QMCJ 给的是 -1.0（上游的「没这一格」哨兵值）：`:data` 原样留（`takeIf { it > -100 }`）
        assertEquals(-1.0, score.optString("pscj")?.toDouble() ?: Double.NaN, 1e-9)
        assertNull(score.optString("djcjmc"), "空串 ⇒ null（等级制那几格）")

        // 这一条真打的是**教务**那个站点（不是 jwapp）：xscjcx.do 的表单必须带上三个分页参数
        assertEquals(1, fake.jwxt.cjcxCalls.get(), "precise-scores 打的是教务的 xscjcx.do")
        assertEquals(0, fake.jwapp.gradeCalls.get(), "这一发不碰 jwapp 的 termScore")
        val form = fake.jwxt.lastCjcxForm.orEmpty()
        assertTrue(form.contains("querySetting="), "有效成绩筛（SFYX=1）要带上，实际：$form")
        assertTrue(form.contains("pageSize=") && form.contains("pageNumber="), "分页参数一个都不能少，实际：$form")
    }

    // ── 人脸识别签到（iclassface）：records ────────────────────────────

    @Test
    fun `iclassface records：未登录 401 + date 默认今天 + 逐字段（姓名学号不投影）`() = withFakeProxy { fake ->
        val token = AccessToken.newToken()
        val session = ServeSession()
        val (server, http) = startEndpointServer(token) {
            sessionRoutes(session, token)
            iclassfaceRoutes(session)
        }
        started = server

        val anonymous = http.get(ICLASSFACE_RECORDS_PATH)
        assertEquals(401, anonymous.statusCode())
        assertEquals(ApiErrors.UNAUTHORIZED, envelopeCode(anonymous))
        assertTrue(envelopeMessage(anonymous).orEmpty().contains("登录"), "401 要指路「先登录」")
        assertEquals(0, fake.iclassface.checkinCalls.get(), "未登录那一枪不该到取数层")

        assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

        val today = todayInSystemZone().toString()
        val response = http.get(ICLASSFACE_RECORDS_PATH)
        assertEquals(200, response.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(response))
        assertNoIdentity(response.body(), "GET $ICLASSFACE_RECORDS_PATH", IDENTITY)
        assertEquals(today, fake.iclassface.lastDate, "省略 ?date= ⇒ 打出去的是服务端时区的今天")

        val data = envelopeData(response)
        assertEquals(setOf("date", "count", "records"), data.keys)
        assertEquals(today, data.optString("date"), "定下来的那一天原样回")
        assertEquals(1, data.optInt("count"), "夹具那张表第二行只有 3 格 ⇒ 不算记录")

        val record = data.arrOf("records")[0] as JsonObject
        assertEquals(setOf("time", "location", "type"), record.keys, "只有三格：姓名与学号不投影（红线）")
        assertEquals(IclassfaceFakeUpstream.RECORD_TIME, record.optString("time"), "上游原文的时刻（不转 ISO-8601）")
        assertEquals(IclassfaceFakeUpstream.RECORD_LOCATION, record.optString("location"))
        assertEquals(IclassfaceFakeUpstream.RECORD_TYPE, record.optString("type"))

        // 夹具那张表里**明明有**姓名与学号（还有一行「合计」）—— 一个都没进响应
        for (leaked in listOf(IclassfaceFakeUpstream.RECORD_NAME, IclassfaceFakeUpstream.RECORD_STUDENT_NO, "合计")) {
            assertFalse(response.body().contains(leaked), "响应里不该出现「$leaked」（夹具里有值）")
        }
    }

    @Test
    fun `iclassface records：date 形状不对 400，空表那一档是 count 0 而不是错误`() = withFakeProxy { fake ->
        val token = AccessToken.newToken()
        val session = ServeSession()
        val (server, http) = startEndpointServer(token) {
            sessionRoutes(session, token)
            iclassfaceRoutes(session)
        }
        started = server

        assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

        // 形状不对 ⇒ 400（文案要写出该长什么样）
        for (bad in listOf("2026/06/04", "20260604", "2026-6-4", "今天")) {
            val response = http.get("$ICLASSFACE_RECORDS_PATH?date=${encode(bad)}")
            assertEquals(400, response.statusCode(), bad)
            assertEquals(ApiErrors.BAD_REQUEST, envelopeCode(response), bad)
            assertTrue(envelopeMessage(response).orEmpty().contains("date"), "400 要说出是哪个参数：$bad")
        }
        assertEquals(0, fake.iclassface.checkinCalls.get(), "400 那几枪都没到取数层")

        // 给了日期就打那一天
        val day = "2026-06-04"
        val one = http.get("$ICLASSFACE_RECORDS_PATH?date=$day")
        assertEquals(200, one.statusCode())
        assertEquals(day, envelopeData(one).optString("date"))
        assertEquals(day, fake.iclassface.lastDate)

        // 「那天没有记录」= 空数组 + count 0（`:data` 的口径），不是错误页
        fake.iclassface.emptyRecords = true
        val empty = http.get("$ICLASSFACE_RECORDS_PATH?date=$day")
        assertEquals(200, empty.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(empty))
        assertEquals(0, envelopeData(empty).optInt("count"))
        assertTrue(envelopeData(empty).arrOf("records").isEmpty())
    }

    // ── 脚手架 ─────────────────────────────────────────────────────────

    private companion object {
        val LOGIN_BODY =
            """{"username":"${LibraryFakeUpstream.USERNAME}","password":"${LibraryFakeUpstream.PASSWORD}"}"""

        const val LMS_COURSES_PATH = "/api/lms/courses"
        const val LMS_ACTIVITIES_PATH = "/api/lms/activities"
        const val LMS_ACTIVITY_PATH = "/api/lms/activity"
        const val JIAOCAI_SEARCH_PATH = "/api/jiaocai/search"
        const val JIAOCAI_SSNO_PATH = "/api/jiaocai/ssno"
        const val JWAPP_SCORES_PATH = "/api/jwapp/scores"
        const val JWAPP_PRECISE_SCORES_PATH = "/api/jwapp/precise-scores"
        const val ICLASSFACE_RECORDS_PATH = "/api/iclassface/records"

        /**
         * §5.2 的红线钉子组（全是夹具里的编造值，不是真人）：
         * 登录学号、思源学堂的教师名与「本人姓名/学号」（`USER_INDEX_HTML` 里有）、
         * 人脸签到表里的姓名与学号，以及几个一眼能认出来的身份键名。
         */
        val IDENTITY = listOf(
            "username",
            "studentId",
            "学号",
            "姓名",
            LibraryFakeUpstream.USERNAME,
            LmsFakeUpstream.INSTRUCTOR_NAME,
            LmsFakeUpstream.USER_INDEX_NAME,
            LmsFakeUpstream.USER_INDEX_USER_NO,
            IclassfaceFakeUpstream.RECORD_NAME,
            IclassfaceFakeUpstream.RECORD_STUDENT_NO,
        )

        /**
         * 思源学堂详情响应里**不许**出现的东西 —— 全部**在夹具里有值**（所以这几条断言有意义）：
         * 那三个要站点会话的附件地址、回放/直播的地址与教师名单。
         */
        val FORBIDDEN_LMS = listOf(
            "/api/uploads/${LmsFakeUpstream.UPLOAD_ID}/blob",
            "downloadUrl",
            "previewUrl",
            "attachmentUrl",
            "replayVideos",
            "liveStreams",
            "liveInstructorNames",
            "submissionList",
            "我完成了",
            LmsFakeUpstream.SUBMISSION_ID.toString(),
        )

        /** 一个字面量都不用去猜：URL 上的查询参数要 encode（中文检索词）。 */
        fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
    }
}

/** `data.someBlock`：是对象就给出来，`null`（或者压根没这一格）就给 `null`。 */
private fun JsonObject.optObject(key: String): JsonObject? = this[key]?.takeIf { it is JsonObject }?.jsonObject
