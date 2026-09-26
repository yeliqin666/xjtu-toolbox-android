package com.xjtu.toolbox.lms

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import com.xjtu.toolbox.auth.LocalAppLoginState
import com.xjtu.toolbox.auth.SiteSession
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjtu.toolbox.ui.glass.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val TAG = "LmsScreen"
/**
 * 会话内页面缓存。
 *
 * [LmsScreen] 这一层在课程列表 / 活动列表 / 活动详情之间切换时始终保持组合，
 * 所以把已加载的数据和筛选选择放在这里，返回上一层就不再重新请求、也不丢筛选。
 * 之前各子页面各自 `remember` + `LaunchedEffect(Unit) { load() }`，
 * 每次返回都是一次冷加载，既慢又把用户的筛选状态清空。
 *
 * 仅存活于本次进入 LMS 期间；退出页面即释放，不做跨会话持久化。
 */
internal class LmsPageCache {
    var courses by mutableStateOf<List<LmsCourseSummary>>(emptyList())
    var selectedSemester by mutableStateOf<String?>(null)
    val activities = mutableStateMapOf<Int, List<LmsActivity>>()
    val selectedTypes = mutableStateMapOf<Int, LmsActivityType?>()
    val details = mutableStateMapOf<Int, LmsActivity>()
}
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
        val headers: Map<String, String> = emptyMap(),
        val startInDual: Boolean = false,
    ) : LmsPage()
}
// ════════════════════════════════════════
//  入口
// ════════════════════════════════════════

