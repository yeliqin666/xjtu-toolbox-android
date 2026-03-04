package com.xjtu.toolbox.classreplay

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val TAG = "VideoPlayer"

// ════════════════════════════════════════
//  显示模式
// ════════════════════════════════════════

/** 画面模式 */
enum class DisplayMode(val label: String) {
    SINGLE("单画面"),
    DUAL("双画面")
}

/** 单画面时 — 选哪个视频源 */
enum class VideoSource(val label: String) {
    INSTRUCTOR("教师直播"),
    ENCODER("电脑屏幕")
}

/** 音频选择 */
enum class AudioSource(val label: String) {
    INSTRUCTOR("教师音频"),
    ENCODER("电脑音频"),
    BOTH("双音轨"),
    MUTE("静音")
}

// ════════════════════════════════════════
//  入口 1 — CLASS 回放（从 activityId 加载）
// ════════════════════════════════════════

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(
    login: ClassLogin,
    activityId: Int,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity

    var replayDetail by remember { mutableStateOf<ReplayDetail?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var instructorUrl by remember { mutableStateOf<String?>(null) }
    var encoderUrl by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // 记录进入前的原始 orientation，退出时恢复
    val prevOrientation = remember {
        activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    // 首次默认横屏
    LaunchedEffect(Unit) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }
    // 退出页面时恢复原始 orientation
    DisposableEffect(Unit) {
        onDispose {
            activity?.requestedOrientation = prevOrientation
        }
    }

    // 系统返回键
    BackHandler { onBack() }

    // 加载数据
    LaunchedEffect(activityId) {
        scope.launch {
            isLoading = true
            errorMsg = null
            try {
                val detail = withContext(Dispatchers.IO) {
                    fetchReplayDetail(login, activityId)
                }
                if (detail == null || detail.replayVideos.isEmpty()) {
                    errorMsg = "未找到回放视频"
                    isLoading = false
                    return@launch
                }
                replayDetail = detail

                withContext(Dispatchers.IO) {
                    val instrVid = detail.replayVideos.find { it.cameraType == "instructor" }
                    val encVid = detail.replayVideos.find { it.cameraType == "encoder" }

                    val dInstr = async { instrVid?.let { resolveVideoUrl(login, it.url) } }
                    val dEnc = async { encVid?.let { resolveVideoUrl(login, it.url) } }
                    instructorUrl = dInstr.await()
                    encoderUrl = dEnc.await()
                }

                if (instructorUrl == null && encoderUrl == null) {
                    errorMsg = "无法获取视频播放地址"
                }
            } catch (e: Exception) {
                Log.e(TAG, "load replay error", e)
                errorMsg = "加载失败: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    when {
        isLoading -> {
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(8.dp))
                    Text("加载回放...", color = Color.White, fontSize = 14.sp)
                    Spacer(Modifier.height(16.dp))
                    TextButton(text = "取消", onClick = onBack)
                }
            }
        }
        errorMsg != null -> {
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(errorMsg ?: "", color = Color(0xFFEF5350), fontSize = 15.sp)
                    Spacer(Modifier.height(12.dp))
                    TextButton(text = "返回", onClick = onBack)
                }
            }
        }
        else -> {
            DualVideoPlayer(
                instructorUrl = instructorUrl,
                encoderUrl = encoderUrl,
                title = replayDetail?.title ?: "回放",
                onBack = onBack
            )
        }
    }
}

// ════════════════════════════════════════
//  入口 2 — 通用（直接传入 URL，支持 HLS/MP4）
// ════════════════════════════════════════

/**
 * 通用视频播放器入口，直接传入教师/屏幕 URL。
 * 适用于 LMS 思源学堂直播（HLS m3u8）和录播。
 *
 * @param instructorUrl 教师画面 URL（可选）
 * @param encoderUrl    电脑屏幕 URL（可选）
 * @param title         视频标题
 * @param headers       额外请求头（如 Origin / Referer）
 * @param isLive        是否直播模式（隐藏进度条/快进快退）
 * @param onBack        返回回调
 */
