package com.xjtu.toolbox.ui.glass.coui

import android.view.MotionEvent
import android.view.VelocityTracker
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.layout.LayoutCoordinates

/** 保留平台事件类型与历史样本；UP 不会作为重复的 MOVE 坐标参与估速。 */
internal class CouiVelocityTracker(private val downTime: Long) {
    private val tracker = VelocityTracker.obtain()

    fun add(change: PointerInputChange, coordinates: LayoutCoordinates, start: Boolean = false) {
        for (sample in change.historical) {
            addMovement(sample.uptimeMillis, MotionEvent.ACTION_MOVE, coordinates.localToWindow(sample.position))
        }
        val action = when {
            !change.previousPressed || start -> MotionEvent.ACTION_DOWN
            !change.pressed -> MotionEvent.ACTION_UP
            else -> MotionEvent.ACTION_MOVE
        }
        addMovement(change.uptimeMillis, action, coordinates.localToWindow(change.position))
    }

    /** 原厂释放速度使用像素/毫秒，再乘 0.3。 */
    fun releaseVelocity(): Offset {
        tracker.computeCurrentVelocity(1, Float.MAX_VALUE)
        return Offset(tracker.xVelocity * 0.3f, tracker.yVelocity * 0.3f)
    }

    fun recycle() { tracker.recycle() }

    private fun addMovement(time: Long, action: Int, position: Offset) {
        val event = MotionEvent.obtain(downTime, time, action, position.x, position.y, 0)
        try {
            tracker.addMovement(event)
        } finally {
            event.recycle()
        }
    }
}
