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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.xjtu.toolbox.agent.skin.PidaiSkin
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 底栏正中的屁岱按钮。
 *
 * 它现在**也是**一个 tab（点击会把 `selectedTabOrdinal` 切到 `BottomTab.PIDAI`，
 * 有选中态），只是点击时还会顺带触发一句气泡（见 `ProactiveRules.pickOnTap`）。
 * 刻意不复用 NavigationBarItem 的灰度线性图标样式——满色形象 + 中心位 + 会动，
 * 三重差异叠加，用户一眼能认出这是"另一类东西"。
 *
 * 形象移植自 bloub 项目（x.ai 机器人头像的 SVG 复刻，MIT）：一个墨色形状按径向
 * 轮廓在状态间实时形变，两眼是身体上的洞。原 Lottie（Noto 🤖）按帧切段复用状态；
 * bloub 是纯程序动画，状态即状态，映射关系：
 *
 * | 底栏状态 | bloub 状态 | 表现                                       |
 * |---------|-----------|--------------------------------------------|
 * | REST    | 静止 idle | 静圆 + 专注表情，带眨眼与视线漂移               |
 * | IDLE    | wink      | 眨单眼、头微歪，一次性播完                    |
 * | THINKING| 轨道 orbit | 三角翻滚甩出彩色轨道环，整周期循环，生成结束为止     |
 * | ALERT   | notify    | 右上角弹出蓝色通知点并保持，循环直到提醒消失     |
 * | TAP     | comet     | 缩成小点、彩色彗尾绕它转一圈，再长回来          |
 *
 * 性能上最要紧的一条：底栏常驻，**待命态只有眨眼和视线漂移在动**（渲染器节流到
 * ~30fps），主动全速播动画的只有微动、提醒、被点击三种情况。
 */
@Composable
fun PidaiNavButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 40.dp,
    excited: Boolean = false,
    selected: Boolean = false,
    /** Agent 正在思考/生成（AgentThinkingHost.isThinking）：播三点脉冲，直到生成结束。 */
    thinking: Boolean = false,
    /**
     * 整体上移量。经典底栏里邻居的图标压在 64dp 项高的上半部（顶部内边距 8dp + 26dp 图标），
     * 文字在下半部；屁岱没有文字，纯居中会显得**整个沉下去**，和一排图标不在一条视觉线上。
     * 上移一点让它的重心回到图标那条线附近，同时仍比邻居大一圈、略微探进文字区。
     */
    liftUp: Dp = 0.dp,
    /**
     * 挖空之后眼洞露出的是真实背景，不再需要靠这个参数告诉 [BloubBotIcon] 底栏是什么颜色；
     * 保留只是为了不改调用方签名（经典栏、浮动栏、玻璃底栏都还在传），内部已不使用。
     */
    paper: Color = MiuixTheme.colorScheme.surface,
    /**
     * 身体墨色。"跟随主题"时应当传底栏前景色（`onSurface`），深色底栏才看得见；
     * 用户选了具体颜色则由调用方换成该色。
     */
    ink: Color = MiuixTheme.colorScheme.onSurface,
    /** 用户选择的形状轮廓；null = 圆形。 */
    shape: DoubleArray? = null,
    /** 当前导入皮肤；null 使用内置形状。 */
    skin: PidaiSkin? = null,
) {
    val scope = rememberCoroutineScope()
    val bounce = remember { Animatable(1f) }
    val interactionSource = remember { MutableInteractionSource() }

    // 状态互斥，优先级：点击 > 思考 > 提醒 > 偶发微动 > 待命。
    // 用一个 state 表达而不是多个 boolean，避免出现"既在彗星又在思考"的叠加态。
    var beat by remember { mutableStateOf(PidaiBeat.REST) }
    var customAction by remember { mutableStateOf<String?>(null) }
    val currentThinking by rememberUpdatedState(thinking)
    val currentExcited by rememberUpdatedState(excited)

    // 思考态跟着生成走：一开始就顶掉提醒和微动；结束回到当时的语境。
    LaunchedEffect(thinking) {
        if (thinking) {
            if (beat != PidaiBeat.TAP) beat = PidaiBeat.THINKING
        } else if (beat == PidaiBeat.THINKING) {
            beat = if (excited) PidaiBeat.ALERT else PidaiBeat.REST
        }
    }
    // 提醒态跟着 excited 走，但**不允许打断进行中的点击反馈和思考**：点进屁岱页的
    // 瞬间气泡常会重新弹出，excited 翻转若直接改写 beat，彗星刚起转就被腰斩。
    LaunchedEffect(excited) {
        if (excited) {
            if (beat != PidaiBeat.TAP && beat != PidaiBeat.THINKING) beat = PidaiBeat.ALERT
        } else if (beat == PidaiBeat.ALERT) beat = PidaiBeat.REST
    }
    // 一次性段落播完自己落回静止。
    //
    // 这里用「按时长 delay」而不是「盯着渲染进度」：进度每帧都变，
    // 拿它当 LaunchedEffect 的 key 会导致协程每帧重启一次，白烧。
    // 段落时长是常量，直接算出来等就行。
    LaunchedEffect(beat) {
        val holdMs = when (beat) {
            PidaiBeat.TAP -> skin?.motion?.actionFor("tap")?.duration?.times(1000)?.toLong() ?: COMET_HOLD_MS
            PidaiBeat.IDLE -> skin?.motion?.actionFor("idle")?.duration?.times(1000)?.toLong() ?: WINK_HOLD_MS
            else -> return@LaunchedEffect
        }
        delay(holdMs)
        beat = when {
            currentThinking -> PidaiBeat.THINKING
            currentExcited -> PidaiBeat.ALERT
            else -> PidaiBeat.REST
        }
    }
    val skinActionGeneration = PidaiSkinActionHost.generation
    LaunchedEffect(skinActionGeneration, skin?.cacheKey) {
        val requested = PidaiSkinActionHost.actionId?.takeIf { it in (skin?.motion?.actions ?: emptyMap()) }
            ?: return@LaunchedEffect
        customAction = requested
        val duration = skin?.motion?.actions?.get(requested)?.duration ?: 0.0
        delay((duration * 1000).toLong().coerceAtLeast(100L))
        if (customAction == requested) customAction = null
    }
    // 偶发微动：只在真正闲着的时候插播，别打断提醒、思考和点击。
    LaunchedEffect(Unit) {
        while (true) {
            delay((25_000L..45_000L).random())
            if (beat == PidaiBeat.REST) beat = PidaiBeat.IDLE
        }
    }

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
            // 画布比触摸区大一圈：球和通知点/彗尾需要更多作画空间，触摸目标保持 diameter。
            // 1.5 倍时球径约 1.23 倍触摸区（经典栏 ~46dp），是底栏高度约束下的舒适上限。
            BloubBotIcon(
                beat = beat,
                ink = ink,
                paper = paper,
                shape = shape,
                skin = skin,
                requestedAction = customAction,
                requestedActionGeneration = skinActionGeneration,
                modifier = Modifier.size(diameter * 1.5f),
            )
        }
    }
}

/** 互斥的动画状态，优先级 TAP > THINKING > ALERT > IDLE > REST。 */
internal enum class PidaiBeat { REST, IDLE, ALERT, TAP, THINKING }

/** wink 一次性的保持时长（bloub wink duration 1.6s）。 */
private const val WINK_HOLD_MS = 1_600L

/** 彗星：核在 1.85s 后开始长回、2.45s 长完，再留一点余量让尾巴融进 idle 的入场形变。 */
private const val COMET_HOLD_MS = 2_500L
