package com.xjtu.toolbox.inbox

import com.xjtu.toolbox.auth.withYwtbLogin
import com.xjtu.toolbox.library.LibraryFakeUpstream
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.util.safeParseJsonObject
import com.xjtu.toolbox.ywtb.YwtbFakeUpstream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 消息收纳取数的**字段级口径**：一网通办那四路（消息 / 事务中心 / 预约 / 校车）对着 `:testkit` 的
 * [YwtbFakeUpstream] 逐字段读。
 *
 * ## 为什么在 `:data` 而不是 `:app`
 *
 * 这一轮把消息收纳的取数与站点从 `:app` 搬进了 `:data`（桌面端第 12 条真数据路由）。搬之前先用
 * 夹具把**搬之前**的口径钉住：`:app` 那边一行逻辑没改，只是文件换了地方、`Context` 换成一条构造参数
 * —— 但「没改」这句话得有证据，这些断言就是那个证据。手法与空闲教室 / 校园卡 / 教务那几条
 *（`EmptyRoomApiJvmTest` / `CampusCardApiJvmTest` / `SchoolCourseApiJvmTest`）逐条对齐：
 * 要登录的那几路走**真登录链**（[withYwtbLogin]），URL 一个字符都不改。
 *
 * 从 `:app` 的 `InboxTest` 迁过来两条纯解析用例（`SchoolInbox.parseMessages`/`parseTodos` 是
 * `internal`，模块边界挡着 ⇒ 测试跟着代码走，见那里与 `ScoreReportTermTest` 同一条注释）。
 *
 * ## 这些断言从哪来
 *
 * 全部来自**夹具原文**（`YwtbFakeUpstream` 里那些常量与 JSON），**不是从跑通的实现里抄回来的**。
 * 夹具摆的四组样本把该被滤掉的东西也摆在了明面上：事务中心推来的消息（`appId` 那个常量）、
 * 座位类的消息（落款「图书馆预约系统」）、校车那边的 0 条预约 —— 「过滤与回落给什么」因此写在明面上。
 */
class InboxApiJvmTest {

    /** 取数按账号分命名空间（`InboxStore` 的 key 里带它）—— 这里就用假上游那组账号。 */
    private val account = LibraryFakeUpstream.USERNAME

    // ══════ ① 真登录之后：四路取数写进 store ══════

