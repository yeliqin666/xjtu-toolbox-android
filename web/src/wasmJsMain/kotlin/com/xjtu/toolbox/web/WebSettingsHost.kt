package com.xjtu.toolbox.web

import androidx.compose.runtime.Composable
import com.xjtu.toolbox.settings.ChangelogEntry
import com.xjtu.toolbox.settings.ScheduleSourceOption
import com.xjtu.toolbox.settings.SettingsHost
import com.xjtu.toolbox.settings.SettingsRow

/**
 * Web 这一端的设置页宿主面（`:core` 的 `SettingsScreen` 是**共享屏**）。
 *
 * ## 真会照着办的行：[SettingsRow.DARK_MODE] 与 [SettingsRow.HOME_THEME]
 *
 * 深色模式：外壳（`Main.kt`）按 `SettingsStore().darkMode` 套 `XJTUToolBoxTheme`，改完由
 * [onAppearanceChanged] 戳一下重新读 —— 与桌面那份同一个写法（键值缝没有监听器）。
 * 于是「在设置页把深色模式改成始终深色」**当场就变**，这一行是真能用的。
 *
 * 首页主题：首页（`:core` 的 `HomeScreen` + 本端 `WebHomeHost`）按 `SettingsStore.homeTheme`
 * 决定画三段式分类卡还是彩虹宫格（图标主题）—— 这个键与 `:app` 同一个（`home_theme`）。
 * 改完**切回首页就是新布局**（与 `:app` 上「切回 Tab 1 就能看见」同一件事：本端一次只组合
 * 当前那一页，所以回首页时会重新读一次）。
 * ## 不列的行（列了就是假开关）
 *
 * - [SettingsRow.DYNAMIC_COLOR]（跟随系统取色 / Monet）：本端外壳现在**不读**这个键
 *   （`Main.kt` 的 `XJTUToolBoxTheme(dynamicColor = false)`）—— 理由不是"浏览器没有 Monet"，
 *   而是这一档在 wasm 上还没验证过（`:core` 用的是 `material-color-utilities` 的 KMP 版）。
 *   TODO：验证之后把 [SettingsRow.DYNAMIC_COLOR] 列进来、`Main.kt` 跟着读这个键，两处一起改；
 * - [SettingsRow.QUICK_ACTIONS]（显示常用功能）：本端首页**不画「常用功能」那一排**
 *   （`HomeCard.QUICK_ACTIONS` 不进 `honoredCards`，理由见 `WebHomeHost`：`:app` 按点击习惯排，
 *   本端没有 `ServiceUsageTracker` 那份记录）⇒ 开着它也不会有任何东西出现，不画这个开关；
 * - [SettingsRow.NAV_STYLE]（玻璃 / 经典）：本端底栏交给共享壳画，`glassStyle = false` 固定
 *   （`ToolboxWebApp` 里那一行）—— 这一端没有"玻璃底栏"这一档，不画这个开关；
 * - [SettingsRow.DEFAULT_TAB]：`:app` 的启动 Tab 存在设置里、由**启动意图**消费；本端是刷新
 *   一个页面，没有"启动"这回事，手动加的 tab 记录也没人读；
 * - [SettingsRow.HAPTICS]：浏览器没有触感反馈（`Haptics` 的 wasm actual 是空实现）；
 * - [SettingsRow.NETWORK_MODE]：本端只有一条路（同源，要么 campus-api 要么 serve）——
 *   `onNetworkModeChanged` 传 null，那一行整组不出现（与桌面同一条口径）；
 * - [SettingsRow.SCHEDULE_SOURCE]：候选表由端上给（`:app` 是 `ScheduleSource.entries`），
 *   本端只有教务这一个来源 ⇒ 空表 ⇒ 那一行不画；
 * - [SettingsRow.SCHEDULE_ATTENDANCE]：考勤状态只在设置里那一个键上（`:app` 的
 *   `CredentialStore.schedule_attendance_badge`），本端**没有考勤取数**（见 `WebScheduleHost`
 *   的 `honored`），开着它也画不出角标 ⇒ 不画；
 * - [SettingsRow.VENUE_CAPTCHA]（自动识别场馆验证码）：`:core` 的图片解码在 wasm 上恒 null
 *   （见 `WebSlideCaptchaHost` 的 KDoc：本端停在手动滑动那一档）⇒ 不画。
 */
internal class WebSettingsHost(
    /** 外观改完之后戳一下外壳（`Main.kt` 重新读一次 `SettingsStore`）。 */
    private val notifyAppearanceChanged: () -> Unit,
) : SettingsHost {

    /**
     * 版本那一行：本端没有版本号这个概念（页面是同一个 dist，服务端直接托管它）。
     *
     * 写"Web 构建（wasm）"而不是编一个 `vX.Y.Z` —— 那一行在 `:app` 上是 `v5.1.1 (471)`、
     * 桌面上是 `vX.Y.Z (desktop)`，本端**如实**写自己是哪一份构建就够。
     */
    override val versionText: String = "Web 构建（wasm）"

    override val honoredRows: Set<SettingsRow> =
        setOf(SettingsRow.DARK_MODE, SettingsRow.HOME_THEME)

    /** 本端没有更新日志（`:app` 的 `AppChangelog` 在更新域里）⇒ 那一行不画。 */
    override val changelog: List<ChangelogEntry> = emptyList()

    /** 见类 KDoc：本端只有教务一个课表来源 ⇒ 空表 ⇒ 那一行不画。 */
    override val scheduleSourceOptions: List<ScheduleSourceOption> = emptyList()

    /** 「通知与提醒」整组：浏览器里没有系统通知排程这一档 ⇒ 整组不画。 */
    override val noticeSection: (@Composable () -> Unit)? = null

    /** 「数据与隐私」整组：清缓存 / 崩溃上报都挂在 Android 的 `cacheDir` 与上报通道上 ⇒ 整组不画。 */
    override val dataPrivacySection: (@Composable () -> Unit)? = null

    /** 「更新」整组：本端的更新就是服务端换一份 dist（刷新一次页面） ⇒ 整组不画。 */
    override val updateSection: (@Composable () -> Unit)? = null

    override val onNetworkModeChanged: (() -> Unit)? = null

    override fun onAppearanceChanged() = notifyAppearanceChanged()

    /** 打开外链：浏览器就是 `window.open(..., 'noopener')`（与 `Data.kt` 里那一份同一个实现）。 */
    override val onOpenUrl: (String) -> Unit = { openInNewTab(it) }
}
