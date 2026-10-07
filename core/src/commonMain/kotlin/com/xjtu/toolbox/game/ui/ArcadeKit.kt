package com.xjtu.toolbox.game.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import top.yukonga.miuix.kmp.utils.SinkFeedback
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 分数类小游戏共用的零件：2048 的分数卡，以及三个游戏共用的暂停 / 结算面板。
 */

/** 一格数字卡；[highlight] 的用主色渐变，当本局分数用。 */
@Composable
fun ScoreCard(label: String, value: String, modifier: Modifier = Modifier, highlight: Boolean = false) {
    val primary = MiuixTheme.colorScheme.primary
    val surface = MiuixTheme.colorScheme.surfaceContainer
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (highlight) Brush.linearGradient(listOf(primary, primary.copy(alpha = 0.78f)))
                else Brush.linearGradient(listOf(surface, surface))
            )
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            style = MiuixTheme.textStyles.footnote2,
            color = if (highlight) Color.White.copy(alpha = 0.85f) else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Text(
            value,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = if (highlight) Color.White else MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

/**
 * 盖在游戏上的暂停 / 结算面板：配色跟着游戏走——[panel] 是面板底色，[ink] 是字色，
 * 第一个按钮用 [accent] 填充。从不可见开始挂上，淡入才会播。
 */
@Composable
fun GameMenu(
    title: String,
    subtitle: String,
    actions: List<Pair<String, () -> Unit>>,
    accent: Color,
    panel: Color,
    ink: Color,
    scrim: Color = Color.Black.copy(alpha = 0.45f),
    big: String? = null,
) {
    val visible = remember { MutableTransitionState(false) }.apply { targetState = true }
    AnimatedVisibility(visibleState = visible, enter = fadeIn(tween(200)) + scaleIn(initialScale = 0.9f), exit = fadeOut()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(scrim)
                // 吃掉点击，别漏到下面的游戏里
                .clickable(remember { MutableInteractionSource() }, indication = null) {},
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .padding(28.dp)
                    .widthIn(max = 340.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(panel)
                    .padding(horizontal = 22.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = ink.copy(alpha = 0.7f))
                if (big != null) {
                    Text(big, fontSize = 48.sp, fontWeight = FontWeight.Bold, color = ink)
                } else {
                    Spacer(Modifier.height(4.dp))
                }
                Text(subtitle, fontSize = 14.sp, color = ink.copy(alpha = 0.55f))
                Spacer(Modifier.height(22.dp))
                actions.forEachIndexed { i, (label, onClick) ->
                    if (i > 0) Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (i == 0) accent else ink.copy(alpha = 0.06f))
                            .clickable(remember { MutableInteractionSource() }, SinkFeedback(), onClick = onClick),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = if (i == 0) Color.White else ink)
                    }
                }
            }
        }
    }
}
