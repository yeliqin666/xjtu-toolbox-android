package com.xjtu.toolbox.settings

import com.xjtu.toolbox.settings.SettingsColors.Blue as cBlue
import com.xjtu.toolbox.settings.SettingsColors.Green as cGreen
import com.xjtu.toolbox.settings.SettingsColors.Indigo as cIndigo
import com.xjtu.toolbox.settings.SettingsColors.Lime as cLime
import com.xjtu.toolbox.settings.SettingsColors.Orange as cOrange
import com.xjtu.toolbox.settings.SettingsColors.Pink as cPink
import com.xjtu.toolbox.settings.SettingsColors.Purple as cPurple
import com.xjtu.toolbox.settings.SettingsColors.Teal as cTeal
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material.icons.filled.Wallpaper
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Carrier
import top.yukonga.miuix.kmp.icon.extended.File
import top.yukonga.miuix.kmp.icon.extended.Forward
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Recent
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Theme
import com.xjtu.toolbox.ui.glass.tabContentBottomPadding
import com.xjtu.toolbox.home.BottomBarTabs
import com.xjtu.toolbox.ui.components.HeroAppearance
import com.xjtu.toolbox.ui.components.HeroAppearanceRevision
import com.xjtu.toolbox.ui.components.HomeHeroAppearanceDialog
import com.xjtu.toolbox.ui.components.JiaoxiaoxiAvatar
import com.xjtu.toolbox.ui.theme.LocalIsDarkTheme
import com.xjtu.toolbox.platform.BackHandler
import com.xjtu.toolbox.platform.copyOnLongPress
import com.xjtu.toolbox.platform.rememberCopyText
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.nav.AppPageMotion
import com.xjtu.toolbox.ui.HapticsSettingItem
import com.xjtu.toolbox.ui.glass.*
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.preference.ArrowPreference
import com.xjtu.toolbox.ui.components.AppDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * 设置屏。窄屏首页只列组名，点进去是该组的二级页；宽屏左栏组名、右栏组内容。
 * 二级页（组、致谢）叠在首页上滑入，返回时首页滚动位置不丢。
 */
