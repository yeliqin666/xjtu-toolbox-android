package com.xjtu.toolbox.card


import androidx.compose.material.icons.automirrored.filled.MenuBook
import com.xjtu.toolbox.ui.glass.tabContentPadding
import com.kyant.backdrop.backdrops.layerBackdrop
import com.xjtu.toolbox.ui.components.AppPullToRefresh
import com.xjtu.toolbox.ui.components.BackButton
import com.xjtu.toolbox.platform.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.produceState
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.sp
import com.xjtu.toolbox.nav.expandOriginSource
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.auth.LocalAuthExpiry
import com.xjtu.toolbox.ui.adaptive.readableWidth
import com.xjtu.toolbox.ui.components.AppDatePickerDialog
import com.xjtu.toolbox.ui.components.AppSegmentedTabs
import com.xjtu.toolbox.ui.components.AppTabPager
import com.xjtu.toolbox.ui.components.EmptyState
import com.xjtu.toolbox.ui.components.ErrorState
import com.xjtu.toolbox.ui.components.LoadingState
import com.xjtu.toolbox.ui.components.MeshBackground
import com.xjtu.toolbox.ui.components.appCardShadow
import com.xjtu.toolbox.ui.components.enterOnce
import com.xjtu.toolbox.ui.glass.CouiPageTopAppBar
import com.xjtu.toolbox.ui.glass.followTopBar
import com.xjtu.toolbox.ui.glass.rememberCouiPageBackdrop
import com.xjtu.toolbox.util.todayOnCampus
import kotlin.math.abs
import kotlin.time.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import com.xjtu.toolbox.nav.AppRoute
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xjtu.toolbox.util.CampusTimeZone
import com.xjtu.toolbox.ui.glass.rememberCouiTopAppBarScrollBehavior

// ==================== 时间范围枚举 ====================

// `DateTimeFormatter.ofPattern("yyyy/M/d")` / `("M/d")` 是 JVM 专属 ⇒ 手写（:core 的既有做法，
// 见 SchoolCalendarScreen / InboxScreen）。口径逐个对齐：年不补零，月、日**不补零**（是 `M/d` 不是 `MM/dd`）。
private fun slashDate(date: LocalDate): String = "${date.year}/${date.month.number}/${date.day}"

private fun slashMonthDay(date: LocalDate): String = "${date.month.number}/${date.day}"

private fun formatRangeChip(start: LocalDate, end: LocalDate): String {
    return if (start.year == end.year) {
        "${slashMonthDay(start)}–${slashMonthDay(end)}"
    } else {
        "${slashDate(start)}–${slashDate(end)}"
    }
}

/** 加餐券入口的状态：值 + 说明 + 待领与待用的总张数（右上角按钮的数字气泡）。 */
data class CouponEntryStat(val value: String = "", val detail: String? = null, val badge: Int = 0)

