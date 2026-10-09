package com.xjtu.toolbox.game.ui

import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjtu.toolbox.ui.theme.LocalIsDarkTheme
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 三个棋类游戏（五子棋 / 围棋 / 象棋）共用的画法：木纹棋盘、带高光的立体棋子、
 * 顶部「谁对谁、轮到谁」的对阵栏、底部的一排操作按钮。只管长相，不碰规则。
 */

/** 一种棋子的配色：[base] 是主体色，[shine] 是左上高光处的颜色。 */
class StonePaint(val base: Color, val shine: Color, val rim: Color = Color.Black.copy(alpha = 0.18f))

object StonePaints {
    val GoBlack = StonePaint(Color(0xFF15171B), Color(0xFF5B616B))
    val GoWhite = StonePaint(Color(0xFFDADCE0), Color(0xFFFFFFFF), Color.Black.copy(alpha = 0.22f))
    /** 西交蓝、上交红：校徽的主色，不随深浅色主题走。 */
    val Xjtu = StonePaint(Color(0xFF123A7A), Color(0xFF5B8FE0))
    val Sjtu = StonePaint(Color(0xFFA10E22), Color(0xFFF0626F))
    /** 象棋子的象牙底。 */
    val Ivory = StonePaint(Color(0xFFEAD9B8), Color(0xFFFFF8EA), Color(0xFF8A6A3A).copy(alpha = 0.5f))
}

val XjtuColor = Color(0xFF1B4C9C)
val SjtuColor = Color(0xFFC8162E)

/** 木纹棋盘的颜色：浅色像榧木，深色模式压暗一档，棋子依然看得清。 */
class WoodColors(val light: Color, val dark: Color, val line: Color)

@Composable
fun woodColors(): WoodColors = if (LocalIsDarkTheme.current) {
    WoodColors(Color(0xFFB48A55), Color(0xFF9A7142), Color(0xFF3B2410).copy(alpha = 0.85f))
} else {
    WoodColors(Color(0xFFF0CF96), Color(0xFFE2B574), Color(0xFF5A3A17).copy(alpha = 0.8f))
}

/** 木纹底：斜向渐变 + 几道若隐若现的纹理，外加一圈柔和投影。 */
fun Modifier.woodBoard(colors: WoodColors, shape: Shape = RoundedCornerShape(18.dp)): Modifier = this
    .shadow(10.dp, shape, ambientColor = Color(0x553B2410), spotColor = Color(0x553B2410))
    .clip(shape)
    .background(Brush.linearGradient(listOf(colors.light, colors.dark, colors.light)))
    .drawBehind {
        val grain = colors.line.copy(alpha = 0.05f)
        var y = size.height * 0.07f
        var i = 0
        while (y < size.height) {
            drawLine(grain, Offset(0f, y), Offset(size.width, y + size.height * 0.03f), strokeWidth = if (i % 3 == 0) 3f else 1.5f)
            y += size.height * (0.05f + (i % 4) * 0.018f)
            i++
        }
    }

/**
 * 画一颗立体棋子：先画一圈落在棋盘上的影子，再画径向渐变的本体，最后一道细边。
 * [lift] > 1 时影子拉远，看着像被拿起来了（象棋选中）。
 */
fun DrawScope.drawStone(
    center: Offset,
    radius: Float,
    paint: StonePaint,
    alpha: Float = 1f,
    scale: Float = 1f,
    lift: Float = 1f,
) {
    val r = radius * scale
    if (r <= 0f) return
    drawCircle(
        Color.Black.copy(alpha = 0.22f * alpha),
        radius = r * 1.02f,
        center = center + Offset(r * 0.08f * lift, r * 0.12f * lift),
    )
    drawCircle(
        Brush.radialGradient(
            colors = listOf(paint.shine, paint.base),
            center = center + Offset(-r * 0.35f, -r * 0.4f),
            radius = r * 1.5f,
        ),
        radius = r,
        center = center,
        alpha = alpha,
    )
    drawCircle(paint.rim, radius = r, center = center, style = Stroke(width = r * 0.06f), alpha = alpha)
}

