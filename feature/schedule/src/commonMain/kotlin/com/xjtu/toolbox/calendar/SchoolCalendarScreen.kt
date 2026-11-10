package com.xjtu.toolbox.calendar

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjtu.toolbox.ui.glass.tabContentPadding
import com.xjtu.toolbox.error.FriendlyError
import com.xjtu.toolbox.schedule.CourseColors
import com.xjtu.toolbox.ui.components.AnimatedBar
import com.xjtu.toolbox.ui.components.AppCardColor
import com.xjtu.toolbox.ui.components.BackButton
import com.xjtu.toolbox.ui.components.HeroMesh
import com.xjtu.toolbox.ui.components.RollingNumberText
import com.xjtu.toolbox.ui.components.enterOnce
import com.xjtu.toolbox.ui.glass.glassBarColor
import com.xjtu.toolbox.ui.glass.glassSource
import com.xjtu.toolbox.ui.glass.glassTop
import com.xjtu.toolbox.ui.glass.glassTopBar
import com.xjtu.toolbox.ui.glass.rememberPageGlass
import com.xjtu.toolbox.ui.glass.withoutTop
import com.xjtu.toolbox.ui.isWideLayout
import com.xjtu.toolbox.util.todayOnCampus
import kotlin.math.roundToInt
import kotlinx.datetime.LocalDate
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 校历屏。
 *
 * 「完全一致」里最容易做到真一致的一屏：模型（[SchoolTerm]/[CalendarEvent]）与全部换算
 * （进度、周次、剩余天数）都在共享层，**两端跑同一份代码同一份算法**，只有取数实现不同
 * （见 [SchoolCalendarSource]）。
 *
 * @param calendarImage 学年校历原图的展示槽（平台能力，null = 本端没有原图）；
 *   槽的实现方自己知道该画哪一年的图（参数就是当前选中学期的 `yearName`）。
 */
