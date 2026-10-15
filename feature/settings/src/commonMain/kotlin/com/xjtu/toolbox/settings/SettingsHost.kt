package com.xjtu.toolbox.settings

import androidx.compose.runtime.Composable

/** 设置页里那些只有某些端才真会照着办的行。 */
enum class SettingsRow {
    /** 深色模式（`:app` 的 `AppearanceSettings` / 桌面外壳都按它切主题）。 */
    DARK_MODE,

    /** 跟随系统取色（Monet）。 */
    DYNAMIC_COLOR,

    /** 首页主题（卡片 / 图标）：`:app` 首页那两套布局。 */
    HOME_THEME,

    /** 首页「显示常用功能」。 */
    QUICK_ACTIONS,

    /** 界面风格（玻璃 / 经典）：`:app` 底栏与各处玻璃点。 */
    NAV_STYLE,

    /** 默认启动 Tab。 */
    DEFAULT_TAB,

    /** 触感反馈。 */
    HAPTICS,

    /** 连接模式（自动检测 / 强制直连 / 强制 WebVPN）。 */
    NETWORK_MODE,

    /** 当前学期课表来源。 */
    SCHEDULE_SOURCE,

    /** 课表显示考勤角标。 */
    SCHEDULE_ATTENDANCE,

    /** 自动识别场馆验证码。 */
    VENUE_CAPTCHA,
}

/** 一条更新日志。 */
data class ChangelogEntry(val version: String, val items: List<String>)

/** 「当前学期课表来源」的一个候选（`:app` 从 `ScheduleSource.entries` 映射过来）。 */
data class ScheduleSourceOption(val key: String, val label: String)

/** 设置页的宿主槽位 —— 「这一端有什么」，而不是「这一端是不是 Android」。 */
interface SettingsHost {

    /** 版本号那一行的文本（`:app` = `BuildConfig.VERSION_NAME (VERSION_CODE)`；桌面 = 桌面包的版本）。 */
    val versionText: String

    /** 这一端**真会照着办**的那些行（见 [SettingsRow]）。 */
    val honoredRows: Set<SettingsRow>

    /** 更新日志（空 = 本端没有更新日志，那一行不画）。 */
    val changelog: List<ChangelogEntry>

    /** 「当前学期课表来源」的候选（空 = 本端没有课表，那一行不画）。 */
    val scheduleSourceOptions: List<ScheduleSourceOption>

    /** 「通知与提醒」整组。`null` = 本端没有系统通知与后台排程。 */
    val noticeSection: (@Composable () -> Unit)?

    /** 「数据与隐私」整组。`null` = 本端没有可清的应用缓存与崩溃上报。 */
    val dataPrivacySection: (@Composable () -> Unit)?

    /** 「更新」整组。`null` = 本端没有应用更新机制。 */
    val updateSection: (@Composable () -> Unit)?

    /** 换连接模式之后要通知的事（`:app` = `loginState.onNetworkChanged()`：按新模式重新挑 backend）。 */
    val onNetworkModeChanged: (() -> Unit)?

    /** 外观设置（深色模式 / 取色 / 首页主题 / 界面风格）写完之后的回调。 */
    fun onAppearanceChanged() {}

    /** 打开一个外链（关于与致谢那几行的项目主页 / 许可证）。 */
    val onOpenUrl: (String) -> Unit
}
