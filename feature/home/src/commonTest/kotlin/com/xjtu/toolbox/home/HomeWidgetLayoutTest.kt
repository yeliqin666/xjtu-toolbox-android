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
    fun `分组落盘往返，标题里的制表符和换行不会破坏格式`() {
        val groups = listOf(
            HomeWidgetGroup("quick", "常用\t功能", "", listOf(spec("a", WidgetSize.SMALL))),
            HomeWidgetGroup("custom-1", "", "#FF8800", listOf(spec("b", WidgetSize.WIDE), spec("a", WidgetSize.LARGE))),
            HomeWidgetGroup("empty", "空组"),
        )
        assertEquals(
            listOf(
                groups[0].copy(title = "常用 功能"),
                groups[1],
                groups[2],
            ),
            HomeWidgetLayout.decodeGroups(HomeWidgetLayout.encodeGroups(groups), available = setOf("a", "b")),
        )
    }

    @Test
    fun `没有组标题行的老存档整串当成一个组`() {
        assertEquals(
            listOf(HomeWidgetGroup("main", widgets = listOf(spec("a", WidgetSize.WIDE)))),
            HomeWidgetLayout.decodeGroups(listOf("a:2x1", "gone:1x1"), available = setOf("a")),
        )
    }

    @Test
    fun `组内换位与跨组挪动，目标组已有同一功能时不动`() {
        val groups = listOf(
            HomeWidgetGroup("g1", widgets = listOf(spec("a", WidgetSize.SMALL), spec("b", WidgetSize.SMALL), spec("c", WidgetSize.SMALL))),
            HomeWidgetGroup("g2", widgets = listOf(spec("b", WidgetSize.WIDE))),
        )
        assertEquals(
            listOf("c", "a", "b"),
            HomeWidgetLayout.move(groups, "g1", "c", "g1", 0)[0].widgets.map { it.routeId },
        )
        val moved = HomeWidgetLayout.move(groups, "g1", "a", "g2", 1)
        assertEquals(listOf("b", "c"), moved[0].widgets.map { it.routeId })
        assertEquals(listOf("b", "a"), moved[1].widgets.map { it.routeId })
        assertEquals(groups, HomeWidgetLayout.move(groups, "g1", "b", "g2", 0))
    }

    @Test
    fun `横向和纵向独立吸附，可以一次拖成任意尺寸`() {
        assertEquals(WidgetSize.SMALL, WidgetSize.fromExtent(1.49f, 1.49f))
        assertEquals(WidgetSize.WIDE, WidgetSize.fromExtent(1.5f, 1f))
        assertEquals(WidgetSize.TALL, WidgetSize.fromExtent(1f, 1.5f))
        assertEquals(WidgetSize.LARGE, WidgetSize.fromExtent(2f, 2f))
        assertEquals(WidgetSize.SMALL, WidgetSize.fromExtent(-1f, 0f))
    }
}
