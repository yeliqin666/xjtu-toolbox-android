package com.xjtu.toolbox.ui.components

import kotlin.time.TimeSource

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.trace
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.PI
import kotlin.math.cos

/*
 * 全 App 共用的动效零件。规矩和屁岱页一致，新加的效果都照这几条来：
 *
 * 1. 动画值只在绘制阶段读（graphicsLayer { } / drawBehind { }），只触发重画、不触发重组。
 *    唯一的例外是数字滚动——Text 要的是字符串，只能在组合阶段读，所以限定 ≤700ms、只在值变化时播。
 * 2. 入场只播一次，只给第一屏（默认前 6 项）；滚到后面的项直接就位，长列表不会一路闪。
 *    「播过」记在 rememberSaveable 里：列表项滚出去再滚回来、从详情页返回，都不重播。
 *    **不能拖慢打开速度**：一整页全部就位不超过约 0.4 秒（240ms + 最多 5 × 28ms 错峰）。
 * 3. 常驻动画（骨架屏的高光）按约 30 帧/秒推进，用定时器不用无限动画，页面不可见就停。
 * 4. 在玻璃顶栏取样范围内的动画一律设时限（[HeroMesh] 默认 6 秒），否则静止页面也在持续重新模糊。
 */

/** 入场用的时长与错峰间隔，全 App 统一。 */
private const val ENTER_MS = 240
private const val ENTER_STAGGER_MS = 28L
private const val ENTER_MAX = 6

/** 一屏从顶到底的入场错峰总跨度：与原先 6 项 × 28ms 的节奏相当。 */
private const val ENTER_SPREAD_MS = 168f

/**
 * 最近一波入场：某块整块在屏幕里、开始演入场时开一波；只露出一截的块在这一波开着时跟着演。
 * 判断在摆放时做（见 [enterOnce]），同一屏的块在同一趟摆放里，一波开 [WAVE_MS] 足够；滚动中探进来的行赶不上。只在 UI 线程用。
 */
private object EnterWave {
    private const val WAVE_MS = 300L

    /** 这一波的起点：同一波的块都按它排时间线（起点 + 自己的错峰），不各自从摆好那一刻算。 */
    var openedAt: kotlin.time.TimeMark? = null
        private set

    /** 已有一波开着就沿用它的起点，不重置：否则后摆好的块会把时间线整体往后推。 */
    fun open() {
        if (!active()) openedAt = TimeSource.Monotonic.markNow()
    }

    fun active(): Boolean = openedAt?.let { it.elapsedNow().inWholeMilliseconds < WAVE_MS } == true
}

/** 图表、进度条这类「揭开」的默认时长。 */
private const val REVEAL_MS = 550

/** 常驻循环（骨架高光、思考中的呼吸）的周期。 */
private const val LOOP_MS = 1200L

/**
 * 0→1 的揭开进度。[key] 变了才重播（数据换了再演一遍）；播过的 key 记在 rememberSaveable 里，
 * 返回页面、滚出去再滚回来都直接是 1。[skip] 为 true 时直接就位。
 * 进度请在 graphicsLayer / drawBehind 里读。
 */
@Composable
fun rememberRevealProgress(
    key: Any? = Unit,
    delayMillis: Long = 0L,
    spec: AnimationSpec<Float> = tween(REVEAL_MS, easing = FastOutSlowInEasing),
    skip: Boolean = false,
): State<Float> {
    val k = key.hashCode()
    val played = rememberSaveable { mutableStateOf(if (skip) k else null) }
    // 起点只在出生时看一眼、不订阅：否则入场播完写回 played，调用方（几十张卡）就各重组一次
    val a = remember {
        Animatable(if (androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { played.value } == k) 1f else 0f)
    }
    LaunchedEffect(k) {
        if (played.value == k) {
            a.snapTo(1f)
            return@LaunchedEffect
        }
        a.snapTo(0f)
        if (delayMillis > 0) delay(delayMillis)
        a.animateTo(1f, spec)
        played.value = k
    }
    return a.asState()
}

/**
 * 入场进度 0→1，第 [index] 项错开 [ENTER_STAGGER_MS]。[index] ≥ [maxAnimated] 的直接是 1：
 * 只有第一屏值得演一下，滚到后面还逐条淡入只会显得拖沓。
 */
@Composable
fun rememberEnterProgress(index: Int, maxAnimated: Int = ENTER_MAX): State<Float> =
    rememberRevealProgress(
        delayMillis = index * ENTER_STAGGER_MS,
        spec = tween(ENTER_MS, easing = FastOutSlowInEasing),
        skip = index >= maxAnimated || !LocalEnterAnimations.current.peek(),
    )

