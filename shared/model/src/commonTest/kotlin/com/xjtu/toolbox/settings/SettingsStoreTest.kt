package com.xjtu.toolbox.settings

import com.xjtu.toolbox.platform.keyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 设置偏好的键名与默认值。 */
class SettingsStoreTest {
    @Test
    fun `头卡外观跨实例保留且无效偏好回退`() {
        val raw = freshStore()
        val settings = SettingsStore(raw)
        assertEquals("auto", settings.heroPalette)
        assertEquals("auto", settings.heroMotion)
        assertEquals(-1, settings.heroSeed)
        assertEquals("liquid", settings.heroBackground)
        settings.heroBackground = "solid"
        settings.heroPalette = "custom"
        settings.heroColorHex = " #a2f "
        settings.heroMotion = "still"
        settings.heroSeed = 347
        val reopened = SettingsStore(raw)
        assertEquals("custom", reopened.heroPalette)
        assertEquals("#AA22FF", reopened.heroColorHex)
        assertEquals("still", reopened.heroMotion)
        assertEquals(347, reopened.heroSeed)
        assertEquals("solid", reopened.heroBackground)
        settings.heroBackground = "forest"
        assertEquals("forest", SettingsStore(raw).heroBackground, "森林背景也要能落盘")
        settings.heroBackground = "pixel"
        assertEquals("pixel", SettingsStore(raw).heroBackground, "像素背景也要能落盘")
        settings.heroBackground = "custom"
        settings.heroImageData = "aGVsbG8="
        assertEquals("custom", SettingsStore(raw).heroBackground, "自定义背景也要能落盘")
        assertEquals("aGVsbG8=", SettingsStore(raw).heroImageData, "自定义图片要跟着背景一起落盘")
        raw.putString("hero_background", "unknown")
        assertEquals("liquid", SettingsStore(raw).heroBackground, "认不出的背景回退到液体")
        raw.putString("hero_palette", "unknown")
        raw.putString("hero_motion", "unknown")
        raw.putString("hero_color_hex", "not a color")
        raw.putInt("hero_seed", -100)
        assertEquals("auto", reopened.heroPalette)
        assertEquals("auto", reopened.heroMotion)
        assertEquals("#2F7CFF", reopened.heroColorHex)
        assertEquals(-1, reopened.heroSeed)
    }

    @Test
    fun `自定义颜色规范化并保留旧取色偏好`() {
        val raw = freshStore()
        raw.putBoolean("dynamic_color", true)
        val settings = SettingsStore(raw)
        assertTrue(settings.dynamicColor)
        assertTrue(!settings.customColorEnabled)
        assertEquals("", settings.customColorHex)
        settings.customColorHex = " #a2f "
        settings.customColorEnabled = true
        assertEquals("#AA22FF", SettingsStore(raw).customColorHex)
        assertTrue(SettingsStore(raw).customColorEnabled)
        settings.customColorEnabled = false
        assertEquals("#AA22FF", settings.customColorHex)
        listOf("#12345678", "+12345", "oops", "#12", "").forEach {
            settings.customColorHex = it
            assertEquals("", settings.customColorHex)
        }
        settings.customColorHex = "123aBC"
        assertEquals("#123ABC", settings.customColorHex)
    }

    @Test
    fun `自选主题色按浅色与深色分开保存`() {
        val raw = freshStore()
        raw.putString("custom_color_hex", "#112233")
        val settings = SettingsStore(raw)
        settings.setCustomColorHex(dark = false, value = "#aabbcc")
        settings.setCustomColorHex(dark = true, value = "#ddeeff")
        val reopened = SettingsStore(raw)
        assertEquals("#AABBCC", reopened.customColorHex(false))
        assertEquals("#DDEEFF", reopened.customColorHex(true))
        raw.remove("custom_color_hex_dark")
        assertEquals("#112233", reopened.customColorHex(true), "没单独选过的深色回退到旧键")
        reopened.customColorEnabled = true
        assertEquals("#AABBCC", reopened.themeSeedHex(dark = false))
        assertEquals("#112233", reopened.themeSeedHex(dark = true))
        reopened.customColorEnabled = false
        assertEquals("", reopened.themeSeedHex(dark = false), "没启用自定义就不喂种子色")
    }

