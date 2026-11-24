package com.xjtu.toolbox.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.xjtu.toolbox.ui.components.LocalPageVisible
import com.xjtu.toolbox.util.nowOnCampus
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

/**
 * 校园时间，精确到分钟：可见页面每到整分钟重算，回到前台 / 切回 tab 时立即校准；后台和隐藏 tab 不轮询。
 * 只到分钟：同一分钟里来回切页校准出的是同一个值，不会让调用方白白重组。
 */
@Composable
fun rememberCampusNow(): LocalDateTime {
    var now by remember { mutableStateOf(nowOnCampus().toMinute()) }
    val owner = LocalLifecycleOwner.current
    // 可见性在副作用里看：调用方（比如整个首页）不必因为页面被盖住 / 露出而重组
    val visible = LocalPageVisible.current
    LaunchedEffect(owner, visible) {
        snapshotFlow { visible.value }.collectLatest { shown ->
            if (shown) owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val exact = nowOnCampus()
                    now = exact.toMinute()
                    delay((60 - exact.second) * 1_000L)
                }
            }
        }
    }
    return now
}

internal fun LocalDateTime.toMinute(): LocalDateTime = LocalDateTime(date, LocalTime(hour, minute))
