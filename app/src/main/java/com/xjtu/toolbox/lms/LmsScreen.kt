package com.xjtu.toolbox.lms

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Assignment
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xjtu.toolbox.ui.components.AppFilterChip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

private const val TAG = "LmsScreen"

// ════════════════════════════════════════
//  导航状态
// ════════════════════════════════════════

private sealed class LmsPage {
    data object CourseList : LmsPage()
    data class ActivityList(val course: LmsCourseSummary) : LmsPage()
    data class ActivityDetail(val course: LmsCourseSummary, val activity: LmsActivity) : LmsPage()
    /** 视频播放器页面（直播 HLS 或录播） */
    data class VideoPlayer(
        val title: String,
        val instructorUrl: String?,
        val encoderUrl: String?,
        val isLive: Boolean,
        val returnPage: LmsPage,
        val headers: Map<String, String> = emptyMap()
    ) : LmsPage()
}

// ════════════════════════════════════════
//  入口
// ════════════════════════════════════════

@Composable
fun LmsScreen(login: LmsLogin, onBack: () -> Unit) {
    val appLoginState = LocalAppLoginState.current
    val context = LocalContext.current
    val api = remember { LmsApi(login) }

    var currentPage by remember { mutableStateOf<LmsPage>(LmsPage.CourseList) }

    // 首次使用提示
    val prefs = remember { context.getSharedPreferences("feature_hints", Context.MODE_PRIVATE) }
    val showHint = remember { mutableStateOf(!prefs.getBoolean("lms_hint_shown", false)) }

    if (showHint.value) {
        BackHandler { showHint.value = false; prefs.edit().putBoolean("lms_hint_shown", true).apply() }
        OverlayBottomSheet(
            show = showHint.value,
            title = "功能说明",
            onDismissRequest = {
                showHint.value = false
                prefs.edit().putBoolean("lms_hint_shown", true).apply()
            }
        ) {
            Column(Modifier.padding(bottom = 16.dp).navigationBarsPadding()) {
                Text(
                    "思源学堂（lms.xjtu.edu.cn）是学校新一代课程管理平台，数据来源为 LMS 系统。",
                    style = MiuixTheme.textStyles.body1
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "支持查看课程、作业、课件和课堂回放。课件会在应用内下载，并统一显示在下载管理中。",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        showHint.value = false
                        prefs.edit().putBoolean("lms_hint_shown", true).apply()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("知道了") }
            }
        }
    }

    // 返回处理
    BackHandler(enabled = currentPage !is LmsPage.CourseList) {
        currentPage = when (val cur = currentPage) {
            is LmsPage.VideoPlayer -> cur.returnPage
            is LmsPage.ActivityDetail -> LmsPage.ActivityList(cur.course)
            is LmsPage.ActivityList -> LmsPage.CourseList
            else -> LmsPage.CourseList
        }
    }

    // 视频播放器独立渲染（全屏，不参与 AnimatedContent）
    val videoPage = currentPage as? LmsPage.VideoPlayer
    if (videoPage != null) {
        com.xjtu.toolbox.classreplay.DirectVideoPlayerScreen(
            instructorUrl = videoPage.instructorUrl,
            encoderUrl = videoPage.encoderUrl,
            title = videoPage.title,
            headers = videoPage.headers,
            isLive = videoPage.isLive,
            onBack = { currentPage = videoPage.returnPage }
        )
        return
    }

    AnimatedContent(
        targetState = currentPage,
        transitionSpec = {
            val forward = when {
                targetState is LmsPage.ActivityList && initialState is LmsPage.CourseList -> true
                targetState is LmsPage.ActivityDetail && initialState is LmsPage.ActivityList -> true
                targetState is LmsPage.VideoPlayer -> true
                else -> false
            }
            if (forward) {
                (slideInHorizontally { it / 3 } + fadeIn()) togetherWith
                    (slideOutHorizontally { -it / 3 } + fadeOut())
            } else {
                (slideInHorizontally { -it / 3 } + fadeIn()) togetherWith
                    (slideOutHorizontally { it / 3 } + fadeOut())
            }
        },
        label = "LmsPage"
    ) { page ->
        when (page) {
            is LmsPage.CourseList -> CourseListPage(
                api = api,
                onBack = onBack,
                onCourseSelected = { currentPage = LmsPage.ActivityList(it) }
            )
            is LmsPage.ActivityList -> ActivityListPage(
                api = api,
                course = page.course,
                onBack = { currentPage = LmsPage.CourseList },
                onActivitySelected = { currentPage = LmsPage.ActivityDetail(page.course, it) }
            )
            is LmsPage.ActivityDetail -> ActivityDetailPage(
                api = api,
                course = page.course,
                activity = page.activity,
                onBack = { currentPage = LmsPage.ActivityList(page.course) },
                onPlayVideo = { title, instrUrl, encUrl, isLive, headers ->
                    currentPage = LmsPage.VideoPlayer(
                        title = title,
                        instructorUrl = instrUrl,
                        encoderUrl = encUrl,
                        isLive = isLive,
                        returnPage = LmsPage.ActivityDetail(page.course, page.activity),
                        headers = headers
                    )
                }
            )
            is LmsPage.VideoPlayer -> { /* handled above */ }
        }
    }
}

