package com.xjtu.toolbox.schedule


import com.xjtu.toolbox.ui.components.AppPullToRefresh
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.graphicsLayer
import com.xjtu.toolbox.ui.glass.CouiToolbarActionButton
import kotlinx.coroutines.delay
import com.xjtu.toolbox.auth.LocalAuthExpiry
import com.xjtu.toolbox.platform.BackHandler
import com.xjtu.toolbox.platform.showBriefMessage
import com.xjtu.toolbox.util.formatTwoDigits
import com.xjtu.toolbox.util.todayOnCampus
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.utils.overScrollVertical
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.key
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.flow.drop
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material.icons.filled.GridView

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.EventAvailable
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.selection.SelectionContainer
import com.xjtu.toolbox.ui.components.AppSegmentedTabs
import com.xjtu.toolbox.ui.components.EmptyState
import com.xjtu.toolbox.ui.components.LoadingState
import com.xjtu.toolbox.ui.components.ErrorState
import top.yukonga.miuix.kmp.basic.VerticalDivider
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import com.xjtu.toolbox.nav.AppRoute
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xjtu.toolbox.util.campusTimeHint

@Composable
fun ScheduleScreen(
    /** 宿主槽位（见 [ScheduleHost]）：本地落盘、教务会话、分享面板，以及考勤 / 思源学堂 / 教室座位数那几档「只有某些端才有」的能力。 */
    host: ScheduleHost,
    onBack: () -> Unit = {},  // 用于 catch AuthExpired 时退出
    onActionsChange: ((@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)?) -> Unit = {},
    onBottomContentChange: ((@Composable () -> Unit)?) -> Unit = {},
    contentBottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
    /**
     * 顶栏盖在内容上面时（首页日程 tab 的玻璃顶栏，plan2 Y2）顶栏的总高度。
     * 各栏的滚动内容把它当作顶部留白，从顶栏下面穿过；0 = 顶栏不盖内容，和原来一样。
     */
    contentTopPadding: androidx.compose.ui.unit.Dp = 0.dp,
    /** 首页日程 tab 的顶栏折叠行为（顶栏在 MainScreen 里），交给各栏的下拉刷新协调。 */
    topAppBarScrollBehavior: com.xjtu.toolbox.ui.glass.CouiTopBarScrollBehavior? = null,
    /** 课程详情面板里的下钻入口（教材全文 / 课程回放 / 考勤）要能跳到别的功能页。 */
    onNavigate: (AppRoute) -> Unit = {},
) {
    // 大屏适配由屏内 Composable 自己根据 currentWindowSize() 判断
    val isWideLayout = com.xjtu.toolbox.ui.isWideLayout()
    // 「会话过期」走 :core 既有的那条缝（`:app` 在 AppRoot 顶层注入过一次），本屏不另造一格。
    val authExpiry = LocalAuthExpiry.current
    val campusNow = com.xjtu.toolbox.ui.rememberCampusNow()
    val scope = rememberCoroutineScope()
    val haptics = com.xjtu.toolbox.ui.rememberHaptics()
    val vm: ScheduleViewModel = viewModel(key = "schedule") { ScheduleViewModel(host) }
    LaunchedEffect(campusNow.date, vm.startOfTerm, vm.totalWeeks) { vm.refreshClock(campusNow.date) }
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                ScheduleEvent.AuthExpired -> authExpiry.onAuthExpired(AppRoute.Schedule, onBack)
                ScheduleEvent.Loaded -> haptics.success()
                is ScheduleEvent.Message -> snackbarHostState.showSnackbar(
                    event.text, duration = if (event.long) SnackbarDuration.Long else SnackbarDuration.Short,
                )
            }
        }
    }
    LaunchedEffect(host.login.accountId, host.session.source, host.login.studentId) { vm.bind() }
    LaunchedEffect(vm.activeSource, host.login.hasCredentials) { vm.autoLoginIfNeeded() }
    // 从设置页回来时检查课表来源有没有换
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) vm.onResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var showAddCourseDialog by remember { mutableStateOf(false) }
    var editingCourse by remember { mutableStateOf<CustomCourse?>(null) }
    // 默认落在周视图；本次会话里切走再回来还停在自己选的那栏
    var selectedTab by rememberSaveable { mutableIntStateOf(1) }
    /** 今日 / 学期两级点课后要弹的详情。周视图有自己那份，见 ScheduleTabContent。 */
    var unifiedSelectedCourse by remember { mutableStateOf<CourseItem?>(null) }
    /** 今日那一级点课时带上今天；学期那一级说不出是哪一次，保持 null。 */
    var unifiedOccurrence by remember { mutableStateOf<Occurrence?>(null) }
    fun selectCourse(course: CourseItem, occurrence: Occurrence?) {
        val custom = vm.customCourses.firstOrNull { it.toCourseItem().courseCode == course.courseCode }
        if (custom != null) editingCourse = custom
        else {
            unifiedOccurrence = occurrence
            unifiedSelectedCourse = course
        }
    }
    var showWeekPicker by remember { mutableStateOf(false) }
    var showExportMenu by remember { mutableStateOf(false) }

    /** tab 序号 → 这一页放什么：固定今日 / 周视图 / 学期三格。 */
    fun contentOf(tab: Int): String = when (tab) { 0 -> "today"; 1 -> "week"; else -> "semester" }
    val currentContent = contentOf(selectedTab)

    // 教务课程 + 自定义日程，剔除命中法定节假日的周次
    val mergedCourses = remember(vm.courses, vm.customCourses) { vm.courses + vm.customCourses.map { it.toCourseItem() } }
    val filteredMergedCourses = remember(mergedCourses, vm.startOfTerm, vm.holidayDates) {
        ScheduleCache.filterByHolidays(mergedCourses, vm.startOfTerm, vm.holidayDates)
    }
    val allCourseNames = remember(filteredMergedCourses) {
        filteredMergedCourses.map { it.courseName }.distinct().sorted()
    }
    // 「接下来」：今日两处 TodayTimeline（窄屏 tab、宽屏常驻栏）共用
    val upcomingItems = remember(vm.exams, vm.homeworkDue, campusNow.date, campusNow.hour, campusNow.minute) {
        buildUpcoming(vm.exams, vm.homeworkDue, campusNow)
    }
    val nextExam = remember(vm.exams, campusNow.date, campusNow.hour, campusNow.minute) { ExamCountdown.next(vm.exams, campusNow) }

    vm.pendingSave?.let { (entity, conflicts) ->
        val lines = conflicts.joinToString("\n") { other ->
            val weeks = CustomCourseConflicts.sharedWeeks(entity.weekBits, other.weekBits)
            "「${other.courseName}」：${CustomCourseConflicts.describeWeeks(weeks)}"
        }
        // Window* 自带窗口，不依赖外层 Scaffold 宿主
        BackHandler { vm.pendingSave = null }
        WindowDialog(
            show = true,
            title = "时间冲突",
            summary = "「${entity.courseName}」与以下日程在同一时段重叠：\n$lines",
            onDismissRequest = { vm.pendingSave = null },
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    text = "都保留",
                    onClick = { vm.commitCustomCourse(entity, emptyList()) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
                TextButton(
                    text = "替换原有日程",
                    onClick = { vm.commitCustomCourse(entity, conflicts) },
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(text = "取消", onClick = { vm.pendingSave = null }, modifier = Modifier.fillMaxWidth())
            }
        }
    }

    // 自定义日程弹窗
    if (showAddCourseDialog) {
        val showAddCourseState = remember { mutableStateOf(true) }
        CustomCourseDialog(
            show = showAddCourseState,
            termCode = vm.selectedTermCode,
            totalWeeks = vm.editableWeeks(),
            draft = vm.addScheduleDraft,
            onAutoSave = { vm.addScheduleDraft = it },
            onSave = vm::saveCustomCourse,
            onDismiss = { showAddCourseDialog = false }
        )
    }
    editingCourse?.let { entity ->
        val showEditCourseState = remember { mutableStateOf(true) }
        CustomCourseDialog(
            show = showEditCourseState,
            existing = entity,
            termCode = vm.selectedTermCode,
            totalWeeks = vm.editableWeeks(),
            onSave = vm::saveCustomCourse,
            onDelete = vm::deleteCustomCourse,
            onDismiss = { editingCourse = null }
        )
    }

    // 学期栏要教材：没加载过就拉；教务会话晚到时随 api 变化补上
    LaunchedEffect(selectedTab, vm.selectedTermCode, vm.textbooksLoaded, vm.activeSource) {
        if (contentOf(selectedTab) == "semester") vm.ensureTextbooks()
    }

    // 注入 TopAppBar actions：[+] [⋮] 两个独立按钮
    val headerActionsContent: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit) = {
        androidx.compose.foundation.layout.Row(
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
        // 添加日程（独立按钮）
        if (currentContent == "week" && ScheduleCapability.CUSTOM_COURSE_EDIT in host.honored) {
            // 一直可点。学期代码还没拿到时（教务接口失败、首装无缓存）别灰掉——用户
            // 只看到一个按不动的加号，不知道为什么；先按日期推一个学期，实在推不出再说明。
            CouiToolbarActionButton(
                onClick = {
                    if (vm.ensureTermForAdd()) {
                        showAddCourseDialog = true
                    } else {
                        scope.launch {
                            snackbarHostState.showSnackbar("还没拿到学期信息，下拉刷新后再添加", duration = SnackbarDuration.Short)
                        }
                    }
                },
            ) {
                Icon(Icons.Default.Add, contentDescription = "添加日程")
            }
        }
        // 周视图/全部周叠加的切换在标签行「第 N 周」那一格的选周里（§1.4），
        // 这里不再放一个意思含糊的按钮。
        Box {
            // 主菜单
            com.xjtu.toolbox.ui.components.AppIconMenu(
                expanded = showExportMenu,
                onExpandedChange = { showExportMenu = it },
                icon = { Icon(Icons.Default.MoreVert, contentDescription = "更多") },
            ) {
                run {
                    // 视图切换已经提到工具栏上了，这里不再重复一份——
                    // 同一个开关两个入口，用户按了哪个都得再确认一次状态。
                    if (vm.termList.isNotEmpty()) {
                        com.xjtu.toolbox.ui.components.AppDropdownSubMenu(
                            title = "切换学期",
                            summary = vm.termLabel(vm.selectedTermCode),
                            leadingIcon = {
                                Icon(Icons.Default.SwapHoriz, null, Modifier.size(20.dp), tint = MiuixTheme.colorScheme.onSurfaceContainer)
                            },
                        ) {
                            vm.termList.forEach { term ->
                                com.xjtu.toolbox.ui.components.AppDropdownOption(
                                    text = vm.termLabel(term),
                                    selected = term == vm.selectedTermCode,
                                    onClick = {
                                        showExportMenu = false
                                        vm.switchTerm(term)
                                    },
                                )
                            }
                        }
                    }
                    // 课表壁纸：本周课表直接设成锁屏或桌面（只在能设壁纸的端上画）
                    if (ScheduleCapability.WALLPAPER in host.honored) {
                        ScheduleMenuRow(
                            icon = Icons.Default.Schedule,
                            text = "设为锁屏课表（第 ${vm.currentWeek} 周）",
                            onClick = {
                                showExportMenu = false
                                val ok = host.setWallpaper(filteredMergedCourses, vm.currentWeek, lock = true)
                                showBriefMessage(if (ok) "已设为锁屏壁纸" else "设置失败，请稍后再试")
                            }
                        )
                        ScheduleMenuRow(
                            icon = Icons.Default.Schedule,
                            text = "设为桌面课表（第 ${vm.currentWeek} 周）",
                            onClick = {
                                showExportMenu = false
                                val ok = host.setWallpaper(filteredMergedCourses, vm.currentWeek, lock = false)
                                showBriefMessage(if (ok) "已设为桌面壁纸" else "设置失败，请稍后再试")
                            }
                        )
                    }
                    // 上课自动勿扰：没授权时点它是去系统里授权，授权后点一下切换开关
                    host.autoDnd?.takeIf { ScheduleCapability.AUTO_DND in host.honored }?.let { dnd ->
                        ScheduleMenuRow(
                            icon = Icons.Default.Notifications,
                            text = when {
                                !dnd.granted -> "上课自动勿扰（去授权）"
                                dnd.enabled -> "上课自动勿扰：已开启"
                                else -> "上课自动勿扰：已关闭"
                            },
                            onClick = {
                                showExportMenu = false
                                if (dnd.granted) dnd.setEnabled(!dnd.enabled) else dnd.openAccessSettings()
                            }
                        )
                    }
                    // 这一行只在「本端真能把文件交给系统」时才画（见 ScheduleCapability.SHARE_FILE）：
                    // 桌面端还没有分享面板，画出来就是点一下没反应。
                    if (ScheduleCapability.SHARE_FILE in host.honored) {
                        ScheduleMenuRow(
                            icon = Icons.Default.Event,
                            text = "导出日历 (ICS)",
                            onClick = {
                                showExportMenu = false
                                val st = vm.startOfTerm
                                if (st == null) {
                                    showBriefMessage("缺少开学日期，下拉刷新后再导出")
                                    return@ScheduleMenuRow
                                }
                                scope.launch {
                                    showBriefMessage("正在导出日历…")
                                    try {
                                        val holidays = host.holidays
                                            .refresh(host.cache(host.login.accountId.ifEmpty { null })).keys
                                        val ics = ScheduleExport.generateIcs(filteredMergedCourses, st, vm.selectedTermCode, holidays)
                                        host.shareFile(ics, "${vm.selectedTermCode}_日程.ics", "text/calendar")
                                    } catch (e: Exception) {
                                        showBriefMessage("节假日获取失败，已按普通课表导出")
                                        val ics = ScheduleExport.generateIcs(filteredMergedCourses, st, vm.selectedTermCode, emptySet())
                                        host.shareFile(ics, "${vm.selectedTermCode}_日程.ics", "text/calendar")
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
        }
    }
    val headerBottomContent: (@Composable () -> Unit) = {
        Column {
            // 中间一格直接写看的是哪一周；已选中时再点弹出选周，不另占位置
            val weekLabel = if (vm.showAllWeeks) "全学期" else "第${vm.currentWeek}周"
            Box {
                AppSegmentedTabs(
                    // 固定今日 / 周 / 学期三格，不再随布局变化，见 plan2 §1.5。
                    tabs = listOf("今日", if (selectedTab == 1) "$weekLabel ▾" else weekLabel, "学期"),
                    selectedTabIndex = selectedTab,
                    onTabSelected = { tab ->
                        if (tab == 1 && selectedTab == 1) {
                            showWeekPicker = true
                            return@AppSegmentedTabs
                        }
                        selectedTab = tab
                        // 学期栏第一次打开时才加载教材，别的栏用不上。
                        if (tab == 2) vm.ensureTextbooks(requireApi = false)
                    },
                )
                // 三格等宽：中间这块就是「第 N 周」，选周菜单以它为锚点
                Row(Modifier.matchParentSize()) {
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        com.xjtu.toolbox.ui.components.AppDropdownMenu(
                            expanded = showWeekPicker,
                            onDismissRequest = { showWeekPicker = false },
                            morph = com.xjtu.toolbox.ui.components.MenuMorph.FromTab,
                        ) {
                            val weeks = vm.totalWeeks.takeIf { it > 0 }
                                ?: filteredMergedCourses.maxOfOrNull { it.weekBits.length }?.takeIf { it > 0 }
                                ?: TermWeeks.DEFAULT_TOTAL_WEEKS
                            val viewingCurrentTerm = vm.selectedTermCode.isEmpty() || vm.selectedTermCode == vm.currentTermCode
                            WeekPickerMenu(
                                totalWeeks = weeks,
                                selectedWeek = vm.currentWeek.takeUnless { vm.showAllWeeks },
                                thisWeek = vm.realCurrentWeek.takeIf { viewingCurrentTerm && it > 0 },
                                hasCourse = { week -> filteredMergedCourses.any { it.isInWeek(week) } },
                                onPickWeek = { week ->
                                    haptics.tick()
                                    vm.showAllWeeks = false
                                    vm.currentWeek = week
                                    showWeekPicker = false
                                },
                                onOverview = {
                                    haptics.tick()
                                    vm.showAllWeeks = true
                                    showWeekPicker = false
                                },
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                }
            }
            campusTimeHint()?.let {
                Text(
                    it,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp),
                )
            }
        }
    }

    // 顶栏按钮、标签行交给外层顶栏去画。交出去的是固定的转发，转发时读本页最新那一版：
    // 直接交出闭包的话，外层拿着的是交出那一刻的值（currentContent、vm.api 之类都是旧的）。
    // 最新版变了只让外层读它的那块重画，不回头重组本页
    val latestActions by rememberUpdatedState(headerActionsContent)
    val latestBottom by rememberUpdatedState(headerBottomContent)
    DisposableEffect(Unit) {
        onActionsChange { latestActions(this) }
        onBottomContentChange { latestBottom() }
        onDispose {
            onActionsChange(null)
            onBottomContentChange(null)
        }
    }

    Scaffold(
        snackbarHost = {
            Box(Modifier.padding(bottom = contentBottomPadding)) {
                SnackbarHost(snackbarHostState)
            }
        },
    ) { _ ->
        // 顶栏在宿主 MainScreen 里，这里不吃 Scaffold 的 padding
        val contentPadding = PaddingValues(0.dp)
        // 宽屏：课表区右侧挂一栏常驻详情。窄屏时 Row 里只剩一个 weight(1f) 的孩子，
        // 等价于改造前那一个 Column。
        Row(
            Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .background(MiuixTheme.colorScheme.surface)
        ) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            // 嵌入式 header 已迁移到 MainScreen TopAppBar.actions / bottomContent slot

            // 顶栏是玻璃、盖在内容上面时（contentTopPadding > 0）：
            // 下面几条不滚动的横幅（缓存提示、切周进度条、考试倒计时）出现时，横幅本身先让出顶栏高度，
            // 列表就不用再留；没有横幅时把留白交给各栏的滚动内容，内容才会从顶栏下面滚过去。
            // 自动勿扰提示是页面内的横幅（不进顶栏），和其他横幅一样让出顶栏高度
            val dndPrompt = host.autoDnd?.takeIf {
                ScheduleCapability.AUTO_DND in host.honored && filteredMergedCourses.isNotEmpty() && it.shouldPrompt()
            }
            val staticHeaderShown = (vm.showingStaleData && !vm.isLoading) || dndPrompt != null ||
                (!vm.isLoading && vm.errorMessage == null &&
                    ((currentContent == "week" && vm.isSwitching) || nextExam != null))
            if (staticHeaderShown && contentTopPadding > 0.dp) Spacer(Modifier.height(contentTopPadding))
            val listTopPadding = if (staticHeaderShown) 0.dp else contentTopPadding
            dndPrompt?.let { AutoDndPromptCard(it) }

            // 缓存数据提示：刷新失败但有缓存时，顶部一条小 banner 告知用户「这可能是旧数据」
            if (vm.showingStaleData && !vm.isLoading) {
                Surface(
                    color = MiuixTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.CloudOff,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "网络异常，正在展示缓存数据",
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.footnote1,
                            modifier = Modifier.weight(1f),
                        )
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier
                                .height(20.dp)
                                .clickable { vm.refreshSchedule() }
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                            contentAlignment = androidx.compose.ui.Alignment.Center,
                        ) {
                            Text("重试", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.primary)
                        }
                    }
                }
            }
            if (vm.isLoading) {
                LoadingState(message = "\u52a0\u8f7d\u65e5\u7a0b...", modifier = Modifier.fillMaxSize())
            } else if (vm.errorMessage != null) {
                ErrorState(
                    message = vm.errorMessage!!,
                    onRetry = vm::retry,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                if (currentContent == "week" && vm.isSwitching) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        height = 2.dp
                    )
                }
                // 没有独立的「考试」tab，改成常驻横幅——功能不能因为改版就消失。
                // 点开是完整考试列表。
                var showExamSheet by remember { mutableStateOf(false) }
                nextExam?.let { n ->
                    ExamCountdownBanner(
                        n,
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                            .clickable { showExamSheet = true },
                    )
                }
                if (showExamSheet) {
                    val examSheetShow = remember { mutableStateOf(true) }
                    ExamListSheet(
                        show = examSheetShow,
                        exams = vm.exams,
                        onDismiss = { showExamSheet = false },
                    )
                }
                // 今日 / 日程 / 学期三栏左右滑动切换（和其他分段标签页一样用 AppTabPager）。
                // 周视图里原来那个切周的翻页器因此关掉了手滑（见 ScheduleTabContent），
                // 两个横向手势叠在一起会互相抢；切周点标签行的「第 N 周」。
                com.xjtu.toolbox.ui.components.AppTabPager(
                    pageCount = 3,
                    selectedTabIndex = selectedTab,
                    onTabSelected = { tab ->
                        selectedTab = tab
                        // 学期栏第一次打开时才加载教材，别的栏用不上（和点标签行的逻辑一致）
                        if (tab == 2) vm.ensureTextbooks(requireApi = false)
                    },
                    modifier = Modifier.fillMaxSize(),
                ) { tab ->
                    when (contentOf(tab)) {
                        "week" -> {
                            val weekTopPadding = listTopPadding
                            AppPullToRefresh(
                                isRefreshing = vm.isRefreshingFromNetwork,
                                // 考试倒计时横幅在每个 tab 上都常驻，下拉刷新时顺带把它也刷了。
                                onRefresh = { vm.refreshSchedule(); vm.refreshExams() },
                                scrollBehavior = topAppBarScrollBehavior,
                                topPadding = weekTopPadding,
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                ScheduleTabContent(
                                    host = host,
                                    courses = filteredMergedCourses,
                                    currentWeek = vm.currentWeek,
                                    totalWeeks = vm.totalWeeks,
                                    showAllWeeks = vm.showAllWeeks,
                                    weekNote = vm.weekNote,
                                    realCurrentWeek = vm.realCurrentWeek,
                                    selectedTermCode = vm.selectedTermCode,
                                    startOfTerm = vm.startOfTerm,
                                    currentTermCode = vm.currentTermCode,
                                    onWeekChange = { vm.currentWeek = it },
                                    onToggleMode = { vm.showAllWeeks = !vm.showAllWeeks },
                                    holidayDates = vm.holidayDates,
                                    customCourses = vm.customCourses,
                                    onEditCustomCourse = { editingCourse = it },
                                    bottomPadding = contentBottomPadding,
                                    topPadding = weekTopPadding,
                                    textbooks = vm.textbooks,
                                    textbooksProblem = vm.textbooksBackgroundError,
                                    onRequestTextbooks = vm::requestTextbooksInBackground,
                                    onNavigate = onNavigate,
                                    // 宽屏：周视图点课不再弹窗，写进与今日 / 学期两级同一份选中状态，
                                    // 由右栏展示。三个入口一个面板。
                                    onCourseSelected = if (isWideLayout) {
                                        { course, occurrence ->
                                            selectCourse(course, occurrence)
                                        }
                                    } else {
                                        null
                                    },
                                )
                            }
                        }
                        "today" -> {
                            AppPullToRefresh(
                                isRefreshing = vm.isRefreshingFromNetwork,
                                onRefresh = { vm.refreshSchedule(); vm.refreshExams() },
                                scrollBehavior = topAppBarScrollBehavior,
                                topPadding = listTopPadding,
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                TodayTimeline(
                                    courses = remember(filteredMergedCourses, vm.realCurrentWeek) {
                                        filteredMergedCourses.filter { it.isInWeek(vm.realCurrentWeek) }
                                    },
                                    exams = vm.exams,
                                    today = campusNow.date,
                                    allCourseNames = allCourseNames,
                                    onCourseClick = {
                                        selectCourse(it, Occurrence(campusNow.date, vm.realCurrentWeek))
                                    },
                                    bottomPadding = contentBottomPadding,
                                    topPadding = listTopPadding,
                                    upcoming = upcomingItems,
                                    todayHomework = vm.homeworkDue,
                                    onAddPlan = vm::addPlanItems,
                                    onNavigate = onNavigate,
                                )
                            }
                        }
                        "semester" -> {
                            AppPullToRefresh(
                                isRefreshing = vm.textbooksRefreshing,
                                onRefresh = {
                                    vm.refreshExams()
                                    vm.reloadTextbooks()
                                },
                                scrollBehavior = topAppBarScrollBehavior,
                                topPadding = listTopPadding,
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                SemesterCourseList(
                                    courses = filteredMergedCourses,
                                    textbooks = vm.textbooks,
                                    // 分级布局没有独立的「考试」页，整学期的考试就落在这一级——
                                    // 「这学期还有哪些考试」本来就是学期尺度的问题。
                                    exams = vm.exams,
                                    // 学期一级一行代表整学期，说不出是哪一次课，
                                    // 所以不给回放也不给本次考勤。
                                    onCourseClick = {
                                        selectCourse(it, null)
                                    },
                                    bottomPadding = contentBottomPadding,
                                    topPadding = listTopPadding,
                                )
                            }
                        }
                    }
                }
            }
        }

            // 宽屏常驻详情栏。选中的课来自周视图 / 今日 / 学期三个入口的**同一份**状态，
            // 没选中时展示「今天的课」，点一下就是选中。
            if (isWideLayout) {
                VerticalDivider()
                Column(
                    Modifier
                        .width(360.dp)
                        .fillMaxHeight()
                        .background(MiuixTheme.colorScheme.surface)
                        .padding(top = contentTopPadding),
                ) {
                    // 「今日」栏左边已经是今天的时间轴，右栏再放一遍「今天的课」就是纯重复。
                    // 这时右栏自动选中现在或下一节课、直接给出它的详情（教材、考勤、回放入口）；
                    // 今天的课都上完了或者今天没课，就给本周概览。「周视图」「学期」两栏照旧：右栏放今天的课是补充。
                    val weekCourses = remember(filteredMergedCourses, vm.realCurrentWeek) {
                        filteredMergedCourses.filter { it.isInWeek(vm.realCurrentWeek) }
                    }
                    val onTodayTab = contentOf(selectedTab) == "today"
                    val todayFocus = if (unifiedSelectedCourse == null && onTodayTab) {
                        remember(weekCourses, campusNow.date, campusNow.hour, campusNow.minute) { focusCourseOf(weekCourses, campusNow.date, campusNow.time) }
                    } else {
                        null
                    }
                    val picked = unifiedSelectedCourse ?: todayFocus?.course
                    if (picked != null) {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .overScrollVertical()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            if (todayFocus != null) {
                                Text(
                                    if (todayFocus.ongoing) "正在上" else "下一节",
                                    style = MiuixTheme.textStyles.footnote1,
                                    fontWeight = FontWeight.Bold,
                                    color = MiuixTheme.colorScheme.primary,
                                    modifier = Modifier.padding(bottom = 6.dp),
                                )
                            }
                            CourseDetailContent(
                                host = host,
                                course = picked,
                                allCourseNames = allCourseNames,
                                textbooks = vm.textbooks,
                                textbooksProblem = vm.textbooksBackgroundError,
                                termCode = vm.selectedTermCode,
                                occurrence = if (todayFocus != null) {
                                    Occurrence(campusNow.date, vm.realCurrentWeek)
                                } else {
                                    unifiedOccurrence
                                },
                                onRequestTextbooks = vm::requestTextbooksInBackground,
                                onNavigate = onNavigate,
                            )
                        }
                    } else if (onTodayTab) {
                        WeekGlance(courses = weekCourses, today = campusNow.date)
                    } else {
                        Text(
                            "今天的课",
                            style = MiuixTheme.textStyles.subtitle,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                        TodayTimeline(
                            courses = weekCourses,
                            exams = vm.exams,
                            today = campusNow.date,
                            allCourseNames = allCourseNames,
                            onCourseClick = {
                                selectCourse(it, Occurrence(campusNow.date, vm.realCurrentWeek))
                            },
                            bottomPadding = contentBottomPadding,
                            upcoming = upcomingItems,
                            todayHomework = vm.homeworkDue,
                            onAddPlan = vm::addPlanItems,
                            onNavigate = onNavigate,
                        )
                    }
                }
            }
        }
    }

    // 宽屏选中的课长在常驻右栏里，不算一层页面：返回直接走页面自己的返回逻辑，
    // 不先清选中（和教师主页同一个约定）。

    // 今日 / 学期两级点课打开的详情，用的是跟周视图完全同一个弹窗——
    // 下钻能力不能因为从哪一级点进来而不同。
    // 宽屏不走这一支：右栏已经在展示同一份选中，再弹一次就是两份。
    if (!isWideLayout) unifiedSelectedCourse?.let { course ->
        val showDetail = remember(course) { mutableStateOf(true) }
        CourseDetailDialog(
            host = host,
            show = showDetail,
            course = course,
            allCourseNames = allCourseNames,
            onDismiss = { unifiedSelectedCourse = null },
            textbooks = vm.textbooks,
            textbooksProblem = vm.textbooksBackgroundError,
            termCode = vm.selectedTermCode,
            occurrence = unifiedOccurrence,
            onRequestTextbooks = vm::requestTextbooksInBackground,
            onNavigate = onNavigate,
        )
    }
}

/** 没有独立的「考试」tab，全列表从倒计时横幅点开。用的是与学期栏同一套卡片和排序（ExamList.kt）。 */
@Composable
private fun ExamListSheet(
    show: MutableState<Boolean>,
    exams: List<ExamItem>,
    onDismiss: () -> Unit,
) {
    BackHandler(enabled = show.value) { show.value = false; onDismiss() }
    val examData = remember(exams) { sortExamsForList(exams) }
    var endedExpanded by rememberSaveable { mutableStateOf(false) }
    OverlayBottomSheet(
        show = show.value,
        title = "考试安排",
        onDismissRequest = { show.value = false; onDismiss() },
    ) {
        Box(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
            if (examData.total == 0) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Outlined.EventAvailable, contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "本学期暂无考试安排",
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().overScrollVertical().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
                ) {
                    examListItems(examData, endedExpanded, { endedExpanded = !endedExpanded })
                }
            }
        }
    }
}