/** 校园卡：概览 / 流水 / 分析三栏（宽屏左概览右「流水 / 分析」）。 */
@Composable
fun CampusCardScreen(
    /** 本端的取数 + 落盘（Android = `AppCampusCardSource`，Web = `CampusCardNetApi`）。 */
    source: CampusCardSource,
    onBack: () -> Unit,
    // 「界面风格：经典」时传 false，顶栏退回不透明——玻璃开关接口，见 plan2 §16.1
    // 第 5 条。默认 false，和 agent/ProactiveBubble.kt 的 glass 参数一个约定：
    // 接上设置项之前先按「经典」的不透明样式来，不在没接设置项的分支里提前显示玻璃。
    glass: Boolean = false,
    onOpenCoupon: () -> Unit = {},
    /** 「余额和今日消费已落盘」——Android 用它给首页 tab 的缓存版本号 +1（首页 tab 一直留在组合里，只认这个版本号：不递增的话充值后回到首页还是旧余额）；Web 没有那个首页缓存⇒ 空实现。 */
    onCacheUpdated: () -> Unit = {},
    /** 桌面小组件刷新（Android = `CampusCardWidgetUpdater.requestUpdate`）；Web 没有小组件 ⇒ 空。 */
    onBalanceChanged: () -> Unit = {},
    /** 「加餐券」那条二级入口的两行状态（值 + 说明），由宿主读自己那份首页摘要（Android = `HomeStats.pushed(context, AppRoute.Coupon)`，读的是 DataCache/SharedPreferences）。 */
    couponStat: (suspend () -> CouponEntryStat?)? = null,
    /** 宿主的 saved state（「选的时间范围」在进程被回收后要还在）。 */
    savedState: CreationExtras.() -> SavedStateHandle = { SavedStateHandle() },
    /** 「干饭人账单」海报怎么存图（Android 分享、桌面存 PNG）；null（Web）就不显示这个入口。 */
    mealReportExport: MealReportExport? = null,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val authExpiry = LocalAuthExpiry.current
    val vm: CampusCardViewModel = viewModel {
        CampusCardViewModel(source, savedState(), onBalanceChanged)
    }
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                // 会话失效：与校历/体测/成绩同一条缝（:app 注入 AppLoginState.handleAuthExpired）
                CampusCardEvent.AuthExpired -> authExpiry.onAuthExpired(AppRoute.CampusCard, onBack)
                CampusCardEvent.CacheUpdated -> onCacheUpdated()
                is CampusCardEvent.Message -> snackbarHostState.showSnackbar(
                    event.text, duration = if (event.long) SnackbarDuration.Long else SnackbarDuration.Short,
                )
            }
        }
    }
    val transactions = vm.transactions
    val cardInfo = vm.cardInfo
    val stats = vm.stats
    val todaySummary = remember(transactions) { todaySummaryOf(transactions) }

    // 选项卡: 0=概览 1=流水 2=分析
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var showCustomRange by remember { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }

    val scrollBehavior = rememberCouiTopAppBarScrollBehavior()
    var showRangeMenu by remember { mutableStateOf(false) }
    // 当前时间范围的一句话说法：顶栏菜单按钮的无障碍描述、流水和分析栏的小字共用
    val rangeLabel = if (vm.timeRange == TimeRange.CUSTOM) {
        vm.range.let { (start, end) -> formatRangeChip(start, end) }
    } else {
        "近${vm.timeRange.label}"
    }
    // 这一页自己的采样源：顶栏的 COUI 渐变模糊采它，不用全局的 LocalAppBackdrop
    // （这是二级页，有自己的 Scaffold / 顶栏）。其它平台没有 COUI，返回 null。
    val pageBackdrop = rememberCouiPageBackdrop()
    // 采样本页正文：玻璃风格时才录，经典风格顶栏是实色。顶栏用 COUI 那层，底部悬浮切换条的磨砂用 kyant 那层
    val frostLayer = com.kyant.backdrop.backdrops.rememberLayerBackdrop()
    val pageSource = if (glass) {
        // 垫页面底色：录下来透明的地方顶栏模糊会直接放行（见 glassSource）
        Modifier.layerBackdrop(frostLayer).then(pageBackdrop?.recordInto(Modifier) ?: Modifier)
            .background(MiuixTheme.colorScheme.surface)
    } else Modifier
    // 宽屏「流水 / 分析」两栏的选中项。标签行在顶栏里，所以提到 Scaffold 外面
    val isWideTop = com.xjtu.toolbox.ui.isWideLayout()
    var wideTab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CouiPageTopAppBar(
                title = "校园卡",
                color = if (glass) Color.Transparent else MiuixTheme.colorScheme.surface,
                largeTitle = "校园卡",
                scrollBehavior = scrollBehavior,
                pageBackdrop = if (glass) pageBackdrop else null,
                navigationIcon = {
                    BackButton(onBack)
                },
                actions = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 加餐券：右上角一颗按钮，点开从按钮长成页面（同首页右上角圆钮），气泡是待领 + 待用张数。
                        // 宿主没给状态（Web）⇒ 不画
                        if (couponStat != null) {
                            val stat by produceState<CouponEntryStat?>(null) { value = couponStat.invoke() }
                            CouponTopAction(badge = stat?.badge ?: 0, onOpen = onOpenCoupon)
                        }
                        // 时间范围收进顶栏菜单：它同时作用于概览、流水、分析三栏，是整页的设置，
                        // 不该在标签行下面单独占一排胶囊。当前范围由各栏里的小字标出（rangeLabel）。
                        Box {
                        com.xjtu.toolbox.ui.glass.CouiIconMenu(
                            expanded = showRangeMenu,
                            onExpandedChange = { showRangeMenu = it },
                            icon = { Icon(Icons.Default.DateRange, contentDescription = "时间范围：$rangeLabel") },
                        ) {
                            TimeRange.entries.filter { it != TimeRange.CUSTOM }.forEach { range ->
                                option("近${range.label}", selected = range == vm.timeRange) { vm.selectRange(range) }
                            }
                            divider()
                            val custom = vm.timeRange == TimeRange.CUSTOM
                            submenu("自定义", summary = rangeLabel.takeIf { custom }) {
                                quickRanges(todayOnCampus()).forEach { (label, dates) ->
                                    option(label, selected = custom && vm.range == dates) {
                                        vm.selectCustomRange(dates.first, dates.second)
                                    }
                                }
                                divider()
                                option("选择日期…") { showCustomRange = true }
                            }
                        }
                    }
                    }
                },
                // 换时间范围时的进度条留在顶栏下沿；页面放在 App 底栏上时分栏标签也回到这里
                bottomContent = {
                    val showTabs = !(cardInfo == null && transactions.isEmpty() &&
                        (vm.isLoading || vm.errorMessage != null))
                    com.xjtu.toolbox.ui.components.DockedPageTabs(
                        tabs = if (isWideTop) listOf("流水", "分析") else listOf("概览", "流水", "分析"),
                        selectedTabIndex = if (isWideTop) wideTab else selectedTab,
                        onTabSelected = { if (isWideTop) wideTab = it else selectedTab = it },
                        visible = showTabs,
                    )
                    if (vm.isReloadingRange) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            height = 2.dp,
                        )
                    }
                },
            )
        },
    ) { padding ->
        val rangeDates = vm.range
        CustomRangeDialog(
            show = showCustomRange,
            initialStart = rangeDates.first,
            initialEnd = rangeDates.second,
            onDismiss = { showCustomRange = false },
            onConfirm = { start, end ->
                showCustomRange = false
                vm.selectCustomRange(start, end)
            }
        )
        when {
            vm.isLoading && cardInfo == null && transactions.isEmpty() ->
                LoadingState("正在加载校园卡数据…", Modifier.fillMaxSize().padding(padding))
            vm.errorMessage != null && transactions.isEmpty() && cardInfo == null ->
                ErrorState(vm.errorMessage.orEmpty(), { vm.load() }, Modifier.fillMaxSize().padding(padding))
            else -> {
                // 内容改成从顶栏下面穿过（plan2 §16.2）：不再用 Scaffold 的 padding 把整页
                // 往下推，而是让横滑翻页器铺满整个 Box（从 y=0 开始），列表用
                // contentPadding.top 把「顶栏 + 标签行 + 时间选择器」的高度让出来。这样
                // 初始状态和以前看着一样，往上滚动时余额卡才能真的滚到顶栏下面、透出玻璃。
                // 标签行挂在顶栏里（bottomContent），和顶栏一起是一整块玻璃，
                // 所以 Scaffold 的 padding 顶部已经连标签行一起算进去了。
                // 稳定值：顶栏折叠时不跟着每帧变，否则整页每帧重组；差额由下面的 followTopBar 补位移
                val stableTop = com.xjtu.toolbox.ui.glass.rememberStableTopPadding(padding)
                val topInset = stableTop.value
                val topContentPadding = topInset

                var isPullRefreshing by remember { mutableStateOf(false) }
                LaunchedEffect(vm.isLoading, vm.isReloadingRange) {
                    if (!vm.isLoading && !vm.isReloadingRange) isPullRefreshing = false
                }

                // 平板横屏：左栏概览、右栏「流水 / 分析」，一屏同时看到余额、汇总和明细。
                // 三栏是同一份数据的三种看法，经常要对照着看，所以宽屏下并排摆，而不是像设置页那样
                // 做成左侧目录一次只看一栏。宽屏不再用 readableWidth 限宽，否则左右各空出四分之一。
                val isWide = isWideTop
                // 分栏标签（宽屏只有右栏的「流水 / 分析」）
                com.xjtu.toolbox.ui.components.PageBottomTabsHost(
                    tabs = if (isWide) listOf("流水", "分析") else listOf("概览", "流水", "分析"),
                    selectedTabIndex = if (isWide) wideTab else selectedTab,
                    onTabSelected = { if (isWide) wideTab = it else selectedTab = it },
                    glass = if (glass) frostLayer else null,
                    modifier = Modifier.fillMaxSize(),
                ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .then(if (isWide) Modifier else Modifier.readableWidth())
                        .nestedScroll(scrollBehavior.nestedScrollConnection)
                ) {
                    AppPullToRefresh(
                        isRefreshing = isPullRefreshing,
                        onRefresh = {
                            isPullRefreshing = true
                            vm.load(silent = true)
                        },
                        scrollBehavior = scrollBehavior,
                        // 内容从顶栏下面穿过以后，这一层铺满整页、从屏幕顶边算起；
                        // 不告诉它顶栏和标签行有多高，指示器就会从屏幕顶边拉出来，而不是大标题下面。
                        topPadding = topContentPadding,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        // 横滑切栏（概览/流水/分析），用契约组件 AppTabPager；标签行仍由下面的
                        // AppSegmentedTabs 负责点击切换，两者共用同一个 selectedTab。挂上
                        // layerBackdrop，顶栏才能采到「余额卡从这里滚过去」的画面。
                        if (isWide) {
                            Row(Modifier.fillMaxSize().then(pageSource).followTopBar({ stableTop.value }, padding)) {
                                Box(Modifier.weight(0.42f).fillMaxHeight()) {
                                    // 右栏就是完整的流水，左栏不再重复「最近交易」
                                    OverviewTab(
                                        cardInfo, stats.monthly, emptyList(), todaySummary, stats.dailyRate,
                                        stats.activeDays, rangeDates.first, rangeDates.second, topInset,
                                        allTransactions = vm.transactions,
                                        onOpenAnalytics = { wideTab = 1 },
                                    )
                                }
                                Box(Modifier.weight(0.58f).fillMaxHeight()) {
                                    AppTabPager(
                                        pageCount = 2,
                                        selectedTabIndex = wideTab,
                                        onTabSelected = { wideTab = it },
                                        modifier = Modifier.fillMaxSize(),
                                    ) { tab ->
                                        when (tab) {
                                            0 -> TransactionTab(
                                                transactions, transactions.size, vm.isLoadingMore, searchQuery,
                                                onSearchChange = { searchQuery = it }, onLoadMore = vm::loadMore,
                                                topContentPadding = topContentPadding, rangeLabel = rangeLabel,
                                            )
                                            else -> AnalyticsTab(
                                                stats.monthly, stats.categories, stats.mealTimes, stats.weekdayWeekend,
                                                stats.activeDays, rangeDates.first, rangeDates.second,
                                                cardInfo?.balance ?: 0.0, stats.dailyRate, stats.food, stats.hourly,
                                                topContentPadding = topContentPadding, rangeLabel = rangeLabel,
                                                mealReport = stats.mealReport.takeIf { mealReportExport != null }, mealReportExport = mealReportExport,
                                                tags = stats.tags,
                                            )
                                        }
                                    }
                                }
                            }
                        } else AppTabPager(
                            pageCount = 3,
                            selectedTabIndex = selectedTab,
                            onTabSelected = { selectedTab = it },
                            modifier = Modifier.fillMaxSize().then(pageSource).followTopBar({ stableTop.value }, padding),
                        ) { tab ->
                            when (tab) {
                                0 -> OverviewTab(
                                    cardInfo, stats.monthly, transactions.take(5), todaySummary, stats.dailyRate,
                                    stats.activeDays, rangeDates.first, rangeDates.second, topContentPadding,
                                    allTransactions = vm.transactions,
                                    onOpenAnalytics = { selectedTab = 2 },
                                    onOpenTransactions = { selectedTab = 1 },
                                )
                                1 -> TransactionTab(
                                    transactions, transactions.size, vm.isLoadingMore, searchQuery,
                                    onSearchChange = { searchQuery = it }, onLoadMore = vm::loadMore,
                                    topContentPadding = topContentPadding, rangeLabel = rangeLabel,
                                )
                                2 -> AnalyticsTab(
                                    stats.monthly, stats.categories, stats.mealTimes, stats.weekdayWeekend,
                                    stats.activeDays, rangeDates.first, rangeDates.second,
                                    cardInfo?.balance ?: 0.0, stats.dailyRate, stats.food, stats.hourly,
                                    topContentPadding = topContentPadding, rangeLabel = rangeLabel,
                                    mealReport = stats.mealReport.takeIf { mealReportExport != null }, mealReportExport = mealReportExport,
                                    tags = stats.tags,
                                )
                            }
                        }
                    }
                }
                }
            }
        }
    }
}

