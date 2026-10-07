package com.xjtu.toolbox.agent.bot

import com.xjtu.toolbox.agent.skin.PidaiDraw
import com.xjtu.toolbox.agent.skin.PidaiLayer
import com.xjtu.toolbox.agent.skin.PidaiMotion
import com.xjtu.toolbox.agent.skin.PidaiMotionAction
import com.xjtu.toolbox.agent.skin.PidaiMotionFrame
import com.xjtu.toolbox.agent.skin.PidaiPaint
import kotlin.math.PI

/**
 * 数据驱动皮肤播放器。
 *
 * 图层按 key 在关键帧之间配对：结构相同的轮廓逐控制点插值，结构不同（换了个完全
 * 不一样的形状、或矢量换成位图）就交叉淡入淡出。两种都算合法的动作语言，所以
 * 播放器不拒绝，只是选一种可解释的过渡。
 *
 * 动作被打断时先冻结当前合成结果，再从那里平滑接入新动作。
 */
class ImportedMotionEngine(
    private val motion: PidaiMotion,
    private val scale: Double = 100.0,
) {
    /** 插值中的一层：几何已选定，变换参数还是可插值的标量。 */
    internal class ResolvedLayer(
        val key: String,
        val outline: SkinOutline?,
        val imageSrc: String?,
        val imageW: Double,
        val imageH: Double,
        val cx: Double,
        val cy: Double,
        val sx: Double,
        val sy: Double,
        val rot: Double,
        val pivotX: Double,
        val pivotY: Double,
        val alpha: Double,
        val fill: PidaiPaint,
        val stroke: PidaiPaint,
        val strokeWidth: Double,
        val evenOdd: Boolean,
        val cap: String,
        val join: String,
        val radial: Boolean,
    ) {
        fun withAlpha(value: Double) = ResolvedLayer(
            key, outline, imageSrc, imageW, imageH, cx, cy, sx, sy, rot, pivotX, pivotY,
            value, fill, stroke, strokeWidth, evenOdd, cap, join, radial,
        )
    }

    private var currentId = motion.actionFor("rest").id
    private var changedAt = 0.0
    private var departure: List<ResolvedLayer>? = null
    private var morphDuration = 0.0

    val actionId: String get() = currentId

    fun setAction(id: String, now: Double, restart: Boolean = false) {
        if (id !in motion.actions || (!restart && id == currentId)) return
        val from = currentId
        departure = composite(now)
        currentId = id
        changedAt = now
        morphDuration = motion.transitionDuration(from, id)
    }

    fun reset(id: String, now: Double = 0.0) {
        currentId = id.takeIf { it in motion.actions } ?: motion.actions.keys.first()
        changedAt = now
        departure = null
        morphDuration = 0.0
    }

    internal fun actionLayers(action: PidaiMotionAction, elapsed: Double): List<ResolvedLayer> {
        val t = if (action.loop && action.duration > 0.0) {
            ((elapsed % action.duration) + action.duration) % action.duration
        } else {
            elapsed.coerceIn(0.0, action.duration)
        }
        val frames = action.frames
        if (t <= frames.first().t) return frames.first().resolve()
        if (t >= frames.last().t) return frames.last().resolve()
        val index = frames.indexOfLast { it.t <= t }.coerceIn(0, frames.lastIndex - 1)
        val a = frames[index]
        val b = frames[index + 1]
        val raw = ((t - a.t) / (b.t - a.t)).coerceIn(0.0, 1.0)
        return blend(a.resolve(), b.resolve(), ease(a.ease, raw))
    }

    internal fun composite(now: Double): List<ResolvedLayer> {
        val target = actionLayers(motion.actions.getValue(currentId), now - changedAt)
        val from = departure ?: return target
        if (morphDuration <= 0.0) return target
        val progress = ((now - changedAt) / morphDuration).coerceIn(0.0, 1.0)
        if (progress >= 1.0) {
            departure = null
            return target
        }
        return blend(from, target, Easings.easeOutCubic(progress))
    }

    fun sample(now: Double): BotFrame = BotFrame(
        bodyPath = null,
        bodyAlpha = 1.0,
        eyes = emptyList(),
        dots = emptyList(),
        dotsBehind = false,
        arcs = emptyList(),
        notif = null,
        layers = composite(now).map { it.toDraw(scale) },
    )

    private fun PidaiMotionFrame.resolve(): List<ResolvedLayer> = layers.map { it.resolve() }

    private fun PidaiLayer.resolve() = ResolvedLayer(
        key = key,
        outline = shape.outline,
        imageSrc = shape.imageSrc,
        imageW = shape.imageW,
        imageH = shape.imageH,
        cx = cx, cy = cy, sx = sx, sy = sy, rot = rot,
        pivotX = pivotX, pivotY = pivotY, alpha = alpha,
        fill = fill ?: shape.fill,
        stroke = stroke ?: shape.stroke,
        strokeWidth = strokeWidth ?: shape.strokeWidth,
        evenOdd = shape.evenOdd,
        cap = shape.cap,
        join = shape.join,
        radial = radial,
    )

    private fun ResolvedLayer.toDraw(scale: Double) = PidaiDraw(
        outline = outline,
        imageSrc = imageSrc,
        imageW = imageW,
        imageH = imageH,
        transform = (
            if (radial) SkinTransform.ofRadialV1(cx, cy, sx, sy, rot)
            else SkinTransform.of(cx, cy, sx, sy, rot, pivotX, pivotY)
            ).scaled(scale),
        fill = fill,
        stroke = stroke,
        strokeWidth = strokeWidth,
        evenOdd = evenOdd,
        cap = cap,
        join = join,
        alpha = alpha,
    )

    /**
     * 按 key 配对两组图层。
     *
     * 出现在两边的层做几何/变换插值；只在一边出现的层按透明度淡入或淡出，并留在
     * 它原来的层序位置上，避免动作切换时 z 序突然重排。
     */
    internal fun blend(a: List<ResolvedLayer>, b: List<ResolvedLayer>, t: Double): List<ResolvedLayer> {
        if (t <= 0.0 && a.isNotEmpty()) return a
        val byA = a.associateBy { it.key }
        val byB = b.associateBy { it.key }
        val out = ArrayList<ResolvedLayer>(maxOf(a.size, b.size) + 4)
        b.forEach { layer ->
            val prev = byA[layer.key]
            if (prev == null) out.add(layer.withAlpha(layer.alpha * t)) else out.addAll(blendPair(prev, layer, t))
        }
        a.forEachIndexed { index, layer ->
            if (layer.key !in byB) out.add(index.coerceAtMost(out.size), layer.withAlpha(layer.alpha * (1.0 - t)))
        }
        return out
    }

    /** 结构相同就逐控制点插值；否则在同一个插值出来的位置上交叉淡入淡出。 */
    private fun blendPair(a: ResolvedLayer, b: ResolvedLayer, t: Double): List<ResolvedLayer> {
        var dRot = b.rot - a.rot
        while (dRot > PI) dRot -= PI * 2
        while (dRot < -PI) dRot += PI * 2
        val cx = lerp(a.cx, b.cx, t)
        val cy = lerp(a.cy, b.cy, t)
        val sx = lerp(a.sx, b.sx, t)
        val sy = lerp(a.sy, b.sy, t)
        val rot = a.rot + dRot * t
        val pivotX = lerp(a.pivotX, b.pivotX, t)
        val pivotY = lerp(a.pivotY, b.pivotY, t)
        val strokeWidth = lerp(a.strokeWidth, b.strokeWidth, t)
        val alpha = lerp(a.alpha, b.alpha, t)

        /** 同一个插值出来的位置与姿态，只有几何与配色是各自的。 */
        fun placed(
            key: String,
            source: ResolvedLayer,
            outline: SkinOutline?,
            fill: PidaiPaint,
            stroke: PidaiPaint,
            value: Double,
        ) = ResolvedLayer(
            key = key,
            outline = outline,
            imageSrc = source.imageSrc,
            imageW = lerp(a.imageW, b.imageW, t),
            imageH = lerp(a.imageH, b.imageH, t),
            cx = cx, cy = cy, sx = sx, sy = sy, rot = rot,
            pivotX = pivotX, pivotY = pivotY, alpha = value,
            fill = fill,
            stroke = stroke,
            strokeWidth = strokeWidth,
            evenOdd = if (t < 0.5) a.evenOdd else b.evenOdd,
            cap = if (t < 0.5) a.cap else b.cap,
            join = if (t < 0.5) a.join else b.join,
            radial = a.radial,
        )

        val fill = blendPaint(a.fill, b.fill, t)
        val stroke = blendPaint(a.stroke, b.stroke, t)
        if (a.imageSrc != null && a.imageSrc == b.imageSrc) {
            return listOf(placed(b.key, b, null, fill, stroke, alpha))
        }
        val morphed = if (a.outline != null && b.outline != null) lerpOutline(a.outline, b.outline, t) else null
        if (morphed != null) return listOf(placed(b.key, b, morphed, fill, stroke, alpha))
        // 几何无法对位：旧形状淡出、新形状淡入，位置照样一路走过去。
        return listOf(
            placed("${a.key}~out", a, a.outline, a.fill, a.stroke, alpha * (1.0 - t)),
            placed(b.key, b, b.outline, b.fill, b.stroke, alpha * t),
        )
    }

    private fun blendPaint(a: PidaiPaint, b: PidaiPaint, t: Double): PidaiPaint {
        if (a is PidaiPaint.Solid && b is PidaiPaint.Solid) {
            fun channel(shift: Int): Long {
                val av = (a.argb shr shift) and 0xFF
                val bv = (b.argb shr shift) and 0xFF
                return (av + (bv - av) * t).toLong().coerceIn(0L, 255L)
            }
            return PidaiPaint.Solid(
                (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
            )
        }
        return if (t < 0.5) a else b
    }

    private fun ease(name: String, t: Double): Double = when (name) {
        "ease-in" -> t * t * t
        "ease-out" -> Easings.easeOutCubic(t)
        "ease-in-out" -> Easings.easeInOutCubic(t)
        else -> t
    }
}