@Composable
private fun ScheduleTabContent(
    /** 宿主槽位：考勤角标那一档从它来（见 ScheduleCapability.ATTENDANCE）。 */
    host: ScheduleHost,
    courses: List<CourseItem>, currentWeek: Int, totalWeeks: Int,
    showAllWeeks: Boolean, weekNote: String? = null,
    realCurrentWeek: Int = 0, selectedTermCode: String = "", startOfTerm: LocalDate? = null, currentTermCode: String = "",
    onWeekChange: (Int) -> Unit, onToggleMode: () -> Unit, onAddSchedule: () -> Unit = {},
    customCourses: List<CustomCourse> = emptyList(),
    holidayDates: Map<LocalDate, String> = emptyMap(),
    onEditCustomCourse: (CustomCourse) -> Unit = {},
    bottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
    topPadding: androidx.compose.ui.unit.Dp = 0.dp,
    textbooks: List<TextbookItem> = emptyList(),
    /** 教材没取到时的原因，null 表示没问题。见 CourseLinkSections。 */
    textbooksProblem: String? = null,
    onRequestTextbooks: () -> Unit = {},
    onNavigate: (AppRoute) -> Unit = {},
    /**
     * 宽屏：点课不再弹详情，而是把选中送给课表右侧的常驻详情栏（由调用方持有）。
     * 窄屏传 null，走下面的本地选中 + 弹窗，行为与改造前一致。
     */
    onCourseSelected: ((CourseItem, Occurrence?) -> Unit)? = null,
) {
    val allNames = remember(courses) { courses.map { it.courseName }.distinct().sorted() }
    var selectedCourse by remember { mutableStateOf<CourseItem?>(null) }
    var selectedOccurrence by remember { mutableStateOf<Occurrence?>(null) }
    // PR T：这是独立于 ScheduleScreen 的私有 Composable，haptics 不能从外层直接闭包过来，本地再取一份。
    val haptics = com.xjtu.toolbox.ui.rememberHaptics()
    /** 两个点击入口共用：宽屏交给右栏，窄屏落回本地状态。 */
    fun selectCourse(course: CourseItem, occurrence: Occurrence?) {
        if (onCourseSelected != null) {
            onCourseSelected(course, occurrence)
        } else {
            selectedOccurrence = occurrence
            selectedCourse = course
        }
    }

    // 考勤角标。三条约束都是"别因为考勤把课表拖坏"：默认关、旁路加载、失败即无角标。
    val badgeEnabled = ScheduleCapability.ATTENDANCE in host.honored && host.attendanceBadgeEnabled
    var attendanceMarks by remember { mutableStateOf<AttendanceMarks?>(null) }
    LaunchedEffect(badgeEnabled, selectedTermCode, startOfTerm, totalWeeks) {
        if (!badgeEnabled) { attendanceMarks = null; return@LaunchedEffect }
        // 告诉考勤那一层这个学期结没结束——它拿不到教务会话，自己判断不了。
        // 用学期自己的起止判断而不是跟 currentTermCode 比：后者在冷启动阶段会被
        // paintCache 覆盖成正在画的学期。
        attendanceMarks = host.courseLinks?.attendanceMarks(
            termCode = selectedTermCode,
            finishedTerms = listOfNotNull(
                selectedTermCode.takeIf {
                    ScheduleCache.isFinishedByDate(startOfTerm, totalWeeks)
                },
            ),
        )
    }
    val absenceColor = Color(0xFFE5484D)
    val lateColor = Color(0xFFF5A524)
    val leaveColor = Color(0xFF9BA1A6)
    fun badgeOf(slot: com.xjtu.toolbox.schedule.ScheduleSlot, week: Int): com.xjtu.toolbox.schedule.SlotMark? {
        val idx = attendanceMarks ?: return null
        return when (idx.statusOf(week, slot.slotDayOfWeek, slot.slotStartSection)) {
            com.xjtu.toolbox.attendance.WaterType.ABSENCE -> com.xjtu.toolbox.schedule.SlotMark(absenceColor)
            com.xjtu.toolbox.attendance.WaterType.LATE -> com.xjtu.toolbox.schedule.SlotMark(lateColor)
            com.xjtu.toolbox.attendance.WaterType.LEAVE -> com.xjtu.toolbox.schedule.SlotMark(leaveColor)
            // 正常出勤也标，用中性色。只标异常的话，全勤的人整学期一个点都看不到；
            // 而这个点本身有信息——这节课已经上过且记了考勤，没点的就是还没上。
            com.xjtu.toolbox.attendance.WaterType.NORMAL -> com.xjtu.toolbox.schedule.SlotMark()
            // 未识别状态：标出来但不判定好坏，用中性色提示"有记录但看不懂"。
            com.xjtu.toolbox.attendance.WaterType.UNKNOWN -> com.xjtu.toolbox.schedule.SlotMark(leaveColor)
            // 查无此格（未来的课、或没有考勤的课）不标。
            null -> null
        }
    }

    // 顶栏是玻璃、盖在内容上时：有「学期已结束 / 距开学」这条提示，就由提示先让出顶栏高度，
    // 网格不再重复留；没有提示时，留白交给网格的纵向滚动，网格才能从顶栏下面滚过去。
    // 以前漏了这一条，提示整条被压在玻璃顶栏后面。
    val gridTopPadding = if (weekNote != null) 0.dp else topPadding
    Column(Modifier.fillMaxSize().overScrollVertical()) {
        if (weekNote != null && topPadding > 0.dp) Spacer(Modifier.height(topPadding))
        // 学期状态提示（未开学/已结束）
        if (weekNote != null) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                shape = RoundedCornerShape(8.dp),
                color = MiuixTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            ) {
                Text(
                    weekNote,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        // 一节课都没有（暑假、还没选课）。0 页 Pager 会崩，20 页空网格是原来的毛病。
        // 判的是 courses（含自定义日程）而不是 totalWeeks：后者只数教务课表，
        // 教务为空时用户自己加的日程也会被这一句挡住，加了等于没加。
        if (courses.isEmpty()) {
            // 空状态也要能下拉刷新（副文案就是这么引导的）：PullToRefresh 的拖动量
            // 全靠嵌套滚动分发，内容不满一屏又没有滚动容器时手势根本传不到它，
            // 垫一层 verticalScroll 只为建立滚动链，内容没有可滚的距离、视觉无变化。
            EmptyState(
                title = "本学期没有课程",
                subtitle = "教务还没排课。可下拉刷新、在「更多」里切换学期，或点 + 添加日程",
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = gridTopPadding, bottom = bottomPadding)
            )
            return@Column
        }
        // 教务课表为空、只有自定义日程时 totalWeeks 是 0，翻页器得有页可翻。
        val pageWeeks = totalWeeks.takeIf { it > 0 }
            ?: courses.maxOfOrNull { it.weekBits.length }?.takeIf { it > 0 }
            ?: TermWeeks.DEFAULT_TOTAL_WEEKS

        // 主体：每周用 Pager 横滑切周；总览单页
        if (!showAllWeeks) {
            // realCurrentWeek 加载完后重建 Pager，让 initialPage 正确停在当前周
            key(realCurrentWeek) {
            val pagerState = rememberPagerState(initialPage = (currentWeek - 1).coerceIn(0, pageWeeks - 1), pageCount = { pageWeeks })
            // currentWeek -> pager（仅在用户没正在拖拽时同步）
            LaunchedEffect(currentWeek) {
                val target = (currentWeek - 1).coerceIn(0, pageWeeks - 1)
                if (pagerState.currentPage != target && !pagerState.isScrollInProgress) {
                    pagerState.scrollToPage(target)
                }
            }
            // pager -> currentWeek（仅在用户拖拽稳定后触发，避免初始化 emit 覆盖）
            LaunchedEffect(pagerState) {
                snapshotFlow { pagerState.settledPage }
                    .drop(1)
                    .collect { page ->
                        val w = page + 1
                        if (w != currentWeek) {
                            haptics.tick()
                            onWeekChange(w)
                        }
                    }
            }
            // userScrollEnabled = false：左右滑动交给外层的三栏切换，切周走周选择胶囊
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize(), userScrollEnabled = false) { page ->
                val weekN = page + 1
                val weekCourses = remember(courses, weekN) { courses.filter { it.isInWeek(weekN) } }
                val weekDates = remember(startOfTerm, weekN) {
                    if (startOfTerm != null && weekN > 0) {
                        // 第 1 周的周一是开学那一周的周一（与 TermWeeks.dateOf 同一个锚点），
                        // kotlinx-datetime 没有 plusWeeks/plusDays，按距 1970 的天数走。
                        val monday = civilFromEpochDay(startOfTerm.toEpochDays().toLong() + (weekN - 1) * 7L)
                        (0..6).map { civilFromEpochDay(monday.toEpochDays().toLong() + it) }
                    } else null
                }
                if (weekCourses.isEmpty() && courses.isNotEmpty()) {
                    EmptyState(
                        title = "这周没课",
                        subtitle = "第${weekN}周整周空着",
                        // 同上：整周空着时页面没有滚动容器，PullToRefresh 收不到拖动量
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = gridTopPadding, bottom = bottomPadding)
                    )
                } else {
                    ScheduleGrid(
                        weekCourses, allNames,
                        isCurrentWeek = (weekN == realCurrentWeek && selectedTermCode == currentTermCode),
                        weekDates = weekDates,
                        holidayNames = holidayDates,
                        enableCompression = true,
                        bottomPadding = bottomPadding,
                        topPadding = gridTopPadding,
                        slotBadge = { badgeOf(it, weekN) },
                        onSlotClick = { item ->
                            val course = item as? CourseItem ?: return@ScheduleGrid
                            val customEntity = customCourses.find { it.toCourseItem().courseCode == course.courseCode }
                            if (customEntity != null) onEditCustomCourse(customEntity)
                            else {
                                // 周视图知道是第几周、哪一天，详情面板据此只给这一次的数据。
                                selectCourse(
                                    course,
                                    weekDates?.getOrNull(course.dayOfWeek - 1)
                                        ?.let { Occurrence(it, weekN) },
                                )
                            }
                        }
                    )
                }
            }
            } // close key(realCurrentWeek)
        } else {
            ScheduleGrid(
                courses, allNames,
                showWeeks = true,
                enableCompression = true,
                bottomPadding = bottomPadding,
                        topPadding = gridTopPadding,
                onSlotClick = { item ->
                    val course = item as? CourseItem ?: return@ScheduleGrid
                    val customEntity = customCourses.find { it.toCourseItem().courseCode == course.courseCode }
                    if (customEntity != null) onEditCustomCourse(customEntity)
                    else {
                        // 全部周叠加一格代表很多周，说不出是哪一次。
                        selectCourse(course, null)
                    }
                }
            )
        }

    }

    // 课程详情弹窗（仅普通课程；自定义课程已在点击时分流到编辑弹窗）
    selectedCourse?.let { course ->
        val showCourseDetail = remember(course) { mutableStateOf(true) }
        CourseDetailDialog(
            host = host,
            show = showCourseDetail,
            course = course,
            allCourseNames = allNames,
            onDismiss = { selectedCourse = null },
            textbooks = textbooks,
            textbooksProblem = textbooksProblem,
            termCode = selectedTermCode,
            occurrence = selectedOccurrence,
            onRequestTextbooks = onRequestTextbooks,
            onNavigate = onNavigate,
        )
    }
}

