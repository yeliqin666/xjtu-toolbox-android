package com.xjtu.toolbox.bus

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xjtu.toolbox.error.FriendlyError
import com.xjtu.toolbox.ui.adaptive.AdaptiveCardGrid
import com.xjtu.toolbox.ui.adaptive.fullLineItem
import com.xjtu.toolbox.ui.components.AppCardColor
import com.xjtu.toolbox.ui.components.AppPullToRefresh
import com.xjtu.toolbox.ui.components.AppSearchBar
import com.xjtu.toolbox.ui.components.AppSegmentedTabs
import com.xjtu.toolbox.ui.components.EmptyState
import com.xjtu.toolbox.ui.components.ErrorState
import com.xjtu.toolbox.ui.components.RollingNumberText
import com.xjtu.toolbox.ui.components.SkeletonList
import com.xjtu.toolbox.ui.components.enterOnce
import com.xjtu.toolbox.ui.components.pressHighlight
import com.xjtu.toolbox.ui.glass.*
import com.xjtu.toolbox.util.CampusTimeZone
import com.xjtu.toolbox.ui.glass.rememberCouiTopAppBarScrollBehavior
import kotlin.time.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toLocalDateTime
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** 宽度够、又是横着的（手机横屏、平板）：筛选放左栏，时刻表占右边。 */
private val TWO_PANE_MIN_WIDTH = 600.dp
private val SIDE_PANE_WIDTH = 300.dp
private val CARD_SHAPE = RoundedCornerShape(18.dp)

