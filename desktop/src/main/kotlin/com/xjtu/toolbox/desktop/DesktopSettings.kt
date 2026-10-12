package com.xjtu.toolbox.desktop

import androidx.compose.runtime.Composable
import com.xjtu.toolbox.platform.secureKeyValueStore
import com.xjtu.toolbox.settings.ChangelogEntry
import com.xjtu.toolbox.settings.ScheduleSourceOption
import com.xjtu.toolbox.settings.SettingsHost
import com.xjtu.toolbox.settings.SettingsRow
import com.xjtu.toolbox.settings.SettingsStore

/**
 * 桌面端的设置偏好存储（进程一份）。
 *
 * 用 `:data` 的 [secureKeyValueStore]（jvm 的 actual = **0600 权限的 Properties 文件**，
 * 与凭据 / cookie / 站点快照同一套落盘口径、同一个数据根目录），文件名与 Android 那份
 * **逐字相同**（`app_settings`）—— 桌面端没有老数据要继承，保持一致是为了两端的键名能一眼对上
 *（与 `JvmCredentialStore` 同一条纪律）。
 *
 * ⚠️ 为什么不用 `:core` 的 `keyValueStore(name)`：jvm 那份是**进程内内存**实现
 *（`:core` 的 KDoc 写着「刻意不落盘 —— 免得测试之间互相污染」），设置项存进去重开就没了。
 * 设计文档 §5.3 把「`KeyValueStore` → 落盘」列在 jvm 侧要「真化」的那一项里；
 * 在这一端先按 store 逐个接上落盘实现（凭据 / cookie 已经是这么做的）。
 */
internal val desktopSettings: SettingsStore by lazy {
    SettingsStore(secureKeyValueStore(SettingsStore.APP_SETTINGS_FILE))
}

/**
 * 桌面端的设置页宿主 —— **如实声明这一端有什么**（见 `:core` 的 [SettingsHost] 与 [SettingsRow]）。
 *
 * | | 桌面这一端 |
 * |---|---|
 * | 真会照着办的行 | **深色模式 / 跟随系统取色**：外壳按这两个偏好套 `XJTUToolBoxTheme`（改一下当场就变） |
 * | 「通知与提醒」整组 | `null` —— 桌面没有 WorkManager 排程，也没有申请通知权限的那套流程（设计文档 §6 Stage C 说「小组件用系统通知替代」，那是下一批的事） |
 * | 「数据与隐私」整组 | `null` —— 没有可清的 Android 应用缓存，也没有 `CrashReporter` |
 * | 「更新」整组 | `null` —— 这一端是 `.deb` / tar.gz，没有「下载并安装 APK」那条路；更新渠道 / 灰度分桶都是 `:app` 的更新域 |
 * | 「连接模式」 | `null` —— 桌面只按直连走（没有校园网判定与 WebVPN 网关，见 `DesktopAuth` 的「已知缺口」） |
 * | 「当前学期课表来源」 | 空表 —— **课表屏这轮点亮了**（`AppRoute.Schedule` + `DesktopScheduleHost`），
 *   而这一端取数读的确实是同一份 `SettingsStore.scheduleSource`；只是这里还没有改它的入口
 *   （要一对 options + 写入），所以那一行不画 |
 * | 更新日志 | 空表 —— `AppChangelog` 属 `:app` 的更新域，没搬 |
 *
 * ## TODO（桌面侧还缺的宿主面）
 *
 * 1. **外壳只接了深色模式 / 取色 / 首页主题**：`显示常用功能`与`界面风格`（底栏玻璃/经典）
 *    是 `:app` 首页与底栏的宿主面，桌面这一端还没有对应实现 ⇒ 那两行**不画**（列进 honoredRows
 *    才是假装能用）。要接的时候：底栏换成 `:core` 的 `GlassBottomTabs`（`:app` 用的是同一份）；
 *    常用功能那一排要先把点击记录（`ServiceUsageTracker`）搬进共享层。
 * 2. **默认启动 Tab**：桌面底栏固定六格（首页 / 校历 / 图书馆 / 黄页 / 游戏 / 全部），没有「默认进哪个 tab」这一档。
 * 3. **触感反馈**：`HapticsController` 的 jvm actual 是空实现（桌面不震）⇒ 那一行不画。
 * 4. **课表来源 / 课表考勤 / 场馆验证码**：课表屏已经点亮（见上表），但「来源」「考勤角标」这两行
 *    还没有改值的入口，「场馆下单」这一端也不支持（`canBook = false`）⇒ 三行都不画。
 *    ⚠️ 其中「课表来源」的值**已经会被读到**（`:data` 的 `ScheduleSourceRouter` 读同一份 store、
 *    同一个键）—— 只是用户在这一端还没有改它的地方，所以是「不画，但读」，不是「画了没用」。
 * 5. **通知与提醒 / 数据与隐私 / 更新**：见上表（`null`）。
 */
internal class DesktopSettingsHost(private val notifyAppearanceChanged: () -> Unit) : SettingsHost {

    /**
     * 版本号那一行：桌面包的版本（构建期烘进 `DesktopBuildConfig`，与 jpackage 的
     * `packageVersion` 同一个来源）。括号里写 `desktop` 是为了与 `:app` 的
     * `v5.1.1 (471)` 那一行一眼分得开 —— 这一端没有 `VERSION_CODE`。
     */
    override val versionText: String = "v${DesktopBuildConfig.VERSION_NAME} (desktop)"

    /**
     * 真会照着办的行。
     *
     *  - [SettingsRow.DARK_MODE] / [SettingsRow.DYNAMIC_COLOR]：外壳按这两个偏好套 `XJTUToolBoxTheme`；
     *  - [SettingsRow.HOME_THEME]：**本轮接上** —— 桌面现在有首页了（`AppRoute.Main` →
     *    `DesktopHomeScreen`），它读的就是这个键（`SettingsStore.homeTheme`，与 `:app` 同一个键、
     *    同一个值域）：改一下再回首页，两套布局真的会换。
     *
     * `显示常用功能`（[SettingsRow.QUICK_ACTIONS]）**不列**：桌面没有 `ServiceUsageTracker` 那份
     * 点击记录，首页那一排「常用功能」在桌面上压根不画（见 `DesktopHomeScreen` 的如实降级那段）
     * —— 开关拨了也看不到任何变化，那就是假装能用。
     */
    override val honoredRows: Set<SettingsRow> =
        setOf(SettingsRow.DARK_MODE, SettingsRow.DYNAMIC_COLOR, SettingsRow.HOME_THEME)

    override val changelog: List<ChangelogEntry> = emptyList()

    override val scheduleSourceOptions: List<ScheduleSourceOption> = emptyList()

    override val noticeSection: (@Composable () -> Unit)? = null

    override val dataPrivacySection: (@Composable () -> Unit)? = null

    override val updateSection: (@Composable () -> Unit)? = null

    override val onNetworkModeChanged: (() -> Unit)? = null

    override fun onAppearanceChanged() = notifyAppearanceChanged()

    /** 打开外链：桌面用 `Main.kt` 里那个 `openInBrowser`（`java.awt.Desktop.browse`）。 */
    override val onOpenUrl: (String) -> Unit = { openInBrowser(it) }
}
