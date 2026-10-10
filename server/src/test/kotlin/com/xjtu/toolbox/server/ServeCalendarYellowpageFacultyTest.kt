package com.xjtu.toolbox.server

import com.xjtu.toolbox.calendar.SchoolCalendarApi
import com.xjtu.toolbox.calendar.SchoolCalendarFakeUpstream
import com.xjtu.toolbox.faculty.FacultyApi
import com.xjtu.toolbox.faculty.FacultyApiSource
import com.xjtu.toolbox.faculty.FacultyFixture
import com.xjtu.toolbox.library.LibraryFakeUpstream
import com.xjtu.toolbox.yellowpage.YellowPageApi
import com.xjtu.toolbox.yellowpage.YellowPageFixture
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * 免登录三域的契约测试（`/api/calendar/school` · `/api/info/yellowpage` · `/api/info/faculty`）。
 *
 * 各自的假上游形态（`:testkit`）：
 * - 校历：走 [FakeCampusProxy]（假代理按 host 分派 `workflow.xjtu.edu.cn`），源注入
 *   `SchoolCalendarApi(SchoolCalendarFakeUpstream.URL)`（http 基址，绕开 TLS 隧道那半台）；
 * - 黄页：`:core` 的 [YellowPageApi] 收 Ktor 客户端 ⇒ `MockEngine` 喂两条夹具响应；
 * - 教师检索：`:data` 的 [FacultyApi] 收 OkHttp 客户端 ⇒ 拦截器按路径分派夹具。
 *
 * 断言形状逐字段；expected 值全部来自夹具原文（`:testkit` 的常量 / 页面样本），
 * 不从实现输出里抄。三条红线（闸门 401 / 信封 / 不含登录身份）每条都过一遍。
 */
class ServeCalendarYellowpageFacultyTest {

    private var started: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    @AfterTest
    fun stopServer() {
        started?.stop(gracePeriodMillis = 100, timeoutMillis = 500)
        started = null
    }

    // ── 校历 ───────────────────────────────────────────────────────────────────

    @Test
    fun `校历：全部学期逐字段，日期 ISO，免登录不带任何会话`() = withFakeProxy(casEnabled = false) { _ ->
        val token = AccessToken.newToken()
        // 源在**假代理已起之后**才构造：SchoolCalendarApi 一构造就建 HttpClients.base（进程级懒加载，
        // 建出来的那一刻把当时的 ProxySelector 抄走 —— 次序不能换）。
        val (server, http) = startEndpointServer(token) {
            calendarRoutes(SchoolCalendarApi(SchoolCalendarFakeUpstream.URL))
        }
        started = server

        // 过闸门 + 信封
        val response = http.get("/api/calendar/school")
        assertEquals(200, response.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(response))
        assertNoIdentity(response.body(), "GET /api/calendar/school", LOGIN_IDENTITY)

        val data = envelopeData(response)
        val terms = data.arrOf("terms")

        // 逐字段：第一学期（夹具原文的 id/日期/名字；结束日 = exam_end 优先）
        val term1 = terms[0].let { it as JsonObject }
        assertEquals("2026-2027-1", term1.optString("id"))
        assertEquals("2026-09-07", term1.optString("startDate"))
        assertEquals("2027-01-15", term1.optString("endDate"))
        assertEquals("2026-2027学年第一学期", term1.optString("termName"))
        assertEquals("2026-2027", term1.optString("yearName"))
        assertEquals(
            ChronoUnit.DAYS.between(LocalDate.parse("2026-09-07"), LocalDate.parse("2027-01-15")) / 7,
            term1.optInt("totalWeeks")?.toLong(),
            "totalWeeks 是 :core 算的 (end-start)/7",
        )
        val workDays = workDayCount("2026-09-07", "2027-01-15")
        assertEquals(workDays, term1.optInt("workDays"), "workDays 是闭区间内的工作日数（:core 同口径）")

        // 事件逐字段：中秋 / 国庆 / 元旦（按开始日期升序），国庆带 remark（specialEvents 关联）
        val events1 = term1.arrOf("events")
        assertEquals(3, events1.size)
        val midAutumn = events1[0] as JsonObject
        assertEquals("中秋节-2026-09-25", midAutumn.optString("id"))
        assertEquals("中秋节", midAutumn.optString("name"))
        assertEquals("2026-09-25", midAutumn.optString("startDate"))
        assertEquals("2026-09-27", midAutumn.optString("endDate"))
        assertEquals("", midAutumn.optString("remark"))
        assertEquals(3, midAutumn.optInt("days"))
        assertEquals("#196dd0", midAutumn.optString("colorHex"))
        val national = events1[1] as JsonObject
        assertEquals("国庆节", national.optString("name"))
        assertEquals(7, national.optInt("days"))
        assertEquals("放假 7 天；调休与补课安排以教务处通知为准。", national.optString("remark"))
        val newYear = events1[2] as JsonObject
        assertEquals("元旦", newYear.optString("name"))
        assertEquals("2027-01-01", newYear.optString("startDate"))

        // 第二学期：id / 起止（exam_end）/ 劳动节
        val term2 = terms[1] as JsonObject
        assertEquals("2026-2027-2", term2.optString("id"))
        assertEquals("2027-02-22", term2.optString("startDate"))
        assertEquals("2027-07-02", term2.optString("endDate"))
        assertEquals("2026-2027学年第二学期", term2.optString("termName"))
        val labor = (term2.arrOf("events")[0] as JsonObject)
        assertEquals("劳动节", labor.optString("name"))
        assertEquals(5, labor.optInt("days"))

        // 闸门：不带令牌 401（信封）；错令牌 401
        assertEquals(401, http.get("/api/calendar/school", bearer = null).statusCode())
        assertEquals(401, http.get("/api/calendar/school", bearer = "wrong").statusCode())
    }

