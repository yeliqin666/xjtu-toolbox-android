package com.xjtu.toolbox.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.archive.ArchiveImportMode
import com.xjtu.toolbox.archive.ArchiveImportPrompt
import com.xjtu.toolbox.archive.StoreArchive
import com.xjtu.toolbox.desktop.archive.ArchiveCheck
import com.xjtu.toolbox.desktop.archive.DesktopArchivePlanOutcome
import com.xjtu.toolbox.desktop.archive.DesktopArchiveHost
import com.xjtu.toolbox.desktop.archive.DesktopImportOutcome
import com.xjtu.toolbox.desktop.archive.PendingArchivePlan
import java.io.File
import com.xjtu.toolbox.platform.secureKeyValueStore
import com.xjtu.toolbox.settings.ChangelogEntry
import com.xjtu.toolbox.settings.ScheduleSourceOption
import com.xjtu.toolbox.settings.SettingsHost
import com.xjtu.toolbox.settings.SettingsRow
import com.xjtu.toolbox.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 桌面端的设置偏好存储（进程一份）。 */
internal val desktopSettings: SettingsStore by lazy {
    SettingsStore(secureKeyValueStore(SettingsStore.APP_SETTINGS_FILE))
}

/** 桌面端的设置页宿主（见 [SettingsHost] 与 [SettingsRow]）。 */
internal class DesktopSettingsHost(private val notifyAppearanceChanged: () -> Unit) : SettingsHost {

    /** 版本号显示。 */
    override val versionText: String = "v${DesktopBuildConfig.VERSION_NAME} (desktop)"

    /** 桌面端支持的设置项。 */
    override val honoredRows: Set<SettingsRow> = setOf(
        SettingsRow.DARK_MODE,
        SettingsRow.DYNAMIC_COLOR,
        SettingsRow.HOME_HERO,
        SettingsRow.DEFAULT_TAB,
        SettingsRow.VENUE_CAPTCHA,
        SettingsRow.SCHEDULE_ATTENDANCE,
        SettingsRow.NAV_STYLE,
    )

    /** 桌面端暂不支持的设置说明。 */
    override val gapNote: String =
        "桌面版没有这些设置（要调请用 App）：常用功能、连接模式" +
            "——常用功能是手机首页上的快捷入口，桌面外壳照桌面习惯另做了一套。" +
            "课表来源只有教务一个：桌面没有另做取数来源。" +
            "「通知与提醒」只有一档：课表有变动时弹一条系统托盘气泡（`notify/`），没有可调的开关——" +
            "其余几类提醒的评测在 `:app` 的 WorkManager 里，桌面没有排程载体（见 `docs/desktop-port-plan.md` 的 D6）；" +
            "「更新」整组也没有：安装包自带版本，不走单独的更新通道。"

    override val changelog: List<ChangelogEntry> = emptyList()

    override val scheduleSourceOptions: List<ScheduleSourceOption> = emptyList()

    override val noticeSection: (@Composable () -> Unit)? = null

    override val dataPrivacySection: (@Composable () -> Unit)? = { DesktopDataPrivacySection() }

    override val updateSection: (@Composable () -> Unit)? = null

    override val onNetworkModeChanged: (() -> Unit)? = null

    override fun onAppearanceChanged() = notifyAppearanceChanged()

    /** 打开外链：桌面用 `Main.kt` 里那个 `openInBrowser`（`java.awt.Desktop.browse`）。 */
    override val onOpenUrl: (String) -> Unit = { openInBrowser(it) }
}

