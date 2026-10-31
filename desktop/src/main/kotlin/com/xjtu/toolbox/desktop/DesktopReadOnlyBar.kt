package com.xjtu.toolbox.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 单实例锁没抢到时的横幅（B-13）：顶在窗口最上面，一直挂着，直到这一份进程退出。
 *
 * 为什么非要挂出来：只读这件事在别处**看不出来**（屏都画得出来、按钮都点得动，只是存不下）。
 * 与其让用户改完设置发现没生效、过一会儿再发现隔壁那个窗口才是活的，不如一进来就说清楚谁在上面、
 * 该先关掉谁。文案本身来自 `:shared:model` 的 `InstanceLock.readOnlyReason`（那边知道 pid / 身份 /
 * 启动时刻），这里只负责画。
 */
@Composable
internal fun DesktopReadOnlyBar(reason: String) {
    val cs = MiuixTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().background(cs.secondaryContainer).padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text("这一端是只读的（另一个实例正在运行）", color = cs.onSurface, fontSize = 13.sp)
        Text(reason, color = cs.onBackgroundVariant, fontSize = 11.sp)
    }
}