    /**
     * 走的是**真登录链**：一网通办门户 → 302 到统一认证 → 凭据表单 POST（密码 RSA 加密）→ 种 TGC
     * → 302 回跳门户（`ticket=` 是一枚 **JWT**）→ 门户页 200 → `idToken` 落进站点令牌；然后
     * `AppInboxSource.refresh` 拿它去打那四路。
     *
     * 四路的 URL 与生产代码里那四条**逐字相同**（夹具按「路径 + 查询串」逐字比对，不认识就 404）
     * ⇒ URL 漂了这里会因为「夹具没被打到 / 条目为空」而红，而不是「换了域名但夹具照答」。
     */
    @Test
    fun `真登录之后：四路取数写进 store，消息与待办都读得出夹具样本`() {
        withYwtbLogin { manager, fake ->
            val source = AppInboxSource(manager)
            val now = System.currentTimeMillis()
            runBlocking { source.refresh(account) }

            // ── 四路各打到一次，走的都是同一个账号的令牌 ──
            assertEquals(1, fake.ywtb.messageCalls.get(), "消息那一路应当恰好被打一次")
            assertEquals(1, fake.ywtb.todoCalls.get(), "事务中心那一路应当恰好被打一次")
            assertEquals(1, fake.ywtb.bookingCalls.get(), "预约中心那一路应当恰好被打一次")
            assertEquals(1, fake.ywtb.busCalls.get(), "校车那一路应当恰好被打一次")
            assertEquals(YwtbFakeUpstream.BUS_URL, fake.ywtb.lastBusinessUrl.get(), "最后那一枪就是校车那一条 URL")
            assertEquals(
                YwtbFakeUpstream.ID_TOKEN,
                fake.ywtb.lastIdToken.get(),
                "四路都只认 CAS 换来的那颗 x-id-token",
            )
            assertTrue(fake.library.credentialPosts.get() >= 1, "统一认证应当真提交过凭据（不是 SSO 直通）")
            assertTrue(fake.ywtb.portalLandings.get() >= 1, "CAS 回跳应当真落在门户上")

            val data = InboxStore.load(account)
            assertTrue(data.schoolFetchedAt > 0, "消息那一路成功 ⇒ 记下这次拉取的时刻")

            // ── 消息：四条样本里两条该被滤掉（事务中心那条 / 座位那条）──
            assertEquals(
                listOf("school:${YwtbFakeUpstream.MESSAGE_SIGNED_ID}", "school:${YwtbFakeUpstream.MESSAGE_BARE_ID}"),
                data.messages.map { it.id },
            )
            val signed = data.messages.first()
            assertEquals(YwtbFakeUpstream.MESSAGE_SIGNED_TITLE, signed.title)
            // 来源取正文末尾括号里的落款（不是 `appName`）
            assertEquals(YwtbFakeUpstream.MESSAGE_SIGNED_SOURCE, signed.source)
            assertEquals(InboxCategories.school(YwtbFakeUpstream.MESSAGE_SIGNED_SOURCE), signed.category)
            assertEquals(
                "您当前的电费余额不足，请及时缴费。 (${YwtbFakeUpstream.MESSAGE_SIGNED_SOURCE})",
                signed.body,
                "正文按 HTML→纯文本 收好（`<p>` 换行、标签去掉）",
            )
            assertEquals(AppRoute.Browser(YwtbFakeUpstream.MESSAGE_SIGNED_LINK).id, signed.route)
            val delta = abs(signed.time - (now - 3 * 60 * 60 * 1000))
            assertTrue(delta < 5 * 60 * 1000, "`editTime` 应当按 Asia/Shanghai 解成毫秒（现在 -3 小时）：${signed.time}")

            // 没有落款、`title` 也空的那条：来源取 `appName`，标题回落成来源
            val bare = data.messages.last()
            assertEquals(YwtbFakeUpstream.MESSAGE_BARE_APP_NAME, bare.source)
            assertEquals(YwtbFakeUpstream.MESSAGE_BARE_APP_NAME, bare.title)
            assertEquals(AppRoute.Browser(YwtbFakeUpstream.MESSAGE_BARE_LINK).id, bare.route, "`mobileUrl` 空 ⇒ 退到 `url`")

            // ── 事务中心：两条（一条有 taskId、一条没有）──
            val todos = data.todos.getValue(InboxCategories.SCHOOL_TODO)
            assertEquals(2, todos.size)
            val withTaskId = todos[0]
            assertEquals("todo:${YwtbFakeUpstream.TODO_ID}", withTaskId.id)
            assertEquals(YwtbFakeUpstream.TODO_TITLE, withTaskId.title)
            assertEquals(YwtbFakeUpstream.TODO_SOURCE, withTaskId.source)
            assertEquals(YwtbFakeUpstream.TODO_NODE, withTaskId.body)
            assertEquals(InboxCategories.SCHOOL_TODO, withTaskId.category)
            assertEquals(AppRoute.Browser(YwtbFakeUpstream.TODO_LINK).id, withTaskId.route)
            val withoutTaskId = todos[1]
            assertTrue(
                withoutTaskId.id.startsWith("todo:${YwtbFakeUpstream.TODO_BARE_TITLE}@"),
                "没有 `taskId` ⇒ id 回落成「标题@addTime」：${withoutTaskId.id}",
            )
            assertEquals(YwtbFakeUpstream.TODO_BARE_TITLE, withoutTaskId.title, "标题取 `transactionName`")
            assertEquals(YwtbFakeUpstream.TODO_BARE_SOURCE, withoutTaskId.source, "`appName`/`custom1` 都空 ⇒ 回落成「事务中心」")
            assertEquals("${YwtbFakeUpstream.TODO_BARE_NODE} · 已超时", withoutTaskId.body, "`timeOut=true` ⇒ 正文尾上「已超时」")
            assertEquals(AppRoute.Browser(YwtbFakeUpstream.TODO_BARE_LINK).id, withoutTaskId.route)

            // ── 预约 / 校车：只报数量；校车 0 条 ⇒ 一条都不出 ──
            val booking = data.todos.getValue(InboxCategories.BOOKING).single()
            assertEquals("booking:预约中心", booking.id)
            assertEquals(
                "预约中心 有 ${YwtbFakeUpstream.BOOKING_RESERVATION_TOTAL} 个待使用的预约",
                booking.title,
            )
            assertEquals("预约中心", booking.source)
            assertNull(booking.route, "预约记录只报数量，没有可点的办理链接")

            // ── TTL：30 分钟内不再拉，过了就该拉（与 `SchoolInbox.TTL_MS` 同一把尺）──
            assertTrue(!source.isDue(account, data.schoolFetchedAt + SchoolInbox.TTL_MS - 1), "TTL 内不算该拉")
            assertTrue(source.isDue(account, data.schoolFetchedAt + SchoolInbox.TTL_MS), "过了 TTL 就该拉")
        }
    }

    // ══════ ② 图书馆座位那条补拉的缝 ══════

