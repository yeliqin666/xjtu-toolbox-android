package com.xjtu.toolbox.inbox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InboxSwipeTest {
    private val now = 1_800_000_000_000L

    private fun msg(id: String) = InboxItem(id = id, category = InboxCategories.NOTICE, source = "教务", title = id, time = now - 1000)

    @Test
    fun defaultsFollowIssue() {
        val s = InboxSwipeSettings()
        assertEquals(InboxSwipeAction.COMPLETE, s.actionOf(InboxSwipeKind.SELF, left = true))
        assertEquals(InboxSwipeAction.DELETE, s.actionOf(InboxSwipeKind.SELF, left = false))
        assertEquals(InboxSwipeAction.IGNORE, s.actionOf(InboxSwipeKind.TODO, left = true))
        assertEquals(InboxSwipeAction.IGNORE, s.actionOf(InboxSwipeKind.TODO, left = false))
        assertEquals(InboxSwipeAction.CLEAR, s.actionOf(InboxSwipeKind.MESSAGE, left = true))
        assertEquals(InboxSwipeAction.CLEAR, s.actionOf(InboxSwipeKind.MESSAGE, left = false))
    }

    @Test
    fun actionOutsideKindChoicesFallsBackToNone() {
        val s = InboxSwipeSettings(todoLeft = InboxSwipeAction.DELETE.key, messageRight = "bogus")
        assertEquals(InboxSwipeAction.NONE, s.actionOf(InboxSwipeKind.TODO, left = true))
        assertEquals(InboxSwipeAction.NONE, s.actionOf(InboxSwipeKind.MESSAGE, left = false))
        val changed = s.with(InboxSwipeKind.MESSAGE, left = false, InboxSwipeAction.READ)
        assertEquals(InboxSwipeAction.READ, changed.actionOf(InboxSwipeKind.MESSAGE, left = false))
    }

    @Test
    fun clearHidesAndMarksReadThenUnclearRestores() {
        val base = InboxData(messages = listOf(msg("a"), msg("b")), readAt = mapOf("b" to now - 500))
        val cleared = InboxRules.clear(base, listOf("a", "b"), now)
        assertTrue(InboxRules.groups(cleared, now).isEmpty())
        assertEquals(now, cleared.readAt["a"])
        assertEquals(now - 500, cleared.readAt["b"])

        val restored = InboxRules.unclear(cleared, listOf("a", "b"), readBefore = mapOf("b" to now - 500))
        assertEquals(setOf("a", "b"), InboxRules.groups(restored, now).flatMap { it.ids }.toSet())
        assertFalse("a" in restored.readAt)
        assertEquals(now - 500, restored.readAt["b"])
    }

    @Test
    fun unignoreBringsTodoBack() {
        val todo = InboxItem(id = "hw", category = InboxCategories.LMS, source = "思源学堂", title = "作业", time = now, expiresAt = now + 86_400_000L)
        val base = InboxData(todos = mapOf(InboxCategories.LMS to listOf(todo)))
        val ignored = InboxRules.ignore(base, "hw", now)
        assertTrue(InboxRules.todos(ignored, now).isEmpty())
        val back = InboxRules.unignore(ignored, "hw")
        assertEquals(listOf("hw"), InboxRules.todos(back, now).map { it.id })
        assertTrue(InboxRules.finished(back, now).isEmpty())
    }
}
