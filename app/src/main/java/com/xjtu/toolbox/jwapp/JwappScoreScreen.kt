package com.xjtu.toolbox.jwapp

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.auth.JwappLogin
import com.xjtu.toolbox.auth.JwxtLogin
import com.xjtu.toolbox.score.ScoreReportApi
import com.xjtu.toolbox.score.ReportedGrade
import com.xjtu.toolbox.judge.JudgeApi
import com.xjtu.toolbox.ui.components.LoadingState
import com.xjtu.toolbox.ui.components.ErrorState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JwappScoreScreen(
    login: JwappLogin,
    jwxtLogin: JwxtLogin? = null,
    studentId: String = "",
    onBack: () -> Unit
) {
    val api = remember { JwappApi(login) }
    val scope = rememberCoroutineScope()

    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var allTermScores by remember { mutableStateOf<List<TermScore>>(emptyList()) }
    var termList by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var selectedTermIndex by remember { mutableIntStateOf(0) }
    var currentWeek by remember { mutableIntStateOf(0) }
    var expandedCourseId by remember { mutableStateOf<String?>(null) }
    var courseDetails by remember { mutableStateOf<Map<String, ScoreDetail>>(emptyMap()) }
    var detailLoading by remember { mutableStateOf<String?>(null) }

    // GPA 选课计算模式
    var gpaSelectMode by remember { mutableStateOf(false) }
    var selectedCourseIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showGpaTips by remember { mutableStateOf(false) }

    // 搜索 & 过滤
    var searchQuery by remember { mutableStateOf("") }
    var excludedCategories by remember { mutableStateOf<Set<String>>(emptySet()) }

    // GPA 小数精度（点击循环 2 → 3 → 4 → 2）
    var gpaPrecision by remember { mutableIntStateOf(2) }

    // 未评教课程名集合（来自 JudgeApi）
    var unevaluatedCourses by remember { mutableStateOf<Set<String>>(emptySet()) }
    // 报表补充数据加载状态
    var reportHint by remember { mutableStateOf<String?>(null) }

    fun loadScoreData() {
        isLoading = true
        errorMessage = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val basis = api.getTimeTableBasis()
                    currentWeek = basis.todayWeekNum
                    val grades = api.getGrade(null).toMutableList()

                    // 课程名标准化（去除各种空格、特殊字符、课程标记符号差异）
                    fun normalizeKey(term: String, name: String): String {
                        val n = name.trim()
                            .replace("\u3000", " ")  // 全角空格
                            .replace("\u00A0", " ")  // 不间断空格
                            .replace(Regex("\\s+"), " ")
                            .replace("（", "(").replace("）", ")")
                            .replace("＋", "+").replace("－", "-")
                            .replace(Regex("[◇◆◎○●★☆※▲△▼▽]"), "")  // 去除课程标记符号
                            .replace(Regex("\\([A-Z]{2,}\\d{4,}\\)$"), "")  // 去除末尾课程代码如(PHYS546609)
                            .trim()
                            .lowercase()
                        return "${term.trim()}|$n"
                    }

                    // 已有课程名+学期 索引（用于去重）
                    val existingKeys = grades.flatMap { ts ->
                        ts.scoreList.map { normalizeKey(ts.termCode, it.courseName) }
                    }.toMutableSet()

                    // 加载报表成绩 + 未评教（先加载未评教列表，再用评教状态安全去重）
                    if (jwxtLogin != null && studentId.isNotEmpty()) {
                        // ⚠ 第一步：加载未评教课程列表（用于安全去重判断）
                        // 已评教课程 jwapp 必定已包含，仅补充未评教课程的报表成绩
                        var unevalSet = emptySet<String>()
                        try {
                            val judgeApi = JudgeApi(jwxtLogin)
                            val unfinished = judgeApi.unfinishedQuestionnaires()
                            unevalSet = unfinished.map { it.KCM }.toSet()
                            unevaluatedCourses = unevalSet
                            android.util.Log.d("ScoreDedup", "未评教课程 ${unevalSet.size} 门: ${unevalSet.joinToString()}")
                        } catch (e: Exception) {
                            android.util.Log.w("Score", "未评教查询失败(报表补充跳过): ${e.message}")
                        }

                        // 第二步：仅在有未评教课程时才从报表补充
                        if (unevalSet.isNotEmpty()) {
                            try {
                                val reportGrades = ScoreReportApi(jwxtLogin).getReportedGrade(studentId)
                                val supplementByTerm = mutableMapOf<String, MutableList<ScoreItem>>()
                                for (rg in reportGrades) {
                                    // 安全策略：仅补充未评教课程（课名相同但课号不同是不同课，按名去重危险）
                                    if (rg.courseName !in unevalSet) continue
                                    val key = normalizeKey(rg.term, rg.courseName)
                                    if (key !in existingKeys) {
                                        android.util.Log.d("ScoreDedup", "ADDING report(未评教): key='$key' name='${rg.courseName}' term='${rg.term}'")
                                        existingKeys.add(key)
                                        val item = rg.toScoreItem()
                                        supplementByTerm.getOrPut(rg.term) { mutableListOf() }.add(item)
                                    }
                                }
                                if (supplementByTerm.isNotEmpty()) {
                                    val newGrades = grades.map { ts ->
                                        val extras = supplementByTerm.remove(ts.termCode)
                                        if (extras != null) ts.copy(scoreList = ts.scoreList + extras) else ts
                                    }.toMutableList()
                                    for ((termCode, items) in supplementByTerm) {
                                        val termName = termCode.replace("-", "—").let { "报表·$it" }
                                        newGrades.add(TermScore(termCode, termName, items))
                                    }
                                    grades.clear()
                                    grades.addAll(newGrades)
                                    val totalReport = grades.flatMap { it.scoreList }.count { it.source == ScoreSource.REPORT }
                                    if (totalReport > 0) reportHint = "已从报表补充 $totalReport 门未评教课程成绩"
                                }
                            } catch (e: Exception) {
                                android.util.Log.w("Score", "报表加载失败: ${e.message}")
                            }
                        } else {
                            android.util.Log.d("ScoreDedup", "无未评教课程或查询失败，跳过报表补充")
                        }
                    }

                    allTermScores = grades
                    termList = grades.map { it.termCode to it.termName }
                }
            } catch (e: Exception) {
                errorMessage = "加载失败: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) { loadScoreData() }

    val currentTermScores = if (selectedTermIndex == 0 && allTermScores.isNotEmpty()) {
        // "所有学期" 选项
        allTermScores.flatMap { it.scoreList }
    } else if (allTermScores.isNotEmpty() && (selectedTermIndex - 1) in allTermScores.indices) {
        allTermScores[selectedTermIndex - 1].scoreList
    } else {
        emptyList()
    }

    // 所有出现的课程类别（majorFlag），用于过滤 chips
    val allCategories = remember(allTermScores) {
        allTermScores.flatMap { it.scoreList }
            .mapNotNull { it.majorFlag }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }

    // 经过搜索 + 类别排除后的课程列表
    val filteredScores = remember(currentTermScores, searchQuery, excludedCategories) {
        currentTermScores.filter { score ->
            (searchQuery.isBlank() || score.courseName.contains(searchQuery, ignoreCase = true)) &&
            (excludedCategories.isEmpty() || score.majorFlag !in excludedCategories)
        }
    }

    // 基于当前选中学期 + 过滤条件计算 GPA（非选课模式）
    val displayGpaInfo = remember(filteredScores) {
        if (filteredScores.isNotEmpty()) {
            api.calculateGpaForCourses(filteredScores)
        } else null
    }

    val selectedGpaInfo = remember(selectedCourseIds, allTermScores) {
        if (selectedCourseIds.isNotEmpty()) {
            val selected = allTermScores.flatMap { it.scoreList }.filter { it.id in selectedCourseIds }
            api.calculateGpaForCourses(selected)
        } else null
    }

    // GPA 映射表弹窗
    if (showGpaTips) {
        GpaMappingDialog(onDismiss = { showGpaTips = false })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (gpaSelectMode) "选课算 GPA" else "成绩查询") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (gpaSelectMode) { gpaSelectMode = false; selectedCourseIds = emptySet() }
                        else onBack()
                    }) {
                        Icon(
                            if (gpaSelectMode) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                actions = {
                    // GPA 映射表
                    IconButton(onClick = { showGpaTips = true }) {
                        Icon(Icons.Default.Info, contentDescription = "GPA 映射")
                    }
                    // 选课算 GPA 切换
                    if (!gpaSelectMode) {
                        IconButton(onClick = {
                            gpaSelectMode = true
                            // 默认全选当前学期
                            selectedCourseIds = currentTermScores.map { it.id }.toSet()
                        }) {
                            Icon(Icons.Default.Calculate, contentDescription = "选课算GPA")
                        }
                    } else {
                        // 全选/取消
                        IconButton(onClick = {
                            val allIds = allTermScores.flatMap { it.scoreList }.map { it.id }.toSet()
                            selectedCourseIds = if (selectedCourseIds.size == allIds.size) emptySet() else allIds
                        }) {
                            Icon(Icons.Default.SelectAll, contentDescription = "全选")
                        }
                    }
                }
            )
        }
    ) { padding ->
        when {
            isLoading -> {
                LoadingState(message = "正在加载成绩数据...", modifier = Modifier.fillMaxSize().padding(padding))
            }

            errorMessage != null -> {
                ErrorState(
                    message = errorMessage!!,
                    onRetry = { loadScoreData() },
                    modifier = Modifier.fillMaxSize().padding(padding)
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    // GPA 卡片
                    item {
                        GpaCard(
                            gpaInfo = if (gpaSelectMode) selectedGpaInfo else displayGpaInfo,
                            currentWeek = currentWeek,
                            totalCourses = if (gpaSelectMode) selectedCourseIds.size else filteredScores.size,
                            totalCredits = if (gpaSelectMode) {
                                allTermScores.flatMap { it.scoreList }
                                    .filter { it.id in selectedCourseIds }
                                    .sumOf { it.coursePoint }
                            } else filteredScores.sumOf { it.coursePoint },
                            isSelectMode = gpaSelectMode,
                            precision = gpaPrecision,
                            onPrecisionToggle = { gpaPrecision = if (gpaPrecision >= 4) 2 else gpaPrecision + 1 }
                        )
                    }

                    item {
                        if (termList.isNotEmpty()) {
                            val allTermsList = listOf("all" to "所有学期") + termList
                            TermSelector(
                                termList = allTermsList,
                                selectedIndex = selectedTermIndex,
                                onSelect = { selectedTermIndex = it; expandedCourseId = null }
                            )
                        }
                    }

                    // 搜索框
                    item {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            label = { Text("搜索课程") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Default.Close, "清除")
                                    }
                                }
                            }
                        )
                    }

                    // 类别排除 chips
                    if (allCategories.isNotEmpty()) {
                        item {
                            Column {
                                Text("排除类别", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(4.dp))
                                @OptIn(ExperimentalLayoutApi::class)
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    allCategories.forEach { category ->
                                        val isExcluded = category in excludedCategories
                                        FilterChip(
                                            selected = isExcluded,
                                            onClick = {
                                                excludedCategories = if (isExcluded)
                                                    excludedCategories - category
                                                else
                                                    excludedCategories + category
                                            },
                                            label = { Text(category, style = MaterialTheme.typography.labelSmall) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (filteredScores.isEmpty()) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                                Text("暂无成绩数据", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else {
                        // 报表补充提示
                        if (reportHint != null) {
                            item {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(18.dp))
                                        Text(reportHint!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                                    }
                                }
                            }
                        }

                        items(filteredScores, key = { it.id }) { scoreItem ->
                            val isFromReport = scoreItem.source == ScoreSource.REPORT
                            val isUnevaluated = scoreItem.courseName in unevaluatedCourses
                            val isExpanded = expandedCourseId == scoreItem.id
                            val detail = courseDetails[scoreItem.id]
                            val isDetailLoading = detailLoading == scoreItem.id
                            val isSelected = scoreItem.id in selectedCourseIds

                            ScoreCard(
                                scoreItem = scoreItem,
                                isExpanded = isExpanded && !isFromReport,
                                detail = detail,
                                isDetailLoading = isDetailLoading,
                                showCheckbox = gpaSelectMode,
                                isSelected = isSelected,
                                isFromReport = isFromReport,
                                isUnevaluated = isUnevaluated,
                                onToggle = {
                                    if (gpaSelectMode) {
                                        selectedCourseIds = if (isSelected) {
                                            selectedCourseIds - scoreItem.id
                                        } else {
                                            selectedCourseIds + scoreItem.id
                                        }
                                    } else if (!isFromReport) {
                                        if (isExpanded) {
                                            expandedCourseId = null
                                        } else {
                                            expandedCourseId = scoreItem.id
                                            if (detail == null && !isDetailLoading) {
                                                detailLoading = scoreItem.id
                                                scope.launch {
                                                    try {
                                                        val d = withContext(Dispatchers.IO) { api.getDetail(scoreItem.id) }
                                                        courseDetails = courseDetails + (scoreItem.id to d)
                                                    } catch (_: Exception) { } finally { detailLoading = null }
                                                }
                                            }
                                        }
                                    }
                                }
                            )
                        }
                    }

                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@Composable
fun GpaCard(gpaInfo: GpaInfo?, currentWeek: Int, totalCourses: Int, totalCredits: Double, isSelectMode: Boolean, precision: Int = 2, onPrecisionToggle: () -> Unit = {}) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelectMode) MaterialTheme.colorScheme.tertiaryContainer
            else MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (isSelectMode) "选课均分" else "成绩概览",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (isSelectMode) MaterialTheme.colorScheme.onTertiaryContainer
                    else MaterialTheme.colorScheme.onPrimaryContainer
                )
                if (currentWeek > 0 && !isSelectMode) {
                    SuggestionChip(onClick = {}, label = { Text("第${currentWeek}周", style = MaterialTheme.typography.labelMedium) })
                }
                if (isSelectMode) {
                    SuggestionChip(onClick = {}, label = { Text("已选 $totalCourses 门", style = MaterialTheme.typography.labelMedium) })
                }
            }
            Spacer(Modifier.height(16.dp))
            val textColor = if (isSelectMode) MaterialTheme.colorScheme.onTertiaryContainer
                else MaterialTheme.colorScheme.onPrimaryContainer
            val numStyle = when {
                precision >= 4 -> MaterialTheme.typography.titleMedium
                precision >= 3 -> MaterialTheme.typography.titleLarge
                else -> MaterialTheme.typography.headlineMedium
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f).clickable { onPrecisionToggle() }) {
                    if (gpaInfo != null) {
                        Text("%.${precision}f".format(gpaInfo.gpa), style = numStyle, fontWeight = FontWeight.Bold, color = textColor, maxLines = 1)
                        Text(if (isSelectMode) "GPA" else "总 GPA", style = MaterialTheme.typography.bodySmall, color = textColor.copy(alpha = 0.7f))
                    } else {
                        Text("—", style = numStyle, fontWeight = FontWeight.Bold, color = textColor.copy(alpha = 0.5f))
                        Text("GPA", style = MaterialTheme.typography.bodySmall, color = textColor.copy(alpha = 0.7f))
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f).clickable { onPrecisionToggle() }) {
                    if (gpaInfo != null && gpaInfo.averageScore > 0) {
                        Text("%.${precision}f".format(gpaInfo.averageScore), style = numStyle, fontWeight = FontWeight.Bold, color = textColor, maxLines = 1)
                        Text("均分", style = MaterialTheme.typography.bodySmall, color = textColor.copy(alpha = 0.7f))
                    } else {
                        Text("—", style = numStyle, fontWeight = FontWeight.Bold, color = textColor.copy(alpha = 0.5f))
                        Text("均分", style = MaterialTheme.typography.bodySmall, color = textColor.copy(alpha = 0.7f))
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    Text("${gpaInfo?.courseCount ?: totalCourses}", style = numStyle, fontWeight = FontWeight.Bold, color = textColor, maxLines = 1)
                    Text("课程数", style = MaterialTheme.typography.bodySmall, color = textColor.copy(alpha = 0.7f))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    Text("%.1f".format(gpaInfo?.totalCredits ?: totalCredits), style = numStyle, fontWeight = FontWeight.Bold, color = textColor, maxLines = 1)
                    Text("总学分", style = MaterialTheme.typography.bodySmall, color = textColor.copy(alpha = 0.7f))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TermSelector(termList: List<Pair<String, String>>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selectedTerm = termList.getOrNull(selectedIndex)

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedTerm?.second ?: "选择学期",
            onValueChange = {},
            readOnly = true,
            label = { Text("学期") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            termList.forEachIndexed { index, (_, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { onSelect(index); expanded = false }, contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding)
            }
        }
    }
}

@Composable
fun ScoreCard(
    scoreItem: ScoreItem,
    isExpanded: Boolean,
    detail: ScoreDetail?,
    isDetailLoading: Boolean,
    showCheckbox: Boolean = false,
    isSelected: Boolean = false,
    isFromReport: Boolean = false,
    isUnevaluated: Boolean = false,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)).clickable { onToggle() },
        colors = if (isFromReport) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                 else CardDefaults.cardColors()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                // 选课模式复选框
                if (showCheckbox) {
                    Icon(
                        if (isSelected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                        contentDescription = null,
                        tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(scoreItem.courseName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (isFromReport) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.tertiary,
                                contentColor = MaterialTheme.colorScheme.onTertiary
                            ) {
                                Text("报表", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                            }
                        }
                        if (isUnevaluated) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            ) {
                                Text("未评教", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${scoreItem.coursePoint}学分", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!scoreItem.majorFlag.isNullOrBlank()) {
                            val flagColor = when (scoreItem.majorFlag) {
                                "必修" -> MaterialTheme.colorScheme.primary
                                "选修" -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Text(scoreItem.majorFlag, style = MaterialTheme.typography.bodySmall, color = flagColor, fontWeight = FontWeight.Medium)
                        }
                        if (scoreItem.examProp.isNotEmpty() && scoreItem.examProp != "初修") {
                            Text(scoreItem.examProp, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        if (scoreItem.examType.isNotEmpty()) {
                            Text(scoreItem.examType, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        // 显示课程 GPA（服务器返回>0时使用，否则本地映射）
                        val courseGpa = (scoreItem.gpa?.takeIf { it > 0.0 })
                            ?: com.xjtu.toolbox.score.ScoreReportApi.scoreToGpa(scoreItem.score)
                        if (courseGpa != null) {
                            Text("GPA %.1f".format(courseGpa), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.End) {
                    // 综合判断是否通过（API passFlag 对等级制课程可能错误返回 false）
                    val localGpa = com.xjtu.toolbox.score.ScoreReportApi.scoreToGpa(scoreItem.score)
                    val reallyPassed = scoreItem.passFlag
                            || (localGpa != null && localGpa > 0.0)
                            || (scoreItem.scoreValue != null && scoreItem.scoreValue >= 60.0)
                    val scoreColor = when {
                        !reallyPassed -> MaterialTheme.colorScheme.error
                        scoreItem.scoreValue != null && scoreItem.scoreValue >= 90 -> MaterialTheme.colorScheme.primary
                        scoreItem.scoreValue != null && scoreItem.scoreValue >= 80 -> MaterialTheme.colorScheme.tertiary
                        scoreItem.scoreValue == null && reallyPassed -> MaterialTheme.colorScheme.primary // 等级制通过
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    Text(scoreItem.score, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = scoreColor)
                    // 等级制成绩显示对应的数字分数
                    if (scoreItem.scoreValue == null) {
                        val equiv = gradeToNumericScore(scoreItem.score)
                        if (equiv != null) {
                            Text("≈ %.0f分".format(equiv), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (!reallyPassed) {
                        Text(scoreItem.specificReason ?: "未通过", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                if (!showCheckbox && !isFromReport) {
                    IconButton(onClick = onToggle) {
                        Icon(if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = if (isExpanded) "收起" else "展开")
                    }
                }
            }

            AnimatedVisibility(
                visible = isExpanded && !showCheckbox,
                enter = expandVertically(animationSpec = spring(stiffness = Spring.StiffnessLow)) + fadeIn(),
                exit = shrinkVertically(animationSpec = spring(stiffness = Spring.StiffnessLow)) + fadeOut()
            ) {
                Column(Modifier.padding(top = 12.dp)) {
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    if (isDetailLoading) {
                        Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(24.dp))
                        }
                    } else if (detail != null) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            DetailChip("绩点", "%.1f".format(detail.gpa))
                            DetailChip("学分", "%.1f".format(detail.coursePoint))
                            DetailChip("类型", detail.examType)
                            if (detail.majorFlag != null) DetailChip("性质", detail.majorFlag)
                        }
                        if (detail.itemList.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            Text("分项成绩", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(8.dp))
                            detail.itemList.forEach { item -> ScoreDetailRow(item) }
                        }
                    } else {
                        Text("无法加载详细成绩", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
fun ScoreDetailRow(item: ScoreDetailItem) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(item.itemName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text("%.0f%%".format(item.itemPercent * 100), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(48.dp))
        if (item.itemScoreValue != null) {
            LinearProgressIndicator(
                progress = { (item.itemScoreValue / 100.0).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.weight(1f).height(8.dp).padding(horizontal = 8.dp),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        Text(item.itemScore, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun DetailChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun GpaMappingDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
        title = { Text("GPA 映射规则", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("数字成绩 → GPA（4.3 绩点制）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text("依据：西交教〔2015〕87号 及 本科生学籍管理与学位授予规定（2017）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val numericRules = listOf(
                    "95-100" to "4.3", "90-94" to "4.0", "85-89" to "3.7",
                    "81-84" to "3.3", "78-80" to "3.0", "75-77" to "2.7",
                    "72-74" to "2.3", "68-71" to "2.0", "64-67" to "1.7",
                    "60-63" to "1.3", "<60" to "0.0"
                )
                numericRules.forEach { (range, gpa) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(range, style = MaterialTheme.typography.bodySmall)
                        Text(gpa, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("等级制（11级，英文/中文）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                val gradeRules = listOf(
                    Triple("A+ / 优+", "98", "4.3"),
                    Triple("A  / 优", "92", "4.0"),
                    Triple("A- / 优-", "87", "3.7"),
                    Triple("B+ / 良+", "83", "3.3"),
                    Triple("B  / 良", "79", "3.0"),
                    Triple("B- / 良-", "76", "2.7"),
                    Triple("C+ / 中+", "73", "2.3"),
                    Triple("C  / 中", "70", "2.0"),
                    Triple("C- / 中-", "66", "1.7"),
                    Triple("D  / 及格", "62", "1.3"),
                    Triple("F  / 不及格", "0", "0.0")
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("等级", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("分数", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                    Text("GPA", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                }
                gradeRules.forEach { (grade, score, gpa) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(grade, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        Text(score, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                        Text(gpa, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("GPA = Σ(课程GPA × 学分) / Σ学分",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("二等级制（通过/不通过）不参与 GPA 计算",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("优先使用服务器返回的绩点，否则按上述规则本地映射",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}

/** 将报表成绩转换为 ScoreItem（用于合并展示） */
private fun ReportedGrade.toScoreItem(): ScoreItem = ScoreItem(
    id = "report_${term}_${courseName.hashCode()}",
    termCode = term,
    courseName = courseName,
    score = score,
    scoreValue = score.toDoubleOrNull(),
    passFlag = gpa?.let { it > 0.0 } ?: (score.toDoubleOrNull()?.let { it >= 60.0 } ?: false),
    specificReason = null,
    coursePoint = coursePoint,
    examType = "",
    majorFlag = null,
    examProp = "",
    replaceFlag = false,
    gpa = gpa,
    source = ScoreSource.REPORT
)
