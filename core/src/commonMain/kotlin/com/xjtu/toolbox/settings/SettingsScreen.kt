package com.xjtu.toolbox.settings

import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tab
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Carrier
import top.yukonga.miuix.kmp.icon.extended.CloudFill
import top.yukonga.miuix.kmp.icon.extended.File
import top.yukonga.miuix.kmp.icon.extended.Forward
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Recent
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Theme
import com.xjtu.toolbox.platform.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.ui.HapticsSettingItem
import com.xjtu.toolbox.ui.glass.*
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * 设置屏（`AppRoute.Settings` / `:app/settings`）—— 从 `:app` 同包同名搬进 `:core`，
 * Android 与桌面画**同一份**（八组设置、宽屏两栏、各处弹层，文案与排版逐字未动）。
 *
 * ## 搬进来的改动只有三类（都是「宿主能力」，不是业务）
 *
 * 1. **偏好的存储**：`CredentialStore`（`Context` + `SharedPreferences`）→ [SettingsStore]
 *    （键值缝；Android 那份就是同一个 `app_settings` 文件 ⇒ 老用户设过的一项不丢）；
 * 2. **Android 专属的整组设置走宿主槽位**（[SettingsHost]）：
 *    「通知与提醒」（系统通知权限 + WorkManager 排程）、「数据与隐私」（清 `cacheDir` + 崩溃上报）、
 *    「更新」（`AppUpdater` 下载安装 APK）—— 这三组整份留在 `:app`（`settings/AppSettings.kt`），
 *    桌面没有对应能力 ⇒ `null` ⇒ **整组不画**（不是画一堆点了没用的开关）；
 * 3. **只有某些端真会照着办的行**由 [SettingsHost.honoredRows] 声明（见 [SettingsRow] 的 KDoc），
 *    没列进来的不画。`:app` 声明全部；桌面只声明它真接得上的（深色模式 / 取色），
 *    其余（首页主题 / 常用功能 / 界面风格 / 默认 Tab / 触感 / 连接模式 / 课表 / 场馆）
 *    桌面还没有对应的宿主面 —— 那是**如实降级**，TODO 写在 `:desktop` 的 `DesktopSettingsHost` 里。
 *
 * 剩下的全在共享屏里：外观与通用的下拉/开关、关于与致谢、更新日志与用户协议的弹层、
 * 宽屏左栏分组、玻璃顶栏、以及每一处文案。
 *
 * @param store 设置偏好的读写（Android = `app_settings` 那份 SharedPreferences；桌面 = 落盘文件）
 * @param host 宿主槽位（见 [SettingsHost]）
 * @param onBack 返回
 */
