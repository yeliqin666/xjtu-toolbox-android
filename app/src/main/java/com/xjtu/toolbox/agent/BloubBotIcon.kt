package com.xjtu.toolbox.agent

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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import com.xjtu.toolbox.agent.bot.BotEngine
import com.xjtu.toolbox.agent.bot.BotFrame
import com.xjtu.toolbox.agent.bot.NOTIF_BLUE

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
    /**
     * 非空 = 冻结在这个时刻的画面（设置页缩略图用），此时不启动帧循环、只采一帧。
     * 缩略图必须是静止的：一排会各自跑 rAF 的缩略图是没有意义的开销。
     */
    frozenAt: Double? = null,
    /** 一直盯着某处（拖动底栏时跟着滑块）：横纵各 -1..1，负为左 / 上；null 不盯。 */
    gaze: () -> Offset? = { null },
    /** 瞟一眼：点了别的 tab 时朝它看一下，横向的还会眨那一侧的眼。 */
    glance: PidaiGlance? = null,
    /** 戳的序号（[PidaiPokes.serial]）：变了就把当前节拍从头再播，连戳每下都有反应。 */
    pokeSerial: Int = 0,
) {
    val engine = remember { BotEngine() }
    val clock = remember { BotClock() }
    val currentBeat by rememberUpdatedState(beat)
    val currentGaze by rememberUpdatedState(gaze)
    val currentGlance by rememberUpdatedState(glance)
    // 最近一次瞟眼的时刻，帧循环里读，不需要是 State
    val glanceAt = remember { doubleArrayOf(-10.0) }
    LaunchedEffect(glance?.serial) {
        val g = glance ?: return@LaunchedEffect
        val now = clock.now()
        glanceAt[0] = now
        if (g.dx != 0f) engine.wink(if (g.dx < 0f) 0 else 1, now)
    }

    /** 把导航栏给的方向换成引擎的注视角；返回此刻是否正在看某处。 */
    fun steer(now: Double): Boolean {
        val target = currentGaze()
            ?: currentGlance?.takeIf { now - glanceAt[0] < GLANCE_SECONDS }?.let { Offset(it.dx, it.dy) }
        engine.lookAt(yaw = (target?.x ?: 0f) * 28.0, pitch = -(target?.y ?: 0f) * 18.0)
        return target != null
    }

    val stateId = when (beat) {
        PidaiBeat.REST -> "idle"
        PidaiBeat.IDLE -> "wink"
        PidaiBeat.THINKING -> "orbit"
        PidaiBeat.ALERT -> "notify"
        PidaiBeat.TAP -> "poke"
        PidaiBeat.COMET -> "comet"
    }

    var frame by remember { mutableStateOf<BotFrame?>(null) }

    if (frozenAt != null) {
        // 冻结缩略图：状态与形状都摆到 0 时刻，再按 [frozenAt] 采一帧。
        LaunchedEffect(stateId, shape, frozenAt) {
            clock.stateChangedAt = 0.0
            engine.setShape(shape, 0.0)
            engine.reset(stateId, 0.0)
            frame = engine.sample(frozenAt)
        }
    } else {
        // 状态切换用与帧循环相同的时钟，保证 setState 的时刻与采样时刻同源。
        LaunchedEffect(stateId, pokeSerial) {
            val now = clock.now()
            clock.stateChangedAt = now
            engine.setState(stateId, now, replay = true)
        }
        // 形状跟着用户选择走：设置页就在底栏旁边，换形状要立刻在底栏看到 morph。
        LaunchedEffect(shape) { engine.setShape(shape, clock.now()) }
        LaunchedEffect(Unit) {
            while (true) {
                val t = clock.now()
                val looking = steer(t)
                // 看某处、以及看完回正的那半秒要全速跑，眼神才跟得上手指
                val resting = currentBeat == PidaiBeat.REST && t - clock.stateChangedAt > 0.6 &&
                    !looking && t - glanceAt[0] > GLANCE_SECONDS + 0.6
                if (resting) {
                    // 待命：入场形变结束后只剩眨眼 / 漂移，~30fps 足够。用定时器而不是逐帧回调：
                    // withFrameNanos 每个 vsync 都会排一帧，界面什么都不动时也按 120Hz 一直在跑。
                    kotlinx.coroutines.delay(33)
                    frame = engine.sample(clock.now())
                } else {
                    withFrameNanos { nanos -> frame = engine.sample(clock.at(nanos)) }
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
            drawFrame(f, ink)
        }
    }
}

private fun DrawScope.drawFrame(f: BotFrame, ink: Color) {
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

/** 一次瞟眼的方向：横 [dx] 或纵 [dy] 取 ±1（负为左 / 上）；[serial] 变了才算新的一次。 */
@androidx.compose.runtime.Immutable
internal data class PidaiGlance(val dx: Float, val dy: Float, val serial: Int)

/** 瞟一眼持续多久，然后视线回正。 */
private const val GLANCE_SECONDS = 1.1

/** 引擎时钟：以组合时刻为零点，帧回调和状态切换共用同一时间原点。 */
private class BotClock {
    val t0: Long = System.nanoTime()

    /** 最近一次状态切换的时刻，供静止态的保活节流判断入场形变是否已结束。 */
    var stateChangedAt: Double = 0.0

    fun now(): Double = at(System.nanoTime())

    fun at(frameNanos: Long): Double = (frameNanos - t0) / 1_000_000_000.0
}
