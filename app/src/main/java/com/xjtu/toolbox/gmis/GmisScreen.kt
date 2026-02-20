package com.xjtu.toolbox.gmis

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
import androidx.compose.material3.*
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

@OptIn(ExperimentalMaterial3Api::class)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("研究生 · 课表/成绩") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") } },
                actions = {
                    IconButton(onClick = { loadData() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                SegmentedButton(selected = selectedTab == 0, onClick = { selectedTab = 0 }, shape = SegmentedButtonDefaults.itemShape(0, 2),
                    icon = { SegmentedButtonDefaults.Icon(selectedTab == 0) { Icon(Icons.Default.CalendarMonth, null, Modifier.size(18.dp)) } }) { Text("课表") }
                SegmentedButton(selected = selectedTab == 1, onClick = { selectedTab = 1 }, shape = SegmentedButtonDefaults.itemShape(1, 2),
                    icon = { SegmentedButtonDefaults.Icon(selectedTab == 1) { Icon(Icons.Default.School, null, Modifier.size(18.dp)) } }) { Text("成绩") }
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

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        item {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text("成绩概览", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("%.2f".format(overallGpa), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text("加权GPA", style = MaterialTheme.typography.bodySmall) }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("${scores.size}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text("课程数", style = MaterialTheme.typography.bodySmall) }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("%.1f".format(totalCredits), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text("总学分", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
        for (type in typeOrder) {
            val items = grouped[type] ?: continue
            stickyHeader(key = "header_$type") {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        type,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 0.dp, vertical = 8.dp)
                    )
                }
            }
            items(items) { scoreItem ->

                val scoreColor = when { scoreItem.score >= 90 -> MaterialTheme.colorScheme.primary; scoreItem.score >= 75 -> MaterialTheme.colorScheme.tertiary; scoreItem.score >= 60 -> MaterialTheme.colorScheme.onSurfaceVariant; else -> MaterialTheme.colorScheme.error }
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(scoreItem.courseName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(4.dp))
                            Text("学分: ${scoreItem.coursePoint}  GPA: ${scoreItem.gpa}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (scoreItem.examDate.isNotEmpty()) Text(scoreItem.examDate, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("%.0f".format(scoreItem.score), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = scoreColor)
                    }
                }
            }
        }
    }
}