/** 选周菜单：总览在上，周按六列排；选中的实心，本周浅底，没课的周变淡。 */
@Composable
private fun WeekPickerMenu(
    totalWeeks: Int,
    selectedWeek: Int?,
    thisWeek: Int?,
    hasCourse: (Int) -> Boolean,
    onPickWeek: (Int) -> Unit,
    onOverview: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val overview = selectedWeek == null
    Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
        WeekPickerAction(Icons.Default.GridView, "全学期总览", overview, Modifier.fillMaxWidth(), onOverview)
        Spacer(Modifier.height(8.dp))
        (1..totalWeeks).chunked(6).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                row.forEach { week ->
                    val selected = week == selectedWeek
                    val current = week == thisWeek
                    Box(
                        Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                when {
                                    selected -> colors.primary
                                    current -> colors.primary.copy(alpha = 0.12f)
                                    else -> androidx.compose.ui.graphics.Color.Transparent
                                },
                            )
                            .clickable { onPickWeek(week) }
                            .alpha(if (selected || hasCourse(week)) 1f else com.xjtu.toolbox.ui.components.ExpiredStyle.CONTENT_ALPHA),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "$week",
                            style = MiuixTheme.textStyles.body2,
                            fontWeight = if (selected || current) FontWeight.Bold else FontWeight.Medium,
                            color = when {
                                selected -> colors.onPrimary
                                current -> colors.primary
                                else -> colors.onSurfaceContainer
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekPickerAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val fg = if (selected) colors.onPrimary else colors.primary
    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) colors.primary else colors.primary.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = fg)
        Spacer(Modifier.width(4.dp))
        Text(label, style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1)
    }
}

