package com.xjtu.toolbox.bulletin


import com.xjtu.toolbox.platform.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import top.yukonga.miuix.kmp.utils.SinkFeedback
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import com.xjtu.toolbox.ui.markdown.GithubMarkdown
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.nav.SeamlessCardSource
import com.xjtu.toolbox.nav.expandOriginSource
import com.xjtu.toolbox.nav.rememberExpandOriginSource
import com.xjtu.toolbox.ui.components.AppCardColor
import com.xjtu.toolbox.ui.components.ChoiceList
import com.xjtu.toolbox.ui.components.rememberChoiceState
import kotlin.time.Clock

internal fun bulletinAccent(level: BulletinLevel) = when (level) {
    BulletinLevel.WARN -> Color(0xFFE65100)
    BulletinLevel.CRITICAL, BulletinLevel.FORCE_UPDATE -> Color(0xFFC62828)
    BulletinLevel.UPDATE -> Color(0xFF1565C0)
    BulletinLevel.INFO -> null
}

internal fun bulletinLevelLabel(level: BulletinLevel) = when (level) {
    BulletinLevel.FORCE_UPDATE -> "必须更新"
    BulletinLevel.UPDATE -> "可更新"
    BulletinLevel.CRITICAL -> "重要"
    BulletinLevel.WARN -> "维护"
    BulletinLevel.INFO -> "通知"
}

private val NOTICE_PANEL_RADIUS = 18.dp

@Composable
fun BulletinNoticePanel(
    bulletins: List<Bulletin>,
    onTap: (Bulletin) -> Unit,
    onDetail: (Bulletin, SeamlessCardSource?) -> Unit,
    onDismiss: (Bulletin) -> Unit,
) {
    if (bulletins.isEmpty()) return
    var expandedId by remember { mutableStateOf<String?>(bulletins.first().id) }
    LaunchedEffect(bulletins.map { it.id }) {
        if (expandedId != null && bulletins.none { it.id == expandedId }) {
            expandedId = bulletins.first().id
        }
    }

    // 「卡片 → 公告详情」的起点：KIT 走 Bitmap 源要矩形，Compose 回落的 ExpandOrigins 也要它。
    val origin = rememberExpandOriginSource()
    val density = LocalDensity.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .expandOriginSource(origin),
        cornerRadius = NOTICE_PANEL_RADIUS,
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
                // 返回转场的源元素快照：缩回时画在原位，和卡面同一套内容与底色。
                val snapshot: @Composable (Modifier) -> Unit = { m -> NoticePanelSnapshot(bulletin, m) }
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
                                GithubMarkdown(
                                    bulletin.body,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    textStyle = MiuixTheme.textStyles.footnote1,
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
                                    if (!bulletin.url.isNullOrBlank() || bulletin.body.isNotBlank()) {
                                        Text(
                                            "查看详情",
                                            style = MiuixTheme.textStyles.body2,
                                            fontWeight = FontWeight.Bold,
                                            color = accent,
                                            modifier = Modifier.clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = SinkFeedback(),
                                                onClick = {
                                                    // 先登记起点再跳：ROM 支持 KIT 时起点给 Bitmap 源用，
                                                    // 不支持时 AppNavigator 压栈会消费它，走 Compose 转场。
                                                    val route = AppRoute.NoticeDetail.of(bulletin)
                                                    origin.arm(route.id, NOTICE_PANEL_RADIUS, density, snapshot)
                                                    onDetail(
                                                        bulletin,
                                                        origin.boundsInWindow()?.let {
                                                            SeamlessCardSource(it, NOTICE_PANEL_RADIUS)
                                                        },
                                                    )
                                                },
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
private fun NoticePanelSnapshot(bulletin: Bulletin, modifier: Modifier) {
    val accent = bulletinAccent(bulletin.level) ?: MiuixTheme.colorScheme.primary
    Card(
        modifier = modifier.fillMaxSize(),
        cornerRadius = NOTICE_PANEL_RADIUS,
        colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = AppCardColor),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
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
        }
    }
}

/** 投票标题下的一行：单选 / 可多选，以及截止倒计时。 */
@Composable
private fun PollMeta(bulletin: Bulletin) {
    val colors = MiuixTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (bulletin.allowMultiple) "可多选" else "单选",
            style = MiuixTheme.textStyles.footnote2,
            fontWeight = FontWeight.Medium,
            color = colors.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(colors.primary.copy(alpha = 0.12f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
        bulletin.remainingText(Clock.System.now())?.let {
            Spacer(Modifier.width(8.dp))
            Text(it, style = MiuixTheme.textStyles.footnote1, color = colors.onSurfaceVariantSummary)
        }
    }
}

@Composable
fun BulletinLaunchDialog(
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
    val choice = rememberChoiceState(bulletin.id, bulletin.options, bulletin.allowMultiple)
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
            if (bulletin.isPoll) {
                Spacer(Modifier.height(8.dp))
                PollMeta(bulletin)
            }
            if (bulletin.body.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                GithubMarkdown(
                    bulletin.body,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    textStyle = MiuixTheme.textStyles.body2,
                )
            }
            Spacer(Modifier.height(16.dp))
            if (bulletin.isPoll) {
                ChoiceList(choice, bulletin.options, bulletin.allowOther)
                val answers = choice.answers
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = { onSubmitPoll(answers) },
                    enabled = answers.isNotEmpty() && !choice.otherMissing,
                    colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.buttonColorsPrimary(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        when {
                            choice.otherMissing -> "填一下「其他」再提交"
                            answers.isEmpty() -> "选一项再提交"
                            else -> "提交"
                        },
                        color = MiuixTheme.colorScheme.onPrimary,
                    )
                }
            } else if (isForceUpdate) {
                Button(
                    onClick = onPrimary,
                    colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.buttonColorsPrimary(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("立即更新", color = MiuixTheme.colorScheme.onPrimary)
                }
                Spacer(Modifier.height(8.dp))
                TextButton(
                    text = "稍后",
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    colors = com.xjtu.toolbox.ui.components.appTonalTextButtonColors(),
                )
            } else {
                // 「知道了」是确认（记账后不再弹）；划走、点外面才是「稍后」
                TextButton(
                    text = "知道了",
                    onClick = onPrimary,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
            Spacer(Modifier.height(8.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}
