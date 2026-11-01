package com.xjtu.toolbox.home

import kotlin.test.Test
import kotlin.test.assertEquals

/** 首页小组件的落盘与排版（[HomeWidgetLayout]）。 */
class HomeWidgetLayoutTest {

    private fun spec(id: String, size: WidgetSize) = HomeWidgetSpec(id, size)

    @Test
    fun `落盘往返，丢掉认不出的行、下架的功能和重复项`() {
        val lines = HomeWidgetLayout.encode(listOf(spec("a", WidgetSize.LARGE), spec("b", WidgetSize.TALL))) +
            listOf("c:9x9", "gone:1x1", "a:1x1", "")
        assertEquals(
            listOf(spec("a", WidgetSize.LARGE), spec("b", WidgetSize.TALL)),
            HomeWidgetLayout.decode(lines, available = setOf("a", "b", "c")),
        )
    }

    @Test
    fun `大格占满两列两行，后面的小格回填右边的空位`() {
        val placed = HomeWidgetLayout.pack(
            listOf(
                spec("big", WidgetSize.LARGE),
                spec("wide", WidgetSize.WIDE),
                spec("s1", WidgetSize.SMALL),
                spec("s2", WidgetSize.SMALL),
            ),
        )
        assertEquals(listOf(0 to 0, 2 to 0, 2 to 1, 3 to 1), placed.map { it.col to it.row })
        assertEquals(2, HomeWidgetLayout.rowCount(placed))
    }

    @Test
    fun `竖条放不下时换到下一行，不和别的格子重叠`() {
        val placed = HomeWidgetLayout.pack(
            listOf(
                spec("w1", WidgetSize.WIDE),
                spec("w2", WidgetSize.WIDE),
                spec("tall", WidgetSize.TALL),
                spec("s", WidgetSize.SMALL),
            ),
        )
        assertEquals(listOf(0 to 0, 2 to 0, 0 to 1, 1 to 1), placed.map { it.col to it.row })
        assertEquals(3, HomeWidgetLayout.rowCount(placed))
    }

    @Test
    fun `尺寸按小、横条、竖条、大轮换`() {
        assertEquals(WidgetSize.WIDE, WidgetSize.SMALL.next())
        assertEquals(WidgetSize.SMALL, WidgetSize.LARGE.next())
    }
}
