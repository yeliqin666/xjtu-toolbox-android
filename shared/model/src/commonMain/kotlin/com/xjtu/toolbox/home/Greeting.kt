package com.xjtu.toolbox.home

import com.xjtu.toolbox.util.nowOnCampus
import kotlinx.datetime.LocalTime

/** 按时刻打招呼。 */
object Greeting {

    /** 北京时间的此刻：问候和课表、提醒用同一个钟。 */
    fun now(): LocalTime = nowOnCampus().time

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
