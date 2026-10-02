package com.xjtu.toolbox.agent

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import com.xjtu.toolbox.agent.bot.cast.Act
import com.xjtu.toolbox.agent.bot.cast.CastCharacter
import com.xjtu.toolbox.agent.bot.cast.CastEngine
import com.xjtu.toolbox.agent.bot.cast.Sketch
import com.xjtu.toolbox.agent.bot.cast.Tone

/**
 * 新角色的 Compose 渲染器：[CastEngine] 每帧在 [Sketch] 上记下笔画，这里转成 Path 画出来。
 * 节奏与经典屁岱一致：待命约 30fps，动起来跟 vsync 走；画在离屏层里，镂空才挖得干净。
 */
@Composable
internal fun CastIcon(
    cast: CastCharacter,
    beat: PidaiBeat,
    modifier: Modifier,
    frozenAt: Double?,
    gaze: () -> Offset?,
    glance: PidaiGlance?,
    pokeSerial: Int,
) {
    val engine = remember(cast) { CastEngine(cast) }
    val sketch = remember { Sketch() }
    val paths = remember { ArrayList<Path>() }
    val clock = remember { BotClock() }
    var tick by remember { mutableIntStateOf(0) }
    val currentBeat by rememberUpdatedState(beat)
    val currentGaze by rememberUpdatedState(gaze)
    val currentGlance by rememberUpdatedState(glance)
    val glanceAt = remember { doubleArrayOf(-10.0) }
    LaunchedEffect(glance?.serial, engine) {
        val g = glance ?: return@LaunchedEffect
        val now = clock.now()
        glanceAt[0] = now
        if (g.dx != 0f) engine.wink(if (g.dx < 0f) 0 else 1, now)
    }

    fun steer(now: Double): Boolean {
        val target = currentGaze()
            ?: currentGlance?.takeIf { now - glanceAt[0] < GLANCE_SECONDS }?.let { Offset(it.dx, it.dy) }
        engine.lookAt((target?.x ?: 0f).toDouble(), (target?.y ?: 0f).toDouble())
        return target != null
    }

    val act = when (beat) {
        PidaiBeat.REST -> null
        PidaiBeat.IDLE -> Act.MICRO
        PidaiBeat.THINKING -> Act.THINK
        PidaiBeat.ALERT -> Act.ALERT
        PidaiBeat.TAP -> Act.POKE
        PidaiBeat.COMET -> Act.COMBO
    }

    if (frozenAt != null) {
        LaunchedEffect(engine, act, frozenAt) {
            engine.reset()
            engine.play(act, 0.0)
            engine.sample(frozenAt, sketch)
            tick++
        }
    } else {
        LaunchedEffect(engine, act, pokeSerial) {
            val now = clock.now()
            clock.stateChangedAt = now
            engine.play(act, now, replay = true)
        }
        LaunchedEffect(engine) {
            while (true) {
                val t = clock.now()
                val looking = steer(t)
                val resting = currentBeat == PidaiBeat.REST && t - clock.stateChangedAt > 0.6 &&
                    !looking && t - glanceAt[0] > GLANCE_SECONDS + 0.6
                if (resting) {
                    kotlinx.coroutines.delay(33)
                    engine.sample(clock.now(), sketch)
                } else {
                    withFrameNanos { nanos -> engine.sample(clock.at(nanos), sketch) }
                }
                tick++
            }
        }
    }

    Canvas(
        // 镂空走 DstOut，要先落到离屏层；clip 防止飞走的鸽子画到画布外的界面上
        modifier = modifier.graphicsLayer {
            compositingStrategy = CompositingStrategy.Offscreen
            clip = true
        },
    ) {
        if (tick == 0 && sketch.count == 0) return@Canvas
        val unitPx = size.minDimension / 2f / 1.22f / 100f
        withTransform({
            translate(size.width / 2f, size.height / 2f)
            scale(unitPx, unitPx, pivot = Offset.Zero)
        }) {
            drawSketch(sketch, paths)
        }
    }
}

/** 把一帧笔画画出来。路径按笔的序号复用，不每帧新建。 */
private fun DrawScope.drawSketch(s: Sketch, paths: MutableList<Path>) {
    val cmd = s.cmd
    for (i in 0 until s.count) {
        val op = s.op(i)
        while (paths.size <= i) paths.add(Path())
        val path = paths[i]
        path.rewind()
        var k = op.start
        while (k < op.end) {
            when (cmd[k]) {
                Sketch.MOVE -> { path.moveTo(cmd[k + 1], cmd[k + 2]); k += 3 }
                Sketch.LINE -> { path.lineTo(cmd[k + 1], cmd[k + 2]); k += 3 }
                Sketch.CUBIC -> {
                    path.cubicTo(cmd[k + 1], cmd[k + 2], cmd[k + 3], cmd[k + 4], cmd[k + 5], cmd[k + 6])
                    k += 7
                }
                else -> { path.close(); k += 1 }
            }
        }
        val style = if (op.stroke > 0f) Stroke(width = op.stroke, cap = StrokeCap.Round, join = StrokeJoin.Round) else Fill
        if (op.tone == Tone.HOLE) {
            drawPath(path, Color.Black, alpha = op.alpha, style = style, blendMode = BlendMode.DstOut)
        } else {
            drawPath(path, Color(op.color.toInt()), alpha = op.alpha, style = style)
        }
    }
}
