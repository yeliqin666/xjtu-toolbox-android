package com.xjtu.toolbox.agent

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import com.xjtu.toolbox.agent.bot.BotEngine
import com.xjtu.toolbox.agent.bot.BotFrame
import com.xjtu.toolbox.agent.bot.ImportedMotionEngine
import com.xjtu.toolbox.agent.bot.NOTIF_BLUE
import com.xjtu.toolbox.agent.bot.SkinOutline
import com.xjtu.toolbox.agent.bot.transformedBy
import com.xjtu.toolbox.agent.bot.SkinTransform
import com.xjtu.toolbox.agent.skin.PidaiDraw
import com.xjtu.toolbox.agent.skin.PidaiPaint
import com.xjtu.toolbox.agent.skin.PidaiSkin

/**
 * bloub 机器人的 Compose 渲染器：把 [BotEngine] 采出的帧画到一块小画布上。
 *
 * 眼睛是身体上真正的「洞」：整只机器人画在一个离屏合成层里（`graphicsLayer` +
 * `CompositingStrategy.Offscreen`），眼睛、通知点凹槽用 [BlendMode.Clear] 挖穿，
 * 而不是拿背景色画上去——这样不管底栏是不透明还是玻璃，洞后面露出来的都是
 * 真实背景，不用关心「背景色是什么」。没有离屏合成层的话 Clear 会直接穿透到
 * 更底层的宿主画布，效果就不对了。burst 的粒子在身体后面（先画，被身体的
 * ink 填充遮住），彗星彩带按 z 分量分前后两半（后半段先画、被身体遮住，才是
 * 「轨道」而不是平面画）。
 *
 * 性能：底栏常驻，待命态只有眨眼和视线漂移在动（渲染器节流到 ~30fps，眨眼
 * 0.18s ≈ 5 帧足够平滑），主动全速播动画的只有微动、提醒、被点击三种情况。
 */
@Composable
internal fun BloubBotIcon(
    beat: PidaiBeat,
    ink: Color,
    /**
     * 曾经是「眼洞露出的底色」，挖空之后眼洞用 [BlendMode.Clear] 真的镂空，
     * 不再需要知道底栏颜色。保留这个参数只是为了不改调用方的签名，内部不再使用。
     */
    paper: Color,
    modifier: Modifier = Modifier,
    /** 用户选择的形状轮廓（见 [com.xjtu.toolbox.agent.bot.BOT_SHAPES]）；null = 圆形。 */
    shape: DoubleArray? = null,
    /** 非空时改用导入皮肤的数据驱动动作；导入形象默认不叠加眼睛。 */
    skin: PidaiSkin? = null,
    requestedAction: String? = null,
    requestedActionGeneration: Int = 0,
    /**
     * 非空 = 冻结在这个时刻的画面（设置页缩略图用），此时不启动帧循环、只采一帧。
     * 缩略图必须是静止的：一排会各自跑 rAF 的缩略图是没有意义的开销。
     */
    frozenAt: Double? = null,
) {
    val engine = remember { BotEngine() }
    val importedEngine = remember(skin?.cacheKey) { skin?.let { ImportedMotionEngine(it.motion) } }
    // 包内位图解码一次；解不开的图直接当作缺图，不让一张坏图拖垮整张皮肤。
    val images = remember(skin?.cacheKey) {
        skin?.images.orEmpty().mapNotNull { (name, bytes) ->
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
                .getOrNull()?.let { name to it }
        }.toMap()
    }
    val clock = remember { BotClock() }
    val currentBeat by rememberUpdatedState(beat)

    val builtInStateId = when (beat) {
        PidaiBeat.REST -> "idle"
        PidaiBeat.IDLE -> "wink"
        PidaiBeat.THINKING -> "orbit"
        PidaiBeat.ALERT -> "notify"
        PidaiBeat.TAP -> "comet"
    }
    val beatName = when (beat) {
        PidaiBeat.REST -> "rest"
        PidaiBeat.IDLE -> "idle"
        PidaiBeat.THINKING -> "thinking"
        PidaiBeat.ALERT -> "alert"
        PidaiBeat.TAP -> "tap"
    }
    val stateId = requestedAction?.takeIf { it in (skin?.motion?.actions ?: emptyMap()) }
        ?: skin?.motion?.actionFor(beatName)?.id
        ?: builtInStateId

    var frame by remember { mutableStateOf<BotFrame?>(null) }

    if (frozenAt != null) {
        // 冻结缩略图：状态与形状都摆到 0 时刻，再按 [frozenAt] 采一帧。
        LaunchedEffect(stateId, shape, skin?.cacheKey, frozenAt) {
            clock.stateChangedAt = 0.0
            if (importedEngine != null) {
                importedEngine.reset(stateId, 0.0)
                frame = importedEngine.sample(frozenAt)
            } else {
                engine.setShape(shape, 0.0)
                engine.reset(stateId, 0.0)
                frame = engine.sample(frozenAt)
            }
        }
    } else {
        // 状态切换用与帧循环相同的时钟，保证 setState 的时刻与采样时刻同源。
        LaunchedEffect(stateId, requestedActionGeneration, skin?.cacheKey) {
            val now = clock.now()
            clock.stateChangedAt = now
            if (importedEngine != null) {
                importedEngine.setAction(stateId, now, restart = requestedAction != null)
            } else {
                engine.setState(stateId, now)
            }
        }
        // 形状跟着用户选择走：设置页就在底栏旁边，换形状要立刻在底栏看到 morph。
        // 也要跟着 importedEngine：从皮肤切回内置形象时，这个效果必须重跑，
        // 否则内置屁岱会退回圆形，无视用户选的形状。
        LaunchedEffect(shape, importedEngine) {
            if (importedEngine == null) engine.setShape(shape, clock.now())
        }
        // key 必须带上 importedEngine：LaunchedEffect 的 block 在首次组合时就固定了，
        // 换皮肤后若不重启，循环会一直采样旧引擎（底栏因此永远不切换）。
        LaunchedEffect(importedEngine) {
            while (true) {
                val resting = currentBeat == PidaiBeat.REST && clock.now() - clock.stateChangedAt > 0.6
                if (resting) {
                    // 待命：入场形变结束后只剩眨眼 / 漂移，~30fps 足够。用定时器而不是逐帧回调：
                    // withFrameNanos 每个 vsync 都会排一帧，界面什么都不动时也按 120Hz 一直在跑。
                    kotlinx.coroutines.delay(33)
                    val now = clock.now()
                    frame = importedEngine?.sample(now) ?: engine.sample(now)
                } else {
                    withFrameNanos { nanos ->
                        val now = clock.at(nanos)
                        frame = importedEngine?.sample(now) ?: engine.sample(now)
                    }
                }
            }
        }
    }

    Canvas(
        // 挖空要用 BlendMode.Clear，必须先落到一个离屏层上再合成，否则会直接
        // 挖穿到宿主 Canvas 之外（见类注释）。
        modifier = modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
    ) {
        val f = frame
        if (f == null) {
            // 首帧采样前的占位（最多一帧）：画一个素球，避免中间塌洞
            drawCircle(ink, radius = size.minDimension * 0.4f, center = center)
            return@Canvas
        }
        // 引擎单位（球半径 = 100）-> 像素。留 1/1.22 的余量给通知点（半径 1.003R + 凹槽）
        // 和彗星彩带（半径 0.94R），静息球占画布约 82%。
        val unitPx = size.minDimension / 2f / 1.22f / 100f
        withTransform({
            translate(size.width / 2f, size.height / 2f)
            scale(unitPx, unitPx, pivot = Offset.Zero)
        }) {
            drawFrame(f, ink, images)
        }
    }
}