@OptIn(UnstableApi::class)
@Composable
fun DirectVideoPlayerScreen(
    instructorUrl: String?,
    encoderUrl: String?,
    title: String,
    headers: Map<String, String> = emptyMap(),
    isLive: Boolean = false,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity

    // 横屏
    val prevOrientation = remember {
        activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    LaunchedEffect(Unit) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }
    DisposableEffect(Unit) {
        onDispose { activity?.requestedOrientation = prevOrientation }
    }

    BackHandler { onBack() }

    if (instructorUrl == null && encoderUrl == null) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("没有可用的视频流", color = Color(0xFFEF5350), fontSize = 15.sp)
                Spacer(Modifier.height(12.dp))
                TextButton(text = "返回", onClick = onBack)
            }
        }
        return
    }

    DualVideoPlayer(
        instructorUrl = instructorUrl,
        encoderUrl = encoderUrl,
        title = title,
        headers = headers,
        isLive = isLive,
        onBack = onBack
    )
}

// ════════════════════════════════════════
//  双源视频播放器核心
// ════════════════════════════════════════

@OptIn(UnstableApi::class)
@Composable
private fun DualVideoPlayer(
    instructorUrl: String?,
    encoderUrl: String?,
    title: String,
    headers: Map<String, String> = emptyMap(),
    isLive: Boolean = false,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val config = LocalConfiguration.current
    val isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE

    // 跟踪用户选择的 orientation（默认横屏），避免 config change 后被重置
    var userOrientation by rememberSaveable { mutableIntStateOf(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) }
    DisposableEffect(userOrientation) {
        activity?.requestedOrientation = userOrientation
        onDispose {}
    }

    // 构建带请求头的 MediaItem
    fun buildMediaItem(url: String): MediaItem {
        return if (headers.isNotEmpty()) {
            MediaItem.Builder()
                .setUri(url)
                .setRequestMetadata(
                    MediaItem.RequestMetadata.Builder().build()
                )
                .build()
        } else {
            MediaItem.fromUri(url)
        }
    }

    // 构建带请求头的 DataSource.Factory
    val dataSourceFactory = remember(headers) {
        if (headers.isNotEmpty()) {
            val httpFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(headers)
            httpFactory
        } else null
    }

    // 播放器实例
    val instructorPlayer = remember {
        instructorUrl?.let { url ->
            val builder = ExoPlayer.Builder(context)
            if (dataSourceFactory != null) {
                val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory)
                builder.setMediaSourceFactory(mediaSourceFactory)
            }
            builder.build().apply {
                setMediaItem(buildMediaItem(url))
                prepare()
            }
        }
    }
    val encoderPlayer = remember {
        encoderUrl?.let { url ->
            val builder = ExoPlayer.Builder(context)
            if (dataSourceFactory != null) {
                val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory)
                builder.setMediaSourceFactory(mediaSourceFactory)
            }
            builder.build().apply {
                setMediaItem(buildMediaItem(url))
                prepare()
            }
        }
    }

    val hasInstructor = instructorPlayer != null
    val hasEncoder = encoderPlayer != null
    val hasBoth = hasInstructor && hasEncoder

    // 状态
    var displayMode by remember { mutableStateOf(DisplayMode.SINGLE) }
    var videoSource by remember {
        mutableStateOf(if (hasEncoder) VideoSource.ENCODER else VideoSource.INSTRUCTOR)
    }
    var audioSource by remember {
        mutableStateOf(if (hasEncoder) AudioSource.ENCODER else AudioSource.INSTRUCTOR)
    }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var isPlaying by remember { mutableStateOf(true) }
    var showControls by remember { mutableStateOf(true) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var showSpeedMenu by remember { mutableStateOf(false) }
    var showSourceMenu by remember { mutableStateOf(false) }

    // 主播放器（用于进度追踪）
    val primaryPlayer = when {
        displayMode == DisplayMode.DUAL -> encoderPlayer ?: instructorPlayer
        videoSource == VideoSource.INSTRUCTOR && hasInstructor -> instructorPlayer
        else -> encoderPlayer ?: instructorPlayer
    }

    fun syncPlayers() {
        val pos = primaryPlayer?.currentPosition ?: 0
        instructorPlayer?.seekTo(pos)
        encoderPlayer?.seekTo(pos)
    }

    fun updateAudioVolumes() {
        val instrVol = when (audioSource) {
            AudioSource.INSTRUCTOR -> 1f
            AudioSource.ENCODER -> 0f
            AudioSource.BOTH -> 0.7f
            AudioSource.MUTE -> 0f
        }
        val encVol = when (audioSource) {
            AudioSource.INSTRUCTOR -> 0f
            AudioSource.ENCODER -> 1f
            AudioSource.BOTH -> 0.7f
            AudioSource.MUTE -> 0f
        }
        instructorPlayer?.volume = instrVol
        encoderPlayer?.volume = encVol
    }

    // 初始化
    LaunchedEffect(Unit) {
        updateAudioVolumes()
        instructorPlayer?.playWhenReady = true
        encoderPlayer?.playWhenReady = true
    }

    LaunchedEffect(audioSource) { updateAudioVolumes() }

    LaunchedEffect(playbackSpeed) {
        val params = PlaybackParameters(playbackSpeed)
        instructorPlayer?.playbackParameters = params
        encoderPlayer?.playbackParameters = params
    }

    // 进度追踪
    LaunchedEffect(primaryPlayer) {
        while (true) {
            primaryPlayer?.let {
                currentPosition = it.currentPosition
                duration = it.duration.coerceAtLeast(0)
                isPlaying = it.isPlaying
            }
            delay(500)
        }
    }

    // 自动隐藏控制栏
    LaunchedEffect(showControls, isPlaying) {
        if (showControls && isPlaying) {
            delay(4000)
            showControls = false
        }
    }

    // 释放
    DisposableEffect(Unit) {
        onDispose {
            instructorPlayer?.release()
            encoderPlayer?.release()
        }
    }

    // ── UI ──────────────────────────────

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                showControls = !showControls
                showSpeedMenu = false
                showSourceMenu = false
            }
    ) {
        // 视频画面区域
        when {
            displayMode == DisplayMode.DUAL && hasBoth -> {
                if (isLandscape) {
                    Row(Modifier.fillMaxSize()) {
                        VideoPanel(instructorPlayer!!, "教师直播", Modifier.weight(1f).fillMaxHeight())
                        Spacer(Modifier.width(2.dp))
                        VideoPanel(encoderPlayer!!, "电脑屏幕", Modifier.weight(1f).fillMaxHeight())
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        VideoPanel(instructorPlayer!!, "教师直播", Modifier.weight(1f).fillMaxWidth())
                        Spacer(Modifier.height(2.dp))
                        VideoPanel(encoderPlayer!!, "电脑屏幕", Modifier.weight(1f).fillMaxWidth())
                    }
                }
            }
            else -> {
                // 单画面
                val player = when {
                    videoSource == VideoSource.INSTRUCTOR && hasInstructor -> instructorPlayer!!
                    hasEncoder -> encoderPlayer!!
                    hasInstructor -> instructorPlayer!!
                    else -> return@Box
                }
                VideoPanel(player, null, Modifier.fillMaxSize())
            }
        }

        // 控制层
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))) {
                // 顶部栏
                Row(
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
                    }
                    Text(
                        title,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    // 横竖屏切换
                    IconButton(onClick = {
                        userOrientation =
                            if (isLandscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                            else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    }) {
                        Icon(Icons.Default.ScreenRotation, contentDescription = "旋转屏幕", tint = Color.White)
                    }
                }

                // 中间：快退 / 播放暂停 / 快进
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    if (!isLive) {
                        IconButton(onClick = {
                            val newPos = (currentPosition - 10_000).coerceAtLeast(0)
                            instructorPlayer?.seekTo(newPos)
                            encoderPlayer?.seekTo(newPos)
                            currentPosition = newPos
                        }) {
                            Icon(Icons.Default.Replay10, contentDescription = "快退10秒",
                                tint = Color.White, modifier = Modifier.size(36.dp))
                        }
                    }

                    IconButton(
                        onClick = {
                            if (isPlaying) {
                                instructorPlayer?.pause()
                                encoderPlayer?.pause()
                            } else {
                                syncPlayers()
                                instructorPlayer?.play()
                                encoderPlayer?.play()
                            }
                            isPlaying = !isPlaying
                            showControls = true
                        },
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "暂停" else "播放",
                            tint = Color.White,
                            modifier = Modifier.size(48.dp)
                        )
                    }

                    if (!isLive) {
                        IconButton(onClick = {
                            val newPos = (currentPosition + 10_000).coerceAtMost(duration)
                            instructorPlayer?.seekTo(newPos)
                            encoderPlayer?.seekTo(newPos)
                            currentPosition = newPos
                        }) {
                            Icon(Icons.Default.Forward10, contentDescription = "快进10秒",
                                tint = Color.White, modifier = Modifier.size(36.dp))
                        }
                    }
                }

                // 底部控制栏
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    if (!isLive) {
                        // 进度条（录播模式）
                        Slider(
                            value = if (duration > 0) currentPosition.toFloat() / duration else 0f,
                            onValueChange = { fraction ->
                                val newPos = (fraction * duration).toLong()
                                instructorPlayer?.seekTo(newPos)
                                encoderPlayer?.seekTo(newPos)
                                currentPosition = newPos
                            }
                        )
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (isLive) {
                            // 直播模式显示 LIVE 标记
                            Box(
                                Modifier
                                    .background(Color(0xFFE53935), shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("● LIVE", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Text(
                                "${formatTime(currentPosition)} / ${formatTime(duration)}",
                                color = Color.White.copy(alpha = 0.9f),
                                fontSize = 12.sp
                            )
                        }

                        Spacer(Modifier.weight(1f))

                        // 双画面切换
                        if (hasBoth) {
                            TextButton(
                                text = displayMode.label,
                                onClick = {
                                    displayMode = when (displayMode) {
                                        DisplayMode.SINGLE -> DisplayMode.DUAL
                                        DisplayMode.DUAL -> DisplayMode.SINGLE
                                    }
                                    syncPlayers()
                                    showSpeedMenu = false; showSourceMenu = false
                                },
                                colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColors(
                                    color = Color.Transparent
                                )
                            )
                        }

                        // 倍速（非直播模式）
                        if (!isLive) {
                            TextButton(
                                text = "${playbackSpeed}x",
                                onClick = { showSpeedMenu = !showSpeedMenu; showSourceMenu = false },
                                colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColors(
                                    color = Color.Transparent
                                )
                            )
                        }

                        // 源切换
                        if (hasBoth) {
                            TextButton(
                                text = "源",
                                onClick = { showSourceMenu = !showSourceMenu; showSpeedMenu = false },
                                colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColors(
                                    color = Color.Transparent
                                )
                            )
                        }
                    }
                }

                // 弹出菜单
                if (showSpeedMenu) {
                    SpeedMenu(
                        currentSpeed = playbackSpeed,
                        onSpeedSelected = { playbackSpeed = it; showSpeedMenu = false },
                        onDismiss = { showSpeedMenu = false },
                        modifier = Modifier.align(Alignment.BottomEnd)
                            .navigationBarsPadding()
                            .padding(bottom = 80.dp, end = 16.dp)
                    )
                }
                if (showSourceMenu) {
                    SourceMenu(
                        displayMode = displayMode,
                        videoSource = videoSource,
                        audioSource = audioSource,
                        hasInstructor = hasInstructor,
                        hasEncoder = hasEncoder,
                        onVideoSourceChanged = { src ->
                            videoSource = src
                            syncPlayers()
                        },
                        onAudioSourceChanged = { audioSource = it },
                        onDismiss = { showSourceMenu = false },
                        modifier = Modifier.align(Alignment.BottomEnd)
                            .navigationBarsPadding()
                            .padding(bottom = 80.dp, end = 16.dp)
                    )
                }
            }
        }
    }
}

