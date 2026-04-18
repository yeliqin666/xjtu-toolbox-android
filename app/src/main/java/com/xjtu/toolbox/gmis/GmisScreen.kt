package com.xjtu.toolbox.gmis

import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.utils.overScrollVertical

import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.School
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.auth.GmisLogin
import com.xjtu.toolbox.ui.ScheduleGrid
import com.xjtu.toolbox.ui.WeekSelector
import com.xjtu.toolbox.ui.components.EmptyState
import com.xjtu.toolbox.ui.components.ErrorState
import com.xjtu.toolbox.ui.components.LoadingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun GmisScreen(login: GmisLogin, onBack: () -> Unit) {
    val api = remember { GmisApi(login) }
    var courses by remember { mutableStateOf<List<GmisScheduleItem>>(emptyList()) }
    var scores by remember { mutableStateOf<List<GmisScoreItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var currentWeek by rememberSaveable { mutableIntStateOf(1) }
    val totalWeeks = 20
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    fun loadData() {
        isLoading = true
        errorMessage = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val scheduleDeferred = async { api.getSchedule() }
                    val scoreDeferred = async { api.getScore() }
                    courses = scheduleDeferred.await()
                    scores = scoreDeferred.await()
                }
            } catch (e: Exception) {
                errorMessage = "加载失败: ${e.message}"
            } finally { isLoading = false }
        }
    }

    LaunchedEffect(Unit) { loadData() }

    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    Scaffold(
        topBar = {
            TopAppBar(
                title = "研究生 · 日程/成绩",
                color = MiuixTheme.colorScheme.surfaceVariant,
                largeTitle = "研究生 · 日程/成绩",
                scrollBehavior = scrollBehavior,
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") } },
                actions = {
                    IconButton(onClick = { loadData() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).nestedScroll(scrollBehavior.nestedScrollConnection)) {
            Surface(modifier = Modifier.fillMaxWidth(), color = MiuixTheme.colorScheme.surfaceVariant) {
                TabRowWithContour(
                    tabs = listOf("日程", "成绩"),
                    selectedTabIndex = selectedTab,
                    onTabSelected = { selectedTab = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            if (isLoading) {
                LoadingState(message = "正在加载...", modifier = Modifier.fillMaxSize())
            } else if (errorMessage != null) {
                ErrorState(message = errorMessage!!, onRetry = { loadData() }, modifier = Modifier.fillMaxSize())
            } else {
                AnimatedContent(targetState = selectedTab, transitionSpec = {
                    fadeIn() + slideInHorizontally { if (targetState > initialState) it else -it } togetherWith
                            fadeOut() + slideOutHorizontally { if (targetState > initialState) -it else it }
                }, label = "gmisTab") { tab ->
                    when (tab) {
                        0 -> GmisScheduleTab(courses, currentWeek, totalWeeks) { currentWeek = it }
                        1 -> GmisScoreTab(scores)
                    }
                }
            }
        }
    }
}

@Composable
private fun GmisScheduleTab(courses: List<GmisScheduleItem>, currentWeek: Int, totalWeeks: Int, onWeekChange: (Int) -> Unit) {
    val allNames = remember(courses) { courses.map { it.name }.distinct().sorted() }
    Column(Modifier.fillMaxSize()) {
        WeekSelector(currentWeek, totalWeeks, onWeekChange)
        ScheduleGrid(courses.filter { it.isInWeek(currentWeek) }, allNames)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GmisScoreTab(scores: List<GmisScoreItem>) {
    if (scores.isEmpty()) {
        EmptyState(title = "暂无成绩数据", modifier = Modifier.fillMaxSize())
        return
    }
    val grouped = scores.groupBy { it.type }
    val typeOrder = listOf("学位课程", "选修课程", "必修环节")
    val totalCredits = scores.sumOf { it.coursePoint }
    val weightedSum = scores.sumOf { it.coursePoint * it.gpa }
    val overallGpa = if (totalCredits > 0) weightedSum / totalCredits else 0.0

    LazyColumn(Modifier.fillMaxSize().overScrollVertical().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        item {
            top.yukonga.miuix.kmp.basic.Card(Modifier.fillMaxWidth(), colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = MiuixTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text("成绩概览", style = MiuixTheme.textStyles.subtitle, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("%.2f".format(overallGpa), style = MiuixTheme.textStyles.title4, fontWeight = FontWeight.Bold); Text("加权GPA", style = MiuixTheme.textStyles.footnote1) }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("${scores.size}", style = MiuixTheme.textStyles.title4, fontWeight = FontWeight.Bold); Text("课程数", style = MiuixTheme.textStyles.footnote1) }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("%.1f".format(totalCredits), style = MiuixTheme.textStyles.title4, fontWeight = FontWeight.Bold); Text("总学分", style = MiuixTheme.textStyles.footnote1) }
                    }
                }
            }
        }
        for (type in typeOrder) {
            val items = grouped[type] ?: continue
            stickyHeader(key = "header_$type") {
                Surface(color = MiuixTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        type,
                        style = MiuixTheme.textStyles.body1,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 0.dp, vertical = 8.dp)
                    )
                }
            }
            items(items) { scoreItem ->

                val scoreColor = when { scoreItem.score >= 90 -> MiuixTheme.colorScheme.primary; scoreItem.score >= 75 -> MiuixTheme.colorScheme.primaryVariant; scoreItem.score >= 60 -> MiuixTheme.colorScheme.onSurfaceVariantSummary; else -> MiuixTheme.colorScheme.error }
                top.yukonga.miuix.kmp.basic.Card(Modifier.fillMaxWidth(), cornerRadius = 12.dp) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(scoreItem.courseName, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(4.dp))
                            Text("学分: ${scoreItem.coursePoint}  GPA: ${scoreItem.gpa}", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            if (scoreItem.examDate.isNotEmpty()) Text(scoreItem.examDate, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                        Text("%.0f".format(scoreItem.score), style = MiuixTheme.textStyles.headline1, fontWeight = FontWeight.Bold, color = scoreColor)
                    }
                }
            }
        }
    }
}