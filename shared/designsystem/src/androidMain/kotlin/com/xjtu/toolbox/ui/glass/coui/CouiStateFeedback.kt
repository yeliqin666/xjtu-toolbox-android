package com.xjtu.toolbox.ui.glass.coui

import android.animation.ArgbEvaluator
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import android.view.ViewTreeObserver
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.unit.toSize
import com.xjtu.toolbox.ui.glass.coui.isCouiTouchCancelled
import com.xjtu.toolbox.ui.glass.coui.CouiTheme

/** 导航项过滤 state_pressed, COUIMaskRippleDrawable 实际只绘 hover -> focus。 */
@Composable
fun Modifier.couiNavigationFeedback(
    dark: Boolean,
    interactionSource: MutableInteractionSource,
): Modifier {
    val hovered by interactionSource.collectIsHoveredAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val hover = rememberMaskProgress(hovered)
    val focus = rememberMaskProgress(focused)
    val inset = with(LocalDensity.current) { 8.dp.toPx() }
    return hoverable(interactionSource).drawBehind {
        val radius = size.height * 0.5f
        val maskSize = Size(size.width + inset * 2f, size.height)
        drawRoundRect(maskColor(hover.value, if (dark) 0x26FFFFFF else 0x14000000), Offset(-inset, 0f), maskSize, CornerRadius(radius))
        drawRoundRect(maskColor(focus.value, if (dark) 0x331A8CFF else 0x1F0080FF), Offset(-inset, 0f), maskSize, CornerRadius(radius))
    }
}

/** COUIFloatingButton 启用材质模糊时关闭 press mask, 保留 hover; 按压形变独立处理。 */
@Composable
fun Modifier.couiFabFeedback(dark: Boolean, interactionSource: MutableInteractionSource): Modifier {
    val hovered by interactionSource.collectIsHoveredAsState()
    val hover = rememberMaskProgress(hovered)
    return hoverable(interactionSource).drawBehind {
        val radius = CornerRadius(size.minDimension * 0.5f)
        drawRoundRect(maskColor(hover.value, if (dark) 0x26FFFFFF else 0x14000000), cornerRadius = radius)
    }
}

/** 原厂顶栏材质过滤 pressed 通知, 仅保留 hover/focus 蒙层; 按压形变独立处理。 */
@Composable
fun Modifier.couiToolbarFeedback(
    dark: Boolean,
    interactionSource: MutableInteractionSource,
    enabled: Boolean,
): Modifier {
    val hovered by interactionSource.collectIsHoveredAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val hover = rememberMaskProgress(enabled && hovered)
    val focus = rememberMaskProgress(enabled && focused)
    return hoverable(interactionSource, enabled).drawBehind {
        val radius = CornerRadius(size.minDimension * 0.5f)
        drawRoundRect(maskColor(hover.value, if (dark) 0x26FFFFFF else 0x14000000), cornerRadius = radius)
        drawRoundRect(maskColor(focus.value, if (dark) 0x331A8CFF else 0x1F0080FF), cornerRadius = radius)
    }
}

@Composable
private fun rememberMaskProgress(active: Boolean): State<Float> {
    val effect = remember { CouiStateProgress() }
    SideEffect { effect.setActive(active) }
    DisposableEffect(effect) { onDispose { effect.dispose() } }
    return effect.progress
}

/**
 * 普通按钮或列表的 hover、focus、press 蒙层；不创建点击或业务提交。
 * list 选择原厂列表的最低按压进度及取消规则，hoverLocked 用于 Popup 展开期间的行悬停。
 * pressFeedback 同时控制普通与列表按压；关闭时释放当前按压动画，不影响 hover/focus。
 * 宿主将同一 interactionSource 交给唯一的 clickable / toggleable owner，并自行提供外部裁剪。
 */