/** 「数据与隐私」组（接上）：两行归档动作 + 一行结果文本。 */
@Composable
private fun DesktopDataPrivacySection() {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    // §6.4 显式镜像档：先算差异、弹窗选档（默认恒为 MERGE），确认后才落地。
    var pendingPlan by remember { mutableStateOf<PendingArchivePlan?>(null) }
    var importMode by remember { mutableStateOf(ArchiveImportMode.MERGE) }

    // 按档位算一次差异（端内零改动）；用户换档位就重算一次。
    val planImport: (ArchiveImportMode) -> Unit = { mode ->
        if (!busy) {
            busy = true
            scope.launch {
                val planned = withContext(Dispatchers.IO) {
                    runCatching { DesktopArchiveHost.planPendingArchive(mode) }.getOrNull()
                }
                busy = false
                when {
                    planned == null -> {
                        pendingPlan = null
                        status = "数据根下没有待导入的档案"
                    }
                    planned.plan is DesktopArchivePlanOutcome.Planned -> {
                        importMode = mode
                        pendingPlan = planned
                        status = null
                    }
                    else -> {
                        pendingPlan = null
                        status = planOutcomeText(planned.path, planned.plan)
                    }
                }
            }
        }
    }

    // 不选档就清下去：仍走原来那条路（maybeImportPendingArchive，默认档）。
    val dismissToLegacyPath: () -> Unit = {
        pendingPlan = null
        if (!busy) {
            busy = true
            scope.launch {
                status = withContext(Dispatchers.IO) { importStatusText() }
                busy = false
            }
        }
    }

    SmallTitle("数据与隐私")
    SettingsCard {
        ArrowPreference(
            title = "导出数据存档",
            summary = "写到数据根下的 ${DesktopArchiveHost.ARCHIVE_DIR}/ 目录与 ${DesktopArchiveHost.ARCHIVE_ZIP_NAME}（不含密码等凭据）",
            startAction = { SettingsIcon(Icons.Default.Upload) },
            onClick = {
                if (busy) return@ArrowPreference
                busy = true
                status = "导出中…"
                scope.launch {
                    status = withContext(Dispatchers.IO) {
                        runCatching { DesktopArchiveHost.exportToDataRoot() }.fold(
                            onSuccess = {
                                "已导出 ${it.entries} 个条目到 ${it.directory.absolutePath}，另有 ${it.zip.name}"
                            },
                            onFailure = { "导出失败：${it.message ?: it::class.simpleName}" },
                        )
                    }
                    busy = false
                }
            },
        )
        ArrowPreference(
            title = "导入数据存档",
            summary = "扫数据根下的 ${DesktopArchiveHost.ARCHIVE_DIR}/ 目录与 *.zip，先自校验再合并",
            startAction = { SettingsIcon(Icons.Default.Download) },
            onClick = { planImport(ArchiveImportMode.MERGE) },
        )
        status?.let {
            Text(
                it,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }

    pendingPlan?.let { shown ->
        val planned = shown.plan as? DesktopArchivePlanOutcome.Planned
        OverlayDialog(
            show = true,
            title = ArchiveImportPrompt.TITLE,
            summary = planned?.plan?.summary ?: planOutcomeText(shown.path, shown.plan),
            onDismissRequest = dismissToLegacyPath,
        ) {
            Column(Modifier.fillMaxWidth()) {
                if (planned != null) {
                    ArchiveImportMode.values().forEach { mode ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = importMode == mode,
                                onClick = { planImport(mode) },
                            )
                            Column(Modifier.padding(start = 10.dp)) {
                                Text(ArchiveImportPrompt.label(mode), style = MiuixTheme.textStyles.body1)
                                Text(
                                    ArchiveImportPrompt.detail(mode),
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                    }
                    ArchiveImportPrompt.warning(importMode, planned.plan.diff)?.let {
                        Text(it, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.error)
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    TextButton(
                        text = ArchiveImportPrompt.CANCEL,
                        onClick = dismissToLegacyPath,
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                    )
                    if (planned != null) {
                        Spacer(Modifier.width(20.dp))
                        TextButton(
                            text = ArchiveImportPrompt.CONFIRM,
                            onClick = {
                                pendingPlan = null
                                busy = true
                                scope.launch {
                                    status = withContext(Dispatchers.IO) {
                                        runCatching { DesktopArchiveHost.confirmImport(planned) }.fold(
                                            onSuccess = { importOutcomeText(shown.path, it) },
                                            onFailure = {
                                                "导入失败：${it.message ?: it::class.simpleName}（端内零变化）"
                                            },
                                        )
                                    }
                                    busy = false
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !busy,
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                    }
                }
            }
        }
    }
}

/** 导入那一行的结果文本：没有档案、导入了、已经导过、档案不可用。 */
internal fun importStatusText(): String {
    val pending = DesktopArchiveHost.maybeImportPendingArchive()
        ?: return "数据根下没有待导入的档案"
    return importOutcomeText(pending.path, pending.outcome)
}

/** 一次导入的文案（前缀就是「发现待导入档案 X」那一句，与 [importStatusText] 逐字一致）。 */
internal fun importOutcomeText(path: File, outcome: DesktopImportOutcome): String {
    val prefix = "发现待导入档案 ${path.name}"
    return when (outcome) {
        is DesktopImportOutcome.Imported ->
            "$prefix：已交给 :core 的 StoreArchive.import 按 §6.4 合并 —— " +
                "写入 ${outcome.result.keysWritten} 个键 / 保持 ${outcome.result.keysKept} 个 / " +
                "写回 ${outcome.result.filesWritten} 个文件" +
                // 未落地计数（B-9）：以前这一层对桌面没有落点的族是静默丢的，现在如实报出来。
                if (outcome.result.filesSkipped > 0) " / ${outcome.result.filesSkipped} 条未落地（这一端没有那一族的落点）" else ""
        is DesktopImportOutcome.AlreadyImported -> "$prefix：这份档案已经导过（标记与它一致），本次未改动任何数据"
        is DesktopImportOutcome.Failed -> "$prefix 不可用：${outcome.reason}（端内零变化）"
    }
}

/** 只算了差异、还没落地时的文案（弹窗标题旁那一行 / 算不出来时的理由）。 */
internal fun planOutcomeText(path: File, plan: DesktopArchivePlanOutcome): String {
    val prefix = "发现待导入档案 ${path.name}"
    return when (plan) {
        is DesktopArchivePlanOutcome.Planned -> "$prefix：${plan.plan.summary}"
        is DesktopArchivePlanOutcome.AlreadyImported -> "$prefix：这份档案已经导过（标记与它一致），本次未改动任何数据"
        is DesktopArchivePlanOutcome.Failed -> "$prefix 不可用：${plan.reason}（端内零变化）"
    }
}

/** 设置页一行左侧的图标（与 `:core` 设置屏的 `SettingsIcon` 同一视觉）。 */
@Composable
private fun SettingsIcon(icon: ImageVector) {
    val color = MiuixTheme.colorScheme.primary
    Surface(
        shape = CircleShape,
        color = color.copy(alpha = 0.12f),
        modifier = Modifier.size(32.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = color)
        }
    }
    Spacer(Modifier.width(12.dp))
}

/** 设置页分组卡片的外壳（与共享屏的私有 `SettingsCard` 一致）。 */
@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        cornerRadius = 16.dp,
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant),
    ) {
        Column(content = content)
    }
}
