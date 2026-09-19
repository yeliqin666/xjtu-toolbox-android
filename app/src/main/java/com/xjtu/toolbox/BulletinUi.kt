package com.xjtu.toolbox

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import top.yukonga.miuix.kmp.utils.SinkFeedback
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.RadioButton
import androidx.compose.ui.state.ToggleableState
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.auth.*
import com.xjtu.toolbox.bulletin.Bulletin
import com.xjtu.toolbox.bulletin.BulletinLevel
import com.xjtu.toolbox.ui.components.AppCardColor

private fun bulletinAccent(level: BulletinLevel) = when (level) {
    BulletinLevel.WARN -> Color(0xFFE65100)
    BulletinLevel.CRITICAL, BulletinLevel.FORCE_UPDATE -> Color(0xFFC62828)
    BulletinLevel.UPDATE -> Color(0xFF1565C0)
    BulletinLevel.INFO -> null
}

private fun bulletinLevelLabel(level: BulletinLevel) = when (level) {
    BulletinLevel.FORCE_UPDATE -> "必须更新"
    BulletinLevel.UPDATE -> "可更新"
    BulletinLevel.CRITICAL -> "重要"
    BulletinLevel.WARN -> "维护"
    BulletinLevel.INFO -> "通知"
}

@Composable
internal fun BulletinNoticePanel(
    bulletins: List<Bulletin>,
    onTap: (Bulletin) -> Unit,
    onDismiss: (Bulletin) -> Unit,
) {
    if (bulletins.isEmpty()) return
    var expandedId by remember { mutableStateOf<String?>(bulletins.first().id) }
    LaunchedEffect(bulletins.map { it.id }) {
        if (expandedId != null && bulletins.none { it.id == expandedId }) {
            expandedId = bulletins.first().id
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 18.dp,
        colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = AppCardColor),
    ) {
        Column(Modifier.fillMaxWidth()) {
            bulletins.forEachIndexed { index, bulletin ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 14.dp),
                        color = MiuixTheme.colorScheme.outline.copy(alpha = 0.18f),
                    )
                }
                val accent = bulletinAccent(bulletin.level) ?: MiuixTheme.colorScheme.primary
                val expanded = expandedId == bulletin.id
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = SinkFeedback(),
                                onClick = {
                                    expandedId = if (expanded) null else bulletin.id
                                },
                            )
                            .padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(accent),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (bulletin.isPoll) "调查" else bulletinLevelLabel(bulletin.level),
                                style = MiuixTheme.textStyles.footnote2,
                                color = accent,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                bulletin.title,
                                style = MiuixTheme.textStyles.body2,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(
                            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (expanded) "收起" else "展开",
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.size(22.dp),
                        )
                        if (!bulletin.isPoll && (
                            bulletin.level == BulletinLevel.INFO ||
                                bulletin.level == BulletinLevel.WARN ||
                                bulletin.level == BulletinLevel.UPDATE
                            )
                        ) {
                            IconButton(onClick = { onDismiss(bulletin) }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "关闭",
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                    AnimatedVisibility(visible = expanded) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 32.dp, end = 14.dp, bottom = 14.dp)
                        ) {
                            if (bulletin.body.isNotBlank()) {
                                Text(
                                    bulletin.body,
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                                Spacer(Modifier.height(12.dp))
                            }
                            if (bulletin.isPoll) {
                                Text(
                                    "去答题",
                                    style = MiuixTheme.textStyles.body2,
                                    fontWeight = FontWeight.Bold,
                                    color = accent,
                                    modifier = Modifier.clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = SinkFeedback(),
                                        onClick = { onTap(bulletin) },
                                    ),
                                )
                                return@AnimatedVisibility
                            }
                            when (bulletin.level) {
                                BulletinLevel.FORCE_UPDATE -> {
                                    Text(
                                        "立即更新",
                                        style = MiuixTheme.textStyles.body2,
                                        fontWeight = FontWeight.Bold,
                                        color = accent,
                                        modifier = Modifier.clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = SinkFeedback(),
                                            onClick = { onTap(bulletin) },
                                        ),
                                    )
                                }
                                BulletinLevel.UPDATE -> {
                                    Text(
                                        "去更新",
                                        style = MiuixTheme.textStyles.body2,
                                        fontWeight = FontWeight.Bold,
                                        color = accent,
                                        modifier = Modifier.clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = SinkFeedback(),
                                            onClick = { onTap(bulletin) },
                                        ),
                                    )
                                }
                                BulletinLevel.CRITICAL -> {
                                    Text(
                                        "知道了",
                                        style = MiuixTheme.textStyles.body2,
                                        fontWeight = FontWeight.Bold,
                                        color = accent,
                                        modifier = Modifier.clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = SinkFeedback(),
                                            onClick = { onDismiss(bulletin) },
                                        ),
                                    )
                                }
                                BulletinLevel.INFO, BulletinLevel.WARN -> {
                                    if (!bulletin.url.isNullOrBlank()) {
                                        Text(
                                            "查看详情",
                                            style = MiuixTheme.textStyles.body2,
                                            fontWeight = FontWeight.Bold,
                                            color = accent,
                                            modifier = Modifier.clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = SinkFeedback(),
                                                onClick = { onTap(bulletin) },
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun BulletinLaunchDialog(
    bulletin: Bulletin,
    show: MutableState<Boolean>,
    onDismiss: () -> Unit,
    onPrimary: () -> Unit,
    onSubmitPoll: (List<String>) -> Unit = {},
) {
    // 投票且 mustAck 时真正不可跳过：没有返回键关闭，WindowBottomSheet 本身也不许
    // 手势下拉/点外部关掉（allowDismiss=false）。mustAck 以外的普通通知/更新提示
    // 保留原来"能关掉，只是关了首页还会再看见"的行为，不动它。
    val forceAnswer = bulletin.isPoll && bulletin.mustAck
    BackHandler(enabled = show.value && !forceAnswer) { onDismiss() }
    val isForceUpdate = bulletin.level == BulletinLevel.FORCE_UPDATE
    val title = when {
        bulletin.isPoll -> "小调查"
        bulletin.level == BulletinLevel.FORCE_UPDATE -> "必须更新"
        bulletin.level == BulletinLevel.UPDATE -> "发现新版本"
        bulletin.level == BulletinLevel.CRITICAL -> "重要通知"
        bulletin.level == BulletinLevel.WARN -> "维护通知"
        else -> "通知"
    }
    val selected = remember(bulletin.id) { mutableStateListOf<String>() }
    var otherText by remember(bulletin.id) { mutableStateOf("") }
    WindowBottomSheet(
        show = show.value,
        title = title,
        onDismissRequest = if (forceAnswer) null else onDismiss,
        allowDismiss = !forceAnswer,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                bulletin.title,
                style = MiuixTheme.textStyles.title4,
                fontWeight = FontWeight.Bold,
            )
            if (bulletin.body.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    bulletin.body,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.height(16.dp))
            if (bulletin.isPoll) {
                bulletin.options.forEach { option ->
                    val isChecked = option in selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (bulletin.allowMultiple) {
                                    if (isChecked) selected.remove(option) else selected.add(option)
                                } else {
                                    selected.clear()
                                    selected.add(option)
                                }
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (bulletin.allowMultiple) {
                            Checkbox(
                                state = if (isChecked) ToggleableState.On else ToggleableState.Off,
                                onClick = null,
                            )
                        } else {
                            RadioButton(selected = isChecked, onClick = null)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(option, style = MiuixTheme.textStyles.body1)
                    }
                }
                if (bulletin.allowOther) {
                    Spacer(Modifier.height(8.dp))
                    TextField(
                        value = otherText,
                        onValueChange = { otherText = it },
                        label = "其他（选填）",
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        val answers = selected.toList() +
                            listOfNotNull(otherText.trim().ifBlank { null }?.let { "其他：$it" })
                        onSubmitPoll(answers)
                    },
                    enabled = selected.isNotEmpty() || otherText.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("提交")
                }
            } else if (isForceUpdate) {
                Button(
                    onClick = onPrimary,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("立即更新")
                }
                Spacer(Modifier.height(8.dp))
                TextButton(
                    text = "稍后",
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                TextButton(
                    text = "知道了",
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(8.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}