/**
 * 随转场变化的页面信号（可见、转场中、要不要演入场）。
 *
 * 以终身不换的持有者往下传，值在快照状态里：变化时只有读 [value] 的地方重组。
 * 直接把 Boolean 当 Local 的值提供的话，值一变，下层每个子组合（页面插槽、懒列表的项、
 * BoxWithConstraints）下次重组都不能跳过，整页重跑——子组合按上层 Local 有没有变决定能否跳过。
 */
@Stable
class PageSignal(private val read: () -> Boolean) {
    /** 当前值；在组合里读会订阅变化。 */
    val value: Boolean get() = read()

    /** 只看一眼、不订阅：只在首次组合时有意义的判断（要不要演入场）用它。 */
    fun peek(): Boolean = androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { read() }

    companion object {
        val True = PageSignal { true }
        val False = PageSignal { false }
    }
}

/** 这一页的控件要不要各自错峰入场。整页一起放大进来时关掉，否则控件和页面不同步。 */
val LocalEnterAnimations = androidx.compose.runtime.staticCompositionLocalOf { PageSignal.True }

/**
 * 这一页正随导航转场进出：常驻循环先停。页面画面不动，顶栏模糊和材质就沿用上一帧，不必逐帧重算。
 * 与可见性无关，[LocalPageVisible] 照旧。
 */
val LocalPageSettling = androidx.compose.runtime.staticCompositionLocalOf { PageSignal.False }

/** 设置里的「简化光效」：保留玻璃与模糊，去掉浮层光晕、投影和背景流动动画。 */
val LocalReducedEffects = androidx.compose.runtime.compositionLocalOf { false }

/**
 * 常驻循环的相位 0→1（周期 [periodMillis]）：按约 30 帧/秒推进，页面不可见就停。
 * 骨架高光、思考中的呼吸都从这里取，节奏全 App 一致。
 */
@Composable
fun rememberLoopPhase(periodMillis: Long = LOOP_MS): State<Float> {
    val phase = remember { mutableFloatStateOf(0f) }
    if (LocalPageVisible.current.value && !LocalPageSettling.current.value) {
        LaunchedEffect(periodMillis) {
            val start = TimeSource.Monotonic.markNow()
            while (true) {
                trace("xjtu:loop:loopPhase") {
                    phase.floatValue = (start.elapsedNow().inWholeMilliseconds % periodMillis) / periodMillis.toFloat()
                }
                delay(33)
            }
        }
    }
    return phase
}

/** 呼吸：按 [phase] 在 [low]..1 之间余弦起伏；[offset]（0..1）错开相位，一排点就成了波浪。 */
fun breath(phase: Float, low: Float = 0.35f, offset: Float = 0f): Float {
    val t = (phase - offset + 1f) % 1f
    return low + (1f - low) * (0.5f - 0.5f * cos(2f * PI.toFloat() * t))
}

/**
 * 入场：从下方 8dp 淡入上浮，只播一次。值在 graphicsLayer 里读，不引起重组。
 *
 * 错峰按这一块**第一次摆好时在屏幕上的高度**：越靠上越先出来，一屏从上到下最多差 [ENTER_SPREAD_MS]。
 * 不按 [index]——各页手排的序号、没挂入场的行、数据先到后到都会让下面的先出来。
 *
 * 哪些块演：第一次摆好时整块在屏幕里的演，并开一「波」；只露出一截的（屏幕底边那一两行）
 * 赶上这一波就跟着演（按位置排在最后），没赶上的——滚动中才探进来的行——直接就位。
 * [index]、[maxAnimated] 不再参与计算，留着是为了不改各页的调用。
 */
