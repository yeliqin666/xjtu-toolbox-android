package com.xjtu.toolbox.jiaocai1

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.fastAny
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 4f
private const val DOUBLE_TAP_MS = 280L
private const val TAP_SLOP = 24f

enum class Jiaocai1ZoomStop { FitWidth, FitHeight, TwoX }

/**
 * 单页缩放容器。scale 相对 ContentScale.Fit（整页可见 = 1）。
 * 放大后拖动自己消化；贴边后再往外拖才不消费，交给外层翻页器。
 */
@Composable
fun Jiaocai1ZoomablePage(
    aspect: Float,
    modifier: Modifier = Modifier,
    passHorizontalAtEdge: Boolean = true,
    passVerticalAtEdge: Boolean = false,
    onTap: () -> Unit,
    onScaleChange: (Float) -> Unit = {},
    content: @Composable () -> Unit,
) {
    var container by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var stop by remember { mutableStateOf<Jiaocai1ZoomStop?>(null) }
    var lastTapAt by remember { mutableStateOf(0L) }
    var lastTapPos by remember { mutableStateOf(Offset.Zero) }
    val tapScope = rememberCoroutineScope()

    fun fitted(): Pair<Float, Float> {
        val w = container.width.toFloat().coerceAtLeast(1f)
        val h = container.height.toFloat().coerceAtLeast(1f)
        val a = aspect.takeIf { it > 0.05f } ?: (700f / 1050f)
        val fittedW = minOf(w, h * a)
        return fittedW to fittedW / a
    }

    fun stops(): Triple<Float, Float, Float> {
        val w = container.width.toFloat().coerceAtLeast(1f)
        val h = container.height.toFloat().coerceAtLeast(1f)
        val (fittedW, fittedH) = fitted()
        val fitW = (w / fittedW).coerceAtLeast(1f)
        val fitH = (h / fittedH).coerceAtLeast(1f)
        return Triple(fitW, fitH, (fitW * 2f).coerceAtMost(MAX_SCALE))
    }

    fun maxPan(s: Float): Offset {
        val w = container.width.toFloat()
        val h = container.height.toFloat()
        val (fittedW, fittedH) = fitted()
        val contentW = fittedW * s
        val contentH = fittedH * s
        return Offset(
            ((contentW - w) / 2f).coerceAtLeast(0f),
            ((contentH - h) / 2f).coerceAtLeast(0f),
        )
    }

    fun clamp(nextScale: Float, nextOffset: Offset): Offset {
        val s = nextScale.coerceIn(MIN_SCALE, MAX_SCALE)
        val max = maxPan(s)
        return Offset(nextOffset.x.coerceIn(-max.x, max.x), nextOffset.y.coerceIn(-max.y, max.y))
    }

    fun applyScale(next: Float, focus: Offset) {
        val old = scale
        val s = next.coerceIn(MIN_SCALE, MAX_SCALE)
        if (old == 0f) return
        val cx = container.width / 2f
        val cy = container.height / 2f
        val world = Offset((focus.x - cx - offset.x) / old, (focus.y - cy - offset.y) / old)
        val raw = Offset(focus.x - cx - world.x * s, focus.y - cy - world.y * s)
        scale = s
        offset = if (s <= 1.01f) Offset.Zero else clamp(s, raw)
        onScaleChange(s)
    }

    fun cycle(focus: Offset) {
        val (fitW, fitH, two) = stops()
        val next = when (stop) {
            null, Jiaocai1ZoomStop.TwoX -> Jiaocai1ZoomStop.FitWidth
            Jiaocai1ZoomStop.FitWidth -> Jiaocai1ZoomStop.FitHeight
            Jiaocai1ZoomStop.FitHeight -> Jiaocai1ZoomStop.TwoX
        }
        stop = next
        val target = when (next) {
            Jiaocai1ZoomStop.FitWidth -> fitW
            Jiaocai1ZoomStop.FitHeight -> fitH
            Jiaocai1ZoomStop.TwoX -> two
        }
        applyScale(target, focus)
    }

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { container = it }
            .pointerInput(aspect, container) {
                var pendingTap: Job? = null
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var zoomed = false
                    var dragged = false
                    var pastSlop = false
                    val start = down.position
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val pressed = event.changes.fastAny { it.pressed }
                        if (!pressed) break
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        if (event.changes.size >= 2 && zoom != 1f) {
                            zoomed = true
                            applyScale(scale * zoom, event.calculateCentroid())
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                            continue
                        }
                        if (scale > 1.02f && (pan.x != 0f || pan.y != 0f)) {
                            val next = clamp(scale, offset + pan)
                            val max = maxPan(scale)
                            val atLeft = next.x >= max.x - 1.5f
                            val atRight = next.x <= -max.x + 1.5f
                            val atTop = next.y >= max.y - 1.5f
                            val atBottom = next.y <= -max.y + 1.5f
                            val outwardH = (atLeft && pan.x > 0f) || (atRight && pan.x < 0f)
                            val outwardV = (atTop && pan.y > 0f) || (atBottom && pan.y < 0f)
                            val releaseH = passHorizontalAtEdge && outwardH && abs(pan.x) >= abs(pan.y)
                            val releaseV = passVerticalAtEdge && outwardV && abs(pan.y) > abs(pan.x)
                            if (!releaseH && !releaseV) {
                                offset = next
                                dragged = true
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                        if ((start - event.changes.first().position).getDistance() > TAP_SLOP) {
                            pastSlop = true
                        }
                    }
                    if (zoomed || dragged) {
                        pendingTap?.cancel()
                    } else if (!pastSlop) {
                        val now = System.currentTimeMillis()
                        if (now - lastTapAt <= DOUBLE_TAP_MS && (down.position - lastTapPos).getDistance() < TAP_SLOP * 2) {
                            pendingTap?.cancel()
                            lastTapAt = 0
                            cycle(down.position)
                        } else {
                            lastTapAt = now
                            lastTapPos = down.position
                            pendingTap?.cancel()
                            pendingTap = tapScope.launch {
                                delay(DOUBLE_TAP_MS)
                                onTap()
                            }
                        }
                    }
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
    ) {
        content()
    }
}