    @Test
    fun `头卡自选色按浅色与深色分开保存`() {
        val raw = freshStore()
        val settings = SettingsStore(raw)
        raw.putString("hero_color_hex", "#112233")
        settings.setHeroColorHex(dark = false, value = "#aabbcc")
        settings.setHeroColorHex(dark = true, value = "#DDEEFF")
        val reopened = SettingsStore(raw)
        assertEquals("#AABBCC", reopened.heroColorHex(false))
        assertEquals("#DDEEFF", reopened.heroColorHex(true))
        raw.remove("hero_color_hex_dark")
        assertEquals("#112233", reopened.heroColorHex(true), "没单独选过的深色回退到旧键")
    }

    private fun freshStore() = keyValueStore("settings_store_unit_test").apply { clear() }

    @Test
    fun `默认值与 app 逐条对齐，且写进去的是同名键`() {
        val raw = freshStore()
        val settings = SettingsStore(raw)

        assertEquals("system", settings.darkMode, "深色模式默认跟随系统")
        assertTrue(!settings.dynamicColor, "取色默认关")
        assertEquals("floating", settings.navBarStyle, "界面风格默认玻璃")
        assertEquals("COURSES", settings.defaultTab, "默认启动 Tab 是日程")
        assertEquals("auto", settings.networkMode, "连接模式默认自动检测")
        assertTrue(settings.showQuickActions, "常用功能默认显示")
        assertTrue(settings.scheduleAttendanceBadge, "考勤角标默认开")
        assertTrue(settings.venueAutoSolveCaptchaEnabled, "场馆验证码自动识别默认开")
        assertEquals("jwxt", settings.scheduleSource, "课表来源默认教务")

        // 写一遍：字面键名一个字符都不能漂（Android 那份是同一个 SharedPreferences 文件）
        settings.darkMode = "dark"
        settings.navBarStyle = "classic"
        settings.scheduleAttendanceBadge = false
        settings.scheduleSource = "bkkq"

        assertEquals("dark", raw.getString("dark_mode"))
        assertEquals("classic", raw.getString("nav_bar_style"))
        assertEquals("false", raw.getBoolean("schedule_attendance_badge", true).toString())
        assertEquals("bkkq", raw.getString("schedule_source"))
    }

    @Test
    fun `值常量的字面量固定：它们已经写进用户数据`() {
        assertEquals("system", SettingsStore.DARK_MODE_SYSTEM)
        assertEquals("light", SettingsStore.DARK_MODE_LIGHT)
        assertEquals("dark", SettingsStore.DARK_MODE_DARK)
        assertEquals("floating", SettingsStore.NAV_STYLE_FLOATING)
        assertEquals("classic", SettingsStore.NAV_STYLE_CLASSIC)
        assertEquals("HOME", SettingsStore.TAB_HOME)
        assertEquals("COURSES", SettingsStore.TAB_COURSES)
        assertEquals("PIDAI", SettingsStore.TAB_PIDAI)
        assertEquals("TOOLS", SettingsStore.TAB_TOOLS)
        assertEquals("PROFILE", SettingsStore.TAB_PROFILE)
        assertEquals("auto", SettingsStore.NETWORK_AUTO)
        assertEquals("direct", SettingsStore.NETWORK_DIRECT)
        assertEquals("vpn", SettingsStore.NETWORK_VPN)
        assertEquals("jwxt", SettingsStore.SCHEDULE_SOURCE_JWXT)
        // 旧版考勤（bkkq）的取值：不能改
        assertEquals("bkkq", SettingsStore.SCHEDULE_SOURCE_ATTENDANCE)
        assertEquals("app_settings", SettingsStore.APP_SETTINGS_FILE)
    }
}
