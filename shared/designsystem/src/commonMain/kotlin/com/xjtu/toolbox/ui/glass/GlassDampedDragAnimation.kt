// 移植自 Kyant0/AndroidLiquidGlass 示例 App —— https://github.com/Kyant0/AndroidLiquidGlass
// （分支 kmp，app/src/commonMain/kotlin/com/kyant/backdrop/catalog/utils/DampedDragAnimation.kt）
// 许可证 Apache License 2.0；改动：改包名和类名（加 Glass 前缀避免和 Kyant 原类混淆）；
// 用 `androidx.compose.runtime.withFrameNanos` 代替原版 `expect suspend fun awaitFrame()`
// （原版是给 KMP 多端用的占位，本项目只有 Android 一个目标，不需要 expect/actual）；
// 用 `kotlin.time.Clock` 代替 `System.currentTimeMillis()`：后者是 java.lang（JVM 默认导入，
// import 判据看不见），而这里要进 commonMain。原注释说的「避免多引入一个 opt-in」已不成立
// —— kotlin.time.Clock 在 Kotlin 2.4 上已是稳定 API。
package com.xjtu.toolbox.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MutatorMutex
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 底栏滑块「按住拖着走、松手回弹/吸附」的动画状态机。[value] 是浮点格数（比如 1.5f
 * 表示停在第 1、2 格中间），拖动时连续变化，松手时用 [GlassDragMath.snappedIndex]
 * 吸附到最近的整数格。
 */
internal class GlassDampedDragAnimation(
    private val animationScope: CoroutineScope,
    val initialValue: Float,
    val valueRange: ClosedRange<Float>,
    val visibilityThreshold: Float,
    val initialScale: Float,
    val pressedScale: Float,
    /** 按住时竖直方向的缩放（滑块上下略压扁），默认同 [pressedScale]。 */
    val pressedScaleY: Float = pressedScale,
    val onDragStarted: GlassDampedDragAnimation.(position: Offset) -> Unit,
    val onDragStopped: GlassDampedDragAnimation.() -> Unit,
    val onDrag: GlassDampedDragAnimation.(size: IntSize, dragAmount: Offset) -> Unit,
) {

    // 滑块在格子之间的移动
    private val valueAnimationSpec = tween<Float>(340, easing = FastOutSlowInEasing)
    private val velocityAnimationSpec = tween<Float>(240, easing = FastOutSlowInEasing)
    // 按下与松手都有过渡；松手比按下慢，不回弹
    private val pressInSpec = tween<Float>(200, easing = LinearOutSlowInEasing)
    private val pressOutSpec = tween<Float>(380, easing = FastOutSlowInEasing)

    private val valueAnimation =
        Animatable(initialValue, visibilityThreshold)
    private val velocityAnimation =
        Animatable(0f, 5f)
    private val pressProgressAnimation =
        Animatable(0f, 0.001f)
    private val scaleXAnimation =
        Animatable(initialScale, 0.001f)
    private val scaleYAnimation =
        Animatable(initialScale, 0.001f)

    private val mutatorMutex = MutatorMutex()

    private val velocityTracker = VelocityTracker()

    val value: Float get() = valueAnimation.value
    val targetValue: Float get() = valueAnimation.targetValue
    val pressProgress: Float get() = pressProgressAnimation.value
    val scaleX: Float get() = scaleXAnimation.value
    val scaleY: Float get() = scaleYAnimation.value
    val velocity: Float get() = velocityAnimation.value

    val modifier: Modifier = Modifier.pointerInput(Unit) {
        inspectDragGestures(
            onDragStart = { down ->
                onDragStarted(down.position)
                press()
            },
            onDragEnd = {
                onDragStopped()
                release()
            },
            onDragCancel = {
                onDragStopped()
                release()
            },
        ) { _, dragAmount ->
            onDrag(size, dragAmount)
        }
    }

    fun press() {
        velocityTracker.resetTracking()
        animationScope.launch {
            launch { pressProgressAnimation.animateTo(1f, pressInSpec) }
            launch { scaleXAnimation.animateTo(pressedScale, pressInSpec) }
            launch { scaleYAnimation.animateTo(pressedScaleY, pressInSpec) }
        }
    }

    fun release() {
        animationScope.launch {
            withFrameNanos {}
            if (value != targetValue) {
                val threshold = (valueRange.endInclusive - valueRange.start) * 0.025f
                snapshotFlow { valueAnimation.value }
                    .filter { abs(it - valueAnimation.targetValue) < threshold }
                    .first()
            }
            launch { pressProgressAnimation.animateTo(0f, pressOutSpec) }
            launch { scaleXAnimation.animateTo(initialScale, pressOutSpec) }
            launch { scaleYAnimation.animateTo(initialScale, pressOutSpec) }
        }
    }

    /** 拖动时直接把滑块放到手指下（不走弹簧，免得追着手指跑、和手指错位）。 */
    fun snapToValue(value: Float) {
        animationScope.launch { valueAnimation.snapTo(value.coerceIn(valueRange)) }
    }

    fun updateValue(value: Float) {
        val targetValue = value.coerceIn(valueRange)
        animationScope.launch {
            launch { valueAnimation.animateTo(targetValue, valueAnimationSpec) { updateVelocity() } }
        }
    }

    /** 滑块移到某一格。只管位置；按压态由手指决定（[press] / [release]），程序切页不闪灰。 */
    fun animateToValue(value: Float) {
        animationScope.launch {
            mutatorMutex.mutate {
                val targetValue = value.coerceIn(valueRange)
                launch { valueAnimation.animateTo(targetValue, valueAnimationSpec) }
                if (velocity != 0f) {
                    launch { velocityAnimation.animateTo(0f, velocityAnimationSpec) }
                }
            }
        }
    }

    private fun updateVelocity() {
        velocityTracker.addPosition(
            kotlin.time.Clock.System.now().toEpochMilliseconds(),
            Offset(value, 0f),
        )
        val targetVelocity = velocityTracker.calculateVelocity().x / (valueRange.endInclusive - valueRange.start)
        animationScope.launch { velocityAnimation.animateTo(targetVelocity, velocityAnimationSpec) }
    }
}
