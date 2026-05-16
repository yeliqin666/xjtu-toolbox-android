package com.xjtu.toolbox.score

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
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.utils.overScrollVertical

import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.xjtu.toolbox.LocalAppLoginState
import com.xjtu.toolbox.Routes
import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.auth.LoginType
import com.xjtu.toolbox.auth.handleAuthExpired
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.auth.JwxtLogin
import com.xjtu.toolbox.ui.components.ErrorState
import com.xjtu.toolbox.ui.components.LoadingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 成绩报表查询页面（绕过评教限制）
 */
@Composable
fun ScoreReportScreen(
    login: JwxtLogin,
    studentId: String,
    onBack: () -> Unit
) {
    val appLoginState = LocalAppLoginState.current
    val api = remember { ScoreReportApi(login) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val dataCache = remember { com.xjtu.toolbox.util.DataCache(context) }
    val gson = remember { com.google.gson.Gson() }

    var isLoading by remember { mutableStateOf(true) }
    var isRefreshing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var allGrades by remember { mutableStateOf<List<ReportedGrade>>(emptyList()) }
    var termGroups by remember { mutableStateOf<Map<String, List<ReportedGrade>>>(emptyMap()) }
    var expandedTerms by rememberSaveable { mutableStateOf<Set<String>>(emptySet()) }
    var searchQuery by rememberSaveable { mutableStateOf("") }

    // 统计
    val totalCredits = allGrades.sumOf { it.coursePoint }
    val weightedGpa = if (totalCredits > 0) {
        allGrades.filter { it.gpa != null }.sumOf { it.gpa!! * it.coursePoint } / allGrades.filter { it.gpa != null }.sumOf { it.coursePoint }
    } else 0.0

    // 搜索过滤
    val filteredTermGroups = if (searchQuery.isBlank()) termGroups
    else termGroups.mapValues { (_, grades) ->
        grades.filter { it.courseName.contains(searchQuery, ignoreCase = true) }
    }.filter { it.value.isNotEmpty() }

    fun loadData() {
        isLoading = true
        isRefreshing = false
        errorMessage = null
        scope.launch {
            // SWR: 先尝试缓存秒显
            val cacheKey = "score_report_${studentId}"
            try {
                val cached = dataCache.get(cacheKey, com.xjtu.toolbox.util.DataCache.DEFAULT_TTL_MS)
                if (cached != null) {
                    val cachedGrades = gson.fromJson(cached, Array<ReportedGrade>::class.java).toList()
                    if (cachedGrades.isNotEmpty()) {
                        allGrades = cachedGrades
                        termGroups = cachedGrades.groupBy { it.term }.toSortedMap(compareByDescending { it })
                        if (expandedTerms.isEmpty() && termGroups.isNotEmpty()) {
                            expandedTerms = setOf(termGroups.keys.first())
                        }
                        isLoading = false
                        isRefreshing = true
                    }
                }
            } catch (_: Exception) { /* 缓存读取失败，正常加载 */ }

            try {
                val grades = withContext(Dispatchers.IO) {
                    api.getReportedGrade(studentId)
                }
                allGrades = grades
                termGroups = grades.groupBy { it.term }.toSortedMap(compareByDescending { it })
                if (expandedTerms.isEmpty() && termGroups.isNotEmpty()) {
                    expandedTerms = setOf(termGroups.keys.first())
                }
                // 更新缓存
                try { dataCache.put(cacheKey, gson.toJson(grades)) } catch (_: Exception) {}
            } catch (e: AuthExpiredException) {
                appLoginState.handleAuthExpired(LoginType.JWXT, Routes.SCORE_REPORT, onBack)
            } catch (e: Exception) {
                if (allGrades.isEmpty()) errorMessage = "加载失败: ${e.message}"
            } finally {
                isLoading = false
                isRefreshing = false
            }
        }
    }

    LaunchedEffect(Unit) { loadData() }

    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    Scaffold(
        topBar = {
            TopAppBar(
                title = "成绩报表",
                color = MiuixTheme.colorScheme.surfaceVariant,
                largeTitle = "成绩报表",
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    if (!isLoading) {
                        IconButton(onClick = { loadData() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).nestedScroll(scrollBehavior.nestedScrollConnection)) {
            if (isRefreshing) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            when {
            isLoading -> {
                LoadingState(
                    message = "正在加载成绩报表...",
                    modifier = Modifier.fillMaxSize()
                )
            }

            errorMessage != null -> {
                ErrorState(
                    message = errorMessage!!,
                    onRetry = { loadData() },
                    modifier = Modifier.fillMaxSize()
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().overScrollVertical().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    // 提醒卡片
                    item {
                        top.yukonga.miuix.kmp.basic.Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant)
                        ) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = MiuixTheme.colorScheme.primaryVariant)
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text("绕过评教查成绩", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface)
                                    Text("此功能通过帆软报表接口获取成绩，可在未评教时查看", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                }
                            }
                        }
                    }

                    // GPA 概览
                    item {
                        top.yukonga.miuix.kmp.basic.Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(Modifier.padding(20.dp)) {
                                Text("成绩概览", style = MiuixTheme.textStyles.title4, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface)
                                Spacer(Modifier.height(16.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("%.2f".format(weightedGpa), style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface)
                                        Text("加权 GPA", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                    }
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("${allGrades.size}", style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface)
                                        Text("课程数", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                    }
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("%.1f".format(totalCredits), style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface)
                                        Text("总学分", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                    }
                                }
                            }
                        }
                    }

                    // 搜索框
                    item {
                        com.xjtu.toolbox.ui.components.AppSearchBar(
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

                        item(key = "header_$term") {
                            top.yukonga.miuix.kmp.basic.Card(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    expandedTerms = if (isExpanded) expandedTerms - term else expandedTerms + term
                                },
                                pressFeedbackType = top.yukonga.miuix.kmp.utils.PressFeedbackType.Sink
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(16.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(formatTermDisplay(term), style = MiuixTheme.textStyles.subtitle, fontWeight = FontWeight.Bold)
                                        Text("${grades.size} 门课 · GPA %.2f".format(termGpa), style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                    }
                                    Icon(
                                        if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = null
                                    )
                                }
                            }
                        }

                        if (isExpanded) {
                            items(grades, key = { "${it.term}_${it.courseName}" }) { grade ->
                                ReportGradeCard(grade)
                            }
                        }
                    }

                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
        }
    }
}

@Composable
private fun ReportGradeCard(grade: ReportedGrade) {
    top.yukonga.miuix.kmp.basic.Card(
        modifier = Modifier.fillMaxWidth().animateContentSize()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    grade.courseName,
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${grade.coursePoint} 学分" + if (grade.gpa != null) " · GPA ${"%.2f".format(grade.gpa)}" else "",
                    style = MiuixTheme.textStyles.footnote1,
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
                style = MiuixTheme.textStyles.headline1,
                fontWeight = FontWeight.Bold,
                color = scoreColor
            )
        }
    }
}

/**
 * 格式化学期代码为显示文本
 * "2024-2025-1" → "2024-2025 秋季学期"
 */
private fun formatTermDisplay(term: String): String {
    val parts = term.split("-")
    if (parts.size != 3) return term
    val semesterName = when (parts[2]) {
        "1" -> "秋季学期"
        "2" -> "春季学期"
        "3" -> "夏季学期"
        else -> "第${parts[2]}学期"
    }
    return "${parts[0]}-${parts[1]} $semesterName"
}