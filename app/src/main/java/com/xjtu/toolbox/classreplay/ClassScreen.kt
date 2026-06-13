package com.xjtu.toolbox.classreplay

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import android.content.Context
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import com.xjtu.toolbox.LocalAppLoginState
import com.xjtu.toolbox.Routes
import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.auth.LoginType
import com.xjtu.toolbox.auth.handleAuthExpired
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjtu.toolbox.ui.components.AppFilterChip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private const val TAG = "ClassScreen"

// ════════════════════════════════════════
//  入口：课程回放主屏幕
// ════════════════════════════════════════

@Composable
fun ClassScreen(
    login: ClassLogin,
    onBack: () -> Unit,
    onPlayReplay: (login: ClassLogin, activityId: Int) -> Unit,
    onDownloadReplay: (login: ClassLogin, activityIds: List<Int>) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    // 页面导航状态
    var selectedCourse by remember { mutableStateOf<Course?>(null) }

    // 首次使用提醒
    val prefs = remember { context.getSharedPreferences("feature_hints", Context.MODE_PRIVATE) }
    val showHint = remember { mutableStateOf(!prefs.getBoolean("class_replay_hint_shown", false)) }
    if (showHint.value) {
        BackHandler { showHint.value = false; prefs.edit().putBoolean("class_replay_hint_shown", true).apply() }
        OverlayBottomSheet(
            show = showHint.value,
            title = "功能说明",
            onDismissRequest = {
                showHint.value = false
                prefs.edit().putBoolean("class_replay_hint_shown", true).apply()
            }
        ) {
            Column(Modifier.padding(bottom = 16.dp).navigationBarsPadding()) {
                Text(
                    "课程回放功能数据来源为 class 平台（TronClass），而非思源学堂。",
                    style = MiuixTheme.textStyles.body1
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "class 平台专注课程录播回看（或许不全，但相对稳定）。如需查看作业、课件等完整功能，请使用教务 Tab 中的「思源学堂」。",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        showHint.value = false
                        prefs.edit().putBoolean("class_replay_hint_shown", true).apply()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("知道了") }
            }
        }
    }

    // 系统返回键处理：子页面返回上级而非直接退出
    BackHandler(enabled = selectedCourse != null) {
        selectedCourse = null
    }

    AnimatedContent(
        targetState = selectedCourse,
        transitionSpec = {
            if (targetState != null) {
                (slideInHorizontally { it / 3 } + fadeIn()) togetherWith
                        (slideOutHorizontally { -it / 3 } + fadeOut())
            } else {
                (slideInHorizontally { -it / 3 } + fadeIn()) togetherWith
                        (slideOutHorizontally { it / 3 } + fadeOut())
            }
        },
        label = "ClassPage"
    ) { course ->
        if (course == null) {
            CourseListPage(login = login, onBack = onBack, onCourseSelected = { selectedCourse = it })
        } else {
            ReplayListPage(
                login = login,
                course = course,
                onBack = { selectedCourse = null },
                onPlayReplay = onPlayReplay,
                onDownloadReplay = onDownloadReplay
            )
        }
    }
}

// ════════════════════════════════════════
//  页面 1 — 课程列表
// ════════════════════════════════════════

