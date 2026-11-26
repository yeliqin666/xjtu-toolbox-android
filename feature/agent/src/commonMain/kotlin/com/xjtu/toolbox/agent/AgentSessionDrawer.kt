@file:OptIn(ExperimentalFoundationApi::class)

package com.xjtu.toolbox.agent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.xjtu.toolbox.util.campusDateTimeOf
import com.xjtu.toolbox.util.formatTwoDigits
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.time.Clock

/**
 * 窄屏的历史对话：从顶栏「历史」按钮右下方长出来的一张卡片菜单。放在按钮旁边（同一个父布局里）才锚得住。
 * 点外面、按返回收起；长按一条交给 [onLongPress]（改名 / 删除）。
 */
@Composable
internal fun SessionMenu(
    expanded: Boolean,
    sessions: List<AgentSession>,
    currentId: String?,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onLongPress: (AgentSession) -> Unit,
    onNewChat: () -> Unit,
) {
    val state = remember { MutableTransitionState(false) }
    state.targetState = expanded
    if (!state.currentState && !state.targetState) return
    val density = LocalDensity.current
    val provider = remember(density) { with(density) { BelowEnd(gap = 6.dp.roundToPx(), margin = 12.dp.roundToPx()) } }
    Popup(popupPositionProvider = provider, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        AnimatedVisibility(
            visibleState = state,
            enter = fadeIn(tween(150)) + scaleIn(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.8f, transformOrigin = TransformOrigin(1f, 0f)),
            exit = fadeOut(tween(120)) + scaleOut(tween(140), targetScale = 0.92f, transformOrigin = TransformOrigin(1f, 0f)),
        ) {
            val shape = RoundedCornerShape(24.dp)
            val ink = MiuixTheme.colorScheme.onSurface
            Column(
                Modifier
                    .widthIn(max = 304.dp)
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .shadow(22.dp, shape, ambientColor = ink.copy(alpha = 0.16f), spotColor = ink.copy(alpha = 0.22f))
                    .clip(shape)
                    .background(MiuixTheme.colorScheme.surfaceContainer)
                    .border(0.5.dp, ink.copy(alpha = 0.07f), shape),
            ) {
                MenuHeader(count = sessions.count { it.id != AgentPushFocus.SESSION_ID }, onNewChat = onNewChat)
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                // 每一行依次淡入上浮，像从按钮那儿一条条落下来
                val appear = remember { Animatable(0f) }
                LaunchedEffect(Unit) { appear.animateTo(1f, tween(420)) }
                LazyColumn(contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 10.dp)) {
                    sessionItems(sessions, currentId, onSelect, onLongPress, stagger = { appear.value })
                }
            }
        }
    }
}

/** 宽屏常驻左栏：同一份列表，不弹出。 */
@Composable
internal fun SessionListPane(
    sessions: List<AgentSession>,
    currentId: String?,
    onSelect: (String) -> Unit,
    onLongPress: (AgentSession) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().navigationBarsPadding().padding(top = 8.dp)) {
        Text(
            "历史对话",
            modifier = Modifier.padding(start = 18.dp, top = 6.dp, bottom = 8.dp),
            style = MiuixTheme.textStyles.title3,
            fontWeight = FontWeight.Bold,
        )
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
            sessionItems(sessions, currentId, onSelect, onLongPress, stagger = null)
        }
    }
}

/** 按顶栏按钮右对齐、贴在它下面；夹在窗口左右 [margin] 以内。 */
private class BelowEnd(private val gap: Int, private val margin: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (anchorBounds.right - popupContentSize.width)
            .coerceAtMost(windowSize.width - popupContentSize.width - margin)
            .coerceAtLeast(margin)
        return IntOffset(x, anchorBounds.bottom + gap)
    }
}

@Composable
private fun MenuHeader(count: Int, onNewChat: () -> Unit) {
    val accent = MiuixTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 14.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("历史对话", style = MiuixTheme.textStyles.title4, fontWeight = FontWeight.Bold)
            Text(
                if (count == 0) "还没有对话" else "$count 个对话",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Row(
            Modifier
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.12f))
                .clickable(onClick = onNewChat)
                .padding(start = 10.dp, end = 13.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(Icons.Default.Add, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
            Text("新对话", style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Medium, color = accent)
        }
    }
}

