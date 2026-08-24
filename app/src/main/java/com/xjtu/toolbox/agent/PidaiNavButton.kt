package com.xjtu.toolbox.agent

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieClipSpec
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 底栏正中的屁岱按钮。
 *
 * 它**不是**第五个标签页：点击是 push 到 AGENT 路由，没有选中态，所以刻意不复用
 * NavigationBarItem 的灰度线性图标样式——满色形象 + 中心位 + 会动，三重差异叠加，
 * 用户一眼能认出这是"另一类东西"。
 *
 * 形象取自 Google Noto Animated Emoji 的 🤖（OFL-1.1 / Apache-2.0，可商用免署名）。
 * 官方只给一整条 164 帧动画，但它是**分段命名**的，切段就能当多个状态用，
 * 不必额外找素材、也不必改 JSON：
 *
 * | 帧段       | 官方图层名       | 这里用作                                |
 * |-----------|-----------------|----------------------------------------|
 * | 68        | INTRO A 末尾     | 待命静帧（站定姿态，见 REST_FRAME 注释）  |
 * | 24 – 70   | wifi 1–6        | 头顶冒信号波纹，机器人本体不动             |
 * | 70 – 164  | SPIN Front/Back | 点击后的一次性转身反馈                    |
 *
 * 波纹段兼作两种用途：偶发微动播一遍，有主动提醒时循环播。
 *
 * 性能上最要紧的一条：底栏常驻，**绝不能一直播**。绝大多数时间 [isPlaying] 是 false，
 * Lottie 停在静止帧上等价于一张静态图；只有偶发微动、有提醒、被点击这三种情况才起转。
 */
