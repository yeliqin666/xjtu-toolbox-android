package com.xjtu.toolbox.ui.glass.coui.menu

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.copy
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.round
import androidx.compose.ui.window.PopupPositionProvider
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
internal fun rememberCouiMenuMotion(expanded: Boolean): CouiMenuMotion {
    val inspecting = LocalInspectionMode.current
    val motion = remember(inspecting) { CouiMenuMotion(inspecting) }
    LaunchedEffect(expanded, motion) {
        if (!expanded && !motion.visible) return@LaunchedEffect
        if (expanded) {
            motion.visible = true
            // Popup 完成定位后才开始, 避免首帧从错误的锚点跳入。
            snapshotFlow { motion.path }.first { it != null }
        }
        motion.animateTo(expanded)
        if (!expanded) {
            motion.visible = false
            motion.path = null
        }
    }
    return motion
}

internal class CouiMenuMotion(private val inspecting: Boolean) {
    var visible by mutableStateOf(false)
    var path by mutableStateOf<CouiMenuMotionPath?>(null)

    private var pathProgress by mutableStateOf(AnimationState(0f))
    private var scaleProgress by mutableStateOf(AnimationState(0f))
    private var opacityProgress by mutableStateOf(AnimationState(0f))
    private var closing = false

    val frame: CouiMenuMotionFrame
        get() = if (inspecting) {
            CouiMenuMotionFrame(Offset.Zero, 1f, 1f)
        } else {
            CouiMenuMotionFrame(
                translation = path?.position(pathProgress.value) ?: Offset.Zero,
                scale = scaleProgress.value.coerceAtLeast(0f),
                alpha = opacityProgress.value.coerceIn(0f, 1f),
            )
        }

    suspend fun animateTo(expanded: Boolean) {
        if (inspecting) return
        val fromScratch = expanded && closing &&
            scaleProgress.value >= 0.6f && opacityProgress.value <= 0.15f
        closing = !expanded
        // COUI 取消旧弹簧后以零初速重建; 特定收起状态下重开才将三个通道一并归零。
        pathProgress = AnimationState(if (fromScratch) 0f else pathProgress.value)
        scaleProgress = AnimationState(if (fromScratch) 0f else scaleProgress.value)
        opacityProgress = AnimationState(if (fromScratch) 0f else opacityProgress.value)
        val target = if (expanded) 1f else 0f
        coroutineScope {
            launch {
                pathProgress = pathProgress.animateMenuSpring(
                    target, if (expanded) OpenPathSpring else ClosePathSpring,
                )
            }
            launch {
                scaleProgress = scaleProgress.animateMenuSpring(
                    target, if (expanded) CouiMenuOpenScaleSpring else CloseScaleSpring,
                )
            }
            launch {
                opacityProgress = opacityProgress.animateMenuSpring(
                    target, if (expanded) CouiMenuOpenOpacitySpring else CloseOpacitySpring,
                )
            }
        }
    }
}

internal data class CouiMenuMotionFrame(
    val translation: Offset,
    val scale: Float,
    val alpha: Float,
)

/** 保留 Material 的横向与普通锚点定位; 触点菜单纵向只按可见窗口避让。 */
internal class CouiMenuMotionPositionProvider(
    private val delegate: PopupPositionProvider,
    private val motion: CouiMenuMotion,
    private val anchorPosition: () -> Offset?,
    private val visibleWindowBounds: () -> IntRect?,
    private val touchOffsetY: Int,
) : PopupPositionProvider {
    var menuSize by mutableStateOf<IntSize?>(null)
    var popupOriginInHostWindow by mutableStateOf<IntOffset?>(null)
    var menuPosition by mutableStateOf(IntOffset.Zero)
        private set
    var anchorCenterX by mutableStateOf(0f)
        private set

    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val contentSize = menuSize ?: return IntOffset.Zero
        val popupOrigin = popupOriginInHostWindow ?: return IntOffset.Zero
        val touchPosition = anchorPosition()
        val positioningAnchor = touchPosition?.let { IntRect(it.round(), IntSize.Zero) } ?: anchorBounds
        val defaultPosition = delegate.calculatePosition(
            positioningAnchor, windowSize, layoutDirection, contentSize,
        )
        val position = if (touchPosition != null) {
            val visibleBounds = visibleWindowBounds() ?: IntRect(IntOffset.Zero, windowSize)
            val below = positioningAnchor.top + touchOffsetY
            val above = below - contentSize.height
            val maxTop = (visibleBounds.bottom - contentSize.height).coerceAtLeast(visibleBounds.top)
            // Material 的额外纵向留白会让靠近底部的触点失去吸附, 这里直接尝试触点两侧。
            val y = when {
                below >= visibleBounds.top && below + contentSize.height <= visibleBounds.bottom -> below
                above >= visibleBounds.top && above + contentSize.height <= visibleBounds.bottom -> above
                else -> below.coerceIn(visibleBounds.top, maxTop)
            }
            defaultPosition.copy(y = y)
        } else {
            defaultPosition
        }
        val menuCenter = Offset(
            position.x + contentSize.width / 2f,
            position.y + contentSize.height / 2f,
        )
        val anchorX = touchPosition?.x ?: (anchorBounds.left + anchorBounds.right) / 2f
        anchorCenterX = anchorX - popupOrigin.x
        val anchorY = touchPosition?.y ?: if (menuCenter.y > (anchorBounds.top + anchorBounds.bottom) / 2f) {
            anchorBounds.bottom.toFloat()
        } else {
            anchorBounds.top.toFloat()
        }
        motion.path = CouiMenuMotionPath.from(Offset(anchorX, anchorY) - menuCenter)
        menuPosition = position - popupOrigin
        return IntOffset.Zero
    }
}

