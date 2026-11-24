package com.xjtu.toolbox.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeroAppearanceTest {
    private val automatic = LiquidMood(LiquidPalettes.Midnight, seed = 4f, speed = 1.2f, crease = 0.8f)

    /** 测试里当「主题色」用：与 LiquidPalettes 的任何一个都不撞，跟错了看得见。 */
    private val themePrimary = Color(0xFFB73945)

    @Test
    fun defaultsPreserveAdaptiveAppearance() {
        assertEquals(automatic, HeroAppearance().applyTo(automatic, themePrimary))
    }

    @Test
    fun midnightKeepsTheOriginalLiquidTrio() {
        val result = HeroAppearance(palette = "midnight").applyTo(automatic, themePrimary)
        assertEquals(Color(0xFF070D33), result.colors.dark)
        assertEquals(Color(0xFF1F5FD6), result.colors.mid)
        assertEquals(Color(0xFF7DA4E0), result.colors.light)
    }

    @Test
    fun motionAndShapeCanChangeWithoutChangingColor() {
        val result = HeroAppearance(motion = "still", seed = 234).applyTo(automatic, themePrimary)
        assertFalse(result.animated)
        assertEquals(automatic.colors, result.colors)
        assertEquals(2.34f, result.seed)
        assertTrue(HeroAppearance(motion = "gentle").applyTo(automatic, themePrimary).speed < automatic.speed)
    }

    @Test
    fun evenWhiteCustomColorRemainsReadableBehindWhiteText() {
        val result = HeroAppearance(palette = "custom", colorHex = "#FFFFFF").applyTo(automatic, themePrimary)
        assertTrue(result.colors.dark.luminance() <= 0.06f)
        assertTrue(result.colors.mid.luminance() <= 0.2f)
        assertTrue(result.colors.light.luminance() <= 0.36f)
        assertEquals(automatic.speed, result.speed)
        assertEquals(automatic.seed, result.seed)
    }

    @Test
    fun solidColorIsPreservedAndTextAdaptsToLightBackgrounds() {
        val light = HeroAppearance(background = "solid", colorHex = "#FFFFFF")
        assertEquals(Color.White, light.solidColorOn(themePrimary), "挑过的色不做任何改写")
        assertTrue(light.contentColorOn(themePrimary).luminance() < 0.1f)
        assertEquals(Color.White, light.copy(colorHex = "#000000").contentColorOn(themePrimary))
    }

    /**
     * 2026-11 维护者要求：纯色模式**默认就是主题色**。
     * 没挑过色（`colorHex` 空串）时铺的底色与字色都要跟着主题色走，而不是回到某个写死的蓝。
     */
    @Test
    fun solidWithoutPickedColorFollowsTheThemeColor() {
        val follow = HeroAppearance(background = "solid", colorHex = "")
        assertNull(follow.pickedSolidColor, "空串 = 没挑过，不能当成非法色值")
        assertEquals(themePrimary, follow.solidColorOn(themePrimary))
        // 字色按实际那个底算：暗主题色用白字，浅主题色用黑字
        assertEquals(Color.White, follow.contentColorOn(Color(0xFF102040)))
        assertEquals(Color.Black, follow.contentColorOn(Color.White))
    }

    /**
     * 自选色这条液体配色也不再写死兜底蓝：没挑过色（`colorHex` 空串）就跟主题色。
     *
     * 比的是「换个主题色结果就跟着变」，不是逐值等于主题色 —— `applyTo` 会把 mid / light 压到
     * 白字立得住的亮度（`capLuminance`），逐值比较必然不过，而「跟不跟主题色」这件事照样能验出来。
     */
    @Test
    fun customLiquidPaletteFallsBackToTheThemeColor() {
        val warm = HeroAppearance(palette = "custom", colorHex = "").applyTo(automatic, Color(0xFFB73945))
        val cool = HeroAppearance(palette = "custom", colorHex = "").applyTo(automatic, Color(0xFF1040C0))
        assertTrue(warm.colors.mid != cool.colors.mid || warm.colors.light != cool.colors.light,
            "换主题色，『跟随主题色』出来的液体配色也要跟着变（写死兜底蓝就不会变）")
        // 挑过的色压过主题色
        val picked = HeroAppearance(palette = "custom", colorHex = "#00FF00").applyTo(automatic, Color(0xFFB73945))
        assertTrue(picked.colors.mid != warm.colors.mid, "挑过的色不该被主题色顶掉")
    }

    @Test
    fun otherBackgroundsAlwaysUseWhiteInk() {
        listOf("liquid", "forest", "pixel").forEach { bg ->
            assertEquals(Color.White, HeroAppearance(background = bg).contentColorOn(Color.White), bg)
        }
    }

    @Test
    fun summaryDescribesTheCurrentChoice() {
        assertEquals("液体", HeroAppearance(palette = "midnight", motion = "gentle").summary)
        assertEquals("纯色", HeroAppearance(background = "solid", colorHex = "#FFFFFF").summary)
        assertEquals("森林", HeroAppearance(background = "forest").summary)
        assertEquals("像素", HeroAppearance(background = "pixel").summary)
        assertEquals("自定义", HeroAppearance(background = "custom").summary)
        assertEquals("自定义", HeroAppearance(background = "custom", imageData = "abc").summary)
    }
}
