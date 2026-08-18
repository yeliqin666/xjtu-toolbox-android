package com.xjtu.toolbox.classreplay

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import kotlinx.coroutines.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import com.xjtu.toolbox.lms.LmsDownloadRecord
import com.xjtu.toolbox.lms.LmsDownloadStore
import com.xjtu.toolbox.ui.components.EmptyState

private const val TAG = "DownloadManagerScreen"

/**
 * 下载管理页面
 */
@Composable
fun DownloadManagerScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val downloadManager = remember { DownloadManager.getInstance(context) }
    val scope = rememberCoroutineScope()

    var allTasks by remember { mutableStateOf<List<DownloadTaskEntity>>(emptyList()) }
    var lmsDownloads by remember { mutableStateOf<List<LmsDownloadRecord>>(emptyList()) }
    var stats by remember { mutableStateOf<DownloadManager.DownloadStats?>(null) }

    // 多选批量管理。两份选中集合，因为本页有两套彼此独立的数据源：
    // 回放视频任务在 Room（按 id），文件类下载在 MediaStore（按 uri）。
    var isCleanupMode by remember { mutableStateOf(false) }
    val selectedTaskIds = remember { mutableStateListOf<Long>() }
    val selectedFileUris = remember { mutableStateListOf<String>() }
    
    // 显示下载目录信息弹窗
    val showDirInfo = remember { mutableStateOf(false) }

    // 加载任务列表
    fun loadTasks() {
        scope.launch {
            allTasks = withContext(Dispatchers.IO) {
                downloadManager.dao.getAll()
            }
            stats = withContext(Dispatchers.IO) {
                downloadManager.getDownloadStats()
            }
            lmsDownloads = LmsDownloadStore.getAll(context)
        }
    }

    LaunchedEffect(Unit) {
        loadTasks()
    }

    // 监听下载进度
    LaunchedEffect(downloadManager) {
        downloadManager.progressFlow.collect { _ ->
            loadTasks()
        }
    }

    // 退出批量模式时清空两类选择
    DisposableEffect(isCleanupMode) {
        onDispose {
            if (!isCleanupMode) {
                selectedTaskIds.clear()
                selectedFileUris.clear()
            }
        }
    }

    Scaffold(
        topBar = {
            if (isCleanupMode) {
                SmallTopAppBar(
                    title = "批量管理",
                    color = MiuixTheme.colorScheme.surface,
                    navigationIcon = {
                        IconButton(onClick = { isCleanupMode = false }) {
                            Icon(Icons.Default.Close, contentDescription = "取消清理")
                        }
                    },
                    actions = {
                        // 全选覆盖两类数据源：回放任务（Room）+ 文件类下载（MediaStore）
                        IconButton(onClick = {
                            val allSelected = selectedTaskIds.size == allTasks.size &&
                                selectedFileUris.size == lmsDownloads.size &&
                                (allTasks.isNotEmpty() || lmsDownloads.isNotEmpty())
                            selectedTaskIds.clear()
                            selectedFileUris.clear()
                            if (!allSelected) {
                                selectedTaskIds.addAll(allTasks.map { it.id })
                                selectedFileUris.addAll(lmsDownloads.map { it.uri })
                            }
                        }) {
                            Icon(Icons.Default.SelectAll, contentDescription = "全选/取消")
                        }
                    }
                )
            } else {
                SmallTopAppBar(
                    title = "下载管理",
                    color = MiuixTheme.colorScheme.surface,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        // 信息按钮 - 显示下载目录
                        IconButton(onClick = { showDirInfo.value = true }) {
                            Icon(Icons.Default.Info, contentDescription = "下载目录信息")
                        }
                        // 有任何可管理的条目就显示入口。条件不能只看回放任务，
                        // 否则只下载过文件的用户进不去批量模式。
                        if (allTasks.isNotEmpty() || lmsDownloads.isNotEmpty()) {
                            IconButton(onClick = { isCleanupMode = true }) {
                                Icon(Icons.Default.DeleteSweep, contentDescription = "批量管理")
                            }
                        }
                    }
                )
            }
        },
        bottomBar = {
            val selectedTotal = selectedTaskIds.size + selectedFileUris.size
            if (isCleanupMode && selectedTotal > 0) {
                var deleteFiles by remember { mutableStateOf(true) }
                Surface(
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
                    color = MiuixTheme.colorScheme.surface,
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "已选 $selectedTotal 项",
                                style = MiuixTheme.textStyles.body2,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f),
                            )
                            if (selectedTaskIds.isNotEmpty()) {
                                Row(
                                    modifier = Modifier.clickable { deleteFiles = !deleteFiles },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(
                                        state = if (deleteFiles) androidx.compose.ui.state.ToggleableState.On
                                        else androidx.compose.ui.state.ToggleableState.Off,
                                        onClick = { deleteFiles = !deleteFiles },
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "同时删除文件",
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = {
                                scope.launch {
                                    selectedTaskIds.toList().forEach { id ->
                                        downloadManager.deleteTask(id, deleteFile = deleteFiles)
                                    }
                                    withContext(Dispatchers.IO) {
                                        selectedFileUris.toList().forEach { uri ->
                                            runCatching {
                                                context.contentResolver.delete(android.net.Uri.parse(uri), null, null)
                                            }
                                            LmsDownloadStore.remove(context, uri)
                                        }
                                    }
                                    selectedTaskIds.clear()
                                    selectedFileUris.clear()
                                    loadTasks()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("删除选中")
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (allTasks.isEmpty() && lmsDownloads.isEmpty()) {
                EmptyState(
                    title = "暂无下载内容",
                    subtitle = "思源课件和课堂回放会统一显示在这里",
                    icon = Icons.Outlined.CloudOff,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                val groupedDownloads = lmsDownloads.groupBy { it.category }
                val fileGroups = listOf(
                    LmsDownloadStore.CATEGORY_TRANSCRIPT to "电子成绩单",
                    LmsDownloadStore.CATEGORY_LMS to "思源文件",
                    LmsDownloadStore.CATEGORY_ZYXF to "仲英学辅资料",
                    LmsDownloadStore.CATEGORY_OTHER to "其他文件",
                )
                LazyColumn(
                    Modifier.fillMaxSize().overScrollVertical(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (stats != null && stats!!.activeCount > 0 && !isCleanupMode) {
                        item(key = "global_controls") {
                            GlobalControls(
                                stats = stats!!,
                                downloadManager = downloadManager,
                                onRefresh = { loadTasks() },
                            )
                        }
                    }

                    if (allTasks.isNotEmpty()) {
                        item(key = "replay_title") {
                            DownloadSectionHeader("课堂回放", allTasks.size)
                        }
                        items(allTasks, key = { it.id }) { task ->
                            DownloadTaskCard(
                                task = task,
                                isCleanupMode = isCleanupMode,
                                isSelected = selectedTaskIds.contains(task.id),
                                onToggleSelection = {
                                    if (selectedTaskIds.contains(task.id)) {
                                        selectedTaskIds.remove(task.id)
                                    } else {
                                        selectedTaskIds.add(task.id)
                                    }
                                },
                                onPause = {
                                    scope.launch {
                                        downloadManager.pauseDownload(task.id)
                                        loadTasks()
                                    }
                                },
                                onResume = {
                                    downloadManager.resumeDownload(task.id)
                                    loadTasks()
                                },
                                onCancel = {
                                    scope.launch {
                                        downloadManager.cancelDownload(task.id)
                                        loadTasks()
                                    }
                                },
                                onPlay = { openReplayFile(context, task) },
                            )
                        }
                    }

                    fileGroups.forEach { (category, title) ->
                        val records = groupedDownloads[category].orEmpty()
                        if (records.isEmpty()) return@forEach
                        item(key = "${category}_title") {
                            DownloadSectionHeader(title, records.size)
                        }
                        items(records, key = { "${category}_${it.uri}" }) { record ->
                            LmsDownloadCard(
                                record = record,
                                isCleanupMode = isCleanupMode,
                                isSelected = record.uri in selectedFileUris,
                                onToggleSelect = {
                                    if (record.uri in selectedFileUris) {
                                        selectedFileUris.remove(record.uri)
                                    } else {
                                        selectedFileUris.add(record.uri)
                                    }
                                },
                                onOpen = { openDownloadedFile(context, record) },
                                onDelete = {
                                    runCatching {
                                        context.contentResolver.delete(android.net.Uri.parse(record.uri), null, null)
                                    }
                                    LmsDownloadStore.remove(context, record.uri)
                                    loadTasks()
                                },
                            )
                        }
                    }
                }
            }

        // 必须在 Scaffold 的 content 内：OverlayBottomSheet 靠 Scaffold 提供的
        // LocalDialogStates 宿主渲染，写在 Scaffold 外面会静默不显示。
    // 下载目录信息弹窗
    DownloadDirInfoDialog(show = showDirInfo)
        }
    }
    
}

@Composable
private fun DownloadSectionHeader(title: String, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MiuixTheme.textStyles.subtitle,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        Text(
            "$count",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun SelectMark(selected: Boolean) {
    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
        if (selected) {
            Icon(
                Icons.Default.CheckBox,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        } else {
            Box(
                Modifier
                    .size(18.dp)
                    .border(2.dp, MiuixTheme.colorScheme.outline, RoundedCornerShape(4.dp))
            )
        }
    }
}

@Composable
private fun LeadingIconWell(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color = MiuixTheme.colorScheme.primary,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = tint.copy(alpha = 0.12f),
        modifier = Modifier.size(42.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun MetaChip(text: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MiuixTheme.colorScheme.surface,
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun LmsDownloadCard(
    record: LmsDownloadRecord,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    isCleanupMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelect: () -> Unit = {},
) {
    Card(
        onClick = if (isCleanupMode) onToggleSelect else onOpen,
        pressFeedbackType = PressFeedbackType.Sink,
        cornerRadius = 16.dp,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isCleanupMode) {
                SelectMark(isSelected)
                Spacer(Modifier.width(10.dp))
            }
            LeadingIconWell(
                icon = when (record.category) {
                    LmsDownloadStore.CATEGORY_TRANSCRIPT -> Icons.Default.PictureAsPdf
                    LmsDownloadStore.CATEGORY_ZYXF -> Icons.Default.MenuBook
                    else -> Icons.Default.Description
                }
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    record.name,
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "${record.categoryLabel()} · ${formatTimestamp(record.savedAt)}",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            if (!isCleanupMode) {
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = "删除文件",
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}

private fun LmsDownloadRecord.categoryLabel(): String = when (category) {
    LmsDownloadStore.CATEGORY_TRANSCRIPT -> "成绩单"
    LmsDownloadStore.CATEGORY_LMS -> "思源文件"
    LmsDownloadStore.CATEGORY_ZYXF -> "仲英学辅"
    LmsDownloadStore.CATEGORY_OTHER -> "其他"
    else -> "已下载"
}

@Composable
private fun GlobalControls(
    stats: DownloadManager.DownloadStats,
    downloadManager: DownloadManager,
    onRefresh: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "下载进行中",
                        style = MiuixTheme.textStyles.subtitle,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        buildString {
                            if (stats.downloadingCount > 0) append("${stats.downloadingCount} 个下载中")
                            val paused = stats.activeCount - stats.downloadingCount
                            if (paused > 0) {
                                if (isNotEmpty()) append(" · ")
                                append("$paused 个已暂停")
                            }
                        }.ifBlank { "${stats.activeCount} 个任务" },
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                TextButton(
                    text = "取消全部",
                    onClick = {
                        scope.launch {
                            downloadManager.cancelAll()
                            delay(300)
                            onRefresh()
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(color = Color.Transparent),
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            downloadManager.pauseAll()
                            delay(300)
                            onRefresh()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.secondaryContainer),
                ) {
                    Text("全部暂停", color = MiuixTheme.colorScheme.onSecondaryContainer)
                }
                Button(
                    onClick = {
                        downloadManager.resumeAll()
                        scope.launch {
                            delay(500)
                            onRefresh()
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("全部继续")
                }
            }
        }
    }
}

@Composable
private fun DownloadTaskCard(
    task: DownloadTaskEntity,
    isCleanupMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelection: () -> Unit = {},
    onPause: () -> Unit = {},
    onResume: () -> Unit = {},
    onCancel: () -> Unit = {},
    onPlay: () -> Unit = {},
) {
    val statusTint = when (task.status) {
        "downloading" -> MiuixTheme.colorScheme.primary
        "completed" -> MiuixTheme.colorScheme.primary
        "failed" -> MiuixTheme.colorScheme.error
        else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    Card(
        onClick = if (isCleanupMode) onToggleSelection else {
            when (task.status) {
                "completed" -> onPlay
                "paused", "pending", "failed" -> onResume
                "downloading" -> onPause
                else -> ({})
            }
        },
        pressFeedbackType = PressFeedbackType.Sink,
        cornerRadius = 16.dp,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isCleanupMode) {
                    SelectMark(isSelected)
                    Spacer(Modifier.width(10.dp))
                }
                LeadingIconWell(
                    icon = when (task.status) {
                        "completed" -> Icons.Default.PlayCircle
                        "failed" -> Icons.Outlined.ErrorOutline
                        "paused" -> Icons.Default.PauseCircle
                        else -> Icons.Default.Download
                    },
                    tint = statusTint,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        task.activityTitle,
                        style = MiuixTheme.textStyles.body1,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MetaChip(if (task.cameraType == "instructor") "教师画面" else "电脑屏幕")
                        Text(
                            formatTimestamp(task.createTime),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                if (!isCleanupMode) {
                    when (task.status) {
                        "downloading" -> {
                            IconButton(onClick = onPause) {
                                Icon(Icons.Default.Pause, contentDescription = "暂停")
                            }
                            IconButton(onClick = onCancel) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "取消",
                                    tint = MiuixTheme.colorScheme.error,
                                )
                            }
                        }
                        "paused", "pending" -> {
                            IconButton(onClick = onResume) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "继续")
                            }
                            IconButton(onClick = onCancel) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "取消",
                                    tint = MiuixTheme.colorScheme.error,
                                )
                            }
                        }
                        "failed" -> {
                            IconButton(onClick = onResume) {
                                Icon(Icons.Default.Refresh, contentDescription = "重试")
                            }
                            IconButton(onClick = onCancel) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "取消",
                                    tint = MiuixTheme.colorScheme.error,
                                )
                            }
                        }
                        "completed" -> {
                            IconButton(onClick = onPlay) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = "播放",
                                    tint = MiuixTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
            if (task.status == "downloading" || task.status == "paused") {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = task.progress.coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxWidth(),
                    height = 4.dp,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    buildString {
                        append("${(task.progress * 100).toInt()}%")
                        if (task.downloadedSize > 0) {
                            append(" · ")
                            append(formatFileSize(task.downloadedSize))
                            if (task.fileSize > 0) {
                                append(" / ")
                                append(formatFileSize(task.fileSize))
                            }
                        }
                        if (task.status == "downloading" && task.downloadSpeed > 0) {
                            append(" · ")
                            append("${formatFileSize(task.downloadSpeed)}/s")
                        }
                        if (task.status == "paused") append(" · 已暂停")
                    },
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.primary,
                )
            } else if (task.status == "completed") {
                Spacer(Modifier.height(4.dp))
                Text(
                    formatFileSize(task.downloadedSize),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            } else if (task.status == "failed") {
                Spacer(Modifier.height(4.dp))
                Text(
                    task.errorMessage?.takeIf { it.isNotBlank() } ?: "下载失败",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 格式化文件大小
 */
private fun openDownloadedFile(context: Context, record: LmsDownloadRecord) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(
                android.net.Uri.parse(record.uri),
                record.mimeType.ifBlank { "*/*" },
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "打开文件"))
    }.onFailure {
        android.widget.Toast.makeText(context, "文件已被移动或删除", android.widget.Toast.LENGTH_SHORT).show()
    }
}

private fun openReplayFile(context: Context, task: DownloadTaskEntity) {
    try {
        val file = File(task.filePath)
        if (!file.exists()) {
            android.widget.Toast.makeText(context, "视频文件不存在", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val mime = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase())
            ?: "*/*"
        try {
            val uri = android.net.Uri.fromFile(file)
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, mime)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    "选择播放器",
                )
            )
        } catch (_: Exception) {
            try {
                val contentUri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
                context.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(contentUri, mime)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        },
                        "打开文件",
                    )
                )
            } catch (_: Exception) {
                android.widget.Toast.makeText(
                    context,
                    "无法启动播放器，请使用系统文件管理器查看",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Play video error", e)
        android.widget.Toast.makeText(context, "无法播放视频: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024L * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }
}

/**
 * 格式化时间戳
 */
private fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

/**
 * 下载目录信息弹窗
 */
@Composable
private fun DownloadDirInfoDialog(show: MutableState<Boolean>) {
    BackHandler(enabled = show.value) { show.value = false }
    OverlayDialog(
        show = show.value,
        title = "下载目录信息",
        onDismissRequest = { show.value = false }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .overScrollVertical()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "保存位置",
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Bold
            )
            Text(
                "文件：${LmsDownloadStore.publicDisplayPath()}\n" +
                    "回放视频：${DownloadManager.getInstance(LocalContext.current).videoDirPath}",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
            Text(
                "• 成绩单、思源课件、资料站文件统一保存到公共下载目录，系统文件管理器可见，卸载 App 不会丢",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            Text(
                "• 课堂回放视频保存在应用专属目录（无需存储权限），支持外部播放器播放；卸载 App 会一并清除",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            Text(
                "• 删除记录仅删除数据库记录，不删除视频文件",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            Text(
                "• 清理模式下可勾选\"同时删除文件\"彻底删除",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            Spacer(Modifier.height(4.dp))
            TextButton(
                text = "知道了",
                onClick = { show.value = false },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