@Composable
fun SettingsScreen(
    store: SettingsStore,
    host: SettingsHost,
    onBack: () -> Unit,
) {

    val showDarkMode = SettingsRow.DARK_MODE in host.honoredRows
    val showDynamicColor = SettingsRow.DYNAMIC_COLOR in host.honoredRows
    val showHomeTheme = SettingsRow.HOME_THEME in host.honoredRows
    val showQuickActions = SettingsRow.QUICK_ACTIONS in host.honoredRows
    val showNavStyle = SettingsRow.NAV_STYLE in host.honoredRows
    val showDefaultTab = SettingsRow.DEFAULT_TAB in host.honoredRows
    val showHaptics = SettingsRow.HAPTICS in host.honoredRows
    val showNetworkMode = SettingsRow.NETWORK_MODE in host.honoredRows && host.onNetworkModeChanged != null
    val scheduleOptions = if (SettingsRow.SCHEDULE_SOURCE in host.honoredRows) host.scheduleSourceOptions else emptyList()
    val showScheduleSource = scheduleOptions.isNotEmpty()
    val showScheduleAttendance = SettingsRow.SCHEDULE_ATTENDANCE in host.honoredRows
    val showVenueCaptcha = SettingsRow.VENUE_CAPTCHA in host.honoredRows

    var navBarStyle by remember { mutableStateOf(store.navBarStyle) }
    var darkMode by remember { mutableStateOf(store.darkMode) }
    var dynamicColor by remember { mutableStateOf(store.dynamicColor) }
    var defaultTab by remember { mutableStateOf(store.defaultTab) }
    var networkMode by remember { mutableStateOf(store.networkMode) }
    var theme by remember { mutableStateOf(store.homeTheme) }
    var showQuickActionsOn by remember { mutableStateOf(store.showQuickActions) }
    var attendanceBadge by remember { mutableStateOf(store.scheduleAttendanceBadge) }
    var scheduleSource by remember { mutableStateOf(store.scheduleSource) }
    var venueAutoSolveCaptcha by remember { mutableStateOf(store.venueAutoSolveCaptchaEnabled) }
    var showChangelog by remember { mutableStateOf(false) }
    var showEula by remember { mutableStateOf(false) }
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

    // 设置页低饱和多彩调色板
    val cPurple = Color(0xFF7E57C2)
    val cBlue = Color(0xFF42A5F5)
    val cTeal = Color(0xFF26A69A)
    val cIndigo = Color(0xFF5C6BC0)
    val cBlueGray = Color(0xFF78909C)
    val cGreen = Color(0xFF66BB6A)
    val cOrange = Color(0xFFFFA726)
    val cPink = Color(0xFFEC407A)
    val cLime = Color(0xFF9CCC65)

    val versionText = host.versionText

    // ── 选项数据 ──
    val darkModeOptions = listOf("跟随系统", "始终浅色", "始终深色")
    val darkModeValues = listOf(
        SettingsStore.DARK_MODE_SYSTEM,
        SettingsStore.DARK_MODE_LIGHT,
        SettingsStore.DARK_MODE_DARK
    )
    val themeOptions = listOf("卡片主题", "图标主题")
    val themeValues = listOf(
        SettingsStore.THEME_CARD,
        SettingsStore.THEME_ICON
    )
    val navStyleOptions = listOf("玻璃（默认）", "经典")
    val navStyleValues = listOf(
        SettingsStore.NAV_STYLE_FLOATING,
        SettingsStore.NAV_STYLE_CLASSIC
    )
    val tabOptions = listOf("首页", "日程", "屁岱", "学辅", "我的")
    val tabValues = listOf(
        SettingsStore.TAB_HOME,
        SettingsStore.TAB_COURSES,
        SettingsStore.TAB_PIDAI,
        SettingsStore.TAB_TOOLS,
        SettingsStore.TAB_PROFILE
    )
    val networkOptions = listOf("自动检测", "强制直连", "强制 WebVPN")
    val networkValues = listOf(
        SettingsStore.NETWORK_AUTO,
        SettingsStore.NETWORK_DIRECT,
        SettingsStore.NETWORK_VPN
    )

    // 玻璃顶栏（经典风格下为 null，一切照旧），用法见 ui/glass/GlassTopBar.kt
    val glass = rememberPageGlass()
    Scaffold(
        topBar = {
            TopAppBar(
                title = "设置",
                largeTitle = "设置",
                color = glassBarColor(glass),
                modifier = Modifier.glassTopBar(glass),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(MiuixIcons.Back, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        val glassTop = padding.glassTop(glass)

        // 各组的正文抽成一个 lambda（状态全在 SettingsScreen 函数体里，窄屏与宽屏捕获的是同一份）。
        // 哪些组合出现由上面那几个 `showXxx` 决定：**没列进 honoredRows 的行不画**，
        // 一组里一行都不剩就整组不画（连同它的小标题）。
        val appearanceGroup: @Composable () -> Unit = {
            SmallTitle("外观")
            SettingsCard {
                if (showDarkMode) {
                    OverlayDropdownPreference(
                        title = "深色模式",
                        items = darkModeOptions,
                        selectedIndex = darkModeValues.indexOf(darkMode).coerceAtLeast(0),
                        startAction = { SettingsIcon(Icons.Default.DarkMode, cPurple) },
                        onSelectedIndexChange = { idx ->
                            val v = darkModeValues[idx]
                            darkMode = v
                            store.darkMode = v
                            host.onAppearanceChanged()
                        }
                    )
                }
                if (showDynamicColor) {
                    SwitchPreference(
                        title = "跟随系统取色",
                        summary = if (dynamicColor) "主题色跟随壁纸与系统调色盘" else "已关闭，使用应用默认配色",
                        checked = dynamicColor,
                        startAction = { SettingsIcon(Icons.Default.Palette, cPink) },
                        onCheckedChange = { enabled ->
                            dynamicColor = enabled
                            store.dynamicColor = enabled
                            host.onAppearanceChanged()
                        }
                    )
                }
                if (showHomeTheme) {
                    OverlayDropdownPreference(
                        title = "主题",
                        items = themeOptions,
                        summary = if (theme == SettingsStore.THEME_CARD) "三段式卡片布局" else "分类宫格彩虹图标",
                        selectedIndex = themeValues.indexOf(theme).coerceAtLeast(0),
                        startAction = { SettingsIcon(MiuixIcons.Theme, cOrange) },
                        onSelectedIndexChange = { idx ->
                            val v = themeValues[idx]
                            theme = v
                            store.homeTheme = v
                            host.onAppearanceChanged()
                        }
                    )
                }
                if (showHomeTheme && showQuickActions && theme == SettingsStore.THEME_CARD) {
                    SwitchPreference(
                        title = "显示常用功能",
                        checked = showQuickActionsOn,
                        startAction = { SettingsIcon(Icons.Default.Star, cTeal) },
                        onCheckedChange = { v ->
                            showQuickActionsOn = v
                            store.showQuickActions = v
                            host.onAppearanceChanged()
                        }
                    )
                }
                if (showNavStyle) {
                    // 管所有玻璃（底栏、侧栏、气泡、顶栏），宽屏也显示
                    OverlayDropdownPreference(
                        title = "界面风格",
                        items = navStyleOptions,
                        selectedIndex = navStyleValues.indexOf(navBarStyle).coerceAtLeast(0),
                        startAction = { SettingsIcon(MiuixIcons.Carrier, cBlue) },
                        onSelectedIndexChange = { idx ->
                            val v = navStyleValues[idx]
                            navBarStyle = v
                            store.navBarStyle = v
                            host.onAppearanceChanged()
                        }
                    )
                }
            }
        }

        // 分组按「这一项管的是什么」来分，不按「它长什么样」：原来的「外观」里混着课表来源、
        // 课表考勤、触感和默认启动 Tab，「网络」「场馆」各只有孤零零一项，崩溃日志上报藏在「关于」里。
        val generalGroup: @Composable () -> Unit = {
            SmallTitle("通用")
            SettingsCard {
                if (showDefaultTab) {
                    OverlayDropdownPreference(
                        title = "默认启动 Tab",
                        items = tabOptions,
                        selectedIndex = tabValues.indexOf(defaultTab).coerceAtLeast(0),
                        startAction = { SettingsIcon(Icons.Default.Tab, cTeal) },
                        onSelectedIndexChange = { idx ->
                            val v = tabValues[idx]
                            defaultTab = v
                            store.defaultTab = v
                        }
                    )
                }
                // 触感开关自带偏好存储，不经 SettingsStore
                if (showHaptics) HapticsSettingItem()
                if (showNetworkMode) {
                    OverlayDropdownPreference(
                        title = "连接模式",
                        items = networkOptions,
                        selectedIndex = networkValues.indexOf(networkMode).coerceAtLeast(0),
                        startAction = { SettingsIcon(MiuixIcons.Carrier, cIndigo) },
                        onSelectedIndexChange = { idx ->
                            val v = networkValues[idx]
                            networkMode = v
                            store.networkMode = v
                            host.onNetworkModeChanged?.invoke()
                        }
                    )
                }
            }
        }

        // ── 功能：只对某一个功能生效的开关，按功能分小节 ──
        val scheduleRows = showScheduleSource || showScheduleAttendance
        val featuresGroup: @Composable () -> Unit = {
            if (scheduleRows) {
                SmallTitle("日程")
                SettingsCard {
                    if (showScheduleSource) {
                        OverlayDropdownPreference(
                            title = "当前学期课表来源",
                            items = scheduleOptions.map { it.label },
                            selectedIndex = scheduleOptions.indexOfFirst { it.key == scheduleSource }.coerceAtLeast(0),
                            startAction = { SettingsIcon(Icons.Default.CalendarMonth, cBlue) },
                            onSelectedIndexChange = { idx ->
                                val v = scheduleOptions[idx]
                                scheduleSource = v.key
                                store.scheduleSource = v.key
                            }
                        )
                    }
                    if (showScheduleAttendance) {
                        SwitchPreference(
                            title = "课表显示考勤",
                            checked = attendanceBadge,
                            startAction = { SettingsIcon(Icons.AutoMirrored.Filled.FactCheck, cGreen) },
                            onCheckedChange = {
                                attendanceBadge = it
                                store.scheduleAttendanceBadge = it
                            }
                        )
                    }
                }
            }
            if (showVenueCaptcha) {
                SmallTitle("场馆")
                SettingsCard {
                    SwitchPreference(
                        title = "自动识别场馆验证码",
                        checked = venueAutoSolveCaptcha,
                        startAction = { SettingsIcon(MiuixIcons.Settings, cIndigo) },
                        onCheckedChange = {
                            venueAutoSolveCaptcha = it
                            store.venueAutoSolveCaptchaEnabled = it
                        }
                    )
                }
            }
        }

        val aboutGroup: @Composable () -> Unit = {
            // ── 关于 ──
            SmallTitle("关于")
            SettingsCard {
                BasicComponent(
                    title = "版本号",
                    summary = versionText,
                    startAction = { SettingsIcon(MiuixIcons.Info, cGreen) }
                )
                if (host.changelog.isNotEmpty()) {
                    ArrowPreference(
                        title = "更新日志",
                        summary = "查看历史版本变化",
                        startAction = { SettingsIcon(MiuixIcons.Recent, cTeal) },
                        onClick = { showChangelog = true }
                    )
                }
                ArrowPreference(
                    title = "项目主页",
                    summary = "GitHub · yeliqin666/xjtu-toolbox-android",
                    startAction = { SettingsIcon(MiuixIcons.Forward, cBlue) },
                    onClick = { host.onOpenUrl("https://github.com/yeliqin666/xjtu-toolbox-android") }
                )
                ArrowPreference(
                    title = "用户协议与隐私政策",
                    startAction = { SettingsIcon(MiuixIcons.File, cPurple) },
                    onClick = { showEula = true }
                )
            }
        }

        val creditsGroup: @Composable () -> Unit = {
            // ── 致谢 ──
            SmallTitle("致谢")
            SettingsCard {
                ArrowPreference(
                    title = "XJTUToolBox by yan-xiaoo",
                    summary = "开源社区项目",
                    startAction = { SettingsIcon(MiuixIcons.Info, cLime) },
                    onClick = { host.onOpenUrl("https://github.com/yan-xiaoo/XJTUToolBox") }
                )
            }
            // 小游戏并入或参考的上游项目。完整的改动说明与许可证全文见仓库根目录的
            // THIRD_PARTY_NOTICES.md，这里只放跳转，避免在设置页里塞进几千字许可证。
            SmallTitle("小游戏的上游项目")
            SettingsCard {
                ArrowPreference(
                    title = "suika-game by moonfloof",
                    summary = "「合成西交大」的玩法与实现基础 · Unlicense",
                    startAction = { SettingsIcon(MiuixIcons.Info, cLime) },
                    onClick = { host.onOpenUrl("https://github.com/moonfloof/suika-game") }
                )
                ArrowPreference(
                    title = "Matter.js by liabru",
                    summary = "「合成西交大」的物理引擎 · MIT",
                    startAction = { SettingsIcon(MiuixIcons.Info, cLime) },
                    onClick = { host.onOpenUrl("https://github.com/liabru/matter-js") }
                )
                ArrowPreference(
                    title = "chinese-chess-fish-android by zfdang",
                    summary = "象棋规则引擎 · MIT",
                    startAction = { SettingsIcon(MiuixIcons.Info, cLime) },
                    onClick = { host.onOpenUrl("https://github.com/zfdang/chinese-chess-fish-android") }
                )
                ArrowPreference(
                    title = "Etoile by JoyinJoester",
                    summary = "社区讨论区与 GitHub 登录 · GPL-3.0",
                    startAction = { SettingsIcon(MiuixIcons.Info, cLime) },
                    onClick = { host.onOpenUrl("https://github.com/JoyinJoester/Etoile") }
                )
                ArrowPreference(
                    title = "blackstone by haslam22",
                    summary = "五子棋 AI 的思路来源（未并入代码）· MIT",
                    startAction = { SettingsIcon(MiuixIcons.Info, cLime) },
                    onClick = { host.onOpenUrl("https://github.com/haslam22/blackstone") }
                )
            }
        }

        // 组的顺序与标题（宽屏左栏就是这一列）。宿主没有那一组时整条不出现。
        val groups = buildList {
            if (showDarkMode || showDynamicColor || showHomeTheme || showQuickActions || showNavStyle) {
                add(SettingsGroup("外观", Icons.Default.Palette, cPurple, appearanceGroup))
            }
            if (showDefaultTab || showHaptics || showNetworkMode) {
                add(SettingsGroup("通用", Icons.Default.Tab, cTeal, generalGroup))
            }
            if (scheduleRows || showVenueCaptcha) {
                add(SettingsGroup("功能", Icons.Default.CalendarMonth, cBlue, featuresGroup))
            }
            host.noticeSection?.let {
                add(SettingsGroup("通知与提醒", Icons.Default.Notifications, cOrange, it))
            }
            host.dataPrivacySection?.let {
                add(SettingsGroup("数据与隐私", MiuixIcons.CloudFill, cBlueGray, it))
            }
            host.updateSection?.let {
                add(SettingsGroup("更新", Icons.Default.Star, cGreen, it))
            }
            add(SettingsGroup("关于", MiuixIcons.Info, cBlue, aboutGroup))
            add(SettingsGroup("致谢", Icons.Default.Star, cPurple, creditsGroup))
        }

        // 宽屏：左栏组名列表 + 右栏选中组。选中项 rememberSaveable，跨旋转不丢。
        var selectedGroup by rememberSaveable { mutableIntStateOf(0) }
        if (com.xjtu.toolbox.ui.isWideLayout()) {
            com.xjtu.toolbox.ui.adaptive.TwoPane(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MiuixTheme.colorScheme.surface)
                    .padding(padding.withoutTop(glass))
                    .glassSource(glass),
                listWidth = 240.dp,
                list = {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .overScrollVertical()
                            .verticalScroll(rememberScrollState())
                    ) {
                        Spacer(Modifier.height(glassTop + 8.dp))
                        SettingsCard {
                            groups.forEachIndexed { i, group ->
                                BasicComponent(
                                    title = group.title,
                                    startAction = { SettingsIcon(group.icon, group.color) },
                                    holdDownState = selectedGroup == i,
                                    onClick = { selectedGroup = i },
                                )
                            }
                        }
                        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                    }
                },
                detail = {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .nestedScroll(scrollBehavior.nestedScrollConnection)
                            .overScrollVertical()
                            .verticalScroll(rememberScrollState())
                    ) {
                        Spacer(Modifier.height(glassTop))
                        groups[selectedGroup.coerceIn(groups.indices)].body()
                        Spacer(Modifier.height(16.dp))
                        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                    }
                },
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // 采样源必须挂在滚动之前：挂在 verticalScroll 后面录下的是整条跟着滚的长内容，
                    // 不是屏幕上这块视口，顶栏按屏幕位置采样就对不上，只剩透明没有模糊
                    .glassSource(glass)
                    .background(MiuixTheme.colorScheme.surface)
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .overScrollVertical()
                    .verticalScroll(rememberScrollState())
                    .padding(padding.withoutTop(glass))
            ) {
                Spacer(Modifier.height(glassTop))
                groups.forEach { it.body() }

                Spacer(Modifier.height(16.dp))
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        }

        // ── 弹层（必须在 Scaffold 内，MIUIX MiuixPopupHost 才能渲染）──
        // 宿主那三组（通知与提醒 / 数据与隐私 / 更新）自己的弹层在各自的 section 里，
        // 它们渲染在上面那个组列表里 ⇒ 同样在 Scaffold 内。
        ChangelogSheet(show = showChangelog, entries = host.changelog, onDismiss = { showChangelog = false })
        EulaSheet(show = showEula, onDismiss = { showEula = false })
    }
}

/** 一组设置：宽屏左栏拿 [title]/[icon]/[color]，右栏拿 [body]。 */
private class SettingsGroup(
    val title: String,
    val icon: ImageVector,
    val color: Color,
    val body: @Composable () -> Unit,
)

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        cornerRadius = 16.dp,
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant)
    ) {
        Column(content = content)
    }
}

