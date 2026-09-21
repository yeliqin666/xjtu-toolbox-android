package com.xjtu.toolbox.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Notifications
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.EventSeat
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Palette
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Theme
import top.yukonga.miuix.kmp.icon.extended.Carrier
import top.yukonga.miuix.kmp.icon.extended.CloudFill
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.icon.extended.Update
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Recent
import top.yukonga.miuix.kmp.icon.extended.Forward
import top.yukonga.miuix.kmp.icon.extended.File
import top.yukonga.miuix.kmp.icon.extended.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.BuildConfig
import com.xjtu.toolbox.AutoUpdateDialog
import com.xjtu.toolbox.util.AppUpdateInfo
import com.xjtu.toolbox.util.AppUpdater
import com.xjtu.toolbox.notification.LibraryReminderScheduler
import com.xjtu.toolbox.notification.LmsDeadlineScheduler
import com.xjtu.toolbox.notification.NoticeWatchScheduler
import com.xjtu.toolbox.notification.NoticeWatchStore
import com.xjtu.toolbox.notification.ReminderKind
import com.xjtu.toolbox.notification.ReminderStore
import com.xjtu.toolbox.notification.ScheduleWatchScheduler
import com.xjtu.toolbox.notification.NoticeWatchSync
import com.xjtu.toolbox.notification.NotificationSource
import com.xjtu.toolbox.notification.SourceCategory
import com.xjtu.toolbox.ui.components.AppFilterChip
import com.xjtu.toolbox.ui.glass.*
import com.xjtu.toolbox.util.CredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
fun SettingsScreen(
    credentialStore: CredentialStore,
    onBack: () -> Unit,
    onNavBarStyleChanged: (String) -> Unit = {},
    onDarkModeChanged: (String) -> Unit = {},
    onDynamicColorChanged: (Boolean) -> Unit = {},
    onDefaultTabChanged: (String) -> Unit = {},
    homeTheme: String = CredentialStore.THEME_CARD,
    onHomeThemeChanged: (String) -> Unit = {},
    showQuickActions: Boolean = true,
    onShowQuickActionsChanged: (Boolean) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    var navBarStyle by remember { mutableStateOf(credentialStore.navBarStyle) }
    var darkMode by remember { mutableStateOf(credentialStore.darkMode) }
    var dynamicColor by remember { mutableStateOf(credentialStore.dynamicColor) }
    var defaultTab by remember { mutableStateOf(credentialStore.defaultTab) }
    var networkMode by remember { mutableStateOf(credentialStore.networkMode) }
    var updateChannel by remember { mutableStateOf(credentialStore.updateChannel) }
    var receivePreviewUpdates by remember { mutableStateOf(credentialStore.receivePreviewUpdates) }
    var showPreviewConfirmDialog by remember { mutableStateOf(false) }
    var venueAutoSolveCaptcha by remember { mutableStateOf(credentialStore.venueAutoSolveCaptchaEnabled) }
    var theme by remember { mutableStateOf(homeTheme) }
    var cacheSizeText by remember { mutableStateOf("计算中...") }
    var showChangelog by remember { mutableStateOf(false) }
    var showEula by remember { mutableStateOf(false) }
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var pendingUpdate by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var noticeWatchEnabled by remember { mutableStateOf(NoticeWatchStore.isEnabled(context)) }
    var noticeWatchSummary by remember { mutableStateOf(NoticeWatchStore.sourceSummary(context)) }
    var showNoticeSources by remember { mutableStateOf(false) }
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

    fun applyNoticeWatch(enabled: Boolean = noticeWatchEnabled) {
        NoticeWatchStore.setEnabled(context, enabled)
        noticeWatchEnabled = enabled
        NoticeWatchScheduler.apply(context)
        if (!enabled) return
        scope.launch(Dispatchers.IO) {
            NoticeWatchSync.sync(context, notify = true, force = true)
            withContext(Dispatchers.Main) {
                noticeWatchSummary = NoticeWatchStore.sourceSummary(context)
            }
        }
    }

    var reminderLibrary by remember { mutableStateOf(ReminderStore.isEnabled(context, ReminderKind.LIBRARY)) }
    var reminderSchedule by remember { mutableStateOf(ReminderStore.isEnabled(context, ReminderKind.SCHEDULE)) }
    var reminderLms by remember { mutableStateOf(ReminderStore.isEnabled(context, ReminderKind.LMS)) }
    /** 打开某类提醒后待补的通知权限申请；授权回调里再落盘排程。 */
    var pendingReminder by remember { mutableStateOf<ReminderKind?>(null) }

    fun commitReminder(kind: ReminderKind, enabled: Boolean) {
        ReminderStore.setEnabled(context, kind, enabled)
        when (kind) {
            ReminderKind.LIBRARY -> {
                reminderLibrary = enabled
                // 关掉就把已排的一次性任务撤了；开着时等图书馆页下次拿到预约状态再排。
                if (!enabled) LibraryReminderScheduler.sync(context, null)
            }
            ReminderKind.SCHEDULE -> {
                reminderSchedule = enabled
                ScheduleWatchScheduler.apply(context)
            }
            ReminderKind.LMS -> {
                reminderLms = enabled
                LmsDeadlineScheduler.apply(context)
            }
        }
    }

    val reminderPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val kind = pendingReminder ?: return@rememberLauncherForActivityResult
        pendingReminder = null
        commitReminder(kind, true)
        if (!granted) {
            Toast.makeText(context, "未授予通知权限，提醒发不出来", Toast.LENGTH_SHORT).show()
        }
    }

    /** 开提醒前先问通知权限：这几项除了发通知没有别的表现形式，没权限等于开了个寂寞。 */
    fun applyReminder(kind: ReminderKind, enabled: Boolean) {
        if (!enabled) {
            commitReminder(kind, false)
            return
        }
        val needAsk = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        if (needAsk) {
            pendingReminder = kind
            reminderPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            commitReminder(kind, true)
        }
    }

    val noticePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        applyNoticeWatch(enabled = true)
        if (!granted) {
            Toast.makeText(
                context,
                "未授予通知权限。小组件仍会更新，但不会弹出系统通知",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            cacheSizeText = runCatching {
                context.cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            }.map(::formatFileSize).getOrDefault("无法获取")
        }
    }

    // 设置页低饱和多彩调色板
    val cPurple = Color(0xFF7E57C2)
    val cBlue = Color(0xFF42A5F5)
    val cTeal = Color(0xFF26A69A)
    val cIndigo = Color(0xFF5C6BC0)
    val cBlueGray = Color(0xFF78909C)
    val cRed = Color(0xFFEF5350)
    val cBrown = Color(0xFFA1887F)
    val cOrange = Color(0xFFFFA726)
    val cDeepOrange = Color(0xFFFF7043)
    val cGreen = Color(0xFF66BB6A)
    val cPink = Color(0xFFEC407A)
    val cLime = Color(0xFF9CCC65)

    val versionText = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})${if (BuildConfig.IS_PREVIEW) "（预览版）" else ""}"
    // ── 选项数据 ──
    val darkModeOptions = listOf("跟随系统", "始终浅色", "始终深色")
    val darkModeValues = listOf(
        CredentialStore.DARK_MODE_SYSTEM,
        CredentialStore.DARK_MODE_LIGHT,
        CredentialStore.DARK_MODE_DARK
    )
    val themeOptions = listOf("卡片主题", "图标主题")
    val themeValues = listOf(
        CredentialStore.THEME_CARD,
        CredentialStore.THEME_ICON
    )
    var attendanceBadge by remember { mutableStateOf(credentialStore.scheduleAttendanceBadge) }
    var crashReportEnabled by remember { mutableStateOf(com.xjtu.toolbox.error.CrashReporter.isEnabled(context)) }
    val scheduleSources = com.xjtu.toolbox.schedule.ScheduleSource.entries
    var scheduleSource by remember { mutableStateOf(com.xjtu.toolbox.schedule.ScheduleSource.fromKey(credentialStore.scheduleSource)) }
    val navStyleOptions = listOf("玻璃（默认）", "经典")
    val navStyleValues = listOf(
        CredentialStore.NAV_STYLE_FLOATING,
        CredentialStore.NAV_STYLE_CLASSIC
    )
    val tabOptions = listOf("首页", "日程", "屁岱", "学辅", "我的")
    val tabValues = listOf(
        CredentialStore.TAB_HOME,
        CredentialStore.TAB_COURSES,
        CredentialStore.TAB_PIDAI,
        CredentialStore.TAB_TOOLS,
        CredentialStore.TAB_PROFILE
    )
    val networkOptions = listOf("自动检测", "强制直连", "强制 WebVPN")
    val networkValues = listOf(
        CredentialStore.NETWORK_AUTO,
        CredentialStore.NETWORK_DIRECT,
        CredentialStore.NETWORK_VPN
    )
    val channelOptions = AppUpdater.channelLabels
    val channelValues = AppUpdater.channelKeys

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

        // 八组设置各抽成一个 lambda。状态全在 SettingsScreen 函数体里，
        // 两种布局捕获的是同一份，弹窗也只有一份（都在下面 Scaffold 的内容层）。
        // 窄屏按顺序依次渲染，宽屏左栏是组名、右栏是选中的那一组。
        val settingsGroup0: @Composable () -> Unit = {
            // ── 外观 ──
            SmallTitle("外观")
            SettingsCard {
                OverlayDropdownPreference(
                    title = "深色模式",
                    items = darkModeOptions,
                    selectedIndex = darkModeValues.indexOf(darkMode).coerceAtLeast(0),
                    startAction = { SettingsIcon(Icons.Default.DarkMode, cPurple) },
                    onSelectedIndexChange = { idx ->
                        val v = darkModeValues[idx]
                        darkMode = v
                        credentialStore.darkMode = v
                        onDarkModeChanged(v)
                    }
                )
                SwitchPreference(
                    title = "跟随系统取色",
                    summary = if (dynamicColor) "主题色跟随壁纸与系统调色盘" else "已关闭，使用应用默认配色",
                    checked = dynamicColor,
                    startAction = { SettingsIcon(Icons.Default.Palette, cPink) },
                    onCheckedChange = { enabled ->
                        dynamicColor = enabled
                        credentialStore.dynamicColor = enabled
                        onDynamicColorChanged(enabled)
                    }
                )
                OverlayDropdownPreference(
                    title = "主题",
                    items = themeOptions,
                    summary = if (theme == CredentialStore.THEME_CARD) "三段式卡片布局" else "分类宫格彩虹图标",
                    selectedIndex = themeValues.indexOf(theme).coerceAtLeast(0),
                    startAction = { SettingsIcon(MiuixIcons.Theme, cOrange) },
                    onSelectedIndexChange = { idx ->
                        val v = themeValues[idx]
                        theme = v
                        onHomeThemeChanged(v)
                    }
                )
                if (theme == CredentialStore.THEME_CARD) {
                    SwitchPreference(
                        title = "显示常用功能",
                        summary = if (showQuickActions) "在首页显示智能推荐的 4 个常用入口" else "已隐藏",
                        checked = showQuickActions,
                        startAction = { SettingsIcon(Icons.Default.Star, cTeal) },
                        onCheckedChange = onShowQuickActionsChanged
                    )
                }
                // 原来叫「底栏风格」，宽屏没有底栏就藏起来。现在它管的是所有玻璃点
                // （侧栏、气泡、搜索浮层、二级页顶栏也在内），宽屏同样有用，所以一直显示。
                run {
                    OverlayDropdownPreference(
                        title = "界面风格",
                        summary = if (navBarStyle == CredentialStore.NAV_STYLE_CLASSIC) {
                            "不透明的经典样式，更省电"
                        } else {
                            "液态玻璃：手机上是可以拖动的玻璃胶囊底栏；经典样式更省电"
                        },
                        items = navStyleOptions,
                        selectedIndex = navStyleValues.indexOf(navBarStyle).coerceAtLeast(0),
                        startAction = { SettingsIcon(MiuixIcons.Carrier, cBlue) },
                        onSelectedIndexChange = { idx ->
                            val v = navStyleValues[idx]
                            navBarStyle = v
                            credentialStore.navBarStyle = v
                            onNavBarStyleChanged(v)
                        }
                    )
                }
            }
        }
        // 分组按「这一项管的是什么」来分，不按「它长什么样」：原来的「外观」里混着课表来源、
        // 课表考勤、触感和默认启动 Tab，「网络」「场馆」各只有孤零零一项，崩溃日志上报藏在「关于」里。
        val settingsGroup1: @Composable () -> Unit = {
            // ── 通用：打开 App 以后怎么用，不属于哪一个功能 ──
            SmallTitle("通用")
            SettingsCard {
                OverlayDropdownPreference(
                    title = "默认启动 Tab",
                    items = tabOptions,
                    selectedIndex = tabValues.indexOf(defaultTab).coerceAtLeast(0),
                    startAction = { SettingsIcon(Icons.Default.Tab, cTeal) },
                    onSelectedIndexChange = { idx ->
                        val v = tabValues[idx]
                        defaultTab = v
                        credentialStore.defaultTab = v
                        onDefaultTabChanged(v)
                    }
                )
                // 触感开关自带偏好存储，不经 CredentialStore
                com.xjtu.toolbox.ui.HapticsSettingItem()
                OverlayDropdownPreference(
                    title = "连接模式",
                    items = networkOptions,
                    selectedIndex = networkValues.indexOf(networkMode).coerceAtLeast(0),
                    startAction = { SettingsIcon(MiuixIcons.Carrier, cIndigo) },
                    onSelectedIndexChange = { idx ->
                        val v = networkValues[idx]
                        networkMode = v
                        credentialStore.networkMode = v
                    }
                )
            }
        }
        val settingsGroup2: @Composable () -> Unit = {
            // ── 功能：只对某一个功能生效的开关，按功能分小节 ──
            SmallTitle("日程")
            SettingsCard {
                OverlayDropdownPreference(
                    title = "当前学期课表来源",
                    summary = "${scheduleSource.label} · ${scheduleSource.summary}。历史学期始终查教务，选的来源取不到时也自动退回教务",
                    items = scheduleSources.map { it.label },
                    selectedIndex = scheduleSources.indexOf(scheduleSource).coerceAtLeast(0),
                    startAction = { SettingsIcon(Icons.Default.CloudSync, cBlue) },
                    onSelectedIndexChange = { idx ->
                        val v = scheduleSources[idx]
                        scheduleSource = v
                        credentialStore.scheduleSource = v.key
                    }
                )
                SwitchPreference(
                    title = "课表显示考勤",
                    // 说清代价，因为它确实有代价：多一次登录、多一次请求。
                    summary = if (attendanceBadge) {
                        "周视图标出迟到/缺勤/请假，课程详情显示本课出勤"
                    } else {
                        "需额外登录考勤系统，已关闭"
                    },
                    checked = attendanceBadge,
                    startAction = { SettingsIcon(Icons.Default.FactCheck, cGreen) },
                    onCheckedChange = {
                        attendanceBadge = it
                        credentialStore.scheduleAttendanceBadge = it
                    }
                )
            }
            SmallTitle("场馆")
            SettingsCard {
                SwitchPreference(
                    title = "自动识别场馆验证码",
                    summary = if (venueAutoSolveCaptcha) {
                        "预约时先尝试自动识别，失败后可手动滑动"
                    } else {
                        "已关闭，预约时始终手动滑动"
                    },
                    checked = venueAutoSolveCaptcha,
                    startAction = { SettingsIcon(MiuixIcons.Settings, cIndigo) },
                    onCheckedChange = {
                        venueAutoSolveCaptcha = it
                        credentialStore.venueAutoSolveCaptchaEnabled = it
                    }
                )
            }
        }
        val settingsGroup3: @Composable () -> Unit = {
            // ── 通知与提醒：会在系统通知栏冒出来的东西都在这里 ──
            SmallTitle("教务通知")
            SettingsCard {
                SwitchPreference(
                    title = "新通知提醒",
                    summary = if (noticeWatchEnabled) {
                        "有新通知时在系统通知栏提示。后台按省电策略每隔数小时检查，电量低或没网会推迟"
                    } else {
                        "关闭后小组件不再自动更新，也不会弹出通知"
                    },
                    checked = noticeWatchEnabled,
                    startAction = { SettingsIcon(Icons.Default.Notifications, cIndigo) },
                    onCheckedChange = { on ->
                        if (on) {
                            val needAsk = Build.VERSION.SDK_INT >= 33 &&
                                ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.POST_NOTIFICATIONS
                                ) != PackageManager.PERMISSION_GRANTED
                            if (needAsk) {
                                noticePermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                applyNoticeWatch(enabled = true)
                            }
                        } else {
                            applyNoticeWatch(enabled = false)
                        }
                    }
                )
                ArrowPreference(
                    title = "推送来源",
                    summary = "$noticeWatchSummary · 小组件与提醒共用",
                    startAction = { SettingsIcon(MiuixIcons.Folder, cTeal) },
                    onClick = { showNoticeSources = true }
                )
            }
            SmallTitle("后台提醒")
            SettingsCard {
                SwitchPreference(
                    title = ReminderKind.LIBRARY.title,
                    summary = if (reminderLibrary) {
                        "预约后快到签到时限、以及中途离开后还没返座时提醒"
                    } else {
                        "需在后台登录图书馆查预约状态，默认关闭"
                    },
                    checked = reminderLibrary,
                    startAction = { SettingsIcon(Icons.Default.EventSeat, cOrange) },
                    onCheckedChange = { on -> applyReminder(ReminderKind.LIBRARY, on) }
                )
                SwitchPreference(
                    title = ReminderKind.SCHEDULE.title,
                    summary = if (reminderSchedule) {
                        "课被调了、停了，或考试临近 3 天时提醒"
                    } else {
                        "需在后台登录教务系统，默认关闭"
                    },
                    checked = reminderSchedule,
                    startAction = { SettingsIcon(Icons.Default.CalendarMonth, cPurple) },
                    onCheckedChange = { on -> applyReminder(ReminderKind.SCHEDULE, on) }
                )
                SwitchPreference(
                    title = ReminderKind.LMS.title,
                    summary = if (reminderLms) {
                        "作业距截止不到 48 小时且还没提交时提醒一次"
                    } else {
                        "需在后台登录思源学堂逐课查作业，默认关闭"
                    },
                    checked = reminderLms,
                    startAction = { SettingsIcon(Icons.Default.Assignment, cGreen) },
                    onCheckedChange = { on -> applyReminder(ReminderKind.LMS, on) }
                )
                ArrowPreference(
                    title = "提醒不准时？",
                    summary = "后台检查按系统省电策略排队，厂商省电模式下可能被推迟很久。" +
                        "点这里到系统设置里把本应用设为不受限制，可提高送达率。不改也能用",
                    startAction = { SettingsIcon(Icons.Default.BatteryAlert, cBlueGray) },
                    onClick = {
                        // 只跳系统的电池优化列表，不申请 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                        // 直接弹窗：那个权限要在清单里声明，且上架审核会追问用途。
                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        val opened = runCatching { context.startActivity(intent) }.isSuccess
                        if (!opened) {
                            Toast.makeText(context, "这台设备没有电池优化设置页", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
        }
        val settingsGroup4: @Composable () -> Unit = {
            // ── 数据与隐私：本机存了什么、往外发了什么 ──
            SmallTitle("数据与隐私")
            SettingsCard {
                BasicComponent(
                    title = "缓存大小",
                    summary = cacheSizeText,
                    startAction = { SettingsIcon(MiuixIcons.CloudFill, cBlueGray) }
                )
                ArrowPreference(
                    title = "清除缓存",
                    summary = "清除临时文件和图片缓存，不影响登录与下载文件",
                    startAction = { SettingsIcon(MiuixIcons.Delete, cRed) },
                    onClick = { showClearCacheDialog = true }
                )
                SwitchPreference(
                    title = "自动上报崩溃日志",
                    summary = if (crashReportEnabled) {
                        "闪退后下次启动匿名上报堆栈与机型，已去除网址参数、学号等"
                    } else {
                        "已关闭，闪退只能靠你手动反馈"
                    },
                    checked = crashReportEnabled,
                    startAction = { SettingsIcon(Icons.Default.BugReport, cRed) },
                    onCheckedChange = {
                        crashReportEnabled = it
                        com.xjtu.toolbox.error.CrashReporter.setEnabled(context, it)
                    }
                )
            }
        }
        val settingsGroup5: @Composable () -> Unit = {
            // ── 更新 ──
            SmallTitle("更新")
            SettingsCard {
                OverlayDropdownPreference(
                    title = "更新渠道",
                    items = channelOptions,
                    selectedIndex = channelValues.indexOf(updateChannel).coerceAtLeast(0),
                    summary = "当前：${AppUpdater.channelLabel(updateChannel)}",
                    startAction = { SettingsIcon(MiuixIcons.Settings, cDeepOrange) },
                    onSelectedIndexChange = { idx ->
                        val v = channelValues[idx]
                        updateChannel = v
                        credentialStore.updateChannel = v
                    }
                )
                SwitchPreference(
                    title = "接收预览版更新",
                    summary = when {
                        !receivePreviewUpdates -> "只接收正式版"
                        updateChannel == AppUpdater.CHANNEL_GITHUB -> "会分批收到尚在测试的新版本，可能不稳定"
                        else -> "预览版只在 GitHub 发布，请把更新通道切到 GitHub"
                    },
                    checked = receivePreviewUpdates,
                    startAction = { SettingsIcon(Icons.Default.CloudSync, cPink) },
                    onCheckedChange = { checked ->
                        if (checked) {
                            showPreviewConfirmDialog = true
                        } else {
                            receivePreviewUpdates = false
                            credentialStore.receivePreviewUpdates = false
                        }
                    }
                )
                var checkingUpdate by remember { mutableStateOf(false) }
                ArrowPreference(
                    title = "立即检查更新",
                    summary = if (checkingUpdate) "正在检查..."
                    else if (receivePreviewUpdates && updateChannel == AppUpdater.CHANNEL_GITHUB) "手动从 GitHub 拉取最新版本（含预览）"
                    else "手动从 ${AppUpdater.channelLabel(updateChannel)} 拉取最新版本",
                    startAction = { SettingsIcon(Icons.Default.Refresh, cTeal) },
                    onClick = {
                        if (checkingUpdate) return@ArrowPreference
                        checkingUpdate = true
                        scope.launch {
                            val result = runCatching {
                                AppUpdater.check(
                                    channel = updateChannel,
                                    includePreview = receivePreviewUpdates,
                                    rolloutId = credentialStore.rolloutId,
                                )
                            }
                            checkingUpdate = false
                            result.fold(
                                onSuccess = { update ->
                                    if (update != null) {
                                        pendingUpdate = update
                                    } else {
                                        Toast.makeText(
                                            context,
                                            "已是最新版本 v${BuildConfig.VERSION_NAME}",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                },
                                onFailure = {
                                    Toast.makeText(context, "检查失败：${it.message}", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                )
            }
        }
        val settingsGroup6: @Composable () -> Unit = {
            // ── 关于 ──
            SmallTitle("关于")
            SettingsCard {
                BasicComponent(
                    title = "版本号",
                    summary = versionText,
                    startAction = { SettingsIcon(MiuixIcons.Info, cGreen) }
                )
                ArrowPreference(
                    title = "更新日志",
                    summary = "查看历史版本变化",
                    startAction = { SettingsIcon(MiuixIcons.Recent, cTeal) },
                    onClick = { showChangelog = true }
                )
                ArrowPreference(
                    title = "项目主页",
                    summary = "GitHub · yeliqin666/xjtu-toolbox-android",
                    startAction = { SettingsIcon(MiuixIcons.Forward, cBlue) },
                    onClick = { uriHandler.openUri("https://github.com/yeliqin666/xjtu-toolbox-android") }
                )
                ArrowPreference(
                    title = "用户协议与隐私政策",
                    startAction = { SettingsIcon(MiuixIcons.File, cPurple) },
                    onClick = { showEula = true }
                )
            }
        }
        val settingsGroup7: @Composable () -> Unit = {
            // ── 致谢 ──
            SmallTitle("致谢")
            SettingsCard {
                ArrowPreference(
                    title = "XJTUToolBox by yan-xiaoo",
                    summary = "开源社区项目",
                    startAction = { SettingsIcon(MiuixIcons.Info, cLime) },
                    onClick = { uriHandler.openUri("https://github.com/yan-xiaoo/XJTUToolBox") }
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
                    onClick = { uriHandler.openUri("https://github.com/moonfloof/suika-game") }
                )
                ArrowPreference(
                    title = "Matter.js by liabru",
                    summary = "「合成西交大」的物理引擎 · MIT",
                    startAction = { SettingsIcon(MiuixIcons.Info, cLime) },
                    onClick = { uriHandler.openUri("https://github.com/liabru/matter-js") }
                )
                ArrowPreference(
                    title = "chinese-chess-fish-android by zfdang",
                    summary = "象棋规则引擎 · MIT",
                    startAction = { SettingsIcon(MiuixIcons.Info, cLime) },
                    onClick = { uriHandler.openUri("https://github.com/zfdang/chinese-chess-fish-android") }
                )
                ArrowPreference(
                    title = "blackstone by haslam22",
                    summary = "五子棋 AI 的思路来源（未并入代码）· MIT",
                    startAction = { SettingsIcon(MiuixIcons.Info, cLime) },
                    onClick = { uriHandler.openUri("https://github.com/haslam22/blackstone") }
                )
            }
        }

        val groupTitles = listOf("外观", "通用", "功能", "通知与提醒", "数据与隐私", "更新", "关于", "致谢")
        val groupIcons = listOf(Icons.Default.Palette, Icons.Default.Tab, Icons.Default.CalendarMonth, Icons.Default.Notifications, MiuixIcons.CloudFill, Icons.Default.Refresh, MiuixIcons.Info, Icons.Default.Star)
        val groupColors = listOf(cPurple, cTeal, cBlue, cOrange, cBlueGray, cGreen, cBlue, cPurple)
        val groupBodies = listOf<@Composable () -> Unit>(settingsGroup0, settingsGroup1, settingsGroup2, settingsGroup3, settingsGroup4, settingsGroup5, settingsGroup6, settingsGroup7)

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
                            groupTitles.forEachIndexed { i, title ->
                                BasicComponent(
                                    title = title,
                                    startAction = { SettingsIcon(groupIcons[i], groupColors[i]) },
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
                        groupBodies[selectedGroup.coerceIn(groupBodies.indices)]()
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
            groupBodies.forEach { it() }

            Spacer(Modifier.height(16.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
        }

        // ── Sheets / Dialogs（必须在 Scaffold 内，MIUIX MiuixPopupHost 才能渲染）──
        if (showClearCacheDialog) {
            OverlayDialog(
                show = showClearCacheDialog,
                title = "清除缓存",
                summary = "将清除约 $cacheSizeText 的临时缓存，不会影响登录状态和下载文件。",
                onDismissRequest = { showClearCacheDialog = false }
            ) {
                Row(Modifier.fillMaxWidth()) {
                    TextButton(
                        text = "取消",
                        onClick = { showClearCacheDialog = false },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(20.dp))
                    TextButton(
                        text = "确认清除",
                        onClick = {
                            showClearCacheDialog = false
                            scope.launch(Dispatchers.IO) {
                                val cleared = runCatching {
                                    context.cacheDir.deleteRecursively()
                                    context.cacheDir.mkdirs()
                                }.isSuccess
                                // 重新计算实际缓存大小，刷新 UI
                                val newSize = runCatching {
                                    context.cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                                }.map(::formatFileSize).getOrDefault("0 B")
                                withContext(Dispatchers.Main) {
                                    cacheSizeText = newSize
                                    Toast.makeText(
                                        context,
                                        if (cleared) "缓存已清除" else "清除失败",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        }
        if (showPreviewConfirmDialog) {
            OverlayDialog(
                show = showPreviewConfirmDialog,
                title = "开启预览版更新",
                summary = "预览版包含正在测试的新功能，可能存在未预料的问题或崩溃。\n\n• 采用分批灰度推送，开启后不一定会立即收到预览版\n• 想回到正式版只需随时关闭此开关，下一个正式版发布时会自动覆盖回归，不会降级应用",
                onDismissRequest = { showPreviewConfirmDialog = false }
            ) {
                Row(Modifier.fillMaxWidth()) {
                    TextButton(
                        text = "取消",
                        onClick = { showPreviewConfirmDialog = false },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(20.dp))
                    TextButton(
                        text = "开启",
                        onClick = {
                            showPreviewConfirmDialog = false
                            receivePreviewUpdates = true
                            credentialStore.receivePreviewUpdates = true
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        }
        pendingUpdate?.let { update ->
            AutoUpdateDialog(
                version = update.version,
                body = update.notes,
                downloadUrl = update.downloadUrl,
                releaseUrl = update.releaseUrl,
                channelLabel = update.channelLabel,
                isPreview = update.isPreview,
                onDismiss = { pendingUpdate = null }
            )
        }
        ChangelogSheet(show = showChangelog, onDismiss = { showChangelog = false })
        EulaSheet(show = showEula, onDismiss = { showEula = false })
        NoticeSourceSheet(
            show = showNoticeSources,
            onDismiss = {
                showNoticeSources = false
                noticeWatchSummary = NoticeWatchStore.sourceSummary(context)
                applyNoticeWatch()
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NoticeSourceSheet(
    show: Boolean,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var selected by remember(show) {
        mutableStateOf(NoticeWatchStore.sources(context))
    }
    var keywords by remember(show) { mutableStateOf(NoticeWatchStore.keywordsRaw(context)) }
    BackHandler(enabled = show) { onDismiss() }
    OverlayBottomSheet(
        show = show,
        title = "推送来源",
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .overScrollVertical()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
        ) {
            Text(
                "系统通知和小部件共用。勾得越多检查越慢，也更费电。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            Text(
                if (selected.isEmpty()) "还没选来源" else "已选 ${selected.size} 个",
                style = MiuixTheme.textStyles.footnote1,
                color = if (selected.isEmpty()) {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                } else {
                    MiuixTheme.colorScheme.primary
                },
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
            )
            SourceCategory.entries.forEach { category ->
                Text(
                    category.displayName,
                    style = MiuixTheme.textStyles.subtitle,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    NotificationSource.byCategory(category).forEach { source ->
                        val checked = source in selected
                        AppFilterChip(
                            selected = checked,
                            onClick = {
                                selected = if (checked) selected - source else selected + source
                                NoticeWatchStore.setSources(context, selected)
                            },
                            label = source.displayName
                        )
                    }
                }
            }

            // ── 关键词过滤 ──
            Text(
                "只推含关键词的",
                style = MiuixTheme.textStyles.subtitle,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
            )
            Text(
                "逗号分隔，留空则全推。只影响系统推送，抓取次数不变，列表和小部件照常显示全部。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            TextField(
                value = keywords,
                onValueChange = {
                    keywords = it
                    NoticeWatchStore.setKeywords(context, it)
                },
                label = "如：保研, 奖学金, 补考, 停电",
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

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
private fun ChangelogSheet(show: Boolean, onDismiss: () -> Unit) {
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
            changelogItems().forEach { entry ->
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

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024))} MB"
    else -> "${"%.2f".format(bytes.toDouble() / (1024 * 1024 * 1024))} GB"
}

private data class ChangelogEntry(
    val version: String,
    val items: List<String>
)

/**
 * 从 [com.xjtu.toolbox.util.AppChangelog] 派生：
 * 设置页 → 关于 → 更新日志 与启动弹窗共享同一份数据，无需重复维护。
 */
private fun changelogItems(): List<ChangelogEntry> =
    com.xjtu.toolbox.util.AppChangelog.ENTRIES.map { (version, log) ->
        ChangelogEntry(
            version = "v$version",
            items = log.items.map { (emoji, text) -> "$emoji $text" } +
                log.issues.map { "⚠️ 已知问题：$it" }
        )
    }
