package com.xjtu.toolbox.server

import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.agent.AgentMemory
import com.xjtu.toolbox.calendar.SchoolCalendarApi
import com.xjtu.toolbox.calendar.SchoolCalendarFakeUpstream
import com.xjtu.toolbox.emptyroom.EmptyRoomFakeUpstream
import com.xjtu.toolbox.faculty.FacultyApi
import com.xjtu.toolbox.jwxt.JwxtFakeUpstream
import com.xjtu.toolbox.library.LibraryFakeUpstream
import com.xjtu.toolbox.platform.keyValueStore
import com.xjtu.toolbox.schedule.KeyValueCustomCourseStore
import com.xjtu.toolbox.schedule.KeyValueScheduleCacheStore
import com.xjtu.toolbox.util.AppJson
import com.xjtu.toolbox.yellowpage.YellowPageApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * serve 侧白名单工具层的**逐档验收**（`docs/agent-web-spec.md` §6；D10 的 S-c）。
 *
 * ## 它验的是哪一层
 *
 * `ServeAgentTest` 验的是「HTTP 端点怎么答」（其中一条已经钉住 `toolAllowlist` 来自真下发的定义）；
 * 这里验的是**工具本体**：九件工具逐个至少一例，答复里的字与 `:app` 同一句话（那几段是逐字搬的，
 * 见 `ServeAgentToolCopyDriftTest`），以及**没开放的那一串一个都不在工具表里**。
 *
 * ## 手法与纪律
 *
 * - 上游一律是**夹具**：教务 / 智慧教室 / 空闲教室 CDN / 校历 / 黄页 / 教师检索全走 `:testkit`
 *   那几台假上游（`withFakeProxy` 装的那张 host 分派表），**绝不打真站点**；
 * - 不打第三方模型：这一层压根不碰模型（模型那一枪由 `ServeAgentTest` 的夹具传输负责）；
 * - 落盘一律在**临时数据根**里（`withFakeProxy` 指过去的那个），绝不碰用户真实的
 *   `~/.local/share/xjtu-toolbox`；
 * - 课表缓存 / 自建日程用的是 `keyValueStore`（jvm 上的进程内那一档）⇒ 每条用例先按名字清一遍，
 *   否则上一条用例的课表缓存会被下一条读成「已经有缓存了」。
 *
 * ⚠️ **每个用例第一件事是进 [withFakeProxy]**：`ServeSession` 一构造就建 `HttpClients.base`，
 * 而那个客户端在建出来那一刻把当时的 `ProxySelector` 与信任库抄走 —— 少了这一步，
 * 这一整个类会直连真站点（同一条教训见 `ServeAgentTest` 的类 KDoc）。
 */
class ServeAgentToolsTest {

    /** 白名单九件，逐条与 `/api/agent/config` 的 `toolAllowlist` 同一份定义。 */
    private val expectedNames = listOf(
        "get_calendar",
        "get_schedule",
        "get_exam_schedule",
        "get_empty_rooms",
        "search_yellow_page",
        "preference",
        "find_faculty",
        "app_guide",
        "calculate",
    )

    private fun wipeToolStores() {
        for (name in listOf(
            CACHE_STORE,
            CUSTOM_STORE,
            AgentMemory.STORE + AccountContext.safeSuffix(),
            AgentMemory.STORE,
        )) {
            runCatching { keyValueStore(name).clear() }
        }
    }

    /**
     * 一份工具层：真取数（假上游）+ 真落盘（临时根 / 进程内键值）。
     *
     * @param session 需要登录态的用例自己传一个已登录的（见各用例）。
     */
    private fun newTools(session: ServeSession = ServeSession()): ServeAgentTools = ServeAgentTools(
        session = session,
        memory = AgentMemory(
            storeOf = { keyValueStore(AgentMemory.STORE + AccountContext.safeSuffix()) },
            legacyStoreOf = { keyValueStore(AgentMemory.STORE) },
        ),
        dataCache = KeyValueScheduleCacheStore(keyValueStore(CACHE_STORE)),
        customCourses = KeyValueCustomCourseStore(keyValueStore(CUSTOM_STORE)),
        calendar = SchoolCalendarApi(SchoolCalendarFakeUpstream.URL),
        yellowPage = YellowPageApi(mockYellowPageClient(), cache = null),
        faculty = FacultyApi(mockFacultyClient()),
    )

    /** 真登一次教务（CAS 那半台由 `LibraryFakeUpstream` 扮演）。 */
    private fun loggedInSession(): ServeSession {
        val session = ServeSession()
        val outcome = runBlocking {
            session.login(LibraryFakeUpstream.USERNAME, LibraryFakeUpstream.PASSWORD)
        }
        assertEquals(LoginOutcome.Success, outcome, "夹具账号应当登得上（假 CAS）")
        return session
    }

    // ── 1. 纯逻辑三件（现状共享实现，一行端代码都没有）───────────────────────

