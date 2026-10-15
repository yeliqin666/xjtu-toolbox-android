package com.xjtu.toolbox.home

import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/** 按时刻打招呼。 */
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
