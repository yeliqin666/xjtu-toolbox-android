package com.xjtu.toolbox.jwapp

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import com.xjtu.toolbox.auth.JwappLogin
import com.xjtu.toolbox.auth.JwxtLogin
import com.xjtu.toolbox.score.ScoreReportApi
import com.xjtu.toolbox.score.ReportedGrade
import com.xjtu.toolbox.judge.JudgeApi
import com.xjtu.toolbox.ui.components.AppFilterChip
import com.xjtu.toolbox.ui.components.LoadingState
import com.xjtu.toolbox.ui.components.ErrorState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JwappScoreScreen(
    login: JwappLogin?,
    jwxtLogin: JwxtLogin? = null,
    studentId: String = "",
    onBack: () -> Unit
) {
    val api = remember(login) { login?.let { JwappApi(it) } }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val dataCache = remember { com.xjtu.toolbox.util.DataCache(context) }
    val gson = remember { com.google.gson.Gson() }
    val snackbarHostState = remember { SnackbarHostState() }

    var isLoading by remember { mutableStateOf(true) }
    var isRefreshing by remember { mutableStateOf(false) }  // 缓存已显示，后台刷新中
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var allTermScores by remember { mutableStateOf<List<TermScore>>(emptyList()) }
    var termList by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var selectedTermIndex by rememberSaveable { mutableIntStateOf(0) }
    var currentTermName by remember { mutableStateOf("") }
    var expandedCourseId by rememberSaveable { mutableStateOf<String?>(null) }
    var courseDetails by remember { mutableStateOf<Map<String, ScoreDetail>>(emptyMap()) }
    var detailLoading by remember { mutableStateOf<String?>(null) }

    // GPA 选课计算模式
    var gpaSelectMode by rememberSaveable { mutableStateOf(false) }
    var selectedCourseIds by rememberSaveable { mutableStateOf<Set<String>>(emptySet()) }
    var showGpaTips by remember { mutableStateOf(false) }

    // 搜索 & 过滤
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedGroups by rememberSaveable { mutableStateOf<Set<CourseGroup>>(emptySet()) }

    // GPA 精度（点击循环 2→3→4→2）
    var gpaPrecision by rememberSaveable { mutableIntStateOf(2) }

    // 未评教课程名集合
    var unevaluatedCourses by remember { mutableStateOf<Set<String>>(emptySet()) }
    // 报表补充提示
    var reportHint by remember { mutableStateOf<String?>(null) }
    // 培养方案匹配状态
    var planHint by remember { mutableStateOf<String?>(null) }
    var planHintIsError by remember { mutableStateOf(false) }

    fun loadScoreData() {
        isLoading = true
        isRefreshing = false
        errorMessage = null
        scope.launch {
            // 先尝试从缓存秒显（Stale-While-Revalidate）
            val cacheKey = "score_all_terms"
            var cachedScoreCount = -1
            try {
                // 离线模式使用极长 TTL 以确保能加载缓存
                val ttl = if (api != null) com.xjtu.toolbox.util.DataCache.DEFAULT_TTL_MS else Long.MAX_VALUE
                val cached = dataCache.get(cacheKey, ttl)
                if (cached != null) {
                    val cachedGrades = gson.fromJson(cached, Array<TermScore>::class.java).toList()
                    if (cachedGrades.isNotEmpty()) {
                        allTermScores = cachedGrades
                        termList = cachedGrades.map { it.termCode to it.termName }
                        cachedScoreCount = cachedGrades.sumOf { it.scoreList.size }
                        isLoading = false  // 缓存已可用，主界面立即显示
                        isRefreshing = api != null  // 仅在线时后台刷新
                        android.util.Log.d("ScoreUI", "Loaded from cache: $cachedScoreCount scores, offline=${api == null}")
                    }
                }
            } catch (_: Exception) { /* 缓存读取失败，正常加载 */ }

            // 离线模式 → 只读缓存，不发网络请求
            if (api == null) {
                isRefreshing = false
                if (allTermScores.isEmpty()) {
                    errorMessage = "离线模式下无缓存数据，请联网后查看"
                } else {
                    scope.launch { snackbarHostState.showSnackbar("当前无网络，显示缓存成绩", duration = SnackbarDuration.Short) }
                }
                isLoading = false
                return@launch
            }

            try {
                withContext(Dispatchers.IO) {
                    val basis = api.getTimeTableBasis()
                    currentTermName = basis.termName
                    val grades = api.getGrade(null).toMutableList()

                    // CjcxApi 精确化：ZCJ/XFJD 替换 JWAPP 数据
                    if (jwxtLogin != null) {
                        try {
                            val cjcxApi = CjcxApi(jwxtLogin)
                            val preciseScores = cjcxApi.getAllScores()
                            val lookup = cjcxApi.buildLookup(preciseScores)
                            val preciseByKch = preciseScores.associateBy { it.kch }

                            var matchCount = 0
                            for (i in grades.indices) {
                                val ts = grades[i]
                                val enrichedList = ts.scoreList.map { score ->
                                    val key = "${ts.termCode}|${CjcxApi.normalizeName(score.courseName)}"
                                    val precise = lookup[key]
                                        ?: score.courseCode?.let { preciseByKch[it] }
                                    if (precise != null) {
                                        matchCount++
                                        score.copy(
                                            scoreValue = precise.zcj,
                                            gpa = precise.xfjd,
                                            courseCategory = precise.kclbdm.ifBlank { null },
                                            courseCode = precise.kch.ifBlank { score.courseCode }
                                        )
                                    } else {
                                        android.util.Log.w("Score", "xscjcx.do 未匹配: ${score.courseName} (code=${score.courseCode}, key=$key)")
                                        score
                                    }
                                }
                                grades[i] = ts.copy(scoreList = enrichedList)
                            }
                            val totalScores = grades.sumOf { it.scoreList.size }
                            android.util.Log.d("Score", "CjcxApi: $matchCount/$totalScores 匹配")
                        } catch (e: Exception) {
                            android.util.Log.w("Score", "CjcxApi 失败(fallback JWAPP): ${e.message}")
                        }

                        // 培养方案感知分类
                        try {
                            val pyfaApi = PyfaApi(jwxtLogin)
                            val summary = pyfaApi.getPersonalPlan()
                            val planCourses = pyfaApi.getPlanCourses(summary.pyfadm)
                            val groupTree = pyfaApi.getCourseGroups(summary.pyfadm)

                            val nodeMap = flattenGroupTree(groupTree)
                            val planCourseByKch = planCourses.associateBy { it.kch }
                            val planCourseNames = planCourses.map { CjcxApi.normalizeName(it.name) }.toSet()
                            val openGroupNodes = nodeMap.values.filter {
                                it.courseCount == 0 && it.children.isEmpty()
                            }

                            val allScores = grades.flatMap { it.scoreList }
                            val classified = classifyCoursesWithCaps(
                                allScores, planCourseByKch, planCourseNames,
                                nodeMap, openGroupNodes
                            )
                            // 回填分类结果
                            var offset = 0
                            for (i in grades.indices) {
                                val ts = grades[i]
                                val newList = classified.subList(offset, offset + ts.scoreList.size)
                                grades[i] = ts.copy(scoreList = newList)
                                offset += ts.scoreList.size
                            }
                            android.util.Log.d("Score", "分类完成: ${planCourseNames.size}门方案课, ${openGroupNodes.size}个开放组")
                            // 设置培养方案匹配成功提示
                            planHint = "已匹配「${summary.pyfamc}」"
                            planHintIsError = false
                        } catch (e: Exception) {
                            android.util.Log.w("Score", "分类失败: ${e.message}")
                            // 设置培养方案匹配失败提示
                            planHint = "培养方案匹配失败: ${e.message ?: "未知错误"}"
                            planHintIsError = true
                        }
                    }

                    // 课程名标准化（空格/全角/符号统一）
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

                    val existingKeys = grades.flatMap { ts ->
                        ts.scoreList.map { normalizeKey(ts.termCode, it.courseName) }
                    }.toMutableSet()

                    // 报表补充未评教课程
                    if (jwxtLogin != null && studentId.isNotEmpty()) {
                        var unevalSet = emptySet<String>()
                        try {
                            val judgeApi = JudgeApi(jwxtLogin)
                            val unfinished = judgeApi.unfinishedQuestionnaires()
                            unevalSet = unfinished.map { it.KCM }.toSet()
                            unevaluatedCourses = unevalSet
                        } catch (e: Exception) {
                            android.util.Log.w("Score", "未评教查询失败: ${e.message}")
                        }

                        if (unevalSet.isNotEmpty()) {
                            try {
                                val reportGrades = ScoreReportApi(jwxtLogin).getReportedGrade(studentId)
                                val supplementByTerm = mutableMapOf<String, MutableList<ScoreItem>>()
                                for (rg in reportGrades) {
                                    if (rg.courseName !in unevalSet) continue
                                    val key = normalizeKey(rg.term, rg.courseName)
                                    if (key !in existingKeys) {
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
                            // 无未评教课程，跳过报表补充
                        }
                    }

                    allTermScores = grades
                    termList = grades.map { it.termCode to it.termName }

                    // 写缓存（加工后的完成品）
                    try { dataCache.put(cacheKey, gson.toJson(grades)) } catch (_: Exception) {}

                    // 检测是否有新成绩
                    val freshScoreCount = grades.sumOf { it.scoreList.size }
                    if (cachedScoreCount >= 0 && freshScoreCount > cachedScoreCount) {
                        val newCount = freshScoreCount - cachedScoreCount
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                "有 $newCount 门新成绩",
                                duration = SnackbarDuration.Short
                            )
                        }
                    } else if (cachedScoreCount >= 0 && freshScoreCount != cachedScoreCount) {
                        scope.launch {
                            snackbarHostState.showSnackbar("成绩数据已更新", duration = SnackbarDuration.Short)
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // 网络失败但有缓存 → 不报错，提示数据可能不是最新
                if (allTermScores.isNotEmpty()) {
                    scope.launch {
                        snackbarHostState.showSnackbar("网络异常，显示的可能不是最新数据", duration = SnackbarDuration.Long)
                    }
                } else {
                    errorMessage = "加载失败: ${e.message}"
                }
            } finally {
                isLoading = false
                isRefreshing = false
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

    // 所有出现的课程分组
    val allCategories = remember(allTermScores) {
        allTermScores.flatMap { it.scoreList }
            .mapNotNull { it.courseGroup }
            .distinct()
            .sortedBy { it.ordinal }
    }

    // 搜索 + 分组筛选
    val filteredScores = remember(currentTermScores, searchQuery, selectedGroups) {
        currentTermScores.filter { score ->
            val group = score.courseGroup
            (searchQuery.isBlank() || score.courseName.contains(searchQuery, ignoreCase = true)) &&
            (selectedGroups.isEmpty() || group in selectedGroups)
        }
    }

    // 当前筛选范围 GPA
    val displayGpaInfo = remember(filteredScores) {
        if (filteredScores.isNotEmpty()) {
            api?.calculateGpaForCourses(filteredScores)
        } else null
    }

    val selectedGpaInfo = remember(selectedCourseIds, allTermScores) {
        if (selectedCourseIds.isNotEmpty()) {
            val selected = allTermScores.flatMap { it.scoreList }.filter { it.id in selectedCourseIds }
            api?.calculateGpaForCourses(selected)
        } else null
    }

    // GPA 映射表弹窗
    if (showGpaTips) {
        GpaMappingDialog(onDismiss = { showGpaTips = false })
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                        // 全选/取消当前筛选范围内的课程
                        IconButton(onClick = {
                            val filteredIds = filteredScores.map { it.id }.toSet()
                            val allFilteredSelected = filteredIds.isNotEmpty() && filteredIds.all { it in selectedCourseIds }
                            selectedCourseIds = if (allFilteredSelected) selectedCourseIds - filteredIds else selectedCourseIds + filteredIds
                        }) {
                            Icon(Icons.Default.SelectAll, contentDescription = "全选当前")
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
                Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                    // 后台刷新时的细进度条
                    AnimatedVisibility(visible = isRefreshing) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(2.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    // GPA 卡片
                    item {
                        GpaCard(
                            gpaInfo = if (gpaSelectMode) selectedGpaInfo else displayGpaInfo,
                            termName = currentTermName,
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

                    // 培养方案匹配提示条（显示在成绩概览下方、分类绩点上方）
                    if (planHint != null) {
                        item {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = if (planHintIsError)
                                        MaterialTheme.colorScheme.errorContainer
                                    else
                                        MaterialTheme.colorScheme.secondaryContainer
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        if (planHintIsError) Icons.Default.Warning else Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = if (planHintIsError)
                                            MaterialTheme.colorScheme.onErrorContainer
                                        else
                                            MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        planHint!!,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (planHintIsError)
                                            MaterialTheme.colorScheme.onErrorContainer
                                        else
                                            MaterialTheme.colorScheme.onSecondaryContainer,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    // 分类别 GPA 概览（非选课模式下显示）
                    if (!gpaSelectMode && filteredScores.any { it.courseCategory != null }) {
                        item {
                            CategoryGpaBreakdown(
                                scores = filteredScores,
                                calculateGpa = { api?.calculateGpaForCourses(it) },
                                precision = gpaPrecision
                            )
                        }
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

                    // 类别筛选 chips
                    if (allCategories.isNotEmpty()) {
                        item {
                            if (gpaSelectMode) {
                                Text(
                                    "筛选类别 → 全选按钮批量勾选",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                            @OptIn(ExperimentalLayoutApi::class)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                // "全部" chip
                                AppFilterChip(
                                    selected = selectedGroups.isEmpty(),
                                    onClick = { selectedGroups = emptySet() },
                                    label = "全部"
                                )
                                // 方案内类别 chips
                                allCategories.filter { it != CourseGroup.OUT_OF_PLAN }.forEach { group ->
                                    val isSelected = group in selectedGroups
                                    AppFilterChip(
                                        selected = isSelected,
                                        onClick = {
                                            selectedGroups = if (isSelected)
                                                (selectedGroups - group).let { if (it.isEmpty()) emptySet() else it }
                                            else
                                                selectedGroups + group
                                        },
                                        label = group.label
                                    )
                                }
                                // "方案外" chip
                                if (CourseGroup.OUT_OF_PLAN in allCategories) {
                                    AppFilterChip(
                                        selected = CourseGroup.OUT_OF_PLAN in selectedGroups,
                                        onClick = {
                                            selectedGroups = if (CourseGroup.OUT_OF_PLAN in selectedGroups)
                                                (selectedGroups - CourseGroup.OUT_OF_PLAN).let { if (it.isEmpty()) emptySet() else it }
                                            else
                                                selectedGroups + CourseGroup.OUT_OF_PLAN
                                        },
                                        label = "方案外"
                                    )
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
                                                        val d = withContext(Dispatchers.IO) { api?.getDetail(scoreItem.id) }
                                                        if (d != null) courseDetails = courseDetails + (scoreItem.id to d)
                                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                                        throw e
                                                    } catch (e: Exception) {
                                                        scope.launch {
                                                            snackbarHostState.showSnackbar(
                                                                "加载分项成绩失败，请重试",
                                                                duration = SnackbarDuration.Short
                                                            )
                                                        }
                                                    } finally { detailLoading = null }
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
                }  // Column
            }
        }
    }
}

@Composable
private fun AnimatedNumber(value: Double, precision: Int, style: androidx.compose.ui.text.TextStyle, color: androidx.compose.ui.graphics.Color, fontWeight: FontWeight = FontWeight.Bold) {
    val animatedValue by animateFloatAsState(
        targetValue = value.toFloat(),
        animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
        label = "gpaNum"
    )
    Text(
        text = "%.${precision}f".format(animatedValue),
        style = style,
        fontWeight = fontWeight,
        color = color,
        maxLines = 1
    )
}

@Composable
private fun GpaRingIndicator(gpa: Double, modifier: Modifier = Modifier) {
    val maxGpa = 4.3
    val animatedProgress by animateFloatAsState(
        targetValue = (gpa / maxGpa).toFloat().coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
        label = "gpaRing"
    )
    val ringColor = when {
        gpa >= 4.0 -> androidx.compose.ui.graphics.Color(0xFF4CAF50)
        gpa >= 3.0 -> androidx.compose.ui.graphics.Color(0xFF2196F3)
        gpa >= 2.0 -> androidx.compose.ui.graphics.Color(0xFFFF9800)
        else -> androidx.compose.ui.graphics.Color(0xFFF44336)
    }
    val trackColor = androidx.compose.ui.graphics.Color.LightGray.copy(alpha = 0.3f)
    val gpaFormatted = "%.2f".format(gpa)
    Canvas(modifier = modifier.semantics { contentDescription = "GPA $gpaFormatted" }) {
        val stroke = 8.dp.toPx()
        val inset = stroke / 2
        val rectSize = Size(size.width - stroke, size.height - stroke)
        drawArc(
            color = trackColor, startAngle = -90f, sweepAngle = 360f,
            useCenter = false, style = Stroke(stroke, cap = StrokeCap.Round),
            topLeft = Offset(inset, inset), size = rectSize
        )
        drawArc(
            color = ringColor, startAngle = -90f, sweepAngle = 360f * animatedProgress,
            useCenter = false, style = Stroke(stroke, cap = StrokeCap.Round),
            topLeft = Offset(inset, inset), size = rectSize
        )
    }
}

@Composable
fun GpaCard(gpaInfo: GpaInfo?, termName: String, totalCourses: Int, totalCredits: Double, isSelectMode: Boolean, precision: Int = 2, onPrecisionToggle: () -> Unit = {}) {
    val containerColor by androidx.compose.animation.animateColorAsState(
        if (isSelectMode) MaterialTheme.colorScheme.tertiaryContainer
        else MaterialTheme.colorScheme.primaryContainer,
        label = "gpaCardBg"
    )
    val textColor by androidx.compose.animation.animateColorAsState(
        if (isSelectMode) MaterialTheme.colorScheme.onTertiaryContainer
        else MaterialTheme.colorScheme.onPrimaryContainer,
        label = "gpaCardText"
    )
    val accentColor = textColor.copy(alpha = 0.12f)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            // 标题行
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (isSelectMode) "选课均分" else "成绩概览",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = textColor
                )
                val chipText = when {
                    isSelectMode -> "已选 $totalCourses 门"
                    termName.isNotEmpty() -> termName
                    else -> null
                }
                if (chipText != null) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = accentColor,
                        border = BorderStroke(0.5.dp, textColor.copy(alpha = 0.15f))
                    ) {
                        Text(
                            chipText,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = textColor.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // 4 列数据（GPA + 均分 + 课程 + 学分）
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onPrecisionToggle() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                GpaStatColumn(
                    value = if (gpaInfo != null) "%.${precision}f".format(gpaInfo.gpa) else "—",
                    label = "GPA",
                    textColor = textColor,
                    modifier = Modifier.weight(1f)
                )
                Box(Modifier.width(1.dp).height(28.dp).background(accentColor))
                GpaStatColumn(
                    value = if (gpaInfo != null && gpaInfo.averageScore > 0)
                        "%.${precision}f".format(gpaInfo.averageScore) else "—",
                    label = "均分",
                    textColor = textColor,
                    modifier = Modifier.weight(1f)
                )
                Box(Modifier.width(1.dp).height(28.dp).background(accentColor))
                GpaStatColumn(
                    value = "${gpaInfo?.courseCount ?: totalCourses}",
                    label = "课程",
                    textColor = textColor,
                    modifier = Modifier.weight(1f)
                )
                Box(Modifier.width(1.dp).height(28.dp).background(accentColor))
                GpaStatColumn(
                    value = "%.1f".format(gpaInfo?.totalCredits ?: totalCredits),
                    label = "学分",
                    textColor = textColor,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun GpaStatColumn(
    value: String,
    label: String,
    textColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = textColor,
            maxLines = 1
        )
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = textColor.copy(alpha = 0.5f),
            maxLines = 1,
            letterSpacing = 0.5.sp
        )
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
                        // 课程分组 badge
                        val group = scoreItem.courseGroup
                        if (group != null && group != CourseGroup.OUT_OF_PLAN) {
                            val groupColor = when (group) {
                                CourseGroup.CORE -> MaterialTheme.colorScheme.primary
                                CourseGroup.GEN_CORE -> MaterialTheme.colorScheme.tertiary
                                CourseGroup.GEN_ELECTIVE -> MaterialTheme.colorScheme.secondary
                                CourseGroup.MAJOR_ELECTIVE -> MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                                CourseGroup.OUT_OF_PLAN -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = groupColor.copy(alpha = 0.12f)
                            ) {
                                Text(
                                    group.shortLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = groupColor,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        } else if (!scoreItem.majorFlag.isNullOrBlank()) {
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
                    // 通过判断（passFlag 对等级制可能不准，需 GPA/分数兜底）
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
                    // 等级制显示精确分数（scoreValue 由 CjcxApi ZCJ 填充）
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
                Text("优先使用 xscjcx.do 精确成绩（ZCJ/XFJD）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}

/** 报表成绩转 ScoreItem */
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

/** 分类别 GPA 概览卡片 */
@Composable
fun CategoryGpaBreakdown(
    scores: List<ScoreItem>,
    calculateGpa: (List<ScoreItem>) -> GpaInfo?,
    precision: Int = 2
) {
    var expanded by remember { mutableStateOf(false) }

    // 按 CourseGroup 分组
    val groupedGpa = remember(scores) {
        scores.groupBy { it.courseGroup ?: CourseGroup.OUT_OF_PLAN }
            .mapNotNull { (group, items) ->
                val gpa = calculateGpa(items)
                if (gpa != null) Triple(group, gpa, items.size) else null
            }
            .sortedBy { it.first.ordinal }
    }

    if (groupedGpa.isEmpty()) return

    Card(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "分类别绩点",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${groupedGpa.size} 个类别",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 默认收起时显示摘要行
            if (!expanded) {
                Spacer(Modifier.height(8.dp))
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    groupedGpa.forEach { (group, gpa, _) ->
                        Text(
                            "${group.shortLabel} %.${precision}f".format(gpa.gpa),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // 展开时显示详细表格
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // 表头
                    Row(Modifier.fillMaxWidth()) {
                        Text("类别", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("GPA", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp), textAlign = TextAlign.End, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("均分", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp), textAlign = TextAlign.End, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("学分", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.width(48.dp), textAlign = TextAlign.End, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("门数", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.width(36.dp), textAlign = TextAlign.End, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    groupedGpa.forEach { (group, gpa, count) ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(group.label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            Text("%.${precision}f".format(gpa.gpa), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(56.dp), textAlign = TextAlign.End, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Text(if (gpa.averageScore > 0) "%.${precision}f".format(gpa.averageScore) else "—", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(56.dp), textAlign = TextAlign.End)
                            Text("%.1f".format(gpa.totalCredits), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(48.dp), textAlign = TextAlign.End)
                            Text("$count", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(36.dp), textAlign = TextAlign.End)
                        }
                    }
                }
            }
        }
    }
}
