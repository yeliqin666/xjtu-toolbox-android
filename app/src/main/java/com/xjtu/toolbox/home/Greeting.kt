package com.xjtu.toolbox.home

import java.time.LocalTime

/**
 * 按时刻打招呼。首页「今日」卡和屁岱首屏共用，两处说法保持一致。
 *
 * 分档照着学生的作息来，而不是只分上午下午：
 * 七点前起来的人值得一句「早安」；一点到两点是午休，说「午安」；
 * 十点以后多半在收尾一天，说「晚安」；过了零点还开着 App 的，就提醒一句夜深了。
 */
object Greeting {
    fun of(time: LocalTime = LocalTime.now()): String = when (time.hour) {
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
