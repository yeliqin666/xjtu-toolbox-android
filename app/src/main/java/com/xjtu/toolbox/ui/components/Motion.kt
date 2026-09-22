package com.xjtu.toolbox.ui.components

import androidx.compose.animation.core.Animatable
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
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Text

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

/**
 * 入场进度 0→1，第 [index] 项错开 45ms。[index] ≥ [maxAnimated] 的直接是 1：
 * 只有第一屏值得演一下，滚到后面还逐条淡入只会显得拖沓。
 */
@Composable
fun rememberEnterProgress(index: Int, maxAnimated: Int = ENTER_MAX): State<Float> {
    val played = androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(index >= maxAnimated) }
    val a = remember { Animatable(if (played.value) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (played.value) return@LaunchedEffect
        delay(index * ENTER_STAGGER_MS)
        a.animateTo(1f, tween(ENTER_MS, easing = FastOutSlowInEasing))
        played.value = true
    }
    return a.asState()
}

/** 入场：从下方 12dp 淡入上浮，只播一次。值在 graphicsLayer 里读，不引起重组。 */
@Composable
fun Modifier.enterOnce(index: Int, maxAnimated: Int = ENTER_MAX): Modifier {
    val p = rememberEnterProgress(index, maxAnimated)
    return graphicsLayer {
        val v = p.value
        alpha = v
        translationY = (1f - v) * 8.dp.toPx()
    }
}

/** 按下时缩到 0.96、松手弹回来，摸起来是「软」的。和屁岱首页卡片同一组弹簧参数。 */
@Composable
fun Modifier.pressScale(source: MutableInteractionSource, pressed: Float = 0.96f): Modifier {
    val isPressed by source.collectIsPressedAsState()
    val s = animateFloatAsState(
        if (isPressed) pressed else 1f,
        spring(dampingRatio = 0.5f, stiffness = 600f),
        label = "pressScale",
    )
    return graphicsLayer {
        scaleX = s.value
        scaleY = s.value
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
    val played = androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val reveal = remember { Animatable(if (played.value) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (played.value) return@LaunchedEffect
        if (delayMillis > 0) delay(delayMillis.toLong())
        reveal.animateTo(1f, tween(550, easing = FastOutSlowInEasing))
        played.value = true
    }
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
 * 一天的节次条（空闲教室）：每格一节，空闲用 [freeColor]，占用用浅灰，当前节加深。
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
) {
    val played = androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val reveal = remember { Animatable(if (played.value) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (played.value) return@LaunchedEffect
        reveal.animateTo(1f, tween(450, easing = FastOutSlowInEasing))
        played.value = true
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .drawBehind {
                val n = free.size
                if (n == 0) return@drawBehind
                val gap = 3.dp.toPx()
                val w = (size.width - gap * (n - 1)) / n
                val r = CornerRadius(size.height / 2f)
                free.forEachIndexed { i, f ->
                    // 第 i 格在揭开进度走到它时才从 0 长到满透明度
                    val local = ((reveal.value * n) - i).coerceIn(0f, 1f)
                    val base = if (f) freeColor.copy(alpha = if (i == currentIndex) 1f else 0.55f) else busyColor
                    drawRoundRect(
                        base.copy(alpha = base.alpha * local),
                        topLeft = Offset(i * (w + gap), 0f),
                        size = Size(w, size.height),
                        cornerRadius = r,
                    )
                }
            },
    )
}

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

/**
 * 骨架屏占位块：圆角灰块 + 一道斜向高光扫过。高光相位按 30 帧/秒推进，
 * 页面不可见时停；只在加载期间组合，数据一到就被真内容换掉。
 */
@Composable
fun SkeletonBlock(modifier: Modifier = Modifier, cornerRadius: Dp = 12.dp) {
    val visible = LocalPageVisible.current
    val phase = remember { mutableFloatStateOf(0f) }
    if (visible) {
        LaunchedEffect(Unit) {
            val start = System.nanoTime()
            while (true) {
                phase.floatValue = (((System.nanoTime() - start) / 1_000_000L) % 1400L) / 1400f
                delay(33)
            }
        }
    }
    val base = AppInsetColor
    Box(
        modifier.drawWithCache {
            val r = CornerRadius(cornerRadius.toPx())
            onDrawBehind {
                drawRoundRect(base, cornerRadius = r)
                val band = size.width * 0.6f
                val x = -band + (size.width + band * 2) * phase.floatValue
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

/** 一次性描出来的对勾（扫码登录成功这类时刻）：外圈弹一下，对勾沿路径画出。 */
@Composable
fun DrawCheckmark(color: Color, modifier: Modifier = Modifier, size: Dp = 72.dp) {
    val ring = remember { Animatable(0.6f) }
    val stroke = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        ring.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 380f))
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