    // ── 黄页 ───────────────────────────────────────────────────────────────────

    @Test
    fun `黄页：类别与部门逐字段（过滤+排序由 core 解析钉住），免登录`() {
        val token = AccessToken.newToken()
        val (server, http) = startEndpointServer(token) {
            infoRoutes(
                yellowpage = YellowPageApi(mockYellowPageClient(), cache = null),
                faculty = FacultyApiSource(FacultyApi(mockFacultyClient())),
            )
        }
        started = server

        val response = http.get("/api/info/yellowpage")
        assertEquals(200, response.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(response))
        assertNoIdentity(response.body(), "GET /api/info/yellowpage", LOGIN_IDENTITY)

        val data = envelopeData(response)
        // updateTime：夹具原文 2026-08-01T09:30:00 → :core 的展示串
        assertEquals("2026年08月01日", data.optString("updateTime"))

        // 类别：status==1 的按 (sort,id) 升序（已停用的线被滤掉）
        val categories = data.arrOf("categories")
        assertEquals(2, categories.size)
        assertCategory(categories[0] as JsonObject, 1, "教务处")
        assertCategory(categories[1] as JsonObject, 2, "学生工作部（处）")

        // 部门：同样过滤 + 排序；办公电话照夹具原文（假号码）
        val departments = data.arrOf("departments")
        assertEquals(3, departments.size)
        assertDepartment(departments[0] as JsonObject, 11, 1, "教学运行中心", "029-82668888")
        assertDepartment(departments[1] as JsonObject, 20, 2, "学生事务大厅", "029-82668891/029-82668892")
        assertDepartment(departments[2] as JsonObject, 12, 1, "综合办公室", "029-82668890")

        // 闸门
        assertEquals(401, http.get("/api/info/yellowpage", bearer = null).statusCode())
        assertEquals(401, http.get("/api/info/yellowpage", bearer = "wrong").statusCode())

        // 信封里不该有身份键
        for (key in listOf("studentNumber", "学号", "手机号")) {
            assertFalse(response.body().contains(key), "黄页响应不该带「$key」")
        }
    }

    private fun assertCategory(obj: JsonObject, id: Int, name: String) {
        assertEquals(id, obj.optInt("id"))
        assertEquals(name, obj.optString("name"))
        assertEquals(setOf("id", "name"), obj.keys, "类别只投影 id/name（status/sort 是解析期的东西）")
    }

    private fun assertDepartment(obj: JsonObject, id: Int, categoryId: Int, name: String, phone: String) {
        assertEquals(id, obj.optInt("id"))
        assertEquals(categoryId, obj.optInt("categoryId"))
        assertEquals(name, obj.optString("name"))
        assertEquals(phone, obj.optString("phone"))
        assertEquals(setOf("id", "categoryId", "name", "phone"), obj.keys, "部门只投影这四个字段")
    }

    // ── 教师检索 ───────────────────────────────────────────────────────────────

