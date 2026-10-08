package com.xjtu.toolbox.agent.bot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 经典屁岱的云朵轮廓与颜色目录。 */
class BotSkinsTest {

    @Test
    fun `云朵是 64 个采样且峰值归一到约 1`() {
        assertEquals(PROFILE_SAMPLES, CLOUD_RADII.size)
        val peak = CLOUD_RADII.max()
        assertTrue("峰值 $peak", peak in 0.95..1.05)
        assertTrue(CLOUD_RADII.all { it > 0.0 && it.isFinite() })
    }

    @Test
    fun `默认颜色在目录里`() {
        assertNotNull(botColorById(DEFAULT_COLOR_ID))
        // 跟随主题必须存在，否则深色模式下默认形象会不可见
        assertEquals(null, botColorById(BOT_COLOR_AUTO)?.argb)
    }

    @Test
    fun `未知 id 一律返回 null 而不是崩溃`() {
        assertEquals(null, botColorById("不存在"))
        assertEquals(null, botColorById(null))
    }
}
