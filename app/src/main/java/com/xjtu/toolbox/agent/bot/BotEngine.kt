package com.xjtu.toolbox.agent.bot

import androidx.compose.ui.graphics.Path
import com.xjtu.toolbox.agent.skin.PidaiDraw
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * 无时钟引擎：sample(t) 是时间的纯函数。
 *
 * 移植自 src/bot/engine.ts，按本项目裁剪：形状固定为圆（eyefit 眼偏移恒为零，
 * 形变退化为恒等）、表情固定为平静（无混合）、无指针跟随（Look）、无轨道弧线。
 * 暂停/恢复/跳时刻都给出同一图像。
 */

class RenderedEye(
    /** 已变换到屏幕单位（引擎单位）的胶囊路径 */
    val path: Path,
    val alpha: Double,
)

class DotDraw(
    val x: Double,
    val y: Double,
    val r: Double,
    val opacity: Double,
    /** -1 = 用身体色；0..1 = 从背景色向身体色插值 */
    val depth: Double,
)

class NotifDraw(val x: Double, val y: Double, val r: Double, val notchR: Double)

class BotFrame(
    /** 内置屁岱的单轮廓身体；导入皮肤走 [layers]，这里为 null。 */
    val bodyPath: Path?,
    val bodyAlpha: Double,
    val eyes: List<RenderedEye>,
    val dots: List<DotDraw>,
    /** true = 粒子在身体后面 */
    val dotsBehind: Boolean,
    val arcs: List<ArcRender>,
    val notif: NotifDraw?,
    /** 导入皮肤的自由图层，下标即 z 序。 */
    val layers: List<PidaiDraw> = emptyList(),
)

/** 两姿态的插值。装饰按透明度交叉淡入淡出，不做几何交叉。 */
private fun blendPose(a: Pose, b: Pose, t: Double): Pose {
    val out = 1.0 - t
    val sil = blendSilhouette(a.sil, b.sil, t, Silhouette(DoubleArray(PROFILE_SAMPLES)))
    return Pose(
        sil = sil,
        offX = lerp(a.offX, b.offX, t),
        offY = lerp(a.offY, b.offY, t),
        gaze = HeadGaze(
            yaw = lerp(a.gaze.yaw, b.gaze.yaw, t),
            pitch = lerp(a.gaze.pitch, b.gaze.pitch, t),
            roll = lerp(a.gaze.roll, b.gaze.roll, t),
        ),
        split = lerp(a.split, b.split, t),
        eyes = listOf(
            EyeCfg(
                w = lerp(a.eyes[0].w, b.eyes[0].w, t),
                h = lerp(a.eyes[0].h, b.eyes[0].h, t),
                open = lerp(a.eyes[0].open, b.eyes[0].open, t),
                tilt = lerp(a.eyes[0].tilt, b.eyes[0].tilt, t),
            ),
            EyeCfg(
                w = lerp(a.eyes[1].w, b.eyes[1].w, t),
                h = lerp(a.eyes[1].h, b.eyes[1].h, t),
                open = lerp(a.eyes[1].open, b.eyes[1].open, t),
                tilt = lerp(a.eyes[1].tilt, b.eyes[1].tilt, t),
            ),
        ),
        eyeAlpha = lerp(a.eyeAlpha, b.eyeAlpha, t),
        bodyAlpha = lerp(a.bodyAlpha, b.bodyAlpha, t),
        dots = a.dots.map { DotSpec(it.x, it.y, it.r, it.opacity * out, it.depth) } +
            b.dots.map { DotSpec(it.x, it.y, it.r, it.opacity * t, it.depth) },
        arcs = a.arcs.map { ArcSpec(it.seed, it.t, it.opacity * out) } +
            b.arcs.map { ArcSpec(it.seed, it.t, it.opacity * t) },
        // 通知点只属于两个状态之一，不混合
        notif = if (t < 0.5) a.notif else b.notif,
        dotsBehind = if (t < 0.5) a.dotsBehind else b.dotsBehind,
    )
}

