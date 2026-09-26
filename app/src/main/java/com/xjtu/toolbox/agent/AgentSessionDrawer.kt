@file:OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)

package com.xjtu.toolbox.agent

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SessionDrawer(
    open: Boolean,
    sessions: List<AgentSession>,
    currentId: String?,
    onClose: () -> Unit,
    onNew: () -> Unit,
    onSelect: (String) -> Unit,
    onRequestDelete: (AgentSession) -> Unit,
    bottomReserve: Dp = 0.dp,
) {
    // 半透明遮罩，点击关闭
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
    // 左侧抽屉面板。右侧收圆角（是盖在页面上的一层，不是把页面裁掉一条）；
    // 「新建对话」用整宽按钮而不是小加号；会话条目默认无底，只有当前会话有浅底 + 竖条。
    AnimatedVisibility(
        visible = open,
        enter = slideInHorizontally(animationSpec = tween(260)) { -it },
        exit = slideOutHorizontally(animationSpec = tween(220)) { -it },
        modifier = Modifier.zIndex(2f)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxHeight()
                .width(304.dp),
            shape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
            color = MiuixTheme.colorScheme.surfaceContainer,
        ) {
            SessionListPane(
                sessions = sessions,
                currentId = currentId,
                onNew = onNew,
                onSelect = onSelect,
                onRequestDelete = onRequestDelete,
                bottomReserve = bottomReserve,
            )
        }
    }

    // 删除确认改由外层 OverlayDialog 处理，保证走 MIUIX 弹窗宿主。
}
/**
 * 会话列表本体（新建按钮 + 会话行），不含容器。
 *
 * 手机上被 [SessionDrawer] 包在遮罩 + 滑入动画里（行为与抽出前一致），
 * 宽屏下直接当常驻左栏用。
 */
@Composable
internal fun SessionListPane(
    sessions: List<AgentSession>,
    currentId: String?,
    onNew: () -> Unit,
    onSelect: (String) -> Unit,
    onRequestDelete: (AgentSession) -> Unit,
    /** 悬浮底栏盖在抽屉上面，列表底部要让出它的高度。 */
    bottomReserve: Dp = 0.dp,
) {
        Column(
            Modifier
                .fillMaxSize()
                // 不加 statusBarsPadding：抽屉现在活在 Scaffold 的内容区里，
                // 顶栏已经把状态栏那段让开了，再加一次就是在「对话」上面白白空出
                // 一整条状态栏的高度——那就是之前看着头重脚轻的原因。
                .navigationBarsPadding()
                .padding(horizontal = 12.dp)
                .padding(top = 4.dp, bottom = 12.dp + bottomReserve)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "对话",
                    style = MiuixTheme.textStyles.title3,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(8.dp))
                if (sessions.isNotEmpty()) {
                    Text(
                        sessions.size.toString(),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.14f))
                    .clickable(onClick = onNew)
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "新建对话",
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.primary,
                )
            }

            Spacer(Modifier.height(10.dp))

            LazyColumn(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (sessions.isEmpty()) {
                    item {
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
                            Text(
                                "还没有任何对话",
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "点上面「新建对话」开始",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f),
                            )
                        }
                    }
                }
                items(sessions, key = { it.id }) { session ->
                    SessionRow(
                        session = session,
                        isCurrent = session.id == currentId,
                        onSelect = { onSelect(session.id) },
                        onRequestDelete = { onRequestDelete(session) },
                    )
                }
            }
        }
}
/** 抽屉里的一条会话。当前会话靠「左侧主色竖条 + 浅色底」区分，其余保持无底。 */
@Composable
private fun SessionRow(
    session: AgentSession,
    isCurrent: Boolean,
    onSelect: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    val bg by animateColorAsState(
        targetValue = if (isCurrent) MiuixTheme.colorScheme.primary.copy(alpha = 0.10f)
        else Color.Transparent,
        animationSpec = tween(200),
        label = "sessionRowBg",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable(onClick = onSelect)
            .padding(start = 10.dp, end = 4.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(18.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(
                    if (isCurrent) MiuixTheme.colorScheme.primary else Color.Transparent
                ),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                session.title,
                style = MiuixTheme.textStyles.body2,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isCurrent) MiuixTheme.colorScheme.primary
                else MiuixTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                formatSessionTime(session.updatedAt),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Box(
            Modifier
                .size(30.dp)
                .clip(CircleShape)
                .clickable(onClick = onRequestDelete),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "删除",
                // 删除是破坏性动作但不是主要动作：默认压得很淡，需要时找得到就行。
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.55f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
private fun formatSessionTime(ts: Long): String =
    java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(ts))
