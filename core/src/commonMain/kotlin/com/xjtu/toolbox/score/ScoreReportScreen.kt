package com.xjtu.toolbox.score

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.auth.LocalAuthExpiry
import com.xjtu.toolbox.error.FriendlyError
import com.xjtu.toolbox.error.SessionExpiredFailure
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.schedule.XjtuTime
import com.xjtu.toolbox.ui.adaptive.AdaptiveRowGrid
import com.xjtu.toolbox.ui.adaptive.fullLineItem
import com.xjtu.toolbox.ui.components.AppCardColor
import com.xjtu.toolbox.ui.components.AppPullToRefresh
import com.xjtu.toolbox.ui.components.AppSearchBar
import com.xjtu.toolbox.ui.components.ErrorState
import com.xjtu.toolbox.ui.components.FullPageState
import com.xjtu.toolbox.ui.components.LoadingState
import com.xjtu.toolbox.ui.glass.GlassTopAppBar
import com.xjtu.toolbox.ui.glass.glassSource
import com.xjtu.toolbox.ui.glass.glassTop
import com.xjtu.toolbox.ui.glass.rememberPageGlass
import com.xjtu.toolbox.ui.glass.withoutTop
import com.xjtu.toolbox.ui.rememberHaptics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * 成绩报表 —— 从 `:app/score/ScoreReportScreen.kt` 搬进 `:core`（347 行，逐字搬）。
 *
 * 搬迁时被替换掉的写法（其余逐字保留）：
 *  1. `SiteSession` + `studentId` → 注入的 [source]（`:app` 的适配器把学号闭在里面，
 *     见 `:app/score/ScoreReportApp.kt`）；
 *  2. `LocalAppLoginState.handleAuthExpired(...)` → [LocalAuthExpiry]（见 `:core/auth/AuthExpiry.kt`）；
 *  3. `DataCache(context, accountId)`（SWR 缓存）→ 注入的 [cache] 端口（`:app` 仍用 `DataCache`，
 *     Web 用 localStorage，行为各自保持原样）；
 *  4. `"%.2f".format(...)` / `"%.1f".format(...)`（`String.format` 是 JVM 专属且是默认导入）
 *     → [formatTwoDecimals] / [formatOneDecimal]；
 *  5. `groupBy { it.term }.toSortedMap(compareByDescending { it })` —— `toSortedMap` 是
 *     **JVM 专属**扩展，换成「按学期代码降序的 `List<Pair>`」（同一个顺序：代码是 `2025-2026-1`
 *     这种零填充形式，字典序降序就是时间降序，与原实现一致）。
 *
 * @param cache 传 null = 这一端不缓存（屏幕每次都真拉）。`:app` 与 Web 都传实现。
 */
