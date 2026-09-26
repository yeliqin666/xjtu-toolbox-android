package com.xjtu.toolbox.schedule

import com.xjtu.toolbox.ui.components.AppPullToRefresh
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableLongStateOf
import com.xjtu.toolbox.ui.glass.glassSource
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import androidx.compose.ui.draw.shadow
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.drawBackdrop
import androidx.activity.compose.BackHandler
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.TopAppBar
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
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.EventAvailable
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import com.xjtu.toolbox.auth.LocalAppLoginState
import com.xjtu.toolbox.auth.handleAuthExpired
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.auth.SiteSession
import androidx.compose.foundation.text.selection.SelectionContainer
import com.xjtu.toolbox.ui.components.AppSegmentedTabs
import com.xjtu.toolbox.ui.components.EmptyState
import com.xjtu.toolbox.ui.components.LoadingState
import com.xjtu.toolbox.ui.components.ErrorState
import top.yukonga.miuix.kmp.basic.VerticalDivider
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import com.xjtu.toolbox.nav.AppRoute
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun ScheduleScreen(
    site: SiteSession? = null,
    studentId: String = "",
    onBack: () -> Unit = {},  // 用于 catch AuthExpired 时退出
    onSubtitleChange: (String) -> Unit = {},
    onActionsChange: ((@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)?) -> Unit = {},
    onBottomContentChange: ((@Composable () -> Unit)?) -> Unit = {},
    contentBottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
    /**
     * 顶栏盖在内容上面时（首页日程 tab 的玻璃顶栏，plan2 Y2）顶栏的总高度。
     * 各栏的滚动内容把它当作顶部留白，从顶栏下面穿过；0 = 顶栏不盖内容，和原来一样。
     */
    contentTopPadding: androidx.compose.ui.unit.Dp = 0.dp,
    /** 首页日程 tab 的顶栏折叠行为（顶栏在 MainScreen 里），交给各栏的下拉刷新协调。 */
    topAppBarScrollBehavior: top.yukonga.miuix.kmp.basic.ScrollBehavior? = null,
    /** 课程详情面板里的下钻入口（教材全文 / 课程回放 / 考勤）要能跳到别的功能页。 */
    onNavigate: (AppRoute) -> Unit = {},
) {
    // 大屏适配由屏内 Composable 自己根据 currentWindowSize() 判断
    val isWideLayout = com.xjtu.toolbox.ui.isWideLayout()
    val appLoginState = LocalAppLoginState.current
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val haptics = com.xjtu.toolbox.ui.rememberHaptics()
    val vm: ScheduleViewModel = viewModel(key = "schedule") { ScheduleViewModel(context, appLoginState) }
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                ScheduleEvent.AuthExpired -> appLoginState.handleAuthExpired(AppRoute.Schedule, onBack)
                ScheduleEvent.Loaded -> haptics.success()
                is ScheduleEvent.Message -> snackbarHostState.showSnackbar(
                    event.text, duration = if (event.long) SnackbarDuration.Long else SnackbarDuration.Short,
                )
            }
        }
    }
    LaunchedEffect(appLoginState.accountId, site, studentId) { vm.bind(appLoginState.accountId, site, studentId) }
    LaunchedEffect(vm.activeSite, appLoginState.hasCredentials) { vm.autoLoginIfNeeded() }
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
    var editingCourse by remember { mutableStateOf<CustomCourseEntity?>(null) }
    // 默认落在周视图；本次会话里切走再回来还停在自己选的那栏
    var selectedTab by rememberSaveable { mutableIntStateOf(1) }
    /** 今日 / 学期两级点课后要弹的详情。周视图有自己那份，见 ScheduleTabContent。 */
    var unifiedSelectedCourse by remember { mutableStateOf<CourseItem?>(null) }
    /** 今日那一级点课时带上今天；学期那一级说不出是哪一次，保持 null。 */
    var unifiedOccurrence by remember { mutableStateOf<Occurrence?>(null) }
    var termDropdownExpanded by remember { mutableStateOf(false) }
    var showWeekPicker by remember { mutableStateOf(false) }
    var showExportMenu by remember { mutableStateOf(false) }

    /** tab 序号 → 这一页放什么：固定今日 / 周视图 / 学期三格。 */
    fun contentOf(tab: Int): String = when (tab) { 0 -> "today"; 1 -> "week"; else -> "semester" }
    val currentContent = contentOf(selectedTab)

    // 通知外层顶栏当前学期
    LaunchedEffect(vm.selectedTermCode, vm.termList) { onSubtitleChange(vm.termLabel(vm.selectedTermCode)) }

    // 教务课程 + 自定义日程，剔除命中法定节假日的周次
    val mergedCourses = remember(vm.courses, vm.customCourses) { vm.courses + vm.customCourses.map { it.toCourseItem() } }
    val filteredMergedCourses = remember(mergedCourses, vm.startOfTerm, vm.holidayDates) {
        ScheduleCache.filterByHolidays(mergedCourses, vm.startOfTerm, vm.holidayDates)
    }
    // 「接下来」：今日两处 TodayTimeline（窄屏 tab、宽屏常驻栏）共用
    val upcomingItems = remember(vm.exams, vm.homeworkDue) { buildUpcoming(vm.exams, vm.homeworkDue) }

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
    val showAddCourseState = remember { mutableStateOf(false) }
    LaunchedEffect(showAddCourseDialog) { showAddCourseState.value = showAddCourseDialog }
    if (showAddCourseDialog) {
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
    LaunchedEffect(selectedTab, vm.selectedTermCode, vm.textbooksLoaded, vm.api) {
        if (contentOf(selectedTab) == "semester") vm.ensureTextbooks()
    }

    // 注入 TopAppBar actions：[+] [⋮] 两个独立按钮
    val headerActionsContent: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit) = {
        // 添加日程（独立按钮）
        if (currentContent == "week") {
            // 一直可点。学期代码还没拿到时（教务接口失败、首装无缓存）别灰掉——用户
            // 只看到一个按不动的加号，不知道为什么；先按日期推一个学期，实在推不出再说明。
            IconButton(
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
        // 周视图/全部周叠加的切换已经挪到标签行下面的周选择胶囊里（§1.4），
        // 这里不再放一个意思含糊的按钮。
        Box {
            IconButton(onClick = { showExportMenu = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "更多")
            }
            // 主菜单
            OverlayListPopup(
                show = showExportMenu,
                alignment = PopupPositionProvider.Align.End,
                onDismissRequest = { showExportMenu = false }
            ) {
                ListPopupColumn {
                    // 视图切换已经提到工具栏上了，这里不再重复一份——
                    // 同一个开关两个入口，用户按了哪个都得再确认一次状态。
                    if (vm.termList.isNotEmpty()) {
                        ScheduleMenuRow(
                            icon = Icons.Default.SwapHoriz,
                            text = "切换学期",
                            onClick = { showExportMenu = false; termDropdownExpanded = true }
                        )
                    }
                    ScheduleMenuRow(
                        icon = Icons.Default.Event,
                        text = "导出日历 (ICS)",
                        onClick = {
                            showExportMenu = false
                            val st = vm.startOfTerm
                            if (st == null) {
                                android.widget.Toast.makeText(context, "无法获取开学日期，ICS 导出不可用", android.widget.Toast.LENGTH_SHORT).show()
                                return@ScheduleMenuRow
                            }
                            scope.launch {
                                android.widget.Toast.makeText(context, "正在导出日历…", android.widget.Toast.LENGTH_SHORT).show()
                                try {
                                    val holidays = HolidayApi.getHolidayDates(context).keys
                                    val ics = ScheduleExport.generateIcs(filteredMergedCourses, st, vm.selectedTermCode, holidays)
                                    ScheduleExport.shareTextFile(context, ics, "${vm.selectedTermCode}_日程.ics", "text/calendar")
                                } catch (e: Exception) {
                                    android.widget.Toast.makeText(
                                        context,
                                        "节假日获取失败，已按普通课表导出",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                    val ics = ScheduleExport.generateIcs(filteredMergedCourses, st, vm.selectedTermCode, emptySet())
                                    ScheduleExport.shareTextFile(context, ics, "${vm.selectedTermCode}_日程.ics", "text/calendar")
                                }
                            }
                        }
                    )
                }
            }
            // 学期切换 popup（独立，由"切换学期"菜单项触发）
            val termSelectedIdxTb = vm.termList.indexOf(vm.selectedTermCode).coerceAtLeast(0)
            OverlayListPopup(
                show = termDropdownExpanded,
                alignment = PopupPositionProvider.Align.End,
                onDismissRequest = { termDropdownExpanded = false }
            ) {
                ListPopupColumn {
                    vm.termList.forEachIndexed { idx, term ->
                        DropdownImpl(
                            text = vm.termLabel(term),
                            optionSize = vm.termList.size,
                            isSelected = idx == termSelectedIdxTb,
                            onSelectedIndexChange = {
                                termDropdownExpanded = false
                                vm.switchTerm(term)
                            },
                            index = idx
                        )
                    }
                }
            }
        }
    }
    val headerBottomContent: (@Composable () -> Unit) = {
        Column {
            AppSegmentedTabs(
                // 固定今日 / 周视图 / 学期三格，不再随布局变化，见 plan2 §1.5。
                tabs = listOf("今日", "周视图", "学期"),
                selectedTabIndex = selectedTab,
                onTabSelected = { tab ->
                    selectedTab = tab
                    // 学期栏第一次打开时才加载教材，别的栏用不上。
                    if (tab == 2) vm.ensureTextbooks(requireApi = false)
                },
            )
        }
    }

    // 周选择弹窗（§1.4③）：网格选任意一周，或者切到「全学期总览」。
    if (showWeekPicker) {
        val pageWeeksForPicker = vm.totalWeeks.takeIf { it > 0 }
            ?: filteredMergedCourses.maxOfOrNull { it.weekBits.length }?.takeIf { it > 0 }
            ?: TermWeeks.DEFAULT_TOTAL_WEEKS
        val pickerShow = remember { mutableStateOf(true) }
        BackHandler(enabled = pickerShow.value) { pickerShow.value = false; showWeekPicker = false }
        OverlayBottomSheet(
            show = pickerShow.value,
            title = "选择周",
            onDismissRequest = { pickerShow.value = false; showWeekPicker = false },
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                // 「全学期总览」和周网格是同一个选择的两种答案，放在网格上方同一行的右端，
                // 选中时和选中的周一样高亮；不再单独占一整行、再配一行解释小字。
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (vm.realCurrentWeek > 0) "本周第 $vm.realCurrentWeek 周 · 共 $pageWeeksForPicker 周" else "共 $pageWeeksForPicker 周",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.weight(1f),
                    )
                    // 「回本周」原来在整行周标题的右端，那一行删了，挪到这里
                    val viewingCurrentTerm = vm.selectedTermCode.isEmpty() || vm.selectedTermCode == vm.currentTermCode
                    if (viewingCurrentTerm && vm.realCurrentWeek > 0 && (vm.showAllWeeks || vm.currentWeek != vm.realCurrentWeek)) {
                        Text(
                            "回本周",
                            style = MiuixTheme.textStyles.body2,
                            fontWeight = FontWeight.SemiBold,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    haptics.tick()
                                    vm.showAllWeeks = false
                                    vm.currentWeek = vm.realCurrentWeek
                                    showWeekPicker = false
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                    val allSelected = vm.showAllWeeks
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (allSelected) MiuixTheme.colorScheme.primary
                                else MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
                            )
                            .clickable {
                                haptics.tick()
                                vm.showAllWeeks = true
                                showWeekPicker = false
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val fg = if (allSelected) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.primary
                        Icon(
                            Icons.Default.GridView,
                            contentDescription = null,
                            tint = fg,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("全学期总览", style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.SemiBold, color = fg)
                    }
                }
                (1..pageWeeksForPicker).chunked(5).forEach { rowWeeks ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowWeeks.forEach { weekN ->
                            val hasCourse = filteredMergedCourses.any { it.isInWeek(weekN) }
                            val isSelected = !vm.showAllWeeks && weekN == vm.currentWeek
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        haptics.tick()
                                        vm.showAllWeeks = false
                                        vm.currentWeek = weekN
                                        showWeekPicker = false
                                    }
                                    .alpha(if (hasCourse) 1f else com.xjtu.toolbox.ui.components.ExpiredStyle.CONTENT_ALPHA),
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) MiuixTheme.colorScheme.primary.copy(alpha = 0.16f)
                                    else MiuixTheme.colorScheme.surfaceVariant,
                            ) {
                                Column(
                                    Modifier.padding(vertical = 10.dp).fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(
                                        "$weekN",
                                        style = MiuixTheme.textStyles.body2,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                                    )
                                    if (weekN == vm.realCurrentWeek) {
                                        Text(
                                            "本周",
                                            style = MiuixTheme.textStyles.footnote2,
                                            color = MiuixTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                        // 不满 5 个时补空位，保持网格对齐。
                        repeat(5 - rowWeeks.size) { Spacer(Modifier.weight(1f)) }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                // 弹层铺到屏幕底边，最后一行会压在系统导航条上；和 AppDialogs 里的弹层一样补一段导航条高度
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        }
    }
    // 顶栏按钮是一段 lambda 交给外层 Scaffold 拿着的，闭包里 currentContent / vm.api 这类
    // 普通 val 是发布那一刻的值：只发布一次的话，切到考试页加号还在、登录晚到 vm.api 仍是 null。
    // 这两个变了就重发一份，别用 SideEffect 每帧发——外层重组会再重组这里，转起来没头。
    DisposableEffect(currentContent, vm.api) {
        onActionsChange(headerActionsContent)
        onBottomContentChange(headerBottomContent)
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
            val staticHeaderShown = (vm.showingStaleData && !vm.isLoading) ||
                (!vm.isLoading && vm.errorMessage == null &&
                    ((currentContent == "week" && vm.isSwitching) || ExamCountdown.next(vm.exams) != null))
            if (staticHeaderShown && contentTopPadding > 0.dp) Spacer(Modifier.height(contentTopPadding))
            val listTopPadding = if (staticHeaderShown) 0.dp else contentTopPadding

            // 缓存数据提示：刷新失败但有缓存时，顶部一条小 banner 告知用户「这可能是旧数据」
            if (vm.showingStaleData && !vm.isLoading) {
                Surface(
                    color = MiuixTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f),
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
                val nextExam = remember(vm.exams) { ExamCountdown.next(vm.exams) }
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
                // 两个横向手势叠在一起会互相抢；切周走周选择胶囊。
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
                        "week" -> Box(Modifier.fillMaxSize()) {
                            // 选周是浮在课表底部、底栏上方的一颗玻璃胶囊（WeekFloatingPill），
                            // 不在顶栏、也不单独占一行。它采样的是下面这层课表。
                            val weekTopPadding = listTopPadding
                            val pillBackdrop = com.xjtu.toolbox.ui.glass.rememberPageGlass()
                            // 课表在滚：胶囊先淡出让路，停下来 700ms 后再浮上来
                            var scrolledAt by remember { mutableLongStateOf(0L) }
                            val scrollWatcher = remember {
                                object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
                                    override fun onPreScroll(
                                        available: androidx.compose.ui.geometry.Offset,
                                        source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
                                    ): androidx.compose.ui.geometry.Offset {
                                        if (kotlin.math.abs(available.y) > 1f) scrolledAt = System.currentTimeMillis()
                                        return androidx.compose.ui.geometry.Offset.Zero
                                    }
                                }
                            }
                            var pillHidden by remember { mutableStateOf(false) }
                            LaunchedEffect(scrolledAt) {
                                if (scrolledAt == 0L) return@LaunchedEffect
                                pillHidden = true
                                kotlinx.coroutines.delay(700)
                                pillHidden = false
                            }
                            AppPullToRefresh(
                                isRefreshing = vm.isRefreshingFromNetwork,
                                // 考试倒计时横幅在每个 tab 上都常驻，下拉刷新时顺带把它也刷了。
                                onRefresh = { vm.refreshSchedule(); vm.refreshExams() },
                                scrollBehavior = topAppBarScrollBehavior,
                                topPadding = weekTopPadding,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .nestedScroll(scrollWatcher)
                                    .then(
                                        if (pillBackdrop != null) Modifier.glassSource(pillBackdrop) else Modifier
                                    ),
                            ) {
                                ScheduleTabContent(
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
                                    // 多留一截给悬浮的选周胶囊，最后一节课能滚到它上面
                                    bottomPadding = contentBottomPadding + 64.dp,
                                    topPadding = weekTopPadding,
                                    textbooks = vm.textbooks,
                                    textbooksProblem = vm.textbooksBackgroundError,
                                    onRequestTextbooks = vm::requestTextbooksInBackground,
                                    onNavigate = onNavigate,
                                    // 宽屏：周视图点课不再弹窗，写进与今日 / 学期两级同一份选中状态，
                                    // 由右栏展示。三个入口一个面板。
                                    onCourseSelected = if (isWideLayout) {
                                        { course, occurrence ->
                                            unifiedOccurrence = occurrence
                                            unifiedSelectedCourse = course
                                        }
                                    } else {
                                        null
                                    },
                                )
                            }
                            WeekFloatingPill(
                                backdrop = pillBackdrop,
                                label = if (vm.showAllWeeks) "全学期" else "第 $vm.currentWeek 周",
                                offWeek = vm.showAllWeeks || (vm.realCurrentWeek > 0 && vm.currentWeek != vm.realCurrentWeek),
                                canPrev = !vm.showAllWeeks && vm.currentWeek > 1,
                                canNext = !vm.showAllWeeks && vm.currentWeek < (vm.totalWeeks.takeIf { it > 0 } ?: TermWeeks.DEFAULT_TOTAL_WEEKS),
                                hidden = pillHidden,
                                onPrev = { haptics.tick(); vm.currentWeek -= 1 },
                                onNext = { haptics.tick(); vm.currentWeek += 1 },
                                onPick = { showWeekPicker = true },
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = contentBottomPadding + 12.dp),
                            )
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
                                    today = java.time.LocalDate.now(),
                                    allCourseNames = remember(filteredMergedCourses) {
                                        filteredMergedCourses.map { it.courseName }.distinct().sorted()
                                    },
                                    onCourseClick = {
                                        unifiedOccurrence = Occurrence(
                                            java.time.LocalDate.now(), vm.realCurrentWeek,
                                        )
                                        unifiedSelectedCourse = it
                                    },
                                    bottomPadding = contentBottomPadding,
                                    topPadding = listTopPadding,
                                    upcoming = upcomingItems,
                                    todayHomework = vm.homeworkDue,
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
                                        unifiedOccurrence = null
                                        unifiedSelectedCourse = it
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
                        remember(weekCourses) { focusCourseOf(weekCourses, java.time.LocalDate.now(), java.time.LocalTime.now()) }
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
                                course = picked,
                                textbooks = vm.textbooks,
                                textbooksProblem = vm.textbooksBackgroundError,
                                termCode = vm.selectedTermCode,
                                occurrence = if (todayFocus != null) {
                                    Occurrence(java.time.LocalDate.now(), vm.realCurrentWeek)
                                } else {
                                    unifiedOccurrence
                                },
                                onRequestTextbooks = vm::requestTextbooksInBackground,
                                onNavigate = onNavigate,
                            )
                        }
                    } else if (onTodayTab) {
                        WeekGlance(courses = weekCourses, today = java.time.LocalDate.now())
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
                            today = java.time.LocalDate.now(),
                            allCourseNames = remember(filteredMergedCourses) {
                                filteredMergedCourses.map { it.courseName }.distinct().sorted()
                            },
                            onCourseClick = {
                                unifiedOccurrence = Occurrence(
                                    java.time.LocalDate.now(), vm.realCurrentWeek,
                                )
                                unifiedSelectedCourse = it
                            },
                            bottomPadding = contentBottomPadding,
                            upcoming = upcomingItems,
                            todayHomework = vm.homeworkDue,
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
            show = showDetail,
            course = course,
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
    courses: List<CourseItem>, currentWeek: Int, totalWeeks: Int,
    showAllWeeks: Boolean, weekNote: String? = null,
    realCurrentWeek: Int = 0, selectedTermCode: String = "", startOfTerm: java.time.LocalDate? = null, currentTermCode: String = "",
    onWeekChange: (Int) -> Unit, onToggleMode: () -> Unit, onAddSchedule: () -> Unit = {},
    customCourses: List<CustomCourseEntity> = emptyList(),
    holidayDates: Map<java.time.LocalDate, String> = emptyMap(),
    onEditCustomCourse: (CustomCourseEntity) -> Unit = {},
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
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val loginStateForBadge = LocalAppLoginState.current
    val badgeEnabled = remember { com.xjtu.toolbox.data.CredentialStore(ctx).scheduleAttendanceBadge }
    var attendanceIndex by remember { mutableStateOf<CourseLinks.AttendanceIndex?>(null) }
    LaunchedEffect(badgeEnabled, selectedTermCode, startOfTerm, totalWeeks) {
        if (!badgeEnabled) { attendanceIndex = null; return@LaunchedEffect }
        // 告诉 CourseLinks 这个学期结没结束——那一层没有教务会话，自己判断不了。
        // 用学期自己的起止判断而不是跟 currentTermCode 比：后者在冷启动阶段会被
        // paintCache 覆盖成正在画的学期。
        CourseLinks.attachContext(
            ctx,
            listOfNotNull(
                selectedTermCode.takeIf {
                    ScheduleCache.isFinishedByDate(startOfTerm, totalWeeks)
                },
            ),
        )
        attendanceIndex = CourseLinks.attendanceIndex(
            loginStateForBadge.sessionManager,
            loginStateForBadge.accountType,
            selectedTermCode,
        )
    }
    val absenceColor = Color(0xFFE5484D)
    val lateColor = Color(0xFFF5A524)
    val leaveColor = Color(0xFF9BA1A6)
    fun badgeOf(slot: com.xjtu.toolbox.schedule.ScheduleSlot, week: Int): com.xjtu.toolbox.schedule.SlotMark? {
        val idx = attendanceIndex ?: return null
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
                color = MiuixTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
            ) {
                Text(
                    weekNote,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onTertiaryContainer
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
                subtitle = "教务还没排课或还没选课。可以下拉刷新、在右上角「更多」里切换学期，或点 + 添加自己的日程",
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
                        val monday = startOfTerm.plusWeeks((weekN - 1).toLong())
                        (0..6).map { monday.plusDays(it.toLong()) }
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
            show = showCourseDetail,
            course = course,
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

@Composable
private fun ScheduleMenuRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = MiuixTheme.colorScheme.onSurfaceContainer)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MiuixTheme.textStyles.body1, color = MiuixTheme.colorScheme.onSurfaceContainer)
    }
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
    course: CourseItem,
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
        // 异步获取教室座位数
        var seatCount by remember { mutableStateOf<Int?>(null) }
        LaunchedEffect(course.location) {
            if (course.location.isNotEmpty()) {
                seatCount = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try { com.xjtu.toolbox.emptyroom.EmptyRoomApi().getRoomSeatCount(course.location) } catch (_: Exception) { null }
                }
            }
        }
        // 标题一段、信息一块、下钻一块。原来是所有行平铺、统一 8dp，
        // 标题、地点、时间、教材、回放一视同仁地排下来，看不出哪些是一组的。
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column {
                Text(
                    course.courseName,
                    style = MiuixTheme.textStyles.headline2,
                    fontWeight = FontWeight.Bold,
                )
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
                val startMinutes = if (course.startMinuteOfDay >= DAY_START_HOUR * 60) {
                    course.startMinuteOfDay
                } else {
                    (DAY_START_HOUR + course.startSection - 1) * 60
                }
                val endMinutes = if (course.endMinuteOfDay > startMinutes) {
                    course.endMinuteOfDay
                } else {
                    (DAY_START_HOUR + course.endSection) * 60
                }
                val endHourRaw = endMinutes / 60
                val endLabel = if (endHourRaw >= 24) "次日00:00"
                else "%02d:%02d".format(endHourRaw, endMinutes % 60)
                "星期$dayName %02d:%02d-$endLabel".format(
                    (startMinutes / 60).coerceIn(0, 23), (startMinutes % 60).coerceIn(0, 59),
                )
            } else {
                "星期$dayName 第${course.startSection}-${course.endSection}节"
            }
            // 具体到某一次时直接报日期，比让人自己数第几周有用。
            val dateText = occurrence?.let {
                "${it.date.monthValue}/${it.date.dayOfMonth} · 第${it.week}周"
            } ?: course.getWeeks().takeIf { it.isNotEmpty() }?.let { "${formatWeeks(it)}周" }

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
    show: MutableState<Boolean>,
    course: CourseItem,
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
            course = course,
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

/** 格式化周次：[1,2,3,5,7,8,9] → "1-3, 5, 7-9" */
private fun formatWeeks(weeks: List<Int>): String {
    if (weeks.isEmpty()) return ""
    val sorted = weeks.sorted()
    val ranges = mutableListOf<String>()
    var start = sorted[0]; var end = sorted[0]
    for (i in 1 until sorted.size) {
        if (sorted[i] == end + 1) { end = sorted[i] }
        else {
            ranges.add(if (start == end) "$start" else "$start-$end")
            start = sorted[i]; end = sorted[i]
        }
    }
    ranges.add(if (start == end) "$start" else "$start-$end")
    return ranges.joinToString(", ")
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



/**
 * 周视图底部悬浮的选周胶囊：「‹  第 3 周  ›」。左右箭头翻周，点中间弹出选择周（含全学期总览、回本周）。
 *
 * 玻璃风格下采样课表那一层（[backdrop]），经典风格退回不透明胶囊。
 * 不在本周（或在看全学期）时周数染主色，提醒「你看的不是这周」。
 * 课表滚动时淡出下沉让路，停下来再浮上来。
 */
@Composable
private fun WeekFloatingPill(
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop?,
    label: String,
    offWeek: Boolean,
    canPrev: Boolean,
    canNext: Boolean,
    hidden: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(50)
    val show by androidx.compose.animation.core.animateFloatAsState(
        if (hidden) 0f else 1f,
        androidx.compose.animation.core.tween(if (hidden) 140 else 260),
        label = "weekPill",
    )
    val surface = MiuixTheme.colorScheme.surfaceContainer
    val fg = MiuixTheme.colorScheme.onSurface
    val accent = if (offWeek) MiuixTheme.colorScheme.primary else fg
    Row(
        modifier
            .graphicsLayer {
                alpha = show
                translationY = (1f - show) * 16.dp.toPx()
            }
            .then(
                if (backdrop != null) {
                    Modifier.drawBackdrop(
                        backdrop = backdrop,
                        shape = { shape },
                        effects = {
                            padding = maxOf(padding, 16.dp.toPx())
                            vibrancy()
                            blur(8.dp.toPx())
                            lens(18.dp.toPx(), 18.dp.toPx())
                        },
                        onDrawSurface = { drawRect(surface.copy(alpha = 0.45f)) },
                    )
                } else {
                    Modifier
                        .shadow(6.dp, shape)
                        .clip(shape)
                        .background(surface)
                }
            )
            .height(48.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrev, enabled = canPrev, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "上一周",
                tint = fg.copy(alpha = if (canPrev) 0.85f else 0.25f),
            )
        }
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .clickable(onClick = onPick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Bold,
                color = accent,
                maxLines = 1,
            )
        }
        IconButton(onClick = onNext, enabled = canNext, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "下一周",
                tint = fg.copy(alpha = if (canNext) 0.85f else 0.25f),
            )
        }
    }
}
