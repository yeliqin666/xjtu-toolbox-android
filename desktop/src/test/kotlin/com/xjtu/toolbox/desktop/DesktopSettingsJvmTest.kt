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

/**
 * 设置（`AppRoute.Settings`，第 27 条真数据路由）在桌面端的**装配验收**。
 *
 * 这一条路由的屏（`:core` 的 `SettingsScreen`）本轮才搬完 —— 搬迁前它在 `:app`，桌面只能落
 * `NotPortedScreen`。这里验两件事（第三件「点一下开关真的落盘」在 `renderScreens` 的
 * `settings.png` 里，那一处要点真控件）：
 *
 *  1. **偏好真落盘**：桌面那份 [SettingsStore] 写进去之后，磁盘上那份 Properties 里的
 *     **键名与 `:app` 的 `app_settings` 逐字相同**（`dark_mode` / `dynamic_color` /
 *     `nav_bar_style` …），默认值也一致（`system` / `false` / `floating` / 考勤角标默认开），
 *     把内存实例抹掉再打开还是同一份值；
 *  2. **如实降级**：[DesktopSettingsHost] 只声明它**真会照着办**的两行（深色模式 / 取色），
 *     Android 独有的那两组（通知与提醒 / 更新）与连接模式、课表来源、更新日志都是「没有」⇒ 屏上整组不画；
 *     「数据与隐私」那一组 S5 起**不再是 null**（接了两行归档动作，见 `DesktopArchiveJvmTest`）。
 *     断言写在这里，免得将来有人顺手把它们填成空实现。
 */
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

        // 默认值：与 `:app` 的 `CredentialStore` 逐条对齐（那是搬迁前的实现）
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

        // ① 磁盘上那份文件里就是这些键（名字与 `:app` 的 `SharedPreferences("app_settings")` 一致）
        assertTrue(file.exists(), "落盘文件应当真的建出来：${file.absolutePath}")
        val props = Properties().apply { file.inputStream().use { load(it) } }
        assertEquals("dark", props.getProperty("dark_mode"))
        assertEquals("true", props.getProperty("dynamic_color"))
        assertEquals("classic", props.getProperty("nav_bar_style"))

        // ② 只把那份文件搬到另一个数据根目录下、重新打开一个 store 实例：值是从**文件**里读出来的
        //    （`secureKeyValueStore` 按名字缓存实例，同一根目录下再开拿到的还是内存里那份；
        //    `wipeSecureStore` 会连文件一起删，所以用它验不了「重开还在」）。
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
            // 首页主题（`HOME_THEME`）本轮接上了：桌面有首页了（`AppRoute.Main` →
            // `DesktopHomeScreen`），它读的就是这个键。`QUICK_ACTIONS` 不列 ——
            // 桌面没有点击记录，那一排压根不画（见 `DesktopHomeScreen`）。
            setOf(SettingsRow.DARK_MODE, SettingsRow.DYNAMIC_COLOR, SettingsRow.HOME_THEME),
            host.honoredRows,
            "桌面外壳接了深色模式 / 取色 / 首页主题（见 DesktopSettingsHost 的 KDoc）",
        )
        assertNull(host.noticeSection, "桌面没有系统通知权限与 WorkManager 排程 ⇒ 整组不画")
        // S5：这一组不再是 null —— 它接上了两行归档动作（导出 / 导入数据存档），
        // 见 `com.xjtu.toolbox.desktop.archive.DesktopArchiveHost` 与 `DesktopArchiveJvmTest`。
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
