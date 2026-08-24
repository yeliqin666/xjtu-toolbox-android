package com.xjtu.toolbox.schedule

import com.xjtu.toolbox.util.XjtuTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduleTermStoreTest {

    @Test
    fun officialName_prefersLiveMcOverLocalMapping() {
        val code = "2025-2026-4"
        assertEquals(
            "2025-2026学年暑期",
            ScheduleTermStore.officialName(
                code,
                live = mapOf(code to "2025-2026学年暑期"),
                disk = mapOf(code to "磁盘名"),
            ),
        )
        assertEquals(
            "磁盘名",
            ScheduleTermStore.officialName(code, live = emptyMap(), disk = mapOf(code to "磁盘名")),
        )
        assertNull(ScheduleTermStore.officialName(code, emptyMap(), emptyMap()))
    }

    @Test
    fun display_fallsBackToLocalOnlyWhenMcMissing() {
        val code = "2025-2026-3"
        assertEquals(
            XjtuTime.displayTerm(code),
            ScheduleTermStore.display(code, emptyMap(), emptyMap()),
        )
        assertEquals(
            "教务短学期名",
            ScheduleTermStore.display(code, mapOf(code to "教务短学期名"), emptyMap()),
        )
    }

    @Test
    fun usableName_ignoresBlankOrCodeItself() {
        assertNull(ScheduleTermStore.usableName("2025-2026-1", "  "))
        assertNull(ScheduleTermStore.usableName("2025-2026-1", "2025-2026-1"))
        assertEquals("秋季", ScheduleTermStore.usableName("2025-2026-1", " 秋季 "))
    }
}
