package com.xjtu.toolbox.ui.glass.coui

import kotlin.math.abs
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sinh
import kotlin.math.sqrt

/** SpringOverScroller 的轴：两遍预测、fling 专用过阻尼插值及 RK4 回弹。长度均为像素。 */
internal class CouiScrollPhysics(private val animationLevel: Int,
    private val springBackTensionMultiple: Float = 3.2f, private val scrollView: Boolean = true) {
    // 原厂从全局动画等级初始化；库允许各组件分别配置，不能沿用跨实例的静态写入。
    private val flingFriction = if (animationLevel >= 3) 0.24f else 0.2f
    private var position = 0.0
    private var velocity = 0.0
    private var tempPosition = 0.0
    private var target = 0.0
    private var start = 0
    private var duration = 0
    private var distance = 0
    private var elapsed = 0f
    private var interpolator = FlingInterpolator(0)
    private var springBack = false
    private var tensionAdjusted = false

    var running = false
        private set
    val current: Int get() = Math.round(position).toInt()
    val currentVelocity: Float get() = velocity.toFloat()

    fun stop() { running = false; velocity = 0.0; tempPosition = position }

    fun fling(from: Int, initialVelocity: Int, frameSeconds: Float) {
        // 水平宿主的 Y 速度为零，原厂依次处理 X/Y 后会清空连续 fling 增速计数。
        val speed = initialVelocity
        start = from
        elapsed = 0f
        springBack = false
        tensionAdjusted = false
        interpolator = FlingInterpolator(speed)
        val step = frameSeconds.takeIf { it > 0f && it.isFinite() } ?: 0.016f
        val minimumDelta = when { abs(speed.toDouble()) <= 5000 -> 0.2; abs(speed.toDouble()) <= 8000 -> 0.3; else -> 0.35 }
        position = from.toDouble()
        velocity = speed.toDouble()
        tempPosition = position
        var count = 1
        val slowFriction = when { animationLevel >= 3 -> 4.0; animationLevel == 2 -> 3.4000000953674316; else -> 2.5 }
        val midFriction = when { animationLevel >= 3 -> 4.5; animationLevel == 2 -> 3.799999952316284; else -> 2.5 }
        val friction = when {
            abs(speed.toDouble()) <= 4000 -> slowFriction
            abs(speed.toDouble()) < 10000 -> midFriction
            else -> ((flingFriction - 8f) * 3f + 25f).toDouble()
        }
        do {
            val previous = position
            integrate(friction, 0.0, from.toDouble(), step)
            count++
        } while (abs(velocity) >= 5 && abs(position - previous) >= minimumDelta)
        distance = ((position.toInt() - from) * distanceScale(abs(speed.toFloat()))).toInt()
        duration = ((step * count * 1000f).toInt() * durationScale(abs(speed.toFloat()))).toInt()
        position = from.toDouble()
        velocity = speed.toDouble()
        tempPosition = position
        if (duration <= 0) {
            running = false
            return
        }
        count = 1
        do {
            val previous = position
            interpolate(step * count * 1000f / duration)
            count++
        } while (abs(velocity) >= 5 && abs(position - previous) >= minimumDelta)
        target = position.toInt().toDouble()
        position = from.toDouble()
        velocity = speed.toDouble()
        tempPosition = position
        running = abs(velocity) > 20
    }

    fun returnToBounds(from: Int, maximum: Int, preserveVelocity: Boolean = false, initialVelocity: Float? = null) {
        position = from.toDouble()
        target = from.coerceIn(0, maximum).toDouble()
        // 只有惯性触边先执行 notifyEdgeReached；直接松手回弹保留积分临时位置。
        if (preserveVelocity) tempPosition = target
        else if (!scrollView) tempPosition = 0.0
        if (initialVelocity != null) velocity = initialVelocity.toDouble()
        else if (!preserveVelocity) velocity = 0.0
        springBack = position != target
        tensionAdjusted = false
        running = springBack
    }

    fun update(frameSeconds: Float, elapsedMillis: Float? = null): Boolean {
        if (!running) return false
        if (!springBack && abs(velocity) <= 20) { running = false; return false }
        val step = frameSeconds.coerceAtLeast(0.008f).let { if (it > 0.025f) 0.008f else it }
        if (springBack) {
            if (atRest()) { running = false; return false }
            val displacement = abs(position - target)
            if (!tensionAdjusted && displacement < 180) tensionAdjusted = true
            else if (displacement < 0.25) {
                position = target
                running = false
                return false
            }
            val previous = position
            do {
                integrate(((12.19f - 8f) * 3f + 25f).toDouble(),
                    (((springBackTensionMultiple * 16f) - 30f) * 3.62f + 194f).toDouble(), target, step)
            } while (abs(previous - position) <= 0.5 && !atRest())
        } else {
            val previous = position
            elapsed = elapsedMillis?.coerceAtLeast(0f) ?: (elapsed + frameSeconds * 1000f)
            interpolate(elapsed / duration)
            if (abs(velocity) < 5) { running = false; return false }
            if ((position - target) * (previous - target) <= 0) {
                position = target
                running = false
            }
        }
        check(position.isFinite() && velocity.isFinite()) { "COUI 滚动物理产生非有限值" }
        return running
    }

    private fun atRest(): Boolean = abs(velocity) <= 20 && abs(position - target) <= 0.05

    private fun interpolate(fraction: Float) {
        position = (distance * interpolator.value(fraction)).toDouble() + start
        velocity = ((interpolator.speed(fraction) * distance / duration) * 1000f).toDouble()
    }

    private fun integrate(friction: Double, tension: Double, end: Double, step: Float) {
        val h = step.toDouble()
        val a1 = (end - tempPosition) * tension
        val v2 = velocity + a1 * h / 2.0
        val a2 = (end - (position + velocity * h / 2.0)) * tension - friction * v2
        val v3 = velocity + a2 * h / 2.0
        val a3 = (end - (position + v2 * h / 2.0)) * tension - friction * v3
        tempPosition = position + v3 * h
        val v4 = velocity + a3 * h
        val a4 = (end - tempPosition) * tension - friction * v4
        position += ((v2 + v3) * 2.0 + velocity + v4) * 0.16699999570846558 * h
        velocity += ((a2 + a3) * 2.0 + a1 + a4) * 0.16699999570846558 * h
    }

    private fun distanceScale(speed: Float): Float = when {
        speed <= 2000 -> 1f
        speed <= 6000 -> 1f - (speed - 2000f) / 4000f * 0.19999999f
        speed <= 10000 -> 0.8f
        speed <= 20000 -> 0.8f - (speed - 10000f) / 10000f * 0.4f
        else -> 0.4f
    }

    private fun durationScale(speed: Float): Float = when {
        speed <= 2000 -> 1.3f
        speed <= 6000 -> 1.3f - (speed - 2000f) / 4000f * 0.49999994f
        speed <= 10000 -> 0.8f
        speed <= 20000 -> 0.8f - (speed - 10000f) / 10000f * 0.3f
        else -> 0.5f
    }

    private class FlingInterpolator(initialVelocity: Int) {
        private val frequency = sqrt(40.0)
        private val damping = 1.15
        private val dampedFrequency = sqrt(damping * damping - 1.0) * frequency
        private val initial = min(abs(initialVelocity.toDouble()), 20000.0) / 15000f
        private val final = origin(1f).let { if (it != 0f && !it.isNaN()) it else 1f }

        private fun origin(fraction: Float): Float {
            val time = fraction.coerceAtLeast(0f).toDouble()
            return (1.0 - (sinh(damping * time) * (frequency * damping - initial) +
                cosh(damping * time) * dampedFrequency) * exp(-damping * frequency * time) / dampedFrequency).toFloat()
        }

        fun value(fraction: Float): Float = origin(fraction) / final

        fun speed(fraction: Float): Float {
            val time = fraction.coerceAtLeast(0f).toDouble()
            val factor = initial * damping * frequency + dampedFrequency * dampedFrequency - damping * damping * frequency * frequency
            return abs((cosh(damping * time) * ((damping * frequency - initial) - frequency * dampedFrequency) * damping +
                sinh(damping * time) * factor) * exp(-damping * frequency * time) / dampedFrequency).toFloat()
        }
    }

}
