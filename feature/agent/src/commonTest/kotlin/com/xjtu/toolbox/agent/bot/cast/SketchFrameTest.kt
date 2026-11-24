package com.xjtu.toolbox.agent.bot.cast

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SketchFrameTest {

    private fun sketch(x: Double, r: Double = 10.0, alpha: Double = 1.0) = Sketch().apply {
        reset()
        dot(x, 0.0, r, alpha = alpha)
    }

    @Test
    fun `没记过任何一帧时总要画`() {
        assertTrue(SketchFrame().differs(sketch(0.0), tolerance = 1f))
    }

    @Test
    fun `起伏在容差之内不重画，超出就重画`() {
        val frame = SketchFrame().apply { capture(sketch(0.0)) }
        assertFalse(frame.differs(sketch(0.0), tolerance = 1f))
        assertFalse(frame.differs(sketch(0.4), tolerance = 1f))
        assertTrue(frame.differs(sketch(2.0), tolerance = 1f))
    }

    @Test
    fun `透明度或笔画结构变了就重画`() {
        val frame = SketchFrame().apply { capture(sketch(0.0)) }
        assertTrue(frame.differs(sketch(0.0, alpha = 0.5), tolerance = 1f))
        val two = Sketch().apply {
            reset()
            dot(0.0, 0.0, 10.0)
            dot(30.0, 0.0, 4.0)
        }
        assertTrue(frame.differs(two, tolerance = 1f))
    }

    @Test
    fun `比的是上次画出的那帧：缓慢漂移累计超出容差时会画`() {
        val frame = SketchFrame().apply { capture(sketch(0.0)) }
        // 每步都只挪 0.6，但和「上次画出的」比，第二步已经差出 1.2
        assertFalse(frame.differs(sketch(0.6), tolerance = 1f))
        assertTrue(frame.differs(sketch(1.2), tolerance = 1f))
    }
}
