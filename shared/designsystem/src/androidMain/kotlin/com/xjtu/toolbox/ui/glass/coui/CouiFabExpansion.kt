package com.xjtu.toolbox.ui.glass.coui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.xjtu.toolbox.ui.glass.coui.CouiFloatingSurface
import kotlin.math.PI

/** 对应 AccessorySideExpansionController 的固定槽位收放, 不改变导航菜单宽度。 */
internal class CouiFabExpansion(initialVisible: Boolean) {
    private var active = initialVisible
    private var layout: Pair<Rect, Rect>? = null
    private var hiddenTranslation = 0f
    private var translationX by mutableFloatStateOf(0f)
    private var scale by mutableFloatStateOf(if (initialVisible) 1f else 0.5f)
    private var translationAnimation = newTranslationAnimation()
    private var scaleAnimation = newScaleAnimation()

    var present by mutableStateOf(initialVisible)
        private set
    var interactive by mutableStateOf(initialVisible)
        private set
    var touchMotionEnabled by mutableStateOf(initialVisible)
        private set

    fun updateTarget(visible: Boolean, menu: Rect, fab: Rect, collapseToLeft: Boolean) {
        if (menu.width <= 0f || menu.height <= 0f || fab.width <= 0f || fab.height <= 0f) return
        val nextLayout = menu to fab
        hiddenTranslation = couiFabHiddenTranslation(menu, fab, collapseToLeft)
        if (layout != nextLayout) {
            // 首次布局及配置变化直接对齐目标, 不从旧尺寸或错误初态播放入场。
            layout = nextLayout
            active = visible
            dispose()
            // 配置变化时重建弹簧，清除旧尺寸对应的速度和动画状态。
            translationAnimation = newTranslationAnimation()
            scaleAnimation = newScaleAnimation()
            translationX = if (visible) 0f else hiddenTranslation
            scale = if (visible) 1f else 0.5f
            present = visible
            interactive = visible
            touchMotionEnabled = visible
            return
        }
        if (active == visible) return
        active = visible
        present = true
        if (visible) interactive = true else touchMotionEnabled = false
        // 运行中的 spring 只改目标, 保留当前值和速度。
        if (!translationAnimation.isRunning) translationAnimation.setStartValue(translationX)
        if (!scaleAnimation.isRunning) scaleAnimation.setStartValue(scale * 10000f)
        scaleAnimation.animateToFinalPosition(if (visible) 10000f else 5000f)
        translationAnimation.animateToFinalPosition(if (visible) 0f else hiddenTranslation)
    }

    fun surface(node: CouiFloatingSurface): CouiFloatingSurface {
        if (!present) return node.copy(alpha = 0f, visible = false)
        return node.copy(
            bounds = node.bounds.translate(Offset(translationX, 0f)),
            scaleX = node.scaleX * snappedScale(node.bounds.width),
            scaleY = node.scaleY * snappedScale(node.bounds.height),
        )
    }

    fun offsetX(): Float = translationX

    fun snappedScale(size: Float): Float = if (size > 0f) Math.round(size * scale) / size else scale

    fun finishCollapseIfOverlapped(ratio: Float) {
        if (active || !translationAnimation.isRunning || ratio < 1f) return
        translationAnimation.cancel()
        translationAnimation = newTranslationAnimation()
        translationX = hiddenTranslation
        present = false
    }

    fun dispose() {
        translationAnimation.cancel()
        scaleAnimation.cancel()
    }

    private fun newTranslationAnimation(): SpringAnimation = SpringAnimation(FloatValueHolder()).apply {
        spring = expansionSpring()
        setMinimumVisibleChange(0.001f)
        addUpdateListener { _, value, _ -> translationX = value }
        addEndListener { _, cancelled, _, _ ->
            if (!cancelled) {
                if (active) touchMotionEnabled = true else present = false
            }
        }
    }

    private fun newScaleAnimation(): SpringAnimation = SpringAnimation(FloatValueHolder()).apply {
        spring = expansionSpring()
        addUpdateListener { _, value, _ -> scale = value / 10000f }
        addEndListener { _, cancelled, _, _ ->
            // 融球 alpha 由重叠几何驱动; 原 Controller 只等缩放收尾, 不等平移。
            if (!cancelled && !active) interactive = false
        }
    }

    private fun expansionSpring(): SpringForce = SpringForce().apply {
        dampingRatio = 1f - 0.35f
        stiffness = ((2.0 * PI / 0.55) * (2.0 * PI / 0.55)).toFloat()
    }
}

internal fun couiFabHiddenTranslation(menu: Rect, fab: Rect, collapseToLeft: Boolean): Float =
    (if (collapseToLeft) menu.left else menu.right - fab.width) - fab.left

internal fun CouiFloatingSurface.couiDrawingBounds(): Rect = Rect(
    bounds.center.x - bounds.width * scaleX * 0.5f,
    bounds.center.y - bounds.height * scaleY * 0.5f,
    bounds.center.x + bounds.width * scaleX * 0.5f,
    bounds.center.y + bounds.height * scaleY * 0.5f,
)

internal fun couiFabOverlapRatio(fab: Rect, menu: Rect): Float {
    val area = fab.width * fab.height
    if (area <= 0f) return 0f
    val intersection = fab.intersect(menu)
    return (intersection.width.coerceAtLeast(0f) * intersection.height.coerceAtLeast(0f) / area).coerceIn(0f, 1f)
}

internal fun couiFabOverlapAlpha(ratio: Float): Float =
    if (ratio > 0.5f) 1f - (ratio - 0.5f) / 0.5f else 1f

// 对应 Base 的 UP 子项 click, 再接 NavigationBarMenuView 的 pending dispatch。
internal fun couiNavigationReleaseTarget(
    preview: Int, pending: Int?, down: Int?, up: Int?, cancelled: Boolean,
): Int? {
    if (!cancelled && up != null && up != down && up != preview) return up
    if (pending != null && pending == preview) return pending
    if (!cancelled && up != null && up == down && up != preview) return up
    return null
}