// ════════════════════════════════════════
//  视频面板 (PlayerView 自动保持正确宽高比)
// ════════════════════════════════════════

@OptIn(UnstableApi::class)
@Composable
private fun VideoPanel(
    player: ExoPlayer,
    label: String?,
    modifier: Modifier = Modifier
) {
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    this.player = player
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                view.player = player
            }
        )
        if (label != null) {
            Text(
                label,
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.4f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

// ════════════════════════════════════════
//  倍速菜单
// ════════════════════════════════════════

@Composable
private fun SpeedMenu(
    currentSpeed: Float,
    onSpeedSelected: (Float) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)

    Card(modifier = modifier.width(120.dp)) {
        Column(Modifier.padding(8.dp)) {
            Text("播放速度", fontSize = 13.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            speeds.forEach { speed ->
                val isSelected = speed == currentSpeed
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSpeedSelected(speed) }
                        .background(
                            if (isSelected) MiuixTheme.colorScheme.primary.copy(alpha = 0.1f)
                            else Color.Transparent
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        "${speed}x",
                        fontSize = 14.sp,
                        color = if (isSelected) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.onSurface,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

// ════════════════════════════════════════
//  源切换菜单
// ════════════════════════════════════════

@Composable
private fun SourceMenu(
    displayMode: DisplayMode,
    videoSource: VideoSource,
    audioSource: AudioSource,
    hasInstructor: Boolean,
    hasEncoder: Boolean,
    onVideoSourceChanged: (VideoSource) -> Unit,
    onAudioSourceChanged: (AudioSource) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.width(180.dp)) {
        Column(Modifier.padding(8.dp)) {
            if (displayMode == DisplayMode.SINGLE) {
                Text("视频源", fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                VideoSource.entries.forEach { src ->
                    val enabled = when (src) {
                        VideoSource.INSTRUCTOR -> hasInstructor
                        VideoSource.ENCODER -> hasEncoder
                    }
                    val isSelected = src == videoSource
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .then(if (enabled) Modifier.clickable { onVideoSourceChanged(src) } else Modifier)
                            .background(
                                if (isSelected) MiuixTheme.colorScheme.primary.copy(alpha = 0.1f)
                                else Color.Transparent
                            )
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            src.label,
                            fontSize = 14.sp,
                            color = when {
                                !enabled -> Color.Gray.copy(alpha = 0.5f)
                                isSelected -> MiuixTheme.colorScheme.primary
                                else -> MiuixTheme.colorScheme.onSurface
                            }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            Text("音频源", fontSize = 13.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            AudioSource.entries.forEach { src ->
                val enabled = when (src) {
                    AudioSource.INSTRUCTOR -> hasInstructor
                    AudioSource.ENCODER -> hasEncoder
                    AudioSource.BOTH -> hasInstructor && hasEncoder
                    AudioSource.MUTE -> true
                }
                val isSelected = src == audioSource
                Box(
                    Modifier
                        .fillMaxWidth()
                        .then(if (enabled) Modifier.clickable { onAudioSourceChanged(src) } else Modifier)
                        .background(
                            if (isSelected) MiuixTheme.colorScheme.primary.copy(alpha = 0.1f)
                            else Color.Transparent
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        src.label,
                        fontSize = 14.sp,
                        color = when {
                            !enabled -> Color.Gray.copy(alpha = 0.5f)
                            isSelected -> MiuixTheme.colorScheme.primary
                            else -> MiuixTheme.colorScheme.onSurface
                        }
                    )
                }
            }
        }
    }
}

// ════════════════════════════════════════
//  工具
// ════════════════════════════════════════

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}
