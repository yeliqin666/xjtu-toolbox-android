package com.xjtu.toolbox.agent

import com.xjtu.toolbox.util.campusEpochMsOf
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentSessionGroupsTest {
    private fun at(day: Int, hour: Int = 12) = campusEpochMsOf(LocalDateTime(2026, 10, day, hour, 0))
    private val now = at(20, 9)

    private fun session(id: String, title: String, updatedAt: Long) = AgentSession(id = id, title = title, updatedAt = updatedAt)

    @Test
    fun groupBoundariesFollowCalendarDays() {
        assertEquals(SessionGroup.TODAY, sessionGroupOf(at(20, 0), now))
        assertEquals(SessionGroup.TODAY, sessionGroupOf(at(21), now))
        assertEquals(SessionGroup.YESTERDAY, sessionGroupOf(at(19, 23), now))
        assertEquals(SessionGroup.WEEK, sessionGroupOf(at(18), now))
        assertEquals(SessionGroup.WEEK, sessionGroupOf(at(14), now))
        assertEquals(SessionGroup.EARLIER, sessionGroupOf(at(13), now))
    }

    @Test
    fun pinnedPushSessionIsSeparatedAndEmptyGroupsDropped() {
        val list = listOf(
            session("a", "课表", at(20)),
            session(AgentPushFocus.SESSION_ID, AgentPushFocus.SESSION_TITLE, at(20)),
            session("b", "食堂", at(1)),
        )
        val model = buildSessionList(list, "", now)
        assertEquals(AgentPushFocus.SESSION_ID, model.pinned?.id)
        assertEquals(listOf(SessionGroup.TODAY, SessionGroup.EARLIER), model.sections.map { it.group })
        assertEquals(listOf("a"), model.sections[0].sessions.map { it.id })
    }

    @Test
    fun queryFiltersByTitleIgnoringCase() {
        val list = listOf(session("a", "Kotlin 笔记", at(20)), session("b", "食堂", at(20)))
        assertEquals(listOf("a"), buildSessionList(list, " kotlin ", now).sections.single().sessions.map { it.id })
        assertNull(buildSessionList(list, "推送", now).pinned)
        assertEquals(true, buildSessionList(list, "没有", now).isEmpty)
    }
}
