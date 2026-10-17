package com.xjtu.toolbox.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SeatComfortTest {

    /** 两排桌子中间一条过道：第 0、1 行贴着，第 2、3 行贴着，1、2 行之间空出 2 个座位宽。每行 4 座。 */
    private fun hall(occupied: Set<String> = emptySet()): List<PlanSeat> {
        val rowsY = listOf(0f, 10f, 40f, 50f)
        return rowsY.flatMapIndexed { r, y ->
            (0 until 4).map { c ->
                val id = "$r-$c"
                PlanSeat(id, left = c * 10f, top = y, width = 10f, height = 10f,
                    status = if (id in occupied) PlanSeat.INSIDE else PlanSeat.FREE)
            }
        }
    }

    @Test
    fun `外沿的角落座位靠边，挨着过道的算临过道`() {
        val c = SeatComfort.of(hall())
        assertEquals(2, c.getValue("0-0").wallSides)          // 左上角：左、上都没座位
        assertEquals(0, c.getValue("0-0").aisleSides)
        assertEquals(1, c.getValue("1-1").aisleSides)         // 第 1 行朝下隔着过道
        assertEquals(0, c.getValue("1-1").wallSides)
    }

    @Test
    fun `空场时角落比过道边好，旁边坐了人分数就掉`() {
        val empty = SeatComfort.of(hall())
        assertTrue(empty.getValue("0-0").score > empty.getValue("1-1").score)

        val busy = SeatComfort.of(hall(occupied = setOf("0-1", "1-0", "1-1")))
        assertTrue(busy.getValue("0-0").score < empty.getValue("0-0").score)
        // 人都挤在左上，右下角那个空座成了首选
        assertEquals("3-3", SeatComfort.best(hall(occupied = setOf("0-1", "1-0", "1-1")), busy, 1).single().seatId)
    }

    @Test
    fun `说明文字`() {
        val c = SeatComfort.of(hall())
        assertEquals("角落、周围没人", c.getValue("0-0").label)
    }
}