@Composable
private fun CourseListPage(
    login: ClassLogin,
    onBack: () -> Unit,
    onCourseSelected: (Course) -> Unit
) {
    val appLoginState = LocalAppLoginState.current
    var allCourses by remember { mutableStateOf<List<Course>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var selectedSemester by remember { mutableStateOf<String?>(null) } // null = 全部
    val scope = rememberCoroutineScope()

    // 一次性加载所有课程（多页）
    fun loadAllCourses() {
        scope.launch {
            isLoading = true
            errorMsg = null
            try {
                val result = mutableListOf<Course>()
                var page = 1
                while (true) {
                    val (list, total) = withContext(Dispatchers.IO) {
                        fetchCourses(login, page = page, pageSize = 100)
                    }
                    result.addAll(list)
                    if (result.size >= total || list.isEmpty()) break
                    page++
                }
                allCourses = result
            } catch (e: AuthExpiredException) {
                appLoginState.handleAuthExpired(LoginType.CLASS, Routes.CLASS_REPLAY, onBack)
            } catch (e: Exception) {
                Log.e(TAG, "loadCourses error", e)
                errorMsg = "加载课程失败: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) { loadAllCourses() }

    // 提取可用学期列表（按时间倒序）
    val semesters = remember(allCourses) {
        allCourses.map { it.semesterLabel }.distinct()
            .sortedDescending()   // "2025-2026 春" > "2024-2025 秋" 字典序即倒序
    }

    // 过滤后课程
    val filteredCourses = remember(allCourses, selectedSemester) {
        if (selectedSemester == null) allCourses
        else allCourses.filter { it.semesterLabel == selectedSemester }
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = "课程回放",
                color = MiuixTheme.colorScheme.surfaceVariant,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                isLoading && allCourses.isEmpty() -> {
                    Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(8.dp))
                        Text("加载课程列表…", fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
                errorMsg != null && allCourses.isEmpty() -> {
                    Column(
                        Modifier.align(Alignment.Center).padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(errorMsg ?: "", color = MiuixTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                        TextButton(text = "重试", onClick = { loadAllCourses() })
                    }
                }
                else -> {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                        // 学期筛选卡（与 LMS 课程页同构）
                        if (semesters.size > 1) {
                            item(key = "semester_filter") {
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                                    cornerRadius = 22.dp,
                                    colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant)
                                ) {
                                    Column(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
                                        Text(
                                            "选择学期",
                                            style = MiuixTheme.textStyles.subtitle,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 16.dp)
                                        )
                                        Text(
                                            (selectedSemester ?: "显示所有学期") + " · ${filteredCourses.size} 门课程",
                                            style = MiuixTheme.textStyles.footnote1,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 3.dp)
                                        )
                                        Row(
                                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                                                .padding(horizontal = 12.dp, vertical = 7.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            AppFilterChip(
                                                selected = selectedSemester == null,
                                                onClick = { selectedSemester = null },
                                                label = "全部"
                                            )
                                            semesters.forEach { sem ->
                                                AppFilterChip(
                                                    selected = selectedSemester == sem,
                                                    onClick = { selectedSemester = sem },
                                                    label = sem
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            item(key = "count_hint") {
                                Text(
                                    "共 ${filteredCourses.size} 门课程",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                                )
                            }
                        }

                        items(filteredCourses, key = { it.id }) { course ->
                            CourseCard(course) { onCourseSelected(course) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CourseCard(course: Course, onClick: () -> Unit) {
    // 与 LMS 课程卡同构：按课程 id 哈希取稳定 accent，首字作头像块
    val accent = listOf(
        Color(0xFF5B6FD8), Color(0xFF2D9B86), Color(0xFFD07A45), Color(0xFF8B63C7)
    )[(course.id.hashCode() and Int.MAX_VALUE) % 4]
    Card(
        onClick = onClick,
        pressFeedbackType = PressFeedbackType.Sink,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp),
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(15.dp),
                color = accent.copy(alpha = 0.13f),
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        course.displayName.take(1),
                        style = MiuixTheme.textStyles.title4,
                        fontWeight = FontWeight.Bold,
                        color = accent
                    )
                }
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    course.displayName,
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                val metaLine = buildList {
                    if (course.instructors.isNotEmpty()) add(course.instructors.joinToString("、"))
                    if (course.department.isNotEmpty()) add(course.department)
                    if (course.startDate != null) add(formatDateRange(course.startDate, course.endDate))
                }.joinToString(" · ")
                if (metaLine.isNotEmpty()) {
                    Text(
                        metaLine,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            // 状态胶囊
            val statusText = when {
                course.isClosed -> "已结课"
                course.isStarted -> "进行中"
                else -> "未开始"
            }
            val statusColor = when {
                course.isClosed -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                course.isStarted -> Color(0xFF2E9E5B)
                else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
            }
            Surface(shape = RoundedCornerShape(8.dp), color = statusColor.copy(alpha = 0.12f)) {
                Text(
                    statusText,
                    Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MiuixTheme.textStyles.footnote1,
                    color = statusColor,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

// ════════════════════════════════════════
//  页面 2 — 回放列表
// ════════════════════════════════════════

@Composable
private fun ReplayListPage(
    login: ClassLogin,
    course: Course,
    onBack: () -> Unit,
    onPlayReplay: (login: ClassLogin, activityId: Int) -> Unit,
    onDownloadReplay: (login: ClassLogin, activityIds: List<Int>) -> Unit
) {
    val appLoginState = LocalAppLoginState.current
    var activities by remember { mutableStateOf<List<LiveActivity>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // 多选模式状态
    var isSelectionMode by remember { mutableStateOf(false) }
    val selectedActivities = remember { mutableStateListOf<Int>() }

    // 下载配置状态
    var selectedVideoSources by remember { mutableStateOf<Set<String>>(setOf("instructor")) }
    var selectedAudioSource by remember { mutableStateOf("instructor") }

    // 加载回放列表
    fun loadAllActivities() {
        scope.launch {
            isLoading = true
            errorMsg = null
            try {
                val result = mutableListOf<LiveActivity>()
                var page = 1
                while (true) {
                    val (list, total) = withContext(Dispatchers.IO) {
                        fetchLiveActivities(login, course.id, page = page, pageSize = 50)
                    }
                    result.addAll(list.filter { it.isClosed })
                    if (result.size + list.count { !it.isClosed } >= total || list.isEmpty()) break
                    page++
                }
                activities = result
            } catch (e: AuthExpiredException) {
                appLoginState.handleAuthExpired(LoginType.CLASS, Routes.CLASS_REPLAY, onBack)
            } catch (e: Exception) {
                Log.e(TAG, "loadActivities error", e)
                errorMsg = "加载回放列表失败: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) { loadAllActivities() }

    // 退出选择模式时清空
    DisposableEffect(isSelectionMode) {
        onDispose {
            if (!isSelectionMode) selectedActivities.clear()
        }
    }

    Scaffold(
        topBar = {
            if (isSelectionMode) {
                // 选择模式 TopAppBar
                SmallTopAppBar(
                    title = "下载课程回放",
                    color = MiuixTheme.colorScheme.surfaceVariant,
                    navigationIcon = {
                        IconButton(onClick = {
                            isSelectionMode = false
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "取消选择")
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            val allIds = activities.map { it.id }.toSet()
                            val allSelected = selectedActivities.containsAll(allIds)
                            selectedActivities.clear()
                            if (!allSelected) {
                                selectedActivities.addAll(allIds)
                            }
                        }) {
                            Icon(Icons.Default.SelectAll, contentDescription = "全选/取消")
                        }
                    }
                )
            } else {
                // 普通模式 TopAppBar
                SmallTopAppBar(
                    title = course.displayName,
                    color = MiuixTheme.colorScheme.surfaceVariant,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        IconButton(onClick = { 
                            isSelectionMode = true
                            selectedActivities.clear()
                        }) {
                            Icon(Icons.Default.Download, contentDescription = "下载")
                        }
                    }
                )
            }
        },
        bottomBar = {
            if (isSelectionMode) {
                // 底栏 - 类似微信选图片
                DownloadBottomBar(
                    selectedCount = selectedActivities.size,
                    totalCount = activities.size,
                    selectedVideoSources = selectedVideoSources,
                    onVideoSourcesChanged = { selectedVideoSources = it },
                    selectedAudioSource = selectedAudioSource,
                    onAudioSourceChanged = { selectedAudioSource = it },
                    onDownload = {
                        if (selectedActivities.isNotEmpty() && selectedVideoSources.isNotEmpty()) {
                            onDownloadReplay(login, selectedActivities.toList())
                            isSelectionMode = false
                        }
                    },
                    enabled = selectedActivities.isNotEmpty() && selectedVideoSources.isNotEmpty()
                )
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                isLoading && activities.isEmpty() -> {
                    Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(8.dp))
                        Text("加载回放列表…", fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
                errorMsg != null && activities.isEmpty() -> {
                    Column(
                        Modifier.align(Alignment.Center).padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(errorMsg ?: "", color = MiuixTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                        TextButton(text = "重试", onClick = { loadAllActivities() })
                    }
                }
                activities.isEmpty() -> {
                    Column(
                        Modifier.align(Alignment.Center).padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.VideocamOff,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "该课程暂无回放录像",
                            fontSize = 15.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "可能未开启课堂录播",
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                        item(key = "count_hint") {
                            Text(
                                "共 ${activities.size} 个回放",
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                            )
                        }
                        items(activities, key = { it.id }) { activity ->
                            ActivityCard(
                                activity = activity,
                                isSelectionMode = isSelectionMode,
                                isSelected = selectedActivities.contains(activity.id),
                                onToggleSelection = {
                                    if (selectedActivities.contains(activity.id)) {
                                        selectedActivities.remove(activity.id)
                                    } else {
                                        selectedActivities.add(activity.id)
                                    }
                                },
                                onClick = {
                                    if (isSelectionMode) {
                                        if (selectedActivities.contains(activity.id)) {
                                            selectedActivities.remove(activity.id)
                                        } else {
                                            selectedActivities.add(activity.id)
                                        }
                                    } else {
                                        onPlayReplay(login, activity.id)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityCard(
    activity: LiveActivity,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelection: () -> Unit = {},
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        pressFeedbackType = PressFeedbackType.Sink,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 选择模式显示 Checkbox（左侧，类似选课算GPA）
            if (isSelectionMode) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clickable { onToggleSelection() },
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Icon(
                            Icons.Default.CheckBox,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .border(
                                    2.dp,
                                    MiuixTheme.colorScheme.outline,
                                    androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                                )
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
            }

            val accent = if (isSelectionMode && isSelected) MiuixTheme.colorScheme.primary
                else Color(0xFF5B6FD8)
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = accent.copy(alpha = 0.12f),
                modifier = Modifier.size(46.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    activity.title,
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    formatActivityTime(activity.startTime, activity.endTime),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }
    }
}

// ════════════════════════════════════════
//  工具函数
// ════════════════════════════════════════

/**
 * 格式化活动时间 (ISO 8601 → 友好显示)
 */
private fun formatActivityTime(startTime: String, endTime: String): String {
    return try {
        val start = ZonedDateTime.parse(startTime)
        val end = ZonedDateTime.parse(endTime)
        val dateFmt = DateTimeFormatter.ofPattern("yyyy/MM/dd")
        val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
        "${start.format(dateFmt)} ${start.format(timeFmt)}-${end.format(timeFmt)}"
    } catch (_: Exception) {
        "$startTime ~ $endTime"
    }
}

/**
 * 格式化课程日期区间
 */
private fun formatDateRange(start: String?, end: String?): String {
    val s = start?.replace("-", "/") ?: return ""
    val e = end?.replace("-", "/")
    return if (e != null) "$s ~ $e" else "$s 起"
}

// ════════════════════════════════════════
//  底栏 - 下载配置（类似微信选图片）
// ════════════════════════════════════════

@Composable
private fun DownloadBottomBar(
    selectedCount: Int,
    totalCount: Int,
    selectedVideoSources: Set<String>,
    onVideoSourcesChanged: (Set<String>) -> Unit,
    selectedAudioSource: String,
    onAudioSourceChanged: (String) -> Unit,
    onDownload: () -> Unit,
    enabled: Boolean
) {
    var showVideoSourcePicker by remember { mutableStateOf(false) }
    var showAudioSourcePicker by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .navigationBarsPadding()
            .background(MiuixTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // 视频源选择行
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("视频源", style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium, modifier = Modifier.width(56.dp))
                
                // 视频源 chips
                listOf(
                    "instructor" to "教师直播",
                    "encoder" to "电脑屏幕"
                ).forEach { (value, label) ->
                    val isSelected = selectedVideoSources.contains(value)
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (isSelected) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f) else MiuixTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.clickable {
                            val newSet = if (isSelected) {
                                selectedVideoSources - value
                            } else {
                                selectedVideoSources + value
                            }
                            onVideoSourcesChanged(newSet)
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (isSelected) {
                                Icon(
                                    Icons.Default.CheckBox,
                                    contentDescription = null,
                                    tint = MiuixTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            Text(
                                label,
                                style = MiuixTheme.textStyles.footnote1,
                                color = if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // 音频源选择行（横向滚动）
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("音频源", style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium, modifier = Modifier.width(56.dp).wrapContentWidth(Alignment.End))
                Spacer(Modifier.width(8.dp))
                
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf(
                        "instructor" to "教师音频",
                        "encoder" to "电脑音频",
                        "both" to "双音轨",
                        "mute" to "静音"
                    ).forEach { (value, label) ->
                        val isSelected = selectedAudioSource == value
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (isSelected) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f) else MiuixTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.clickable { onAudioSourceChanged(value) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (isSelected) {
                                    androidx.compose.foundation.layout.Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .background(MiuixTheme.colorScheme.primary, androidx.compose.foundation.shape.CircleShape)
                                    )
                                } else {
                                    androidx.compose.foundation.layout.Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .border(
                                                1.5.dp,
                                                MiuixTheme.colorScheme.outline,
                                                androidx.compose.foundation.shape.CircleShape
                                            )
                                    )
                                }
                                Text(
                                    label,
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // 下载按钮
            Button(
                onClick = onDownload,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("下载 $selectedCount 个视频")
            }
        }
    }
}


