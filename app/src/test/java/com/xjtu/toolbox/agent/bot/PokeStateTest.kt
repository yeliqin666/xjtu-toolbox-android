package com.xjtu.toolbox.agent.bot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PokeStateTest {

    @Test
    fun `末帧就是静息脸，回 idle 不跳`() {
        val end = STATE_POKE.pose(STATE_POKE.duration)
        val rest = EXPRESSION_ATTENTIF
        assertEquals(rest.gaze.yaw, end.gaze.yaw, 1e-9)
        assertEquals(rest.gaze.pitch, end.gaze.pitch, 1e-9)
        assertEquals(rest.gaze.roll, end.gaze.roll, 1e-9)
        assertEquals(rest.split, end.split, 1e-9)
        end.eyes.forEach {
            assertEquals(rest.eyes[0].w, it.w, 1e-9)
            assertEquals(rest.eyes[0].h, it.h, 1e-9)
        }
        assertEquals(1.0, end.sil.sy, 0.005)
        assertEquals(1.0, end.sil.sx, 0.005)
    }

    @Test
    fun `先按扁再回弹，过冲有限`() {
        val pressed = STATE_POKE.pose(0.07)
        assertTrue(pressed.sil.sy < 0.9 && pressed.sil.sx > 1.0)
        // 眯眼：横杠，宽大于高
        assertTrue(pressed.eyes[0].w > pressed.eyes[0].h)
        val tallest = (0..70).maxOf { STATE_POKE.pose(it / 100.0).sil.sy }
        assertTrue("过冲 $tallest", tallest in 1.0..1.06)
    }
}