// ════════════════════════════════════════
//  页面 1 — 课程列表
// ════════════════════════════════════════

@Composable
private fun CourseListPage(
    api: LmsApi,
    onBack: () -> Unit,
    onCourseSelected: (LmsCourseSummary) -> Unit
) {
    val appLoginState = LocalAppLoginState.current
    var courses by remember { mutableStateOf<List<LmsCourseSummary>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var selectedSemester by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun loadCourses() {
        scope.launch {
            isLoading = true
            errorMsg = null
            try {
                courses = withContext(Dispatchers.IO) { api.getMyCourses() }
            } catch (e: AuthExpiredException) {
                appLoginState.handleAuthExpired(LoginType.LMS, Routes.LMS, onBack)
            } catch (e: Exception) {
                Log.e(TAG, "loadCourses error", e)
                errorMsg = "加载课程失败: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) { loadCourses() }

    val semesters = remember(courses) {
        courses.map { it.semesterLabel }.distinct().sortedDescending()
    }

    val filtered = remember(courses, selectedSemester) {
        if (selectedSemester == null) courses
        else courses.filter { it.semesterLabel == selectedSemester }
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = "思源学堂",
                color = MiuixTheme.colorScheme.background,
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
                isLoading && courses.isEmpty() -> LoadingIndicator("加载课程列表…")
                errorMsg != null && courses.isEmpty() -> ErrorRetry(errorMsg!!) { loadCourses() }
                courses.isEmpty() -> EmptyState(Icons.Default.School, "没有课程", "暂未加入任何课程")
                else -> {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                        if (semesters.size > 1) {
                            item(key = "semester_filter") {
                                Row(
                                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    AppFilterChip(selected = selectedSemester == null, onClick = { selectedSemester = null }, label = "全部")
                                    semesters.forEach { sem ->
                                        AppFilterChip(selected = selectedSemester == sem, onClick = { selectedSemester = sem }, label = sem)
                                    }
                                }
                            }
                        }
                        item(key = "count") {
                            Text(
                                "共 ${filtered.size} 门课程" + if (selectedSemester != null) " ($selectedSemester)" else "",
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                            )
                        }
                        items(filtered, key = { it.id }) { course ->
                            LmsCourseCard(course) { onCourseSelected(course) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LmsCourseCard(course: LmsCourseSummary, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        pressFeedbackType = PressFeedbackType.Sink,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
        colors = CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
        )
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    course.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                if (course.instructors.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Person, null, Modifier.size(14.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            course.instructorNames,
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (course.department.name.isNotEmpty()) {
                        Text(course.department.name, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                    if (course.credit.isNotEmpty()) {
                        Text("${course.credit} 学分", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                    Text(course.semesterLabel, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.ChevronRight, null, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.size(20.dp))
        }
    }
}

// ════════════════════════════════════════
//  页面 2 — 活动列表
// ════════════════════════════════════════

@Composable
private fun ActivityListPage(
    api: LmsApi,
    course: LmsCourseSummary,
    onBack: () -> Unit,
    onActivitySelected: (LmsActivity) -> Unit
) {
    val appLoginState = LocalAppLoginState.current
    var activities by remember { mutableStateOf<List<LmsActivity>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var selectedType by remember { mutableStateOf<LmsActivityType?>(null) }
    val scope = rememberCoroutineScope()

    fun loadActivities() {
        scope.launch {
            isLoading = true
            errorMsg = null
            try {
                activities = withContext(Dispatchers.IO) { api.getCourseActivities(course.id) }
            } catch (e: AuthExpiredException) {
                appLoginState.handleAuthExpired(LoginType.LMS, Routes.LMS, onBack)
            } catch (e: Exception) {
                Log.e(TAG, "loadActivities error", e)
                errorMsg = "加载活动失败: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) { loadActivities() }

    val types = remember(activities) {
        activities.map { it.type }.distinct().sortedBy { it.ordinal }
    }

    val filtered = remember(activities, selectedType) {
        if (selectedType == null) activities
        else activities.filter { it.type == selectedType }
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = course.name,
                color = MiuixTheme.colorScheme.background,
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
                isLoading && activities.isEmpty() -> LoadingIndicator("加载活动列表…")
                errorMsg != null && activities.isEmpty() -> ErrorRetry(errorMsg!!) { loadActivities() }
                activities.isEmpty() -> EmptyState(Icons.Default.Inbox, "暂无活动", "该课程还没有发布任何活动")
                else -> {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                        if (types.size > 1) {
                            item(key = "type_filter") {
                                Row(
                                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    AppFilterChip(selected = selectedType == null, onClick = { selectedType = null }, label = "全部")
                                    types.forEach { type ->
                                        AppFilterChip(
                                            selected = selectedType == type,
                                            onClick = { selectedType = type },
                                            label = type.displayName()
                                        )
                                    }
                                }
                            }
                        }
                        item(key = "count") {
                            Text(
                                "共 ${filtered.size} 个活动",
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                            )
                        }
                        items(filtered, key = { it.id }) { activity ->
                            LmsActivityCard(activity) { onActivitySelected(activity) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LmsActivityCard(activity: LmsActivity, onClick: () -> Unit) {
    val (icon, color) = activityTypeVisual(activity.type)
    Card(
        onClick = onClick,
        pressFeedbackType = PressFeedbackType.Sink,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
        colors = CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
        )
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    activity.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        activity.type.displayName(),
                        fontSize = 12.sp,
                        color = color
                    )
                    activity.startTime?.let {
                        Text(
                            formatLmsTime(it),
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                }
            }
            Icon(Icons.Default.ChevronRight, null, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

// ════════════════════════════════════════
//  页面 3 — 活动详情
// ════════════════════════════════════════

@Composable
private fun ActivityDetailPage(
    api: LmsApi,
    course: LmsCourseSummary,
    activity: LmsActivity,
    onBack: () -> Unit,
    onPlayVideo: (title: String, instructorUrl: String?, encoderUrl: String?, isLive: Boolean, headers: Map<String, String>) -> Unit
) {
    val context = LocalContext.current
    val appLoginState = LocalAppLoginState.current
    var detail by remember { mutableStateOf<LmsActivity?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun loadDetail() {
        scope.launch {
            isLoading = true
            errorMsg = null
            try {
                detail = withContext(Dispatchers.IO) { api.getActivityDetail(activity.id) }
            } catch (e: AuthExpiredException) {
                appLoginState.handleAuthExpired(LoginType.LMS, Routes.LMS, onBack)
            } catch (e: Exception) {
                Log.e(TAG, "loadDetail error", e)
                errorMsg = "加载详情失败: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) { loadDetail() }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = activity.title,
                color = MiuixTheme.colorScheme.background,
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
                isLoading -> LoadingIndicator("加载活动详情…")
                errorMsg != null -> ErrorRetry(errorMsg!!) { loadDetail() }
                detail != null -> {
                    val d = detail!!
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        // 基本信息卡
                        item(key = "info") { ActivityInfoCard(d) }

                        // 作业描述（HTML 去标签后展示）
                        if (!d.description.isNullOrBlank()) {
                            item(key = "desc") {
                                val plainText = remember(d.description) {
                                    val doc = Jsoup.parse(d.description!!)
                                    doc.select("br").forEach { it.before("\n") }
                                    doc.select("p").forEach { it.after("\n") }
                                    doc.body()?.wholeOwnText()?.trim()?.ifBlank { null }
                                        ?: doc.text()
                                }
                                SectionHeader("作业描述")
                                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    Text(
                                        plainText,
                                        style = MiuixTheme.textStyles.body2,
                                        modifier = Modifier.padding(16.dp)
                                    )
                                }
                            }
                        }

                        // 附件
                        if (d.uploads.isNotEmpty()) {
                            item(key = "uploads_header") { SectionHeader("附件 (${d.uploads.size})") }
                            items(d.uploads, key = { "upload_${it.id}" }) { upload ->
                                UploadCard(upload, context, api)
                            }
                        }

                        // 作业提交记录
                        if (d.type == LmsActivityType.HOMEWORK && d.submissionList != null) {
                            val submissions = d.submissionList!!
                            if (submissions.list.isNotEmpty()) {
                                item(key = "sub_header") { SectionHeader("提交记录 (${submissions.list.size})") }
                                items(submissions.list, key = { "sub_${it.id}" }) { sub ->
                                    SubmissionCard(sub, context, api)
                                }
                            }
                        }

                        // 课堂回放 (LESSON 类型)
                        if (d.type == LmsActivityType.LESSON && d.replayVideos.isNotEmpty()) {
                            item(key = "replay_header") { SectionHeader("课堂回放 (${d.replayVideos.size})") }

                            // 多机位播放器入口按钮
                            if (d.replayVideos.size >= 2) {
                                item(key = "replay_play_btn") {
                                    val instrVideo = d.replayVideos.find {
                                        it.label.contains("instructor", true) || it.readableLabel == "教师画面"
                                    }
                                    val encVideo = d.replayVideos.find {
                                        it.label.contains("encoder", true) || it.label.contains("screen", true)
                                            || it.readableLabel == "电脑屏幕"
                                    }
                                    Button(
                                        onClick = {
                                            onPlayVideo(
                                                d.title,
                                                instrVideo?.downloadUrl,
                                                encVideo?.downloadUrl ?: d.replayVideos.first().downloadUrl,
                                                false,
                                                emptyMap()
                                            )
                                        },
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                    ) {
                                        Icon(Icons.Default.PlayCircle, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("多机位播放器")
                                    }
                                }
                            }

                            items(d.replayVideos, key = { "replay_${it.id}" }) { video ->
                                ReplayVideoCard(video, context) {
                                    // 单机位播放
                                    if (video.downloadUrl.isNotEmpty()) {
                                        val isInstr = video.label.contains("instructor", true)
                                        onPlayVideo(
                                            "${d.title} - ${video.readableLabel}",
                                            if (isInstr) video.downloadUrl else null,
                                            if (!isInstr) video.downloadUrl else null,
                                            false,
                                            emptyMap()
                                        )
                                    }
                                }
                            }
                        }

                        // 直播/录播信息 (LECTURE_LIVE 类型)
                        if (d.type == LmsActivityType.LECTURE_LIVE) {
                            item(key = "live_header") { SectionHeader("直播信息") }
                            item(key = "live_info") { LiveInfoCard(d) }

                            // HLS 直播流 — 用视频播放器播放
                            if (d.liveStreams.isNotEmpty()) {
                                item(key = "live_play_btn") {
                                    val instrStream = d.liveStreams.find { it.isInstructor }
                                    val encStream = d.liveStreams.find { it.isEncoder }
                                    val lmsHeaders = mapOf(
                                        "Origin" to "https://lms.xjtu.edu.cn",
                                        "Referer" to "https://lms.xjtu.edu.cn/"
                                    )
                                    Button(
                                        onClick = {
                                            onPlayVideo(
                                                d.title,
                                                instrStream?.src,
                                                encStream?.src ?: d.liveStreams.first().src,
                                                true,
                                                lmsHeaders
                                            )
                                        },
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                    ) {
                                        Icon(Icons.Default.LiveTv, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("多机位观看直播")
                                    }
                                }
                                // 单独的流列表
                                items(d.liveStreams, key = { "stream_${it.label}" }) { stream ->
                                    LiveStreamCard(stream) {
                                        val lmsHeaders = mapOf(
                                            "Origin" to "https://lms.xjtu.edu.cn",
                                            "Referer" to "https://lms.xjtu.edu.cn/"
                                        )
                                        onPlayVideo(
                                            "${d.title} - ${stream.readableLabel}",
                                            if (stream.isInstructor) stream.src else null,
                                            if (!stream.isInstructor) stream.src else null,
                                            true,
                                            lmsHeaders
                                        )
                                    }
                                }
                            }

                            // LECTURE_LIVE 录播回放
                            if (d.liveReplayVideos.isNotEmpty()) {
                                item(key = "live_replay_header") { SectionHeader("课堂录播 (${d.liveReplayVideos.size})") }

                                if (d.liveReplayVideos.size >= 2) {
                                    item(key = "live_replay_play_btn") {
                                        val instrVideo = d.liveReplayVideos.find {
                                            it.label.contains("instructor", true) || it.readableLabel == "教师画面"
                                        }
                                        val encVideo = d.liveReplayVideos.find {
                                            it.label.contains("encoder", true) || it.label.contains("screen", true)
                                                || it.readableLabel == "电脑屏幕"
                                        }
                                        Button(
                                            onClick = {
                                                onPlayVideo(
                                                    d.title,
                                                    instrVideo?.downloadUrl,
                                                    encVideo?.downloadUrl ?: d.liveReplayVideos.first().downloadUrl,
                                                    false,
                                                    emptyMap()
                                                )
                                            },
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                        ) {
                                            Icon(Icons.Default.PlayCircle, null, Modifier.size(18.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("多机位播放器")
                                        }
                                    }
                                }

                                d.liveReplayVideos.forEachIndexed { idx, video ->
                                    item(key = "live_replay_${idx}_${video.id}") {
                                        ReplayVideoCard(video, context) {
                                            if (video.downloadUrl.isNotEmpty()) {
                                                val isInstr = video.label.contains("instructor", true)
                                                onPlayVideo(
                                                    "${d.title} - ${video.readableLabel}",
                                                    if (isInstr) video.downloadUrl else null,
                                                    if (!isInstr) video.downloadUrl else null,
                                                    false,
                                                    emptyMap()
                                                )
                                            }
                                        }
                                    }
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

// ════════════════════════════════════════
//  详情子组件
// ════════════════════════════════════════

@Composable
private fun ActivityInfoCard(activity: LmsActivity) {
    val (icon, color) = activityTypeVisual(activity.type)
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = color, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text(activity.type.displayName(), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = color)
            }
            Spacer(Modifier.height(8.dp))
            Text(activity.title, fontSize = 18.sp, fontWeight = FontWeight.Bold)

            if (activity.startTime != null || activity.endTime != null) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Schedule, null, Modifier.size(14.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        buildString {
                            activity.startTime?.let { append(formatLmsTime(it)) }
                            activity.endTime?.let { append(" ~ "); append(formatLmsTime(it)) }
                        },
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }

            // 作业特有信息
            if (activity.type == LmsActivityType.HOMEWORK) {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (activity.submitByGroup) {
                        InfoChip("小组提交", Icons.Default.Group)
                    }
                    if (activity.userSubmitCount > 0) {
                        InfoChip("已提交 ${activity.userSubmitCount} 次", Icons.Default.CheckCircle)
                    }
                }
                val hasStats = activity.averageScore != null || activity.highestScore != null
                    || activity.lowestScore != null || activity.hasScoreCount != null
                if (hasStats) {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        activity.hasScoreCount?.let { InfoChip("已批阅 $it 人", Icons.Default.People) }
                        activity.averageScore?.let { InfoChip("平均 ${"%.1f".format(it)}", Icons.Default.Analytics) }
                        activity.highestScore?.let { InfoChip("最高 ${"%.1f".format(it)}", Icons.Default.TrendingUp) }
                        activity.lowestScore?.let { InfoChip("最低 ${"%.1f".format(it)}", Icons.Default.TrendingDown) }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoChip(text: String, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(14.dp), tint = MiuixTheme.colorScheme.primary)
        Spacer(Modifier.width(4.dp))
        Text(text, fontSize = 12.sp, color = MiuixTheme.colorScheme.primary)
    }
}

@Composable
private fun UploadCard(upload: LmsUpload, context: Context, api: LmsApi? = null) {
    val isImage = upload.type.startsWith("image", ignoreCase = true)
    var previewBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var isDownloading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 图片预览弹窗
    if (previewBitmap != null) {
        Dialog(
            onDismissRequest = { previewBitmap = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable { previewBitmap = null },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = previewBitmap!!.asImageBitmap(),
                    contentDescription = upload.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    Card(
        onClick = {
            if (isImage && api != null) {
                val url = upload.downloadUrl.ifEmpty { upload.previewUrl }
                if (url.isNotEmpty() && !isDownloading) {
                    isDownloading = true
                    scope.launch {
                        val bytes = withContext(Dispatchers.IO) { api.downloadBytes(url) }
                        previewBitmap = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                        isDownloading = false
                    }
                }
            } else if (api != null) {
                val url = upload.downloadUrl.ifEmpty { upload.previewUrl }
                if (url.isNotEmpty() && !isDownloading) {
                    isDownloading = true
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            saveToDownloads(context, upload.name, upload.type, url, api)
                        }
                        isDownloading = false
                        Toast.makeText(
                            context,
                            if (ok) "已保存到下载" else "下载失败，请重试",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        },
        pressFeedbackType = PressFeedbackType.Sink,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(fileTypeIcon(upload.type), null, tint = MiuixTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(upload.name, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (upload.readableSize.isNotEmpty()) {
                    Text(upload.readableSize, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
            if (isDownloading) {
                CircularProgressIndicator(size = 20.dp, strokeWidth = 2.dp)
            } else {
                Icon(
                    if (isImage && api != null) Icons.Default.ZoomIn else Icons.Default.Download,
                    null, tint = MiuixTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun SubmissionCard(sub: LmsSubmissionItem, context: Context, api: LmsApi) {
    val scope = rememberCoroutineScope()
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val statusColor = when (sub.status) {
                        "graded" -> Color(0xFF4CAF50)
                        "submitted" -> MiuixTheme.colorScheme.primary
                        "returned" -> Color(0xFFFF9800)
                        else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                    }
                    Text(sub.statusLabel, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = statusColor)
                    if (sub.isResubmitted) {
                        Spacer(Modifier.width(8.dp))
                        Text("(重新提交)", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
                Text(sub.scoreDisplay, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.primary)
            }

            if (sub.submittedAt != null) {
                Spacer(Modifier.height(4.dp))
                Text("提交于 ${formatLmsTime(sub.submittedAt!!)}", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }

            if (sub.content.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(sub.content, fontSize = 13.sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
            }

            if (sub.instructorComment.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Comment, null, Modifier.size(14.dp), tint = Color(0xFFFF9800))
                    Spacer(Modifier.width(4.dp))
                    Text("教师评语: ${sub.instructorComment}", fontSize = 13.sp, color = Color(0xFFFF9800))
                }
            }

            // 批改附件
            val correctUploads = sub.submissionCorrect.uploads
            if (correctUploads.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("批改附件", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                correctUploads.forEach { upload ->
                    UploadCard(upload, context, api)
                }
            }

            // 提交附件
            if (sub.uploads.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("提交附件", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                sub.uploads.forEach { upload ->
                    UploadCard(upload, context, api)
                    if (upload.attachmentUrl.isNotEmpty()) {
                        var downloadingMarked by remember { mutableStateOf(false) }
                        TextButton(
                            text = if (downloadingMarked) "正在下载批改版…" else "下载批改版",
                            enabled = !downloadingMarked,
                            onClick = {
                                downloadingMarked = true
                                scope.launch {
                                    val markedName = upload.name.substringBeforeLast('.', upload.name) +
                                        "_批改版." + upload.name.substringAfterLast('.', "bin")
                                    val ok = withContext(Dispatchers.IO) {
                                        saveToDownloads(context, markedName, upload.type, upload.attachmentUrl, api)
                                    }
                                    downloadingMarked = false
                                    Toast.makeText(
                                        context,
                                        if (ok) "批改版已保存到下载管理" else "批改版下载失败",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReplayVideoCard(video: LmsReplayVideo, context: Context, onPlay: () -> Unit) {
    Card(
        onClick = onPlay,
        pressFeedbackType = PressFeedbackType.Sink,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.PlayCircle, null, tint = MiuixTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(video.readableLabel, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (video.readableSize.isNotEmpty()) {
                        Text(video.readableSize, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                    if (video.mute) {
                        Text("静音", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
            Icon(Icons.Default.PlayArrow, null, tint = MiuixTheme.colorScheme.primary)
        }
    }
}

/** HLS 直播流卡片 */
@Composable
private fun LiveStreamCard(stream: LmsLiveStream, onPlay: () -> Unit) {
    Card(
        onClick = onPlay,
        pressFeedbackType = PressFeedbackType.Sink,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (stream.isInstructor) Icons.Default.Videocam else Icons.Default.ScreenShare,
                null, tint = Color(0xFFC62828), modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stream.readableLabel, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("HLS 直播", fontSize = 12.sp, color = Color(0xFFC62828))
                    if (stream.mute) {
                        Text("静音", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
            Icon(Icons.Default.PlayArrow, null, tint = Color(0xFFC62828))
        }
    }
}

@Composable
private fun LiveInfoCard(activity: LmsActivity) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(16.dp)) {
            // 教室信息
            if (!activity.liveRoomName.isNullOrBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.MeetingRoom, null, Modifier.size(16.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Spacer(Modifier.width(6.dp))
                    Text("教室: ${activity.liveRoomName}", fontSize = 14.sp)
                }
                Spacer(Modifier.height(6.dp))
            }

            // 教师
            if (activity.liveInstructorNames.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Person, null, Modifier.size(16.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Spacer(Modifier.width(6.dp))
                    Text("教师: ${activity.liveInstructorNames.joinToString(", ")}", fontSize = 14.sp)
                }
                Spacer(Modifier.height(6.dp))
            }

            // 直播状态
            if (!activity.liveStatus.isNullOrBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val isLive = activity.liveStatus == "live_in_progress"
                    val statusColor = if (isLive) Color(0xFFC62828) else MiuixTheme.colorScheme.onSurfaceVariantSummary
                    val statusText = when (activity.liveStatus) {
                        "live_in_progress" -> "● 直播中"
                        "live_ended" -> "已结束"
                        "live_not_started" -> "未开始"
                        else -> activity.liveStatus!!
                    }
                    Icon(Icons.Default.Circle, null, Modifier.size(10.dp), tint = statusColor)
                    Spacer(Modifier.width(8.dp))
                    Text(statusText, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = statusColor)
                }
                Spacer(Modifier.height(6.dp))
            }

            // 流信息摘要
            if (activity.liveStreams.isNotEmpty()) {
                Text(
                    "${activity.liveStreams.size} 个视频流可用",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.primary
                )
            }

            // 录播摘要
            if (activity.liveReplayVideos.isNotEmpty()) {
                Text(
                    "${activity.liveReplayVideos.size} 个录播视频可用",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.primary
                )
            }
        }
    }
}

// ════════════════════════════════════════
//  通用组件
// ════════════════════════════════════════

@Composable
private fun BoxScope.LoadingIndicator(text: String) {
    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Spacer(Modifier.height(8.dp))
        Text(text, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
private fun BoxScope.ErrorRetry(message: String, onRetry: () -> Unit) {
    Column(Modifier.align(Alignment.Center).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, color = MiuixTheme.colorScheme.error)
        Spacer(Modifier.height(8.dp))
        TextButton(text = "重试", onClick = onRetry)
    }
}

@Composable
private fun BoxScope.EmptyState(icon: ImageVector, title: String, subtitle: String) {
    Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(48.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Spacer(Modifier.height(12.dp))
        Text(title, fontSize = 15.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = MiuixTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
    )
}

// ════════════════════════════════════════
//  工具函数
// ════════════════════════════════════════

private fun LmsActivityType.displayName(): String = when (this) {
    LmsActivityType.HOMEWORK -> "作业"
    LmsActivityType.MATERIAL -> "资料"
    LmsActivityType.LESSON -> "课堂"
    LmsActivityType.LECTURE_LIVE -> "直播"
    LmsActivityType.UNKNOWN -> "其他"
}

private fun activityTypeVisual(type: LmsActivityType): Pair<ImageVector, Color> = when (type) {
    LmsActivityType.HOMEWORK -> Icons.AutoMirrored.Filled.Assignment to Color(0xFFE65100)
    LmsActivityType.MATERIAL -> Icons.Default.Description to Color(0xFF1565C0)
    LmsActivityType.LESSON -> Icons.Default.OndemandVideo to Color(0xFF512DA8)
    LmsActivityType.LECTURE_LIVE -> Icons.Default.LiveTv to Color(0xFFC62828)
    LmsActivityType.UNKNOWN -> Icons.Default.HelpOutline to Color(0xFF757575)
}

private fun saveToDownloads(context: Context, name: String, mimeType: String, url: String, api: LmsApi): Boolean {
    val mime = mimeType.ifBlank { "application/octet-stream" }
    val cv = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, name)
        put(MediaStore.Downloads.MIME_TYPE, mime)
        put(MediaStore.Downloads.RELATIVE_PATH, "Download/岱宗盒子")
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return false
    return try {
        val out = resolver.openOutputStream(uri) ?: return false
        val ok = api.downloadToStream(url, out)
        cv.clear()
        cv.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, cv, null, null)
        if (ok) {
            LmsDownloadStore.add(
                context,
                LmsDownloadRecord(name, mime, uri.toString(), System.currentTimeMillis())
            )
        } else {
            resolver.delete(uri, null, null)
        }
        ok
    } catch (e: Exception) {
        resolver.delete(uri, null, null)
        false
    }
}

private fun fileTypeIcon(type: String): ImageVector = when {
    type.contains("pdf", true) -> Icons.Default.PictureAsPdf
    type.contains("image", true) || type.contains("png", true) || type.contains("jpg", true) -> Icons.Default.Image
    type.contains("video", true) -> Icons.Default.VideoFile
    type.contains("audio", true) -> Icons.Default.AudioFile
    type.contains("zip", true) || type.contains("rar", true) -> Icons.Default.FolderZip
    else -> Icons.Default.InsertDriveFile
}

/**
 * 格式化 LMS 时间字符串 (ISO 8601 → 友好显示)
 */
private fun formatLmsTime(raw: String): String {
    return try {
        val zdt = java.time.ZonedDateTime.parse(raw)
        zdt.format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"))
    } catch (_: Exception) {
        try {
            val ldt = java.time.LocalDateTime.parse(raw.replace(" ", "T"))
            ldt.format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"))
        } catch (_: Exception) {
            raw.take(16).replace("T", " ")
        }
    }
}
