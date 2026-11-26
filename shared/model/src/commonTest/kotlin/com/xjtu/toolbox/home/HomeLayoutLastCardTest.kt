package com.xjtu.toolbox.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeLayoutLastCardTest {
    private fun t(id: String, route: String, x: Float = 0f, y: Float = 0f) = HomeTile(id, route, x, y, 2f, 1f)

    private val layout = HomeLayout(
        listOf(
            HomeGroup("quick", tiles = listOf(t("q1", "schedule"))),
            HomeGroup("class", tiles = listOf(t("c1", "schedule"), t("c2", "grades", 2f), t("c3", "grades", 0f, 1f), t("c4", "exam", 2f, 1f))),
        ),
    )

    @Test
    fun `只有在整页只剩一块时才算最后一块`() {
        assertFalse(layout.isLastOf("q1"))
        assertFalse(layout.isLastOf("c2"))
        assertTrue(layout.isLastOf("c4"))
    }

    @Test
    fun `删组时只在这组有卡的功能各留一块挪进别的组`() {
        assertEquals(listOf("grades", "exam"), layout.lastTilesIn("class").map { it.routeId })
        val after = layout.removeGroupKeepingLast("class", "quick")
        assertEquals(null, after.group("class"))
        assertEquals(setOf("schedule", "grades", "exam"), after.group("quick")!!.tiles.mapTo(mutableSetOf()) { it.routeId })
        assertTrue(after.isConsistent())
    }
}