/**
 * 顶栏右上角的加餐券按钮：点开从这颗按钮长成加餐券页（[com.xjtu.toolbox.nav.ExpandOrigins]，同首页右上角圆钮），
 * 右上角气泡是待领 + 待用的张数，转场时跟按钮一起藏起来。
 */
@Composable
private fun CouponTopAction(badge: Int, onOpen: () -> Unit) {
    val source = com.xjtu.toolbox.nav.rememberExpandOriginSource()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val tint = MiuixTheme.colorScheme.onSurface
    // 不再额外留 4dp：多钮间距统一由外层 Row(2dp) + 每颗钮 4dp 内边距给出 10dp（对齐小布记忆）
    Box {
        com.xjtu.toolbox.ui.glass.CouiToolbarActionButton(
            onClick = {
                // 不给替身：转场里叠加层画的就是这颗按钮此刻的真实画面（含圆底），
                // 面板底色贴近按钮时透明，落地前后是同一份像素
                source.arm(
                    AppRoute.Coupon.id, cornerRadius = 20.dp, density = density,
                    restFill = androidx.compose.ui.graphics.Color.Transparent, restShadow = false,
                )
                onOpen()
            },
            modifier = Modifier
                .expandOriginSource(source)
                .semantics(mergeDescendants = true) {
                    contentDescription = if (badge > 0) "加餐券，$badge 张待领或待用" else "加餐券"
                },
        ) {
            Icon(Icons.Default.Restaurant, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        }
        if (badge > 0) {
            Text(
                if (badge > 99) "99+" else "$badge",
                fontSize = 10.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.Bold,
                color = androidx.compose.ui.graphics.Color.White,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    // 圆钮视觉直径还是 40dp，但交互区 48dp，角标按内圈往外让 4dp
                    .offset(x = 2.dp, y = 8.dp)
                    .graphicsLayer { alpha = if (com.xjtu.toolbox.nav.ExpandOrigins.isHidden(source)) 0f else 1f }
                    .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MiuixTheme.colorScheme.error)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

// ==================== 概览 Tab ====================

@Composable
private fun OverviewTab(
    cardInfo: CardInfo?,
    monthlyStats: List<MonthlyStats>,
    recentTransactions: List<Transaction>,
    today: TodaySpendSummary,
    dailyRate: Double?,
    activeCampusDays: Int,
    rangeStart: LocalDate,
    rangeEnd: LocalDate,
    // 顶栏 + 标签行 + 时间选择器的高度，给列表让出来（plan2 §16.2）。
    topContentPadding: Dp = 0.dp,
    /** 全部已加载流水，「今天吃啥」按它算就餐习惯。 */
    allTransactions: List<Transaction> = emptyList(),
    onOpenAnalytics: () -> Unit = {},
    onOpenTransactions: () -> Unit = {},
) {
    // 近三周没吃过饭时「今天吃啥」自己什么都不画，先在这里判掉：留个空项，列表间距照算，会多出一截空白
    val hasMeals = remember(allTransactions) { mealPicksInput(allTransactions, null, null) != null }
    LazyColumn(
        state = com.xjtu.toolbox.ui.glass.rememberReportedLazyListState(),
        // 左右留白放在 contentPadding 里：放在列表外面会切掉余额卡投影
        modifier = Modifier.fillMaxSize().overScrollVertical(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = tabContentPadding(PaddingValues(start = 16.dp, end = 16.dp, top = topContentPadding + 12.dp, bottom = 12.dp))
    ) {
        // 现在（余额 + 今天）→ 今天吃啥 → 这段时间 → 最近交易；后两块是流水、分析两栏的入口
        cardInfo?.let { info ->
            item { Box(Modifier.enterOnce(0)) { BalanceCard(info, runwayDays(info.balance, dailyRate), dailyRate, today) } }
        }
        if (hasMeals && com.xjtu.toolbox.agent.AgentInsightHub.insights.available) {
            item { Box(Modifier.enterOnce(1)) { MealPicksCard(allTransactions, cardInfo?.balance, dailyRate) } }
        }
        item {
            Box(Modifier.enterOnce(2)) {
                RangeSpendCard(CampusCardAnalysis.summarizeRange(monthlyStats, rangeStart, rangeEnd), activeCampusDays, onOpenAnalytics)
            }
        }
        if (recentTransactions.isNotEmpty()) {
            item {
                OverviewCard(Modifier.enterOnce(3)) {
                    OverviewHeader("最近交易", Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp)) {
                        com.xjtu.toolbox.ui.components.TextLink(
                            text = "全部",
                            onClick = onOpenTransactions,
                            chevron = true,
                        )
                    }
                    TransactionRows(recentTransactions)
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

@Composable
private fun OverviewCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val colors = CardDefaults.defaultColors(color = com.xjtu.toolbox.ui.components.AppCardColor)
    if (onClick == null) {
        Card(modifier.fillMaxWidth(), cornerRadius = 20.dp, colors = colors) { content() }
    } else {
        Card(
            modifier = modifier.fillMaxWidth(),
            cornerRadius = 20.dp,
            colors = colors,
            pressFeedbackType = top.yukonga.miuix.kmp.utils.PressFeedbackType.Sink,
            onClick = onClick,
        ) { content() }
    }
}

/** 概览各块统一的标题行。 */
@Composable
private fun OverviewHeader(title: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MiuixTheme.textStyles.subtitle, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        trailing()
    }
}

// 余额卡的 Mesh 顶点颜色：3x3 网格，中间那个顶点缓慢漂移，四角、四边中点固定在卡片边框上，
// 保证渐变始终铺满整张卡。
// 余额卡原来是一整块深蓝，在一片白卡里很突兀。改成浅色：白底上一层很淡的蓝色流光，
// 和页面上其他卡片是一个体系，余额数字用主题色做重点。深色模式同理：贴近卡片底色，只带一点蓝。
private val BalanceCardMeshLight = listOf(
    listOf(Color(0xFFEAF2FF), Color(0xFFF7FAFF), Color(0xFFE2EDFF)),
    listOf(Color(0xFFF4F8FF), Color(0xFFD3E5FF), Color(0xFFF1F6FF)),
    listOf(Color(0xFFE4EEFF), Color(0xFFF8FBFF), Color(0xFFDCEAFF)),
)
private val BalanceCardMeshDark = listOf(
    listOf(Color(0xFF1B2433), Color(0xFF20242C), Color(0xFF1A2536)),
    listOf(Color(0xFF1F242D), Color(0xFF1E3350), Color(0xFF20252E)),
    listOf(Color(0xFF1A2434), Color(0xFF21252D), Color(0xFF1B2839)),
)

@Composable
private fun BalanceCard(info: CardInfo, runway: Int?, dailyRate: Double?, today: TodaySpendSummary) {
    // 浅色卡：余额和图标用主题色，其余文字用常规的正文 / 次要文字颜色
    val accent = MiuixTheme.colorScheme.primary
    val secondary = MiuixTheme.colorScheme.onSurfaceVariantSummary
    top.yukonga.miuix.kmp.basic.Card(
        // 这一页的主角，托一层带主题色的柔影（和首页 Hero 卡同一档）
        modifier = Modifier
            .fillMaxWidth()
            .then(Modifier.appCardShadow(shape = RoundedCornerShape(24.dp), strong = true)),
        cornerRadius = 24.dp,
        colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = Color.Transparent)
    ) {
        Box(Modifier.fillMaxWidth()) {
            MeshBackground(
                modifier = Modifier.matchParentSize(),
                lightVertexColors = BalanceCardMeshLight,
                darkVertexColors = BalanceCardMeshDark,
                // 这张卡在玻璃顶栏的取样范围里：一直流动的话，静止时顶栏也在持续重新模糊
                runForMillis = 6_000L,
            )
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("校园卡余额", style = MiuixTheme.textStyles.body2,
                            color = secondary)
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("¥", style = MiuixTheme.textStyles.title4,
                                fontWeight = FontWeight.Medium,
                                color = accent,
                                modifier = Modifier.padding(bottom = 4.dp))
                            Spacer(Modifier.width(2.dp))
                            // 进页面时从 0 滚到余额，刷新后从旧值滚到新值
                            com.xjtu.toolbox.ui.components.RollingNumberText(
                                value = info.balance,
                                format = { money2(it) },
                                style = MiuixTheme.textStyles.title1,
                                fontWeight = FontWeight.Bold,
                                color = accent,
                            )
                        }
                    }
                    Surface(shape = CircleShape,
                        color = accent.copy(alpha = 0.12f),
                        modifier = Modifier.size(48.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.CreditCard, null,
                                tint = accent,
                                modifier = Modifier.size(24.dp))
                        }
                    }
                }
                val identity = listOf(info.name, info.cardType, info.account.takeIf { it.isNotBlank() }?.let { "一卡通号 $it" })
                    .filter { !it.isNullOrBlank() }.joinToString(" · ")
                val pending = if (info.pendingAmount > 0) "待入账 ¥" + money2(info.pendingAmount) else null
                listOfNotNull(pending, identity.ifBlank { null }).joinToString("　").takeIf { it.isNotEmpty() }?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MiuixTheme.textStyles.footnote1, color = secondary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (info.lostFlag || info.frozenFlag) {
                    val error = MiuixTheme.colorScheme.error
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(error.copy(alpha = 0.10f)).padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(if (info.frozenFlag) Icons.Default.Lock else Icons.Default.Warning, null,
                            tint = error, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            listOfNotNull("已挂失".takeIf { info.lostFlag }, "已冻结".takeIf { info.frozenFlag })
                                .joinToString("、") + "，消费和付款码可能暂不可用",
                            style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Medium, color = error,
                        )
                    }
                }
                if (runway != null && dailyRate != null) {
                    // 油表：满格按 30 天算，三天以内变红
                    val low = runway <= 3
                    val gauge = if (low) MiuixTheme.colorScheme.error else accent
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.HourglassBottom, null, tint = gauge, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(runwayText(runway), style = MiuixTheme.textStyles.body2,
                            fontWeight = FontWeight.Medium, color = gauge, modifier = Modifier.weight(1f))
                        Text("近 30 天日均 ¥" + money1(dailyRate),
                            style = MiuixTheme.textStyles.footnote2, color = secondary)
                    }
                    Spacer(Modifier.height(6.dp))
                    com.xjtu.toolbox.ui.components.AnimatedBar(
                        progress = (runway / 30f).coerceIn(0.03f, 1f),
                        color = gauge,
                        trackColor = gauge.copy(alpha = 0.14f),
                        height = 6.dp,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(16.dp))
                TodayMealsStrip(today, secondary)
            }
        }
    }
}