@Composable
fun Modifier.enterOnce(index: Int, maxAnimated: Int = ENTER_MAX): Modifier {
    val enter = LocalEnterAnimations.current.peek()
    val played = rememberSaveable { mutableStateOf(!enter) }
    val p = remember {
        Animatable(if (androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { played.value }) 1f else 0f)
    }
    // 第一次摆好那一刻定下的错峰延迟（ms）；NaN = 还没摆，负数 = 不演、直接就位。
    // 必须在摆放时就定：协程要等这一帧画完才跑，慢帧（debug 包首帧几百毫秒）里「这一波」早过期了，
    // 底边那半截行会被误判成直接就位，比上面还在淡入的行先出来
    val plan = remember { mutableFloatStateOf(Float.NaN) }
    // 所属那一波的起点：错峰从它算，同一波里先摆好、后摆好的块落在同一条时间线上
    val wave = remember { arrayOfNulls<kotlin.time.TimeMark>(1) }
    val screen = LocalWindowInfo.current.containerSize.height
    LaunchedEffect(Unit) {
        if (played.value) return@LaunchedEffect
        val offset = snapshotFlow { plan.floatValue }.first { !it.isNaN() }
        if (offset >= 0f) {
            val wait = offset.toLong() - (wave[0]?.elapsedNow()?.inWholeMilliseconds ?: 0L)
            if (wait > 0) delay(wait)
            p.animateTo(1f, tween(ENTER_MS, easing = FastOutSlowInEasing))
        } else {
            p.snapTo(1f)
        }
        played.value = true
    }
    return onPlaced {
        if (plan.floatValue.isNaN() && !played.value) {
            val y = it.positionInWindow().y
            val onScreen = screen > 0 && y >= 0f && y < screen
            val whole = onScreen && y + it.size.height <= screen
            // 整块在屏幕里的块开一波；同一趟摆放里（自上而下）随后只露出一截的块据此跟上
            if (whole) EnterWave.open()
            val joins = whole || (onScreen && EnterWave.active())
            wave[0] = if (joins) EnterWave.openedAt else null
            plan.floatValue = if (joins) y / screen * ENTER_SPREAD_MS else -1f
        }
    }.graphicsLayer {
        val v = p.value
        alpha = v
        translationY = (1f - v) * 8.dp.toPx()
    }
}

/** 一上来就快、越走越慢的指数减速（取自 HyperOS 系统菜单的实测节奏）：弹出面板、整页放大都用它。 */
val AppDecelerate: androidx.compose.animation.core.Easing = androidx.compose.animation.core.Easing { x ->
    val k = 2.8f
    (1f - kotlin.math.exp(-k * x)) / (1f - kotlin.math.exp(-k))
}

/**
 * 按下时缓缓缩小、松手慢慢回来，不回弹。卡片、圆钮用它；小圆钮可以压得更深（如 0.92）。
 * 整行的列表项用 [pressHighlight]：一整行跟着缩放太晃。
 */
@Composable
fun Modifier.pressScale(source: MutableInteractionSource, pressed: Float = 0.96f): Modifier {
    val isPressed by source.collectIsPressedAsState()
    val s = animateFloatAsState(
        if (isPressed) pressed else 1f,
        if (isPressed) tween(200) else tween(360),
        label = "pressScale",
    )
    return graphicsLayer {
        scaleX = s.value
        scaleY = s.value
    }
}

/** 列表行的按压：盖一层淡淡的前景色，缓缓压暗、松手慢慢退；深浅色主题下都看得见。 */
@Composable
fun Modifier.pressHighlight(source: MutableInteractionSource): Modifier {
    val isPressed by source.collectIsPressedAsState()
    val a = animateFloatAsState(if (isPressed) 1f else 0f, tween(if (isPressed) 200 else 440), label = "pressHighlight")
    val tint = MiuixTheme.colorScheme.onSurface
    return drawWithContent {
        drawContent()
        if (a.value > 0f) drawRect(tint.copy(alpha = 0.07f * a.value))
    }
}

/**
 * 数字滚动：第一次从 0 滚到 [target]，之后每次变化从旧值滚到新值。
 * 显示过的值记在 rememberSaveable 里，返回页面时直接是它，不会再从 0 滚一遍。
 * 返回的是当前显示值，调用方自己格式化；想直接出一个 Text 用 [RollingNumberText]。
 */
@Composable
fun rememberRollingValue(target: Double, durationMillis: Int = 500): State<Float> {
    val shown = androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val a = remember { Animatable(shown.floatValue) }
    LaunchedEffect(target) {
        a.animateTo(target.toFloat(), tween(durationMillis, easing = FastOutSlowInEasing))
        shown.floatValue = target.toFloat()
    }
    return a.asState()
}

/** 滚动的数字文本。[format] 把当前值变成要显示的字符串，比如 `{ "¥%.2f".format(it) }`。 */
@Composable
fun RollingNumberText(
    value: Double,
    format: (Float) -> String,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle.Default,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    durationMillis: Int = 500,
) {
    val v by rememberRollingValue(value, durationMillis)
    Text(format(v), modifier = modifier, style = style, color = color, fontWeight = fontWeight, maxLines = 1)
}

/**
 * 从左边长出来的进度条。进度在 drawBehind 里读：动画期间只重画这一条，不重组。
 * [delayMillis] 用来让一组条错开出场；[glowTip] 在端点画一个柔光点。
 */
