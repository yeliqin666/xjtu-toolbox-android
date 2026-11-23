package com.xjtu.toolbox.termreport

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.xjtu.toolbox.util.DailyCounters
import com.xjtu.toolbox.util.nowOnCampus

/** 小游戏每天各玩了多少秒（项 = 游戏名）。 */
val GamePlaytime = DailyCounters("game_playtime")

/** App 本身的使用：`launch` 回到前台、`hour:<0–23>` 那次的钟点、`open:<路由 id>` 打开了哪个功能。 */
val AppUsage = DailyCounters("app_usage")

fun recordAppLaunch() {
    AppUsage.add("launch")
    AppUsage.add("hour:${nowOnCampus().hour}")
}

/** 单次超过这么久多半是忘了关，按上限记。 */
private const val MAX_SESSION_S = 4 * 60 * 60L

/** 包在游戏页里：在前台才计时，退到后台或离开页面时记一笔。 */
@Composable
fun TrackGamePlaytime(game: String) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, game) {
        var since: Long? = null
        fun stop() {
            since?.let { GamePlaytime.add(game, ((System.currentTimeMillis() - it) / 1000).coerceAtMost(MAX_SESSION_S)) }
            since = null
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (since == null) since = System.currentTimeMillis()
                Lifecycle.Event.ON_STOP -> stop()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            stop()
        }
    }
}
