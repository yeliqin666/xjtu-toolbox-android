@file:OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)

package com.xjtu.toolbox.agent

import com.xjtu.toolbox.util.formatTwoDigits
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.xjtu.toolbox.util.campusDateTimeOf
import kotlin.time.Clock

/** 窄屏的全高模态抽屉：盖住顶栏和状态栏，宽度为屏宽的 85%（最多 320dp），点遮罩关闭。 */
@Composable
internal fun SessionDrawer(
    open: Boolean,
    sessions: List<AgentSession>,
    currentId: String?,
    onClose: () -> Unit,
    onNew: () -> Unit,
    onSelect: (String) -> Unit,
    onLongPress: (AgentSession) -> Unit,
) {
    AnimatedVisibility(
        visible = open,
        enter = fadeIn(animationSpec = tween(180)),
        exit = fadeOut(animationSpec = tween(140)),
        modifier = Modifier.zIndex(1f)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onClose() }
        )
    }
    BoxWithConstraints(Modifier.fillMaxSize().zIndex(2f)) {
        AnimatedVisibility(
            visible = open,
            enter = slideInHorizontally(animationSpec = tween(260)) { -it },
            exit = slideOutHorizontally(animationSpec = tween(220)) { -it },
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(minOf(maxWidth * 0.85f, 320.dp)),
                shape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
                color = MiuixTheme.colorScheme.surfaceContainer,
            ) {
                SessionListPane(
                    sessions = sessions,
                    currentId = currentId,
                    onNew = onNew,
                    onSelect = onSelect,
                    onLongPress = onLongPress,
                    modifier = Modifier.statusBarsPadding(),
                )
            }
        }
    }
}

/**
 * 会话列表本体（头部 + 搜索 + 置顶的推送记录 + 按日期分组的会话），不含容器。
 * 窄屏被 [SessionDrawer] 包在遮罩和滑入动画里，宽屏直接当常驻左栏。
 */
@Composable
internal fun SessionListPane(
    sessions: List<AgentSession>,
    currentId: String?,
    onNew: () -> Unit,
    onSelect: (String) -> Unit,
    onLongPress: (AgentSession) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val model = buildSessionList(sessions, query, Clock.System.now().toEpochMilliseconds())
    Column(
        modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp)
            .padding(top = 8.dp, bottom = 12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 6.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "历史对话",
                modifier = Modifier.weight(1f),
                style = MiuixTheme.textStyles.title3,
                fontWeight = FontWeight.Bold,
            )
            Row(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.14f))
                    .clickable(onClick = onNew)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "新对话",
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.primary,
                )
            }
        }

        TextField(
            value = query,
            onValueChange = { query = it },
            label = "搜索对话",
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))

        LazyColumn(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (model.isEmpty) {
                item {
                    EmptyHint(
                        title = if (sessions.isEmpty()) "还没有任何对话" else "没有匹配的对话",
                        detail = if (sessions.isEmpty()) "点上面「新对话」开始" else "换个关键词试试",
                    )
                }
            }
            model.pinned?.let { pinned ->
                item(key = pinned.id) {
                    SessionRow(
                        session = pinned,
                        isCurrent = pinned.id == currentId,
                        icon = Icons.Default.Notifications,
                        onSelect = { onSelect(pinned.id) },
                    )
                }
                item(key = "pinned-divider") {
                    HorizontalDivider(Modifier.padding(horizontal = 6.dp, vertical = 6.dp))
                }
            }
            model.sections.forEach { section ->
                item(key = "group-${section.group.name}") {
                    Text(
                        section.group.label,
                        modifier = Modifier.padding(start = 10.dp, top = 8.dp, bottom = 4.dp),
                        style = MiuixTheme.textStyles.footnote1,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                items(section.sessions, key = { it.id }) { session ->
                    SessionRow(
                        session = session,
                        isCurrent = session.id == currentId,
                        onSelect = { onSelect(session.id) },
                        onLongClick = { onLongPress(session) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyHint(title: String, detail: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.List,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(12.dp))
        Text(title, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Spacer(Modifier.height(4.dp))
        Text(
            detail,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f),
        )
    }
}

/**
 * 一条会话。当前会话靠「左侧主色竖条 + 浅色底」区分，其余保持无底；
 * 带 [icon] 的是置顶条目（用图标代替竖条），没有 [onLongClick] 就不弹菜单。
 */
@Composable
private fun SessionRow(
    session: AgentSession,
    isCurrent: Boolean,
    onSelect: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    icon: ImageVector? = null,
) {
    val bg by animateColorAsState(
        targetValue = if (isCurrent) MiuixTheme.colorScheme.primary.copy(alpha = 0.10f)
        else Color.Transparent,
        animationSpec = tween(200),
        label = "sessionRowBg",
    )
    val accent = if (isCurrent) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .combinedClickable(onClick = onSelect, onLongClick = onLongClick)
            .padding(start = 10.dp, end = 10.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
        } else {
            Box(
                Modifier
                    .width(3.dp)
                    .height(18.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (isCurrent) MiuixTheme.colorScheme.primary else Color.Transparent),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                session.title,
                style = MiuixTheme.textStyles.body2,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = accent,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                formatSessionTime(session.updatedAt),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

private fun formatSessionTime(ts: Long): String =
    // `SimpleDateFormat("MM-dd HH:mm")` 的跨端等价（commonMain 没有 `java.text`）：
    // 北京时间 + 同样补零，年月日之外的部分本来就是稳定格式。
    campusDateTimeOf(ts)
        .let { "${formatTwoDigits(it.monthNumber)}-${formatTwoDigits(it.dayOfMonth)} " +
            "${formatTwoDigits(it.hour)}:${formatTwoDigits(it.minute)}" }