@Composable
fun ScoreReportScreen(
    source: ScoreReportSource,
    onBack: () -> Unit,
    cache: ScoreReportCache? = null,
) {
    val authExpiry = LocalAuthExpiry.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()

    var isLoading by remember { mutableStateOf(true) }
    var isRefreshing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var allGrades by remember { mutableStateOf<List<ReportedGrade>>(emptyList()) }
    var termGroups by remember { mutableStateOf<List<Pair<String, List<ReportedGrade>>>>(emptyList()) }
    var expandedTerms by rememberSaveable { mutableStateOf<Set<String>>(emptySet()) }
    var searchQuery by rememberSaveable { mutableStateOf("") }

    // 统计
    val totalCredits = allGrades.sumOf { it.coursePoint }
    val weightedGpa = if (totalCredits > 0) {
        allGrades.filter { it.gpa != null }.sumOf { it.gpa!! * it.coursePoint } /
            allGrades.filter { it.gpa != null }.sumOf { it.coursePoint }
    } else 0.0

    // 搜索过滤
    val filteredTermGroups = if (searchQuery.isBlank()) termGroups
    else termGroups
        .map { (term, grades) -> term to grades.filter { it.courseName.contains(searchQuery, ignoreCase = true) } }
        .filter { it.second.isNotEmpty() }

    fun applyGrades(grades: List<ReportedGrade>) {
        allGrades = grades
        termGroups = groupByTermDescending(grades)
        if (expandedTerms.isEmpty() && termGroups.isNotEmpty()) {
            expandedTerms = setOf(termGroups.first().first)
        }
    }

    fun loadData(silent: Boolean = false) {
        if (silent) isRefreshing = true else {
            isLoading = true
            isRefreshing = false
        }
        errorMessage = null
        scope.launch {
            // SWR: 先尝试缓存秒显
            if (!silent && cache != null) {
                try {
                    val cachedGrades = cache.read()
                    if (cachedGrades != null && cachedGrades.isNotEmpty()) {
                        applyGrades(cachedGrades)
                        isLoading = false
                        isRefreshing = true
                    }
                } catch (_: Exception) { /* 缓存读取失败，正常加载 */ }
            }

            try {
                val grades = source.grades()
                applyGrades(grades)
                haptics.success()
                // 更新缓存
                runCatching { cache?.write(grades) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is SessionExpiredFailure) {
                    authExpiry.onAuthExpired(AppRoute.ScoreReport, onBack)
                } else {
                    if (allGrades.isEmpty()) {
                        errorMessage = FriendlyError.of(e, "加载")
                        haptics.error()
                    }
                }
            } finally {
                isLoading = false
                isRefreshing = false
            }
        }
    }

    LaunchedEffect(source) { loadData() }

    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    // 玻璃顶栏（经典风格下为 null，一切照旧），用法见 ui/glass/GlassTopBar.kt
    val glass = rememberPageGlass()
    Scaffold(
        topBar = {
            GlassTopAppBar(
                title = "成绩报表",
                glass = glass,
                scrollBehavior = scrollBehavior,
                onBack = onBack,
            )
        }
    ) { padding ->
        // 内容铺到顶栏下面，顶部留白放进各个列表里；下拉指示器也从顶栏下面出来
        val glassTop = padding.glassTop(glass)
        AppPullToRefresh(
            isRefreshing = isRefreshing,
            onRefresh = { loadData(silent = true) },
            scrollBehavior = scrollBehavior,
            topPadding = glassTop,
            modifier = Modifier.fillMaxSize().padding(padding.withoutTop(glass)).glassSource(glass),
        ) {
            when {
                isLoading -> {
                    FullPageState(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = glassTop)) {
                        LoadingState(message = "正在加载成绩报表...", modifier = Modifier.fillMaxSize())
                    }
                }

                errorMessage != null && allGrades.isEmpty() -> {
                    FullPageState(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = glassTop)) {
                        ErrorState(
                            message = errorMessage!!,
                            onRetry = { loadData() },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                else -> {
                    // 宽屏学期卡分两三列（见 AdaptiveRowGrid：卡片会竖着展开，按行对齐，展开一张别的卡不挪位置）；GPA、说明、搜索横跨全宽。
                    // 以前一列学期卡横跨整个平板宽度，一门课的名字和分数隔着大半个屏幕。
                    AdaptiveRowGrid(
                        modifier = Modifier.fillMaxSize().overScrollVertical(),
                        spacing = 12.dp,
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = glassTop + 8.dp, bottom = 8.dp)
                    ) {
                        // GPA 概览：三个数字是这页的主角，放在最上面
                        fullLineItem {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.defaultColors(color = AppCardColor)
                            ) {
                                Column(Modifier.padding(vertical = 18.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                        ScoreStat(formatTwoDecimals(weightedGpa), "加权 GPA", MiuixTheme.colorScheme.primary)
                                        ScoreStat("${allGrades.size}", "课程数", MiuixTheme.colorScheme.onSurface)
                                        ScoreStat(formatOneDecimal(totalCredits), "总学分", MiuixTheme.colorScheme.onSurface)
                                    }
                                }
                            }
                        }

                        // 说明文字改成一行脚注：它是一次性说明，不值得占一张和数据卡同等重量的卡片
                        fullLineItem {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "通过帆软报表接口获取，未评教也能查看",
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            }
                        }

                        // 搜索框
                        fullLineItem {
                            AppSearchBar(
                                query = searchQuery,
                                onQueryChange = { searchQuery = it },
                                label = "搜索课程名称...",
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // 按学期分组
                        filteredTermGroups.forEach { (term, grades) ->
                            val isExpanded = term in expandedTerms
                            val termGpa = grades.filter { it.gpa != null }.let { valid ->
                                if (valid.isNotEmpty()) valid.sumOf { it.gpa!! * it.coursePoint } / valid.sumOf { it.coursePoint } else 0.0
                            }

                            // 一个学期 = 一张卡：卡头可折叠，展开后课程逐行排在同一张卡内。
                            // 之前是"学期头一张卡 + 每门课各一张卡"，滚起来是一串碎片。
                            item(key = "term_$term") {
                                Card(
                                    modifier = Modifier.fillMaxWidth().animateContentSize(),
                                    colors = CardDefaults.defaultColors(color = AppCardColor)
                                ) {
                                    Column(Modifier.fillMaxWidth()) {
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    expandedTerms = if (isExpanded) expandedTerms - term else expandedTerms + term
                                                }
                                                .padding(16.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column {
                                                Text(formatTermDisplay(term), style = MiuixTheme.textStyles.subtitle, fontWeight = FontWeight.Bold)
                                                Text("${grades.size} 门课 · GPA ${formatTwoDecimals(termGpa)}", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                            }
                                            Icon(
                                                if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                                contentDescription = null,
                                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                            )
                                        }
                                        if (isExpanded) {
                                            // 卡头之上已有一行，所以每一行课程都需要上分隔线
                                            grades.forEach { grade -> ReportGradeRow(grade) }
                                            Spacer(Modifier.height(4.dp))
                                        }
                                    }
                                }
                            }
                        }

                        fullLineItem { Spacer(Modifier.height(16.dp)) }
                    }
                }
            }
        }
    }
}

