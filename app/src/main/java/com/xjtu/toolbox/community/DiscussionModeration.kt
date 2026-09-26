package com.xjtu.toolbox.community

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.ui.components.AppDropdownMenu
import com.xjtu.toolbox.ui.components.AppDropdownMenuItem
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 关闭原因（DiscussionCloseReason）。 */
internal val CLOSE_REASONS = listOf("RESOLVED" to "已解决", "OUTDATED" to "已过时", "DUPLICATE" to "重复")

/** 折叠原因（ReportedContentClassifiers）。 */
internal val MINIMIZE_REASONS = listOf(
    "SPAM" to "垃圾信息", "ABUSE" to "辱骂", "OFF_TOPIC" to "跑题", "OUTDATED" to "已过时",
    "DUPLICATE" to "重复", "RESOLVED" to "已解决", "LOW_QUALITY" to "低质量",
)

internal fun closeReasonLabel(reason: String) = CLOSE_REASONS.firstOrNull { it.first == reason }?.second ?: "已关闭"

/** GitHub 返回的折叠原因是小写加连字符（off-topic），这里统一成枚举名再查。 */
internal fun minimizeReasonLabel(reason: String) =
    MINIMIZE_REASONS.firstOrNull { it.first == reason.uppercase().replace('-', '_') }?.second ?: "已折叠"

internal fun shareLink(context: Context, title: String, url: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, title)
        .putExtra(Intent.EXTRA_TEXT, "$title\n$url")
    context.startActivity(Intent.createChooser(send, "分享到"))
}

/** 引用某条回复：取前几行，每行加「> 」，末尾空一行开始写。 */
internal fun quoteMarkdown(comment: GithubDiscussionComment): String {
    val lines = comment.body.trim().lines().take(8)
    val quoted = lines.joinToString("\n") { "> $it" }
    return "> @${comment.author ?: "ghost"}：\n$quoted\n\n"
}

/** 菜单项；[danger] 用红字。 */
internal data class MenuAction(val label: String, val danger: Boolean = false, val onClick: () -> Unit)

/** 「…」按钮 + 弹出菜单；没有可用项时不显示。 */
@Composable
internal fun MoreMenu(actions: List<MenuAction>) {
    if (actions.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Box {
        CommunityAction(Icons.Outlined.MoreHoriz, "", onClick = { open = true })
        AppDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            actions.forEach { action ->
                AppDropdownMenuItem(
                    text = {
                        Text(
                            action.label,
                            color = if (action.danger) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurface,
                        )
                    },
                    onClick = { open = false; action.onClick() },
                )
            }
        }
    }
}

/** 从几个原因里选一个；选完即确认。 */
@Composable
internal fun ReasonDialog(
    title: String,
    summary: String?,
    reasons: List<Pair<String, String>>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    OverlayDialog(show = true, title = title, summary = summary, onDismissRequest = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            reasons.forEach { (value, label) ->
                TextButton(text = label, onClick = { onPick(value) }, modifier = Modifier.fillMaxWidth())
            }
            TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColorsPrimary())
        }
    }
}

/** 二次确认。 */
@Composable
internal fun ConfirmDialog(title: String, summary: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    OverlayDialog(show = true, title = title, summary = summary, onDismissRequest = onDismiss) {
        Row(Modifier.fillMaxWidth()) {
            TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(20.dp))
            TextButton(
                text = confirm,
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

/** 被折叠的回复：只露一行原因，点开再看。 */
@Composable
internal fun MinimizedNotice(reason: String, onExpand: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "这条回复已被折叠（${minimizeReasonLabel(reason)}）",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.weight(1f),
        )
        TextButton(text = "展开", onClick = onExpand, minHeight = 32.dp)
    }
}
