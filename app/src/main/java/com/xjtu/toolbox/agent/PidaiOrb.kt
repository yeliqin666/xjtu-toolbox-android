package com.xjtu.toolbox.agent

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.xjtu.toolbox.shell.ShellBarAccessoryForm
import com.xjtu.toolbox.ui.glass.GLASS_BAR_HEIGHT
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 底栏右边那颗小岱球（#557）：点一下打开小岱，按住说话、松手发，往上滑取消。
 *
 * 玻璃 / 悬浮胶囊旁是一颗带投影的圆盘，和胶囊同高；经典底栏里是最右那一格，不画圆盘；侧栏里放在最底下。
 * 按住时球上方浮出一枚胶囊：在听画实时波形，滑到取消区变红「松手取消」，转写中是三点跳动。
 */
@Composable
internal fun PidaiOrb(
    form: ShellBarAccessoryForm,
    voice: AgentVoiceInput?,
    onTap: () -> Unit,
    onHoldStart: () -> Unit,
    onHoldEnd: (cancel: Boolean) -> Unit,
    onBoundsChange: (Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = pidaiNavAppearance()
    val gaze = rememberPidaiGaze()
    val haptics = LocalHapticFeedback.current
    var armed by remember { mutableStateOf(false) }
    // 语音是全局一份，底栏球和子页悬浮球都看得到：只有被按住的那颗显示胶囊和波纹
    var mine by remember { mutableStateOf(false) }
    val voicePhase = voice?.phase ?: VoicePhase.IDLE
    LaunchedEffect(voicePhase) { if (voicePhase == VoicePhase.IDLE) mine = false }
    val phase = if (mine) voicePhase else VoicePhase.IDLE
    val disc = form == ShellBarAccessoryForm.GLASS || form == ShellBarAccessoryForm.FLOATING
    val accent = MiuixTheme.colorScheme.primary
    val surface = MiuixTheme.colorScheme.surface
    // 圆盘带一点主题色，和页面底色、玻璃胶囊都拉开；必须不透明：小岱的眼睛是按这个颜色挖的
    val paper = if (disc) lerp(surface, accent, if (surface.luminance() < 0.5f) 0.24f else 0.13f) else surface

    Box(
        modifier
            .onGloballyPositioned { onBoundsChange(it.boundsInRoot()) }
            .then(
                when (form) {
                    // 不能 fillMaxHeight：底栏的高度没封顶，会把整条栏撑成整屏
                    ShellBarAccessoryForm.CLASSIC -> Modifier.padding(horizontal = 14.dp)
                    ShellBarAccessoryForm.RAIL -> Modifier.padding(top = 12.dp, bottom = 16.dp)
                    else -> Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (phase != VoicePhase.IDLE) {
            VoiceRipples(
                phase = phase,
                level = { voice?.level ?: 0f },
                color = accent,
                modifier = if (disc) Modifier.size(GLASS_BAR_HEIGHT) else Modifier.size(46.dp),
            )
        }
        if (disc) {
            Box(
                Modifier
                    .size(GLASS_BAR_HEIGHT)
                    .shadow(10.dp, CircleShape, ambientColor = accent.copy(alpha = 0.3f), spotColor = accent.copy(alpha = 0.36f))
                    .clip(CircleShape)
                    .background(paper)
                    .border(1.dp, accent.copy(alpha = 0.22f), CircleShape),
            )
        }
        PidaiNavButton(
            onClick = onTap,
            excited = ProactiveBubbleHost.message != null,
            thinking = AgentThinkingHost.isThinking,
            diameter = if (form == ShellBarAccessoryForm.CLASSIC) 38.dp else 40.dp,
            paper = paper,
            ink = style.ink,
            cast = style.cast,
            shape = style.shape,
            gaze = gaze.look,
            voicePhase = phase,
            voiceLevel = { voice?.level ?: 0f },
            onHoldStart = {
                armed = false
                mine = true
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onHoldStart()
            },
            onHoldMove = { now ->
                armed = now
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            },
            onHoldEnd = { cancel ->
                armed = false
                onHoldEnd(cancel)
                // 没开起来（缺 Key、没授权）就不算这颗的
                if (voicePhase == VoicePhase.IDLE) mine = false
            },
            modifier = Modifier.then(gaze.track),
        )
        if (phase != VoicePhase.IDLE) {
            Popup(
                popupPositionProvider = remember { AboveAnchor() },
                properties = PopupProperties(focusable = false, clippingEnabled = false),
            ) {
                VoiceCapsule(phase = phase, armed = armed, level = { voice?.level ?: 0f })
            }
        }
    }
}

/** 浮在球上方：水平居中于球，但夹在屏幕左右 16dp 以内（球贴着右边）。 */
private class AboveAnchor : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val margin = (windowSize.width * 0.04f).toInt()
        val x = (anchorBounds.center.x - popupContentSize.width / 2)
            .coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin))
        val y = anchorBounds.top - popupContentSize.height - (anchorBounds.height * 0.25f).toInt()
        return IntOffset(x, y.coerceAtLeast(0))
    }
}

