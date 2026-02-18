package com.xjtu.toolbox.score

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.auth.JwxtLogin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 成绩报表查询页面（绕过评教限制）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScoreReportScreen(
    login: JwxtLogin,
    studentId: String,
    onBack: () -> Unit
) {
    val api = remember { ScoreReportApi(login) }
    val scope = rememberCoroutineScope()

    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var allGrades by remember { mutableStateOf<List<ReportedGrade>>(emptyList()) }
    var termGroups by remember { mutableStateOf<Map<String, List<ReportedGrade>>>(emptyMap()) }
    var expandedTerms by remember { mutableStateOf<Set<String>>(emptySet()) }

    // 统计
    val totalCredits = allGrades.sumOf { it.coursePoint }
    val weightedGpa = if (totalCredits > 0) {
        allGrades.filter { it.gpa != null }.sumOf { it.gpa!! * it.coursePoint } / allGrades.filter { it.gpa != null }.sumOf { it.coursePoint }
    } else 0.0

    LaunchedEffect(Unit) {
        try {
            val grades = withContext(Dispatchers.IO) {
                api.getReportedGrade(studentId)
            }
            allGrades = grades
            termGroups = grades.groupBy { it.term }.toSortedMap(compareByDescending { it })
            if (termGroups.isNotEmpty()) {
                expandedTerms = setOf(termGroups.keys.first())
            }
        } catch (e: Exception) {
            errorMessage = "加载失败: ${e.message}"
        } finally {
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("成绩报表") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                }
            )
        }
    ) { padding ->
        when {
            isLoading -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(8.dp))
                        Text("正在加载成绩报表...", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(4.dp))
                        Text("（绕过评教限制）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            errorMessage != null -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(errorMessage!!, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(onClick = {
                            errorMessage = null
                            isLoading = true
                            scope.launch {
                                try {
                                    val grades = withContext(Dispatchers.IO) { api.getReportedGrade(studentId) }
                                    allGrades = grades
                                    termGroups = grades.groupBy { it.term }.toSortedMap(compareByDescending { it })
                                    if (termGroups.isNotEmpty()) expandedTerms = setOf(termGroups.keys.first())
                                } catch (e: Exception) {
                                    errorMessage = "加载失败: ${e.message}"
                                } finally {
                                    isLoading = false
                                }
                            }
                        }) { Text("重试") }
                    }
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    // 提醒卡片
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                        ) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text("绕过评教查成绩", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onTertiaryContainer)
                                    Text("此功能通过帆软报表接口获取成绩，可在未评教时查看", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f))
                                }
                            }
                        }
                    }

                    // GPA 概览
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        ) {
                            Column(Modifier.padding(20.dp)) {
                                Text("成绩概览", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                Spacer(Modifier.height(16.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("%.2f".format(weightedGpa), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                        Text("加权 GPA", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                                    }
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("${allGrades.size}", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                        Text("课程数", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                                    }
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("%.1f".format(totalCredits), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                        Text("总学分", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                                    }
                                }
                            }
                        }
                    }

                    // 按学期分组
                    termGroups.forEach { (term, grades) ->
                        val isExpanded = term in expandedTerms
                        val termGpa = grades.filter { it.gpa != null }.let { valid ->
                            if (valid.isNotEmpty()) valid.sumOf { it.gpa!! * it.coursePoint } / valid.sumOf { it.coursePoint } else 0.0
                        }

                        item(key = "header_$term") {
                            Card(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    expandedTerms = if (isExpanded) expandedTerms - term else expandedTerms + term
                                }
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(16.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(formatTermDisplay(term), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                        Text("${grades.size} 门课 · GPA %.2f".format(termGpa), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

@Composable
private fun ReportGradeCard(grade: ReportedGrade) {
    Card(
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
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${grade.coursePoint} 学分" + if (grade.gpa != null) " · GPA ${grade.gpa}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            val scoreColor = when {
                grade.score.toDoubleOrNull()?.let { it < 60 } == true -> MaterialTheme.colorScheme.error
                grade.score.contains("不及格") -> MaterialTheme.colorScheme.error
                grade.score.toDoubleOrNull()?.let { it >= 90 } == true -> MaterialTheme.colorScheme.primary
                grade.score.toDoubleOrNull()?.let { it >= 80 } == true -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.onSurface
            }
            Text(
                grade.score,
                style = MaterialTheme.typography.headlineSmall,
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
