package com.xjtu.toolbox.home

import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * 按时刻打招呼。首页「今日」卡和屁岱首屏共用，两处说法保持一致。
 *
 * 分档照着学生的作息来，而不是只分上午下午：
 * 七点前起来的人值得一句「早安」；一点到两点是午休，说「午安」；
 * 十点以后多半在收尾一天，说「晚安」；过了零点还开着 App 的，就提醒一句夜深了。
 *
 * 为什么在 :core：这段是纯判定，没有任何 Android 依赖，而「同一时刻两端说法不同」这种事
 * 一旦发生只会显得诡异。搬进来时把 `java.time.LocalTime` 换成了 `kotlinx-datetime.LocalTime`
 * —— 后者在各端逐位一致（JVM 上本就是 `java.time` 的包装），Web / iOS 也编得过。
 */
object Greeting {

    /** 本机时区的当前时刻。`java.time.LocalTime.now()` 在 commonMain 不存在，换 Clock + TimeZone。 */
    fun now(): LocalTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).time

    fun of(time: LocalTime = now()): String = when (time.hour) {
        in 0..3 -> "夜深了"
        in 4..6 -> "早安"
        in 7..8 -> "早上好"
        in 9..10 -> "上午好"
        in 11..12 -> "中午好"
        13 -> "午安"
        in 14..17 -> "下午好"
        in 18..21 -> "晚上好"
        else -> "晚安"
    }
}