    /**
     * `afterRefresh` 只在**有座位待办**时才叫本端那件事（`:app` = 现查一次座位系统 + `LibraryStatus.publish`；
     * 桌面传 null ⇒ 什么都不做）。这一条把搬迁前那个早返回逐条钉住：它搬进 `:data` 之后仍然是
     * 「没有座位待办就不打扰宿主」，而不是「每次刷新都现查一次图书馆」。
     */
    @Test
    fun `座位待办存在时才补拉图书馆（本端没有那一路就什么都不做）`() {
        var requeries = 0
        val local = AppInboxSource(sessionManager = null, requeryLibraryBooking = { requeries++ })
        val seat = InboxItem(id = "library:入馆签到", category = InboxCategories.LIBRARY, source = "图书馆", title = "座位 A01 待入馆签到")
        val homework = InboxItem(id = "lms:4:1", category = InboxCategories.LMS, source = "数学物理方法", title = "作业一")

        runBlocking {
            // 没有管家 ⇒ `refresh` 直接返回（搬迁前那一句 `sessionManager ?: return`），不抛
            local.refresh(account)
            // 空数据、以及「只有别的类别的待办」⇒ 都不补拉
            local.afterRefresh(InboxData())
            local.afterRefresh(InboxData(todos = mapOf(InboxCategories.LMS to listOf(homework))))
            assertEquals(0, requeries, "没有座位待办就不该打扰宿主")
            // 有座位待办 ⇒ 恰好一次
            local.afterRefresh(InboxData(todos = mapOf(InboxCategories.LIBRARY to listOf(seat))))
            assertEquals(1, requeries)
            // 本端没有那一路（桌面端传 null）⇒ 什么都不做，也不抛
            AppInboxSource(sessionManager = null, requeryLibraryBooking = null).afterRefresh(
                InboxData(todos = mapOf(InboxCategories.LIBRARY to listOf(seat))),
            )
        }
    }

    // ══════ ③ 解析口径（从 `:app` 的 `InboxTest` 迁来）══════

    @Test
    fun `解析学校消息：按落款当来源，事务中心的推送跳过`() {
        val json = """{"code":0,"data":[
            {"id":"m1","title":"低电通知","appId":"ycz7pmawmfpksprtio309vow","appName":"消息平台","editTime":"2026-09-27 20:00:12",
             "content":"<p>您当前的电费余额不足，请及时缴费。 (公寓用电管理系统)</p>","mobileUrl":"","url":""},
            {"id":"m2","title":"待办催办","appId":"b125b6f0e46911ebc909e55a42ec966e","appName":"事务中心","editTime":"2026-09-27 20:00:12","content":"x"}
        ]}""".safeParseJsonObject()
        val items = SchoolInbox.parseMessages(json)
        assertEquals(1, items.size)
        assertEquals("school:m1", items[0].id)
        assertEquals("公寓用电管理系统", items[0].source)
        assertEquals(InboxCategories.school("公寓用电管理系统"), items[0].category)
        assertNull(items[0].route)
        assertTrue(items[0].time > 0)
    }

    @Test
    fun `解析事务中心待办：带办理链接`() {
        val json = """{"code":0,"data":{"pageIndex":2,"items":[
            {"nodeName":"成绩单申请","addTime":"2026-09-11 16:20:27","title":"在校本科生电子成绩单申请","custom1":"师生可信电子凭证门户",
             "taskId":"t1","mHandleUrl":"https://dzpz.xjtu.edu.cn/m/handle?id=1","timeOut":"false"}
        ]}}""".safeParseJsonObject()
        val todos = SchoolInbox.parseTodos(json)
        assertEquals(1, todos.size)
        assertEquals("todo:t1", todos[0].id)
        assertEquals("师生可信电子凭证门户", todos[0].source)
        assertEquals("成绩单申请", todos[0].body)
        assertTrue(todos[0].route!!.startsWith("browser?url="))
        assertTrue(!todos[0].route!!.contains(" "))
    }

    /** `data.list` 是 `parseMessages` 认的第二种形状；座位类（落款「图书馆预约系统」）在解析阶段就被丢掉。 */
    @Test
    fun `解析学校消息：data_list 那种形状也认，座位类的消息留不住`() {
        val list = """{"code":0,"data":{"list":[
            {"id":"m9","title":"借阅到期","appName":"消息平台","editTime":"2026-09-27 20:00:12",
             "content":"<p>您借的书即将到期。 (图书馆借阅系统)</p>"}
        ]}}""".safeParseJsonObject()
        val items = SchoolInbox.parseMessages(list)
        assertEquals(listOf("school:m9"), items.map { it.id })
        assertEquals("图书馆借阅系统", items.single().source)

        val seat = """{"code":0,"data":[
            {"id":"s1","title":"预约超时","editTime":"2026-09-27 20:00:12",
             "content":"<p>您的预约已经超时，即将在五分钟后释放。 (图书馆预约系统)</p>"}
        ]}""".safeParseJsonObject()
        assertTrue(SchoolInbox.parseMessages(seat).isEmpty(), "座位那类消息活几分钟，不收")
    }
}
