package com.xjtu.toolbox.ui.glass.coui

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.PI

/** 普通 COUI 弹簧通道。运行中重定向保留速度，硬重置清除速度和动画内部状态。 */
internal class CouiSpring(
    initial: Float,
    private val minimumChange: Float = 1f,
    private val onUpdate: () -> Unit = {},
) {
    private val holder = FloatValueHolder(initial)
    private val position = mutableFloatStateOf(initial)
    private var onFrame: ((Float) -> Unit)? = null
    private var onEnd: (() -> Unit)? = null
    private var needsReset = false
    private var minValue = -Float.MAX_VALUE
    private var maxValue = Float.MAX_VALUE
    private var animation = newAnimation()

    val state: State<Float> get() = position
    val value: Float get() = position.floatValue
    val isRunning: Boolean get() = animation.isRunning
    var velocity: Float = 0f
        private set

    /** 可选边界交给 AndroidX 积分器；仅裁剪绘制结果会改变越界期间的速度和回弹时机。 */
    fun setBounds(minimum: Float, maximum: Float) {
        require(minimum.isFinite() && maximum.isFinite() && minimum <= maximum)
        minValue = minimum
        maxValue = maximum
    }

    fun animateTo(
        target: Float,
        response: Float,
        bounce: Float = 0f,
        minimumChange: Float = this.minimumChange,
        velocity: Float? = null,
        onFrame: ((Float) -> Unit)? = null,
        onEnd: (() -> Unit)? = null,
    ) {
        this.onFrame = onFrame
        this.onEnd = onEnd
        needsReset = true
        animation.spring.dampingRatio = 1f - bounce
        animation.spring.stiffness = ((2.0 * PI / response) * (2.0 * PI / response)).toFloat()
        animation.setMinimumVisibleChange(minimumChange)
        animation.setMinValue(minValue)
        animation.setMaxValue(maxValue)
        if (!animation.isRunning) animation.setStartValue(value.coerceIn(minValue, maxValue))
        if (velocity != null) {
            this.velocity = velocity
            animation.setStartVelocity(velocity)
        }
        animation.animateToFinalPosition(target)
    }

    fun snapTo(next: Float) {
        cancelComplete()
        holder.value = next
        position.floatValue = next
    }

    fun cancel() {
        onFrame = null
        onEnd = null
        animation.cancel()
    }

    fun cancelComplete() {
        cancel()
        // DynamicAnimation 1.1.0 的 cancel 会清除待定目标，完整重置仍需清除速度和结束请求。
        if (needsReset) {
            animation = newAnimation()
            needsReset = false
        }
        velocity = 0f
    }

    private fun newAnimation(): SpringAnimation = SpringAnimation(holder).apply {
        spring = SpringForce(holder.value)
        setMinimumVisibleChange(minimumChange)
        addUpdateListener { _, next, speed ->
            position.floatValue = next
            velocity = speed
            onFrame?.invoke(next)
            onUpdate()
        }
        addEndListener { _, cancelled, _, _ -> if (!cancelled) onEnd?.invoke() }
    }
}