private fun DrawScope.drawFrame(
    f: BotFrame,
    ink: Color,
    images: Map<String, ImageBitmap>,
) {
    // 导入皮肤：任意条自由图层，下标即 z 序，没有内置身体也没有眼洞。
    if (f.bodyPath == null) {
        f.layers.forEach { drawSkinLayer(it, ink, images) }
        return
    }

    fun drawDots() {
        for (d in f.dots) {
            // depth < 0：在身体前面，纯墨色；depth ∈ [0,1]：在身体后面，挖空以后
            // 背后不再是固定的纸色，改用墨色乘透明度去逼近「越靠后越淡」的观感。
            val color = if (d.depth < 0.0) ink else ink.copy(alpha = d.depth.toFloat())
            drawCircle(
                color = color,
                radius = d.r.toFloat(),
                center = Offset(d.x.toFloat(), d.y.toFloat()),
                alpha = d.opacity.toFloat(),
            )
        }
    }

    fun drawArcs() {
        for (a in f.arcs) {
            val brush = Brush.linearGradient(
                0f to a.colors[0],
                0.5f to a.colors[1],
                1f to a.colors[2],
                start = Offset(a.gradX1.toFloat(), a.gradY1.toFloat()),
                end = Offset(a.gradX2.toFloat(), a.gradY2.toFloat()),
            )
            val style = Stroke(width = a.width.toFloat(), cap = StrokeCap.Round)
            drawPath(a.back, brush, style = style, alpha = a.opacity.toFloat())
            drawPath(a.front, brush, style = style, alpha = a.opacity.toFloat())
        }
    }

    if (f.dotsBehind) drawDots()

    // 彩带后半段：画在身体之前，被身体遮住
    drawArcs()

    // 挖空身体轮廓：先把身后的粒子、彩带清掉，腾出一块干净区域，
    // 免得它们透过之后的 alpha 混合渗出到身体边缘。
    drawPath(f.bodyPath, Color.Black, alpha = f.bodyAlpha.toFloat(), blendMode = BlendMode.Clear)

    clipPath(f.bodyPath) {
        // 墨色身体：裁剪到轮廓，画满整个裁剪区即可
        drawRect(
            color = ink,
            topLeft = Offset(-250f, -250f),
            size = Size(500f, 500f),
            alpha = f.bodyAlpha.toFloat(),
        )
        // 眼洞与通知点凹槽：真的挖穿，不再拿背景色回填
        for (eye in f.eyes) {
            drawPath(eye.path, Color.Black, alpha = eye.alpha.toFloat(), blendMode = BlendMode.Clear)
        }
        f.notif?.let { n ->
            drawCircle(
                Color.Black,
                radius = n.notchR.toFloat(),
                center = Offset(n.x.toFloat(), n.y.toFloat()),
                blendMode = BlendMode.Clear,
            )
        }
    }

    if (!f.dotsBehind) drawDots()

    f.notif?.let { n ->
        drawCircle(
            color = Color(NOTIF_BLUE),
            radius = n.r.toFloat(),
            center = Offset(n.x.toFloat(), n.y.toFloat()),
        )
    }

    // 彩带前半段：最后画，压在身体上
    drawArcs()
}

