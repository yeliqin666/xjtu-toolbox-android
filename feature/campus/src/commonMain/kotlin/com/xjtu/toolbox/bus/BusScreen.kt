package com.xjtu.toolbox.bus

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import com.xjtu.toolbox.ui.components.EmptyState
import com.xjtu.toolbox.ui.components.ErrorState
import com.xjtu.toolbox.ui.components.SkeletonList
import com.xjtu.toolbox.ui.glass.*
import com.xjtu.toolbox.util.CampusTimeZone
import kotlin.time.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toLocalDateTime
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** 宽度够、又是横着的（手机横屏、平板）：筛选放左栏，时刻表占右边。 */
private val TWO_PANE_MIN_WIDTH = 600.dp
private val SIDE_PANE_WIDTH = 300.dp

/** 校车查询：今明后三天的时刻表。排版照时刻表来：时间打头，一眼看到下一班和余量。 */
@Composable
fun BusScreen(source: BusSource, onBack: () -> Unit) {
    val vm: BusViewModel = viewModel { BusViewModel(source) }
    val listState = rememberLazyStaggeredGridState()
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
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
                    ) { BusFilters(vm, today, shown.size, stackedTabs = true) }
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
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { BusFilters(vm, today, shown.size, stackedTabs = false) }
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
                                items(groups.size, key = { "period_${groups[it].first}" }) { index ->
                                    val (period, runs) = groups[index]
                                    RunGroup(period, runs, nextKey, expanded, departed = false) { key -> expanded = if (expanded == key) null else key }
                                }
                                if (departed.isNotEmpty()) {
                                    fullLineItem(key = "departed_toggle") {
                                        DepartedToggle(departed.size, showDeparted) { showDeparted = !showDeparted }
                                    }
                                    if (showDeparted) {
                                        fullLineItem(key = "departed") {
                                            RunGroup(null, departed, null, expanded, departed = true) { key -> expanded = if (expanded == key) null else key }
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
private fun BusFilters(vm: BusViewModel, today: LocalDate, count: Int, stackedTabs: Boolean) {
    AppSearchBar(
        query = vm.query,
        onQueryChange = { vm.query = it },
        label = if (stackedTabs) "搜线路或站点" else "搜线路或站点，如：创新港到思源",
        modifier = Modifier.fillMaxWidth(),
    )
    DateSegments(vm.dates, vm.date, today, vm::selectDate)
    CategoryTabs(vm.categories, vm.category, count, stacked = stackedTabs) { vm.category = it }
    Text(
        BusRules.BOARDING_TIP,
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

/** 选日期：一条分段控件，占满整行，不用胶囊。 */
@Composable
private fun DateSegments(dates: List<LocalDate>, selected: LocalDate, today: LocalDate, onSelect: (LocalDate) -> Unit) {
    val cs = MiuixTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cs.onSurface.copy(alpha = 0.05f)).padding(3.dp),
    ) {
        dates.forEach { d ->
            val active = d == selected
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(11.dp))
                    .background(if (active) AppCardColor else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { onSelect(d) }
                    .padding(vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    dayName(d, today),
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    color = if (active) cs.primary else cs.onSurface,
                )
                Text("${d.month.ordinal + 1}/${d.day}", style = MiuixTheme.textStyles.footnote1, color = cs.onSurfaceVariantSummary)
            }
        }
    }
}

/** 分类只是轻量文字，和班次数放同一行，不再占一排胶囊。 */
@Composable
private fun CategoryTabs(categories: List<String>, selected: String?, count: Int, stacked: Boolean, onSelect: (String?) -> Unit) {
    val cs = MiuixTheme.colorScheme
    if (categories.size <= 1 && count == 0) return
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(if (stacked) 12.dp else 16.dp),
        ) {
            if (categories.size > 1) {
                TextTab("全部", selected == null) { onSelect(null) }
                categories.forEach { c -> TextTab(c.removeSuffix("班车"), selected == c) { onSelect(c) } }
            }
        }
        Text("$count 班", style = MiuixTheme.textStyles.footnote1, color = cs.onSurfaceVariantSummary)
    }
}

@Composable
private fun TextTab(label: String, active: Boolean, onClick: () -> Unit) {
    val cs = MiuixTheme.colorScheme
    Text(
        label,
        style = MiuixTheme.textStyles.body2,
        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        color = if (active) cs.primary else cs.onSurfaceVariantSummary,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(vertical = 4.dp, horizontal = 2.dp),
    )
}

/** 一个时段一张卡，里面一行一班，比一班一张卡省地方也好扫。 */
@Composable
private fun RunGroup(
    period: String?,
    runs: List<BusRun>,
    nextKey: String?,
    expanded: String?,
    departed: Boolean,
    onToggle: (String) -> Unit,
) {
    val cs = MiuixTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        if (period != null) {
            Text(
                period,
                style = MiuixTheme.textStyles.footnote1,
                fontWeight = FontWeight.Bold,
                color = cs.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
            )
        }
        Surface(color = AppCardColor, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.animateContentSize()) {
                runs.forEachIndexed { i, run ->
                    if (i > 0) Box(Modifier.padding(start = 76.dp).fillMaxWidth().height(0.5.dp).background(cs.onSurface.copy(alpha = 0.08f)))
                    val key = runKey(run)
                    RunRow(run, isNext = key == nextKey, departed = departed, isExpanded = expanded == key) { onToggle(key) }
                }
            }
        }
    }
}

@Composable
private fun RunRow(run: BusRun, isNext: Boolean, departed: Boolean, isExpanded: Boolean, onClick: () -> Unit) {
    val cs = MiuixTheme.colorScheme
    val faded = if (departed) cs.onSurfaceVariantSummary else cs.onSurface
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
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
        AnimatedVisibility(isExpanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
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
                Text(run.seatsLeft.toString(), style = MiuixTheme.textStyles.title4, fontWeight = FontWeight.Bold, color = color)
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
                Box(Modifier.width(1.dp).height(6.dp).background(if (first) androidx.compose.ui.graphics.Color.Transparent else line))
                Box(Modifier.size(7.dp).clip(CircleShape).background(if (first || last) cs.primary else cs.onSurfaceVariantSummary))
                Box(Modifier.width(1.dp).weight(1f).background(if (last) androidx.compose.ui.graphics.Color.Transparent else line))
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

@Composable
private fun DepartedToggle(count: Int, open: Boolean, onClick: () -> Unit) {
    val cs = MiuixTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("今天已发车 $count 班", style = MiuixTheme.textStyles.footnote1, color = cs.onSurfaceVariantSummary, modifier = Modifier.weight(1f))
        Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (open) "收起" else "展开", tint = cs.onSurfaceVariantSummary, modifier = Modifier.size(18.dp))
    }
}

private fun dayName(date: LocalDate, today: LocalDate): String = when ((date.toEpochDays() - today.toEpochDays()).toInt()) {
    0 -> "今天"
    1 -> "明天"
    else -> "后天"
}
