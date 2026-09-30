package com.xjtu.toolbox.inbox

import com.xjtu.toolbox.util.safeParseJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxTest {

    private val now = 1_800_000_000_000L
    private val hour = 60 * 60 * 1000L

    private fun msg(id: String, title: String, time: Long, category: String = InboxCategories.school("公寓用电管理系统")) =
        InboxItem(id = id, category = category, source = "公寓用电管理系统", title = title, time = time)

    @Test
    fun `同类只留最新一条，未读看最新那条`() {
        val data = InboxData(
            messages = listOf(msg("a", "低电通知", now - 3 * hour), msg("b", "低电通知", now - hour), msg("c", "图书馆预约系统", now - 2 * hour)),
            readAt = mapOf("a" to now - hour),
        )
        val groups = InboxRules.groups(data, now)
        assertEquals(listOf("b", "c"), groups.map { it.latest.id })
        assertEquals(2, groups[0].count)
        assertTrue(groups[0].unread)
        assertEquals(2 + 0, InboxRules.badge(data, now))
    }

    @Test
    fun `关掉的分类不显示也不计数`() {
        val todo = OwnInbox.todo(InboxCategories.COUPON, "coupon:pending", "加餐券", "有 2 张加餐券没领", "coupon")
        val data = InboxData(
            messages = listOf(msg("a", "低电通知", now - hour)),
            todos = mapOf(InboxCategories.COUPON to listOf(todo)),
            off = setOf(InboxCategories.school("公寓用电管理系统"), InboxCategories.COUPON),
        )
        assertTrue(InboxRules.groups(data, now).isEmpty())
        assertEquals(0, InboxRules.badge(data, now))
    }

    @Test
    fun `过了截止的待办和保留期外的消息都不显示`() {
        val due = OwnInbox.todo(InboxCategories.LMS, "lms:1:1", "高数", "作业一", null, expiresAt = now - 1)
        val data = InboxData(
            messages = listOf(msg("old", "低电通知", now - InboxRules.KEEP_MS - 1)),
            todos = mapOf(InboxCategories.LMS to listOf(due)),
        )
        assertTrue(InboxRules.groups(data, now).isEmpty())
        assertTrue(InboxRules.todos(data, now).isEmpty())
    }

    @Test
    fun `合并按 id 去重，清掉保留期外的消息和对应已读`() {
        val data = InboxData(
            messages = listOf(msg("old", "x", now - InboxRules.KEEP_MS - 1), msg("a", "x", now - hour)),
            readAt = mapOf("old" to now, "a" to now),
        )
        val merged = InboxRules.merge(data, listOf(msg("a", "x", now - hour), msg("b", "y", now)), now)
        assertEquals(setOf("a", "b"), merged.messages.map { it.id }.toSet())
        assertEquals(setOf("a"), merged.readAt.keys)
    }

    @Test
    fun `重复推来的消息保留首次时间，已读不丢`() {
        val data = InboxData(messages = listOf(msg("a", "x", now - hour)), readAt = mapOf("a" to now))
        val merged = InboxRules.merge(data, listOf(msg("a", "x 改了", now)), now)
        assertEquals(now - hour, merged.messages.single().time)
        assertEquals("x 改了", merged.messages.single().title)
        assertEquals(setOf("a"), merged.readAt.keys)
    }

    @Test
    fun `读过的消息灰着留 7 天，老数据的已读集合按现在补上时刻`() {
        val day = 24 * hour
        val data = InboxData(
            messages = listOf(msg("a", "低电通知", now - 2 * day), msg("b", "图书馆借阅系统", now - 10 * day)),
            readAt = mapOf("a" to now - day, "b" to now - 8 * day),
        )
        val groups = InboxRules.groups(data, now)
        assertEquals(listOf("a"), groups.map { it.latest.id })
        assertFalse(groups.single().unread)
        assertEquals(0, InboxRules.badge(data, now))

        val migrated = InboxRules.migrate(InboxData(read = setOf("x")), now)
        assertEquals(mapOf("x" to now), migrated.readAt)
        assertTrue(migrated.read.isEmpty())
    }

    private fun todo(id: String, expiresAt: Long = 0L) =
        OwnInbox.todo(InboxCategories.SCHOOL_TODO, id, "事务中心", "待办 $id", null, expiresAt).copy(time = now - hour)

    @Test
    fun `这次刷新没了、又没到截止的待办算办完，挪进已完成`() {
        val data = InboxData(todos = mapOf(InboxCategories.SCHOOL_TODO to listOf(todo("a"), todo("b"), todo("c", expiresAt = now - 1))))
        val next = InboxRules.replaceTodos(data, InboxCategories.SCHOOL_TODO, listOf(todo("b")), now)
        assertEquals(listOf("b"), InboxRules.todos(next, now).map { it.id })
        // 过期的 c 不算办完
        assertEquals(listOf("a"), InboxRules.finished(next, now).map { it.item.id })
        // 7 天后不再显示
        assertTrue(InboxRules.finished(next, now + InboxRules.FINISHED_KEEP_MS).isEmpty())
    }

    @Test
    fun `办完的又冒出来就从已完成拿掉`() {
        val data = InboxRules.replaceTodos(
            InboxData(todos = mapOf(InboxCategories.SCHOOL_TODO to listOf(todo("a")))), InboxCategories.SCHOOL_TODO, emptyList(), now,
        )
        val back = InboxRules.replaceTodos(data, InboxCategories.SCHOOL_TODO, listOf(todo("a")), now + hour)
        assertTrue(InboxRules.finished(back, now + hour).isEmpty())
        assertEquals(listOf("a"), InboxRules.todos(back, now + hour).map { it.id })
    }

    @Test
    fun `首页红点只算没看过的待办，看过的留在列表里`() {
        val data = InboxData(todos = mapOf(InboxCategories.SCHOOL_TODO to listOf(todo("a"), todo("b"))), seenTodos = setOf("a"))
        assertEquals(1, InboxRules.badge(data, now))
        assertEquals(2, InboxRules.todos(data, now).size)
    }

    @Test
    fun `忽略的待办进已完成，来源还报着也不再进列表和红点`() {
        val data = InboxData(todos = mapOf(InboxCategories.SCHOOL_TODO to listOf(todo("a"), todo("b"))))
        val ignored = InboxRules.ignore(data, "a", now)
        val refreshed = InboxRules.replaceTodos(ignored, InboxCategories.SCHOOL_TODO, listOf(todo("a"), todo("b")), now + hour)
        assertEquals(listOf("b"), InboxRules.todos(refreshed, now + hour).map { it.id })
        assertEquals(1, InboxRules.badge(refreshed, now + hour))
        val finished = InboxRules.finished(refreshed, now + hour).single()
        assertEquals("a", finished.item.id)
        assertTrue(finished.ignored)
    }

    @Test
    fun `图书馆座位消息不收，已存的合并时清掉，借阅提醒照收`() {
        val seat = InboxItem(id = "s", category = InboxCategories.school("图书馆预约系统"), source = "图书馆预约系统", title = "预约超时", time = now - hour)
        val borrow = InboxItem(id = "b", category = InboxCategories.school("图书馆借阅系统"), source = "图书馆借阅系统", title = "图书即将到期", time = now - hour)
        val merged = InboxRules.merge(InboxData(messages = listOf(seat, borrow)), emptyList(), now)
        assertEquals(listOf("b"), merged.messages.map { it.id })

        val json = """{"data":[{"id":"m1","title":"图书馆预约系统","editTime":"2026-09-27 20:00:12",
            "content":"<p>您的预约已经超时，即将在五分钟后释放。 (图书馆预约系统)</p>"}]}""".safeParseJsonObject()
        assertTrue(SchoolInbox.parseMessages(json).isEmpty())
    }

    @Test
    fun `图书馆座位待办：座位号加要做的事，点开去图书馆页`() {
        val b = com.xjtu.toolbox.library.MyBookingInfo("A123", "三楼北区", "待入馆", mapOf("入馆签到" to "u"))
        assertEquals("入馆签到", com.xjtu.toolbox.library.LibraryStatus.urgentAction(b))
        val item = OwnInbox.library("入馆签到", b)
        assertEquals("座位 A123 待入馆签到", item.title)
        assertEquals("三楼北区 · 待入馆", item.body)
        assertEquals(InboxCategories.LIBRARY, item.category)
        assertEquals(com.xjtu.toolbox.nav.AppRoute.Library.id, item.route)
        assertNull(com.xjtu.toolbox.library.LibraryStatus.urgentAction(b.copy(actionUrls = mapOf("释放" to "u"))))
    }

    @Test
    fun `公告时间取 id 开头的发布日期`() {
        val b = com.xjtu.toolbox.bulletin.Bulletin(
            id = "2026-09-24-tips", level = com.xjtu.toolbox.bulletin.BulletinLevel.INFO,
            title = "社区上线了", body = "", mustAck = false, block = false,
        )
        val item = OwnInbox.bulletin(b)
        assertEquals("bulletin:2026-09-24-tips", item.id)
        assertEquals(InboxCategories.BULLETIN, item.category)
        assertEquals(java.time.LocalDate.of(2026, 9, 24), java.time.Instant.ofEpochMilli(item.time).atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate())
        assertNull(item.route)
    }

    @Test
    fun `落款取正文末尾括号，HTML 转纯文本`() {
        val body = InboxRules.plainText("<p>您的预约已经超时，即将在五分钟后释放。 (图书馆预约系统)</p>")
        assertEquals("图书馆预约系统", InboxRules.signature(body))
        assertNull(InboxRules.signature("没有落款的正文"))
    }

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
        assertFalse(todos[0].route!!.contains(" "))
    }
}
