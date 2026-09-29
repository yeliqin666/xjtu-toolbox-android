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
            read = setOf("a"),
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
        val data = InboxData(messages = listOf(msg("old", "x", now - InboxRules.KEEP_MS - 1), msg("a", "x", now - hour)), read = setOf("old", "a"))
        val merged = InboxRules.merge(data, listOf(msg("a", "x", now - hour), msg("b", "y", now)), now)
        assertEquals(setOf("a", "b"), merged.messages.map { it.id }.toSet())
        assertEquals(setOf("a"), merged.read)
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
