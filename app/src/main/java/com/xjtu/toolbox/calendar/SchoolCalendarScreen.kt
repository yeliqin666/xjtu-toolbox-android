package com.xjtu.toolbox.calendar

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjtu.toolbox.auth.SiteSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun SchoolCalendarScreen(site: SiteSession?, onBack: () -> Unit) {
    val api = remember(site) { SchoolCalendarApi(site) }

    var terms by remember { mutableStateOf<List<SchoolTerm>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var selectedTermIndex by remember { mutableIntStateOf(0) }

    val today = remember { LocalDate.now() }
    val scrollState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()

    // 加载数据
    LaunchedEffect(Unit) {
        try {
            val result = withContext(Dispatchers.IO) { api.getTerms() }
            terms = result
            // 默认选中当前学期
            val currentIdx = result.indexOfFirst { it.currentWeek(today) > 0 }
            selectedTermIndex = if (currentIdx >= 0) currentIdx else 0
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            errorMessage = "加载失败：${e.message}"
        }
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "校历",
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.padding(start = 8.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(size = 40.dp, strokeWidth = 3.dp)
                            Spacer(Modifier.height(12.dp))
                            Text("正在加载校历...", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                    }
                }
                errorMessage != null -> {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Default.CloudOff, null,
                            Modifier.size(56.dp),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            errorMessage!!,
                            style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                        if (site == null) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "提示：登录教务系统后可通过 SSO 自动访问校历",
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                    }
                }
                terms.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("暂无校历数据", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
                else -> {
                    val currentTerm = terms.getOrNull(selectedTermIndex) ?: terms.first()
                    TermContent(
                        terms = terms,
                        currentTerm = currentTerm,
                        today = today,
                        selectedIndex = selectedTermIndex,
                        onSelectTerm = { selectedTermIndex = it },
                        listState = scrollState,
                        scrollBehavior = scrollBehavior
                    )
                }
            }
        }
    }
}

@Composable
private fun TermContent(
    terms: List<SchoolTerm>,
    currentTerm: SchoolTerm,
    today: LocalDate,
    selectedIndex: Int,
    onSelectTerm: (Int) -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
    scrollBehavior: ScrollBehavior
) {
    val progress by animateFloatAsState(
        targetValue = currentTerm.progress(today),
        animationSpec = spring(),
        label = "progress"
    )
    val currentWeek = currentTerm.currentWeek(today)
    val daysRemaining = currentTerm.daysRemaining(today)
    val todayEvent = currentTerm.todayEvent(today)
    val isBeforeTerm = today < currentTerm.startDate
    val isAfterTerm = today > currentTerm.endDate

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().nestedScrollToTopAppBar(scrollBehavior),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        // ── 学期选择标签 ──────────────────────────────────
        if (terms.size > 1) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    terms.forEachIndexed { idx, term ->
                        val isSelected = idx == selectedIndex
                        val bgColor by animateColorAsState(
                            if (isSelected) MiuixTheme.colorScheme.primary
                            else MiuixTheme.colorScheme.secondaryContainer,
                            label = "tabBg"
                        )
                        val textColor by animateColorAsState(
                            if (isSelected) MiuixTheme.colorScheme.onPrimary
                            else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            label = "tabText"
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(bgColor)
                                .clickable { onSelectTerm(idx) }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = term.termName
                                    .replace("学年", "\n")
                                    .replace("第", "")
                                    .replace("学期", "学期"),
                                color = textColor,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // ── 英雄卡片：当前状态 + 进度 ──────────────────────
        item {
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

        // ── 统计信息行 ────────────────────────────────────
        item {
            StatsRow(
                totalWeeks = currentTerm.totalWeeks,
                workDays = currentTerm.workDays,
                daysRemaining = daysRemaining,
                currentWeek = currentWeek,
                isBeforeTerm = isBeforeTerm,
                isAfterTerm = isAfterTerm
            )
        }

        // ── 事件时间轴 ──────────────────────────────────
        item {
            Text(
                "日程安排",
                style = MiuixTheme.textStyles.subtitle,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp)
            )
        }

        itemsIndexed(currentTerm.events) { index, event ->
            val isPast = today > event.endDate
            val isCurrent = today >= event.startDate && today <= event.endDate
            EventTimelineItem(
                event = event,
                isPast = isPast,
                isCurrent = isCurrent,
                isLast = index == currentTerm.events.lastIndex
            )
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
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(Modifier.padding(20.dp)) {
            // 主标题：当前状态
            val statusTitle = when {
                isAfterTerm -> "本学期已结束"
                isBeforeTerm -> "距开学还有 ${
                    (currentTerm.startDate.toEpochDay() - today.toEpochDay()).toInt()
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

            val dateFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE")
            Text(
                today.format(dateFormatter),
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
                    val fmtMonthDay = DateTimeFormatter.ofPattern("MM/dd")
                    Text(
                        currentTerm.startDate.format(fmtMonthDay),
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    Text(
                        "%.0f%%".format(progress * 100),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = primaryColor
                    )
                    Text(
                        currentTerm.endDate.format(fmtMonthDay),
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
                Spacer(Modifier.height(4.dp))
                // 自定义进度条
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(surfaceVariant)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress)
                            .fillMaxHeight()
                            .background(primaryColor)
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
            Text(
                value,
                style = MiuixTheme.textStyles.subtitle,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.primary
            )
            Text(
                label,
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
    }
}

@Composable
private fun EventTimelineItem(
    event: CalendarEvent,
    isPast: Boolean,
    isCurrent: Boolean,
    isLast: Boolean
) {
    val accentColor = remember(event.colorHex) {
        runCatching {
            val argb = android.graphics.Color.parseColor(event.colorHex)
            Color(argb)
        }.getOrElse { Color(0xFF196DD0) }
    }
    val alpha = if (isPast) 0.45f else 1f

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .alpha(alpha)
    ) {
        // 时间线竖轴
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(20.dp)
        ) {
            Box(
                Modifier
                    .size(if (isCurrent) 12.dp else 8.dp)
                    .clip(CircleShape)
                    .background(if (isCurrent) accentColor else accentColor.copy(alpha = 0.6f))
            )
            if (!isLast) {
                Box(
                    Modifier
                        .width(2.dp)
                        .height(if (isCurrent) 60.dp else 52.dp)
                        .background(MiuixTheme.colorScheme.dividerLine)
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        // 事件内容
        Column(
            Modifier
                .weight(1f)
                .padding(bottom = if (isLast) 0.dp else 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isCurrent) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(accentColor)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "进行中",
                            fontSize = 10.sp,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    event.name,
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    color = if (isCurrent) MiuixTheme.colorScheme.onSurface
                    else MiuixTheme.colorScheme.onSurface
                )
            }

            val fmtDate = DateTimeFormatter.ofPattern("M月d日")
            val dateRange = if (event.startDate == event.endDate)
                event.startDate.format(fmtDate)
            else
                "${event.startDate.format(fmtDate)} ~ ${event.endDate.format(fmtDate)}"

            Text(
                "$dateRange · ${event.days}天",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 2.dp)
            )

            if (event.remark.isNotEmpty() && isCurrent) {
                Text(
                    event.remark,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
                )
            } else if (!isLast) {
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

/** LazyColumn 嵌套滚动接入 TopAppBar */
private fun Modifier.nestedScrollToTopAppBar(scrollBehavior: ScrollBehavior): Modifier {
    return this.nestedScroll(scrollBehavior.nestedScrollConnection)
}
