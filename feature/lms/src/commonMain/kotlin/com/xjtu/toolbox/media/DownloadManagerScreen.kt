package com.xjtu.toolbox.media

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.platform.BackHandler
import com.xjtu.toolbox.ui.adaptive.readableWidth
import com.xjtu.toolbox.ui.components.EmptyState
import com.xjtu.toolbox.ui.glass.GlassTopAppBar
import com.xjtu.toolbox.ui.glass.glassBarColor
import com.xjtu.toolbox.ui.glass.glassSource
import com.xjtu.toolbox.ui.glass.glassTop
import com.xjtu.toolbox.ui.glass.glassTopBar
import com.xjtu.toolbox.ui.glass.rememberPageGlass
import com.xjtu.toolbox.ui.glass.withoutTop
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * 下载管理页面（共享屏）。
 *
 * @param files 另一类下载（成绩单 / 思源课件 / 资料站文件）。桌面端没有那张登记表
 *   ⇒ 传 null，屏上只列排队下载的回放视频（如实，不是列表被吞了）。
 * @param onPlayTask 播放一个**已下载完**的回放文件：Android = 外部播放器（Media3 那套只播流，
 *   播本地文件走的还是 `ACTION_VIEW`），桌面 = 系统播放器。
 */
@Composable
fun DownloadManagerScreen(
    manager: DownloadManager,
    onBack: () -> Unit,
    dirInfo: DownloadDirInfo,
    files: DownloadedFilesHost? = null,
    onPlayTask: (DownloadTask) -> Unit = {},
) {
    val scope = rememberCoroutineScope()

    var allTasks by remember { mutableStateOf<List<DownloadTask>>(emptyList()) }
    var stats by remember { mutableStateOf<DownloadStats?>(null) }
    var fileDownloads by remember { mutableStateOf<List<DownloadedFile>>(emptyList()) }

    // 多选批量管理。两份选中集合，因为本页有两套彼此独立的数据源：
    // 回放视频任务在存储里（按 id），文件类下载在宿主的登记表里（按 uri）。
    var isCleanupMode by remember { mutableStateOf(false) }
    val selectedTaskIds = remember { mutableStateListOf<Long>() }
    val selectedFileUris = remember { mutableStateListOf<String>() }

    // 显示下载目录信息弹窗
    val showDirInfo = remember { mutableStateOf(false) }
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    // 玻璃顶栏（经典风格下为 null，一切照旧），用法见 ui/glass/GlassTopBar.kt。
    // 批量管理模式下换成 SmallTopAppBar，同样要玻璃化。
    val glass = rememberPageGlass()

    // 加载任务列表（Room 的挂起查询自带线程，无需切 IO）。
    fun loadTasks() {
        scope.launch {
            allTasks = manager.getAllTasks()
            stats = manager.getDownloadStats()
            fileDownloads = files?.list() ?: emptyList()
        }
    }

    LaunchedEffect(Unit) {
        loadTasks()
    }

    // 监听下载进度
    LaunchedEffect(manager) {
        manager.progressFlow.collect { _ ->
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
                    color = glassBarColor(glass),
                    modifier = Modifier.glassTopBar(glass),
                    navigationIcon = {
                        IconButton(onClick = { isCleanupMode = false }) {
                            Icon(Icons.Default.Close, contentDescription = "取消清理")
                        }
                    },
                    actions = {
                        // 全选覆盖两类数据源：回放任务（存储）+ 文件类下载（宿主登记表）
                        IconButton(onClick = {
                            val allSelected = selectedTaskIds.size == allTasks.size &&
                                selectedFileUris.size == fileDownloads.size &&
                                (allTasks.isNotEmpty() || fileDownloads.isNotEmpty())
                            selectedTaskIds.clear()
                            selectedFileUris.clear()
                            if (!allSelected) {
                                selectedTaskIds.addAll(allTasks.map { it.id })
                                selectedFileUris.addAll(fileDownloads.map { it.uri })
                            }
                        }) {
                            Icon(Icons.Default.SelectAll, contentDescription = "全选/取消")
                        }
                    }
                )
            } else {
                GlassTopAppBar(
                    title = "下载管理",
                    glass = glass,
                    scrollBehavior = scrollBehavior,
                    onBack = onBack,
                    actions = {
                        // 信息按钮 - 显示下载目录
                        IconButton(onClick = { showDirInfo.value = true }) {
                            Icon(Icons.Default.Info, contentDescription = "下载目录信息")
                        }
                        // 有任何可管理的条目就显示入口。条件不能只看回放任务，
                        // 否则只下载过文件的用户进不去批量模式。
                        if (allTasks.isNotEmpty() || fileDownloads.isNotEmpty()) {
                            IconButton(onClick = { isCleanupMode = true }) {
                                Icon(Icons.Default.DeleteSweep, contentDescription = "批量管理")
                            }
                        }
                    },
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
                                        state = if (deleteFiles) ToggleableState.On else ToggleableState.Off,
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
                                        manager.deleteTask(id, deleteFile = deleteFiles)
                                    }
                                    // 文件类下载的删除是宿主的事（Android = 删 MediaStore 那条 + 撤销登记）
                                    selectedFileUris.toList().forEach { uri ->
                                        fileDownloads.firstOrNull { it.uri == uri }?.let { files?.delete(it) }
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
        val glassTop = padding.glassTop(glass)
        Box(Modifier.padding(padding.withoutTop(glass)).readableWidth().fillMaxSize().glassSource(glass).nestedScroll(scrollBehavior.nestedScrollConnection)) {
            if (allTasks.isEmpty() && fileDownloads.isEmpty()) {
                EmptyState(
                    title = "还没下载过东西",
                    subtitle = "思源课件和课堂回放会统一显示在这里",
                    icon = Icons.Outlined.CloudOff,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                val groupedDownloads = fileDownloads.groupBy { it.category }
                val fileGroups = listOf(
                    DownloadedFile.CATEGORY_TRANSCRIPT to "电子成绩单",
                    DownloadedFile.CATEGORY_LMS to "思源文件",
                    DownloadedFile.CATEGORY_ZYXF to "仲英学辅资料",
                    DownloadedFile.CATEGORY_OTHER to "其他文件",
                )
                LazyColumn(
                    Modifier.fillMaxSize().overScrollVertical(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = glassTop, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (stats != null && stats!!.activeCount > 0 && !isCleanupMode) {
                        item(key = "global_controls") {
                            GlobalControls(
                                stats = stats!!,
                                manager = manager,
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
                                        manager.pauseDownload(task.id)
                                        loadTasks()
                                    }
                                },
                                onResume = {
                                    manager.resumeDownload(task.id)
                                    loadTasks()
                                },
                                onCancel = {
                                    scope.launch {
                                        manager.cancelDownload(task.id)
                                        loadTasks()
                                    }
                                },
                                onPlay = { onPlayTask(task) },
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
                            DownloadedFileCard(
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
                                onOpen = { files?.open(record) },
                                onDelete = {
                                    scope.launch {
                                        files?.delete(record)
                                        loadTasks()
                                    }
                                },
                            )
                        }
                    }
                }
            }

            // 必须在 Scaffold 的 content 内：OverlayBottomSheet 靠 Scaffold 提供的
            // LocalDialogStates 宿主渲染，写在 Scaffold 外面会静默不显示。
            // 下载目录信息弹窗
            DownloadDirInfoDialog(show = showDirInfo, dirInfo = dirInfo, videoDir = manager.videoDirPath)
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
private fun DownloadedFileCard(
    record: DownloadedFile,
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
                    DownloadedFile.CATEGORY_TRANSCRIPT -> Icons.Default.PictureAsPdf
                    DownloadedFile.CATEGORY_ZYXF -> Icons.AutoMirrored.Filled.MenuBook
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
                    "${record.categoryLabel} · ${formatDownloadTime(record.savedAt)}",
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

@Composable
private fun GlobalControls(
    stats: DownloadStats,
    manager: DownloadManager,
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
                            manager.cancelAll()
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
                            manager.pauseAll()
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
                        manager.resumeAll()
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
    task: DownloadTask,
    isCleanupMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelection: () -> Unit = {},
    onPause: () -> Unit = {},
    onResume: () -> Unit = {},
    onCancel: () -> Unit = {},
    onPlay: () -> Unit = {},
) {
    val statusTint = when (task.status) {
        DownloadStatus.DOWNLOADING -> MiuixTheme.colorScheme.primary
        DownloadStatus.COMPLETED -> MiuixTheme.colorScheme.primary
        DownloadStatus.FAILED -> MiuixTheme.colorScheme.error
        else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    Card(
        onClick = if (isCleanupMode) onToggleSelection else {
            when (task.status) {
                DownloadStatus.COMPLETED -> onPlay
                DownloadStatus.PAUSED, DownloadStatus.PENDING, DownloadStatus.FAILED -> onResume
                DownloadStatus.DOWNLOADING -> onPause
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
                        DownloadStatus.COMPLETED -> Icons.Default.PlayCircle
                        DownloadStatus.FAILED -> Icons.Outlined.ErrorOutline
                        DownloadStatus.PAUSED -> Icons.Default.PauseCircle
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
                            formatDownloadTime(task.createTime),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                if (!isCleanupMode) {
                    when (task.status) {
                        DownloadStatus.DOWNLOADING -> {
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
                        DownloadStatus.PAUSED, DownloadStatus.PENDING -> {
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
                        DownloadStatus.FAILED -> {
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
                        DownloadStatus.COMPLETED -> {
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
            if (task.status == DownloadStatus.DOWNLOADING || task.status == DownloadStatus.PAUSED) {
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
                            append(formatDownloadSize(task.downloadedSize))
                            if (task.fileSize > 0) {
                                append(" / ")
                                append(formatDownloadSize(task.fileSize))
                            }
                        }
                        if (task.status == DownloadStatus.DOWNLOADING && task.downloadSpeed > 0) {
                            append(" · ")
                            append("${formatDownloadSize(task.downloadSpeed)}/s")
                        }
                        if (task.status == DownloadStatus.PAUSED) append(" · 已暂停")
                    },
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.primary,
                )
            } else if (task.status == DownloadStatus.COMPLETED) {
                Spacer(Modifier.height(4.dp))
                Text(
                    formatDownloadSize(task.downloadedSize),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            } else if (task.status == DownloadStatus.FAILED) {
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
 * 下载目录信息弹窗。
 *
 * 两句位置与那几条说明都来自 [DownloadDirInfo]（各端事实不同 —— Android 的「卸载 App 会一并清除」
 * 是**应用专属外部目录**的性质，桌面写的是 `~/Downloads`），屏只负责画。
 */
@Composable
private fun DownloadDirInfoDialog(show: MutableState<Boolean>, dirInfo: DownloadDirInfo, videoDir: String) {
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
                "文件：${dirInfo.filesLocation}\n" +
                    "回放视频：$videoDir",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
            dirInfo.notes.forEach { note ->
                Text(
                    "• $note",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
            Spacer(Modifier.height(4.dp))
            TextButton(
                text = "知道了",
                onClick = { show.value = false },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
