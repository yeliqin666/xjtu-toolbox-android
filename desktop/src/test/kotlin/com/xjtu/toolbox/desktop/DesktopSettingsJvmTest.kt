package com.xjtu.toolbox.desktop

import com.xjtu.toolbox.platform.secureKeyValueStore
import com.xjtu.toolbox.platform.dataRootOverride
import com.xjtu.toolbox.platform.wipeSecureStore
import com.xjtu.toolbox.settings.SettingsRow
import com.xjtu.toolbox.settings.SettingsStore
import java.io.File
import java.nio.file.Files
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 设置（`AppRoute.Settings`）在桌面端的装配验收。 */
class DesktopSettingsJvmTest {

    private lateinit var dataRoot: File

    private fun withTempDataRoot(block: () -> Unit) {
        dataRoot = Files.createTempDirectory("xjtu-desktop-settings-test").toFile()
        dataRootOverride = dataRoot
        wipeSecureStore(SettingsStore.APP_SETTINGS_FILE)
        try {
            block()
        } finally {
            dataRootOverride = null
        }
    }

    @Test
    fun `设置项真落盘：键名与 app 那份 app_settings 逐字相同，重开还是同一份值`() = withTempDataRoot {
        val file = File(dataRoot, "app_settings.properties")
        val store = SettingsStore(secureKeyValueStore(SettingsStore.APP_SETTINGS_FILE))

        assertEquals(SettingsStore.DARK_MODE_SYSTEM, store.darkMode, "深色模式默认跟随系统")
        assertTrue(!store.dynamicColor, "取色默认关")
        assertEquals(SettingsStore.NAV_STYLE_FLOATING, store.navBarStyle, "界面风格默认玻璃")
        assertEquals(SettingsStore.TAB_COURSES, store.defaultTab, "默认启动 Tab 是日程")
        assertEquals(SettingsStore.NETWORK_AUTO, store.networkMode, "连接模式默认自动检测")
        assertTrue(store.scheduleAttendanceBadge, "考勤角标默认开")
        assertTrue(store.venueAutoSolveCaptchaEnabled, "场馆验证码自动识别默认开")
        assertTrue(store.showQuickActions, "常用功能默认显示")
        assertEquals(SettingsStore.SCHEDULE_SOURCE_JWXT, store.scheduleSource, "课表来源默认教务")

        store.darkMode = SettingsStore.DARK_MODE_DARK
        store.dynamicColor = true
        store.navBarStyle = SettingsStore.NAV_STYLE_CLASSIC

        assertTrue(file.exists(), "落盘文件应当真的建出来：${file.absolutePath}")
        val props = Properties().apply { file.inputStream().use { load(it) } }
        assertEquals("dark", props.getProperty("dark_mode"))
        assertEquals("true", props.getProperty("dynamic_color"))
        assertEquals("classic", props.getProperty("nav_bar_style"))

        val anotherRoot = Files.createTempDirectory("xjtu-desktop-settings-again").toFile()
        file.copyTo(File(anotherRoot, file.name))
        dataRootOverride = anotherRoot
        val reopened = SettingsStore(secureKeyValueStore(SettingsStore.APP_SETTINGS_FILE))
        assertEquals(SettingsStore.DARK_MODE_DARK, reopened.darkMode)
        assertTrue(reopened.dynamicColor)
        assertEquals(SettingsStore.NAV_STYLE_CLASSIC, reopened.navBarStyle)
    }

    @Test
    fun `桌面宿主的如实降级：只声明真会照办的两行，没有的行与组是 null`() {
        var ticked = 0
        val host = DesktopSettingsHost { ticked++ }

        assertEquals(
            setOf(SettingsRow.DARK_MODE, SettingsRow.DYNAMIC_COLOR, SettingsRow.HOME_THEME),
            host.honoredRows,
            "桌面外壳接了深色模式 / 取色 / 首页主题（见 DesktopSettingsHost 的 KDoc）",
        )
        assertNull(host.noticeSection, "桌面没有系统通知权限与 WorkManager 排程 ⇒ 整组不画")
        assertNotNull(
            host.dataPrivacySection,
            "S5 起「数据与隐私」是真组：导出数据存档 / 导入数据存档两行（Android 那两样仍然没有）",
        )
        assertNull(host.updateSection, "桌面是 .deb/tar.gz，没有「下载并安装 APK」那条路 ⇒ 整组不画")
        assertNull(host.onNetworkModeChanged, "桌面只按直连走 ⇒ 连接模式那一行不画")
        assertTrue(host.scheduleSourceOptions.isEmpty(), "桌面还没有改课表来源的入口 ⇒ 那一行不画")
        assertTrue(host.changelog.isEmpty(), "更新日志属 :app 的更新域，没搬 ⇒ 那一行不画")
        assertTrue(host.versionText.startsWith("v"), "版本号那一行照旧画（桌面包的版本）")

        host.onAppearanceChanged()
        assertEquals(1, ticked, "外观改完要戳一下外壳（它据此重读主题偏好）")
    }
}
