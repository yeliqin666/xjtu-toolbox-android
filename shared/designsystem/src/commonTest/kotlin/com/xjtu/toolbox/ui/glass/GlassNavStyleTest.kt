package com.xjtu.toolbox.ui.glass

import com.xjtu.toolbox.settings.SettingsStore
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [glassNavStyle] 的四档：浮动/经典 × 宽屏/窄窗。 */
class GlassNavStyleTest {

    @Test
    fun `默认那一档（浮动 + 窄窗）给玻璃`() {
        assertTrue(
            glassNavStyle(SettingsStore.NAV_STYLE_FLOATING, isWide = false),
            "设置默认值就是 floating ⇒ 默认桌面/web 窗口（520dp 那种）应该是玻璃底栏",
        )
    }

    @Test
    fun `选了经典就不给玻璃`() {
        assertFalse(glassNavStyle(SettingsStore.NAV_STYLE_CLASSIC, isWide = false))
        assertFalse(glassNavStyle(SettingsStore.NAV_STYLE_CLASSIC, isWide = true))
    }

    @Test
    fun `宽屏一律不给玻璃（壳走侧栏，玻璃底栏不会出现）`() {
        assertFalse(
            glassNavStyle(SettingsStore.NAV_STYLE_FLOATING, isWide = true),
            "宽屏不传玻璃：否则壳会白挂一层整窗采样 backdrop，视觉零变化",
        )
    }

    @Test
    fun `认不出的值按经典处理，不猜`() {
        assertFalse(glassNavStyle("", isWide = false))
        assertFalse(glassNavStyle("浮空", isWide = false))
    }
}