@Composable
fun AnimatedBar(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier,
    trackColor: Color = color.copy(alpha = 0.14f),
    height: Dp = 6.dp,
    delayMillis: Int = 0,
    glowTip: Boolean = false,
) {
    val shown = androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val a = remember { Animatable(shown.floatValue) }
    val target = progress.coerceIn(0f, 1f)
    LaunchedEffect(target) {
        if (a.value == 0f && delayMillis > 0) delay(delayMillis.toLong())
        a.animateTo(target, tween(550, easing = FastOutSlowInEasing))
        shown.floatValue = target
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .drawBehind {
                val r = CornerRadius(size.height / 2f)
                drawRoundRect(trackColor, cornerRadius = r)
                val w = size.width * a.value
                if (w > 0f) {
                    drawRoundRect(color, size = Size(w.coerceAtLeast(size.height), size.height), cornerRadius = r)
                    if (glowTip) {
                        val center = Offset(w.coerceAtLeast(size.height / 2f), size.height / 2f)
                        val gr = size.height * 2.4f
                        drawCircle(
                            Brush.radialGradient(listOf(color.copy(alpha = 0.55f), Color.Transparent), center, gr),
                            radius = gr,
                            center = center,
                        )
                        drawCircle(Color.White.copy(alpha = 0.9f), radius = size.height * 0.32f, center = center)
                    }
                }
            },
    )
}

/**
 * 分段比例条（考勤、体测这类「几种状态各占多少」）。整条一次揭开，从左到右依次露出各段。
 * [parts] 是（数量, 颜色）；全是 0 时只画底色。
 */
@Composable
fun SegmentedBar(
    parts: List<Pair<Float, Color>>,
    modifier: Modifier = Modifier,
    height: Dp = 6.dp,
    trackColor: Color = Color.Gray.copy(alpha = 0.14f),
    gap: Dp = 2.dp,
    delayMillis: Int = 0,
) {
    val reveal = rememberRevealProgress(delayMillis = delayMillis.toLong())
    val total = parts.sumOf { it.first.toDouble() }.toFloat()
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .drawBehind {
                val r = CornerRadius(size.height / 2f)
                drawRoundRect(trackColor, cornerRadius = r)
                if (total <= 0f) return@drawBehind
                val visible = parts.filter { it.first > 0f }
                val gapPx = if (visible.size > 1) gap.toPx() else 0f
                val usable = size.width - gapPx * (visible.size - 1)
                val limit = size.width * reveal.value
                var x = 0f
                visible.forEach { (n, c) ->
                    val w = usable * n / total
                    val drawW = (limit - x).coerceIn(0f, w)
                    if (drawW > 0f) {
                        drawRoundRect(c, topLeft = Offset(x, 0f), size = Size(drawW, size.height), cornerRadius = r)
                    }
                    x += w + gapPx
                }
            },
    )
}

/**
 * 一天的节次条（空闲教室）：每格一节，空闲用 [freeColor]，占用用浅灰，当前节加深并描边。
 * [breaks] 是分段处（这些下标之后留宽缝，如上午 / 下午 / 晚上）。
 * 一个 drawBehind 画完全部格子，不拆成十几个 Box；进场时各格从左到右依次亮起。
 */
@Composable
fun SlotStripe(
    free: List<Boolean>,
    freeColor: Color,
    modifier: Modifier = Modifier,
    currentIndex: Int = -1,
    busyColor: Color = Color.Gray.copy(alpha = 0.16f),
    height: Dp = 6.dp,
    breaks: Set<Int> = emptySet(),
) {
    val reveal = rememberRevealProgress(spec = tween(450, easing = FastOutSlowInEasing))
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .drawBehind {
                val n = free.size
                if (n == 0) return@drawBehind
                val gap = 3.dp.toPx()
                val wideGap = 8.dp.toPx()
                val wideCount = breaks.count { it in 0 until n - 1 }
                val w = (size.width - gap * (n - 1 - wideCount) - wideGap * wideCount) / n
                val r = CornerRadius(size.height / 2f)
                var x = 0f
                free.forEachIndexed { i, f ->
                    // 第 i 格在揭开进度走到它时才从 0 长到满透明度
                    val local = ((reveal.value * n) - i).coerceIn(0f, 1f)
                    val base = if (f) freeColor.copy(alpha = if (i == currentIndex) 1f else 0.55f) else busyColor
                    drawRoundRect(base.copy(alpha = base.alpha * local), Offset(x, 0f), Size(w, size.height), r)
                    // 当前节占用时也要看得出「现在」在哪
                    if (i == currentIndex && !f) {
                        val s = 1.dp.toPx()
                        drawRoundRect(
                            freeColor.copy(alpha = local),
                            Offset(x + s / 2, s / 2), Size(w - s, size.height - s), r,
                            style = Stroke(s),
                        )
                    }
                    x += w + if (i in breaks) wideGap else gap
                }
            },
    )
}