/** 菜单中心相对最终位置的曲线; 中心缩放等价于 COUI 的锚点缩放加 pivot 补偿。 */
internal data class CouiMenuMotionPath(
    private val start: Offset,
    private val control1: Offset,
    private val control2: Offset,
) {
    fun position(progress: Float): Offset {
        // 不限制到 [0, 1], 保留路径弹簧的轻微回弹。
        val remaining = 1f - progress
        return start * (remaining * remaining * remaining) +
            control1 * (3f * remaining * remaining * progress) +
            control2 * (3f * remaining * progress * progress)
    }

    companion object {
        fun from(start: Offset): CouiMenuMotionPath {
            val delta = -start
            val distance = delta.getDistance()
            if (distance < 1f) return CouiMenuMotionPath(start, start, Offset.Zero)

            // COUI 的重力弧线参数使用物理像素, 不随 density 再放大。
            val distanceWeight = ((distance - 30f) / (400f - 30f)).coerceIn(0f, 1f)
            val directionWeight = min(abs(delta.x), abs(delta.y)) /
                (max(abs(delta.x), abs(delta.y)) + 0.0001f)
            val arc = 130f * distanceWeight * directionWeight
            var normal = Offset(-delta.y / distance, delta.x / distance)
            if (normal.y < 0f) normal = -normal
            if (abs(normal.y) < 0.001f) normal = Offset(0f, 1f)
            val gravity = normal * arc
            return CouiMenuMotionPath(
                start = start,
                control1 = start + delta * 0.35f + gravity,
                control2 = start + delta * 0.65f + gravity,
            )
        }
    }
}

private const val MenuSpringRestThreshold = 0.001f

private suspend fun AnimationState<Float, AnimationVector1D>.animateMenuSpring(
    target: Float,
    spec: SpringSpec<Float>,
): AnimationState<Float, AnimationVector1D> {
    animateTo(target, spec) {
        // COUI 同时等待位移和速度收敛, 不能只按位移提前结束。
        if (abs(value - target) < MenuSpringRestThreshold && abs(velocity) < MenuSpringRestThreshold) {
            cancelAnimation()
        }
    }
    return copy(value = target, velocity = 0f)
}

// response 是弹簧周期参数, 不是动画时长; 统一按单位质量换算为 Compose 刚度。
private fun menuSpring(response: Float, bounce: Float = 0f): SpringSpec<Float> {
    val angularFrequency = (2.0 * PI / response).toFloat()
    return spring(
        dampingRatio = 1f - bounce,
        stiffness = angularFrequency * angularFrequency,
        // 让上面的双阈值先决定收尾, Compose 的时长估算只作为更精细的终点。
        visibilityThreshold = MenuSpringRestThreshold * 0.001f,
    )
}

private val OpenPathSpring = menuSpring(response = 0.29f, bounce = 0.13f)
val CouiMenuOpenScaleSpring = menuSpring(response = 0.40f, bounce = 0.20f)
val CouiMenuOpenOpacitySpring = menuSpring(response = 0.27f)
private val ClosePathSpring = menuSpring(response = 0.26f)
private val CloseScaleSpring = menuSpring(response = 0.21f)
private val CloseOpacitySpring = menuSpring(response = 0.23f)