/** 课表有内容、还没开自动勿扰时在顶部提示一次；「不再提示」之后就不再出现。 */
@Composable
private fun AutoDndPromptCard(dnd: AutoDndAccess) {
    var hidden by remember { mutableStateOf(false) }
    if (hidden) return
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        cornerRadius = 14.dp,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text("上课时自动开启勿扰？", style = MiuixTheme.textStyles.body1, color = MiuixTheme.colorScheme.onSurfaceContainer)
            Text(
                "课前 3 分钟到课后 3 分钟自动切换，需要在系统里授权一次。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(text = "不再提示", onClick = { dnd.dismissPrompt(); hidden = true })
                Spacer(Modifier.width(8.dp))
                TextButton(
                    text = if (dnd.granted) "开启" else "去授权",
                    onClick = {
                        if (dnd.granted) dnd.setEnabled(true) else dnd.openAccessSettings()
                        hidden = true
                    },
                )
            }
        }
    }
}

@Composable
private fun ScheduleMenuRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    onClick: () -> Unit
) {
    com.xjtu.toolbox.ui.components.AppDropdownMenuItem(
        text = { Text(text, style = MiuixTheme.textStyles.body1, color = MiuixTheme.colorScheme.onSurfaceContainer) },
        leadingIcon = { Icon(icon, null, Modifier.size(20.dp), tint = MiuixTheme.colorScheme.onSurfaceContainer) },
        onClick = onClick,
    )
}