@Composable
fun LmsScreen(
    site: SiteSession,
    onBack: () -> Unit,
    /**
     * 从日程页点「思源学堂」进来时带的课程 ID：课程列表一到就直接翻到那门课的活动页。
     * 匹配不到（这门课没在思源开、或学期对不上）就停在课程列表，不额外报错——
     * 用户到了他要去的系统，只是少了一跳。
     */
    initialCourseId: Int? = null,
) {
    val appLoginState = LocalAppLoginState.current
    val context = LocalContext.current
    val api = remember(site) { LmsApi(site) }

    var currentPage by remember { mutableStateOf<LmsPage>(LmsPage.CourseList) }
    val cache = remember { LmsPageCache() }

    // 只跳一次：跳完把意图消费掉，否则用户从活动页返回课程列表会被立刻弹回去。
    var pendingCourseId by remember { mutableStateOf(initialCourseId) }
    // 深链进来时「课程列表」这一页一直画占位，直到确定匹配不到那门课才露出列表。
    // 不能跟着 pendingCourseId 一起清：清掉它和切到活动页是同一帧，AnimatedContent 里淡出中的
    // 旧页会用新状态重组一次——占位变成完整课程列表，用户就看到「中间闪过思源学堂主页」。
    var listPlaceholder by remember { mutableStateOf(initialCourseId != null) }
    LaunchedEffect(pendingCourseId) {
        // 占位期间 CourseListPage 没被组合，它那个"进页面就加载"的 effect 不会跑，
        // 得在这里把列表拉起来，否则一直转圈。
        if (pendingCourseId != null && cache.courses.isEmpty()) {
            runCatching {
                cache.courses = withContext(Dispatchers.IO) { api.getMyCourses() }
            }.onSuccess {
                if (cache.courses.isEmpty()) { pendingCourseId = null; listPlaceholder = false }
            }.onFailure {
                // 列表都拉不下来就别一直转圈：落回课程列表，由它显示错误和重试
                pendingCourseId = null
                listPlaceholder = false
            }
        }
    }
    LaunchedEffect(cache.courses, pendingCourseId) {
        val want = pendingCourseId ?: return@LaunchedEffect
        if (cache.courses.isEmpty()) return@LaunchedEffect
        val hit = cache.courses.firstOrNull { it.id == want }
        // 匹配不到就老实落回课程列表，别把用户困在转圈里。
        pendingCourseId = null
        if (hit != null) currentPage = LmsPage.ActivityList(hit) else listPlaceholder = false
    }

    // 首次使用提示
    val prefs = remember { context.getSharedPreferences("feature_hints", Context.MODE_PRIVATE) }
    val showHint = remember { mutableStateOf(!prefs.getBoolean("lms_hint_shown", false)) }

    if (showHint.value) {
        BackHandler { showHint.value = false; prefs.edit().putBoolean("lms_hint_shown", true).apply() }
        // 用 Window* 而非 Overlay*：本函数是路由外壳，Scaffold 在各子页面里
        // （CourseListPage / ActivityListPage…），而 Overlay* 需要 Scaffold 提供的
        // LocalDialogStates 宿主才会渲染，写在外壳里拿不到宿主会静默不显示。
        // 当前显示哪个子页面是动态的，搬进任一个都不对，故用自带独立 Window 的变体。
        WindowDialog(
            show = showHint.value,
            title = "功能说明",
            summary = "思源学堂（lms.xjtu.edu.cn）是学校新一代课程管理平台，数据来源为 LMS 系统。",
            onDismissRequest = {
                showHint.value = false
                prefs.edit().putBoolean("lms_hint_shown", true).apply()
            }
        ) {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "支持查看课程、作业、课件和课堂回放。课件会保存到下载管理；已结束的活动学堂会关掉下载。",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.height(12.dp))
                TextButton(
                    text = "知道了",
                    onClick = {
                        showHint.value = false
                        prefs.edit().putBoolean("lms_hint_shown", true).apply()
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    // 返回处理
    // 从日程页深链进来看某门课时，返回应当直接回日程，而不是先落到课程列表——
    // 用户并没有"进过"那个列表，退到那儿等于凭空多一层。
    // 合并成一个 BackHandler：多个同时启用时后注册的先被调用，
    // 拆成两个很容易被顺序坑到。
    val deepLinked = initialCourseId != null
    BackHandler(enabled = currentPage !is LmsPage.CourseList) {
        val cur = currentPage
        if (deepLinked && cur is LmsPage.ActivityList) {
            onBack()
            return@BackHandler
        }
        currentPage = when (cur) {
            is LmsPage.VideoPlayer -> cur.returnPage
            is LmsPage.ActivityDetail -> LmsPage.ActivityList(cur.course)
            is LmsPage.ActivityList -> LmsPage.CourseList
            else -> LmsPage.CourseList
        }
    }

    // 视频播放器独立渲染（全屏，不参与 AnimatedContent）
    val videoPage = currentPage as? LmsPage.VideoPlayer
    if (videoPage != null) {
        com.xjtu.toolbox.media.DirectVideoPlayerScreen(
            instructorUrl = videoPage.instructorUrl,
            encoderUrl = videoPage.encoderUrl,
            title = videoPage.title,
            headers = videoPage.headers,
            isLive = videoPage.isLive,
            startInDual = videoPage.startInDual,
            onBack = { currentPage = videoPage.returnPage }
        )
        return
    }

    // 前后两页同时淡入淡出，中途都是半透明——没有底色时会透出导航栈底下的主页。
    AnimatedContent(
        targetState = currentPage,
        modifier = Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface),
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
            // 带着 courseId 进来时先显示占位：课程列表要等接口回来才能匹配到那门课，
            // 这中间把列表画出来，用户看到的就是"闪一下列表又跳走"。
            is LmsPage.CourseList -> if (listPlaceholder) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    com.xjtu.toolbox.ui.components.MorphingLoader()  // 整页加载统一用形变加载器
                }
            } else {
                CourseListPage(
                    api = api,
                    cache = cache,
                    onBack = onBack,
                    onCourseSelected = { currentPage = LmsPage.ActivityList(it) }
                )
            }
            is LmsPage.ActivityList -> ActivityListPage(
                api = api,
                cache = cache,
                course = page.course,
                // 标题栏的返回箭头要和系统返回键一致：深链进来的直接退出，
                // 否则点箭头仍会掉进那个用户没进过的课程列表。
                onBack = { if (deepLinked) onBack() else currentPage = LmsPage.CourseList },
                onActivitySelected = { currentPage = LmsPage.ActivityDetail(page.course, it) }
            )
            is LmsPage.ActivityDetail -> ActivityDetailPage(
                api = api,
                cache = cache,
                course = page.course,
                activity = page.activity,
                onBack = { currentPage = LmsPage.ActivityList(page.course) },
                onPlayVideo = { title, instrUrl, encUrl, isLive, headers, startInDual ->
                    currentPage = LmsPage.VideoPlayer(
                        title = title,
                        instructorUrl = instrUrl,
                        encoderUrl = encUrl,
                        isLive = isLive,
                        returnPage = LmsPage.ActivityDetail(page.course, page.activity),
                        headers = headers,
                        startInDual = startInDual,
                    )
                }
            )
            is LmsPage.VideoPlayer -> { /* handled above */ }
        }
    }
}
// ════════════════════════════════════════
//  通用组件
// ════════════════════════════════════════

