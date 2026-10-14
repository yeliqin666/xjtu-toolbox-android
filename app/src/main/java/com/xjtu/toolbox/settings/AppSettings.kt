package com.xjtu.toolbox.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.EventSeat
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Upload
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.xjtu.toolbox.BuildConfig
import com.xjtu.toolbox.archive.AppArchiveUx
import com.xjtu.toolbox.data.CredentialStore
import com.xjtu.toolbox.notification.LibraryReminderScheduler
import com.xjtu.toolbox.notification.LmsDeadlineScheduler
import com.xjtu.toolbox.notification.NoticeWatchScheduler
import com.xjtu.toolbox.notification.NoticeWatchStore
import com.xjtu.toolbox.notification.NoticeWatchSync
import com.xjtu.toolbox.notification.NotificationSource
import com.xjtu.toolbox.notification.ReminderKind
import com.xjtu.toolbox.notification.ReminderStore
import com.xjtu.toolbox.notification.ScheduleWatchScheduler
import com.xjtu.toolbox.notification.SourceCategory
import com.xjtu.toolbox.platform.BackHandler
import com.xjtu.toolbox.schedule.ScheduleSource
import com.xjtu.toolbox.ui.components.AppFilterChip
import com.xjtu.toolbox.update.AppChangelog
import com.xjtu.toolbox.update.AppUpdateInfo
import com.xjtu.toolbox.update.AppUpdater
import com.xjtu.toolbox.update.AutoUpdateDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * Android 侧的设置页**宿主**：`:core` 那份共享设置屏（`SettingsScreen`）里 Android 独有的三组，
 * 加上「这一端真会照着办」的行清单。
 *
 * ## 为什么这三组留在 `:app`
 *
 * | 组 | 卡的是什么 |
 * |---|---|
 * | 通知与提醒 | `NotificationManager` 的通知权限（`POST_NOTIFICATIONS`）+ WorkManager 排程（`NoticeWatchWorker` / `ReminderNotifier`）+ 系统电池优化设置页 |
 * | 数据与隐私 | `Context.cacheDir` 的清理（还有桌面小组件刷新）与 `CrashReporter`（Android 的崩溃上报） |
 * | 更新 | `AppUpdater`：下载并**安装 APK**（`PackageInstaller` / `FileProvider`）—— 桌面是 `.deb`/tar.gz，没有这条路 |
 *
 * 这三组整份走 [SettingsHost] 的三个槽位（`noticeSection` / `dataPrivacySection` / `updateSection`），
 * 桌面传 `null` ⇒ 整组不画（不是画一堆点了没用的开关）。
 *
 * 三组里的每一行、每一句文案、每一个弹层都与搬迁前的 `settings/SettingsScreen.kt` **逐字相同**
 * （搬迁 = 把这三段从共享屏里切出来，行为零变化）。
 */
@Composable
fun rememberAppSettingsHost(
    credentialStore: CredentialStore,
    onNetworkModeChanged: suspend () -> Unit,
): SettingsHost {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 打开外链仍是搬迁前那一句 `LocalUriHandler.current.openUri(...)`：屏里不再读这个
    // CompositionLocal（各端不是同一件事，而且离屏渲染场景根本没提供），改由宿主给。
    val uriHandler = LocalUriHandler.current
    return remember(credentialStore, context, scope, uriHandler) {
        AppSettingsHost(context, credentialStore, scope, onNetworkModeChanged) { uriHandler.openUri(it) }
    }
}

