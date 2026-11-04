package com.xjtu.toolbox.bus

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xjtu.toolbox.error.FriendlyError
import com.xjtu.toolbox.ui.adaptive.AdaptiveCardGrid
import com.xjtu.toolbox.ui.adaptive.fullLineItem
import com.xjtu.toolbox.ui.components.AppCardColor
import com.xjtu.toolbox.ui.components.AppFilterChip
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
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** 校车查询：今明后三天的班次、途经站点和余量。 */
@Composable
fun BusScreen(source: BusSource, onBack: () -> Unit) {
    val vm: BusViewModel = viewModel { BusViewModel(source) }
    val listState = rememberLazyStaggeredGridState()
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val glass = rememberPageGlass()
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    val shown = vm.shown
    val today = vm.dates.first()
    val nowHhmm = remember(vm.isLoading) { Clock.System.now().toLocalDateTime(CampusTimeZone).time.toString().take(5) }

    LaunchedEffect(vm.date) { runCatching { listState.scrollToItem(0) } }

    Scaffold(
        topBar = { GlassTopAppBar(title = "校车查询", glass = glass, scrollBehavior = scrollBehavior, onBack = onBack) },
    ) { padding ->
        val glassTop = padding.glassTop(glass)
        Box(Modifier.fillMaxSize().padding(padding.withoutTop(glass)).glassSource(glass).nestedScroll(scrollBehavior.nestedScrollConnection)) {
            AppPullToRefresh(
                isRefreshing = vm.isLoading && vm.runs.isNotEmpty(),
                onRefresh = vm::load,
                scrollBehavior = scrollBehavior,
                topPadding = glassTop,
                modifier = Modifier.fillMaxSize(),
            ) {
                AdaptiveCardGrid(
                    modifier = Modifier.fillMaxSize().overScrollVertical(),
                    state = listState,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp + glassTop, bottom = 16.dp),
                    spacing = 8.dp,
                ) {
                    fullLineItem(key = "tip") { BoardingTip() }
                    fullLineItem(key = "search") {
                        AppSearchBar(
                            query = vm.query,
                            onQueryChange = { vm.query = it },
                            label = "搜线路或站点，如：创新港到思源",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    fullLineItem(key = "dates") {
                        ChipRow {
                            vm.dates.forEach { d ->
                                AppFilterChip(selected = d == vm.date, onClick = { vm.selectDate(d) }, label = dayLabel(d, today))
                            }
                        }
                    }
                    if (vm.categories.size > 1) {
                        fullLineItem(key = "categories") {
                            ChipRow {
                                AppFilterChip(selected = vm.category == null, onClick = { vm.category = null }, label = "全部")
                                vm.categories.forEach { c ->
                                    AppFilterChip(selected = vm.category == c, onClick = { vm.category = c }, label = c)
                                }
                            }
                        }
                    }
                    when {
                        vm.isLoading && vm.runs.isEmpty() -> fullLineItem(key = "loading") {
                            SkeletonList(Modifier.fillMaxWidth().height(480.dp), rows = 6, rowHeight = 84.dp)
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
                        else -> items(shown.size, key = { index -> runKey(shown[index]) }) { index ->
                            val run = shown[index]
                            val key = runKey(run)
                            BusRunCard(
                                run = run,
                                departed = vm.date == today && BusRules.hasDeparted(run, nowHhmm),
                                isExpanded = expanded == key,
                                onClick = { expanded = if (expanded == key) null else key },
                            )
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
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/** 常驻提醒：学生坐校车不用预约，余量才是该看的。 */
@Composable
private fun BoardingTip() {
    Surface(
        color = MiuixTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Info, null, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(BusRules.BOARDING_TIP, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun BusRunCard(run: BusRun, departed: Boolean, isExpanded: Boolean, onClick: () -> Unit) {
    val cs = MiuixTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize().alpha(if (departed) 0.5f else 1f),
        colors = CardDefaults.defaultColors(color = AppCardColor),
        pressFeedbackType = PressFeedbackType.Sink,
        onClick = onClick,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(run.time, style = MiuixTheme.textStyles.title4, fontWeight = FontWeight.Bold, color = cs.onSurface)
                Spacer(Modifier.width(10.dp))
                Text(
                    run.lineName,
                    style = MiuixTheme.textStyles.body1,
                    color = cs.onSurface,
                    maxLines = if (isExpanded) 3 else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        departed -> "已发车"
                        run.isFull -> "已满"
                        else -> "余 ${run.seatsLeft} 座"
                    },
                    style = MiuixTheme.textStyles.footnote1,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        departed -> cs.onSurfaceVariantSummary
                        run.isTight -> cs.error
                        else -> cs.primary
                    },
                )
            }
            Text(
                "${run.from} → ${run.to}" + if (!departed && run.isTight && !run.isFull) " · 余量紧张，早点去排队" else "",
                style = MiuixTheme.textStyles.footnote1,
                color = cs.onSurfaceVariantSummary,
                maxLines = if (isExpanded) 3 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (isExpanded) {
                Spacer(Modifier.height(4.dp))
                run.stops.forEach { stop -> StopRow(stop) }
                Text(
                    listOfNotNull("共 ${run.seats} 座", run.plate.takeIf { it.isNotEmpty() }).joinToString(" · "),
                    style = MiuixTheme.textStyles.footnote1,
                    color = cs.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun StopRow(stop: BusStop) {
    val cs = MiuixTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stop.time.orEmpty(), style = MiuixTheme.textStyles.footnote1, color = cs.onSurfaceVariantSummary, modifier = Modifier.width(40.dp))
        Column(Modifier.weight(1f)) {
            Text(stop.name, style = MiuixTheme.textStyles.body2, color = cs.onSurface)
            if (stop.remark.isNotEmpty()) {
                Text(stop.remark, style = MiuixTheme.textStyles.footnote1, color = cs.onSurfaceVariantSummary)
            }
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