@Composable
fun Modifier.couiButtonFeedback(dark: Boolean = CouiTheme.isDark, interactionSource: MutableInteractionSource,
    enabled: Boolean = true, pressFeedback: Boolean = true, list: Boolean = false, hoverLocked: Boolean = false): Modifier {
    val hovered by interactionSource.collectIsHoveredAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val hover = rememberMaskProgress(enabled && (hovered || hoverLocked))
    val focus = rememberMaskProgress(enabled && focused)
    val listPress = if (list && pressFeedback) remember { CouiListPressMask() } else null
    if (listPress != null) {
        SideEffect { listPress.updateState(enabled, pressed) }
        val view = LocalView.current
        DisposableEffect(listPress, view) {
            val listener = ViewTreeObserver.OnWindowFocusChangeListener { focused -> if (!focused) listPress.release() }
            view.viewTreeObserver.addOnWindowFocusChangeListener(listener)
            onDispose { if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnWindowFocusChangeListener(listener); listPress.dispose() }
        }
    }
    val press = if (listPress == null) rememberMaskProgress(enabled && pressed && pressFeedback) else null
    val observer = if (listPress != null && enabled) Modifier.pointerInput(listPress) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            listPress.touch(true)
            try {
                do {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    val consumedMove = change.pressed && change.isConsumed && change.position != change.previousPosition
                    if (event.isCouiTouchCancelled() || consumedMove || !size.toSize().toRect().contains(change.position)) break
                    if (!change.pressed) break
                } while (true)
            } finally { listPress.touch(false) }
        }
    } else Modifier
    return hoverable(interactionSource, enabled).then(observer).drawBehind {
        drawRect(maskColor(hover.value, if (dark) 0x26FFFFFF else 0x14000000))
        drawRect(maskColor(focus.value, if (dark) 0x331A8CFF else 0x1F0080FF))
        drawRect(maskColor(listPress?.progress ?: checkNotNull(press).value, if (dark) 0x33FFFFFF else 0x1F000000))
    }
}

/** COUISwitch 的 hover/press 蒙层；触摸会话与点击识别取消分别收尾。 */
@Composable
internal fun Modifier.couiSwitchFeedback(
    dark: Boolean,
    interactionSource: MutableInteractionSource,
    enabled: Boolean,
    touchPressed: Boolean,
): Modifier {
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val hover = rememberMaskProgress(enabled && hovered)
    val press = rememberMaskProgress(enabled && (pressed || touchPressed))
    return hoverable(interactionSource, enabled).drawBehind {
        if (enabled) {
            drawRect(maskColor(hover.value, if (dark) 0x26FFFFFF else 0x14000000))
            drawRect(maskColor(press.value, if (dark) 0x33FFFFFF else 0x1F000000))
        }
    }
}

/** COUIStrokeDrawable 的 TYPE_OUTER：沿项目形状画双倍笔宽，再裁掉轨道内部。 */
@Composable
internal fun Modifier.couiSwitchFocusOutline(
    interactionSource: MutableInteractionSource,
    enabled: Boolean,
    color: Color,
    shape: Shape,
): Modifier {
    val focused by interactionSource.collectIsFocusedAsState()
    val focus = rememberMaskProgress(enabled && focused)
    return drawWithCache {
        val path = (shape.createOutline(size, layoutDirection, this) as Outline.Generic).path
        // getDimensionPixelOffset(default_focus_stroke_radius) 先取整；外半笔宽为 3dp。
        val stroke = Stroke(3.dp.toPx().toInt() * 2f)
        onDrawBehind {
            if (enabled) clipPath(path, ClipOp.Difference) {
                drawPath(path, maskColor(focus.value, color.toArgb()), style = stroke)
            }
        }
    }
}

/** ListSelectedItemLayout 的容器蒙层：DOWN 立即启动，短点按/CANCEL 达到 .7 再退场。 */
private class CouiListPressMask {
    private val effect = CouiStateProgress()
    val progress: Float get() = effect.value
    private var touched = false
    private var semanticPressed = false
    private var awaitingSemanticRelease = false
    private var enabled = true
    private var disposed = false
    fun touch(value: Boolean) { touched = value; awaitingSemanticRelease = !value; update() }
    fun updateState(active: Boolean, pressed: Boolean) {
        enabled = active
        if (!pressed) awaitingSemanticRelease = false
        semanticPressed = pressed && !awaitingSemanticRelease
        update()
    }
    private fun update() {
        if (disposed) return
        if (!enabled) { clear(); return }
        effect.setActive(touched || (semanticPressed && !awaitingSemanticRelease), minimumPress = true)
    }
    fun clear() {
        touched = false; semanticPressed = false; awaitingSemanticRelease = true
        effect.reset()
    }
    // Activity 转场会失焦，但仍在绘制。沿原厂 touch exit 完成最低进度与退场，不能跳到零。
    fun release() {
        touched = false; semanticPressed = false; awaitingSemanticRelease = true
        update()
    }
    fun dispose() { disposed = true; clear(); effect.dispose() }
}

private val MaskEvaluator = ArgbEvaluator()

private fun maskColor(progress: Float, target: Int): Color =
    Color(MaskEvaluator.evaluate(progress.coerceIn(0f, 1f), 0, target) as Int)
