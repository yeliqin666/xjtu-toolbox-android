package com.xjtu.toolbox.ui.glass.coui.menu

import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
import android.view.ViewConfiguration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.ui.glass.coui.isCouiTouchCancelled
import kotlin.math.roundToInt
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** 移植 COUIScrollBar 的滑块几何; Drawable 内缩不改变实际可拖动范围。 */
internal class CouiMenuScrollBar(val scroll: ScrollState) {
    val alpha = Animatable(0f)
    var dragging by mutableStateOf(false)
    var coordinates: LayoutCoordinates? = null
    val bounds = Rect()
    var minHeight = 0
    var width = 0
    var contentPadding = 0
    var rtl = false

    fun updateBounds(viewWidth: Int, viewHeight: Int): Boolean {
        // RecyclerView 的滚动 extent 不含 padding; Compose 的 maxValue 已含整段内容留白。
        val extent = (viewHeight - contentPadding * 2).coerceAtLeast(0)
        val range = scroll.maxValue
        if (range <= 0 || range == Int.MAX_VALUE || extent == 0) {
            bounds.setEmpty()
            return false
        }
        val height = maxOf(minHeight, (extent.toFloat() / (range.toFloat() + extent) * viewHeight).roundToInt())
        val top = ((viewHeight - height) * (scroll.value.toFloat() / range)).roundToInt()
        val left = if (rtl) 0 else viewWidth - width
        bounds.set(left, top, left + width, top + height)
        return true
    }

    fun contains(root: LayoutCoordinates, position: Offset): Boolean {
        val local = coordinates?.takeIf { it.isAttached } ?: return false
        return contains(local.localPositionOf(root, position))
    }

    fun contains(position: Offset): Boolean {
        val viewport = coordinates ?: return false
        return alpha.value > 0f && updateBounds(viewport.size.width, viewport.size.height) &&
            position.x >= bounds.left && position.x <= bounds.right &&
            position.y >= bounds.top && position.y <= bounds.bottom
    }

    fun scrollDelta(thumbDelta: Int): Float {
        val viewport = coordinates ?: return 0f
        if (!updateBounds(viewport.size.width, viewport.size.height)) return 0f
        val travel = viewport.size.height - bounds.height()
        if (travel <= 0) return 0f
        val top = (bounds.top + thumbDelta).coerceIn(0, travel)
        return (scroll.maxValue * (top.toFloat() / travel)).roundToInt().toFloat() - scroll.value
    }
}

@Composable
internal fun couiMenuScrollBar(
    bar: CouiMenuScrollBar,
    visible: Boolean,
    enabled: Boolean,
    dark: Boolean,
    contentPadding: Dp,
): Modifier {
    val density = LocalDensity.current
    bar.minHeight = with(density) { 48.dp.roundToPx() }
    bar.width = with(density) { 14.dp.roundToPx() }
    bar.contentPadding = with(density) { contentPadding.roundToPx() }
    bar.rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val drawable = remember(density, dark) {
        // 保留原厂 Builder 的 StateListDrawable + InsetDrawable, 不用另一套圆角或描边。
        val width = with(density) { 14.dp.roundToPx() }
        val margin = with(density) { 18.dp.toPx().toInt() }
        val color = if (dark) 0x40FFFFFF else 0x29000000
        fun thumb(inset: Int) = InsetDrawable(
            GradientDrawable().apply {
                setColor(color)
                cornerRadius = width / 2f
            },
            inset, margin, inset, margin,
        )
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), thumb(with(density) { 4.dp.roundToPx() }))
            addState(intArrayOf(), thumb(with(density) { 5.5.dp.roundToPx() }))
        }
    }
    LaunchedEffect(bar, visible) {
        if (!visible) {
            bar.alpha.snapTo(0f)
            return@LaunchedEffect
        }
        var initial = true
        snapshotFlow { listOf(bar.scroll.maxValue, bar.scroll.value, bar.scroll.isScrollInProgress, bar.dragging) }
            .collectLatest {
                if (bar.scroll.maxValue <= 0 || bar.scroll.maxValue == Int.MAX_VALUE) {
                    bar.alpha.snapTo(0f)
                    return@collectLatest
                }
                val timeout = if (initial) ViewConfiguration.getScrollDefaultDelay().toLong() * 4 else 2000L
                initial = false
                bar.alpha.snapTo(1f)
                if (bar.scroll.isScrollInProgress || bar.dragging) return@collectLatest
                delay(timeout)
                bar.alpha.animateTo(0f, tween(ViewConfiguration.getScrollBarFadeDuration(), easing = LinearEasing))
            }
    }
    return Modifier.onGloballyPositioned { bar.coordinates = it }
        .drawWithContent {
            drawContent()
            if (bar.alpha.value > 0f && bar.updateBounds(size.width.roundToInt(), size.height.roundToInt())) {
                drawable.state = if (bar.dragging) intArrayOf(android.R.attr.state_pressed) else intArrayOf()
                drawable.bounds = bar.bounds
                drawable.alpha = (bar.alpha.value * 255).roundToInt()
                drawIntoCanvas { drawable.draw(it.nativeCanvas) }
            }
        }
        .pointerInput(bar, enabled, visible) {
            if (!enabled || !visible) return@pointerInput
            coroutineScope {
                val scope = this
                awaitEachGesture {
                    val down = awaitFirstDown(pass = PointerEventPass.Initial)
                    if (currentEvent.changes.count { it.pressed } != 1 || !bar.contains(down.position)) return@awaitEachGesture
                    down.consume()
                    bar.dragging = true
                    val deltas = Channel<Int>(Channel.UNLIMITED)
                    val scrollJob = scope.launch {
                        bar.scroll.scroll(MutatePriority.UserInput) {
                            for (delta in deltas) scrollBy(bar.scrollDelta(delta))
                        }
                    }
                    var lastY = down.position.y
                    var released = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id }
                            event.changes.forEach { it.consume() }
                            if (event.isCouiTouchCancelled() || event.changes.count { it.pressed } > 1 || change == null) break
                            if (!change.pressed) {
                                released = true
                                break
                            }
                            val delta = (change.position.y - lastY).roundToInt()
                            if (delta != 0) {
                                deltas.trySend(delta)
                                lastY = change.position.y
                            }
                        }
                    } finally {
                        bar.dragging = false
                        deltas.close()
                        if (!released) scrollJob.cancel()
                    }
                }
            }
        }
}