/** 置顶的推送记录 + 按日期分组的会话。[stagger] 给出入场进度时各行错峰淡入，null 不演。 */
private fun LazyListScope.sessionItems(
    sessions: List<AgentSession>,
    currentId: String?,
    onSelect: (String) -> Unit,
    onLongPress: (AgentSession) -> Unit,
    stagger: (() -> Float)?,
) {
    val model = buildSessionList(sessions, Clock.System.now().toEpochMilliseconds())
    if (model.isEmpty) {
        item(key = "empty") {
            Column(Modifier.fillMaxWidth().padding(vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.History, contentDescription = null, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f), modifier = Modifier.size(30.dp))
                Spacer(Modifier.height(8.dp))
                Text("聊过的会出现在这里", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        return
    }
    var row = 0
    fun Modifier.enter(i: Int): Modifier = if (stagger == null) this else graphicsLayer {
        val p = ((stagger() - minOf(i, 8) * 0.07f) / 0.45f).coerceIn(0f, 1f)
        alpha = p
        translationY = (1f - p) * 10.dp.toPx()
    }
    model.pinned?.let { pinned ->
        val i = row++
        item(key = pinned.id) {
            SessionRow(pinned, pinned.id == currentId, onSelect = { onSelect(pinned.id) }, icon = Icons.Default.Notifications, modifier = Modifier.enter(i))
        }
    }
    model.sections.forEach { section ->
        val i = row++
        item(key = "group-${section.group.name}") {
            Text(
                section.group.label,
                modifier = Modifier.enter(i).padding(start = 12.dp, top = 10.dp, bottom = 4.dp),
                style = MiuixTheme.textStyles.footnote2,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        section.sessions.forEach { session ->
            val j = row++
            item(key = session.id) {
                SessionRow(
                    session,
                    session.id == currentId,
                    onSelect = { onSelect(session.id) },
                    onLongClick = { onLongPress(session) },
                    modifier = Modifier.enter(j),
                )
            }
        }
    }
}

/** 会话的小方块用的几种颜色，按 id 挑一种，同一条对话每次都是同一个色。 */
private val SWATCHES = listOf(
    Color(0xFF3B82F6), Color(0xFF14B8A6), Color(0xFFF59E0B), Color(0xFFEC4899), Color(0xFF8B5CF6), Color(0xFF22C55E),
)

/**
 * 一条会话：左边是标题首字的彩色圆角方块（推送记录用图标），中间标题和时间，当前这条浅主色底、右边打勾。
 * 没有 [onLongClick] 就不响应长按。
 */
@Composable
private fun SessionRow(
    session: AgentSession,
    isCurrent: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    icon: ImageVector? = null,
) {
    val accent = MiuixTheme.colorScheme.primary
    val bg by animateColorAsState(if (isCurrent) accent.copy(alpha = 0.10f) else Color.Transparent, tween(200), label = "sessionRowBg")
    val swatch = if (icon != null) accent else SWATCHES[(session.id.hashCode() and Int.MAX_VALUE) % SWATCHES.size]
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .combinedClickable(onClick = onSelect, onLongClick = onLongClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(swatch.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = swatch, modifier = Modifier.size(18.dp))
            } else {
                Text(
                    session.title.trim().take(1).ifEmpty { "·" },
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = FontWeight.Bold,
                    color = swatch,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                session.title,
                style = MiuixTheme.textStyles.body2,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isCurrent) accent else MiuixTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(1.dp))
            Text(formatSessionTime(session.updatedAt), style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        if (isCurrent) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.Check, contentDescription = "当前对话", tint = accent, modifier = Modifier.size(18.dp))
        }
    }
}

internal fun formatSessionTime(ts: Long): String =
    // `SimpleDateFormat("MM-dd HH:mm")` 的跨端等价（commonMain 没有 `java.text`）
    campusDateTimeOf(ts)
        .let { "${formatTwoDigits(it.monthNumber)}-${formatTwoDigits(it.dayOfMonth)} " +
            "${formatTwoDigits(it.hour)}:${formatTwoDigits(it.minute)}" }