@Composable
private fun SettingsIcon(icon: ImageVector, color: Color) {
    Surface(
        shape = CircleShape,
        color = color.copy(alpha = 0.12f),
        modifier = Modifier.size(32.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = color)
        }
    }
    Spacer(Modifier.width(12.dp))
}

@Composable
private fun ChangelogSheet(show: Boolean, entries: List<ChangelogEntry>, onDismiss: () -> Unit) {
    BackHandler(enabled = show) { onDismiss() }
    OverlayBottomSheet(
        show = show,
        title = "更新日志",
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
        ) {
            entries.forEach { entry ->
                Text(
                    text = entry.version,
                    style = MiuixTheme.textStyles.subtitle,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                )
                entry.items.forEach { item ->
                    Text(
                        text = "· $item",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun EulaSheet(show: Boolean, onDismiss: () -> Unit) {
    BackHandler(enabled = show) { onDismiss() }
    OverlayBottomSheet(
        show = show,
        title = "用户协议与隐私政策",
        onDismissRequest = onDismiss
    ) {
        // 正文来自 legal/Eula.kt —— 和首次启动时要求同意的那一份是同一份。
        // 这里原来是另外手写的两段摘要，措辞和范围都跟首启页对不上，
        // 等于用户在设置里读到的不是他当初同意的东西。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
        ) {
            com.xjtu.toolbox.legal.Eula.Body()
            Spacer(Modifier.height(8.dp))
        }
    }
}
