package com.xjtu.toolbox.jwapp

import androidx.lifecycle.viewmodel.compose.viewModel
import com.xjtu.toolbox.ui.adaptive.AdaptiveRowGrid
import com.xjtu.toolbox.ui.adaptive.fullLineItem
import androidx.compose.foundation.lazy.grid.itemsIndexed
import com.xjtu.toolbox.ui.components.enterOnce
import androidx.activity.compose.BackHandler
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
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
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.SinkFeedback

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SelectAll
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.xjtu.toolbox.auth.LocalAppLoginState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.ui.components.AppCardColor
import com.xjtu.toolbox.ui.components.AppFilterChip
import com.xjtu.toolbox.ui.components.AppSearchBar
import com.xjtu.toolbox.ui.components.LoadingState
import com.xjtu.toolbox.ui.components.ErrorState
import com.xjtu.toolbox.ui.glass.*

@Composable
fun JwappScoreScreen(
    site: SiteSession?,
    jwxtSite: SiteSession? = null,
    studentId: String = "",
    onBack: () -> Unit,
    onOpenReport: () -> Unit = {}
) {
    val appLoginState = LocalAppLoginState.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val vm: JwappScoreViewModel = viewModel(key = "score-${appLoginState.accountId}-${System.identityHashCode(site)}") {
        JwappScoreViewModel(context, appLoginState.accountId, site, jwxtSite, studentId, appLoginState)
    }
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(vm) {
        vm.messages.collect {
            snackbarHostState.showSnackbar(it.text, duration = if (it.long) SnackbarDuration.Long else SnackbarDuration.Short)
        }
    }
    val allTermScores = vm.allTermScores
    val termList = vm.termList

    var selectedTermIndex by rememberSaveable { mutableIntStateOf(0) }
    var expandedCourseId by rememberSaveable { mutableStateOf<String?>(null) }

    // GPA 选课计算模式
    var gpaSelectMode by rememberSaveable { mutableStateOf(false) }
    var selectedCourseIds by rememberSaveable { mutableStateOf<Set<String>>(emptySet()) }
    val showGpaTips = remember { mutableStateOf(false) }

    // 搜索 & 过滤
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedGroups by rememberSaveable { mutableStateOf<Set<CourseGroup>>(emptySet()) }

    // GPA 精度（点击循环 2→3→4→2）
    var gpaPrecision by rememberSaveable { mutableIntStateOf(2) }

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
            com.xjtu.toolbox.score.ScoreCalculator.calculateGpaForCourses(filteredScores)
        } else null
    }

    val selectedGpaInfo = remember(selectedCourseIds, allTermScores) {
        if (selectedCourseIds.isNotEmpty()) {
            val selected = allTermScores.flatMap { it.scoreList }.filter { it.id in selectedCourseIds }
            com.xjtu.toolbox.score.ScoreCalculator.calculateGpaForCourses(selected)
        } else null
    }

    val filterTermLabel = when {
        selectedTermIndex == 0 -> "所有学期"
        else -> {
            val pair = termList.getOrNull(selectedTermIndex - 1)
            pair?.second?.ifBlank { pair.first }.orEmpty()
        }
    }
    val groupedTerms = remember(filteredScores, termList, selectedTermIndex) {
        val byCode = filteredScores.groupBy { it.termCode }
        if (selectedTermIndex == 0) {
            val ordered = termList.mapNotNull { (code, name) ->
                byCode[code]?.let { Triple(code, name, it) }
            }
            val known = termList.map { it.first }.toSet()
            ordered + byCode.keys.filter { it !in known }.map { code ->
                Triple(code, code, byCode.getValue(code))
            }
        } else {
            val (code, name) = termList.getOrNull(selectedTermIndex - 1) ?: ("" to filterTermLabel)
            if (filteredScores.isEmpty()) emptyList()
            else listOf(Triple(code, name, filteredScores))
        }
    }

    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val pullToRefreshState = rememberPullToRefreshState()
    var termMenuExpanded by remember { mutableStateOf(false) }
    // 玻璃顶栏（经典风格下为 null，一切照旧），用法见 ui/glass/GlassTopBar.kt
    val glass = rememberPageGlass()
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = if (gpaSelectMode) "选课算 GPA" else "成绩查询",
                largeTitle = if (gpaSelectMode) "选课算 GPA" else "成绩查询",
                color = glassBarColor(glass),
                modifier = Modifier.glassTopBar(glass),
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
                    // 成绩报表（FR 报表，含加权 GPA）——此前无入口，补回右上角
                    IconButton(onClick = onOpenReport) {
                        Icon(Icons.Default.Assessment, contentDescription = "成绩报表")
                    }
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

        // 内容铺到顶栏下面，顶部留白放进各个列表里；下拉指示器也从顶栏下面出来
        val glassTop = padding.glassTop(glass)
        when {
            vm.isLoading -> {
                LazyColumn(
                    Modifier.fillMaxSize().padding(padding.withoutTop(glass)).glassSource(glass),
                    contentPadding = PaddingValues(top = glassTop),
                ) {
                    item {
                        Box(Modifier.fillParentMaxSize()) {
                            LoadingState(message = "正在加载成绩数据...", modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }

            vm.errorMessage != null -> {
                LazyColumn(
                    Modifier.fillMaxSize().padding(padding.withoutTop(glass)).glassSource(glass),
                    contentPadding = PaddingValues(top = glassTop),
                ) {
                    item {
                        Box(Modifier.fillParentMaxSize()) {
                            ErrorState(
                                message = vm.errorMessage.orEmpty(),
                                onRetry = vm::retry,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }

            else -> {
                PullToRefresh(
                    refreshTexts = com.xjtu.toolbox.ui.components.AppRefreshTexts,
                    isRefreshing = vm.isRefreshing,
                    onRefresh = { if (vm.canRefresh) vm.load(silent = true) },
                    pullToRefreshState = pullToRefreshState,
                    topAppBarScrollBehavior = scrollBehavior,
                    contentPadding = PaddingValues(top = glassTop),
                    modifier = Modifier.fillMaxSize().padding(padding.withoutTop(glass)).glassSource(glass)
                ) {
                    // 宽屏：GPA、筛选、学期标题横跨全宽，一门门课分两三列排（见 AdaptiveRowGrid：卡片会竖着展开，按行对齐，展开一张别的卡不挪位置）。
                    // 以前整页限宽 720 居中，平板横屏两边各空一大块。
                    AdaptiveRowGrid(
                        modifier = Modifier.fillMaxSize().overScrollVertical(),
                    spacing = 10.dp,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = glassTop + 8.dp, bottom = 8.dp)
                ) {
                    // 整页依次登场：GPA 卡 → 筛选 → 成绩列表第一屏
                    fullLineItem {
                      Box(Modifier.enterOnce(0)) {
                        GpaCard(
                            gpaInfo = if (gpaSelectMode) selectedGpaInfo else displayGpaInfo,
                            totalCourses = if (gpaSelectMode) selectedCourseIds.size else filteredScores.size,
                            totalCredits = if (gpaSelectMode) {
                                allTermScores.flatMap { it.scoreList }
                                    .filter { it.id in selectedCourseIds }
                                    .sumOf { it.coursePoint }
                            } else filteredScores.sumOf { it.coursePoint },
                            isSelectMode = gpaSelectMode,
                            precision = gpaPrecision,
                            onPrecisionToggle = { gpaPrecision = if (gpaPrecision >= 4) 2 else gpaPrecision + 1 }
                        ) {
                            if (!gpaSelectMode && filteredScores.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                GpaModeBreakdown(
                                    scores = filteredScores,
                                    calculateGpa = { com.xjtu.toolbox.score.ScoreCalculator.calculateGpaForCourses(it) },
                                    precision = gpaPrecision,
                                    embedded = true,
                                )
                            }
                        }
                      }
                    }

                    fullLineItem {
                        Column(Modifier.enterOnce(1)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (termList.isNotEmpty()) {
                                    val termLabel = if (selectedTermIndex == 0) "全部学期"
                                    else termList.getOrNull(selectedTermIndex - 1)?.second ?: "学期"
                                    Box {
                                        Row(
                                            Modifier
                                                .clip(RoundedCornerShape(12.dp))
                                                .clickable { termMenuExpanded = true }
                                                .padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                termLabel,
                                                style = MiuixTheme.textStyles.body2,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.widthIn(max = 120.dp)
                                            )
                                            Icon(
                                                Icons.Default.ExpandMore,
                                                contentDescription = "选择学期",
                                                modifier = Modifier.size(18.dp),
                                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                            )
                                        }
                                        val termChoices = listOf("全部学期") + termList.map { it.second }
                                        OverlayListPopup(
                                            show = termMenuExpanded,
                                            alignment = PopupPositionProvider.Align.Start,
                                            onDismissRequest = { termMenuExpanded = false }
                                        ) {
                                            ListPopupColumn {
                                                termChoices.forEachIndexed { idx, name ->
                                                    DropdownImpl(
                                                        text = name,
                                                        optionSize = termChoices.size,
                                                        isSelected = idx == selectedTermIndex,
                                                        onSelectedIndexChange = {
                                                            selectedTermIndex = it
                                                            expandedCourseId = null
                                                            termMenuExpanded = false
                                                        },
                                                        index = idx
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                AppSearchBar(
                                    query = searchQuery,
                                    onQueryChange = { searchQuery = it },
                                    label = "搜索课程",
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (allCategories.isNotEmpty()) {
                                if (gpaSelectMode) {
                                    Text(
                                        "筛选后可用右上角全选",
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        modifier = Modifier.padding(top = 6.dp)
                                    )
                                }
                                @OptIn(ExperimentalLayoutApi::class)
                                FlowRow(
                                    modifier = Modifier.padding(top = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    AppFilterChip(
                                        selected = selectedGroups.isEmpty(),
                                        onClick = { selectedGroups = emptySet() },
                                        label = "全部"
                                    )
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
                            if (vm.reportHint != null) {
                                Text(
                                    vm.reportHint.orEmpty(),
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                            }
                        }
                    }

                    if (filteredScores.isEmpty()) {
                        fullLineItem {
                            Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                                Text("暂无成绩数据", style = MiuixTheme.textStyles.body1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            }
                        }
                    } else {
                        groupedTerms.forEach { (termCode, termName, termScores) ->
                            if (selectedTermIndex == 0 || groupedTerms.size > 1) {
                                fullLineItem(key = "term_$termCode") {
                                    Text(
                                        "$termName · ${termScores.size} 门",
                                        style = MiuixTheme.textStyles.body2,
                                        fontWeight = FontWeight.Medium,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                                    )
                                }
                            }
                            itemsIndexed(termScores, key = { _, it -> "${termCode}_${it.id}" }) { rowIndex, scoreItem ->
                                            val isFromReport = scoreItem.source == ScoreSource.REPORT
                                            val isUnevaluated = scoreItem.courseName in vm.unevaluatedCourses
                                            val isExpanded = expandedCourseId == scoreItem.id
                                            val detail = vm.courseDetails[scoreItem.id]
                                            val isDetailLoading = vm.detailLoading == scoreItem.id
                                            val isSelected = scoreItem.id in selectedCourseIds
                                            ScoreRow(
                                                // 第一屏错峰淡入，后面的直接就位
                                                modifier = Modifier.enterOnce(rowIndex + 2),
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
                                                            vm.loadDetail(scoreItem)
                                                        }
                                                    }
                                                }
                                            )
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

@Composable
fun GpaCard(
    gpaInfo: GpaInfo?,
    totalCourses: Int,
    totalCredits: Double,
    isSelectMode: Boolean,
    precision: Int = 2,
    onPrecisionToggle: () -> Unit = {},
    extraContent: @Composable ColumnScope.() -> Unit = {},
) {
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
      Box(Modifier.fillMaxWidth()) {
        // 平时是这页的主角，铺一层主题色流动底色（6 秒后停，它在玻璃顶栏下面）；
        // 选课算均分时换成 secondaryContainer 纯色，表示「这是临时的计算结果」
        if (!isSelectMode) {
            com.xjtu.toolbox.ui.components.HeroMesh(
                base = containerColor,
                accent = MiuixTheme.colorScheme.primary,
                modifier = Modifier.matchParentSize(),
            )
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            if (isSelectMode) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "选课均分",
                        style = MiuixTheme.textStyles.body1,
                        fontWeight = FontWeight.SemiBold,
                        color = textColor
                    )
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = accentColor,
                        border = BorderStroke(0.5.dp, textColor.copy(alpha = 0.15f))
                    ) {
                        Text(
                            "已选 $totalCourses 门",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MiuixTheme.textStyles.footnote1,
                            color = textColor.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

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
                    value = gpaInfo?.gpa,
                    format = { "%.${precision}f".format(it) },
                    label = "GPA",
                    textColor = textColor,
                    modifier = Modifier.weight(1f)
                )
                GpaStatColumn(
                    value = gpaInfo?.averageScore?.takeIf { it > 0 },
                    format = { "%.${precision}f".format(it) },
                    label = "均分",
                    textColor = textColor,
                    modifier = Modifier.weight(1f)
                )
                GpaStatColumn(
                    value = (gpaInfo?.courseCount ?: totalCourses).toDouble(),
                    format = { "%.0f".format(it) },
                    label = "课程",
                    textColor = textColor,
                    modifier = Modifier.weight(1f)
                )
                GpaStatColumn(
                    value = gpaInfo?.totalCredits ?: totalCredits,
                    format = { "%.1f".format(it) },
                    label = "学分",
                    textColor = textColor,
                    modifier = Modifier.weight(1f)
                )
            }
            extraContent()
        }
      }
    }
}

@Composable
private fun GpaStatColumn(
    value: Double?,
    format: (Float) -> String,
    label: String,
    textColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    // 字号按**最终值**的长度定，滚动过程中不跟着变，数字不会一跳一跳地换字号
    val finalText = value?.let { format(it.toFloat()) } ?: "—"
    val valueStyle = when {
        finalText.length >= 7 -> MiuixTheme.textStyles.body2
        finalText.length >= 6 -> MiuixTheme.textStyles.body1
        finalText.length >= 5 -> MiuixTheme.textStyles.subtitle
        else -> MiuixTheme.textStyles.title3
    }
    val rolled by com.xjtu.toolbox.ui.components.rememberRollingValue(value ?: 0.0)
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            if (value == null) "—" else format(rolled),
            style = valueStyle,
            fontWeight = FontWeight.Bold,
            color = textColor,
            maxLines = 1,
            softWrap = false
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
private fun ScoreRow(
    scoreItem: ScoreItem,
    isExpanded: Boolean,
    detail: ScoreDetail?,
    isDetailLoading: Boolean,
    showCheckbox: Boolean = false,
    isSelected: Boolean = false,
    isFromReport: Boolean = false,
    isUnevaluated: Boolean = false,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reallyPassed = com.xjtu.toolbox.score.ScoreCalculator.isPassed(scoreItem)
    val scoreColor = when {
        !reallyPassed -> MiuixTheme.colorScheme.error
        scoreItem.scoreValue != null && scoreItem.scoreValue >= 90 -> MiuixTheme.colorScheme.primary
        scoreItem.scoreValue != null && scoreItem.scoreValue >= 80 -> MiuixTheme.colorScheme.primaryVariant
        scoreItem.scoreValue == null && reallyPassed -> MiuixTheme.colorScheme.primary
        else -> MiuixTheme.colorScheme.onSurface
    }
    val courseGpa = com.xjtu.toolbox.score.ScoreCalculator.courseGpa(scoreItem)
    val meta = buildList {
        add("${scoreItem.coursePoint} 学分")
        scoreItem.courseGroup?.let { add(it.shortLabel) }
            ?: scoreItem.majorFlag?.takeIf { it.isNotBlank() }?.let { add(it) }
        if (scoreItem.examProp.isNotEmpty() && scoreItem.examProp != "初修") add(scoreItem.examProp)
        if (scoreItem.examType.isNotEmpty()) add(scoreItem.examType)
        if (courseGpa != null) add("GPA %.1f".format(courseGpa))
    }.joinToString("  ·  ")

    Card(
        modifier = modifier.fillMaxWidth().animateContentSize(
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
        ),
        colors = CardDefaults.defaultColors(color = AppCardColor),
        onClick = onToggle,
        pressFeedbackType = PressFeedbackType.Sink,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showCheckbox) {
                    Checkbox(
                        state = if (isSelected) ToggleableState.On else ToggleableState.Off,
                        onClick = onToggle,
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            scoreItem.courseName,
                            style = MiuixTheme.textStyles.body1,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (isFromReport) {
                            ScoreTag("报表", MiuixTheme.colorScheme.primary, MiuixTheme.colorScheme.primary.copy(alpha = 0.12f))
                        }
                        if (isUnevaluated) {
                            ScoreTag("未评教", MiuixTheme.colorScheme.error, MiuixTheme.colorScheme.error.copy(alpha = 0.12f))
                        }
                    }
                    if (meta.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            meta,
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        scoreItem.score,
                        style = MiuixTheme.textStyles.title3,
                        fontWeight = FontWeight.SemiBold,
                        color = scoreColor
                    )
                    if (!reallyPassed) {
                        Text(
                            scoreItem.specificReason ?: "未通过",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.error
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = isExpanded && !showCheckbox,
                enter = expandVertically(animationSpec = spring(stiffness = Spring.StiffnessLow)) + fadeIn(),
                exit = shrinkVertically(animationSpec = spring(stiffness = Spring.StiffnessLow)) + fadeOut()
            ) {
                Column(Modifier.padding(top = 12.dp)) {
                    if (isDetailLoading) {
                        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(size = 22.dp)
                        }
                    } else if (detail != null) {
                        if (detail.itemList.isNotEmpty()) {
                            detail.itemList.forEach { item -> ScoreDetailRow(item) }
                        } else {
                            Text(
                                "该课程暂无分项成绩",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
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
private fun ScoreTag(text: String, color: androidx.compose.ui.graphics.Color, container: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        style = MiuixTheme.textStyles.footnote2,
        color = color,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(container)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
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
                    backgroundColor = MiuixTheme.colorScheme.outline.copy(alpha = 0.28f)
                ),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        Text(item.itemScore, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium)
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
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("4.3 绩点制", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Bold)
                val numericRules = listOf(
                    "95–100 → 4.3", "90–94 → 4.0", "85–89 → 3.7",
                    "81–84 → 3.3", "78–80 → 3.0", "75–77 → 2.7",
                    "72–74 → 2.3", "68–71 → 2.0", "64–67 → 1.7",
                    "60–63 → 1.3", "<60 → 0"
                )
                numericRules.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(pair[0], style = MiuixTheme.textStyles.footnote1, modifier = Modifier.weight(1f))
                        if (pair.size > 1) {
                            Text(pair[1], style = MiuixTheme.textStyles.footnote1, modifier = Modifier.weight(1f))
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "等级制按同样分数映射（A+/优+ = 4.3 … F/不及格 = 0）。通过/不通过不计入。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Text(
                    "GPA = Σ(绩点 × 学分) / Σ学分",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Text(
                    "页面数字仅供快速预览，不用于保研、奖学金等正式场景。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
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

/** 三种 GPA 模式概览卡片：全部 / 排除通选 / 排除所有通识 */
@Composable
fun GpaModeBreakdown(
    scores: List<ScoreItem>,
    calculateGpa: (List<ScoreItem>) -> GpaInfo?,
    precision: Int = 2,
    embedded: Boolean = false,
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

    val body: @Composable () -> Unit = {
        Column(if (embedded) Modifier else Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = SinkFeedback(),
                    ) { expanded = !expanded },
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

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Text("统计范围", style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        Text("GPA", style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp), textAlign = TextAlign.End, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        Text("均分", style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp), textAlign = TextAlign.End, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        Text("学分", style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Bold, modifier = Modifier.width(48.dp), textAlign = TextAlign.End, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        Text("门数", style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Bold, modifier = Modifier.width(36.dp), textAlign = TextAlign.End, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
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

    if (embedded) {
        body()
    } else {
        Card(
            modifier = Modifier.fillMaxWidth(),
            onClick = { expanded = !expanded },
            pressFeedbackType = PressFeedbackType.Sink,
            colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant)
        ) { body() }
    }
}