/** 余额卡底部的今天三餐：「现在」的另一半，和余额放一起看。 */
@Composable
private fun TodayMealsStrip(today: TodaySpendSummary, secondary: Color) {
    val hour = Clock.System.now().toLocalDateTime(CampusTimeZone).hour
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("今天", style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f))
        Text(if (today.total > 0) "共 ¥" + money2(today.total) else "还没刷卡",
            style = MiuixTheme.textStyles.footnote1, color = secondary)
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val palette = campusCardPalette()
        listOf(
            Triple("早餐", today.breakfast, 5..10) to palette[3],
            Triple("午餐", today.lunch, 11..14) to palette[7],
            Triple("晚餐", today.dinner, 17..21) to palette[2],
        ).forEach { (meal, color) ->
            val (name, amount, hours) = meal
            val eaten = amount > 0
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                    .background(color.copy(alpha = if (eaten) 0.16f else 0.07f))
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(name, style = MiuixTheme.textStyles.footnote1, color = color, fontWeight = FontWeight.Medium)
                Text(
                    when {
                        eaten -> "¥" + money1(amount)
                        hour > hours.last -> "没刷卡"
                        hour >= hours.first -> "饭点中"
                        else -> "—"
                    },
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = if (eaten) FontWeight.Bold else FontWeight.Normal,
                    color = if (eaten) MiuixTheme.colorScheme.onSurface else secondary,
                )
            }
        }
    }
}

