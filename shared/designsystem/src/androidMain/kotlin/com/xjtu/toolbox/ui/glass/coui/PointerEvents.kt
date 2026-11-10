package com.xjtu.toolbox.ui.glass.coui

import androidx.compose.ui.input.pointer.PointerEvent

// Compose 合成的 CANCEL 没有平台事件, 且将上一帧原地转为已消费的抬手。
internal fun PointerEvent.isCouiTouchCancelled(): Boolean =
    motionEvent == null && changes.any { it.previousPressed } && changes.all {
        !it.pressed && (!it.previousPressed ||
            (it.isConsumed && it.uptimeMillis == it.previousUptimeMillis &&
                it.position == it.previousPosition))
    }
