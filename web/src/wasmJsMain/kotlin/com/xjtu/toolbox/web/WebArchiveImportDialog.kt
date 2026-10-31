package com.xjtu.toolbox.web

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.archive.ArchiveImportMode
import com.xjtu.toolbox.archive.ArchiveImportPrompt
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 导入档位弹窗（§6.4「显式镜像档」）。
 *
 * 话术一个字都不在这里：标题、两档的名称与说明、差异那一行、提醒、两个按钮全从
 * `ArchiveImportPrompt` 取（三端逐字同一份）。这一层只负责把 [WebArchiveActions] 里那几
 * 个状态摆上屏：默认档恒为 MERGE，镜像档只有用户自己点才选得上。
 */
@Composable
internal fun WebArchiveImportDialog(archive: WebArchiveActions) {
    val colors = MiuixTheme.colorScheme
    OverlayDialog(
        show = true,
        title = ArchiveImportPrompt.TITLE,
        summary = archive.diffText ?: "",
        onDismissRequest = { archive.cancel() },
    ) {
        Column(Modifier.fillMaxWidth()) {
            ArchiveImportMode.values().forEach { mode ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 这一档算不出来（choose() 时那一份是 null）就置灰：选不上的档位比选上去弹窗消失好。
                    val usable = if (mode == ArchiveImportMode.MIRROR) archive.mirrorPlan != null else archive.mergePlan != null
                    RadioButton(
                        selected = archive.mode == mode,
                        // 换档位只换看哪一份差异（两份都是 choose() 时算好的，本机零改动）
                        onClick = { if (usable) archive.mode = mode },
                        enabled = usable,
                    )
                    Column(Modifier.padding(start = 10.dp)) {
                        Text(ArchiveImportPrompt.label(mode), style = MiuixTheme.textStyles.body1)
                        Text(
                            ArchiveImportPrompt.detail(mode),
                            style = MiuixTheme.textStyles.footnote1,
                            color = colors.onSurfaceVariantSummary,
                        )
                    }
                }
            }
            archive.warningText?.let {
                Text(it, style = MiuixTheme.textStyles.footnote1, color = colors.error)
            }
            Row(Modifier.fillMaxWidth()) {
                TextButton(
                    text = ArchiveImportPrompt.CANCEL,
                    onClick = { archive.cancel() },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = ArchiveImportPrompt.CONFIRM,
                    onClick = { archive.confirm() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}
