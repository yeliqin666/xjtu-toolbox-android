package com.xjtu.toolbox.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import com.xjtu.toolbox.archive.StoreArchive
import com.xjtu.toolbox.desktop.archive.ArchiveCheck
import com.xjtu.toolbox.desktop.archive.DesktopArchiveHost
import com.xjtu.toolbox.desktop.archive.DesktopImportOutcome
import com.xjtu.toolbox.platform.secureKeyValueStore
import com.xjtu.toolbox.settings.ChangelogEntry
import com.xjtu.toolbox.settings.ScheduleSourceOption
import com.xjtu.toolbox.settings.SettingsHost
import com.xjtu.toolbox.settings.SettingsRow
import com.xjtu.toolbox.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 桌面端的设置偏好存储（进程一份）。 */
internal val desktopSettings: SettingsStore by lazy {
    SettingsStore(secureKeyValueStore(SettingsStore.APP_SETTINGS_FILE))
}

/** 桌面端的设置页宿主（见 [SettingsHost] 与 [SettingsRow]）。 */
internal class DesktopSettingsHost(private val notifyAppearanceChanged: () -> Unit) : SettingsHost {

    /** 版本号那一行：桌面包的版本（构建期烘进 `DesktopBuildConfig`，与 jpackage 的`packageVersion` 同一个来源）。 */
    override val versionText: String = "v${DesktopBuildConfig.VERSION_NAME} (desktop)"

    /** 真会照着办的行。 */
    override val honoredRows: Set<SettingsRow> =
        setOf(SettingsRow.DARK_MODE, SettingsRow.DYNAMIC_COLOR, SettingsRow.HOME_THEME)

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
            onClick = {
                if (busy) return@ArrowPreference
                busy = true
                scope.launch {
                    status = withContext(Dispatchers.IO) { importStatusText() }
                    busy = false
                }
            },
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
}

/** 导入那一行的结果文本：没有档案、导入了、已经导过、档案不可用。 */
internal fun importStatusText(): String {
    val pending = DesktopArchiveHost.maybeImportPendingArchive()
        ?: return "数据根下没有待导入的档案"
    val prefix = "发现待导入档案 ${pending.path.name}"
    return when (val outcome = pending.outcome) {
        is DesktopImportOutcome.Imported ->
            "$prefix：已交给 :core 的 StoreArchive.import 按 §6.4 合并 —— " +
                "写入 ${outcome.result.keysWritten} 个键 / 保持 ${outcome.result.keysKept} 个 / " +
                "写回 ${outcome.result.filesWritten} 个文件"
        is DesktopImportOutcome.AlreadyImported -> "$prefix：这份档案已经导过（标记与它一致），本次未改动任何数据"
        is DesktopImportOutcome.Failed -> "$prefix 不可用：${outcome.reason}（端内零变化）"
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
