package com.xjtu.toolbox.ui.glass.coui

import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.PI

/** 顶栏搜索与普通工具栏的连续展收, 不持有查询或 renderer。 */
@Stable
internal class CouiSearchMotion(
    private val enabled: Boolean,
    private val expandedState: State<Boolean>,
) {
    private var animationTarget = expandedState.value
    private var searchAnimation = newAnimation(toolbar = false)
    private var toolbarAnimation = newAnimation(toolbar = true)
    private var retained by mutableStateOf(expandedState.value)
    private var animationRunning by mutableStateOf(false)
    val expanded: Boolean get() = expandedState.value
    val present: Boolean get() = expanded || retained
    val running: Boolean get() = expanded != animationTarget || animationRunning
    var progress by mutableFloatStateOf(if (expandedState.value) 1f else 0f)
        private set
    var toolbarAlpha by mutableFloatStateOf(if (expandedState.value) 0f else 1f)
        private set
    val actionBoundsTransform = BoundsTransform { _, _ ->
        // 收起时搜索表面先让位, 常驻操作保持工具栏的返回节奏。
        val force = if (expanded) searchAnimation.spring else toolbarAnimation.spring
        spring(
            dampingRatio = force.dampingRatio,
            stiffness = force.stiffness,
        )
    }
    val toolbarScale: Float get() {
        val end = if (expanded) 0.8f else 0.5f
        return end + (1f - end) * toolbarAlpha
    }

    fun updateTarget(value: Boolean) {
        if (animationTarget == value) return
        animationTarget = value
        if (!enabled) return
        retained = true
        animationRunning = true
        searchAnimation.spring.apply {
            dampingRatio = 1f - 0.2f
            stiffness = searchStiffness(if (value) 0.55f else 0.45f)
        }
        toolbarAnimation.spring.apply {
            dampingRatio = 1f - if (value) 0.3f else 0.2f
            stiffness = searchStiffness(if (value) 0.40f else 0.50f)
        }
        if (!searchAnimation.isRunning) searchAnimation.setStartValue(progress * 1000f)
        if (!toolbarAnimation.isRunning) toolbarAnimation.setStartValue((1f - toolbarAlpha) * 1000f)
        // 运行中只改目标, 不 cancel 或清零速度。
        searchAnimation.animateToFinalPosition(if (value) 1000f else 0f)
        toolbarAnimation.animateToFinalPosition(if (value) 1000f else 0f)
    }

    fun finishCollapse() {
        if (expanded) return
        dispose()
        // 完成收起时重建弹簧，下一次展开从静止开始。
        searchAnimation = newAnimation(toolbar = false)
        toolbarAnimation = newAnimation(toolbar = true)
        progress = 0f
        toolbarAlpha = 1f
        retained = false
        animationRunning = false
        animationTarget = false
    }

    fun dispose() {
        searchAnimation.cancel()
        toolbarAnimation.cancel()
    }

    private fun newAnimation(toolbar: Boolean): SpringAnimation = SpringAnimation(FloatValueHolder()).apply {
        spring = SpringForce().apply { dampingRatio = 0.8f; stiffness = searchStiffness(0.55f) }
        setMinimumVisibleChange(0.001f)
        addUpdateListener { _, value, _ ->
            if (toolbar) toolbarAlpha = (1f - value / 1000f).coerceIn(0f, 1f) else progress = value / 1000f
        }
        if (!toolbar) addEndListener { _, cancelled, _, _ ->
            if (!cancelled) {
                progress = if (expanded) 1f else 0f
                retained = expanded
                animationRunning = false
            }
        }
    }
}

private fun searchStiffness(response: Float): Float = ((2.0 * PI / response) * (2.0 * PI / response)).toFloat()
