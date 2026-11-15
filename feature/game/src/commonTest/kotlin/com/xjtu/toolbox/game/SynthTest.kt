package com.xjtu.toolbox.game

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class SynthTest {
    @Test
    fun everySfxIsCleanAndClickFree() {
        for (sfx in Sfx.entries) {
            val x = Synth.make(sfx)
            assertTrue(x.size > 400, "$sfx 太短")
            assertTrue(x.all { it.isFinite() }, "$sfx 有非有限样本")
            val peak = x.maxOf { abs(it) }
            assertTrue(peak in 0.3f..0.95f, "$sfx 峰值 $peak")
            // 首尾不为零就会「啪」一声
            assertTrue(abs(x.first()) < 0.01f && abs(x.last()) < 0.01f, "$sfx 首尾未归零")
        }
    }
}
