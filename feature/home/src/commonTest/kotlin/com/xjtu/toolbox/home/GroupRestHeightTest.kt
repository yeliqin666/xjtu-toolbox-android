package com.xjtu.toolbox.home

import androidx.compose.ui.unit.Density
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals

class GroupRestHeightTest {
    private val density = Density(3f)
    private val board = 1000

    private fun tile(y: Float, h: Float) = HomeTile(id = "t$y", routeId = "r", x = 0f, y = y, w = 1f, h = h)

    @Test
    fun 网格高按行数乘边长减一道缝() {
        val group = HomeGroup(id = "g", tiles = listOf(tile(0f, 1f), tile(1f, 2f)))
        val gap = 8f * 3f
        val rowUnit = (board + gap) / 4
        assertEquals((3 * rowUnit - gap).roundToInt(), density.groupRestHeightPx(group, editing = false, boardWidthPx = board))
    }

    @Test
    fun 有组名或编辑态才算组头() {
        val bare = HomeGroup(id = "g", tiles = listOf(tile(0f, 1f)))
        val titled = bare.copy(title = "常用")
        val plain = density.groupRestHeightPx(bare, editing = false, boardWidthPx = board)
        val header = 34 * 3 + 8 * 3
        assertEquals(plain + header, density.groupRestHeightPx(titled, editing = false, boardWidthPx = board))
        assertEquals(plain + header, density.groupRestHeightPx(bare, editing = true, boardWidthPx = board))
    }

    @Test
    fun 空组按一行占位() {
        val empty = HomeGroup(id = "g")
        val one = HomeGroup(id = "g", tiles = listOf(tile(0f, 1f)))
        assertEquals(
            density.groupRestHeightPx(one, editing = false, boardWidthPx = board),
            density.groupRestHeightPx(empty, editing = false, boardWidthPx = board),
        )
    }
}