/** 按学期代码降序（= 时间降序）分组。原实现是 `groupBy { it.term }.toSortedMap(compareByDescending { it })`。 */
private fun groupByTermDescending(grades: List<ReportedGrade>): List<Pair<String, List<ReportedGrade>>> =
    grades.groupBy { it.term }
        .entries
        .sortedByDescending { it.key }
        .map { it.key to it.value }

/** 概览卡里的单个统计数字。 */
@Composable
private fun ScoreStat(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold, color = color)
        Spacer(Modifier.height(2.dp))
        Text(label, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

/**
 * 成绩行。
 *
 * 原来每门课是一张独立 Card + 12dp 间距，几十门课就是几十个悬浮小方块，
 * 读起来全是边框和空隙。现在同一学期的课合并进一张卡里，逐行排列 + 细分隔线，
 * 成绩靠右等宽对齐，扫一列就能比大小。
 */
@Composable
private fun ReportGradeRow(grade: ReportedGrade) {
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .padding(start = 16.dp)
                .fillMaxWidth()
                .height(0.5.dp)
                .background(MiuixTheme.colorScheme.outline.copy(alpha = 0.5f))
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    grade.courseName,
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${grade.coursePoint} 学分" + if (grade.gpa != null) " · GPA ${formatTwoDecimals(grade.gpa)}" else "",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
            Spacer(Modifier.width(12.dp))
            val scoreColor = when {
                grade.score.toDoubleOrNull()?.let { it < 60 } == true -> MiuixTheme.colorScheme.error
                grade.score.contains("不及格") -> MiuixTheme.colorScheme.error
                grade.score.toDoubleOrNull()?.let { it >= 90 } == true -> MiuixTheme.colorScheme.primary
                grade.score.toDoubleOrNull()?.let { it >= 80 } == true -> MiuixTheme.colorScheme.primaryVariant
                else -> MiuixTheme.colorScheme.onSurface
            }
            Text(
                grade.score,
                style = MiuixTheme.textStyles.title4,
                fontWeight = FontWeight.Bold,
                color = scoreColor
            )
        }
    }
}

private fun formatTermDisplay(term: String): String = XjtuTime.displayTerm(term)
