package com.xjtu.toolbox.agent

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * 全 App 手指最近按在哪（根坐标），屁岱拿来看。不是 State：只在它自己的帧循环里读，
 * 手指动不引起重组。松手后再看 [LINGER_MS] 才回正。
 */
internal object PidaiTouch {
    private const val LINGER_MS = 900L
    private var x = 0f
    private var y = 0f
    private var down = false
    private var upAt = Long.MIN_VALUE / 2

    fun point(): Offset? =
        if (down || android.os.SystemClock.uptimeMillis() - upAt < LINGER_MS) Offset(x, y) else null

    fun record(p: Offset, pressed: Boolean) {
        x = p.x
        y = p.y
        if (pressed) down = true
        else if (down) { down = false; upAt = android.os.SystemClock.uptimeMillis() }
    }
}

/** 挂在 App 最外层，只看不拦：Initial 阶段读位置，不消费事件。 */
internal fun Modifier.feedPidaiTouch(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull() ?: continue
            PidaiTouch.record(change.position, change.pressed)
        }
    }
}

/** 屁岱的视线：[look] 给 BloubBotIcon 的 gaze，[track] 挂在屁岱自己身上记下它的中心。 */
internal class PidaiGaze(val look: () -> Offset?, val track: Modifier)

/** 看手指：近处按比例偏，远处只取方向。 */
@Composable
internal fun rememberPidaiGaze(): PidaiGaze {
    val reach = with(LocalDensity.current) { 160.dp.toPx() }
    return remember(reach) {
        val center = floatArrayOf(Float.NaN, 0f)
        PidaiGaze(
            look = look@{
                val p = PidaiTouch.point() ?: return@look null
                if (center[0].isNaN()) return@look null
                val dx = p.x - center[0]
                val dy = p.y - center[1]
                val k = 1f / maxOf(kotlin.math.hypot(dx, dy), reach)
                Offset(dx * k, dy * k)
            },
            track = Modifier.onGloballyPositioned {
                val c = it.boundsInRoot().center
                center[0] = c.x
                center[1] = c.y
            },
        )
    }
}