private class AppSettingsHost(
    private val context: Context,
    private val credentialStore: CredentialStore,
    private val scope: CoroutineScope,
    private val notifyNetworkModeChanged: suspend () -> Unit,
    private val openUri: (String) -> Unit,
) : SettingsHost {

    override val versionText: String =
        "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})${if (BuildConfig.IS_PREVIEW) "（预览版）" else ""}"

    /** Android 每一项都有宿主面在读它（见 `SettingsRow` 的 KDoc），所以全列。 */
    override val honoredRows: Set<SettingsRow> = SettingsRow.entries.toSet()

    override val changelog: List<ChangelogEntry> = AppChangelog.ENTRIES.map { (version, log) ->
        ChangelogEntry(
            version = "v$version",
            items = log.items.map { (emoji, text) -> "$emoji $text" } +
                log.issues.map { "⚠️ 已知问题：$it" }
        )
    }

    override val scheduleSourceOptions: List<ScheduleSourceOption> =
        ScheduleSource.entries.map { ScheduleSourceOption(it.key, it.label) }

    override val noticeSection: @Composable () -> Unit = { NoticeAndReminderSection(context, scope) }

    override val dataPrivacySection: @Composable () -> Unit = { DataPrivacySection(context, scope) }

    override val updateSection: @Composable () -> Unit = {
        UpdateSection(context, scope, credentialStore)
    }

    override val onNetworkModeChanged: () -> Unit = { scope.launch { notifyNetworkModeChanged() } }

    /**
     * 打开外链：Android 这边就是搬迁前那一句 `LocalUriHandler.current.openUri(...)`
     *（`:app` 的导航树里这个 CompositionLocal 是提供好的）—— 行为一字未改，只是从屏里挪到了宿主。
     */
    override val onOpenUrl: (String) -> Unit = { openUri(it) }
}

