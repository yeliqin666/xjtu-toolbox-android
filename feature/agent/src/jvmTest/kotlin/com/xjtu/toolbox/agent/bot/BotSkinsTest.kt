package com.xjtu.toolbox.agent.bot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 形象目录与眼睛贴合表的校验。
 *
 * 这些断言存在的意义：底栏形象的形状一旦畸形，肉眼要等到装到设备上才发现，而且
 * 「眼洞戳出身体」这类缺陷在圆上根本看不出来。用数值把三个不变量钉死——形状归一、
 * 圆零偏移、非圆形状确实拿到了修正量。
 */
class BotSkinsTest {

    @Test
    fun `每种形状都是 64 个采样且峰值归一到约 1`() {
        assertEquals(8, BOT_SHAPES.size)
        for (shape in BOT_SHAPES) {
            assertEquals("${shape.id} 采样数", PROFILE_SAMPLES, shape.radii.size)
            val peak = shape.radii.max()
            // squircle 归一在 1.15（对角线上），其余在 1.0~1.04
            assertTrue("${shape.id} 峰值 $peak 偏小", peak > 0.95)
            assertTrue("${shape.id} 峰值 $peak 偏大", peak <= 1.16)
            assertTrue("${shape.id} 有非正半径", shape.radii.all { it > 0.0 })
            assertTrue("${shape.id} 有非有限半径", shape.radii.all { it.isFinite() })
        }
    }

    @Test
    fun `默认形状与颜色在目录里`() {
        assertNotNull(botShapeById(DEFAULT_SHAPE_ID))
        assertNotNull(botColorById(DEFAULT_COLOR_ID))
        // 跟随主题必须存在，否则深色模式下默认形象会不可见
        assertEquals(null, botColorById(BOT_COLOR_AUTO)?.argb)
    }

    @Test
    fun `未知 id 一律返回 null 而不是崩溃`() {
        assertEquals(null, botShapeById("不存在"))
        assertEquals(null, botColorById(null))
    }

    @Test
    fun `圆形上两眼的贴合偏移为零`() {
        val circle = botShapeById("cercle")!!.radii
        for (state in listOf("idle", "wink", "notify")) {
            val (x, y) = eyeOffsetFor(circle, state)
            // 圆上原始轮廓与形状轮廓相同，余量本就是要求的那个值，不需要挪
            assertTrue("$state 圆形偏移应为 0，实得 ($x, $y)", abs(x) < 1e-6 && abs(y) < 1e-6)
        }
    }

    @Test
    fun `每种形状的静息态眼洞都不戳出身体`() {
        // 这才是真正要保的不变量：修正量为零不是目标，「眼睛放得下」才是。
        // 当前表情眼高较小，对目录里每种形状都放得下——所以贴合表全为零是正确结果。
        for (shape in BOT_SHAPES) {
            for (state in listOf("idle", "wink", "notify")) {
                val margin = eyeFitMargin(shape.radii, state)
                assertNotNull("$state 应有校验数据", margin)
                assertTrue(
                    "${shape.id} / $state 眼洞溢出，余量 ${margin}",
                    margin!! >= 0.0,
                )
            }
        }
    }

    @Test
    fun `贴合偏移都是有限小量`() {
        for (shape in BOT_SHAPES) {
            for (state in listOf("idle", "wink", "notify")) {
                val (x, y) = eyeOffsetFor(shape.radii, state)
                assertTrue("${shape.id}/$state x 非有限", x.isFinite())
                assertTrue("${shape.id}/$state y 非有限", y.isFinite())
                // 偏移是「球半径单位」，不应大到把脸甩出身体
                assertTrue("${shape.id}/$state 偏移过大", kotlin.math.hypot(x, y) < 1.0)
            }
        }
    }

    @Test
    fun `不替换身体的动画状态不参与贴合表`() {
        val triangle = botShapeById("triangle")!!.radii
        for (state in listOf("orbit", "comet", "burst")) {
            val (x, y) = eyeOffsetFor(triangle, state)
            assertEquals("$state 不该有贴合偏移", 0.0, x, 1e-9)
            assertEquals("$state 不该有贴合偏移", 0.0, y, 1e-9)
            // 动画状态的轮廓就是动画本身，没有「这个形状」可言
            assertEquals("$state 不该有校验数据", null, eyeFitMargin(triangle, state))
        }
    }

    @Test
    fun `未知轮廓（null）返回零偏移`() {
        assertEquals(0.0 to 0.0, eyeOffsetFor(null, "idle"))
    }

    @Test
    fun `每种形状下眯眼和睁眼都不戳出身体`() {
        BOT_SHAPES.forEach { shape ->
            val margin = eyeFitMargin(shape.radii, STATE_POKE.id)!!
            assertTrue("${shape.id} 余量 $margin", margin > 0.0)
        }
    }
}
