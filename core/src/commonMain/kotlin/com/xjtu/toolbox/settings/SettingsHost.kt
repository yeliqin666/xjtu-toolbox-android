package com.xjtu.toolbox.settings

import androidx.compose.runtime.Composable

/**
 * 设置页里那些**只有某些端才真会照着办**的行。
 *
 * ## 为什么要有这张清单
 *
 * 设置项分两类：一类是「存了就会有人读」（例如深色模式：`:app` 的 `AppearanceSettings`、
 * 桌面外壳都订阅同一份偏好），另一类是「这一端根本没有那个东西」（桌面没有首页、没有底栏玻璃、
 * 没有课表、没有系统通知排程）。第二类**不该画出来**：存进去没人理 = 让用户以为设了有用，
 * 与「点了会失败的按钮一个都不画」是同一条纪律（见 `LibraryScreen` 的 KDoc）。
 *
 * 所以每一端用 [SettingsHost.honoredRows] 声明自己**真会照着办**的那几行，屏只画列进来的。
 * `:app` 声明全部（它每一项都有宿主面在读）；桌面声明它真接得上的那几项，其余的留 TODO。
 */
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

/**
 * 一条更新日志（`:app` 从 `AppChangelog.ENTRIES` 映射过来 —— 更新域不在 `:core`，
 * 所以这里只收「画那一屏要什么」：版本号 + 每条一句话）。
 */
data class ChangelogEntry(val version: String, val items: List<String>)

/** 「当前学期课表来源」的一个候选（`:app` 从 `ScheduleSource.entries` 映射过来）。 */
data class ScheduleSourceOption(val key: String, val label: String)

/**
 * 设置页的**宿主槽位** —— 「这一端有什么」，而不是「这一端是不是 Android」。
 *
 * 三类东西走这里（其余一律留在共享屏里）：
 *
 * 1. **只有某些端真会照着办的行**：[honoredRows]（见 [SettingsRow] 的 KDoc）；
 * 2. **Android 专属的整组设置**：[noticeSection]（系统通知权限 + WorkManager 排程）、
 *    [dataPrivacySection]（清 `cacheDir` + 崩溃日志上报）、[updateSection]（`AppUpdater`
 *    下载安装 APK）。`null` = 本端没有这一组，**整组不画**（连同它的小标题）；
 * 3. **宿主才知道的数据**：版本号、更新日志、课表来源候选、换连接模式之后的动作。
 */
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

    /**
     * 换连接模式之后要通知的事（`:app` = `loginState.onNetworkChanged()`：按新模式重新挑 backend）。
     * `null` = 本端没有「连接模式」这一档（桌面只按直连走，没有校园网判定与 WebVPN 网关）。
     */
    val onNetworkModeChanged: (() -> Unit)?

    /**
     * 外观设置（深色模式 / 取色 / 首页主题 / 界面风格）写完之后的回调。
     *
     * `:app` 不用做任何事：`AppearanceSettings` 监听的就是同一个 `app_settings` 文件，
     * 主题自己会跟。桌面那份键值缝没有监听器 ⇒ 外壳在这里重新读一次 store（于是
     * 「在设置页把深色模式改成始终深色」当场就变）。
     */
    fun onAppearanceChanged() {}

    /**
     * 打开一个外链（关于与致谢那几行的项目主页 / 许可证）。
     *
     * 为什么走槽位而不是屏里读 `LocalUriHandler`：各端的「打开链接」不是同一件事
     *（Android = `Intent.ACTION_VIEW`、桌面 = `Desktop.browse`、浏览器 = `window.open`），
     * 而且它是个**可能没被提供**的 CompositionLocal（离屏渲染场景就没有）——
     * 与 `FacultyScreen` / `FeedbackScreen` 那两屏同一条口径。
     */
    val onOpenUrl: (String) -> Unit
}