class BotEngine(
    /** 静息球半径，单位为 viewBox */
    val scale: Double = 100.0,
    initial: String = "idle",
) {
    companion object {
        /** 换形状时的形变时长（秒）。与状态形变共用同一条 ease-out 曲线。 */
        const val SHAPE_MORPH = 0.45
    }

    private var cur: String = initial
    private var prev: String? = null

    /** 冻结的起始姿态：仅当形变进行中又收到切换时设置。见 [setState]。 */
    private var departFige: Pose? = null
    private var tCur = 0.0
    private var tPrev = 0.0
    private var blinkAt = -10.0
    private val pts: Array<Point> = Array(PROFILE_SAMPLES) { Point(0.0, 0.0) }

    /** 用户选择的形状轮廓（球半径单位）。null = 不替换（等价圆形）。 */
    private var shape: DoubleArray? = null
    private var shapePrev: DoubleArray? = null
    private var shapeAt = -10.0

    val state: String get() = cur

    /**
     * 换形状。与状态切换一样带时刻：形状在 [SHAPE_MORPH] 内滑过去，不瞬间跳。
     * 所有形状按同一组角度采样，所以插值半径就够了。
     */
    fun setShape(radii: DoubleArray?, now: Double) {
        if (radii === shape) return
        shapePrev = shape
        shape = radii
        shapeAt = now
    }

    /**
     * 当前形状（形变中则插值）。不把 [shapePrev] 置空：`sample` 必须对时间保持纯函数，
     * 回读过去某时刻要还原出中间帧。
     *
     * 开放为 internal 是为了让单测能在不构造 `android.graphics.Path` 的前提下校验形变。
     */
    internal fun shapeAtTime(now: Double): DoubleArray? {
        val to = shape ?: return null
        val from = shapePrev ?: return to
        val k = (now - shapeAt) / SHAPE_MORPH
        if (k >= 1.0) return to
        val t = Easings.easeOutQuint(clamp01(k))
        // 只在形变期间分配；形变外原样返回
        return DoubleArray(to.size) { i -> lerp(from[i], to[i], t) }
    }

    /** 形状 morph 与状态 fondu 共用的轴向插值：一段起止值 + 曲线。 */
    private fun surAxe(
        a: Pair<Double, Double>,
        b: Pair<Double, Double>,
        debut: Double,
        duree: Double,
        now: Double,
    ): Pair<Double, Double> {
        if (a == b) return b
        val k = (now - debut) / duree
        if (k >= 1.0) return b
        val t = Easings.easeOutQuint(clamp01(k))
        return lerp(a.first, b.first, t) to lerp(a.second, b.second, t)
    }

    /**
     * 这个状态要加给两眼的偏移。读表并插值，绝不现算——这是 eyefit 的全部要点。
     *
     * 在 morph 的**两端**查表（[shapePrev] 与 [shape]），而不是查插值出的轮廓：后者
     * 每帧都是新数组、没有身份，也不在任何表里。
     */
    private fun decalageAtTime(now: Double, stateId: String): Pair<Double, Double> =
        surAxe(
            eyeOffsetFor(shapePrev, stateId),
            eyeOffsetFor(shape, stateId),
            shapeAt, SHAPE_MORPH, now,
        )

    private fun posed(def: StateDef, t: Double, shape: DoubleArray?): Pose {
        var pose = def.pose(maxOf(0.0, t))
        // 形状只替换「静息轮廓」状态的身体：其余状态的轮廓就是动画本身，不许被覆盖。
        if (def.baseBody && shape != null) {
            pose = Pose(
                sil = Silhouette(
                    shape,
                    rot = pose.sil.rot, cx = pose.sil.cx, cy = pose.sil.cy,
                    sx = pose.sil.sx, sy = pose.sil.sy,
                ),
                offX = pose.offX,
                offY = pose.offY,
                gaze = pose.gaze,
                split = pose.split,
                eyes = pose.eyes,
                eyeAlpha = pose.eyeAlpha,
                bodyAlpha = pose.bodyAlpha,
                dots = pose.dots,
                arcs = pose.arcs,
                notif = pose.notif,
                dotsBehind = pose.dotsBehind,
            )
        }
        // 待机状态佩戴所选表情（bloub：只有 baseFace 的状态会被表情覆盖脸）。
        if (def.baseFace) {
            return Pose(
                sil = pose.sil,
                offX = pose.offX,
                offY = pose.offY,
                gaze = REST_EXPRESSION.gaze,
                split = REST_EXPRESSION.split,
                eyes = REST_EXPRESSION.eyes,
                eyeAlpha = pose.eyeAlpha,
                bodyAlpha = pose.bodyAlpha,
                dots = pose.dots,
                arcs = pose.arcs,
                notif = pose.notif,
                dotsBehind = pose.dotsBehind,
            )
        }
        return pose
    }

    /** 正在进行的淡出的原点：冻结姿态，或前一状态按其自身时间求值（仍在动画中，这是有意的）。 */
    private fun origine(now: Double, shape: DoubleArray?): Pose? {
        departFige?.let { return it }
        val p = prev ?: return null
        return posed(BOT_STATES.getValue(p), maxOf(0.0, now - tPrev), shape)
    }

    private fun poseComposee(now: Double): Pose {
        val def = BOT_STATES.getValue(cur)
        val shape = shapeAtTime(now)
        val pose = posed(def, maxOf(0.0, now - tCur), shape)
        val since = now - tCur
        if (since >= def.morph) return pose
        val origine = origine(now, shape) ?: return pose
        return blendPose(origine, pose, Easings.easeOutQuint(clamp01(since / def.morph)))
    }

    /**
     * 状态切换，带时刻。
     *
     * 引擎只留一格历史，形变中途再切换会把混合的起点换成被离开状态的完整姿态，
     * 而不是屏幕上那帧部分混合的图像。因此中途切换时冻结当前复合姿态，从它出发
     * 继续混合——无论连续切换多少次都保证连续。
     */
    fun setState(id: String, now: Double) {
        if (id == cur) return
        val morph = BOT_STATES.getValue(cur).morph
        val enPleinFondu = prev != null && now - tCur < morph
        departFige = if (enPleinFondu) poseComposee(now) else null
        prev = cur
        tPrev = tCur
        cur = id
        tCur = now
        // 视频里每次形状变化都由一次眨眼掩护。
        if (BOT_STATES.getValue(id).blinkIn) blinkAt = now
    }

    /** 回卷到 [id]，不保留前一状态，如同刚摆放在该状态上。 */
    fun reset(id: String, now: Double) {
        cur = id
        prev = null
        departFige = null
        tCur = now
        tPrev = now
        blinkAt = -10.0
    }

    fun sample(now: Double): BotFrame {
        val R = scale
        val def = BOT_STATES.getValue(cur)
        val shape = shapeAtTime(now)
        val elapsed = maxOf(0.0, now - tCur)
        // 循环状态（轨道）：姿态时间按周期取模；每过一个接缝安排一次眨眼掩护跳变
        val poseTime: Double
        val blinkOrigin: Double
        if (def.loop != null) {
            val wraps = floor(elapsed / def.loop)
            poseTime = elapsed - wraps * def.loop
            blinkOrigin = tCur + wraps * def.loop
        } else {
            poseTime = elapsed
            blinkOrigin = blinkAt
        }
        var pose = posed(def, poseTime, shape)
        var decalage = decalageAtTime(now, cur)

        // --- 过渡 ------------------------------------------------------------
        val since = elapsed
        // 前一状态永不清除：since < morph 足以在淡出结束后忽略它；清掉会让引擎
        // 不可复放——重读淡出结束前的时刻会找不到起点。
        if (since < def.morph) {
            val origine = origine(now, shape)
            if (origine != null) {
                // ease-out quint：视频实测曲线。身体不过冲。
                // 比值有界：重读早于切换的时刻会得到负比值，extrapolate 会把轮廓甩飞。
                val ratio = Easings.easeOutQuint(clamp01(since / def.morph))
                pose = blendPose(origine, pose, ratio)
                // 眼偏移跟着**激发它的那份轮廓**走同一条曲线：来自被离开的状态。
                val quitte = prev
                if (quitte != null) {
                    val avant = decalageAtTime(now, quitte)
                    decalage = lerp(avant.first, decalage.first, ratio) to
                        lerp(avant.second, decalage.second, ratio)
                }
            }
        }

        // --- 静息生命感 -------------------------------------------------------
        val alive = pose.eyeAlpha > 0.01
        val life = liveliness(now, wander = if (alive) 1.0 else 0.0, blink = alive)

        val gaze = HeadGaze(
            yaw = pose.gaze.yaw + life.dYaw,
            pitch = pose.gaze.pitch + life.dPitch,
            // roll 不跟随任何东西：头的倾角是视频的签名
            roll = pose.gaze.roll + life.dRoll,
        )

        // 状态切换触发的眨眼，叠加在时刻表之上；循环状态则来自周期接缝
        val forced = clamp01((now - blinkOrigin) / 0.2)
        val forcedLid = if (forced < 1.0) abs(forced * 2.0 - 1.0) else 1.0
        val lid = minOf(life.lid, forcedLid)

        val offX = pose.offX + life.driftX
        val offY = pose.offY + life.driftY

        // --- 身体 ------------------------------------------------------------
        val sil = Silhouette(
            pose.sil.radii,
            rot = pose.sil.rot,
            cx = pose.sil.cx + offX,
            cy = pose.sil.cy + offY,
            sx = pose.sil.sx,
            sy = pose.sil.sy * life.breath,
        )
        val bodyPath = closedPath(toPoints(sil, R, pts))

        // --- 眼睛 ------------------------------------------------------------
        // 眼睛活在半径 1 的球面上；轮廓不是圆时按该方向的真实半径折算，否则会
        // 溢出轮廓（圆形身体下 fit 恒为 1，但保留通用代码以正确支持 burst 的
        // 缩小球）。
        val bodyRadius = fun(x: Double, y: Double): Double =
            radiusAtAngle(pose.sil.radii, atan2(y, x) - pose.sil.rot)

        val eyes = ArrayList<RenderedEye>(2)
        if (pose.eyeAlpha > 0.01) {
            val poses = eyePoses(gaze, R, pose.split)
            for (i in 0..1) {
                val e = poses[i]
                if (e.depth <= 0.02) continue
                val cfg = pose.eyes[i]
                val fit = bodyRadius(e.x, e.y)
                // 眼睛自转：切向标架再复合一个眼平面内的旋转（基 x 旋转）。
                val phi = cfg.tilt.deg2rad()
                val cp = cos(phi)
                val sp = sin(phi)
                val ax = e.a * cp + e.c * sp
                val ay = e.b * cp + e.d * sp
                val cx2 = -e.a * sp + e.c * cp
                val cy2 = -e.b * sp + e.d * cp
                // 眨眼作用在这一切之后：是屏幕竖直压扁，不是沿胶囊轴。
                val k = blinkScale(min(lid, cfg.open))
                // 眼洞路径：构造时直接套仿射（x 列 = (ax, ay*k)，y 列 = (cx2, cy2*k)）。
                // 不用 Path.addRoundRect/transform——在本项目 Compose 版本上实测前者
                // 出垃圾坐标、后者 no-op，只有基础图元可靠。
                val eyePath = transformedCapsulePath(
                    hw = cfg.w * R / 2.0,
                    hh = cfg.h * R / 2.0,
                    m00 = ax, m01 = cx2, m02 = e.x * fit + (offX + decalage.first) * R,
                    m10 = ay * k, m11 = cy2 * k, m12 = e.y * fit + (offY + decalage.second) * R,
                )
                eyes.add(
                    RenderedEye(
                        path = eyePath,
                        alpha = pose.eyeAlpha * clamp01(e.depth / 0.12),
                    )
                )
            }
        }

        // --- 装饰 ------------------------------------------------------------
        val dots = pose.dots
            .filter { it.opacity > 0.01 && it.r > 0.0005 }
            .map { DotDraw((it.x + offX) * R, (it.y + offY) * R, it.r * R, it.opacity, it.depth) }

        // 弧线（彗星彩带）：状态声明球半径单位，引擎统一光栅化
        val arcs = pose.arcs
            .filter { it.opacity > 0.01 }
            .map { arcRender(it.seed, it.t, R, it.opacity) }

        // 通知点贴在轮廓上：跟随形状
        var notif: NotifDraw? = null
        pose.notif?.let { n ->
            val nFit = bodyRadius(n.x, n.y)
            val nx = (n.x * nFit + offX) * R
            val ny = (n.y * nFit + offY) * R
            notif = NotifDraw(nx, ny, n.r * R, n.notch * R)
        }

        return BotFrame(
            bodyPath = bodyPath,
            bodyAlpha = pose.bodyAlpha,
            eyes = eyes,
            dots = dots,
            dotsBehind = pose.dotsBehind,
            arcs = arcs,
            notif = notif,
        )
    }
}
