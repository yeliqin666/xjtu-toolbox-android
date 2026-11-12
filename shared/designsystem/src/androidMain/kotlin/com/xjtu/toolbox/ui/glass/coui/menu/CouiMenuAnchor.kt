package com.xjtu.toolbox.ui.glass.coui.menu

import android.graphics.Rect
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntRect

/** 设置行与其菜单共用的触点锚点, 定位时再转换坐标以跟随滚动和布局变化。 */
@Stable
class CouiMenuAnchor internal constructor(private val view: View) {
    internal var coordinates: LayoutCoordinates? by mutableStateOf(null, neverEqualPolicy())
    internal var pendingTouchPosition: Offset? = null
    private var clickPosition: Offset? by mutableStateOf(null)

    internal val positionInWindow: Offset?
        get() {
            val position = clickPosition ?: return null
            val layout = coordinates?.takeIf { it.isAttached } ?: return null
            return layout.localToWindow(position).takeIf {
                it.isSpecified && it.x.isFinite() && it.y.isFinite()
            }
        }

    internal val visibleWindowBounds: IntRect?
        get() {
            if (!view.isAttachedToWindow) return null
            val frame = Rect()
            view.getWindowVisibleDisplayFrame(frame)
            if (frame.isEmpty) return null
            val screenLocation = IntArray(2)
            val windowLocation = IntArray(2)
            view.getLocationOnScreen(screenLocation)
            view.getLocationInWindow(windowLocation)
            // 可见区域包含系统栏和 IME 避让, 必须与触点使用相同的窗口坐标。
            frame.offset(windowLocation[0] - screenLocation[0], windowLocation[1] - screenLocation[1])
            return IntRect(frame.left, frame.top, frame.right, frame.bottom)
        }

    /** 从原有 onClick 调用; 键盘和无障碍点击没有触点, 使用菜单原有锚点。 */
    fun open(onOpen: () -> Unit) {
        clickPosition = pendingTouchPosition
        pendingTouchPosition = null
        onOpen()
    }
}

@Composable
fun rememberCouiMenuAnchor(): CouiMenuAnchor {
    val view = LocalView.current
    return remember(view) { CouiMenuAnchor(view) }
}

/** 挂在触发行或其容器上, 仅观察触点, 不消费事件或替代 clickable。 */
fun Modifier.couiMenuAnchor(anchor: CouiMenuAnchor): Modifier =
    onGloballyPositioned { anchor.coordinates = it }
        .pointerInput(anchor) {
            try {
                awaitPointerEventScope {
                    var pointerId: PointerId? = null
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (pointerId == null) {
                            pointerId = event.changes.firstOrNull { it.changedToDownIgnoreConsumed() }?.id
                        }
                        val change = event.changes.firstOrNull { it.id == pointerId }
                        anchor.pendingTouchPosition = change?.position

                        // 子节点在 Main 阶段执行点击, Final 再清理, 防止取消手势留下旧触点。
                        awaitPointerEvent(PointerEventPass.Final)
                        if (change == null || !change.pressed) {
                            pointerId = null
                            anchor.pendingTouchPosition = null
                        }
                    }
                }
            } finally {
                anchor.pendingTouchPosition = null
            }
        }
