package com.xjtu.toolbox.inbox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 收纳里的「重要程度」：用户对某条待办的标注，只决定角标与通知措辞，不参与排序。 */
class InboxImportanceTest {

    private val now = 1_000L
    private val day = 24 * 60 * 60 * 1000L

    private fun item(id: String, expiresAt: Long = 0L) = InboxItem(
        id = id,
        category = InboxCategories.LMS,
        source = "高数",
        title = "作业",
        time = now,
        expiresAt = expiresAt,
        sticky = expiresAt > 0,
    )

    @Test
    fun `没标过是普通，标了能读出来，选普通等于摘掉`() {
        val data = InboxData(todos = mapOf(InboxCategories.LMS to listOf(item("a"))))
        assertEquals(InboxRules.IMPORTANCE_NONE, InboxRules.importanceOf(data, "a"))
        assertNull(InboxRules.importanceLabel(InboxRules.IMPORTANCE_NONE), "普通不画角标，所以没有字面")

        val high = InboxRules.setImportance(data, "a", InboxRules.IMPORTANCE_HIGH)
        assertEquals(InboxRules.IMPORTANCE_HIGH, InboxRules.importanceOf(high, "a"))
        assertEquals("紧急", InboxRules.importanceLabel(InboxRules.IMPORTANCE_HIGH))

        val cleared = InboxRules.setImportance(high, "a", InboxRules.IMPORTANCE_NONE)
        assertEquals(InboxRules.IMPORTANCE_NONE, InboxRules.importanceOf(cleared, "a"))
        assertTrue(cleared.importance.isEmpty(), "摘掉就应该从表里消失，别留一条 0")
    }

    @Test
    fun `标了紧急也不插队，顺序仍按截止时间`() {
        val hour = 3_600_000L
        val urgentButLater = item("later", expiresAt = now + 3 * hour)
        val normalButSooner = item("sooner", expiresAt = now + 1 * hour)
        val data = InboxRules.setImportance(
            InboxData(todos = mapOf(InboxCategories.LMS to listOf(urgentButLater, normalButSooner))),
            "later",
            InboxRules.IMPORTANCE_HIGH,
        )
        assertEquals(listOf("sooner", "later"), InboxRules.todos(data, now).map { it.id })
    }

    @Test
    fun `办完挪进已完成的还留着标注，彻底消失才丢`() {
        val before = InboxData(todos = mapOf(InboxCategories.SELF to listOf(item("self:1", expiresAt = now + 1_000L))))
        val marked = InboxRules.setImportance(before, "self:1", InboxRules.IMPORTANCE_MEDIUM)
        // 来源不再报这一条 ⇒ 挪进「已完成」；已完成区照样画角标，所以标注要跟着
        val finished = InboxRules.replaceTodos(marked, InboxCategories.SELF, emptyList(), now)
        assertEquals(listOf("self:1"), finished.finished.map { it.item.id })
        assertEquals(InboxRules.IMPORTANCE_MEDIUM, InboxRules.importanceOf(finished, "self:1"))

        // 8 天后连「已完成」都过了保留期，标注一起清掉（表不会无限涨）
        val gone = InboxRules.replaceTodos(marked, InboxCategories.SELF, emptyList(), now + 8 * day)
        assertTrue(gone.finished.isEmpty())
        assertEquals(InboxRules.IMPORTANCE_NONE, InboxRules.importanceOf(gone, "self:1"))
    }
}