/**
 * 课程详情的**内容**，不含容器。
 *
 * 手机上被 [CourseDetailDialog] 包在 OverlayBottomSheet 里（行为与抽出前一致），
 * 宽屏下直接长在课表右侧的常驻详情栏里。
 *
 * 不再有底部的「知道了」：弹窗下滑、点外面、返回键都能关，多一颗按钮只占高度。
 */
@Composable
private fun CourseDetailContent(
    /** 宿主槽位：教室座位数与下钻区都从它来。 */
    host: ScheduleHost,
    course: CourseItem,
    /** 同屏全部课程名，默认配色按它排序取色。 */
    allCourseNames: List<String>,
    textbooks: List<TextbookItem> = emptyList(),
    /** 教材没取到时的原因，null 表示没问题。 */
    textbooksProblem: String? = null,
    termCode: String = "",
    /** 这一次课是哪天、第几周；学期总览给不出，传 null。 */
    occurrence: Occurrence? = null,
    onRequestTextbooks: () -> Unit = {},
    onNavigate: (AppRoute) -> Unit = {},
) {
    val isAgenda = course.courseType == "日程"
        // 异步获取教室座位数。只有本端真接了这一档才去问（见 ScheduleCapability.SEAT_COUNT）：
        // 空闲教室那套取数在 `:data`、要站点会话，桌面还没接。
        var seatCount by remember { mutableStateOf<Int?>(null) }
        LaunchedEffect(course.location) {
            if (course.location.isNotEmpty() && ScheduleCapability.SEAT_COUNT in host.honored) {
                seatCount = kotlinx.coroutines.withContext(host.io) {
                    try { host.roomSeatCount(course.location) } catch (_: Exception) { null }
                }
            }
        }
        // 标题一段、信息一块、下钻一块。原来是所有行平铺、统一 8dp，
        // 标题、地点、时间、教材、回放一视同仁地排下来，看不出哪些是一组的。
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        course.courseName,
                        style = MiuixTheme.textStyles.headline2,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    var pickColor by remember { mutableStateOf(false) }
                    val color = rememberCourseColors(allCourseNames).colorOf(course.courseName)
                    Box(
                        Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(color)
                            .clickable { pickColor = true }
                    )
                    if (pickColor) CourseColorDialog(course.courseName, color, agenda = isAgenda) { pickColor = false }
                }
                course.courseType.takeIf { it.isNotBlank() && !isAgenda }?.let {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        it,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            // 压成两行：谁在哪、什么时候。原来五行图标各占一行，一加下钻区就爆。
            val dayName = when (course.dayOfWeek) {
                1 -> "一"; 2 -> "二"; 3 -> "三"; 4 -> "四"
                5 -> "五"; 6 -> "六"; 7 -> "日"; else -> "?"
            }
            val timeText = if (isAgenda) {
                val summer = XjtuTime.isSummerTime((occurrence?.date ?: todayOnCampus()).monthNumber)
                val (start, end) = course.clockMinutes(summer)
                "星期$dayName ${formatTwoDigits(start / 60)}:${formatTwoDigits(start % 60)}-${formatTwoDigits(end / 60)}:${formatTwoDigits(end % 60)}"
            } else {
                "星期$dayName 第${course.startSection}-${course.endSection}节"
            }
            // 具体到某一次时直接报日期，比让人自己数第几周有用。
            val dateText = occurrence?.let {
                "${it.date.monthNumber}/${it.date.dayOfMonth} · 第${it.week}周"
            } ?: course.getWeeks().takeIf { it.isNotEmpty() }?.let { "${TermWeeks.formatRanges(it, sep = ", ")}周" }

            // 两行元信息合进一张卡：它们回答的是同一个问题（这门课在哪、什么时候），
            // 裸排在弹窗底色上时和下面的下钻入口分不开。
            // 底色不能用 surfaceVariant：miuix 深色主题里它和弹窗底色同为 #242424，卡片等于隐形。
            Card(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 14.dp,
                colors = CardDefaults.defaultColors(color = courseDetailTileColor()),
            ) {
            Column(
                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
            if (course.teacher.isNotEmpty() || course.location.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (course.location.isNotEmpty()) Icons.Default.Place else Icons.Default.Person,
                        null, Modifier.size(17.dp), tint = MiuixTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    SelectionContainer {
                        Text(
                            listOfNotNull(
                                course.location.takeIf { it.isNotEmpty() },
                                course.teacher.takeIf { it.isNotEmpty() },
                            ).joinToString("  ·  "),
                            style = MiuixTheme.textStyles.body2,
                        )
                    }
                    if (seatCount != null) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MiuixTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                "${seatCount}座",
                                Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.CalendarMonth, null, Modifier.size(17.dp),
                    tint = MiuixTheme.colorScheme.primaryVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    listOfNotNull(timeText, dateText).joinToString("  ·  "),
                    style = MiuixTheme.textStyles.body2,
                )
            }
            }
            }
            // 下钻区：教材 → 全文、思源学堂、课程回放、本课考勤。见 CourseLinkSections。
            CourseLinkSections(
                host = host,
                course = course,
                textbooks = textbooks,
                textbooksProblem = textbooksProblem,
                termCode = termCode,
                occurrence = occurrence,
                onRequestTextbooks = onRequestTextbooks,
                onNavigate = onNavigate,
            )
        }
        Spacer(Modifier.height(16.dp))
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
}

