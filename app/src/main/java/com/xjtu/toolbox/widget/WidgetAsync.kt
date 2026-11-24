package com.xjtu.toolbox.widget

import android.content.BroadcastReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 小组件更新放到 IO 线程做，广播用 [goAsync] 撑到做完。
 *
 * `updateAppWidget` 是跨进程调用，桌面忙时一次能卡上百毫秒；应用一启动系统就会发更新广播，
 * 在主线程做正好卡在启动那几帧上（实测 200ms+）。
 */
internal fun BroadcastReceiver.updateWidgetsAsync(block: () -> Unit) {
    val pending = goAsync()
    CoroutineScope(Dispatchers.IO).launch {
        try {
            block()
        } finally {
            pending.finish()
        }
    }
}