/** 校车查询：今明后三天的时刻表。时间打头，一眼看到下一班和余量；字都放在卡片里，换日期时整表重新入场。 */
@Composable
fun BusScreen(source: BusSource, onBack: () -> Unit) {
    val vm: BusViewModel = viewModel { BusViewModel(source) }
    val listState = rememberLazyStaggeredGridState()
    val scrollBehavior = rememberCouiTopAppBarScrollBehavior()
    val glass = rememberPageGlass()
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var showDeparted by rememberSaveable { mutableStateOf(false) }
    val today = vm.dates.first()
    val nowHhmm = remember(vm.isLoading) { Clock.System.now().toLocalDateTime(CampusTimeZone).time.toString().take(5) }

    LaunchedEffect(vm.date) {
        showDeparted = false
        runCatching { listState.scrollToItem(0) }
    }

    val isToday = vm.date == today
    val shown = vm.shown
    val departed = if (isToday) shown.filter { BusRules.hasDeparted(it, nowHhmm) } else emptyList()
    val upcoming = shown - departed.toSet()
    val nextKey = if (isToday) upcoming.firstOrNull()?.let(::runKey) else null
    val groups = upcoming.groupBy { BusRules.periodOf(it.time) }.toList()
    val toggle = { key: String -> expanded = if (expanded == key) null else key }

    Scaffold(
        topBar = { GlassTopAppBar(title = "校车查询", glass = glass, scrollBehavior = scrollBehavior, onBack = onBack) },
    ) { padding ->
        val glassTop = padding.glassTop(glass)
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding.withoutTop(glass)).glassSource(glass).nestedScroll(scrollBehavior.nestedScrollConnection)) {
            val twoPane = maxWidth >= TWO_PANE_MIN_WIDTH && maxWidth > maxHeight
            Row(Modifier.fillMaxSize()) {
                if (twoPane) {
                    Column(
                        Modifier.width(SIDE_PANE_WIDTH).fillMaxHeight().verticalScroll(rememberScrollState())
                            .padding(start = 16.dp, end = 8.dp, top = 8.dp + glassTop, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) { BusFilters(vm, today, shown.size, narrowSearch = true) }
                }
                AppPullToRefresh(
                    isRefreshing = vm.isLoading && vm.runs.isNotEmpty(),
                    onRefresh = vm::load,
                    scrollBehavior = scrollBehavior,
                    topPadding = glassTop,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                ) {
                    AdaptiveCardGrid(
                        modifier = Modifier.fillMaxSize().overScrollVertical(),
                        state = listState,
                        contentPadding = PaddingValues(start = if (twoPane) 8.dp else 16.dp, end = 16.dp, top = 8.dp + glassTop, bottom = 16.dp),
                        spacing = 10.dp,
                    ) {
                        if (!twoPane) {
                            fullLineItem(key = "filters") {
                                Column(Modifier.enterOnce(0), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    BusFilters(vm, today, shown.size, narrowSearch = false)
                                }
                            }
                        }
                        when {
                            vm.isLoading && vm.runs.isEmpty() -> fullLineItem(key = "loading") {
                                SkeletonList(Modifier.fillMaxWidth().height(480.dp), rows = 6, rowHeight = 72.dp)
                            }
                            vm.error != null && vm.runs.isEmpty() -> fullLineItem(key = "error") {
                                ErrorState(
                                    message = FriendlyError.of(vm.error!!, "加载校车"),
                                    onRetry = vm::load,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                                )
                            }
                            shown.isEmpty() -> fullLineItem(key = "empty") {
                                EmptyState(
                                    title = if (vm.runs.isEmpty()) "这天没有校车" else "没有匹配的班次",
                                    subtitle = if (vm.runs.isEmpty()) "换一天看看" else "换个站点或线路试试",
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                                )
                            }
                            else -> {
                                // key 带上日期和分类：换了就是新的一组，错峰重新入场
                                items(groups.size, key = { "${vm.date}_${vm.category}_${groups[it].first}" }) { index ->
                                    val (period, runs) = groups[index]
                                    Box(Modifier.enterOnce(index + 1)) {
                                        RunGroup(period, runs, nextKey, expanded, toggle)
                                    }
                                }
                                if (departed.isNotEmpty()) {
                                    fullLineItem(key = "departed_${vm.date}") {
                                        Box(Modifier.enterOnce(groups.size + 1)) {
                                            DepartedGroup(departed, showDeparted, { showDeparted = !showDeparted }, expanded, toggle)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 同一线路同一时刻可能发两辆车，要带上车牌才唯一。 */
private fun runKey(run: BusRun) = "${run.time}_${run.lineName}_${run.plate}"

@Composable
private fun BusFilters(vm: BusViewModel, today: LocalDate, count: Int, narrowSearch: Boolean) {
    val cs = MiuixTheme.colorScheme
    AppSearchBar(
        query = vm.query,
        onQueryChange = { vm.query = it },
        label = if (narrowSearch) "搜线路或站点" else "搜线路或站点，如：创新港到思源",
        modifier = Modifier.fillMaxWidth(),
    )
    Surface(color = AppCardColor, shape = CARD_SHAPE, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            AppSegmentedTabs(
                tabs = vm.dates.map { dayLabel(it, today) },
                selectedTabIndex = vm.dates.indexOf(vm.date).coerceAtLeast(0),
                onTabSelected = { vm.selectDate(vm.dates[it]) },
                embedded = true,
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (vm.categories.size > 1) {
                        TextTab("全部", vm.category == null) { vm.category = null }
                        vm.categories.forEach { c -> TextTab(c.removeSuffix("班车"), vm.category == c) { vm.category = c } }
                    }
                }
                RollingNumberText(
                    value = count.toDouble(),
                    format = { "${it.toInt()} 班" },
                    style = MiuixTheme.textStyles.footnote1,
                    color = cs.onSurfaceVariantSummary,
                    durationMillis = 300,
                )
            }
            Row(
                Modifier.padding(horizontal = 12.dp).padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(cs.primary.copy(alpha = 0.08f)).padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Info, null, tint = cs.primary, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text(BusRules.BOARDING_TIP, style = MiuixTheme.textStyles.footnote1, color = cs.onSurface)
            }
        }
    }
}

@Composable
private fun TextTab(label: String, active: Boolean, onClick: () -> Unit) {
    val cs = MiuixTheme.colorScheme
    val color by animateColorAsState(if (active) cs.primary else cs.onSurfaceVariantSummary, tween(180), label = "busTab")
    Text(
        label,
        style = MiuixTheme.textStyles.body2,
        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        color = color,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(vertical = 4.dp, horizontal = 2.dp),
    )
}

/** 一个时段一张卡，标题在卡片里，一行一班。 */
@Composable
private fun RunGroup(period: String, runs: List<BusRun>, nextKey: String?, expanded: String?, onToggle: (String) -> Unit) {
    val cs = MiuixTheme.colorScheme
    Surface(color = AppCardColor, shape = CARD_SHAPE, modifier = Modifier.fillMaxWidth()) {
        // 不套 animateContentSize：行内展开自己会长高，外层再追一层动画，下面的卡就会慢半拍才挪
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(period, style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Bold, color = cs.onSurfaceVariantSummary, modifier = Modifier.weight(1f))
                Text("${runs.size} 班", style = MiuixTheme.textStyles.footnote1, color = cs.onSurfaceVariantSummary)
            }
            Rows(runs, nextKey, expanded, departed = false, onToggle)
        }
    }
}

@Composable
private fun Rows(runs: List<BusRun>, nextKey: String?, expanded: String?, departed: Boolean, onToggle: (String) -> Unit) {
    val cs = MiuixTheme.colorScheme
    runs.forEachIndexed { i, run ->
        if (i > 0) Box(Modifier.padding(start = 76.dp).fillMaxWidth().height(0.5.dp).background(cs.onSurface.copy(alpha = 0.08f)))
        val key = runKey(run)
        RunRow(run, isNext = key == nextKey, departed = departed, isExpanded = expanded == key) { onToggle(key) }
    }
}

/** 今天已发车的收在一张卡里，默认折叠。 */
@Composable
private fun DepartedGroup(runs: List<BusRun>, open: Boolean, onToggleOpen: () -> Unit, expanded: String?, onToggle: (String) -> Unit) {
    val cs = MiuixTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    Surface(color = AppCardColor, shape = CARD_SHAPE, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier.fillMaxWidth().pressHighlight(source).clickable(source, null, onClick = onToggleOpen).padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("今天已发车 ${runs.size} 班", style = MiuixTheme.textStyles.body2, color = cs.onSurfaceVariantSummary, modifier = Modifier.weight(1f))
                Chevron(open)
            }
            AnimatedVisibility(open, enter = BusExpand, exit = BusCollapse) {
                Column { Rows(runs, null, expanded, departed = true, onToggle) }
            }
        }
    }
}

/** 展开收起只有这一层尺寸动画，和箭头同一条曲线。 */
private val BusExpand = expandVertically(tween(320, easing = androidx.compose.animation.core.FastOutSlowInEasing)) + fadeIn(tween(220))
private val BusCollapse = shrinkVertically(tween(320, easing = androidx.compose.animation.core.FastOutSlowInEasing)) + fadeOut(tween(160))

@Composable
private fun Chevron(open: Boolean) {
    val rotation by animateFloatAsState(if (open) 180f else 0f, tween(320, easing = androidx.compose.animation.core.FastOutSlowInEasing), label = "busChevron")
    Icon(
        Icons.Default.ExpandMore,
        if (open) "收起" else "展开",
        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = rotation },
    )
}

@Composable
private fun RunRow(run: BusRun, isNext: Boolean, departed: Boolean, isExpanded: Boolean, onClick: () -> Unit) {
    val cs = MiuixTheme.colorScheme
    val faded = if (departed) cs.onSurfaceVariantSummary else cs.onSurface
    val source = remember { MutableInteractionSource() }
    Column(Modifier.fillMaxWidth().pressHighlight(source).clickable(source, null, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(60.dp)) {
                Text(run.time, style = MiuixTheme.textStyles.title4, fontWeight = FontWeight.Bold, color = if (isNext) cs.primary else faded)
                if (isNext) Text("下一班", style = MiuixTheme.textStyles.footnote1, color = cs.primary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(BusRules.shortLineName(run.lineName), style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium, color = faded, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (BusRules.isDirect(run)) {
                        Spacer(Modifier.width(6.dp))
                        Text("直达", style = MiuixTheme.textStyles.footnote1, color = cs.onSurfaceVariantSummary)
                    }
                }
                Text(
                    "${run.from} → ${run.to}",
                    style = MiuixTheme.textStyles.footnote1,
                    color = cs.onSurfaceVariantSummary,
                    maxLines = if (isExpanded) 3 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            SeatBadge(run, departed)
        }
        AnimatedVisibility(isExpanded, enter = BusExpand, exit = BusCollapse) {
            Column(Modifier.padding(start = 60.dp, top = 12.dp)) {
                run.stops.forEachIndexed { i, stop -> StopLine(stop, first = i == 0, last = i == run.stops.lastIndex) }
                Text(
                    listOfNotNull(run.plate.takeIf { it.isNotEmpty() }, "共 ${run.seats} 座").joinToString(" · "),
                    style = MiuixTheme.textStyles.footnote1,
                    color = cs.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/** 右侧只放一个数字：这页最该看的就是还剩几个座。 */
@Composable
private fun SeatBadge(run: BusRun, departed: Boolean) {
    val cs = MiuixTheme.colorScheme
    val color = when {
        departed -> cs.onSurfaceVariantSummary
        run.isFull || run.isTight -> cs.error
        else -> cs.onSurface
    }
    Column(horizontalAlignment = Alignment.End) {
        when {
            departed -> Text("已发车", style = MiuixTheme.textStyles.footnote1, color = color)
            run.isFull -> Text("已满", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Bold, color = color)
            else -> {
                RollingNumberText(
                    value = run.seatsLeft.toDouble(),
                    format = { it.toInt().toString() },
                    style = MiuixTheme.textStyles.title4,
                    fontWeight = FontWeight.Bold,
                    color = color,
                )
                Text(if (run.isTight) "快满了" else "余座", style = MiuixTheme.textStyles.footnote1, color = if (run.isTight) cs.error else cs.onSurfaceVariantSummary)
            }
        }
    }
}

/** 途经站：一条竖线串起来。 */
@Composable
private fun StopLine(stop: BusStop, first: Boolean, last: Boolean) {
    val cs = MiuixTheme.colorScheme
    val line = cs.onSurface.copy(alpha = 0.15f)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(14.dp).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.width(1.dp).height(6.dp).background(if (first) Color.Transparent else line))
                Box(Modifier.size(7.dp).clip(CircleShape).background(if (first || last) cs.primary else cs.onSurfaceVariantSummary))
                Box(Modifier.width(1.dp).weight(1f).background(if (last) Color.Transparent else line))
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f).padding(bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stop.name, style = MiuixTheme.textStyles.body2, color = cs.onSurface, modifier = Modifier.weight(1f))
                stop.time?.let { Text(it, style = MiuixTheme.textStyles.footnote1, color = cs.onSurfaceVariantSummary) }
            }
            if (stop.remark.isNotEmpty()) Text(stop.remark, style = MiuixTheme.textStyles.footnote1, color = cs.onSurfaceVariantSummary)
        }
    }
}

private fun dayLabel(date: LocalDate, today: LocalDate): String {
    val name = when ((date.toEpochDays() - today.toEpochDays()).toInt()) {
        0 -> "今天"
        1 -> "明天"
        else -> "后天"
    }
    return "$name ${date.month.ordinal + 1}/${date.day}"
}