@Composable
private fun CourseDetailDialog(
    /** 宿主槽位，原样交给 [CourseDetailContent] 的下钻区。 */
    host: ScheduleHost,
    show: MutableState<Boolean>,
    course: CourseItem,
    allCourseNames: List<String>,
    onDismiss: () -> Unit,
    textbooks: List<TextbookItem> = emptyList(),
    /** 教材没取到时的原因，null 表示没问题。 */
    textbooksProblem: String? = null,
    termCode: String = "",
    /** 这一次课是哪天、第几周；学期总览给不出，传 null。 */
    occurrence: Occurrence? = null,
    onRequestTextbooks: () -> Unit = {},
    onNavigate: (AppRoute) -> Unit = {},
) {
    val close = { show.value = false; onDismiss() }
    BackHandler(enabled = show.value) { close() }
    OverlayBottomSheet(
        show = show.value,
        onDismissRequest = close,
    ) {
        CourseDetailContent(
            host = host,
            course = course,
            allCourseNames = allCourseNames,
            textbooks = textbooks,
            textbooksProblem = textbooksProblem,
            termCode = termCode,
            occurrence = occurrence,
            onRequestTextbooks = onRequestTextbooks,
            // 下钻跳转前先关弹窗，行为与抽出前一致。
            onNavigate = { route -> close(); onNavigate(route) },
        )
    }
}

