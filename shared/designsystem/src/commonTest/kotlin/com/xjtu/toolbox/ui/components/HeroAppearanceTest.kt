package com.xjtu.toolbox.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HeroAppearanceTest {
    private val automatic = LiquidMood(LiquidPalettes.Midnight, seed = 4f, speed = 1.2f, crease = 0.8f)

    @Test
    fun defaultsPreserveAdaptiveAppearance() {
        assertEquals(automatic, HeroAppearance().applyTo(automatic))
    }

    @Test
    fun midnightKeepsTheOriginalLiquidTrio() {
        val result = HeroAppearance(palette = "midnight").applyTo(automatic)
        assertEquals(Color(0xFF070D33), result.colors.dark)
        assertEquals(Color(0xFF1F5FD6), result.colors.mid)
        assertEquals(Color(0xFF7DA4E0), result.colors.light)
    }

    @Test
    fun motionAndShapeCanChangeWithoutChangingColor() {
        val result = HeroAppearance(motion = "still", seed = 234).applyTo(automatic)
        assertFalse(result.animated)
        assertEquals(automatic.colors, result.colors)
        assertEquals(2.34f, result.seed)
        assertTrue(HeroAppearance(motion = "gentle").applyTo(automatic).speed < automatic.speed)
    }

    @Test
    fun evenWhiteCustomColorRemainsReadableBehindWhiteText() {
        val result = HeroAppearance(palette = "custom", colorHex = "#FFFFFF").applyTo(automatic)
        assertTrue(result.colors.dark.luminance() <= 0.06f)
        assertTrue(result.colors.mid.luminance() <= 0.2f)
        assertTrue(result.colors.light.luminance() <= 0.36f)
        assertEquals(automatic.speed, result.speed)
        assertEquals(automatic.seed, result.seed)
    }

    @Test
    fun solidColorIsPreservedAndTextAdaptsToLightBackgrounds() {
        val light = HeroAppearance(background = "solid", colorHex = "#FFFFFF")
        assertEquals(Color.White, light.solidColor)
        assertTrue(light.contentColor.luminance() < 0.1f)
        assertEquals(Color.White, light.copy(colorHex = "#000000").contentColor)
    }

    @Test
    fun summaryDescribesTheCurrentChoice() {
        assertEquals("液体 · 午夜蓝 · 轻柔", HeroAppearance(palette = "midnight", motion = "gentle").summary)
        assertEquals("纯色 · #FFFFFF", HeroAppearance(background = "solid", colorHex = "#FFFFFF").summary)
        assertEquals("森林", HeroAppearance(background = "forest").summary)
        assertEquals("像素", HeroAppearance(background = "pixel").summary)
        assertEquals("自定义 · 未选图", HeroAppearance(background = "custom").summary)
        assertEquals("自定义", HeroAppearance(background = "custom", imageData = "abc").summary)
    }
}