    @Test
    fun `教师检索：members 逐字段，contactsAvailable 为 false 且不投影任何联系方式`() {
        val token = AccessToken.newToken()
        val (server, http) = startEndpointServer(token) {
            infoRoutes(
                yellowpage = YellowPageApi(mockYellowPageClient(), cache = null),
                faculty = FacultyApiSource(FacultyApi(mockFacultyClient())),
            )
        }
        started = server

        val response = http.get("/api/info/faculty?q=示例")
        assertEquals(200, response.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(response))
        assertNoIdentity(response.body(), "GET /api/info/faculty", LOGIN_IDENTITY)

        val data = envelopeData(response)
        assertEquals(false, data.optBoolean("contactsAvailable"), "serve 不拉联系方式 ⇒ 开关必须如实 false")
        assertEquals(4, data.optInt("total"), "夹具 totalnum=4")
        assertEquals(1, data.optInt("totalPage"))
        assertEquals(1, data.optInt("pageIndex"))

        val members = data.arrOf("members")
        assertEquals(4, members.size, "夹具 teacherData 四条全收（默认查询不过滤职称/导师）")

        // 第 1 行全字段（fixture 原文经 :data 的 parseMember：trim/归一/补地址）
        val m1 = members[0] as JsonObject
        assertEquals(20101L, m1.optLong("teacherId"))
        assertEquals("示例甲", m1.optString("name"))
        assertEquals("Jia Shi Li", m1.optString("englishName"))
        assertEquals("shi li jia", m1.optString("pinyin"))
        assertEquals("https://gr.xjtu.edu.cn/example-a/zh_CN/index.htm", m1.optString("homepageUrl"))
        assertEquals("示例学院", m1.optString("collegeName"))
        assertEquals("教授", m1.optString("proRank"))
        assertEquals("系主任", m1.optString("job"))
        assertEquals("物理学", m1.optString("discipline"))
        assertEquals("博士", m1.optString("degree"))
        assertEquals("研究生", m1.optString("education"))
        assertEquals("西安交通大学", m1.optString("graduatedUniversity"))
        assertEquals(true, m1.optBoolean("isDoctoralTutor"))
        assertEquals(false, m1.optBoolean("isMasterTutor"))
        assertEquals("示例甲的简介。", m1.optString("profile"))
        assertEquals(listOf("方向甲", "方向乙"), m1.arrOf("researchDirections").strings())
        assertTrue(m1.optString("picUrl")!!.endsWith("/20101.jpg"), "头像路径照 :data 解析（相对路径）")
        assertTrue(m1.optString("picUrl")!!.startsWith("/"), "头像路径以 / 开头")
        assertEquals("2005-09", m1.optString("entryTime"))
        assertEquals("2026-08-17", m1.optString("lastUpdate"))
        assertEquals(1234L, m1.optLong("clickTimes"))

        // 第 2 行：只给 teacherId+name 的那条 —— 其余全是默认值；主页地址由英文接口补上
        val m2 = members[1] as JsonObject
        assertEquals(20102L, m2.optLong("teacherId"))
        assertEquals("示例乙", m2.optString("name"))
        assertEquals("https://gr.xjtu.edu.cn/example-b/zh_CN/index.htm", m2.optString("homepageUrl"))
        assertEquals("", m2.optString("proRank"))
        assertEquals(false, m2.optBoolean("isDoctoralTutor"))

        // 第 4 行：站外主页地址原样透出（normalizeHomepage 认不出 ORCID）
        assertEquals("https://orcid.org/0000-0000-0000-0000", (members[3] as JsonObject).optString("homepageUrl"))

        // ★ 红线：一个联系方式字段都不许出现（contactsAvailable=false 的投影面）
        val memberKeys = m1.keys
        for (contactKey in listOf("email", "contact", "phone", "mobilePhone", "officeLocation", "address")) {
            assertFalse(contactKey in memberKeys, "members 里不该有「$contactKey」字段")
        }
        val body = response.body()
        assertFalse(body.contains("@example.edu.cn"), "响应体不该出现邮箱")
        assertFalse(body.contains("13000000000"), "响应体不该出现手机号")
        assertFalse(body.contains("029-0000000"), "响应体不该出现联系方式字段的值")

        // 闸门
        assertEquals(401, http.get("/api/info/faculty", bearer = null).statusCode())
        assertEquals(401, http.get("/api/info/faculty", bearer = "wrong").statusCode())

        // 认不得的筛选参数不炸：college 非数字按 0（不限）处理
        val page1 = http.get("/api/info/faculty?page=abc")
        assertEquals(200, page1.statusCode())
    }

    // ── 小工具 ─────────────────────────────────────────────────────────────────

    private fun workDayCount(start: String, end: String): Int {
        var day = LocalDate.parse(start)
        val last = LocalDate.parse(end)
        var count = 0
        while (day <= last) {
            val dow = day.dayOfWeek
            if (dow != java.time.DayOfWeek.SATURDAY && dow != java.time.DayOfWeek.SUNDAY) count++
            day = day.plusDays(1)
        }
        return count
    }

    private fun JsonArray.strings(): List<String> = map { it.jsonPrimitive.content }

    private companion object {
        /** 红线：登录用户本人的身份（学号是夹具那组假账号；姓名/手机号不进这一层）。 */
        val LOGIN_IDENTITY = listOf(
            LibraryFakeUpstream.USERNAME,
            "password",
            "username",
            "studentId",
            "学号",
            "手机号",
        )
    }
}