/**
 * 下一场考试的倒计时条。常驻在页面顶部，点开是完整考试列表（ExamListSheet）。
 */
@Composable
fun ExamCountdownBanner(next: ExamCountdown.Next, modifier: Modifier = Modifier) {
    // 三天以内才转成警示色。整学期都红着，红色就不再是信号了。
    val urgent = next.daysLeft <= 3
    val accent = if (urgent) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.primary
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = accent.copy(alpha = 0.12f),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 考试就在明天或今天：图标每隔 4 秒轻晃一下，一共三次就停，提醒到了就不再打扰
            val wiggle = remember { androidx.compose.animation.core.Animatable(0f) }
            if (next.daysLeft <= 1) {
                LaunchedEffect(next.exam.courseName) {
                    repeat(3) {
                        delay(if (it == 0) 800L else 4_000L)
                        for (angle in listOf(14f, -12f, 8f, -5f, 0f)) {
                            wiggle.animateTo(angle, androidx.compose.animation.core.tween(70))
                        }
                    }
                }
            }
            Icon(
                Icons.Default.Schedule, null,
                Modifier.size(18.dp).graphicsLayer { rotationZ = wiggle.value },
                tint = accent,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 天数变化时翻牌：旧的往上走、新的从下面上来
                    androidx.compose.animation.AnimatedContent(
                        targetState = next.label,
                        transitionSpec = {
                            (androidx.compose.animation.slideInVertically { it } + androidx.compose.animation.fadeIn()) togetherWith
                                (androidx.compose.animation.slideOutVertically { -it } + androidx.compose.animation.fadeOut())
                        },
                        label = "examDays",
                    ) { label ->
                        Text(label, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Bold, color = accent, maxLines = 1)
                    }
                    Text(
                        " · ${next.exam.courseName}",
                        style = MiuixTheme.textStyles.body2,
                        fontWeight = FontWeight.Bold,
                        color = accent,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
                val detail = listOfNotNull(
                    next.exam.examDate.takeIf { it.isNotBlank() },
                    next.exam.examTime.takeIf { it.isNotBlank() },
                    next.exam.location.takeIf { it.isNotBlank() },
                ).joinToString("  ")
                if (detail.isNotBlank()) {
                    Text(
                        detail,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