private fun DrawScope.drawSkinLayer(
    d: PidaiDraw,
    ink: Color,
    images: Map<String, ImageBitmap>,
) {
    val alpha = d.alpha.toFloat().coerceIn(0f, 1f)
    if (alpha <= 0.002f) return
    val image = d.imageSrc?.let(images::get)
    if (image != null) {
        if (image.width <= 0 || image.height <= 0) return
        // 位图按自然像素画，由矩阵把它铺进以原点为中心的 w × h 框，避免整数 dstSize 取整。
        val box = SkinTransform(
            d.imageW / image.width, 0.0,
            0.0, d.imageH / image.height,
            -d.imageW / 2.0, -d.imageH / 2.0,
        )
        withTransform({ transform(d.transform.times(box).toMatrix()) }) {
            drawImage(image, topLeft = Offset.Zero, alpha = alpha)
        }
        return
    }
    val outline = d.outline ?: return
    // 仿射保持三次贝塞尔，所以直接变换控制点：几何留在纯 Kotlin 里，可被单测断言。
    val path = outline.transformedBy(d.transform).toComposePath(d.evenOdd)
    d.fill.resolve(ink)?.let { (color, blendMode) -> drawPath(path, color, alpha = alpha, blendMode = blendMode) }
    val stroke = d.stroke.resolve(ink)
    if (stroke != null && d.strokeWidth > 0.0) {
        val (strokeColor, strokeBlendMode) = stroke
        drawPath(
            path,
            strokeColor,
            alpha = alpha,
            blendMode = strokeBlendMode,
            style = Stroke(
                width = (d.strokeWidth * d.transform.lineScale).toFloat(),
                cap = when (d.cap) {
                    "butt" -> StrokeCap.Butt
                    "square" -> StrokeCap.Square
                    else -> StrokeCap.Round
                },
                join = when (d.join) {
                    "miter" -> StrokeJoin.Miter
                    "bevel" -> StrokeJoin.Bevel
                    else -> StrokeJoin.Round
                },
            ),
        )
    }
}

/**
 * 皮肤里声明为 `"paper"` 的填充/描边：挖空以后不再是「底栏颜色」，
 * 而是用 [BlendMode.Clear] 真的镂空，所以要连同混合模式一起返回。
 */
private fun PidaiPaint.resolve(ink: Color): Pair<Color, BlendMode>? = when (this) {
    PidaiPaint.None -> null
    PidaiPaint.Ink -> ink to BlendMode.SrcOver
    PidaiPaint.Paper -> Color.Black to BlendMode.Clear
    is PidaiPaint.Solid -> Color(argb.toInt()) to BlendMode.SrcOver
}

private fun SkinOutline.toComposePath(evenOdd: Boolean): Path {
    val path = Path()
    if (evenOdd) path.fillType = PathFillType.EvenOdd
    subpaths.forEach { sub ->
        val p = sub.pts
        path.moveTo(p[0].toFloat(), p[1].toFloat())
        var i = 2
        while (i < p.size) {
            path.cubicTo(
                p[i].toFloat(), p[i + 1].toFloat(),
                p[i + 2].toFloat(), p[i + 3].toFloat(),
                p[i + 4].toFloat(), p[i + 5].toFloat(),
            )
            i += 6
        }
        if (sub.closed) path.close()
    }
    return path
}

/**
 * Compose 的 [Matrix] 是列主序数组，且 `get(row, column)` 实际取的是 `values[row*4+column]`
 * ——第一个参数是列不是行。按下标赋值极易把平移写进透视位（会让每个点被除以一个过零的 w，
 * 图形炸成楔形）。这里直接给出数组，并由 BloubBotIconMatrixTest 钉住。
 */
internal fun SkinTransform.toMatrix(): Matrix = Matrix(
    floatArrayOf(
        a.toFloat(), b.toFloat(), 0f, 0f,
        c.toFloat(), d.toFloat(), 0f, 0f,
        0f, 0f, 1f, 0f,
        e.toFloat(), f.toFloat(), 0f, 1f,
    )
)

/** 引擎时钟：以组合时刻为零点，帧回调和状态切换共用同一时间原点。 */
private class BotClock {
    val t0: Long = System.nanoTime()

    /** 最近一次状态切换的时刻，供静止态的保活节流判断入场形变是否已结束。 */
    var stateChangedAt: Double = 0.0

    fun now(): Double = at(System.nanoTime())

    fun at(frameNanos: Long): Double = (frameNanos - t0) / 1_000_000_000.0
}