    @Test
    fun `纯逻辑三件：算术 功能引导 偏好读写在同一个工具层里`() = withFakeProxy { _ ->
        wipeToolStores()
        val tools = newTools()

        runBlocking {
            assertEquals("2*3+4 = 10", tools.execute("calculate", """{"expression":"2*3+4"}"""))
            assertEquals("error: missing_param; param: expression", tools.execute("calculate", "{}"))

            val campus = tools.execute("app_guide", """{"topic":"campus"}""")
            assertTrue(campus.contains("兴庆校区"), "campus 话题没给校区那段：$campus")
            assertTrue(!campus.contains("App 功能与入口"), "campus 话题不该带 App 功能那段")

            // preference：给 value 是记下，不给是删掉（与 :app 同一条判据）
            assertEquals("记住了：常在 = 兴庆", tools.execute("preference", """{"key":"常在","value":"兴庆"}"""))
            assertTrue(tools.memoryBlock().contains("- 常在：兴庆"), "偏好没进系统提示：${tools.memoryBlock()}")
            assertEquals("忘掉了「常在」。", tools.execute("preference", """{"key":"常在"}"""))
            assertTrue(tools.memoryBlock().isEmpty(), "删掉之后不该还在：${tools.memoryBlock()}")
        }

        // 记忆是**落在 store 上**的：换一份工具层（新一份 AgentMemory）读得回来
        runBlocking { tools.execute("preference", """{"key":"校区","value":"兴庆"}""") }
        assertTrue(
            newTools().memoryBlock().contains("- 校区：兴庆"),
            "偏好没落到 store 上：${newTools().memoryBlock()}",
        )
    }

    // ── 2. 公开通讯录两件（黄页 / 教师检索，与桌面同形）──────────────────────

    @Test
    fun `公开通讯录两件：黄页与教师检索`() = withFakeProxy { _ ->
        wipeToolStores()
        val tools = newTools()

        runBlocking {
            val byCategory = tools.execute("search_yellow_page", """{"category":"教务处"}""")
            assertTrue(byCategory.contains("教学运行中心｜029-82668888｜教务处"), "类别筛选没生效：$byCategory")
            assertTrue(!byCategory.contains("已撤销的科室"), "停用的科室不该出现：$byCategory")
            assertEquals(
                "error: not_found; category: 不存在的机构; options: 教务处, 学生工作部（处）",
                tools.execute("search_yellow_page", """{"category":"不存在的机构"}"""),
            )

            val faculty = tools.execute("find_faculty", """{"name":"示例甲"}""")
            assertTrue(faculty.contains("【示例甲】"), "夹具那位老师没找到：$faculty")
            assertTrue(faculty.contains("研究方向：方向甲、方向乙"), "研究方向那段不对：$faculty")
            assertEquals("error: missing_param; param: name", tools.execute("find_faculty", "{}"))
        }
    }

    // ── 3. get_calendar：当前时间 + 校历（免登录、公开）──────────────────────

    @Test
    fun `get_calendar 给的是「当前 + 作息 + 校历」那两段`() = withFakeProxy(casEnabled = false) { _ ->
        wipeToolStores()
        val reply = runBlocking { newTools().execute("get_calendar", "{}") }

        // 前半段是 `:app` 的 getCurrentTime（逐字搬来的那段），后半段是校历
        assertTrue(reply.startsWith("当前："), "第一段应当是「当前：…」：$reply")
        assertTrue(reply.contains("作息（"), "少了今天那套作息：$reply")
        assertTrue(reply.contains("校历："), "少了校历那一段：$reply")
        assertTrue(reply.contains("学期："), "校历没给学期起止：$reply")
    }

    // ── 4. get_schedule：真登录 + 假教务上游 + 真课表缓存 ────────────────────

    @Test
    fun `get_schedule 走合并后的课表（调停补课已并进去）并按日期取那一天`() = withFakeProxy { _ ->
        wipeToolStores()
        // 节假日那一格先塞一份缓存：不然 `HolidayApi` 会去撞两个**没被夹具扮演**的公开接口
        //（假代理只认它那张 host 表，别的目标会走真网络直到超时）—— 这条用例验的是课表，不该把时间花在那上面。
        // 格式就是 `HolidayApi` 自己那份缓存（`{"日期":"节日名"}`），值编造。
        keyValueStore(CACHE_STORE).putString("holiday_dates", """{"2026-10-01":"国庆节"}""")
        val tools = newTools(session = loggedInSession())

        val reply = runBlocking {
            tools.execute("get_schedule", """{"term":"${JwxtFakeUpstream.TERM_NEW}","date":"${JwxtFakeUpstream.TERM_START_DATE}"}""")
        }

        // 2026-09-14 是夹具那个学期的第一天（周一，第 1 周）⇒ 取到周一那两节
        assertTrue(reply.contains("2026-09-14 第1周周一"), "那一天那一周没算对：$reply")
        assertTrue(reply.contains(JwxtFakeUpstream.SCHEDULE_1_COURSE), "少了那门课：$reply")
        assertTrue(reply.contains(JwxtFakeUpstream.SCHEDULE_1_ROOM), "少了教室：$reply")
        assertTrue(reply.contains(JwxtFakeUpstream.SCHEDULE_1_TEACHER), "少了对那天的教师名：$reply")
        // 调课记录那条尾巴也在（夹具第 5 周那次调课，`:app` 同一条 note）
        assertTrue(reply.contains("调课记录："), "少了调课记录那句：$reply")

        // 卡片刻进这一轮的控件里（`widget` 事件就是从这走的）
        val widgets = tools.drainWidgets()
        assertEquals(1, widgets.size, "这一天应当出一张课表卡：$widgets")
    }