/** 按住说话时的胶囊：在听 = 波形 + 「正在听，上滑取消」；取消区 = 红色「松手取消」；转写 = 三点跳动。 */
@Composable
private fun VoiceCapsule(phase: VoicePhase, armed: Boolean, level: () -> Float) {
    val accent = MiuixTheme.colorScheme.primary
    val danger = MiuixTheme.colorScheme.error
    val tint by animateColorAsState(if (armed) danger else accent, tween(160), label = "voiceTint")
    // 最近一段的音量，画成从右往左滚的波形
    val history = remember { FloatArray(WAVE_BARS) }
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(phase) {
        if (phase != VoicePhase.RECORDING) return@LaunchedEffect
        history.fill(0f)
        while (true) {
            System.arraycopy(history, 1, history, 0, WAVE_BARS - 1)
            history[WAVE_BARS - 1] = level()
            frame++
            delay(55)
        }
    }
    Column(
        Modifier
            .shadow(12.dp, RoundedCornerShape(22.dp))
            .clip(RoundedCornerShape(22.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnimatedContent(
            targetState = phase == VoicePhase.TRANSCRIBING,
            transitionSpec = { (fadeIn(tween(180)) + scaleIn(initialScale = 0.9f)) togetherWith (fadeOut(tween(120)) + scaleOut(targetScale = 0.9f)) },
            label = "voiceCapsule",
        ) { transcribing ->
            if (transcribing) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TranscribingDots(accent)
                    Text("正在转成文字…", style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurface)
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Canvas(Modifier.width(168.dp).height(30.dp)) {
                        // 读一下帧号：波形每 55ms 往左滚一格时重画
                        if (frame < 0) return@Canvas
                        val gap = size.width / WAVE_BARS
                        val w = gap * 0.55f
                        for (i in 0 until WAVE_BARS) {
                            // 中间高两边低一点，没声音时留一条细线
                            val shape = 0.55f + 0.45f * kotlin.math.sin(Math.PI * (i + 0.5) / WAVE_BARS).toFloat()
                            val h = (size.height * (0.12f + 0.88f * history[i] * shape)).coerceAtLeast(3f)
                            drawRoundRect(
                                color = tint.copy(alpha = 0.35f + 0.65f * (i + 1f) / WAVE_BARS),
                                topLeft = Offset(i * gap + (gap - w) / 2, (size.height - h) / 2),
                                size = Size(w, h),
                                cornerRadius = CornerRadius(w / 2, w / 2),
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (armed) "松手取消" else "正在听，上滑取消",
                        style = MiuixTheme.textStyles.footnote1,
                        fontWeight = FontWeight.Medium,
                        color = if (armed) danger else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}

/**
 * 球外的声波纹：在听时三圈从球边往外扩，音量越大扩得越远越亮，贴着球还有一圈跟着音量鼓起的光晕；
 * 转写中换成一段弧绕着球转。画在球后面，不裁剪，波纹可以漫出底栏。
 */
@Composable
private fun VoiceRipples(phase: VoicePhase, level: () -> Float, color: Color, modifier: Modifier) {
    val loop = rememberInfiniteTransition(label = "voiceRipple")
    val wave by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "wave")
    val spin by loop.animateFloat(0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "spin")
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(240)) }
    Canvas(modifier) {
        val r0 = size.minDimension / 2
        val k = appear.value
        if (phase == VoicePhase.RECORDING) {
            val v = level().coerceIn(0f, 1f)
            drawCircle(color, r0 * (1.04f + 0.16f * v), alpha = k * (0.16f + 0.24f * v))
            for (i in 0 until 3) {
                val p = (wave + i / 3f) % 1f
                drawCircle(
                    color,
                    radius = r0 * (1.05f + p * (0.3f + 0.5f * v)),
                    alpha = k * (1f - p) * (0.3f + 0.6f * v),
                    style = Stroke(width = (2f + 2f * v).dp.toPx()),
                )
            }
        } else {
            val r = r0 + 5.dp.toPx()
            drawCircle(color, r, alpha = k * 0.14f, style = Stroke(width = 3.dp.toPx()))
            drawArc(
                color,
                startAngle = spin,
                sweepAngle = 110f,
                useCenter = false,
                topLeft = Offset(center.x - r, center.y - r),
                size = Size(2 * r, 2 * r),
                alpha = k,
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
            )
        }
    }
}

@Composable
private fun TranscribingDots(color: androidx.compose.ui.graphics.Color) {
    val phase = com.xjtu.toolbox.ui.components.rememberLoopPhase(900)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        for (k in 0..2) {
            Box(
                Modifier
                    .size(7.dp)
                    .graphicsLayer { alpha = com.xjtu.toolbox.ui.components.breath(phase.value, low = 0.25f, offset = k * 0.18f) }
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

private const val WAVE_BARS = 24
