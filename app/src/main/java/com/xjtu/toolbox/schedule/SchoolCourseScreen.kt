package com.xjtu.toolbox.schedule

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjtu.toolbox.auth.JwxtLogin
import com.xjtu.toolbox.ui.components.AppFilterChip
import com.xjtu.toolbox.ui.components.ErrorState
import com.xjtu.toolbox.ui.components.LoadingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.extra.SuperBottomSheet
import top.yukonga.miuix.kmp.extra.SuperSpinner
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.SinkFeedback
import top.yukonga.miuix.kmp.utils.overScrollVertical

private const val TAG = "SchoolCourseScreen"

// ── 主屏幕 ────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SchoolCourseScreen(
    login: JwxtLogin?,
    onBack: () -> Unit
) {
    if (login == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val scope = rememberCoroutineScope()
    val api = remember { SchoolCourseApi(login) }

    // ── 状态 ──
    var isInitializing by remember { mutableStateOf(true) }
    var initError by remember { mutableStateOf<String?>(null) }

    // 下拉选项
    var termList by remember { mutableStateOf<List<TermOption>>(emptyList()) }
    var departmentList by remember { mutableStateOf<List<DepartmentOption>>(emptyList()) }
    var currentTermCode by remember { mutableStateOf("") }

    // 搜索条件
    var selectedTermCode by rememberSaveable { mutableStateOf("") }
    var searchCourseName by rememberSaveable { mutableStateOf("") }
    var searchCourseCode by rememberSaveable { mutableStateOf("") }
    var searchTeacher by rememberSaveable { mutableStateOf("") }
    var searchClassName by rememberSaveable { mutableStateOf("") }
    var selectedDeptCode by rememberSaveable { mutableStateOf("") }
    var selectedCampusCode by rememberSaveable { mutableStateOf("") }
    var selectedWeekday by rememberSaveable { mutableIntStateOf(0) } // 0 = 不限
    var selectedStartSection by rememberSaveable { mutableIntStateOf(0) } // 0 = 不限
    var selectedEndSection by rememberSaveable { mutableIntStateOf(0) }
    var isPublicElectiveFilter by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var selectedElectiveCat by rememberSaveable { mutableStateOf("") }

    // 搜索结果
    var result by remember { mutableStateOf<SchoolCourseResult?>(null) }
    var isSearching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var currentPage by rememberSaveable { mutableIntStateOf(1) }

    // 高级筛选展开
    var showAdvancedFilter by rememberSaveable { mutableStateOf(false) }

    // 课程详情
    var detailCourse by remember { mutableStateOf<SchoolCourse?>(null) }

    // Snackbar
    val snackbarState = remember { SnackbarHostState() }

    // ── 初始化加载 ──
    LaunchedEffect(Unit) {
        isInitializing = true
        try {
            withContext(Dispatchers.IO) {
                val termsFuture = async(Dispatchers.IO) { api.getTermList() }
                val deptFuture = async(Dispatchers.IO) { api.getDepartments() }
                val currentFuture = async(Dispatchers.IO) { api.getCurrentTerm() }
                termList = termsFuture.await()
                departmentList = deptFuture.await()
                currentTermCode = currentFuture.await()
            }
            if (selectedTermCode.isBlank() && currentTermCode.isNotBlank()) {
                selectedTermCode = currentTermCode
            }
            initError = null
        } catch (e: Exception) {
            Log.e(TAG, "init failed", e)
            initError = "初始化失败: ${e.message}"
        } finally {
            isInitializing = false
        }
    }

    // ── 搜索函数 ──
    fun doSearch(page: Int = 1) {
        if (selectedTermCode.isBlank()) return
        isSearching = true
        searchError = null
        currentPage = page
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) {
                    api.queryCourses(
                        termCode = selectedTermCode,
                        courseName = searchCourseName.ifBlank { null },
                        courseCode = searchCourseCode.ifBlank { null },
                        teacher = searchTeacher.ifBlank { null },
                        departmentCode = selectedDeptCode.ifBlank { null },
                        className = searchClassName.ifBlank { null },
                        campusCode = selectedCampusCode.ifBlank { null },
                        isPublicElective = isPublicElectiveFilter,
                        electiveCategoryCode = selectedElectiveCat.ifBlank { null },
                        weekday = if (selectedWeekday > 0) selectedWeekday else null,
                        startSection = if (selectedStartSection > 0) selectedStartSection else null,
                        endSection = if (selectedEndSection > 0) selectedEndSection else null,
                        pageSize = 20,
                        pageNumber = page
                    )
                }
                result = r
            } catch (e: Exception) {
                Log.e(TAG, "search failed", e)
                searchError = "查询失败: ${e.message}"
            } finally {
                isSearching = false
            }
        }
    }

    // Scaffold 布局
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

    Scaffold(
        topBar = {
            TopAppBar(
                title = "全校课程查询",
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarState) }
    ) { padding ->
        if (isInitializing) {
            LoadingState("正在加载课程查询...", Modifier.padding(padding))
            return@Scaffold
        }

        if (initError != null) {
            ErrorState(initError!!, onRetry = {
                initError = null
                isInitializing = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            termList = api.getTermList()
                            departmentList = api.getDepartments()
                            currentTermCode = api.getCurrentTerm()
                        }
                        if (selectedTermCode.isBlank()) selectedTermCode = currentTermCode
                        initError = null
                    } catch (e: Exception) {
                        initError = "初始化失败: ${e.message}"
                    } finally {
                        isInitializing = false
                    }
                }
            }, modifier = Modifier.padding(padding))
            return@Scaffold
        }

        val listState = rememberLazyListState()

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── 学期选择 ──
            item(key = "term_selector") {
                val termEntries = termList.map { SpinnerEntry(title = it.name) }
                val selectedIdx = termList.indexOfFirst { it.code == selectedTermCode }.coerceAtLeast(0)

                Card(Modifier.fillMaxWidth(), cornerRadius = 16.dp) {
                    if (termEntries.isNotEmpty()) {
                        SuperSpinner(
                            items = termEntries,
                            selectedIndex = selectedIdx,
                            title = "学期",
                            onSelectedIndexChange = { idx ->
                                selectedTermCode = termList.getOrNull(idx)?.code ?: ""
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // ── 快捷筛选（课程名 + 教师） ──
            item(key = "quick_search") {
                Card(Modifier.fillMaxWidth(), cornerRadius = 16.dp) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = null,
                                tint = MiuixTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("搜索条件", style = MiuixTheme.textStyles.subtitle, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(12.dp))

                        TextField(
                            value = searchCourseName,
                            onValueChange = { searchCourseName = it },
                            label = "课程名",
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        TextField(
                            value = searchTeacher,
                            onValueChange = { searchTeacher = it },
                            label = "教师姓名",
                            modifier = Modifier.fillMaxWidth()
                        )

                        // 高级筛选切换
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = SinkFeedback()
                                ) { showAdvancedFilter = !showAdvancedFilter }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                if (showAdvancedFilter) "收起高级筛选" else "展开高级筛选",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.primary
                            )
                            Icon(
                                if (showAdvancedFilter) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                                tint = MiuixTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // ── 高级筛选内容 ──
                        AnimatedVisibility(
                            visible = showAdvancedFilter,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Column {
                                Spacer(Modifier.height(8.dp))
                                HorizontalDivider()
                                Spacer(Modifier.height(12.dp))

                                TextField(
                                    value = searchCourseCode,
                                    onValueChange = { searchCourseCode = it },
                                    label = "课程号",
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(Modifier.height(8.dp))
                                TextField(
                                    value = searchClassName,
                                    onValueChange = { searchClassName = it },
                                    label = "上课班级",
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Spacer(Modifier.height(12.dp))

                                // 开课单位
                                val deptEntries = buildList {
                                    add(SpinnerEntry(title = "不限"))
                                    departmentList.forEach { add(SpinnerEntry(title = it.name)) }
                                }
                                val deptIdx = if (selectedDeptCode.isBlank()) 0
                                    else (departmentList.indexOfFirst { it.code == selectedDeptCode } + 1).coerceAtLeast(0)
                                Card(Modifier.fillMaxWidth(), cornerRadius = 12.dp) {
                                    SuperSpinner(
                                        items = deptEntries,
                                        selectedIndex = deptIdx,
                                        title = "开课单位",
                                        onSelectedIndexChange = { idx ->
                                            selectedDeptCode = if (idx == 0) "" else departmentList.getOrNull(idx - 1)?.code ?: ""
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }

                                Spacer(Modifier.height(12.dp))

                                // 校区筛选
                                Text("校区", style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.height(6.dp))
                                val campusList = api.getCampusList()
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    AppFilterChip(
                                        selected = selectedCampusCode.isBlank(),
                                        onClick = { selectedCampusCode = "" },
                                        label = "全部"
                                    )
                                    campusList.forEach { campus ->
                                        AppFilterChip(
                                            selected = selectedCampusCode == campus.code,
                                            onClick = { selectedCampusCode = if (selectedCampusCode == campus.code) "" else campus.code },
                                            label = campus.name
                                        )
                                    }
                                }

                                Spacer(Modifier.height(12.dp))

                                // 星期筛选
                                Text("星期", style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.height(6.dp))
                                val weekDayLabels = listOf("不限", "一", "二", "三", "四", "五", "六", "日")
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    weekDayLabels.forEachIndexed { index, label ->
                                        AppFilterChip(
                                            selected = selectedWeekday == index,
                                            onClick = { selectedWeekday = index },
                                            label = label
                                        )
                                    }
                                }

                                Spacer(Modifier.height(12.dp))

                                // 节次筛选
                                Text("节次范围", style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.height(6.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val sectionEntries = buildList {
                                        add(SpinnerEntry(title = "不限"))
                                        for (i in 1..14) add(SpinnerEntry(title = "第${i}节"))
                                    }
                                    Card(Modifier.weight(1f), cornerRadius = 12.dp) {
                                        SuperSpinner(
                                            items = sectionEntries,
                                            selectedIndex = selectedStartSection,
                                            title = "开始",
                                            onSelectedIndexChange = { idx ->
                                                selectedStartSection = idx
                                                if (selectedEndSection in 1..<idx) selectedEndSection = idx
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                    Text(" ~ ", Modifier.padding(horizontal = 8.dp), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                    Card(Modifier.weight(1f), cornerRadius = 12.dp) {
                                        SuperSpinner(
                                            items = sectionEntries,
                                            selectedIndex = selectedEndSection,
                                            title = "结束",
                                            onSelectedIndexChange = { idx ->
                                                selectedEndSection = idx
                                                if (selectedStartSection in 1..14 && idx in 1..<selectedStartSection) selectedStartSection = idx
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }

                                Spacer(Modifier.height(12.dp))

                                // 校公选课筛选
                                Text("课程类型", style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.height(6.dp))
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    AppFilterChip(
                                        selected = isPublicElectiveFilter == null,
                                        onClick = { isPublicElectiveFilter = null; selectedElectiveCat = "" },
                                        label = "全部"
                                    )
                                    AppFilterChip(
                                        selected = isPublicElectiveFilter == true,
                                        onClick = {
                                            isPublicElectiveFilter = if (isPublicElectiveFilter == true) null else true
                                        },
                                        label = "校公选课"
                                    )
                                    AppFilterChip(
                                        selected = isPublicElectiveFilter == false,
                                        onClick = {
                                            isPublicElectiveFilter = if (isPublicElectiveFilter == false) null else false
                                            selectedElectiveCat = ""
                                        },
                                        label = "非公选课"
                                    )
                                }

                                // 公选课类别
                                AnimatedVisibility(isPublicElectiveFilter == true) {
                                    Column {
                                        Spacer(Modifier.height(8.dp))
                                        Text("公选课类别", style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Medium)
                                        Spacer(Modifier.height(6.dp))
                                        val categories = api.getElectiveCategories()
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            AppFilterChip(
                                                selected = selectedElectiveCat.isBlank(),
                                                onClick = { selectedElectiveCat = "" },
                                                label = "全部"
                                            )
                                            categories.forEach { cat ->
                                                AppFilterChip(
                                                    selected = selectedElectiveCat == cat.code,
                                                    onClick = { selectedElectiveCat = if (selectedElectiveCat == cat.code) "" else cat.code },
                                                    label = cat.name
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        // 搜索按钮
                        Button(
                            onClick = { doSearch(1) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isSearching && selectedTermCode.isNotBlank()
                        ) {
                            if (isSearching) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(if (isSearching) "搜索中..." else "搜索")
                        }
                    }
                }
            }

            // ── 搜索结果统计 ──
            result?.let { r ->
                item(key = "result_stats") {
                    Card(Modifier.fillMaxWidth(), cornerRadius = 16.dp) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "共 ${r.totalSize} 条结果",
                                style = MiuixTheme.textStyles.body2,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "第 ${r.pageNumber}/${r.totalPages} 页",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                    }
                }
            }

            // ── 搜索错误 ──
            if (searchError != null) {
                item(key = "search_error") {
                    ErrorState(searchError!!, onRetry = { doSearch(currentPage) })
                }
            }

            // ── 搜索中/空状态 ──
            if (isSearching && result == null) {
                item(key = "loading") {
                    LoadingState("正在查询...")
                }
            }

            // ── 课程列表 ──
            result?.let { r ->
                if (r.courses.isEmpty() && !isSearching) {
                    item(key = "empty") {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.SearchOff,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    "没有找到匹配的课程",
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                                Text(
                                    "试试放宽搜索条件？",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            }
                        }
                    }
                }

                items(r.courses, key = { it.teachingClassId.ifBlank { "${it.courseCode}_${it.sectionNumber}" } }) { course ->
                    CourseCard(course = course, onClick = { detailCourse = course })
                }

                // ── 分页控制 ──
                if (r.totalPages > 1) {
                    item(key = "pagination") {
                        PaginationBar(
                            currentPage = r.pageNumber,
                            totalPages = r.totalPages,
                            isSearching = isSearching,
                            onPageChange = { doSearch(it) }
                        )
                    }
                }
            }

            // 底部间距
            item(key = "bottom_spacer") {
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    // ── 课程详情 BottomSheet ──
    detailCourse?.let { course ->
        CourseDetailSheet(
            course = course,
            onDismiss = { detailCourse = null }
        )
    }
}

// ── 课程卡片 ────────────────────────────────

@Composable
private fun CourseCard(course: SchoolCourse, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = SinkFeedback()
            ) { onClick() },
        cornerRadius = 16.dp
    ) {
        Column(Modifier.padding(16.dp)) {
            // 标题行
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        course.courseName,
                        style = MiuixTheme.textStyles.subtitle,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${course.courseCode} · 课序号 ${course.sectionNumber}",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
                Spacer(Modifier.width(8.dp))
                // 学分标签
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
                ) {
                    Text(
                        "${course.credit}学分",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MiuixTheme.textStyles.footnote1,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.primary
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // 教师 + 单位
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Person, null, Modifier.size(14.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Spacer(Modifier.width(4.dp))
                Text(
                    course.teacher.ifBlank { "未知" },
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(12.dp))
                Icon(Icons.Default.Business, null, Modifier.size(14.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Spacer(Modifier.width(4.dp))
                Text(
                    course.department.ifBlank { "-" },
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 时间地点
            if (course.scheduleLocation.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Schedule, null, Modifier.size(14.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        course.scheduleLocation,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // 底部：选课/容量
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 校区 + 类型标签
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (course.campus.isNotBlank()) {
                        SmallTag(course.campus, MiuixTheme.colorScheme.primary)
                    }
                    if (course.isPublicElective && course.electiveCategory.isNotBlank()) {
                        SmallTag(course.electiveCategory, Color(0xFF00796B))
                    }
                }

                // 选课人数 / 容量
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val fillColor = when {
                        course.fillRatio >= 0.95f -> Color(0xFFD32F2F)
                        course.fillRatio >= 0.8f -> Color(0xFFE65100)
                        course.fillRatio >= 0.5f -> Color(0xFFF9A825)
                        else -> Color(0xFF2E7D32)
                    }
                    Text(
                        "${course.enrollCount}/${course.capacity}",
                        style = MiuixTheme.textStyles.footnote1,
                        fontWeight = FontWeight.Medium,
                        color = fillColor
                    )
                    Spacer(Modifier.width(4.dp))
                    // 容量条
                    Box(
                        Modifier
                            .width(40.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MiuixTheme.colorScheme.surfaceVariant)
                    ) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(course.fillRatio)
                                .clip(RoundedCornerShape(2.dp))
                                .background(fillColor)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SmallTag(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.1f)
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MiuixTheme.textStyles.footnote2,
            color = color,
            maxLines = 1
        )
    }
}

// ── 分页控件 ────────────────────────────────

@Composable
private fun PaginationBar(
    currentPage: Int,
    totalPages: Int,
    isSearching: Boolean,
    onPageChange: (Int) -> Unit
) {
    Card(Modifier.fillMaxWidth(), cornerRadius = 16.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 上一页
            TextButton(
                text = "上一页",
                onClick = { if (currentPage > 1) onPageChange(currentPage - 1) },
                enabled = currentPage > 1 && !isSearching
            )

            Spacer(Modifier.width(8.dp))

            // 页码
            val visiblePages = getVisiblePages(currentPage, totalPages)
            visiblePages.forEach { page ->
                if (page == -1) {
                    Text(
                        "...",
                        modifier = Modifier.padding(horizontal = 4.dp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                } else {
                    val isCurrentPage = page == currentPage
                    Surface(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .clickable(enabled = !isSearching && !isCurrentPage) { onPageChange(page) },
                        shape = CircleShape,
                        color = if (isCurrentPage) MiuixTheme.colorScheme.primary else Color.Transparent
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                "$page",
                                style = MiuixTheme.textStyles.footnote1,
                                fontWeight = if (isCurrentPage) FontWeight.Bold else FontWeight.Normal,
                                color = if (isCurrentPage) Color.White else MiuixTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.width(8.dp))

            // 下一页
            TextButton(
                text = "下一页",
                onClick = { if (currentPage < totalPages) onPageChange(currentPage + 1) },
                enabled = currentPage < totalPages && !isSearching
            )
        }
    }
}

private fun getVisiblePages(current: Int, total: Int): List<Int> {
    if (total <= 7) return (1..total).toList()
    val pages = mutableListOf<Int>()
    pages.add(1)
    if (current > 3) pages.add(-1) // ...
    val start = (current - 1).coerceAtLeast(2)
    val end = (current + 1).coerceAtMost(total - 1)
    for (i in start..end) pages.add(i)
    if (current < total - 2) pages.add(-1)
    pages.add(total)
    return pages
}

// ── 课程详情 BottomSheet ────────────────────

@Composable
private fun CourseDetailSheet(
    course: SchoolCourse,
    onDismiss: () -> Unit
) {
    BackHandler { onDismiss() }

    val showSheet = remember { mutableStateOf(true) }

    SuperBottomSheet(
        show = showSheet,
        onDismissRequest = { showSheet.value = false; onDismiss() }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .overScrollVertical()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            // 标题
            Text(
                course.courseName,
                style = MiuixTheme.textStyles.headline2,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${course.courseCode} · 课序号 ${course.sectionNumber}",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            // 基本信息
            DetailSection("基本信息") {
                DetailRow("开课单位", course.department)
                DetailRow("教师", course.teacher.ifBlank { "未知" })
                DetailRow("学分", "${course.credit}")
                DetailRow("总学时", "${course.totalHours.toInt()}")
                if (course.lectureHours > 0) DetailRow("授课学时", "${course.lectureHours.toInt()}")
                if (course.labHours > 0) DetailRow("实验学时", "${course.labHours.toInt()}")
                if (course.practiceHours > 0) DetailRow("实践学时", "${course.practiceHours.toInt()}")
                if (course.weeklyHours > 0) DetailRow("周学时", "${course.weeklyHours}")
            }

            Spacer(Modifier.height(16.dp))

            // 时间地点
            if (course.scheduleLocation.isNotBlank()) {
                DetailSection("时间地点") {
                    // 按逗号分割各时段
                    course.scheduleLocation.split(",").forEach { slot ->
                        val trimmed = slot.trim()
                        if (trimmed.isNotEmpty()) {
                            Row(
                                Modifier.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = MiuixTheme.colorScheme.primary.copy(alpha = 0.12f),
                                    modifier = Modifier.size(8.dp).offset(y = 5.dp)
                                ) {}
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    trimmed,
                                    style = MiuixTheme.textStyles.body2
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            // 选课信息
            DetailSection("选课信息") {
                DetailRow("选课人数", "${course.enrollCount}")
                DetailRow("课容量", "${course.capacity}")
                DetailRow("剩余名额", "${course.remaining}", valueColor = when {
                    course.remaining <= 0 -> Color(0xFFD32F2F)
                    course.remaining <= 5 -> Color(0xFFE65100)
                    else -> null
                })
                DetailRow("男生", "${course.maleEnrollCount}人")
                DetailRow("女生", "${course.femaleEnrollCount}人")

                // 容量条指示
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "容量 ${(course.fillRatio * 100).toInt()}%",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .weight(1f)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(MiuixTheme.colorScheme.surfaceVariant)
                    ) {
                        val fillColor = when {
                            course.fillRatio >= 0.95f -> Color(0xFFD32F2F)
                            course.fillRatio >= 0.8f -> Color(0xFFE65100)
                            course.fillRatio >= 0.5f -> Color(0xFFF9A825)
                            else -> Color(0xFF2E7D32)
                        }
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(course.fillRatio)
                                .clip(RoundedCornerShape(3.dp))
                                .background(fillColor)
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // 其他信息
            DetailSection("其他") {
                DetailRow("校区", course.campus.ifBlank { "未知" })
                if (course.isPublicElective) {
                    DetailRow("校公选课", "是")
                    if (course.electiveCategory.isNotBlank()) DetailRow("公选类别", course.electiveCategory)
                }
                if (course.className.isNotBlank()) DetailRow("上课班级", course.className)
                DetailRow("教学班ID", course.teachingClassId)
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun DetailSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        title,
        style = MiuixTheme.textStyles.subtitle,
        fontWeight = FontWeight.Bold,
        color = MiuixTheme.colorScheme.primary
    )
    Spacer(Modifier.height(8.dp))
    Column(Modifier.padding(start = 4.dp), content = content)
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    valueColor: Color? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.widthIn(max = 100.dp)
        )
        Text(
            value,
            style = MiuixTheme.textStyles.body2,
            fontWeight = FontWeight.Medium,
            color = valueColor ?: MiuixTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f, fill = false)
        )
    }
}