    // ── 5. get_exam_schedule：教务考试表 ────────────────────────────────────

    @Test
    fun `get_exam_schedule 给日期 时间 地点 座位`() = withFakeProxy { _ ->
        wipeToolStores()
        val tools = newTools(session = loggedInSession())

        val reply = runBlocking { tools.execute("get_exam_schedule", "{}") }

        assertTrue(reply.startsWith("考试安排（1场）："), "少了表头：$reply")
        assertTrue(reply.contains(JwxtFakeUpstream.EXAM_DATE), "少了考试日期：$reply")
        assertTrue(reply.contains(JwxtFakeUpstream.EXAM_TIME), "少了考试时间：$reply")
        assertTrue(reply.contains(JwxtFakeUpstream.EXAM_ROOM), "少了考场：$reply")
        assertTrue(reply.contains("座位 ${JwxtFakeUpstream.EXAM_SEAT}"), "少了座位号：$reply")
        assertEquals(1, tools.drainWidgets().size, "考试也出一张卡")
    }

    // ── 6. get_empty_rooms：空闲教室（CDN 那一档，免登录）────────────────────

    @Test
    fun `get_empty_rooms 按节次筛出空闲的那几间，并把节次列出来`() = withFakeProxy(casEnabled = false) { _ ->
        wipeToolStores()
        val tools = newTools()

        val reply = runBlocking {
            tools.execute(
                "get_empty_rooms",
                """{"campus":"兴庆校区","building":"主楼A","section":1,"date":"${EmptyRoomFakeUpstream.DATE}"}""",
            )
        }

        // 第 1 节空着的是夹具那四间（主楼A 那天五间里排掉一间上课的）
        assertTrue(
            reply.contains("空教室（兴庆校区 主楼A 第1节 ${EmptyRoomFakeUpstream.DATE}，共4间）"),
            "表头不对：$reply",
        )
        assertTrue(reply.contains("主楼A-102｜48 座｜空闲节次 1,2,3,4,5,6,7,8,9,10,11"), "少了全天空的那间：$reply")
        assertTrue(reply.contains("203｜24 座｜空闲节次 1,2,3,4,5"), "少了一间：$reply")
        assertEquals(1, tools.drainWidgets().size, "空教室也出一张卡")
    }

    // ── 7. 白名单之外的档：一个都不下发 ──────────────────────────────────────

    /**
     * 这条是设计稿 §6.2 那把尺子的落点：**没开放的档一个字都不画**（画了就得能用）。
     *
     * 名单逐条对着 §6.2 的表写（写 / 涉及钱 / 涉及学号姓名 / 要站点会话 / 联网搜索那几族），
     * 不是从实现里抄回来的 —— 谁哪天偷偷放开一档，这一条会当场红。
     */
    @Test
    fun `白名单之外的工具不会被下发，调过来也只会回 not_found`() = withFakeProxy(casEnabled = false) { _ ->
        wipeToolStores()
        val tools = newTools()

        val names = (AppJson.parseToJsonElement(tools.toolDefinitions) as JsonArray)
            .map { it.jsonObject.getValue("function").jsonObject.getValue("name").jsonPrimitive.content }
        assertEquals(expectedNames, names, "工具表与白名单那九件对不上（顺序也该与 :core 那份目录一致）")

        for (missing in listOf(
            // 端能力那三档（honored 空集：浏览器与 serve 都没有那些端上资源）
            "set_alarm", "app_setting", "add_schedule_event",
            // 写路径 / 钱 / 本人敏感
            "get_grades", "get_fitness_score", "get_attendance", "get_card_info", "get_coupons",
            // 要站点会话 / 独立一大块 / 第二批
            "get_notifications", "search_school_courses", "get_library", "list_zyxf", "read_zyxf_file",
            "get_textbooks", "get_lms", "get_lms_activity",
            // 联网搜索（联网的是 serve 那台机器，语义会变）
            "web_search", "web_fetch",
        )) {
            assertTrue(missing !in names, "没开放的档画出来了：$missing")
        }

        runBlocking {
            assertEquals("error: not_found; tool: get_grades", tools.execute("get_grades", "{}"))
            assertEquals("error: not_found; tool: web_search", tools.execute("web_search", """{"query":"x"}"""))
        }
    }

    private companion object {
        /** 课表族缓存的落盘文件（进程内那一档，用例之间按名字清）。 */
        const val CACHE_STORE = "serve_tools_cache_test"

        /** 自建日程的落盘文件。 */
        const val CUSTOM_STORE = "serve_tools_custom_test"
    }
}
