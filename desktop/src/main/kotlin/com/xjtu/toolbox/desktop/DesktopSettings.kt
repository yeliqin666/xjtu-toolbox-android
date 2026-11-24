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
import com.xjtu.toolbox.settings.SettingsColors
import com.xjtu.toolbox.settings.SettingsIcon
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

    /** 桌面端支持的设置项；主页设置与 app 共用 `HomeSettingsSheet`，落盘在 JVM 可用的 `HomeLayoutStore`。 */
    override val honoredRows: Set<SettingsRow> = setOf(
        SettingsRow.DARK_MODE,
        SettingsRow.DYNAMIC_COLOR,
        SettingsRow.HOME_HERO,
        SettingsRow.HOME_SETTINGS,
        SettingsRow.DEFAULT_TAB,
        SettingsRow.SCHEDULE_ATTENDANCE,
        SettingsRow.NAV_STYLE,
    )

    /** 桌面端暂不支持的设置说明。 */
    // 提醒只有课表变动的托盘气泡（`notify/`）；其余提醒在 `:app` 的 WorkManager 里，桌面没有排程载体
    override val gapNote: String = "常用功能、连接模式、课表来源和提醒设置请在手机上调整。"

    override val changelog: List<ChangelogEntry> = emptyList()

    override val scheduleSourceOptions: List<ScheduleSourceOption> = emptyList()

    override val noticeSection: (@Composable () -> Unit)? = null

    override val dataPrivacySection: (@Composable () -> Unit)? = { DesktopDataPrivacySection() }

    override val updateSection: (@Composable () -> Unit)? = { DesktopUpdateSection() }

    override val onNetworkModeChanged: (() -> Unit)? = null

    override fun onAppearanceChanged() = notifyAppearanceChanged()

    /** 打开外链：桌面用 `Main.kt` 里那个 `openInBrowser`（`java.awt.Desktop.browse`）。 */
    override val onOpenUrl: (String) -> Unit = { openInBrowser(it) }
}

/** 「更新」组：查 GitHub 发布页，有新版就打开发布页（桌面没有自动安装通道）。 */
@Composable
private fun DesktopUpdateSection() {
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<DesktopUpdate.CheckResult?>(null) }
    SmallTitle("更新")
    SettingsCard {
        val available = result as? DesktopUpdate.CheckResult.Available
        ArrowPreference(
            title = if (available != null) "下载新版 v${available.release.version}" else "检查更新",
            summary = when (val r = result) {
                null -> if (checking) "正在检查…" else "当前 v${DesktopBuildConfig.VERSION_NAME}"
                is DesktopUpdate.CheckResult.Available -> "在浏览器打开发布页下载安装包"
                DesktopUpdate.CheckResult.UpToDate -> "已是最新版本（v${DesktopBuildConfig.VERSION_NAME}）"
                is DesktopUpdate.CheckResult.Failed -> r.reason
            },
            onClick = {
                when {
                    available != null -> openInBrowser(available.release.releaseUrl)
                    checking -> Unit
                    else -> {
                        checking = true
                        result = null
                        scope.launch {
                            result = withContext(Dispatchers.IO) { DesktopUpdate.check() }
                            checking = false
                        }
                    }
                }
            },
        )
    }
}

/** 「数据与隐私」组（接上）：两行归档动作 + 一行结果文本。 */
@Composable
private fun DesktopDataPrivacySection() {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    // 显式镜像档：先算差异、弹窗选档（默认恒为 MERGE），确认后才落地。
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
            summary = "不含密码",
            startAction = { SettingsIcon(Icons.Default.Upload, SettingsColors.Teal) },
            onClick = {
                if (busy) return@ArrowPreference
                busy = true
                status = "导出中…"
                scope.launch {
                    status = withContext(Dispatchers.IO) {
                        runCatching { DesktopArchiveHost.exportToDataRoot() }.fold(
                            onSuccess = {
                                "已导出到 ${it.zip.absolutePath}"
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
            summary = "与现有数据合并",
            startAction = { SettingsIcon(Icons.Default.Download, SettingsColors.Blue) },
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
                                                "导入失败，数据未改动：${it.message ?: it::class.simpleName}"
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
        ?: return "没有待导入的存档"
    return importOutcomeText(pending.path, pending.outcome)
}

/** 一次导入的文案（前缀与 [importStatusText] 一致）。 */
internal fun importOutcomeText(path: File, outcome: DesktopImportOutcome): String {
    val prefix = "存档 ${path.name}"
    return when (outcome) {
        is DesktopImportOutcome.Imported ->
            "$prefix 已导入" +
                // 桌面没有落点的那几类数据不静默丢，报个数
                if (outcome.result.filesSkipped > 0) "，${outcome.result.filesSkipped} 项桌面版不支持" else ""
        is DesktopImportOutcome.AlreadyImported -> "$prefix 已经导入过，未作改动"
        is DesktopImportOutcome.Failed -> "$prefix 无法导入，数据未改动：${outcome.reason}"
    }
}

/** 只算了差异、还没落地时的文案（弹窗标题旁那一行 / 算不出来时的理由）。 */
internal fun planOutcomeText(path: File, plan: DesktopArchivePlanOutcome): String {
    val prefix = "存档 ${path.name}"
    return when (plan) {
        is DesktopArchivePlanOutcome.Planned -> "$prefix：${plan.plan.summary}"
        is DesktopArchivePlanOutcome.AlreadyImported -> "$prefix 已经导入过，未作改动"
        is DesktopArchivePlanOutcome.Failed -> "$prefix 无法导入，数据未改动：${plan.reason}"
    }
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
