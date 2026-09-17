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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.graphics.lerp
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
 * 眼睛是身体上的「洞」（露出底栏背景色），不是白色形状贴在上面——所以先画一层
 * 与身体同形的背景色垫底，再在身体裁剪内画墨色、以背景色回填眼洞。burst 的粒子
 * 在身体后面（先画，被垫底遮住），通知点带凹槽（身体先让出一圈背景色再叠蓝点），
 * 彗星彩带按 z 分量分前后两半（后半段先画、被身体遮住，才是「轨道」而不是平面画）。
 *
 * 性能：底栏常驻，待命态只有眨眼和视线漂移在动（渲染器节流到 ~30fps，眨眼
 * 0.18s ≈ 5 帧足够平滑），主动全速播动画的只有微动、提醒、被点击三种情况。
 */
@Composable
internal fun BloubBotIcon(
    beat: PidaiBeat,
    ink: Color,
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
            var lastSampleAt = 0.0
            while (true) {
                withFrameNanos { nanos ->
                    val now = clock.at(nanos)
                    val sinceChange = now - clock.stateChangedAt
                    // 待命节流：入场形变结束后只剩眨眼/漂移，~30fps 足够；其余状态全速
                    val minInterval =
                        if (currentBeat == PidaiBeat.REST && sinceChange > 0.6) 0.033 else 0.0
                    if (now - lastSampleAt >= minInterval) {
                        lastSampleAt = now
                        frame = importedEngine?.sample(now) ?: engine.sample(now)
                    }
                }
            }
        }
    }

    Canvas(modifier = modifier) {
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
            drawFrame(f, ink, paper, images)
        }
    }
}

private fun DrawScope.drawFrame(
    f: BotFrame,
    ink: Color,
    paper: Color,
    images: Map<String, ImageBitmap>,
) {
    // 导入皮肤：任意条自由图层，下标即 z 序，没有内置身体也没有眼洞。
    if (f.bodyPath == null) {
        f.layers.forEach { drawSkinLayer(it, ink, paper, images) }
        return
    }

    fun drawDots() {
        for (d in f.dots) {
            val color = if (d.depth < 0.0) ink else lerp(paper, ink, d.depth.toFloat())
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

    // 与身体同形的背景色垫底：眼洞和凹槽露出的就是它，同时遮住身后的粒子和彩带
    drawPath(f.bodyPath, paper, alpha = f.bodyAlpha.toFloat())

    clipPath(f.bodyPath) {
        // 墨色身体：裁剪到轮廓，画满整个裁剪区即可
        drawRect(
            color = ink,
            topLeft = Offset(-250f, -250f),
            size = Size(500f, 500f),
            alpha = f.bodyAlpha.toFloat(),
        )
        // 眼洞与通知点凹槽：以背景色回填
        for (eye in f.eyes) {
            drawPath(eye.path, paper, alpha = eye.alpha.toFloat())
        }
        f.notif?.let { n ->
            drawCircle(paper, radius = n.notchR.toFloat(), center = Offset(n.x.toFloat(), n.y.toFloat()))
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
    paper: Color,
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
    d.fill.resolve(ink, paper)?.let { drawPath(path, it, alpha = alpha) }
    val strokeColor = d.stroke.resolve(ink, paper)
    if (strokeColor != null && d.strokeWidth > 0.0) {
        drawPath(
            path,
            strokeColor,
            alpha = alpha,
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

private fun PidaiPaint.resolve(ink: Color, paper: Color): Color? = when (this) {
    PidaiPaint.None -> null
    PidaiPaint.Ink -> ink
    PidaiPaint.Paper -> paper
    is PidaiPaint.Solid -> Color(argb.toInt())
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