/** 这段时间的总账；明细在分析栏，整卡点进去。 */
@Composable
private fun RangeSpendCard(summary: RangeSpendSummary, activeDays: Int, onOpen: () -> Unit) {
    val secondary = MiuixTheme.colorScheme.onSurfaceVariantSummary
    OverviewCard(onClick = onOpen) {
        Column(Modifier.padding(start = 20.dp, end = 16.dp, top = 8.dp, bottom = 18.dp)) {
            OverviewHeader(summary.title) {
                summary.subtitle?.let { Text(it, style = MiuixTheme.textStyles.footnote1, color = secondary) }
                Icon(Icons.Default.ChevronRight, null, tint = secondary, modifier = Modifier.size(20.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("¥" + money0(summary.totalSpend), style = MiuixTheme.textStyles.title2,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (summary.changePercent != null && summary.changeCaption != null) {
                    val change = summary.changePercent
                    val isUp = change > 0
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isUp) MiuixTheme.colorScheme.errorContainer
                        else MiuixTheme.colorScheme.secondaryContainer
                    ) {
                        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (isUp) Icons.AutoMirrored.Filled.TrendingUp
                                else Icons.AutoMirrored.Filled.TrendingDown,
                                null, modifier = Modifier.size(14.dp),
                                tint = if (isUp) MiuixTheme.colorScheme.onErrorContainer
                                else MiuixTheme.colorScheme.onSecondaryContainer)
                            Spacer(Modifier.width(2.dp))
                            Text(
                                "${summary.changeCaption} " + percent0(abs(change)),
                                style = MiuixTheme.textStyles.footnote1,
                                fontWeight = FontWeight.Bold,
                                color = if (isUp) MiuixTheme.colorScheme.onErrorContainer
                                else MiuixTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(com.xjtu.toolbox.ui.components.AppInsetColor).padding(vertical = 10.dp),
            ) {
                StatColumn("充值", "¥" + money0(summary.totalIncome))
                StatColumn("笔数", "${summary.transactionCount}")
                // 按刷过卡的天数摊，和分析页头卡同一口径；放假的日子不算进分母
                if (activeDays > 0) StatColumn("在校日均", "¥" + money1(summary.totalSpend / activeDays))
                else StatColumn("日均", "¥" + money1(summary.avgDailySpend))
            }
        }
    }
}

@Composable
private fun RowScope.StatColumn(label: String, value: String) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

// ==================== 流水 Tab ====================

@Composable
private fun TransactionTab(
    transactions: List<Transaction>,
    total: Int,
    isLoadingMore: Boolean,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onLoadMore: () -> Unit,
    topContentPadding: Dp = 0.dp,
    /** 当前时间范围（如「近1个月」）。时间范围在顶栏菜单里，这里用小字交代一句。 */
    rangeLabel: String = "",
) {
    val filtered = remember(transactions, searchQuery) {
        if (searchQuery.isBlank()) transactions
        else transactions.filter { tx ->
            tx.displayMerchant.contains(searchQuery, ignoreCase = true) ||
                tx.type.contains(searchQuery, ignoreCase = true) ||
                tx.description.contains(searchQuery, ignoreCase = true)
        }
    }
    // `Map.toSortedMap(compareByDescending { it })` 是 JVM 专属（`kotlin.collections` 里的 JVM 扩展，
    // 默认导入 ⇒ import 判据看不见）⇒ 换成「按日期倒序排好的 entries 列表」，下面 forEachIndexed
    // 的 `(date, txList)` 解构照样成立（`Map.Entry` 有 component1/component2）。
    val grouped = remember(filtered) {
        filtered.groupBy { it.time.substringBefore(" ") }
            .entries
            .sortedByDescending { it.key }
    }

    LazyColumn(
        state = com.xjtu.toolbox.ui.glass.rememberReportedLazyListState(),
        modifier = Modifier.fillMaxSize().overScrollVertical().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = tabContentPadding(PaddingValues(top = topContentPadding + 12.dp, bottom = 12.dp))
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    com.xjtu.toolbox.ui.components.AppSearchBar(
                        query = searchQuery,
                        onQueryChange = onSearchChange,
                        label = "搜索商户或交易类型",
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            if (searchQuery.isNotBlank()) "搜索结果: ${filtered.size} 笔"
                            else if (rangeLabel.isNotEmpty()) "$rangeLabel · 共 $total 笔"
                            else "共 $total 笔交易",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        val totalSpend = filtered.filter { it.amount < 0 }.sumOf { -it.amount }
                        val totalIncome = filtered.filter { it.amount > 0 }.sumOf { it.amount }
                        Text("支出¥" + money0(totalSpend) + " | 收入¥" + money0(totalIncome),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
        }
        grouped.forEachIndexed { dayIndex, (date, txList) ->
            item(key = "day_$date") {
                Card(
                    modifier = Modifier.fillMaxWidth().enterOnce(dayIndex + 1),
                    colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant)
                ) {
                    Column {
                        val dayTotal = -txList.filter { it.amount < 0 }.sumOf { it.amount }
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(formatDateHeader(date),
                                style = MiuixTheme.textStyles.body2,
                                fontWeight = FontWeight.Medium,
                                color = MiuixTheme.colorScheme.primary)
                            if (dayTotal > 0) {
                                Text("−¥" + money2(dayTotal),
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.error.copy(alpha = 0.7f))
                            }
                        }
                        TransactionRows(txList)
                    }
                }
            }
        }
        if (searchQuery.isBlank() && transactions.size < total) {
            item {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    if (isLoadingMore) CircularProgressIndicator(size = 24.dp)
                    else com.xjtu.toolbox.ui.components.TextLink(text = "加载更多", onClick = onLoadMore)
                }
            }
        }
        if (filtered.isEmpty() && searchQuery.isNotBlank()) {
            item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.SearchOff, null, modifier = Modifier.size(48.dp),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.3f))
                        Spacer(Modifier.height(8.dp))
                        Text("未找到匹配的交易", style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
        }
    }
}

/** 一组流水，行间细线从文字起点开始（16 边距 + 40 图标 + 12 间距）。 */
@Composable
private fun TransactionRows(list: List<Transaction>) {
    list.forEachIndexed { i, tx ->
        if (i > 0) com.xjtu.toolbox.ui.components.RowDivider(startInset = 68.dp, endInset = 16.dp)
        TransactionItem(tx)
    }
}

