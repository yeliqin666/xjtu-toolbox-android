package com.xjtu.toolbox.lms

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import com.xjtu.toolbox.auth.LocalAppLoginState
import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.auth.handleAuthExpired
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import com.xjtu.toolbox.ui.components.rememberRetainedLazyListState
import com.xjtu.toolbox.ui.adaptive.readableWidth
import androidx.compose.foundation.lazy.staggeredgrid.items
import com.xjtu.toolbox.ui.glass.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import com.xjtu.toolbox.nav.AppRoute

private const val TAG = "LmsScreen"

// ════════════════════════════════════════
//  页面 3 — 活动详情
// ════════════════════════════════════════

@Composable
internal fun ActivityDetailPage(
    api: LmsApi,
    cache: LmsPageCache,
    course: LmsCourseSummary,
    activity: LmsActivity,
    onBack: () -> Unit,
    onPlayVideo: (title: String, instructorUrl: String?, encoderUrl: String?, isLive: Boolean, headers: Map<String, String>, startInDual: Boolean) -> Unit
) {
    val context = LocalContext.current
    val appLoginState = LocalAppLoginState.current
    val detail = cache.details[activity.id]
    var isLoading by remember { mutableStateOf(cache.details[activity.id] == null) }
    val listState = rememberRetainedLazyListState("lms_detail_${activity.id}")
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // 思源学堂回放下载：走 media.DownloadManager 队列。
    // download_url 本身就是直链，不必再解析。
    // 直播流（HLS/m3u8）不提供下载——它不是单文件，按分片下载另属一套实现。
    fun enqueueDownload(video: LmsReplayVideo, title: String) {
        val url = video.downloadUrl
        if (url.isBlank() || url.contains(".m3u8", ignoreCase = true)) {
            Toast.makeText(context, "该视频不支持下载", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    com.xjtu.toolbox.media.DownloadManager.getInstance(context.applicationContext)
                        .enqueueDownloads(
                            courseName = course.name,
                            activityTitle = title,
                            activityId = activity.id,
                            videos = listOf(
                                com.xjtu.toolbox.media.DownloadManager.DownloadItem(
                                    cameraType = if (video.label.contains("instructor", true)) "instructor" else "encoder",
                                    url = url,
                                )
                            ),
                        )
                }
            }.onSuccess {
                Toast.makeText(context, "已加入下载队列", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Log.e(TAG, "enqueue lms download failed", it)
                Toast.makeText(context, "加入下载失败：${it.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun loadDetail() {
        scope.launch {
            isLoading = true
            errorMsg = null
            try {
                // 详情接口不返回 deadline，得把列表里那条一起传进去（见 LmsApi.mergeBrief）
                cache.details[activity.id] = withContext(Dispatchers.IO) { api.getActivityDetail(activity.id, activity) }
            } catch (e: AuthExpiredException) {
                appLoginState.handleAuthExpired(AppRoute.Lms(), onBack)
            } catch (e: Exception) {
                Log.e(TAG, "loadDetail error", e)
                errorMsg = com.xjtu.toolbox.error.FriendlyError.of(e, "加载课程详情")
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) { if (cache.details[activity.id] == null) loadDetail() }

    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    // 玻璃顶栏（经典风格下为 null，一切照旧），用法见 ui/glass/GlassTopBar.kt；
    // 播放器另开全屏页（本函数外的 VideoPlayer 分支），不在这里，不受影响。
    val glass = rememberPageGlass()
    Scaffold(
        topBar = {
            TopAppBar(
                title = activity.title,
                largeTitle = activity.title,
                color = glassBarColor(glass),
                modifier = Modifier.glassTopBar(glass),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        val glassTop = padding.glassTop(glass)
        Box(Modifier.fillMaxSize().padding(padding.withoutTop(glass)).glassSource(glass).nestedScroll(scrollBehavior.nestedScrollConnection)) {
            when {
                isLoading -> LoadingIndicator("加载活动详情…")
                errorMsg != null -> ErrorRetry(errorMsg!!) { loadDetail() }
                detail != null -> {
                    val d = detail!!

                    // 视频源要求带同源请求头，否则 CDN 直接 403。
                    // 直播分支一直带着这两个头能正常播，回放分支之前传的是 emptyMap()，
                    // 于是 ExoPlayer 取流被拒（日志：Source error → Response code: 403）。
                    // 提到这里共用，避免两个分支再次走散。
                    val lmsVideoHeaders = mapOf(
                        "Origin" to "https://lms.xjtu.edu.cn",
                        "Referer" to "https://lms.xjtu.edu.cn/",
                    )
                    LazyColumn(
                        state = listState,
                        // 活动详情是一篇从上往下读的内容，宽屏限宽居中，不拉满整个平板
                        modifier = Modifier.fillMaxSize().readableWidth(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = glassTop + 12.dp, bottom = 12.dp)
                    ) {
                        // 基本信息卡
                        item(key = "info") { ActivityInfoCard(d) }

                        // 正文（HTML 去标签后展示）
                        if (!d.description.isNullOrBlank()) {
                            item(key = "desc") {
                                val plainText = remember(d.description) {
                                    val doc = Jsoup.parse(d.description!!)
                                    doc.select("br").forEach { it.before("\n") }
                                    doc.select("p").forEach { it.after("\n") }
                                    doc.body()?.wholeOwnText()?.trim()?.ifBlank { null }
                                        ?: doc.text()
                                }
                                SectionHeader(if (d.type == LmsActivityType.HOMEWORK) "作业描述" else "内容")
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
                                UploadCard(upload, context, api, activityClosed = d.isClosed)
                            }
                        }

                        // 作业信息 + 提交记录
                        if (d.type == LmsActivityType.HOMEWORK) {
                            item(key = "hw_meta") { HomeworkMetaCard(d) }

                            val submissions = d.submissionList?.list.orEmpty()
                            if (submissions.isNotEmpty()) {
                                item(key = "sub_header") { SectionHeader("提交记录 (${submissions.size})") }
                                items(submissions, key = { "sub_${it.id}" }) { sub ->
                                    SubmissionCard(sub, context, api, activityClosed = d.isClosed)
                                }
                            } else {
                                // 一条都没有时必须明确说出来。之前这里什么都不渲染，
                                // 用户分不清是「没交」还是「加载失败」。
                                item(key = "sub_empty") { NoSubmissionCard(closed = d.isClosed) }
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
                                                lmsVideoHeaders,
                                                true,
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
                                ReplayVideoCard(
                                    video, context,
                                    onDownload = { enqueueDownload(video, d.title) },
                                ) {
                                    // 单机位播放
                                    if (video.downloadUrl.isNotEmpty()) {
                                        val isInstr = video.label.contains("instructor", true)
                                        onPlayVideo(
                                            "${d.title} - ${video.readableLabel}",
                                            if (isInstr) video.downloadUrl else null,
                                            if (!isInstr) video.downloadUrl else null,
                                            false,
                                            lmsVideoHeaders,
                                            false,
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
                                    Button(
                                        onClick = {
                                            onPlayVideo(
                                                d.title,
                                                instrStream?.src,
                                                encStream?.src ?: d.liveStreams.first().src,
                                                true,
                                                lmsVideoHeaders,
                                                true,
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
                                        onPlayVideo(
                                            "${d.title} - ${stream.readableLabel}",
                                            if (stream.isInstructor) stream.src else null,
                                            if (!stream.isInstructor) stream.src else null,
                                            true,
                                            lmsVideoHeaders,
                                            false,
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
                                                    lmsVideoHeaders,
                                                    true,
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
                                        ReplayVideoCard(
                                            video, context,
                                            onDownload = { enqueueDownload(video, d.title) },
                                        ) {
                                            if (video.downloadUrl.isNotEmpty()) {
                                                val isInstr = video.label.contains("instructor", true)
                                                onPlayVideo(
                                                    "${d.title} - ${video.readableLabel}",
                                                    if (isInstr) video.downloadUrl else null,
                                                    if (!isInstr) video.downloadUrl else null,
                                                    false,
                                                    lmsVideoHeaders,
                                                    false,
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
                if (activity.isClosed) {
                    Spacer(Modifier.width(8.dp))
                    Text("已结束", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(activity.title, fontSize = 18.sp, fontWeight = FontWeight.Bold)

            val timeLine = lmsActivityTimeLine(activity)
            if (timeLine.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Schedule, null, Modifier.size(14.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        timeLine,
                        modifier = Modifier.weight(1f),
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
private fun UploadCard(
    upload: LmsUpload,
    context: Context,
    api: LmsApi? = null,
    activityClosed: Boolean = false,
) {
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
            if (activityClosed) {
                Toast.makeText(context, "活动已结束，学堂已关闭下载", Toast.LENGTH_SHORT).show()
                return@Card
            }
            if (isImage && api != null) {
                if (!isDownloading) {
                    isDownloading = true
                    scope.launch {
                        val bytes = withContext(Dispatchers.IO) { api.downloadUploadBytes(upload) }
                        previewBitmap = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                        if (previewBitmap == null) {
                            Toast.makeText(context, "无法预览", Toast.LENGTH_SHORT).show()
                        }
                        isDownloading = false
                    }
                }
            } else if (api != null) {
                if (!isDownloading) {
                    isDownloading = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            saveUploadToDownloads(context, upload, api)
                        }
                        isDownloading = false
                        Toast.makeText(context, downloadToast(result), Toast.LENGTH_SHORT).show()
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
/**
 * 作业基本信息：截止时间、剩余时间、提交次数、取分规则。
 *
 * 这些字段上游一直有（deadline / submit_times / score_rule 等），此前没解析也没展示，
 * 学生看不到「什么时候截止、还能交几次」——恰恰是最该先看到的东西。
 */
@Composable
internal fun HomeworkMetaCard(activity: LmsActivity) {
    val deadline = activity.deadline
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Schedule,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MiuixTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (deadline.isNullOrBlank()) "未设置截止时间"
                    else "截止 " + formatLmsTime(deadline),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.width(8.dp))
                val remain = deadline?.let { remainingLabel(it) }
                if (activity.isClosed) {
                    Text("已关闭", fontSize = 12.sp, color = MiuixTheme.colorScheme.error)
                } else if (remain != null) {
                    Text(
                        remain,
                        fontSize = 12.sp,
                        color = if (remain == "已过期") MiuixTheme.colorScheme.error
                        else MiuixTheme.colorScheme.primary,
                    )
                }
            }

            val extras = buildList {
                when {
                    activity.nonSubmitTimes -> add("提交次数不限")
                    activity.submitTimes != null && activity.submitTimes > 0 ->
                        add("最多提交 " + activity.submitTimes + " 次")
                }
                if (activity.userSubmitCount > 0) add("已提交 " + activity.userSubmitCount + " 次")
                // 只留跟"我还能不能交、交过几次"直接相关的。
                // 取分规则/可撤回/提交形式这类对学生是套话，占位置不提供决策依据。
            }
            if (extras.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    extras.joinToString(" · "),
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}
/** 没有任何提交记录时的明确说明，避免和「加载失败」混淆 */
@Composable
private fun NoSubmissionCard(closed: Boolean) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Default.AssignmentLate,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (closed) "这次作业你没有提交记录" else "还没有提交记录",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "提交请前往思源学堂网页端",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}
/** 距截止还剩多久；已过返回「已过期」。解析失败返回 null，不猜。 */
private fun remainingLabel(deadlineRaw: String): String? = try {
    val deadline = java.time.ZonedDateTime.parse(deadlineRaw).toInstant()
    val now = java.time.Instant.now()
    if (deadline.isBefore(now)) "已过期" else {
        val minutes = java.time.Duration.between(now, deadline).toMinutes()
        when {
            minutes < 60 -> "剩 " + minutes + " 分钟"
            minutes < 60 * 24 -> "剩 " + (minutes / 60) + " 小时"
            else -> "剩 " + (minutes / (60 * 24)) + " 天"
        }
    }
} catch (_: Exception) {
    null
}
@Composable
private fun SubmissionCard(
    sub: LmsSubmissionItem,
    context: Context,
    api: LmsApi,
    activityClosed: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 草稿要压过 status：上游 status 可能仍是 submitted，
                    // 但 is_draft=true 意味着实际没交出去
                    val statusColor = when {
                        sub.isDraft -> Color(0xFFFF9800)
                        sub.status == "graded" -> Color(0xFF4CAF50)
                        sub.status == "submitted" -> MiuixTheme.colorScheme.primary
                        sub.status == "returned" -> Color(0xFFFF9800)
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
                    UploadCard(upload, context, api, activityClosed = activityClosed)
                }
            }

            // 提交附件
            if (sub.uploads.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("提交附件", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                sub.uploads.forEach { upload ->
                    UploadCard(upload, context, api, activityClosed = activityClosed)
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
                                    val result = withContext(Dispatchers.IO) {
                                        saveToDownloads(context, markedName, upload.type, upload.attachmentUrl, api)
                                    }
                                    downloadingMarked = false
                                    Toast.makeText(
                                        context,
                                        when (result) {
                                            LmsDownloadResult.Ok -> "批改版已保存到下载管理"
                                            LmsDownloadResult.Forbidden -> "活动已结束，学堂已关闭下载"
                                            LmsDownloadResult.Failed -> "批改版下载失败"
                                        },
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
private fun ReplayVideoCard(
    video: LmsReplayVideo,
    context: Context,
    onDownload: (() -> Unit)? = null,
    // onPlay 必须是最后一个参数：调用方用尾随 lambda 传它，
    // 放在 onDownload 之前会让尾随 lambda 绑错形参。
    onPlay: () -> Unit,
) {
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
                    // 不要把 mute 显示成「静音」：上游同时下发 mute 与 is_best_audio，
                    // mute 是「多机位同放时该路要静音」的播放提示（避免回声），
                    // 不代表文件没有音轨——单独播放这一路是有声音的。
                    // 真正有信息量的是哪一路被标为推荐音源。
                    if (video.isBestAudio) {
                        Text("主音源", fontSize = 12.sp, color = MiuixTheme.colorScheme.primary)
                    }
                }
            }
            // 只有拿得到可直接 GET 的地址才给下载入口（HLS 已在调用方排除）
            if (onDownload != null) {
                IconButton(onClick = onDownload) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = "下载",
                        tint = MiuixTheme.colorScheme.primary,
                    )
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
                    // 同上：直播流的 mute 也是多路同放时的静音提示
                    if (!stream.mute) {
                        Text("含音频", fontSize = 12.sp, color = MiuixTheme.colorScheme.primary)
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