@Composable
fun PidaiNavButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 40.dp,
    excited: Boolean = false,
    selected: Boolean = false,
    /**
     * 整体上移量。经典底栏里邻居的图标压在 64dp 项高的上半部（顶部内边距 8dp + 26dp 图标），
     * 文字在下半部；屁岱没有文字，纯居中会显得**整个沉下去**，和一排图标不在一条视觉线上。
     * 上移一点让它的重心回到图标那条线附近，同时仍比邻居大一圈、略微探进文字区。
     */
    liftUp: Dp = 0.dp,
) {
    val scope = rememberCoroutineScope()
    val bounce = remember { Animatable(1f) }
    val interactionSource = remember { MutableInteractionSource() }

    val composition by rememberLottieComposition(
        LottieCompositionSpec.RawRes(com.xjtu.toolbox.R.raw.pidai_robot),
    )

    // 三个状态互斥，优先级：点击 > 提醒 > 偶发微动。
    // 用一个 state 表达而不是三个 boolean，避免出现"既在转身又在冒波纹"的叠加态。
    var beat by remember { mutableStateOf(PidaiBeat.REST) }

    // 提醒态跟着 excited 走。
    LaunchedEffect(excited) {
        if (excited) beat = PidaiBeat.ALERT
        else if (beat == PidaiBeat.ALERT) beat = PidaiBeat.REST
    }
    // 一次性段落播完自己落回静止。
    //
    // 这里用「按时长 delay」而不是「盯着 progress >= 1f」：progress 每帧都变，
    // 拿它当 LaunchedEffect 的 key 会导致协程每帧重启一次，白烧。
    // 段落时长是常量（帧数 / 60fps / speed），直接算出来等就行。
    LaunchedEffect(beat) {
        val holdMs = when (beat) {
            PidaiBeat.TAP -> TAP_DURATION_MS
            PidaiBeat.IDLE -> IDLE_DURATION_MS
            else -> return@LaunchedEffect
        }
        delay(holdMs)
        beat = if (excited) PidaiBeat.ALERT else PidaiBeat.REST
    }
    // 偶发微动：只在真正闲着的时候插播，别打断提醒和点击。
    LaunchedEffect(Unit) {
        while (true) {
            delay((25_000L..45_000L).random())
            if (beat == PidaiBeat.REST) beat = PidaiBeat.IDLE
        }
    }

    val clip = when (beat) {
        PidaiBeat.REST -> LottieClipSpec.Frame(REST_FRAME, REST_FRAME + 1)
        PidaiBeat.IDLE -> LottieClipSpec.Frame(WAVE_START, WAVE_END)
        PidaiBeat.ALERT -> LottieClipSpec.Frame(WAVE_START, WAVE_END)
        PidaiBeat.TAP -> LottieClipSpec.Frame(SPIN_START, SPIN_END)
    }
    val progress by animateLottieCompositionAsState(
        composition = composition,
        clipSpec = clip,
        isPlaying = beat != PidaiBeat.REST,
        iterations = if (beat == PidaiBeat.ALERT) Int.MAX_VALUE else 1,
        speed = if (beat == PidaiBeat.IDLE) 0.85f else 1f,
        // 每次换段都从段首重放，否则 Lottie 会拿上一段的进度接着走，动作会从中间"跳"进来。
        restartOnPlay = true,
    )
    // 脚下的主色柔光身兼两职：**选中态**给一层淡的（它没有文字标签，不然看不出这个
    // tab 正开着），**有提醒**时给一层浓的。用 drawBehind 画径向渐变而不是加实心圆底：
    // 实心底会把满色的机器人圈死成一颗"按钮"，柔光则是它自己在发亮。
    val glowAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = when {
            excited -> 1f
            selected -> 0.45f
            else -> 0f
        },
        animationSpec = tween(400),
        label = "pidaiGlow",
    )
    val accent = MiuixTheme.colorScheme.primary

    Box(
        modifier = modifier
            .semantics { contentDescription = "屁岱助手" }
            .selectable(
                selected = false,
                onClick = {
                    beat = PidaiBeat.TAP
                    scope.launch {
                        bounce.snapTo(0.80f)
                        bounce.animateTo(
                            1f,
                            spring(dampingRatio = 0.32f, stiffness = Spring.StiffnessMediumLow),
                        )
                    }
                    onClick()
                },
                role = Role.Button,
                interactionSource = interactionSource,
                indication = null,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(diameter)
                .graphicsLayer {
                    scaleX = bounce.value
                    scaleY = bounce.value
                    translationY = -liftUp.toPx()
                }
                .drawBehind {
                    if (glowAlpha <= 0f) return@drawBehind
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                accent.copy(alpha = 0.34f * glowAlpha),
                                Color.Transparent,
                            ),
                            center = Offset(size.width / 2f, size.height / 2f),
                            radius = size.minDimension * 0.62f,
                        ),
                        radius = size.minDimension * 0.62f,
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            if (composition != null) {
                LottieAnimation(
                    composition = composition,
                    progress = { progress },
                    // 入场段把机器人放大到超出 1024 画布，天线杆顶因此被画布边界切平。
                    // 关掉画布裁剪，天线就完整了；同时把画面按 0.86 收进容器，
                    // 给溢出的天线留出余量，不至于顶到底栏边缘。
                    clipToCompositionBounds = false,
                    modifier = Modifier.size(diameter * 0.86f),
                )
            } else {
                // 首帧解析完成前的占位：直接空着会让底栏中间塌一个洞。
                androidx.compose.foundation.Image(
                    painter = painterResource(com.xjtu.toolbox.R.drawable.shortcut_agent),
                    contentDescription = null,
                    modifier = Modifier.size(diameter * 0.6f),
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(accent),
                )
            }
        }
    }
}

private enum class PidaiBeat { REST, IDLE, ALERT, TAP }

/**
 * 待命静帧。
 *
 * 官方在第 13 帧打了个 `rest` marker，但那一帧其实落在**入场放大过弹的中途**——
 * 机器人这时被放大到超出 1024 画布，天线杆顶直接被裁掉，位置也偏上。
 * 真正"站定"的姿态在 INTRO A 段的末尾，所以静帧取这里而不是听 marker 的。
 */
private const val REST_FRAME = 13

/**
 * 头顶信号波纹段。
 *
 * 波纹图层（wifi 1–6）覆盖 24–116 帧，但 70 帧起 SPIN 就开始转身了，
 * 取满会把转身混进来，循环时 116→24 还会硬跳一下。
 * 只取 24–70：机器人正面站定不动，纯波纹一圈圈往外冒，首尾同姿态，循环无缝。
 */
private const val WAVE_START = 24
private const val WAVE_END = 70

/** 转身段，一次性播完。 */
private const val SPIN_START = 70
private const val SPIN_END = 164

/** 波纹段 46 帧 @60fps，再除以 0.85 倍速。 */
private const val IDLE_DURATION_MS = 900L

/** SPIN 段 94 帧 @60fps。 */
private const val TAP_DURATION_MS = 1_570L
