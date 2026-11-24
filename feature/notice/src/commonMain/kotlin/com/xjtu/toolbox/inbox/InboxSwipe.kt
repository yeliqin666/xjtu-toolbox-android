package com.xjtu.toolbox.inbox

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 一行滑出来的动作。[removesRow] 为 false 的动作（标为已读）做完这一行还在，要弹回原位。
 */
internal class SwipeSpec(
    val label: String,
    val icon: ImageVector,
    val color: Color,
    val removesRow: Boolean = true,
    val run: () -> Unit,
)

/** 动作对应的字面、图标和底色；[InboxSwipeAction.NONE] 没有，这一边就滑不动。 */
@Composable
internal fun swipeSpecOf(action: InboxSwipeAction, run: () -> Unit): SwipeSpec? = when (action) {
    InboxSwipeAction.NONE -> null
    InboxSwipeAction.COMPLETE -> SwipeSpec("完成", Icons.Default.Check, Color(0xFF22C55E), run = run)
    InboxSwipeAction.DELETE -> SwipeSpec("删除", Icons.Outlined.Delete, MiuixTheme.colorScheme.error, run = run)
    InboxSwipeAction.IGNORE -> SwipeSpec("忽略", Icons.Default.VisibilityOff, Color(0xFF8E8E93), run = run)
    InboxSwipeAction.CLEAR -> SwipeSpec("清除", Icons.Default.ClearAll, Color(0xFF3B82F6), run = run)
    InboxSwipeAction.READ -> SwipeSpec("标为已读", Icons.Default.DoneAll, MiuixTheme.colorScheme.primary, removesRow = false, run = run)
}

/**
 * 能左右滑的一行，做法同 GitHub Mobile 的收件箱：拖过行宽的 [TRIGGER_FRACTION] 松手就执行，
 * 没拖到就弹回去；过线时震一下、底色变实，松手前看得出会不会触发。
 *
 * [swipeLeft] 是手指往左拖时执行的动作，底色从右边露出来；[swipeRight] 反之。给 null 的那一边拖不动。
 * 横向拖动要先越过系统的触摸阈值才接管，竖着滚列表不受影响。
 */
@Composable
internal fun SwipeActionRow(
    swipeLeft: SwipeSpec?,
    swipeRight: SwipeSpec?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (swipeLeft == null && swipeRight == null) {
        Box(modifier) { content() }
        return
    }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val offset = remember { Animatable(0f) }
    var width by remember { mutableIntStateOf(0) }
    var armed by remember { mutableStateOf(false) }
    // 拖动中按手指位移同步累加，不等动画协程回读，同一帧多次回调也不丢
    var target by remember { mutableFloatStateOf(0f) }
    val left by rememberUpdatedState(swipeLeft)
    val right by rememberUpdatedState(swipeRight)
    val threshold = width * TRIGGER_FRACTION

    fun clamp(x: Float): Float = x.coerceIn(if (left != null) -width.toFloat() else 0f, if (right != null) width.toFloat() else 0f)

    fun settle(velocity: Float) = scope.launch {
        val x = offset.value
        val spec = if (x > 0) right else if (x < 0) left else null
        // 快速甩一下也算：拖过一半阈值、速度朝同一个方向就够
        val flung = abs(velocity) > FLING_VELOCITY && velocity * x > 0 && abs(x) > threshold / 2
        if (spec != null && (abs(x) >= threshold || flung)) {
            offset.animateTo(if (x > 0) width.toFloat() else -width.toFloat())
            spec.run()
            if (!spec.removesRow) offset.animateTo(0f)
        } else {
            offset.animateTo(0f)
        }
        armed = false
        target = offset.value
    }

    Box(modifier.onSizeChanged { width = it.width }.clipToBounds()) {
        val x = offset.value
        val spec = if (x > 0) swipeRight else if (x < 0) swipeLeft else null
        if (spec != null) {
            val gap = with(density) { abs(x).toDp() }
            Box(Modifier.matchParentSize()) {
                Box(
                    Modifier
                        .align(if (x > 0) Alignment.CenterStart else Alignment.CenterEnd)
                        .width(gap)
                        .fillMaxHeight()
                        .clipToBounds()
                        .background(if (armed) spec.color else spec.color.copy(alpha = 0.55f)),
                    contentAlignment = if (x > 0) Alignment.CenterStart else Alignment.CenterEnd,
                ) {
                    Row(
                        Modifier.wrapContentWidth(if (x > 0) Alignment.Start else Alignment.End, unbounded = true).padding(horizontal = 22.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(spec.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(spec.label, color = Color.White, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
        Box(
            Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        val next = clamp(target + delta)
                        target = next
                        scope.launch { offset.snapTo(next) }
                        val nowArmed = abs(next) >= threshold
                        if (nowArmed != armed) {
                            armed = nowArmed
                            if (nowArmed) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    },
                    onDragStarted = { target = offset.value },
                    onDragStopped = { velocity -> settle(velocity) },
                ),
        ) { content() }
    }
}

/** 拖过行宽的这个比例松手才执行。 */
private const val TRIGGER_FRACTION = 0.35f

/** 甩动的速度门槛（px/s）。 */
private const val FLING_VELOCITY = 1500f