/**
 * 落子动画的进度：[key] 变了就从 0 弹到 1。绘制阶段读 `.value`，只触发重画，不重组。
 * 首次进入不播，免得一开页面所有棋子都在弹。
 */
@Composable
fun rememberDropProgress(key: Any?): Animatable<Float, *> {
    val anim = remember { Animatable(1f) }
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(key) {
        if (first[0]) {
            first[0] = false
            return@LaunchedEffect
        }
        anim.snapTo(0f)
        anim.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow))
    }
    return anim
}

/** 对阵栏里一方的信息。 */
class Contender(val name: String, val detail: String, val paint: StonePaint, val accent: Color = paint.base)

/**
 * 顶部对阵栏：左右两方各一张小卡，轮到谁谁的卡亮起来；[thinking] 时亮着的那方转个圈。
 * [activeLeft] 为 null 表示对局结束，两边都不亮。
 */
@Composable
fun VersusBar(
    left: Contender,
    right: Contender,
    activeLeft: Boolean?,
    modifier: Modifier = Modifier,
    thinking: Boolean = false,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ContenderCard(left, active = activeLeft == true, thinking = thinking && activeLeft == true)
        Text("VS", fontSize = 13.sp, fontWeight = FontWeight.Black, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        ContenderCard(right, active = activeLeft == false, thinking = thinking && activeLeft == false)
    }
}

@Composable
private fun RowScope.ContenderCard(c: Contender, active: Boolean, thinking: Boolean) {
    val accent = c.accent
    val bg by animateColorAsState(
        if (active) accent.copy(alpha = 0.14f) else MiuixTheme.colorScheme.surfaceContainer,
        tween(220),
        label = "contenderBg",
    )
    val border by animateColorAsState(
        if (active) accent.copy(alpha = 0.55f) else Color.Transparent,
        tween(220),
        label = "contenderBorder",
    )
    Row(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .border(1.5.dp, border, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(24.dp)) { drawStone(center, size.minDimension / 2f * 0.9f, c.paint) }
            if (thinking) {
                CircularProgressIndicator(size = 26.dp, strokeWidth = 2.dp, progress = null)
            }
        }
        Column(Modifier.padding(start = 10.dp)) {
            Text(
                c.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (thinking) "思考中…" else if (active) "该落子了" else c.detail,
                fontSize = 12.sp,
                color = if (active) accent else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 一个底部操作：文字 + 是否可用 + 是否是主要操作。 */
class GameAction(val label: String, val enabled: Boolean = true, val primary: Boolean = false, val onClick: () -> Unit)

/** 底部一排等宽的操作按钮，胶囊形；主要操作用主色。 */
@Composable
fun GameActionRow(actions: List<GameAction>, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.forEach { a ->
            val primary = MiuixTheme.colorScheme.primary
            val bg = when {
                a.primary -> primary
                else -> MiuixTheme.colorScheme.surfaceContainer
            }
            val fg = when {
                a.primary -> MiuixTheme.colorScheme.onPrimary
                else -> MiuixTheme.colorScheme.onSurface
            }
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(bg.copy(alpha = if (a.enabled) 1f else 0.5f))
                    .then(
                        if (a.enabled) Modifier.clickable(onClick = a.onClick) else Modifier
                    )
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    a.label,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = fg.copy(alpha = if (a.enabled) 1f else 0.45f),
                    maxLines = 1,
                )
            }
        }
    }
}

/** 状态提示条：固定高度，文字变化不会把下面的棋盘顶来顶去。 */
@Composable
fun GameHint(text: String?, modifier: Modifier = Modifier, color: Color = MiuixTheme.colorScheme.onSurfaceVariantSummary) {
    Box(modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        // 提示换内容（轮到谁、谁赢了）时新的一句轻轻弹进来，胜负那一刻有个「落定」的感觉
        androidx.compose.animation.AnimatedContent(
            targetState = text ?: " ",
            transitionSpec = {
                (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)) +
                    androidx.compose.animation.scaleIn(
                        initialScale = 0.9f,
                        animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 600f),
                    )) togetherWith androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120))
            },
            label = "gameHint",
        ) { t ->
            Text(
                t,
                fontSize = 13.sp,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