@Composable
private fun TransactionItem(tx: Transaction) {
    val isExpense = tx.amount < 0
    val icon = getTransactionIcon(tx)
    val iconBg = if (isExpense) MiuixTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
    else MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)

    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = iconBg, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, null, modifier = Modifier.size(20.dp),
                        tint = if (isExpense) MiuixTheme.colorScheme.error
                        else MiuixTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(tx.displayMerchant.ifBlank { tx.type.ifBlank { tx.description.ifBlank { "未知交易" } } },
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(tx.time.substringAfter(" ").substringBeforeLast(":"),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text((if (isExpense) "-" else "+") + "¥" + money2(abs(tx.amount)),
                    style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Bold,
                    color = if (isExpense) MiuixTheme.colorScheme.error
                    else MiuixTheme.colorScheme.primary)
                Text("余额 ¥" + money2(tx.balance),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.6f))
            }
        }
}

// ==================== 分析 Tab ====================

@Composable
private fun AnalyticsTab(
    monthlyStats: List<MonthlyStats>,
    categorySpending: Map<String, Double>,
    mealTimeStats: Map<String, MealTimeStats>,
    weekdayWeekend: Pair<DayTypeStats, DayTypeStats>?,
    activeCampusDays: Int,
    rangeStart: LocalDate,
    rangeEnd: LocalDate,
    /** 当前余额，用来估「还能撑几天」；取不到传 0 就不给这条。 */
    balance: Double,
    dailyRate: Double?,
    foodBreakdown: Map<String, Double>,
    hourlyMeals: List<Int>,
    topContentPadding: Dp = 0.dp,
    /** 当前时间范围（如「近1个月」），见 [TransactionTab] 同名参数。 */
    rangeLabel: String = "",
    mealReport: MealReport? = null,
    mealReportExport: MealReportExport? = null,
    tags: List<Pair<String, String>> = emptyList(),
) {
    var showReport by remember { mutableStateOf(false) }
    if (showReport && mealReport != null && mealReportExport != null) {
        MealReportDialog(mealReport, mealReportExport) { showReport = false }
    }
    val foodSpend = categorySpending["餐饮"] ?: 0.0
    val meals = mealTimeStats.values.sumOf { it.count }
    val totalSpend = monthlyStats.sumOf { it.totalSpend }
    LazyColumn(
        state = com.xjtu.toolbox.ui.glass.rememberReportedLazyListState(),
        modifier = Modifier.fillMaxSize().overScrollVertical().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = tabContentPadding(PaddingValues(top = topContentPadding + 12.dp, bottom = 12.dp))
    ) {
        if (categorySpending.isEmpty() && monthlyStats.isEmpty() && mealTimeStats.isEmpty()) {
            item {
                EmptyState(
                    title = "这段时间没有消费记录",
                    modifier = Modifier.fillParentMaxSize()
                )
            }
        } else {
            // 各统计卡依次登场，卡里的条形图随后从左边长出来
            item {
                Box(Modifier.enterOnce(0)) {
                    SpendingHeroCard(totalSpend, activeCampusDays, CampusCardAnalysis.perMeal(mealTimeStats), tags, rangeLabel)
                }
            }
            if (mealReport != null) {
                item { Box(Modifier.enterOnce(1)) { MealReportEntry(mealReport) { showReport = true } } }
            }
            // 主食构成排在顶层类别之前：食堂党的钱 95% 以上都在「餐饮」那一块里
            if (foodBreakdown.isNotEmpty()) {
                item { Box(Modifier.enterOnce(1)) { FoodBreakdownCard(foodBreakdown, meals, foodSpend) } }
            }
            // 某一类独占九成时这张卡只剩一根满条，主食构成已经说完了，不再单独占一屏
            val catTotal = categorySpending.values.sum()
            val catSpread = catTotal > 0 && (categorySpending.values.maxOrNull() ?: 0.0) / catTotal < 0.9
            if (catSpread) { item { Box(Modifier.enterOnce(2)) { CategoryCard(categorySpending) } } }
            if (hourlyMeals.any { it > 0 }) { item { Box(Modifier.enterOnce(2)) { MealRhythmCard(hourlyMeals, mealTimeStats) } } }
            if (monthlyStats.isNotEmpty()) { item { Box(Modifier.enterOnce(3)) { TopMerchantsCard(monthlyStats) } } }
            // 只有一个月时趋势就是一根条，概览页的区间卡已经写了这个数
            if (monthlyStats.count { it.totalSpend > 0 } >= 2) { item { Box(Modifier.enterOnce(3)) { MonthlyTrendCard(monthlyStats, rangeStart, rangeEnd) } } }
            item { Box(Modifier.enterOnce(4)) { SpendingInsightsCard(monthlyStats, categorySpending, mealTimeStats, weekdayWeekend, activeCampusDays, rangeStart, rangeEnd, balance, dailyRate) } }
        }
    }
}


