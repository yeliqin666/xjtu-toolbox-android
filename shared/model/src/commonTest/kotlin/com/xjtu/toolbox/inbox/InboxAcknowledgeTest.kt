package com.xjtu.toolbox.inbox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InboxAcknowledgeTest {
    private val now = 1_800_000_000_000L
    private val hour = 60 * 60 * 1000L

    private fun msg(id: String, title: String, time: Long, category: String) =
        InboxItem(id = id, category = category, source = "公寓用电管理系统", title = title, time = time)

    @Test
    fun `点开气泡：对应的收纳消息标已读，待办标已看，别的不动`() {
        val grade = msg("grade:30", "出了 2 门新成绩", now - hour, InboxCategories.GRADE)
        val other = msg("attendance:x", "考勤异常", now - hour, InboxCategories.ATTENDANCE)
        // 和 OwnInbox.todo(COUPON, "coupon:pending", "加餐券", "有 2 张加餐券没领", "coupon") 同形（那个在 :feature:notice）
        val todo = InboxItem(
            id = "coupon:pending", category = InboxCategories.COUPON, source = "加餐券",
            title = "有 2 张加餐券没领", time = now, route = "coupon",
        )
        val data = InboxData(messages = listOf(grade, other), todos = mapOf(InboxCategories.COUPON to listOf(todo)))

        val byCategory = InboxRules.acknowledge(data, emptyList(), InboxCategories.GRADE, now)
        assertTrue("grade:30" in byCategory.readAt)
        assertFalse("attendance:x" in byCategory.readAt)

        val byId = InboxRules.acknowledge(data, listOf("attendance:x"), null, now)
        assertTrue("attendance:x" in byId.readAt)
        assertFalse("grade:30" in byId.readAt)

        val todos = InboxRules.acknowledge(data, emptyList(), InboxCategories.COUPON, now)
        assertTrue("coupon:pending" in todos.seenTodos)
        // 看过的待办还在列表里，只是不再算红点
        assertEquals(1, InboxRules.todos(todos, now).size)
        assertEquals(0, InboxRules.badge(todos.copy(messages = emptyList()), now))
    }

    @Test
    fun `已读的不重新记时刻`() {
        val data = InboxData(messages = listOf(msg("a", "x", now - hour, InboxCategories.GRADE)), readAt = mapOf("a" to 5L))
        assertEquals(5L, InboxRules.acknowledge(data, listOf("a"), InboxCategories.GRADE, now).readAt["a"])
    }
}