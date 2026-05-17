package com.xjtu.toolbox.jwapp

import androidx.activity.compose.BackHandler
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
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.SinkFeedback

import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.draw.clip
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
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.xjtu.toolbox.LocalAppLoginState
import com.xjtu.toolbox.Routes
import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.auth.LoginType
import com.xjtu.toolbox.auth.handleAuthExpired
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
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

@Composable
fun JwappScoreScreen(
    login: JwappLogin?,
    jwxtLogin: JwxtLogin? = null,
    studentId: String = "",
    onBack: () -> Unit
) {
    val appLoginState = LocalAppLoginState.current
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
    val showGpaTips = remember { mutableStateOf(false) }

    // 搜索 & 过滤
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedGroups by rememberSaveable { mutableStateOf<Set<CourseGroup>>(emptySet()) }

    // GPA 精度（点击循环 2→3→4→2）
    var gpaPrecision by rememberSaveable { mutableIntStateOf(2) }

    // 未评教课程名集合
    var unevaluatedCourses by remember { mutableStateOf<Set<String>>(emptySet()) }
    // 报表补充提示
    var reportHint by remember { mutableStateOf<String?>(null) }

    fun loadScoreData() {
        isLoading = true
        isRefreshing = false
        errorMessage = null
        scope.launch {
            // 先尝试从缓存秒显（Stale-While-Revalidate）
            val cacheKey = "score_all_terms"
            var cachedScoreCount = -1
            try {
                // 未登录态使用极长 TTL 以确保能加载缓存
                val ttl = if (api != null) com.xjtu.toolbox.util.DataCache.DEFAULT_TTL_MS else Long.MAX_VALUE
                val cached = dataCache.get(cacheKey, ttl)
                if (cached != null) {
                    val cachedGrades = gson.fromJson(cached, Array<TermScore>::class.java).toList()
                    if (cachedGrades.isNotEmpty()) {
                        allTermScores = cachedGrades
                        termList = cachedGrades.map { it.termCode to it.termName }
                        cachedScoreCount = cachedGrades.sumOf { it.scoreList.size }
                        isLoading = false  // 缓存已可用，主界面立即显示
                        isRefreshing = api != null  // 仅登录后才后台刷新
                        android.util.Log.d("ScoreUI", "Loaded from cache: $cachedScoreCount scores, hasApi=${api != null}")
                    }
                }
            } catch (_: Exception) { /* 缓存读取失败，正常加载 */ }

            // 未登录态 → 仅展示缓存
            if (api == null) {
                isRefreshing = false
                if (allTermScores.isEmpty()) {
                    errorMessage = "暂无成绩缓存"
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

                        // 课程号前缀分类（通核/通选）
                        for (i in grades.indices) {
                            val ts = grades[i]
                            val classified = ts.scoreList.map { score ->
                                val code = score.courseCode?.uppercase()
                                val group = when {
                                    code != null && code.startsWith("CORE") -> CourseGroup.GEN_CORE
                                    code != null && code.startsWith("GNED") -> CourseGroup.GEN_ELECTIVE
                                    else -> null
                                }
                                if (group != null) score.copy(courseGroup = group) else score
                            }
                            grades[i] = ts.copy(scoreList = classified)
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
            } catch (e: AuthExpiredException) {
                // [policy] JWAPP token 服务端拒绝时（reAuth 后重试也失败），
                // 不再立即 popBackStack 弹回主页（之前会和 markStaleAndRetry 形成"进-退-进"死循环）。
                // 改为停留当前页，展示缓存（若有）+ snackbar/errorMessage 引导用户。
                // 注意：login 缓存已被 reAuth 流程清掉，下次主动重试会走 full login 拿新 token。
                if (allTermScores.isNotEmpty()) {
                    scope.launch {
                        snackbarHostState.showSnackbar("成绩同步暂不可用，显示缓存数据。下拉刷新可重试", duration = SnackbarDuration.Long)
                    }
                } else {
                    errorMessage = "成绩查询服务暂不可用：${e.message ?: "请稍后重试"}"
                }
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

    // [修复] 监听 login 变化：onRetry 中 clearLogin+autoLogin(JWAPP) 重建 JwappLogin 后，
    // login 引用会变更，触发本 effect 用新 token 重新加载（避免 lambda 内 stale closure 问题）。
    LaunchedEffect(login) { loadScoreData() }

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

    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = if (gpaSelectMode) "选课算 GPA" else "成绩查询",
                largeTitle = if (gpaSelectMode) "选课算 GPA" else "成绩查询",
                scrollBehavior = scrollBehavior,
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
                    IconButton(onClick = { showGpaTips.value = true }) {
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
        // GPA 映射表弹窗
        GpaMappingDialog(show = showGpaTips)

        when {
            isLoading -> {
                LoadingState(message = "正在加载成绩数据...", modifier = Modifier.fillMaxSize().padding(padding))
            }

            errorMessage != null -> {
                ErrorState(
                    message = errorMessage!!,
                    onRetry = {
                        // [关键修复] 之前 onRetry 直接 loadScoreData，但 jwappLogin.authToken
                        // 已被上一次 reAuth-失败清空，loadScoreData → authenticatedRequest 立即抛
                        // RuntimeException("未登录") → 用户看到"加载失败: 未登录"，再点重试同样无效。
                        // 现在改为：先 clearLogin(JWAPP) + force autoLogin(JWAPP, interactive=true)
                        // 让状态机重建 JwappLogin（含必要的 MFA dialog），拿新 token 后再 loadScoreData。
                        scope.launch {
                            isLoading = true
                            errorMessage = null
                            try {
                                withContext(Dispatchers.IO) {
                                    appLoginState.clearLogin(LoginType.JWAPP)
                                    appLoginState.autoLogin(LoginType.JWAPP, force = true, interactive = true)
                                }
                            } catch (_: Exception) {}
                            // 不管 autoLogin 是否成功都 loadScoreData：
                            //   - 成功 → 拿新 token 重试业务请求
                            //   - 失败 → loadScoreData 内 catch 会显示更准确的错误（含缓存兜底）
                            loadScoreData()
                        }
                    },
                    modifier = Modifier.fillMaxSize().padding(padding)
                )
            }

            else -> {
                Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                    // 后台刷新时的细进度条
                    AnimatedVisibility(visible = isRefreshing) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            height = 2.dp
                        )
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection).overScrollVertical().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    // 免责声明
                    item {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                        ) {
                            Row(
                                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Info,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.6f)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "仅供快速预览，不用于保研/奖学金等正式场景，不具有效力，不保证准确性",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.6f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

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

                    // GPA 筛选模式（非选课模式时显示）
                    if (!gpaSelectMode && filteredScores.isNotEmpty()) {
                        item {
                            GpaModeBreakdown(
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
                        TextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            label = "搜索课程",
                            useLabelAsPlaceholder = true,
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
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.primaryVariant
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
                                // 通核/通选 chips
                                allCategories.forEach { group ->
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
                            }
                        }
                    }

                    if (filteredScores.isEmpty()) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                                Text("暂无成绩数据", style = MiuixTheme.textStyles.body1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            }
                        }
                    } else {
                        // 报表补充提示
                        if (reportHint != null) {
                            item {
                                top.yukonga.miuix.kmp.basic.Card(
                                    colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(Icons.Default.Info, null, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.size(18.dp))
                                        Text(reportHint!!, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
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
        animationSpec = spring(dampingRatio = 0.85f, stiffness = 500f),
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
        animationSpec = spring(dampingRatio = 0.85f, stiffness = 400f),
        label = "gpaRing"
    )
    val ringColor = when {
        gpa >= 4.0 -> MiuixTheme.colorScheme.primary
        gpa >= 3.0 -> MiuixTheme.colorScheme.primaryVariant
        gpa >= 2.0 -> MiuixTheme.colorScheme.primaryVariant.copy(alpha = 0.7f)
        else -> MiuixTheme.colorScheme.error
    }
    val trackColor = MiuixTheme.colorScheme.outline.copy(alpha = 0.3f)
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
        if (isSelectMode) MiuixTheme.colorScheme.secondaryContainer
        else MiuixTheme.colorScheme.surfaceVariant,
        animationSpec = spring(dampingRatio = 0.85f, stiffness = 500f),
        label = "gpaCardBg"
    )
    val textColor by androidx.compose.animation.animateColorAsState(
        if (isSelectMode) MiuixTheme.colorScheme.onSecondaryContainer
        else MiuixTheme.colorScheme.onSurface,
        animationSpec = spring(dampingRatio = 0.85f, stiffness = 500f),
        label = "gpaCardText"
    )
    val accentColor = textColor.copy(alpha = 0.12f)

    top.yukonga.miuix.kmp.basic.Card(
        modifier = Modifier.fillMaxWidth(),
        colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = containerColor)
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
                    style = MiuixTheme.textStyles.subtitle,
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
                            style = MiuixTheme.textStyles.footnote1,
                            color = textColor.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // 4 列统计 — GPA / 均分 / 课程 / 学分
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = top.yukonga.miuix.kmp.utils.SinkFeedback()
                    ) { onPrecisionToggle() },
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
        // 根据数字长度自适应字号，避免溢出遮盖
        val textStyle = when {
            value.length >= 7 -> MiuixTheme.textStyles.body2      // 83.4567
            value.length >= 6 -> MiuixTheme.textStyles.body1       // 3.1579
            value.length >= 5 -> MiuixTheme.textStyles.subtitle    // 3.158
            else -> MiuixTheme.textStyles.title4                   // 3.16
        }
        Text(
            value,
            style = textStyle,
            fontWeight = FontWeight.Bold,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            style = MiuixTheme.textStyles.footnote1,
            color = textColor.copy(alpha = 0.5f),
            maxLines = 1,
            letterSpacing = 0.5.sp
        )
    }
}

@Composable
fun TermSelector(termList: List<Pair<String, String>>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    val items = remember(termList) { termList.map { (_, name) -> DropdownItem(title = name) } }
    top.yukonga.miuix.kmp.basic.Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp
    ) {
        OverlaySpinnerPreference(
            items = items,
            selectedIndex = selectedIndex,
            title = "学期",
            onSelectedIndexChange = onSelect,
            modifier = Modifier.fillMaxWidth()
        )
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
        modifier = Modifier.fillMaxWidth().animateContentSize(animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)),
        onClick = onToggle,
        cornerRadius = 16.dp,
        pressFeedbackType = PressFeedbackType.Sink,
        colors = if (isFromReport) CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant)
                 else CardDefaults.defaultColors()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                // 选课模式复选框
                if (showCheckbox) {
                    Icon(
                        if (isSelected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                        contentDescription = null,
                        tint = if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(scoreItem.courseName, style = MiuixTheme.textStyles.subtitle, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (isFromReport) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MiuixTheme.colorScheme.primaryVariant,
                                contentColor = MiuixTheme.colorScheme.onPrimaryVariant
                            ) {
                                Text("报表", style = MiuixTheme.textStyles.footnote1, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                            }
                        }
                        if (isUnevaluated) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MiuixTheme.colorScheme.error,
                                contentColor = MiuixTheme.colorScheme.onError
                            ) {
                                Text("未评教", style = MiuixTheme.textStyles.footnote1, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${scoreItem.coursePoint}学分", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        // 课程分组 badge（通核/通选）
                        val group = scoreItem.courseGroup
                        if (group != null) {
                            val groupColor = when (group) {
                                CourseGroup.GEN_CORE -> MiuixTheme.colorScheme.primaryVariant
                                CourseGroup.GEN_ELECTIVE -> MiuixTheme.colorScheme.secondary
                            }
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = groupColor.copy(alpha = 0.12f)
                            ) {
                                Text(
                                    group.shortLabel,
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = groupColor,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        } else if (!scoreItem.majorFlag.isNullOrBlank()) {
                            val flagColor = when (scoreItem.majorFlag) {
                                "必修" -> MiuixTheme.colorScheme.primary
                                "选修" -> MiuixTheme.colorScheme.primaryVariant
                                else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                            }
                            Text(scoreItem.majorFlag, style = MiuixTheme.textStyles.footnote1, color = flagColor, fontWeight = FontWeight.Medium)
                        }
                        if (scoreItem.examProp.isNotEmpty() && scoreItem.examProp != "初修") {
                            Text(scoreItem.examProp, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.error)
                        }
                        if (scoreItem.examType.isNotEmpty()) {
                            Text(scoreItem.examType, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                        // 显示课程 GPA（服务器返回>0时使用，否则本地映射）
                        val courseGpa = (scoreItem.gpa?.takeIf { it > 0.0 })
                            ?: com.xjtu.toolbox.score.ScoreReportApi.scoreToGpa(scoreItem.score)
                        if (courseGpa != null) {
                            Text("GPA %.1f".format(courseGpa), style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
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
                        !reallyPassed -> MiuixTheme.colorScheme.error
                        scoreItem.scoreValue != null && scoreItem.scoreValue >= 90 -> MiuixTheme.colorScheme.primary
                        scoreItem.scoreValue != null && scoreItem.scoreValue >= 80 -> MiuixTheme.colorScheme.primaryVariant
                        scoreItem.scoreValue == null && reallyPassed -> MiuixTheme.colorScheme.primary // 等级制通过
                        else -> MiuixTheme.colorScheme.onSurface
                    }
                    Text(scoreItem.score, style = MiuixTheme.textStyles.headline1, fontWeight = FontWeight.Bold, color = scoreColor)
                    // 等级制显示精确分数（scoreValue 由 CjcxApi ZCJ 填充）
                    if (!reallyPassed) {
                        Text(scoreItem.specificReason ?: "未通过", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.error)
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
                            CircularProgressIndicator(size = 24.dp)
                        }
                    } else if (detail != null) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            DetailChip("绩点", "%.1f".format(detail.gpa))
                            DetailChip("学分", "%.1f".format(detail.coursePoint))
                            DetailChip("类型", detail.examType)
                            if (detail.majorFlag != null) DetailChip("性质", detail.majorFlag)
                        }
                        if (detail.itemList.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            Text("分项成绩", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(8.dp))
                            detail.itemList.forEach { item -> ScoreDetailRow(item) }
                        }
                    } else {
                        Text("无法加载详细成绩", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
fun ScoreDetailRow(item: ScoreDetailItem) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(item.itemName, style = MiuixTheme.textStyles.body2, modifier = Modifier.weight(1f))
        Text("%.0f%%".format(item.itemPercent * 100), style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.width(48.dp))
        if (item.itemScoreValue != null) {
            LinearProgressIndicator(
                progress = (item.itemScoreValue / 100.0).toFloat().coerceIn(0f, 1f),
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                height = 8.dp,
                colors = ProgressIndicatorDefaults.progressIndicatorColors(
                    backgroundColor = MiuixTheme.colorScheme.surfaceVariant
                ),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        Text(item.itemScore, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun DetailChip(label: String, value: String) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(value, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.primary)
            Text(label, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

@Composable
fun GpaMappingDialog(show: MutableState<Boolean>) {
    BackHandler(enabled = show.value) { show.value = false }
    OverlayBottomSheet(
        show = show.value,
        title = "GPA 映射规则",
        onDismissRequest = { show.value = false }
    ) {
            Column(
                modifier = Modifier.overScrollVertical().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("数字成绩 → GPA（4.3 绩点制）", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Bold)
                Text("依据：西交教〔2015〕87号 及 本科生学籍管理与学位授予规定（2017）", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                val numericRules = listOf(
                    "95-100" to "4.3", "90-94" to "4.0", "85-89" to "3.7",
                    "81-84" to "3.3", "78-80" to "3.0", "75-77" to "2.7",
                    "72-74" to "2.3", "68-71" to "2.0", "64-67" to "1.7",
                    "60-63" to "1.3", "<60" to "0.0"
                )
                numericRules.forEach { (range, gpa) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(range, style = MiuixTheme.textStyles.footnote1)
                        Text(gpa, style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Medium)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("等级制（11级，英文/中文）", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Bold)
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
                    Text("等级", style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("分数", style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                    Text("GPA", style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                }
                gradeRules.forEach { (grade, score, gpa) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(grade, style = MiuixTheme.textStyles.footnote1, modifier = Modifier.weight(1f))
                        Text(score, style = MiuixTheme.textStyles.footnote1, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                        Text(gpa, style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("GPA = Σ(课程GPA × 学分) / Σ学分",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Text("二等级制（通过/不通过）不参与 GPA 计算",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Text("优先使用 xscjcx.do 精确成绩（ZCJ/XFJD）",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { show.value = false },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("知道了")
        }
        Spacer(Modifier.height(16.dp))
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
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

/** 三种 GPA 模式概览卡片：全部 / 排除通选 / 排除所有通识 */
@Composable
fun GpaModeBreakdown(
    scores: List<ScoreItem>,
    calculateGpa: (List<ScoreItem>) -> GpaInfo?,
    precision: Int = 2
) {
    data class GpaMode(val label: String, val filter: (ScoreItem) -> Boolean)

    val modes = remember {
        listOf(
            GpaMode("所有课程") { true },
            GpaMode("排除通识选修") { it.courseGroup != CourseGroup.GEN_ELECTIVE },
            GpaMode("排除所有通识") { it.courseGroup != CourseGroup.GEN_CORE && it.courseGroup != CourseGroup.GEN_ELECTIVE }
        )
    }

    val results = remember(scores) {
        modes.mapNotNull { mode ->
            val filtered = scores.filter(mode.filter)
            val gpa = if (filtered.isNotEmpty()) calculateGpa(filtered) else null
            if (gpa != null) Triple(mode.label, gpa, filtered.size) else null
        }
    }

    if (results.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }

    top.yukonga.miuix.kmp.basic.Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = { expanded = !expanded },
        pressFeedbackType = PressFeedbackType.Sink,
        colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "分类别绩点",
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${results.size} 种统计",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }

            // 收起时显示摘要行
            if (!expanded) {
                Spacer(Modifier.height(8.dp))
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    results.forEach { (label, gpa, _) ->
                        Text(
                            "$label %.${precision}f".format(gpa.gpa),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.primary,
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
                        Text("统计范围", style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        Text("GPA", style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp), textAlign = TextAlign.End, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        Text("均分", style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp), textAlign = TextAlign.End, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        Text("学分", style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Bold, modifier = Modifier.width(48.dp), textAlign = TextAlign.End, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        Text("门数", style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Bold, modifier = Modifier.width(36.dp), textAlign = TextAlign.End, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                    HorizontalDivider(color = MiuixTheme.colorScheme.outline)
                    results.forEach { (label, gpa, count) ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(label, style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            Text("%.${precision}f".format(gpa.gpa), style = MiuixTheme.textStyles.footnote1, modifier = Modifier.width(56.dp), textAlign = TextAlign.End, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.primary)
                            Text(if (gpa.averageScore > 0) "%.${precision}f".format(gpa.averageScore) else "—", style = MiuixTheme.textStyles.footnote1, modifier = Modifier.width(56.dp), textAlign = TextAlign.End)
                            Text("%.1f".format(gpa.totalCredits), style = MiuixTheme.textStyles.footnote1, modifier = Modifier.width(48.dp), textAlign = TextAlign.End)
                            Text("$count", style = MiuixTheme.textStyles.footnote1, modifier = Modifier.width(36.dp), textAlign = TextAlign.End)
                        }
                    }
                }
            }
        }
    }
}