@Composable
private fun CustomRangeDialog(
    show: Boolean,
    initialStart: LocalDate,
    initialEnd: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate, LocalDate) -> Unit,
) {
    var draftStart by remember(show, initialStart) { mutableStateOf(initialStart) }
    var draftEnd by remember(show, initialEnd) { mutableStateOf(initialEnd) }
    var picking by remember(show) { mutableStateOf<String?>(null) }
    val today = remember { todayOnCampus() }
    val earliest = remember { today.minus(6, DateTimeUnit.YEAR) }

    BackHandler(enabled = show && picking == null) { onDismiss() }
    OverlayDialog(
        show = show,
        title = "自定义时间段",
        onDismissRequest = onDismiss
    ) {
        ArrowPreference(
            title = "开始",
            summary = slashDate(draftStart),
            onClick = { picking = "start" }
        )
        ArrowPreference(
            title = "结束",
            summary = slashDate(draftEnd),
            onClick = { picking = "end" }
        )
        if (draftStart > draftEnd) {
            Text(
                "开始日晚于结束日时，确定后会自动对调。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
        Row(Modifier.fillMaxWidth()) {
            TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(20.dp))
            TextButton(
                text = "确定",
                onClick = {
                    if (draftStart > draftEnd) onConfirm(draftEnd, draftStart)
                    else onConfirm(draftStart, draftEnd)
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary()
            )
        }
    }
    AppDatePickerDialog(
        show = picking != null,
        title = if (picking == "end") "结束日期" else "开始日期",
        date = if (picking == "end") draftEnd else draftStart,
        minDate = earliest,
        maxDate = today,
        onDismiss = { picking = null },
        onConfirm = { picked ->
            if (picking == "end") draftEnd = picked else draftStart = picked
            picking = null
        }
    )
}

@Composable
private fun CategoryCard(categories: Map<String, Double>) {
    val total = categories.values.sum()
    val categoryIcons = mapOf(
        "餐饮" to Icons.Default.Restaurant, "超市" to Icons.Default.ShoppingCart,
        "洗浴" to Icons.Default.Shower, "水电" to Icons.Default.ElectricBolt,
        "学习" to Icons.AutoMirrored.Filled.MenuBook, "洗衣" to Icons.Default.LocalLaundryService,
        "交通" to Icons.Default.DirectionsBus, "医疗" to Icons.Default.LocalHospital,
        "充值" to Icons.Default.AddCard, "其他" to Icons.Default.MoreHoriz)
    val categoryColors = categoryIcons.keys.zip(campusCardPalette()).toMap()

    top.yukonga.miuix.kmp.basic.Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
        Column(Modifier.padding(20.dp)) {
            AnalyticsHeader(Icons.Default.PieChart, "消费类别", "总计 ¥" + money0(total))
            Spacer(Modifier.height(16.dp))
            categories.entries.forEach { (category, amount) ->
                val percent = if (total > 0) amount / total else 0.0
                val color = categoryColors[category] ?: MiuixTheme.colorScheme.outline
                val icon = categoryIcons[category] ?: Icons.Default.MoreHoriz
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, modifier = Modifier.size(20.dp), tint = color)
                    Spacer(Modifier.width(8.dp))
                    Text(category, style = MiuixTheme.textStyles.body2,
                        modifier = Modifier.width(48.dp))
                    com.xjtu.toolbox.ui.components.AnimatedBar(
                            progress = percent.toFloat().coerceIn(0f, 1f),
                            color = color,
                            modifier = Modifier.weight(1f),
                            trackColor = color.copy(alpha = 0.12f),
                            height = 8.dp,
                        )
                    Spacer(Modifier.width(8.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text("¥" + money0(amount), style = MiuixTheme.textStyles.footnote1,
                            fontWeight = FontWeight.Medium)
                        Text(percent0(percent * 100), style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
        }
    }
}

/**
 * 主食构成：环形图 + 图例。
 *
 * 顶层「消费类别」那张卡在食堂党身上基本失效——餐饮常年 95% 以上，一张只有一块的饼。
 * 这张把餐饮内部拆开（面食 / 米饭 / 自选 / 汤粥 / 小吃 / 饺包 / 饮品），才看得出口味。
 */
@Composable
private fun FoodBreakdownCard(breakdown: Map<String, Double>, mealCount: Int, foodSpend: Double) {
    if (breakdown.isEmpty()) return
    val total = breakdown.values.sum()
    if (total <= 0) return
    val entries = breakdown.entries.toList()
    val foodColors = listOf("面食", "米饭", "自选", "汤粥", "小吃", "饺包", "饮品").zip(campusCardPalette()).toMap()

    // 一次扫过整圈的入场动画。进度在 drawBehind 里读，动画期间只重画这一个环。
    val sweep = com.xjtu.toolbox.ui.components.rememberRevealProgress(
        breakdown, spec = tween(600, easing = androidx.compose.animation.core.FastOutSlowInEasing),
    )
    val trackColor = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.12f)

    top.yukonga.miuix.kmp.basic.Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
        Column(Modifier.padding(20.dp)) {
            AnalyticsHeader(Icons.Default.Restaurant, "主食构成", "¥" + money0(if (foodSpend > 0) foodSpend else total))
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(116.dp)
                        .drawBehind {
                            val stroke = 20.dp.toPx()
                            val inset = stroke / 2f
                            val arcSize = androidx.compose.ui.geometry.Size(
                                size.width - stroke, size.height - stroke
                            )
                            val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
                            drawArc(
                                color = trackColor, startAngle = 0f, sweepAngle = 360f, useCenter = false,
                                topLeft = topLeft, size = arcSize,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke),
                            )
                            // 从 12 点开始顺时针铺，每段之间留 2° 缝。平头收尾：圆头会各向外多出半个线宽，
                            // 几个 1%–5% 的小段就叠成一团
                            var angle = -90f
                            entries.forEach { (name, amount) ->
                                val full = (amount / total * 360f).toFloat()
                                val shown = full * sweep.value
                                if (shown > 0.5f) {
                                    drawArc(
                                        color = foodColors[name] ?: trackColor,
                                        startAngle = angle, sweepAngle = (shown - 2f).coerceAtLeast(0.8f),
                                        useCenter = false, topLeft = topLeft, size = arcSize,
                                        style = androidx.compose.ui.graphics.drawscope.Stroke(stroke),
                                    )
                                }
                                angle += full
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "$mealCount", style = MiuixTheme.textStyles.title3,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "顿饭", style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f)) {
                    entries.forEach { (name, amount) ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(foodColors[name] ?: trackColor)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(name, style = MiuixTheme.textStyles.body2, modifier = Modifier.weight(1f))
                            Text(
                                "¥" + money0(amount), style = MiuixTheme.textStyles.footnote1,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                percent0(amount / total * 100),
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.width(34.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthlyTrendCard(stats: List<MonthlyStats>, rangeStart: LocalDate, rangeEnd: LocalDate) {
    // 掐掉首尾的空月（还没办卡、整段假期）；中间的空月留着，那是真的一个月没消费
    val months = stats.sortedBy { it.month }
        .dropWhile { it.totalSpend <= 0 }
        .dropLastWhile { it.totalSpend <= 0 }
    if (months.isEmpty()) return
    val spanYears = CampusCardAnalysis.spansYears(rangeStart, rangeEnd)
    fun label(m: MonthlyStats) =
        if (spanYears) "${m.month.year % 100}/${m.month.month.number}" else "${m.month.month.number}月"
    val max = months.maxOf { it.totalSpend }
    // 刷卡不足 5 天的月份多半是假期，画浅色，也不进月均
    val inSchool = months.filter { it.spendDays >= 5 }
    val avg = if (inSchool.isEmpty()) null else inSchool.sumOf { it.totalSpend } / inSchool.size
    val peak = months.maxBy { it.totalSpend }
    val accent = MiuixTheme.colorScheme.primary
    val guide = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f)
    val grow = com.xjtu.toolbox.ui.components.rememberRevealProgress(
        months, spec = tween(600, easing = androidx.compose.animation.core.FastOutSlowInEasing),
    )
    // 月份多时隔几个标一次，最多 8 个
    val labelEvery = (months.size + 7) / 8

    Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
        Column(Modifier.padding(20.dp)) {
            AnalyticsHeader(Icons.Default.BarChart, "月度趋势", avg?.let { "在校月均 ¥" + money0(it) })
            Spacer(Modifier.height(18.dp))
            Box(
                Modifier.fillMaxWidth().height(120.dp).drawBehind {
                    val slot = size.width / months.size
                    val barW = minOf(slot * 0.58f, 26.dp.toPx())
                    val radius = androidx.compose.ui.geometry.CornerRadius(minOf(barW / 2f, 6.dp.toPx()))
                    months.forEachIndexed { i, m ->
                        val h = (size.height * (m.totalSpend / max).toFloat() * grow.value).coerceAtLeast(if (m.totalSpend > 0) 2.dp.toPx() else 0f)
                        drawRoundRect(
                            color = if (m.spendDays >= 5) accent else accent.copy(alpha = 0.3f),
                            topLeft = androidx.compose.ui.geometry.Offset(i * slot + (slot - barW) / 2f, size.height - h),
                            size = androidx.compose.ui.geometry.Size(barW, h),
                            cornerRadius = radius,
                        )
                    }
                    avg?.let {
                        val y = size.height * (1f - (it / max).toFloat())
                        drawLine(
                            guide, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y),
                            strokeWidth = 1.dp.toPx(),
                            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
                        )
                    }
                },
            )
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth()) {
                // 标签居中对准柱子，可以溢出到旁边没标的格子里
                months.forEachIndexed { i, m ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (i % labelEvery == 0) {
                            Text(
                                label(m), style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                maxLines = 1, softWrap = false,
                                modifier = Modifier.wrapContentWidth(unbounded = true),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "最高 ${label(peak)} ¥${money0(peak.totalSpend)}" +
                    (if (avg != null) " · 虚线为在校月均" else "") +
                    (if (inSchool.size < months.size) " · 浅色为假期月" else ""),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun SpendingInsightsCard(
    stats: List<MonthlyStats>,
    categories: Map<String, Double>,
    mealStats: Map<String, MealTimeStats>,
    weekdayWeekend: Pair<DayTypeStats, DayTypeStats>?,
    activeCampusDays: Int,
    rangeStart: LocalDate,
    rangeEnd: LocalDate,
    balance: Double,
    dailyRate: Double?,
) {
    val insights = remember(stats, categories, mealStats, weekdayWeekend, activeCampusDays, rangeStart, rangeEnd, balance, dailyRate) {
        generateInsights(stats, categories, mealStats, weekdayWeekend, activeCampusDays, rangeStart, rangeEnd, balance, dailyRate)
    }
    if (insights.isEmpty()) return
    val tints = campusCardPalette()

    top.yukonga.miuix.kmp.basic.Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
        Column(Modifier.padding(20.dp)) {
            AnalyticsHeader(Icons.Default.Lightbulb, "消费小结")
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(com.xjtu.toolbox.ui.components.AppInsetColor).padding(vertical = 4.dp),
            ) {
                insights.forEachIndexed { i, (icon, text) ->
                    if (i > 0) com.xjtu.toolbox.ui.components.RowDivider(startInset = 52.dp, endInset = 14.dp)
                    val tint = tints[i % tints.size]
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(28.dp).clip(CircleShape).background(tint.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(icon, null, modifier = Modifier.size(15.dp), tint = tint)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(text, style = MiuixTheme.textStyles.body2)
                    }
                }
            }
        }
    }
}

// ==================== 辅助函数 ====================

private fun getTransactionIcon(tx: Transaction): ImageVector {
    val m = tx.displayMerchant.lowercase()
    val d = tx.description.lowercase()
    return when {
        m.contains("浴室") || m.contains("澡堂") -> Icons.Default.Shower
        m.contains("能源") || d.contains("电费") || m.contains("电控") -> Icons.Default.ElectricBolt
        m.contains("超市") || m.contains("超级市场") || m.contains("商店") || m.contains("便利") || m.contains("卖场") -> Icons.Default.ShoppingCart
        m.contains("图书") || m.contains("打印") || m.contains("复印") -> Icons.AutoMirrored.Filled.MenuBook
        m.contains("洗衣") || m.contains("洗涤") -> Icons.Default.LocalLaundryService
        m.contains("医院") || m.contains("药") -> Icons.Default.LocalHospital
        tx.type.contains("充值") || tx.type.contains("圈存") -> Icons.Default.AddCard
        tx.amount > 0 -> Icons.Default.AddCard
        else -> Icons.Default.Restaurant
    }
}

private fun formatDateHeader(dateStr: String): String {
    return try {
        val date = LocalDate.parse(dateStr)
        val today = todayOnCampus()
        when {
            date == today -> "今天"
            date == today.minus(1, DateTimeUnit.DAY) -> "昨天"
            date == today.minus(2, DateTimeUnit.DAY) -> "前天"
            date.year == today.year -> "${date.month.number}月${date.day}日"
            else -> "${date.year}年${date.month.number}月${date.day}日"
        }
    } catch (_: Exception) { dateStr }
}

private fun generateInsights(
    stats: List<MonthlyStats>,
    categories: Map<String, Double>,
    mealStats: Map<String, MealTimeStats>,
    weekdayWeekend: Pair<DayTypeStats, DayTypeStats>?,
    activeCampusDays: Int,
    rangeStart: LocalDate,
    rangeEnd: LocalDate,
    balance: Double = 0.0,
    dailyRate: Double? = null,
): List<Pair<ImageVector, String>> {
    val insights = mutableListOf<Pair<ImageVector, String>>()
    val total = categories.values.sum()
    val spanYears = CampusCardAnalysis.spansYears(rangeStart, rangeEnd)

    // 上面几张卡已经画出来的（餐饮占比、最常去的商户、每月高低、水电）这里不再复述，
    // 只留图上看不出来、要算一下才知道的结论。

    CampusCardAnalysis.monthChangeInsight(stats, rangeEnd)?.let { line ->
        val up = "增长" in line
        insights.add(
            (if (up) Icons.AutoMirrored.Filled.TrendingUp else Icons.AutoMirrored.Filled.TrendingDown) to line
        )
    }

    val lunchStats = mealStats["午餐"]
    val dinnerStats = mealStats["晚餐"]
    if (lunchStats != null && dinnerStats != null) {
        val diff = dinnerStats.avgAmount - lunchStats.avgAmount
        if (abs(diff) > 1.0) {
            insights.add(Icons.Default.Compare to
                    (if (diff > 0) "晚餐比午餐平均贵 ¥" else "午餐比晚餐平均贵 ¥") + money1(abs(diff)))
        }
    }

    // 工作日和周末单笔差个几毛钱是噪声，差出一成半才值得提
    if (weekdayWeekend != null) {
        val (wd, we) = weekdayWeekend
        val wdAvg = wd.avgPerTransaction
        val weAvg = we.avgPerTransaction
        if (wd.count > 0 && we.count > 0 && minOf(wdAvg, weAvg) > 0 &&
            maxOf(wdAvg, weAvg) / minOf(wdAvg, weAvg) >= 1.15
        ) {
            insights.add(Icons.Default.CalendarMonth to
                    (if (weAvg > wdAvg) "周末单笔比工作日高（¥" else "工作日单笔比周末高（¥") +
                        money1(maxOf(wdAvg, weAvg)) + " / ¥" + money1(minOf(wdAvg, weAvg)) + "）")
        }
    }

    // 早餐频率（用"在校天数"做分母，即至少有一顿正餐的自然日）
    val breakfast = mealStats["早餐"]
    val calendarDays = CampusCardAnalysis.calendarDays(rangeStart, rangeEnd)
    val denominator = if (activeCampusDays > 3) activeCampusDays else calendarDays
    if (breakfast != null && denominator > 3) {
        val breakfastRate = (breakfast.count.toDouble() / denominator * 100).coerceAtMost(100.0)
        insights.add(Icons.Default.WbSunny to "在校日里 " + percent0(breakfastRate) + " 吃了早餐")
    }

    val peakMonth = stats.maxByOrNull { it.peakDayAmount }
    if (peakMonth != null && peakMonth.peakDayAmount > 0) {
        insights.add(Icons.Default.LocalFireDepartment to
                "单日最高：${CampusCardAnalysis.formatPeakDay(peakMonth.peakDay, spanYears)} ¥" + money0(peakMonth.peakDayAmount))
    }

    runwayDays(balance, dailyRate)?.let { days ->
        insights.add(Icons.Default.AccountBalanceWallet to "余额 ¥" + money0(balance) + "，" + runwayText(days))
    }

    return insights
}