@Composable
fun SettingsScreen(
    store: SettingsStore,
    host: SettingsHost,
    onBack: () -> Unit,
) {
    val showDarkMode = SettingsRow.DARK_MODE in host.honoredRows
    val showDynamicColor = SettingsRow.DYNAMIC_COLOR in host.honoredRows
    val showQuickActions = SettingsRow.QUICK_ACTIONS in host.honoredRows
    val showNavStyle = SettingsRow.NAV_STYLE in host.honoredRows
    val showHomeHero = SettingsRow.HOME_HERO in host.honoredRows
    val showDefaultTab = SettingsRow.DEFAULT_TAB in host.honoredRows
    val showBottomTabs = SettingsRow.BOTTOM_TABS in host.honoredRows
    val showHaptics = SettingsRow.HAPTICS in host.honoredRows
    val showNetworkMode = SettingsRow.NETWORK_MODE in host.honoredRows && host.onNetworkModeChanged != null
    val scheduleOptions = if (SettingsRow.SCHEDULE_SOURCE in host.honoredRows) host.scheduleSourceOptions else emptyList()
    val showScheduleSource = scheduleOptions.isNotEmpty()
    val showScheduleAttendance = SettingsRow.SCHEDULE_ATTENDANCE in host.honoredRows
    val launcherShortcuts = host.launcherShortcutPreference

    var navBarStyle by remember { mutableStateOf(store.navBarStyle) }
    var darkMode by remember { mutableStateOf(store.darkMode) }
    var dynamicColor by remember { mutableStateOf(store.dynamicColor) }
    var customColorEnabled by remember { mutableStateOf(store.customColorEnabled) }
    // 浅色 / 深色各存一份自选色，弹窗里分别设置
    var customLightHex by remember { mutableStateOf(store.customColorHex(dark = false)) }
    var customDarkHex by remember { mutableStateOf(store.customColorHex(dark = true)) }
    var showThemeColor by remember { mutableStateOf(false) }
    var showHomeHeroDialog by remember { mutableStateOf(false) }
    val accountType = com.xjtu.toolbox.account.AccountContext.activeAccountType
    var bottomTabs by remember { mutableStateOf(BottomBarTabs.barIds(store.bottomBar, accountType)) }
    var defaultTab by remember { mutableStateOf(BottomBarTabs.defaultTab(store.defaultTab, bottomTabs)) }
    var showBottomBarDialog by remember { mutableStateOf(false) }
    var networkMode by remember { mutableStateOf(store.networkMode) }
    var showQuickActionsOn by remember { mutableStateOf(store.showQuickActions) }
    var attendanceBadge by remember { mutableStateOf(store.scheduleAttendanceBadge) }
    var scheduleSource by remember { mutableStateOf(store.scheduleSource) }
    // 二级页：NO_PAGE、CREDITS_PAGE 或 groups 下标
    var page by rememberSaveable { mutableIntStateOf(NO_PAGE) }
    if (showThemeColor) {
        ThemeColorDialog(
            initialLightHex = customLightHex,
            initialDarkHex = customDarkHex,
            onDismiss = { showThemeColor = false },
            onConfirm = { light, dark ->
                customLightHex = light
                customDarkHex = dark
                customColorEnabled = true
                dynamicColor = false
                // 旧键（单色）跟着浅色走：万一被没升到这版的端读到，也还是用户挑过的那个色
                store.customColorHex = light
                store.setCustomColorHex(dark = false, value = light)
                store.setCustomColorHex(dark = true, value = dark)
                store.customColorEnabled = true
                store.dynamicColor = false
                host.onAppearanceChanged()
                showThemeColor = false
            },
        )
    }
    if (showHomeHeroDialog) {
        HomeHeroAppearanceDialog(store = store, onDismiss = { showHomeHeroDialog = false })
    }
    if (showBottomBarDialog) {
        BottomBarDialog(
            candidates = BottomBarTabs.candidates(accountType),
            initial = BottomBarTabs.middleOf(store.bottomBar, accountType),
            icon = host.serviceIcon,
            onDismiss = { showBottomBarDialog = false },
            onConfirm = { middle ->
                store.bottomBar = BottomBarTabs.encode(middle)
                bottomTabs = BottomBarTabs.barIds(store.bottomBar, accountType)
                defaultTab = BottomBarTabs.defaultTab(store.defaultTab, bottomTabs)
                showBottomBarDialog = false
            },
        )
    }

    val versionText = host.versionText

    // ── 选项数据 ──
    val darkModeOptions = listOf("跟随系统", "始终浅色", "始终深色")
    val darkModeValues = listOf(
        SettingsStore.DARK_MODE_SYSTEM,
        SettingsStore.DARK_MODE_LIGHT,
        SettingsStore.DARK_MODE_DARK
    )
    val navStyleOptions = listOf("玻璃（默认）", "经典")
    val navStyleValues = listOf(
        SettingsStore.NAV_STYLE_FLOATING,
        SettingsStore.NAV_STYLE_CLASSIC
    )
    // 启动页只能选底栏上有的格子
    val tabValues = bottomTabs
    val tabOptions = tabValues.map(BottomBarTabs::labelOf)
    val networkOptions = listOf("自动检测", "强制直连", "强制 WebVPN")
    val networkValues = listOf(
        SettingsStore.NETWORK_AUTO,
        SettingsStore.NETWORK_DIRECT,
        SettingsStore.NETWORK_VPN
    )

    // 外观一页分三段：主题（全局配色与质感）→ 首页 → 底栏
    val appearanceGroup: @Composable () -> Unit = {
        if (showDarkMode || showDynamicColor || showNavStyle) SmallTitle("主题")
        if (showDarkMode || showDynamicColor || showNavStyle) SettingsCard {
            if (showDarkMode) {
                AppDropdownPreference(
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
                AppDropdownPreference(
                    title = "主题取色",
                    items = listOf("默认配色", "跟随系统", "自定义颜色"),
                    selectedIndex = if (customColorEnabled) 2 else if (dynamicColor) 1 else 0,
                    startAction = { SettingsIcon(Icons.Default.Palette, cPink) },
                    onSelectedIndexChange = { idx ->
                        if (idx == 2 && customLightHex.isEmpty() && customDarkHex.isEmpty()) {
                            showThemeColor = true
                        } else {
                            customColorEnabled = idx == 2
                            dynamicColor = idx == 1
                            store.customColorEnabled = customColorEnabled
                            store.dynamicColor = dynamicColor
                            host.onAppearanceChanged()
                        }
                    },
                )
                if (customColorEnabled) {
                    ArrowPreference(
                        title = "自定义主题色",
                        summary = "浅色 $customLightHex · 深色 $customDarkHex",
                        startAction = {
                            // 两个色点：左浅右深，一眼看出两档各设了什么。
                            // 尺寸照 SettingsIcon 那套（32dp 内容 + 12dp 间隔），标题才和上下行对齐。
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf(customLightHex, customDarkHex).forEach { hex ->
                                    Box(
                                        Modifier.size(14.dp)
                                            .background(
                                                com.xjtu.toolbox.schedule.CourseColors.parseHex(hex) ?: cPink,
                                                CircleShape,
                                            )
                                            .border(
                                                1.dp,
                                                MiuixTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                                                CircleShape,
                                            )
                                    )
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                        },
                        onClick = { showThemeColor = true },
                    )
                }
            }
            if (showNavStyle) {
                // 管所有玻璃（底栏、侧栏、气泡、顶栏），宽屏也显示
                AppDropdownPreference(
                    title = "界面质感",
                    summary = "顶栏、底栏和气泡用玻璃还是实色",
                    items = navStyleOptions,
                    selectedIndex = navStyleValues.indexOf(navBarStyle).coerceAtLeast(0),
                    startAction = { SettingsIcon(Icons.Default.Layers, cBlue) },
                    onSelectedIndexChange = { idx ->
                        val v = navStyleValues[idx]
                        navBarStyle = v
                        store.navBarStyle = v
                        host.onAppearanceChanged()
                    }
                )
            }
        }
        if (showHomeHero) HomeSettingsBody(onDismiss = {})
        if (showHomeHero || showQuickActions) {
            SmallTitle("首页内容")
            SettingsCard {
                if (showHomeHero) {
                    val dark = LocalIsDarkTheme.current
                    val heroAppearance = remember(HeroAppearanceRevision.current, dark) {
                        HeroAppearance.read(store, dark)
                    }
                    ArrowPreference(
                        title = "今日卡片",
                        summary = heroAppearance.summary,
                        startAction = { SettingsIcon(Icons.Default.Wallpaper, cPink) },
                        onClick = { showHomeHeroDialog = true },
                    )
                }
                if (showQuickActions) {
                    SwitchPreference(
                        title = "常用功能",
                        summary = "按你的使用习惯，把最常点的几项排在首页最前面",
                        checked = showQuickActionsOn,
                        startAction = { SettingsIcon(Icons.Default.Star, cTeal) },
                        onCheckedChange = { v ->
                            showQuickActionsOn = v
                            store.showQuickActions = v
                            host.onAppearanceChanged()
                        }
                    )
                }
            }
        }
        if (showBottomTabs || showDefaultTab) {
            SmallTitle("底栏")
            SettingsCard {
                if (showBottomTabs) {
                    ArrowPreference(
                        title = "底栏放哪些页",
                        summary = tabOptions.joinToString(" · "),
                        startAction = { SettingsIcon(Icons.Default.Tab, cIndigo) },
                        onClick = { showBottomBarDialog = true },
                    )
                }
                if (showDefaultTab) {
                    AppDropdownPreference(
                        title = "打开应用时进入",
                        items = tabOptions,
                        selectedIndex = tabValues.indexOf(defaultTab).coerceAtLeast(0),
                        startAction = { SettingsIcon(Icons.Default.Home, cTeal) },
                        onSelectedIndexChange = { idx ->
                            val v = tabValues[idx]
                            defaultTab = v
                            store.defaultTab = v
                        }
                    )
                }
            }
        }
    }

    val generalRows = showHaptics || showNetworkMode || launcherShortcuts != null
    val scheduleRows = showScheduleSource || showScheduleAttendance
    // 通用一页：全局开关在前，只对某一个功能生效的按功能分小节
    val generalGroup: @Composable () -> Unit = {
        if (generalRows) {
            SmallTitle("通用")
            SettingsCard {
                // 触感开关自带偏好存储，不经 SettingsStore
                if (showHaptics) HapticsSettingItem()
                if (showNetworkMode) {
                    AppDropdownPreference(
                        title = "连接模式",
                        items = networkOptions,
                        selectedIndex = networkValues.indexOf(networkMode).coerceAtLeast(0),
                        startAction = { SettingsIcon(Icons.Default.Wifi, cIndigo) },
                        onSelectedIndexChange = { idx ->
                            val v = networkValues[idx]
                            networkMode = v
                            store.networkMode = v
                            host.onNetworkModeChanged?.invoke()
                        }
                    )
                }
                launcherShortcuts?.invoke()
            }
        }
        if (scheduleRows) {
            SmallTitle("日程")
            SettingsCard {
                if (showScheduleSource) {
                    AppDropdownPreference(
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
    }

    val aboutGroup: @Composable () -> Unit = {
        val copy = rememberCopyText()
        SmallTitle("关于")
        SettingsCard {
            BasicComponent(
                modifier = Modifier.copyOnLongPress(versionText, copy),
                title = "版本号",
                summary = versionText,
                startAction = { SettingsIcon(MiuixIcons.Info, cGreen) }
            )
            if (host.changelog.isNotEmpty()) {
                ArrowPreference(
                    title = "更新日志",
                    summary = "查看历史版本变化",
                    startAction = { SettingsIcon(MiuixIcons.Recent, cTeal) },
                    onClick = { page = CHANGELOG_PAGE }
                )
            }
            ArrowPreference(
                title = "项目主页",
                summary = "GitHub · yeliqin666/xjtu-toolbox-android",
                startAction = { SettingsIcon(MiuixIcons.Forward, cBlue) },
                onClick = { host.onOpenUrl("https://github.com/yeliqin666/xjtu-toolbox-android") }
            )
            ArrowPreference(
                title = CREDITS_TITLE,
                summary = "图标形象、贡献者与许可证",
                startAction = { SettingsIcon(Icons.Default.Star, cPurple) },
                onClick = { page = CREDITS_PAGE }
            )
            ArrowPreference(
                title = "用户协议与隐私政策",
                startAction = { SettingsIcon(MiuixIcons.File, cPurple) },
                onClick = { page = EULA_PAGE }
            )
        }
        host.updateSection?.invoke()
        host.dataPrivacySection?.invoke()
    }

    // 组的顺序与标题（窄屏首页、宽屏左栏都是这一列）。宿主没有那一组时整条不出现。
    val groups = buildList {
        if (showDarkMode || showDynamicColor || showNavStyle || showHomeHero || showQuickActions || showBottomTabs || showDefaultTab) {
            add(SettingsGroup("外观", Icons.Default.Palette, cPurple, appearanceGroup))
        }
        if (generalRows || scheduleRows) {
            add(SettingsGroup("通用", MiuixIcons.Settings, cTeal, generalGroup))
        }
        host.noticeSection?.let {
            add(SettingsGroup("通知与提醒", Icons.Default.Notifications, cOrange, it))
        }
        add(SettingsGroup("关于", MiuixIcons.Info, cBlue, aboutGroup))
    }

    // 「关于」里点进去的页（致谢、更新日志、用户协议）叠在「关于」上面：两层各滑各的，「关于」一直在底下，
    // 返回时滚动位置也还在。以前只有一层、直接换内容，那一层一直可见，AnimatedVisibility 不会触发（#356）
    val backToAbout = !com.xjtu.toolbox.ui.isWideLayout()
    val aboutChild = page in ABOUT_CHILD_PAGES
    val groupPage = when {
        page >= 0 -> page
        aboutChild && backToAbout -> groups.lastIndex
        else -> NO_PAGE
    }
    // 退场滑出时 page 已经换掉，画的仍是刚才那页
    var shownAboutChild by remember { mutableIntStateOf(CREDITS_PAGE) }
    if (aboutChild) shownAboutChild = page
    // 退场滑出时 groupPage 已回 NO_PAGE，画的仍是刚才那页
    var shownGroup by remember { mutableIntStateOf(NO_PAGE) }
    if (groupPage != NO_PAGE) shownGroup = groupPage
    // 和压栈打开子页同一套转场（AppPageMotion）：新页从右侧整屏滑入，
    // 被盖住的设置页向左让出 1/4 屏并轻微压暗，与「我的 → 设置」一致。
    val cover by animateFloatAsState(
        targetValue = if (page != NO_PAGE) 1f else 0f,
        animationSpec = tween(AppPageMotion.DurationMillis, easing = AppPageMotion.Easing),
        label = "subPageCover",
    )
    val creditsCover by animateFloatAsState(
        targetValue = if (aboutChild && backToAbout) 1f else 0f,
        animationSpec = tween(AppPageMotion.DurationMillis, easing = AppPageMotion.Easing),
        label = "creditsCover",
    )
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(Modifier.fillMaxSize()) {
        SettingsHome(
            groups, onBack,
            onOpenGroup = { page = it },
            modifier = Modifier.graphicsLayer {
                translationX = (if (rtl) 1f else -1f) * cover * size.width * 0.25f
                alpha = 1f - 0.1f * cover
            },
        )
        AnimatedVisibility(
            visible = groupPage != NO_PAGE,
            enter = slideInHorizontally(
                tween(AppPageMotion.DurationMillis, easing = AppPageMotion.Easing),
            ) { if (rtl) -it else it },
            exit = slideOutHorizontally(
                tween(AppPageMotion.DurationMillis, easing = AppPageMotion.Easing),
            ) { if (rtl) -it else it },
        ) {
            BackHandler(enabled = page >= 0) { page = NO_PAGE }
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    translationX = (if (rtl) 1f else -1f) * creditsCover * size.width * 0.25f
                    alpha = 1f - 0.1f * creditsCover
                },
            ) {
                groups.getOrNull(shownGroup)?.let { group ->
                    SettingsSubPage(title = group.title, onBack = { page = NO_PAGE }) { group.body() }
                }
            }
        }
        AnimatedVisibility(
            visible = aboutChild,
            enter = slideInHorizontally(
                tween(AppPageMotion.DurationMillis, easing = AppPageMotion.Easing),
            ) { if (rtl) -it else it },
            exit = slideOutHorizontally(
                tween(AppPageMotion.DurationMillis, easing = AppPageMotion.Easing),
            ) { if (rtl) -it else it },
        ) {
            // 窄屏是从「关于」二级页进来的，返回时退回「关于」
            val close = { page = if (backToAbout) groups.lastIndex else NO_PAGE }
            BackHandler(enabled = aboutChild) { close() }
            when (shownAboutChild) {
                CHANGELOG_PAGE -> SettingsSubPage(title = "更新日志", onBack = close) { ChangelogBody(host.changelog) }
                EULA_PAGE -> SettingsSubPage(title = "用户协议与隐私政策", onBack = close) {
                    com.xjtu.toolbox.legal.Eula.Body(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
                else -> CreditsScreen(host = host, onBack = close)
            }
        }
    }
}

@Composable
private fun SettingsHome(
    groups: List<SettingsGroup>,
    onBack: () -> Unit,
    onOpenGroup: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    // 玻璃顶栏（经典风格下为 null，一切照旧），用法见 ui/glass/GlassTopBar.kt
    val glass = rememberPageGlass()
    Scaffold(
        modifier = modifier,
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
                            .verticalScroll(rememberScrollState()).tabContentBottomPadding()
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
                            .verticalScroll(rememberScrollState()).tabContentBottomPadding()
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
                    .verticalScroll(rememberScrollState()).tabContentBottomPadding()
                    .padding(padding.withoutTop(glass))
            ) {
                Spacer(Modifier.height(glassTop + 8.dp))
                SettingsCard {
                    groups.forEachIndexed { i, group ->
                        ArrowPreference(
                            title = group.title,
                            startAction = { SettingsIcon(group.icon, group.color) },
                            onClick = { onOpenGroup(i) },
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        }
    }
}

/** 二级页外壳：组页与致谢页共用的顶栏、玻璃与滚动。 */
@Composable
private fun SettingsSubPage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val glass = rememberPageGlass()
    Scaffold(
        topBar = {
            TopAppBar(
                title = title,
                largeTitle = title,
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .glassSource(glass)
                .background(MiuixTheme.colorScheme.surface)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .overScrollVertical()
                .verticalScroll(rememberScrollState())
                .padding(padding.withoutTop(glass))
        ) {
            Spacer(Modifier.height(padding.glassTop(glass)))
            content()
            Spacer(Modifier.height(16.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

private const val NO_PAGE = -1
private const val CREDITS_PAGE = -2
private const val CHANGELOG_PAGE = -3
private const val EULA_PAGE = -4

/** 从「关于」点进去、叠在它上面的那几页。 */
private val ABOUT_CHILD_PAGES = setOf(CREDITS_PAGE, CHANGELOG_PAGE, EULA_PAGE)

/** 一组设置：组名列表拿 [title]/[icon]/[color]，组内容拿 [body]。 */
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

/** 更新日志页的正文：每个版本一张卡，版本号作卡头。 */
@Composable
private fun ChangelogBody(entries: List<ChangelogEntry>) {
    entries.forEach { entry ->
        SmallTitle(entry.version)
        SettingsCard {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                entry.items.forEach { item ->
                    Text(
                        text = "· $item",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }
    }
}

private const val CREDITS_TITLE = "致谢与开源许可"

/** 致谢二级页：贡献者、许可证与上游项目。 */
@Composable
private fun CreditsScreen(host: SettingsHost, onBack: () -> Unit) {
    SettingsSubPage(title = CREDITS_TITLE, onBack = onBack) {
        // 交小西制作组的授权要求：版权归属与「非制作组出品」两句必须同时可见，
        // 文案不要删减，出处见 THIRD_PARTY_NOTICES.md「美术素材」。
        SmallTitle("图标形象")
        SettingsCard {
            BasicComponent(
                title = "交小西",
                summary = "图标形象版权归交小西制作组所有",
                startAction = {
                    JiaoxiaoxiAvatar(Modifier.size(32.dp))
                    Spacer(Modifier.width(12.dp))
                }
            )
            ArrowPreference(
                title = "B 站主页",
                summary = "space.bilibili.com/3494379805215567",
                startAction = { SettingsIcon(MiuixIcons.Forward, cBlue) },
                onClick = { host.onOpenUrl("https://space.bilibili.com/3494379805215567") }
            )
        }
        Text(
            text = "已获得交小西制作组授权使用该形象。本应用为开源项目，不由交小西制作组出品。",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 6.dp)
        )
        SmallTitle("贡献者")
        SettingsCard {
            ArrowPreference(
                title = "项目贡献者",
                summary = "GitHub 上的贡献者列表",
                startAction = { SettingsIcon(MiuixIcons.Forward, cBlue) },
                onClick = { host.onOpenUrl("https://github.com/yeliqin666/xjtu-toolbox-android/graphs/contributors") }
            )
        }
        SmallTitle("许可证")
        SettingsCard {
            ArrowPreference(
                title = "岱宗盒子",
                summary = "GPL-3.0",
                startAction = { SettingsIcon(MiuixIcons.File, cPurple) },
                onClick = { host.onOpenUrl("https://github.com/yeliqin666/xjtu-toolbox-android/blob/main/LICENSE") }
            )
            ArrowPreference(
                title = "第三方声明",
                summary = "上游项目的改动说明与许可证全文",
                startAction = { SettingsIcon(MiuixIcons.File, cPurple) },
                onClick = { host.onOpenUrl("https://github.com/yeliqin666/xjtu-toolbox-android/blob/main/THIRD_PARTY_NOTICES.md") }
            )
        }
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
}