@Composable
private fun NoticeAndReminderSection(context: Context, scope: CoroutineScope) {
    var noticeWatchEnabled by remember { mutableStateOf(NoticeWatchStore.isEnabled(context)) }
    var noticeWatchSummary by remember { mutableStateOf(NoticeWatchStore.sourceSummary(context)) }
    var showNoticeSources by remember { mutableStateOf(false) }

    fun applyNoticeWatch(enabled: Boolean = noticeWatchEnabled) {
        NoticeWatchStore.setEnabled(context, enabled)
        noticeWatchEnabled = enabled
        NoticeWatchScheduler.apply(context)
        if (!enabled) return
        scope.launch(Dispatchers.IO) {
            NoticeWatchSync.sync(context, notify = true, force = true)
            withContext(Dispatchers.Main) {
                noticeWatchSummary = NoticeWatchStore.sourceSummary(context)
            }
        }
    }

    var reminderLibrary by remember { mutableStateOf(ReminderStore.isEnabled(context, ReminderKind.LIBRARY)) }
    var reminderSchedule by remember { mutableStateOf(ReminderStore.isEnabled(context, ReminderKind.SCHEDULE)) }
    var reminderLms by remember { mutableStateOf(ReminderStore.isEnabled(context, ReminderKind.LMS)) }

    /** 打开某类提醒后待补的通知权限申请；授权回调里再落盘排程。 */
    var pendingReminder by remember { mutableStateOf<ReminderKind?>(null) }

    fun commitReminder(kind: ReminderKind, enabled: Boolean) {
        ReminderStore.setEnabled(context, kind, enabled)
        when (kind) {
            ReminderKind.LIBRARY -> {
                reminderLibrary = enabled
                // 关掉就把已排的一次性任务撤了；开着时等图书馆页下次拿到预约状态再排。
                if (!enabled) LibraryReminderScheduler.sync(context, null)
            }
            ReminderKind.SCHEDULE -> {
                reminderSchedule = enabled
                ScheduleWatchScheduler.apply(context)
            }
            ReminderKind.LMS -> {
                reminderLms = enabled
                LmsDeadlineScheduler.apply(context)
            }
        }
    }

    val reminderPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val kind = pendingReminder ?: return@rememberLauncherForActivityResult
        pendingReminder = null
        commitReminder(kind, true)
        if (!granted) {
            Toast.makeText(context, "未授予通知权限，提醒发不出来", Toast.LENGTH_SHORT).show()
        }
    }

    /** 开提醒前先问通知权限：这几项除了发通知没有别的表现形式，没权限等于开了个寂寞。 */
    fun applyReminder(kind: ReminderKind, enabled: Boolean) {
        if (!enabled) {
            commitReminder(kind, false)
            return
        }
        val needAsk = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        if (needAsk) {
            pendingReminder = kind
            reminderPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            commitReminder(kind, true)
        }
    }

    val noticePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        applyNoticeWatch(enabled = true)
        if (!granted) {
            Toast.makeText(
                context,
                "未授予通知权限，不会弹出系统通知",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    SmallTitle("教务通知")
    SettingsCard {
        SwitchPreference(
            title = "新通知提醒",
            checked = noticeWatchEnabled,
            startAction = { SettingsIcon(Icons.Default.Notifications) },
            onCheckedChange = { on ->
                if (on) {
                    val needAsk = Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    if (needAsk) {
                        noticePermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        applyNoticeWatch(enabled = true)
                    }
                } else {
                    applyNoticeWatch(enabled = false)
                }
            }
        )
        ArrowPreference(
            title = "推送来源",
            summary = noticeWatchSummary,
            startAction = { SettingsIcon(Icons.Default.CloudSync) },
            onClick = { showNoticeSources = true }
        )
    }
    SmallTitle("后台提醒")
    SettingsCard {
        SwitchPreference(
            title = ReminderKind.LIBRARY.title,
            summary = "签到快超时、暂离未返座",
            checked = reminderLibrary,
            startAction = { SettingsIcon(Icons.Default.EventSeat) },
            onCheckedChange = { on -> applyReminder(ReminderKind.LIBRARY, on) }
        )
        SwitchPreference(
            title = ReminderKind.SCHEDULE.title,
            summary = "调课、停课、考前 3 天",
            checked = reminderSchedule,
            startAction = { SettingsIcon(Icons.Default.CalendarMonth) },
            onCheckedChange = { on -> applyReminder(ReminderKind.SCHEDULE, on) }
        )
        SwitchPreference(
            title = ReminderKind.LMS.title,
            summary = "作业截止前 48 小时未交",
            checked = reminderLms,
            startAction = { SettingsIcon(Icons.AutoMirrored.Filled.Assignment) },
            onCheckedChange = { on -> applyReminder(ReminderKind.LMS, on) }
        )
        ArrowPreference(
            title = "提醒不准时？",
            summary = "在系统设置里取消省电限制",
            startAction = { SettingsIcon(Icons.Default.BatteryAlert) },
            onClick = {
                // 只跳系统的电池优化列表，不申请 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                // 直接弹窗：那个权限要在清单里声明，且上架审核会追问用途。
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                val opened = runCatching { context.startActivity(intent) }.isSuccess
                if (!opened) {
                    Toast.makeText(context, "这台设备没有电池优化设置页", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    NoticeSourceSheet(
        show = showNoticeSources,
        onDismiss = {
            showNoticeSources = false
            noticeWatchSummary = NoticeWatchStore.sourceSummary(context)
            applyNoticeWatch()
        },
    )
}

@Composable
private fun DataPrivacySection(context: Context, scope: CoroutineScope) {
    var cacheSizeText by remember { mutableStateOf("计算中...") }
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var crashReportEnabled by remember { mutableStateOf(com.xjtu.toolbox.error.CrashReporter.isEnabled(context)) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            cacheSizeText = runCatching { clearableCacheBytes(context) }.map(::formatFileSize).getOrDefault("无法获取")
        }
    }

    var pendingExportArchive by remember { mutableStateOf<Map<String, String>?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(AppArchiveUx.ARCHIVE_MIME),
    ) { uri ->
        val archive = pendingExportArchive
        pendingExportArchive = null
        if (uri != null && archive != null) {
            scope.launch(Dispatchers.IO) {
                val ok = runCatching { AppArchiveUx.writeExportToUri(context, uri, archive) }.isSuccess
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, if (ok) "已导出数据存档" else "导出失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val ok = runCatching { AppArchiveUx.copyImportToStaging(context, uri) != null }.getOrDefault(false)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, if (ok) "已放入待导入" else "导入失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    SmallTitle("数据与隐私")
    SettingsCard {
        ArrowPreference(
            title = "清除缓存",
            summary = cacheSizeText,
            startAction = { SettingsIcon(Icons.Default.Refresh) },
            onClick = { showClearCacheDialog = true }
        )
        SwitchPreference(
            title = "自动上报崩溃日志",
            summary = "匿名，已去除学号等个人信息",
            checked = crashReportEnabled,
            startAction = { SettingsIcon(Icons.Default.BugReport) },
            onCheckedChange = {
                crashReportEnabled = it
                com.xjtu.toolbox.error.CrashReporter.setEnabled(context, it)
            }
        )
        ArrowPreference(
            title = "导出数据存档",
            summary = "把本机数据打包成 zip（不含密码等凭据）",
            startAction = { SettingsIcon(Icons.Default.Upload) },
            onClick = {
                scope.launch {
                    val archive = withContext(Dispatchers.IO) { runCatching { AppArchiveUx.exportArchive(context) }.getOrNull() }
                    if (archive == null) {
                        Toast.makeText(context, "导出失败", Toast.LENGTH_SHORT).show()
                    } else {
                        pendingExportArchive = archive
                        exportLauncher.launch("xjtu-toolbox-archive.zip")
                    }
                }
            },
        )
        ArrowPreference(
            title = "导入数据存档",
            summary = "从 zip 档案导入（默认不覆盖本机数据）",
            startAction = { SettingsIcon(Icons.Default.Download) },
            onClick = { importLauncher.launch(arrayOf("*/*")) },
        )
    }

    if (showClearCacheDialog) {
        OverlayDialog(
            show = showClearCacheDialog,
            title = "清除缓存",
            summary = "将清除约 $cacheSizeText 的课表、成绩等缓存，打开对应页面会重新加载，桌面小组件会暂时为空。登录、下载的文件和更新包不受影响。",
            onDismissRequest = { showClearCacheDialog = false }
        ) {
            Row(Modifier.fillMaxWidth()) {
                TextButton(
                    text = "取消",
                    onClick = { showClearCacheDialog = false },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = "确认清除",
                    onClick = {
                        showClearCacheDialog = false
                        scope.launch(Dispatchers.IO) {
                            val cleared = runCatching { clearableCacheFiles(context).forEach { it.deleteRecursively() } }.isSuccess
                            // 重新计算实际缓存大小，刷新 UI
                            val newSize = runCatching { clearableCacheBytes(context) }.map(::formatFileSize).getOrDefault("0 B")
                            com.xjtu.toolbox.widget.ScheduleWidgetUpdater.requestUpdate(context)
                            withContext(Dispatchers.Main) {
                                cacheSizeText = newSize
                                Toast.makeText(
                                    context,
                                    if (cleared) "缓存已清除" else "清除失败",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    }
}

@Composable
private fun UpdateSection(context: Context, scope: CoroutineScope, credentialStore: CredentialStore) {
    var updateChannel by remember { mutableStateOf(credentialStore.updateChannel) }
    var receivePreviewUpdates by remember { mutableStateOf(credentialStore.receivePreviewUpdates) }
    var showPreviewConfirmDialog by remember { mutableStateOf(false) }
    var pendingUpdate by remember { mutableStateOf<AppUpdateInfo?>(null) }

    SmallTitle("更新")
    SettingsCard {
        OverlayDropdownPreference(
            title = "更新渠道",
            items = AppUpdater.channelLabels,
            selectedIndex = AppUpdater.channelKeys.indexOf(updateChannel).coerceAtLeast(0),
            startAction = { SettingsIcon(Icons.Default.Star) },
            onSelectedIndexChange = { idx ->
                val v = AppUpdater.channelKeys[idx]
                updateChannel = v
                credentialStore.updateChannel = v
            }
        )
        SwitchPreference(
            title = "接收预览版更新",
            summary = if (receivePreviewUpdates && updateChannel != AppUpdater.CHANNEL_GITHUB) "预览版只在 GitHub 渠道发布" else null,
            checked = receivePreviewUpdates,
            startAction = { SettingsIcon(Icons.Default.CloudSync) },
            onCheckedChange = { checked ->
                if (checked) {
                    showPreviewConfirmDialog = true
                } else {
                    receivePreviewUpdates = false
                    credentialStore.receivePreviewUpdates = false
                }
            }
        )
        var checkingUpdate by remember { mutableStateOf(false) }
        ArrowPreference(
            title = "立即检查更新",
            summary = if (checkingUpdate) "正在检查…" else null,
            startAction = { SettingsIcon(Icons.Default.Refresh) },
            onClick = {
                if (checkingUpdate) return@ArrowPreference
                checkingUpdate = true
                scope.launch {
                    val result = runCatching {
                        AppUpdater.check(
                            channel = updateChannel,
                            includePreview = receivePreviewUpdates,
                            rolloutId = credentialStore.rolloutId,
                        )
                    }
                    checkingUpdate = false
                    result.fold(
                        onSuccess = { update ->
                            if (update != null) {
                                pendingUpdate = update
                            } else {
                                Toast.makeText(
                                    context,
                                    "已是最新版本 v${BuildConfig.VERSION_NAME}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        onFailure = {
                            Toast.makeText(context, com.xjtu.toolbox.error.FriendlyError.of(it, "检查更新"), Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        )
    }

    if (showPreviewConfirmDialog) {
        OverlayDialog(
            show = showPreviewConfirmDialog,
            title = "开启预览版更新",
            summary = "预览版可能不稳定。随时可以关闭，下个正式版会自动覆盖回来。",
            onDismissRequest = { showPreviewConfirmDialog = false }
        ) {
            Row(Modifier.fillMaxWidth()) {
                TextButton(
                    text = "取消",
                    onClick = { showPreviewConfirmDialog = false },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = "开启",
                    onClick = {
                        showPreviewConfirmDialog = false
                        receivePreviewUpdates = true
                        credentialStore.receivePreviewUpdates = true
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    }
    pendingUpdate?.let { update ->
        AutoUpdateDialog(
            version = update.version,
            body = update.notes,
            downloadUrl = update.downloadUrl,
            releaseUrl = update.releaseUrl,
            channelLabel = update.channelLabel,
            isPreview = update.isPreview,
            onDismiss = { pendingUpdate = null }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NoticeSourceSheet(
    show: Boolean,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var selected by remember(show) {
        mutableStateOf(NoticeWatchStore.sources(context))
    }
    var keywords by remember(show) { mutableStateOf(NoticeWatchStore.keywordsRaw(context)) }
    BackHandler(enabled = show) { onDismiss() }
    OverlayBottomSheet(
        show = show,
        title = "推送来源",
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .overScrollVertical()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
        ) {
            Text(
                "勾得越多越费电。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            Text(
                if (selected.isEmpty()) "还没选来源" else "已选 ${selected.size} 个",
                style = MiuixTheme.textStyles.footnote1,
                color = if (selected.isEmpty()) {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                } else {
                    MiuixTheme.colorScheme.primary
                },
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
            )
            SourceCategory.entries.forEach { category ->
                Text(
                    category.displayName,
                    style = MiuixTheme.textStyles.subtitle,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    NotificationSource.byCategory(category).forEach { source ->
                        val checked = source in selected
                        AppFilterChip(
                            selected = checked,
                            onClick = {
                                selected = if (checked) selected - source else selected + source
                                NoticeWatchStore.setSources(context, selected)
                            },
                            label = source.displayName
                        )
                    }
                }
            }

            // ── 关键词过滤 ──
            Text(
                "只推含关键词的",
                style = MiuixTheme.textStyles.subtitle,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
            )
            Text(
                "逗号分隔，留空则全推。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            TextField(
                value = keywords,
                onValueChange = {
                    keywords = it
                    NoticeWatchStore.setKeywords(context, it)
                },
                label = "如：保研, 奖学金, 补考, 停电",
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * 设置页的「一行一图标」——与共享屏里的私有 `SettingsIcon` 视觉一致（同样的圆形色块 + 18dp 图标），
 * 但那是共享屏里的私有 composable，跨文件用不了 ⇒ 这里自己起一份（`:app` 的通知组也用它）。
 */
@Composable
private fun SettingsIcon(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    val color = MiuixTheme.colorScheme.primary
    top.yukonga.miuix.kmp.basic.Surface(
        shape = androidx.compose.foundation.shape.CircleShape,
        color = color.copy(alpha = 0.12f),
        modifier = Modifier.size(32.dp)
    ) {
        androidx.compose.foundation.layout.Box(
            contentAlignment = androidx.compose.ui.Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = color)
        }
    }
    Spacer(Modifier.width(12.dp))
}

/** 设置页里每个分组卡片的外壳（与共享屏里的私有 `SettingsCard` 同一条口径）。 */
@Composable
private fun SettingsCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    top.yukonga.miuix.kmp.basic.Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        cornerRadius = 16.dp,
        colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant)
    ) {
        Column(content = content)
    }
}

/** 清缓存时保留 cacheDir 下的 updates/：已经下载好、等着安装的更新包。 */
private fun clearableCacheFiles(context: Context): List<java.io.File> =
    context.cacheDir.listFiles().orEmpty().filter { it.name != "updates" }

private fun clearableCacheBytes(context: Context): Long =
    clearableCacheFiles(context).sumOf { root -> root.walkTopDown().filter { it.isFile }.sumOf { it.length() } }

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024))} MB"
    else -> "${"%.2f".format(bytes.toDouble() / (1024 * 1024 * 1024))} GB"
}
