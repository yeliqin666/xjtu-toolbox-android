package com.xjtu.toolbox.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sign

/**
 * 胶囊按住时的「橡皮筋」位移与形变，移植自 COUI-Kit `CouiTouchMotion`。
 *
 * 原实现用 `androidx.dynamicanimation` 的 SpringAnimation；这里换成 Compose 的
 * [Animatable] + [spring]，物理参数一一对应：`dampingRatio = 1 − bounce`、
 * `stiffness = (2π / response)²`，所以位移曲线与原版同一条。
 *
 * 位移与形变都按手指相对按下点的距离做 `rubber()` 衰减：横竖两个方向独立取目标缩放，
 * 再按两个方向的权重几何平均，这就是「拖得越远、胶囊跟着拉长变扁」的来源。
 */
class GlassBarDeform(
    private val scope: CoroutineScope,
    private val density: Float,
    private val deformationEnabled: Boolean = true,
) {
    private val scaleXSpring = Animatable(1f, 0.0005f)
    private val scaleYSpring = Animatable(1f, 0.0005f)
    private val translationXSpring = Animatable(0f, 0.5f)
    private val translationYSpring = Animatable(0f, 0.5f)

    private val stretchDistance = (28f * density).roundToLong().toFloat()
    private val deformDistance = 150f * density

    private var rubberOrigin = Offset.Zero
    private var deformOrigin = Offset.Zero
    private var targetSize = IntSize.Zero
    private var maximumScale = 1.2f
    private var dragging = false
    private var moved = false
    private val jobs = mutableListOf<Job>()

    val scaleX: Float get() = scaleXSpring.value
    val scaleY: Float get() = scaleYSpring.value
    val translationX: Float get() = translationXSpring.value
    val translationY: Float get() = translationYSpring.value

    fun begin(position: Offset, size: IntSize) {
        cancel()
        targetSize = size
        maximumScale = maximumScale(size)
        rubberOrigin = position - Offset(
            inverseRubber(translationXSpring.value, 0.05f, stretchDistance),
            inverseRubber(translationYSpring.value, 0.05f, stretchDistance),
        )
        val xDominant = scaleXSpring.value >= scaleYSpring.value
        val currentScale = if (xDominant) scaleXSpring.value else scaleYSpring.value
        val distance = inverseRubber(
            min((currentScale - 1f) / (maximumScale - 1f), 1f) * deformDistance,
            0.55f, deformDistance,
        )
        deformOrigin = position - if (xDominant) Offset(distance, 0f) else Offset(0f, distance)
        dragging = true
        moved = translationXSpring.value != 0f || translationYSpring.value != 0f ||
            scaleXSpring.value != 1f || scaleYSpring.value != 1f
    }

    fun move(position: Offset) {
        if (!dragging) return
        val rubberDelta = position - rubberOrigin
        if (abs(rubberDelta.x) >= 5f || abs(rubberDelta.y) >= 5f) {
            moved = true
            animate(translationXSpring, rubber(rubberDelta.x, 0.05f, stretchDistance), 0.15f, 0.15f)
            animate(translationYSpring, rubber(rubberDelta.y, 0.05f, stretchDistance), 0.15f, 0.15f)
        }
        if (!deformationEnabled) return
        val delta = position - deformOrigin
        val x = abs(delta.x)
        val y = abs(delta.y)
        if (x < 5f && y < 5f) return
        moved = true
        val sx = 1f + (maximumScale - 1f) * rubber(x, 0.55f, deformDistance) / deformDistance
        val sy = 1f + (maximumScale - 1f) * rubber(y, 0.55f, deformDistance) / deformDistance
        val xWeight = x / (x + y)
        val yWeight = y / (x + y)
        val targetX = when {
            y == 0f -> sx
            x == 0f -> 1f / sy
            else -> exp(ln(1f / sy) * yWeight + ln(sx) * xWeight)
        }
        val targetY = when {
            y == 0f -> 1f / sx
            x == 0f -> sy
            else -> exp(yWeight * ln(sy) + xWeight * ln(1f / sx))
        }
        animate(scaleXSpring, targetX, 0.15f, 0.15f)
        animate(scaleYSpring, targetY, 0.15f, 0.15f)
    }

    fun release(velocity: Offset) {
        if (!dragging) return
        dragging = false
        if (!moved) return
        val smallSide = 56f * density
        val largeSide = 100f * density
        val area = targetSize.width.toFloat() * targetSize.height
        val fraction = ((area - smallSide * smallSide) /
            (largeSide * largeSide - smallSide * smallSide)).coerceIn(0f, 1f)
        val response = 0.42f + (0.45f - 0.42f) * fraction
        releaseAxis(translationXSpring, 0f, velocity.x, response)
        releaseAxis(translationYSpring, 0f, velocity.y, response)
        if (!deformationEnabled) return
        releaseAxis(scaleXSpring, 1f, velocity.x, response)
        releaseAxis(scaleYSpring, 1f, velocity.y, response)
    }

    /** 停住当前所有弹簧（保留当前值）。 */
    fun cancel() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        dragging = false
    }

    fun reset() {
        dragging = false
        moved = false
        scope.launch {
            scaleXSpring.snapTo(1f)
            scaleYSpring.snapTo(1f)
            translationXSpring.snapTo(0f)
            translationYSpring.snapTo(0f)
        }
    }

    private fun releaseAxis(axis: Animatable<Float, *>, target: Float, velocity: Float, response: Float) {
        val rest = abs(axis.value - target)
        if (target == 1f && rest < 0.002f) {
            scope.launch { axis.snapTo(1f) }
            return
        }
        val initialVelocity = (velocity / max(28f * density, 1f) * rest).coerceIn(-1000f, 1000f)
        val bounce = max(0.65f, min(abs(initialVelocity) / 1000f, 1f))
        animate(axis, target, response, bounce, initialVelocity)
    }

    /** 运行中重定向：取消上一条动画、从当前值继续（Compose 的 MutatorMutex 语义）。 */
    private fun animate(
        axis: Animatable<Float, *>,
        target: Float,
        response: Float,
        bounce: Float,
        velocity: Float = 0f,
    ) {
        val job = scope.launch {
            axis.animateTo(
                targetValue = target,
                animationSpec = spring(
                    dampingRatio = (1f - bounce).coerceIn(0.01f, 1f),
                    stiffness = ((2.0 * PI / response) * (2.0 * PI / response)).toFloat(),
                    visibilityThreshold = 0.0005f,
                ),
                initialVelocity = velocity,
            )
        }
        jobs.add(job)
    }

    private fun maximumScale(size: IntSize): Float {
        val area = size.width.toFloat() * size.height
        val shortSide = min(size.width, size.height)
        if (shortSide == 0 || area <= 38416f) return 1.2f
        val aspect = max(size.width, size.height).toFloat() / shortSide
        val decay = min(0.015f * (aspect - 1f) + 0.35f * ln(area / 38416f), 1f)
        return max(1.03f, 1.03f + (1.2f - 1.03f) * (1f - decay))
    }

    private fun rubber(distance: Float, ratio: Float, maximum: Float): Float =
        if (maximum <= 0f) 0f
        else sign(distance) * (1f - 1f / (abs(distance) * ratio / maximum + 1f)) * maximum

    private fun inverseRubber(value: Float, ratio: Float, maximum: Float): Float {
        if (maximum <= 0f) return value
        val magnitude = abs(value)
        if (magnitude >= maximum || ratio <= 0f) return sign(value) * maximum
        return sign(value) * magnitude * maximum / (ratio * (maximum - magnitude))
    }
}

@Composable
fun rememberGlassBarDeform(): GlassBarDeform {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    return remember(scope, density) { GlassBarDeform(scope, density) }
}