@Composable
fun SchoolCalendarScreen(
    source: SchoolCalendarSource,
    onBack: () -> Unit,
    calendarImage: (@Composable (selectedYear: String?) -> Unit)? = null,
) {
    var terms by remember { mutableStateOf<List<SchoolTerm>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var selectedTermIndex by remember { mutableIntStateOf(0) }

    val today = remember { todayOnCampus() }
    val scrollState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()
    // 玻璃顶栏（经典风格下为 null，一切照旧），用法见 ui/glass/GlassTopBar.kt
    val glass = rememberPageGlass()

    LaunchedEffect(source) {
        isLoading = true
        try {
            val result = source.terms()
            terms = result
            selectedTermIndex = defaultTermIndex(result, today)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            errorMessage = FriendlyError.of(e, "加载校历")
        }
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "校历",
                // 学期切换收进右上角了，当前是哪个学期写在这里
                subtitle = terms.getOrNull(selectedTermIndex)?.termName.orEmpty(),
                color = glassBarColor(glass),
                modifier = Modifier.glassTopBar(glass),
                actions = {
                    if (terms.size > 1) {
                        var termMenuOpen by remember { mutableStateOf(false) }
                        com.xjtu.toolbox.ui.components.AppIconMenu(
                            expanded = termMenuOpen,
                            onExpandedChange = { termMenuOpen = it },
                            icon = { Icon(Icons.Default.DateRange, contentDescription = "切换学期") },
                        ) {
                            terms.forEachIndexed { index, term ->
                                com.xjtu.toolbox.ui.components.AppDropdownOption(term.termName, index == selectedTermIndex) {
                                    termMenuOpen = false
                                    selectedTermIndex = index
                                }
                            }
                        }
                    }
                },
                navigationIcon = {
                    BackButton(onBack, modifier = Modifier.padding(start = 8.dp))
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        val glassTop = padding.glassTop(glass)
        Box(Modifier.padding(padding.withoutTop(glass)).glassSource(glass).fillMaxSize()) {
            when {
                isLoading -> {
                    Box(Modifier.fillMaxSize().padding(top = glassTop), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(size = 40.dp, strokeWidth = 3.dp)
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "正在加载校历...",
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
                errorMessage != null -> {
                    Column(
                        Modifier.fillMaxSize().padding(top = glassTop).padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            Icons.Default.CloudOff, null,
                            Modifier.size(56.dp),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            errorMessage!!,
                            style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                terms.isEmpty() -> {
                    Box(Modifier.fillMaxSize().padding(top = glassTop), contentAlignment = Alignment.Center) {
                        Text("暂无校历数据", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
                else -> {
                    val currentTerm = terms.getOrNull(selectedTermIndex) ?: terms.first()
                    TermContent(
                        currentTerm = currentTerm,
                        today = today,
                        listState = scrollState,
                        scrollBehavior = scrollBehavior,
                        glassTop = glassTop,
                        calendarImage = calendarImage,
                    )
                }
            }
        }
    }
}

@Composable
private fun TermContent(
    currentTerm: SchoolTerm,
    today: LocalDate,
    listState: LazyListState,
    scrollBehavior: top.yukonga.miuix.kmp.basic.ScrollBehavior,
    glassTop: androidx.compose.ui.unit.Dp = 0.dp,
    calendarImage: (@Composable (selectedYear: String?) -> Unit)? = null,
) {
    // 动画交给 HeroCard 里的滚动数字和进度条（从 0 长上来）；以前 animateFloatAsState 的初值就是目标值，进场根本不动
    val progress = currentTerm.progress(today)
    val currentWeek = currentTerm.currentWeek(today)
    val daysRemaining = currentTerm.daysRemaining(today)
    val todayEvent = currentTerm.todayEvent(today)
    val isBeforeTerm = today < currentTerm.startDate
    val isAfterTerm = today > currentTerm.endDate

    // 宽屏两栏：左边是「这学期现在到哪了」（学期切换、状态卡、统计），右边是整学期的日程时间轴。
    // 以前整页限宽 720 居中，平板横屏左右各空一大块，时间轴还得滚过状态卡才看得到。
    val wide = isWideLayout()
    // 状态区：英雄卡片（当前状态 + 进度）+ 统计信息行；学期切换在顶栏右上角
    val statusItems: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {
        // 整页依次登场：学期卡 → 统计 → 日程标题 → 时间轴前几项
        item {
            Box(Modifier.enterOnce(0)) {
                HeroCard(
                    currentTerm = currentTerm,
                    today = today,
                    currentWeek = currentWeek,
                    todayEvent = todayEvent,
                    progress = progress,
                    daysRemaining = daysRemaining,
                    isBeforeTerm = isBeforeTerm,
                    isAfterTerm = isAfterTerm
                )
            }
        }
        item {
            Box(Modifier.enterOnce(1)) {
                StatsRow(
                    totalWeeks = currentTerm.totalWeeks,
                    workDays = currentTerm.workDays,
                    daysRemaining = daysRemaining,
                    currentWeek = currentWeek,
                    isBeforeTerm = isBeforeTerm,
                    isAfterTerm = isAfterTerm
                )
            }
        }
        // 学期状态之后、日程之前：整学年校历原图，点开可放大、保存
        if (calendarImage != null) {
            item(key = "calendar_image") {
                calendarImage(currentTerm.yearName)
            }
        }
    }
    // ── 事件时间轴 ──────────────────────────────────
    val timelineItems: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {
        item {
            Row(
                Modifier.enterOnce(2).fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 10.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    "日程安排",
                    style = MiuixTheme.textStyles.subtitle,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${currentTerm.events.size} 项",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        itemsIndexed(currentTerm.events) { index, event ->
            val isPast = today > event.endDate
            val isCurrent = today >= event.startDate && today <= event.endDate
            EventTimelineItem(
                modifier = Modifier.enterOnce(index + 3),
                event = event,
                isPast = isPast,
                isCurrent = isCurrent,
                isFirst = index == 0,
                isLast = index == currentTerm.events.lastIndex
            )
        }
    }

    if (!wide) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().nestedScrollToTopAppBar(scrollBehavior),
            contentPadding = tabContentPadding(PaddingValues(top = glassTop, bottom = 24.dp))
        ) {
            statusItems()
            timelineItems()
        }
    } else {
        Row(Modifier.fillMaxSize().nestedScrollToTopAppBar(scrollBehavior)) {
            LazyColumn(
                modifier = Modifier.width(440.dp).fillMaxHeight(),
                contentPadding = tabContentPadding(PaddingValues(top = glassTop, bottom = 24.dp))
            ) {
                statusItems()
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentPadding = tabContentPadding(PaddingValues(top = glassTop, bottom = 24.dp))
            ) {
                timelineItems()
            }
        }
    }
}

@Composable
private fun HeroCard(
    currentTerm: SchoolTerm,
    today: LocalDate,
    currentWeek: Int,
    todayEvent: CalendarEvent?,
    progress: Float,
    daysRemaining: Int,
    isBeforeTerm: Boolean,
    isAfterTerm: Boolean
) {
    val primaryColor = MiuixTheme.colorScheme.primary
    val surfaceVariant = MiuixTheme.colorScheme.surfaceVariant

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = AppCardColor),
    ) {
        Box(Modifier.fillMaxWidth()) {
            HeroMesh(
                base = AppCardColor,
                accent = primaryColor,
                modifier = Modifier.matchParentSize(),
            )
            Column(Modifier.padding(20.dp)) {
                // 主标题：当前状态
                val statusTitle = when {
                    isAfterTerm -> "本学期已结束"
                    isBeforeTerm -> "距开学还有 ${
                        (currentTerm.startDate.toEpochDays() - today.toEpochDays()).toInt()
                    } 天"
                    todayEvent != null -> todayEvent.name
                    currentWeek > 0 -> "第 $currentWeek 学习周"
                    else -> currentTerm.termName
                }

                Text(
                    statusTitle,
                    style = MiuixTheme.textStyles.headline1,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface
                )

                Text(
                    fullDateCn(today),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 2.dp)
                )

                // 今日事件备注
                if (todayEvent != null && todayEvent.remark.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        todayEvent.remark,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // 学期进度条
                if (!isBeforeTerm && !isAfterTerm || isAfterTerm) {
                    Spacer(Modifier.height(16.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            monthDaySlash(currentTerm.startDate),
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                        RollingNumberText(
                            value = progress * 100.0,
                            format = ::percent0,
                            style = MiuixTheme.textStyles.footnote2,
                            fontWeight = FontWeight.Medium,
                            color = primaryColor,
                            durationMillis = 700,
                        )
                        Text(
                            monthDaySlash(currentTerm.endDate),
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    // 进度条从左边长到今天的位置，端点一个柔光点标出「现在」
                    AnimatedBar(
                        progress = progress,
                        color = primaryColor,
                        trackColor = surfaceVariant,
                        height = 6.dp,
                        glowTip = !isAfterTerm,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatsRow(
    totalWeeks: Int,
    workDays: Int,
    daysRemaining: Int,
    currentWeek: Int,
    isBeforeTerm: Boolean,
    isAfterTerm: Boolean
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatChip(
            value = "${totalWeeks}周",
            label = "共",
            modifier = Modifier.weight(1f)
        )
        StatChip(
            value = "${workDays}天",
            label = "工作日",
            modifier = Modifier.weight(1f)
        )
        if (!isAfterTerm && !isBeforeTerm && currentWeek > 0) {
            StatChip(
                value = "第${currentWeek}周",
                label = "当前",
                modifier = Modifier.weight(1f)
            )
        } else {
            StatChip(
                value = if (daysRemaining > 0) "${daysRemaining}天" else "已结束",
                label = "还剩",
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun StatChip(value: String, label: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(
            Modifier.padding(vertical = 10.dp, horizontal = 4.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 纯数字的统计滚动出来，「—」这类占位照原样
            val n = value.toIntOrNull()
            if (n != null) {
                RollingNumberText(
                    value = n.toDouble(),
                    format = ::int0,
                    style = MiuixTheme.textStyles.subtitle,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.primary,
                )
            } else {
                Text(
                    value,
                    style = MiuixTheme.textStyles.subtitle,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.primary
                )
            }
            Text(
                label,
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
    }
}

/**
 * 日程表的一行，整列连成一张卡（首行圆上角、末行圆下角，行间细线）：
 * 左边开始日期，中间一条事件色竖条，右边名字和起止。进行中的那行淡淡染上事件色，过去的压淡。
 */
@Composable
private fun EventTimelineItem(
    event: CalendarEvent,
    isPast: Boolean,
    isCurrent: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    modifier: Modifier = Modifier,
) {
    val fallbackColor = MiuixTheme.colorScheme.primary  // 用主题色兜底，深色主题下也有对比度
    val accentColor = remember(event.colorHex) {
        CourseColors.parseHex(event.colorHex) ?: fallbackColor
    }
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val radius = 20.dp
    val shape = RoundedCornerShape(
        topStart = if (isFirst) radius else 0.dp, topEnd = if (isFirst) radius else 0.dp,
        bottomStart = if (isLast) radius else 0.dp, bottomEnd = if (isLast) radius else 0.dp,
    )

    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(shape)
            .background(AppCardColor)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (isCurrent) Modifier.background(accentColor.copy(alpha = 0.07f)) else Modifier)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .alpha(if (isPast) 0.45f else 1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.width(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${event.startDate.month.ordinal + 1}月", fontSize = 11.sp, color = muted)
                Text(
                    "${event.startDate.day}",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isCurrent) accentColor else MiuixTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier
                    .width(3.dp)
                    .height(34.dp)
                    .clip(RoundedCornerShape(50))
                    .background(accentColor.copy(alpha = if (isCurrent) 1f else 0.7f))
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        event.name,
                        style = MiuixTheme.textStyles.body1,
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isCurrent) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "进行中",
                            fontSize = 10.sp,
                            color = accentColor,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(accentColor.copy(alpha = 0.14f))
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                    }
                }
                val dateRange = if (event.startDate == event.endDate)
                    weekdayCn(event.startDate)
                else
                    "至 ${monthDayCn(event.endDate)}"
                Text(
                    "$dateRange · ${event.days}天",
                    fontSize = 12.sp,
                    color = muted,
                    modifier = Modifier.padding(top = 2.dp)
                )
                if (event.remark.isNotEmpty() && isCurrent) {
                    Text(
                        event.remark,
                        style = MiuixTheme.textStyles.footnote1,
                        color = muted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
        // 文字起点：16 边距 + 40 日期 + 12 + 3 竖条 + 12
        if (!isLast) com.xjtu.toolbox.ui.components.RowDivider(startInset = 83.dp)
    }
}

// ─────────────────────────────────────────────────────────────────────
//  日期与数字的跨端格式化
//
//  原实现用 `DateTimeFormatter.ofPattern(...)` 与 `String.format`，两者都是 JVM 专属
//  （`String.format` 在 JVM 上还是默认导入，任何基于 import 的判据都看不见它）。
//  这里手写，口径与原实现逐个对齐：
//   "yyyy年M月d日 EEEE" → [fullDateCn] ／ "MM/dd" → [monthDaySlash] ／ "M月d日" → [monthDayCn]
//   "%.0f%%" → [percent0] ／ "%.0f" → [int0]
// ─────────────────────────────────────────────────────────────────────

/** 星期名。原实现走 `EEEE` 的**本地化**名字，App 是纯中文界面，所以这里固定中文。 */
private fun weekdayCn(date: LocalDate): String = "星期" + "一二三四五六日"[date.dayOfWeek.ordinal]

private fun fullDateCn(date: LocalDate): String =
    "${date.year}年${date.month.ordinal + 1}月${date.day}日 ${weekdayCn(date)}"

private fun monthDayCn(date: LocalDate): String = "${date.month.ordinal + 1}月${date.day}日"

private fun monthDaySlash(date: LocalDate): String =
    "${pad2(date.month.ordinal + 1)}/${pad2(date.day)}"

private fun pad2(value: Int): String = value.toString().padStart(2, '0')

private fun int0(value: Float): String = value.roundToInt().toString()

private fun percent0(value: Float): String = "${value.roundToInt()}%"

/** LazyColumn 嵌套滚动接入 TopAppBar */
private fun Modifier.nestedScrollToTopAppBar(scrollBehavior: top.yukonga.miuix.kmp.basic.ScrollBehavior): Modifier {
    return this.then(nestedScroll(scrollBehavior.nestedScrollConnection))
}