@Composable
internal fun BoxScope.LoadingIndicator(text: String) {
    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Spacer(Modifier.height(8.dp))
        Text(text, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}
@Composable
internal fun BoxScope.ErrorRetry(message: String, onRetry: () -> Unit) {
    Column(Modifier.align(Alignment.Center).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, color = MiuixTheme.colorScheme.error)
        Spacer(Modifier.height(8.dp))
        TextButton(text = "重试", onClick = onRetry)
    }
}
@Composable
internal fun BoxScope.EmptyState(icon: ImageVector, title: String, subtitle: String) {
    Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(48.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Spacer(Modifier.height(12.dp))
        Text(title, fontSize = 15.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}
@Composable
internal fun SectionHeader(title: String) {
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

internal fun LmsActivityType.displayName(): String = when (this) {
    LmsActivityType.HOMEWORK -> "作业"
    LmsActivityType.MATERIAL -> "资料"
    LmsActivityType.LESSON -> "课堂"
    LmsActivityType.LECTURE_LIVE -> "直播"
    LmsActivityType.PAGE -> "页面"
    LmsActivityType.FORUM -> "讨论区"
    LmsActivityType.QUESTIONNAIRE -> "问卷"
    LmsActivityType.ONLINE_VIDEO -> "在线视频"
    LmsActivityType.UNKNOWN -> "其他"
}
internal fun activityTypeVisual(type: LmsActivityType): Pair<ImageVector, Color> = when (type) {
    LmsActivityType.HOMEWORK -> Icons.AutoMirrored.Filled.Assignment to Color(0xFFE65100)
    LmsActivityType.MATERIAL -> Icons.Default.Description to Color(0xFF1565C0)
    LmsActivityType.LESSON -> Icons.Default.OndemandVideo to Color(0xFF512DA8)
    LmsActivityType.LECTURE_LIVE -> Icons.Default.LiveTv to Color(0xFFC62828)
    LmsActivityType.PAGE -> Icons.AutoMirrored.Filled.Article to Color(0xFF00796B)
    LmsActivityType.FORUM -> Icons.Default.Forum to Color(0xFF6D4C41)
    LmsActivityType.QUESTIONNAIRE -> Icons.Default.Quiz to Color(0xFFAD1457)
    LmsActivityType.ONLINE_VIDEO -> Icons.Default.PlayCircle to Color(0xFF00838F)
    LmsActivityType.UNKNOWN -> Icons.Default.HelpOutline to Color(0xFF757575)
}
internal fun downloadToast(result: LmsDownloadResult): String = when (result) {
    LmsDownloadResult.Ok -> "已保存到下载"
    LmsDownloadResult.Forbidden -> "活动已结束，学堂已关闭下载"
    LmsDownloadResult.Failed -> "下载失败"
}
internal suspend fun saveUploadToDownloads(context: Context, upload: LmsUpload, api: LmsApi): LmsDownloadResult =
    saveToDownloads(context, upload.name, upload.type, url = null, api = api, upload = upload)
internal suspend fun saveToDownloads(
    context: Context,
    name: String,
    mimeType: String,
    url: String?,
    api: LmsApi,
    upload: LmsUpload? = null,
): LmsDownloadResult {
    val mime = mimeType.ifBlank { "application/octet-stream" }
    val cv = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, name)
        put(MediaStore.Downloads.MIME_TYPE, mime)
        put(MediaStore.Downloads.RELATIVE_PATH, LmsDownloadStore.RELATIVE_PATH)
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
        ?: return LmsDownloadResult.Failed
    return try {
        val out = resolver.openOutputStream(uri) ?: return LmsDownloadResult.Failed
        val result = out.use { stream ->
            if (upload != null) api.downloadUpload(upload, stream)
            else if (api.downloadToStream(checkNotNull(url), stream)) LmsDownloadResult.Ok
            else LmsDownloadResult.Failed
        }
        cv.clear()
        cv.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, cv, null, null)
        if (result is LmsDownloadResult.Ok) {
            LmsDownloadStore.add(
                context,
                LmsDownloadRecord(
                    name = name,
                    mimeType = mime,
                    uri = uri.toString(),
                    savedAt = System.currentTimeMillis(),
                    category = LmsDownloadStore.CATEGORY_LMS
                )
            )
        } else {
            resolver.delete(uri, null, null)
        }
        result
    } catch (e: Exception) {
        resolver.delete(uri, null, null)
        LmsDownloadResult.Failed
    }
}
internal fun fileTypeIcon(type: String): ImageVector = when {
    type.contains("pdf", true) -> Icons.Default.PictureAsPdf
    type.contains("image", true) || type.contains("png", true) || type.contains("jpg", true) -> Icons.Default.Image
    type.contains("video", true) -> Icons.Default.VideoFile
    type.contains("audio", true) -> Icons.Default.AudioFile
    type.contains("zip", true) || type.contains("rar", true) -> Icons.Default.FolderZip
    else -> Icons.Default.InsertDriveFile
}
/**
 * 活动时间行（详情页信息卡）。
 *
 * 作业：`开始/可见 <begin> ~ 截止 <deadline>`
 * - 「可见」（`visible_start_at`）与「开始作答」（`start_time`）不是一回事，实测 65 份作业里
 *   32 份只有一个字段有值，所以缺哪个就用另一个顶上，别渲染成空的
 * - 「截止」取真 `deadline`（列表级字段，见 [com.xjtu.toolbox.lms.mergeBrief]）：`end_time` 是
 *   学堂关门时间，实测 4.6% 与 deadline 不同。下面 HomeworkMetaCard 也显示 deadline，
 *   两处取同一个值，才不会同屏出现两个不一样的「截止」
 *
 * 其余类型：只给 `start_time ~ end_time`，不加标签——课堂/直播的 endTime 是下课时间，
 * 叫「截止」是错的。
 */
internal fun lmsActivityTimeLine(activity: LmsActivity): String {
    val isHomework = activity.type == LmsActivityType.HOMEWORK
    val begin = activity.startTime ?: activity.visibleStartAt
    val beginLabel = if (!isHomework) "" else if (activity.startTime == null) "可见 " else "开始 "
    val endLabel = if (isHomework) "截止 " else ""
    val end = if (isHomework) (activity.deadline ?: activity.endTime) else activity.endTime
    return buildString {
        begin?.let { append(beginLabel).append(formatLmsTime(it)) }
        end?.let {
            if (isNotEmpty()) append(" ~ ")
            append(endLabel).append(formatLmsTime(it))
        }
    }
}
/**
 * 格式化 LMS 时间字符串 (ISO 8601 → 友好显示)
 */
internal fun formatLmsTime(raw: String): String {
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