/** 一天 11 节的分段处：第 4、8 节之后（上午 / 下午 / 晚上）。 */
val DaySlotBreaks: Set<Int> = setOf(3, 7)

/**
 * 页面大卡的流动底色：[accent] 按 [weights] 的浓度混进 [base]。封装的是首页 Hero 那套做法，
 * 默认只流动 6 秒——这类卡几乎都在玻璃顶栏的取样范围里。
 */
@Composable
fun HeroMesh(
    base: Color,
    accent: Color,
    modifier: Modifier = Modifier,
    weights: List<List<Float>> = DefaultHeroMeshWeights,
    runForMillis: Long = 6_000L,
) {
    val colors = remember(base, accent, weights) {
        weights.map { row -> row.map { t -> lerp(base, accent, t) } }
    }
    MeshBackground(
        modifier = modifier,
        lightVertexColors = colors,
        darkVertexColors = colors,
        runForMillis = runForMillis,
    )
}

/** 右上最浓、往下渐淡，给文字留出干净的底。 */
val DefaultHeroMeshWeights = listOf(
    listOf(0.15f, 0.08f, 0.24f),
    listOf(0.07f, 0.12f, 0.08f),
    listOf(0.03f, 0.02f, 0.05f),
)

/** 骨架屏占位块：圆角灰块 + 一道斜向高光扫过；只在加载期间组合，数据一到就被真内容换掉。 */
@Composable
fun SkeletonBlock(modifier: Modifier = Modifier, cornerRadius: Dp = 12.dp) {
    val phase = rememberLoopPhase(1400L)
    val base = AppInsetColor
    Box(
        modifier.drawWithCache {
            val r = CornerRadius(cornerRadius.toPx())
            onDrawBehind {
                drawRoundRect(base, cornerRadius = r)
                val band = size.width * 0.6f
                val x = -band + (size.width + band * 2) * phase.value
                drawRoundRect(
                    Brush.linearGradient(
                        listOf(Color.Transparent, Color.White.copy(alpha = 0.35f), Color.Transparent),
                        start = Offset(x, 0f),
                        end = Offset(x + band, size.height),
                    ),
                    cornerRadius = r,
                )
            }
        },
    )
}

/** 「成功」的绿色：打勾动画和成功文案共用。 */
val SuccessGreen = Color(0xFF34C759)

/** 一次性描出来的对勾（登录、预订、生成成功这类时刻）：外圈放大到位，对勾沿路径画出。 */
@Composable
fun DrawCheckmark(color: Color = SuccessGreen, modifier: Modifier = Modifier, size: Dp = 72.dp) {
    val ring = remember { Animatable(0.6f) }
    val stroke = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        ring.animateTo(1f, tween(420, easing = AppDecelerate))
    }
    LaunchedEffect(Unit) {
        delay(120)
        stroke.animateTo(1f, tween(360, easing = FastOutSlowInEasing))
    }
    Box(
        modifier
            .size(size)
            .graphicsLayer {
                scaleX = ring.value
                scaleY = ring.value
            }
            .drawWithCache {
                val w = this.size.width
                val path = Path().apply {
                    moveTo(w * 0.28f, w * 0.52f)
                    lineTo(w * 0.44f, w * 0.67f)
                    lineTo(w * 0.73f, w * 0.37f)
                }
                val measure = PathMeasure().apply { setPath(path, false) }
                val length = measure.length
                val partial = Path()
                onDrawBehind {
                    drawCircle(color.copy(alpha = 0.14f))
                    partial.reset()
                    measure.getSegment(0f, length * stroke.value, partial, true)
                    drawPath(partial, color, style = Stroke(width = w * 0.085f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            },
    )
}

/**
 * 列表页首次加载的骨架：几张和真实条目差不多大小的占位卡，依次淡入。
 * 比一个居中转圈更像「内容马上就到」，数据回来时版面也不会大跳。
 */
@Composable
fun SkeletonList(
    modifier: Modifier = Modifier,
    rows: Int = 6,
    rowHeight: Dp = 72.dp,
) {
    androidx.compose.foundation.layout.Column(
        modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
    ) {
        repeat(rows) { i ->
            SkeletonBlock(
                Modifier
                    .enterOnce(i)
                    .fillMaxWidth()
                    .height(rowHeight),
                cornerRadius = 18.dp,
            )
        }
    }
}
