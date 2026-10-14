package com.xjtu.toolbox.settings

import com.xjtu.toolbox.platform.keyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 设置偏好的读写：**键名与默认值与 `:app` 那份 `CredentialStore` 逐字相同**。
 *
 * 这一条为什么值得单独钉：`:app` 的老用户（以及桌面端已经设过的那几项）都是按**字面键名**
 * 存在 `app_settings` 里的，键名漂一个字符，用户重装/升级后就会看到「设置被重置了」——
 * 而那种错只有在真机上才发现得了。所以这里不测「行为」，测的是**契约**：
 *  - 每个键的名字（`dark_mode` / `nav_bar_style` / `schedule_attendance_badge` ……）；
 *  - 每个键的默认值（`system` / `floating` / `COURSES` / `true` 那一套，与搬迁前逐条对齐）；
 *  - 值常量的字面量（`"jwxt"` / `"bkkq"` / `"HOME"` ……）——它们已经写进用户数据，不能改。
 *
 * 存储用 `:core` 的键值缝（JVM 上现在真落盘）：这一层不关心落盘在哪儿，
 * 落盘那份由各端的宿主测（桌面的 `DesktopSettingsJvmTest` 钉的是 0600 的 Properties 文件）。
 */
class SettingsStoreTest {

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
        assertEquals("card", settings.homeTheme, "首页主题默认卡片")
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
        assertEquals("card", SettingsStore.THEME_CARD)
        assertEquals("icon", SettingsStore.THEME_ICON)
        assertEquals("HOME", SettingsStore.TAB_HOME)
        assertEquals("COURSES", SettingsStore.TAB_COURSES)
        assertEquals("PIDAI", SettingsStore.TAB_PIDAI)
        assertEquals("TOOLS", SettingsStore.TAB_TOOLS)
        assertEquals("PROFILE", SettingsStore.TAB_PROFILE)
        assertEquals("auto", SettingsStore.NETWORK_AUTO)
        assertEquals("direct", SettingsStore.NETWORK_DIRECT)
        assertEquals("vpn", SettingsStore.NETWORK_VPN)
        assertEquals("jwxt", SettingsStore.SCHEDULE_SOURCE_JWXT)
        // 旧版考勤（bkkq）时代的写法：不能改成 attendance 之类
        assertEquals("bkkq", SettingsStore.SCHEDULE_SOURCE_ATTENDANCE)
        assertEquals("app_settings", SettingsStore.APP_SETTINGS_FILE)
    }
}
