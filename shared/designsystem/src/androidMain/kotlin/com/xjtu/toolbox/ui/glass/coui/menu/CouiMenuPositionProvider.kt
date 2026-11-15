package com.xjtu.toolbox.ui.glass.coui.menu

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider

/** 保留原 AndroidX Below 定位的候选顺序和安全区；触点纵向避让仍由 motion provider 处理。 */
internal class CouiMenuPositionProvider(offset: DpOffset, density: Density) : PopupPositionProvider {
    private val offsetX = with(density) { offset.x.roundToPx() }
    private val offsetY = with(density) { offset.y.roundToPx() }
    private val horizontalMargin = with(density) { 8.dp.roundToPx() }
    private val verticalMargin = with(density) { 48.dp.roundToPx() }

    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
        layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val rtl = layoutDirection == LayoutDirection.Rtl
        val width = popupContentSize.width
        val height = popupContentSize.height
        val xCandidates = intArrayOf(
            if (rtl) anchorBounds.right - width else anchorBounds.left,
            if (rtl) anchorBounds.left else anchorBounds.right - width,
            if (anchorBounds.center.x < windowSize.width / 2) 0 else windowSize.width - width,
        )
        val yCandidates = intArrayOf(
            anchorBounds.bottom,
            anchorBounds.top - height,
            anchorBounds.top - Alignment.CenterVertically.align(0, height),
            if (anchorBounds.center.y < windowSize.height / 2) 0 else windowSize.height - height,
        )
        return IntOffset(
            position(xCandidates, offsetX * if (rtl) -1 else 1, width, windowSize.width, horizontalMargin),
            position(yCandidates, offsetY, height, windowSize.height, verticalMargin),
        )
    }

    private fun position(candidates: IntArray, offset: Int, size: Int, window: Int, margin: Int): Int {
        for (candidate in candidates) {
            val value = candidate + offset
            if (value >= margin && value + size <= window - margin) return value
        }
        return if (size >= window - 2 * margin) Alignment.CenterVertically.align(size, window)
        else (candidates.last() + offset).coerceIn(margin, window - margin - size)
    }
}
