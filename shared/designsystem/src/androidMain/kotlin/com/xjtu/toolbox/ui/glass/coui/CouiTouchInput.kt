package com.xjtu.toolbox.ui.glass.coui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import com.xjtu.toolbox.ui.glass.coui.isCouiTouchCancelled

@Composable
fun rememberCouiTouchMotion(expanded: Boolean): CouiTouchMotion {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val motion = remember(scope, density) { CouiTouchMotion(scope, density) }
    LaunchedEffect(expanded, motion) {
        if (!expanded) motion.release(Offset.Zero)
    }
    DisposableEffect(motion) {
        onDispose { motion.dispose() }
    }
    return motion
}

/** 普通按压随节点释放；保留构造函数供显式 CoroutineScope 生命周期使用。 */
@Composable
fun rememberCouiPressMotion(): CouiPressMotion {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val press = remember(scope, density) { CouiPressMotion(scope, density) }
    DisposableEffect(press) { onDispose { press.dispose() } }
    return press
}

/** 只观察按下与取消；滚动子项可在父容器消费移动后释放，直到本次触摸结束都不重新按压。 */
fun Modifier.couiPressMotion(
    press: CouiPressMotion,
    enabled: Boolean = true,
    cancelOnConsumedMove: Boolean = false,
): Modifier =
    pointerInput(press, enabled, cancelOnConsumedMove) {
        if (!enabled) return@pointerInput
        var pressed = false
        var cancelled = false
        try {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val hasPointers = event.changes.any { it.pressed }
                    val nowPressed = !cancelled && !event.isCouiTouchCancelled() && hasPointers
                    if (nowPressed && !pressed) press.press(size)
                    if (!nowPressed && pressed) press.release()
                    pressed = nowPressed
                    if (!hasPointers) cancelled = false
                    if (cancelOnConsumedMove) {
                        val finalEvent = awaitPointerEvent(PointerEventPass.Final)
                        if (pressed && finalEvent.changes.any {
                                it.isConsumed && it.pressed && it.position != it.previousPosition
                            }) {
                            press.release()
                            pressed = false
                            cancelled = true
                        }
                    }
                }
            }
        } finally {
            if (pressed) press.release()
        }
    }

/** 将 COUI 矩阵变换后的触点送入形变单元, 不接管子项手势。 */
fun Modifier.couiTouchMotion(
    motion: CouiTouchMotion,
    expanded: Boolean,
    targetSize: ((Offset) -> IntSize?)? = null,
): Modifier = couiTouchMotion(motion, expanded, cancelOnConsumedMove = false, targetSize = targetSize)

/** 弹窗可跟随父滚动取消；底栏和 FAB 可在拖拽越过阈值后接管移动，公开入口仍只观察。 */
internal fun Modifier.couiTouchMotion(
    motion: CouiTouchMotion,
    expanded: Boolean,
    cancelOnConsumedMove: Boolean,
    targetSize: ((Offset) -> IntSize?)? = null,
    consumeDrag: Boolean = false,
): Modifier =
    onGloballyPositioned { motion.coordinates = it }
        .pointerInput(motion, expanded, cancelOnConsumedMove, consumeDrag) {
            if (!expanded) return@pointerInput
            val trackers = mutableMapOf<PointerId, CouiVelocityTracker>()
            var downTime = 0L
            try {
                awaitPointerEventScope {
                    var active: PointerId? = null
                    var velocityPointer: PointerId? = null
                    var cancelled = false
                    var dragOrigin = Offset.Zero
                    var ownsDrag = false

                    fun cancelTouch() {
                        motion.release(Offset.Zero)
                        trackers.values.forEach { it.recycle() }
                        trackers.clear()
                        active = null
                        velocityPointer = null
                        ownsDrag = false
                    }

                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.isCouiTouchCancelled()) {
                            cancelTouch()
                            cancelled = false
                            continue
                        }
                        if (cancelled) {
                            if (event.changes.none { it.pressed }) cancelled = false
                            continue
                        }
                        val coordinates = motion.coordinates?.takeIf { it.isAttached } ?: continue
                        val newPointer = event.changes.firstOrNull { it.pressed && !it.previousPressed }
                        val oldPointer = event.changes.firstOrNull { it.id == active }
                        val pointer = newPointer ?: if (oldPointer?.pressed == false) {
                            event.changes.firstOrNull { it.pressed } ?: oldPointer
                        } else {
                            oldPointer ?: if (targetSize != null) event.changes.firstOrNull { it.pressed } else null
                        } ?: continue
                        // 命中范围只决定能否开始形变, 已按住的拖拽在出界后仍持续到 UP 或 CANCEL。
                        val bounds = if (active == null && targetSize != null) {
                            targetSize(pointer.position) ?: continue
                        } else {
                            size
                        }
                        if (active == null) {
                            downTime = pointer.uptimeMillis
                            velocityPointer = pointer.id
                        }
                        for (change in event.changes) {
                            trackers.getOrPut(change.id) { CouiVelocityTracker(downTime) }.add(
                                change, coordinates, start = active == null && change.id == pointer.id,
                            )
                        }
                        val position = coordinates.localToWindow(pointer.position)
                        if (active == null) {
                            dragOrigin = position
                            ownsDrag = false
                            motion.begin(position, bounds)
                        } else if (pointer.id != active) {
                            val previous = oldPointer?.let { coordinates.localToWindow(it.position) }
                            if (previous != null) dragOrigin += position - previous
                            motion.switchPointer(previous, position)
                        }
                        if (consumeDrag && pointer.pressed &&
                            (ownsDrag || (position - dragOrigin).getDistance() > viewConfiguration.touchSlop)
                        ) {
                            ownsDrag = true
                            // Initial 阶段接管移动，父滚动和子项点击均能在本帧取消，普通轻触不受影响。
                            event.changes.forEach { change ->
                                if (change.pressed && change.position != change.previousPosition) change.consume()
                            }
                        }
                        if (newPointer != null) velocityPointer = newPointer.id
                        if (event.changes.any { it.id == velocityPointer && !it.pressed } &&
                            event.changes.any { it.pressed }
                        ) {
                            // 厂商速度 helper 选末指, motion unit 选首指; 保留两者原有语义。
                            velocityPointer = event.changes.lastOrNull { it.pressed }?.id
                        }
                        active = pointer.id
                        if (pointer.pressed) {
                            if (newPointer == null && oldPointer?.pressed == true) motion.move(position)
                        } else {
                            motion.release(trackers[velocityPointer]?.releaseVelocity() ?: Offset.Zero)
                            trackers.values.forEach { it.recycle() }
                            trackers.clear()
                            active = null
                            velocityPointer = null
                            ownsDrag = false
                        }
                        if (cancelOnConsumedMove) {
                            val finalEvent = awaitPointerEvent(PointerEventPass.Final)
                            if (active != null && finalEvent.changes.any {
                                    it.isConsumed && it.pressed && it.position != it.previousPosition
                                }) {
                                cancelTouch()
                                cancelled = true
                            }
                        }
                    }
                }
            } finally {
                trackers.values.forEach { it.recycle() }
                motion.release(Offset.Zero)
            }
}
