package com.xjtu.toolbox.agent

import com.xjtu.toolbox.agent.bot.cast.CastCharacter

/** 屁岱形象的节拍：互斥的动画状态，优先级 TAP/COMET > THINKING > ALERT > IDLE > REST。 */
enum class PidaiBeat { REST, IDLE, ALERT, TAP, COMET, THINKING }

/** 一次性节拍播完后保持多久再落回去；null = 持续态，不自己结束。新角色各有各的时长。 */
fun PidaiBeat.holdMs(cast: CastCharacter?): Long? {
    if (cast != null) {
        val sec = when (this) {
            PidaiBeat.IDLE -> cast.microSec
            PidaiBeat.TAP -> cast.pokeSec
            PidaiBeat.COMET -> cast.comboSec
            else -> return null
        }
        return (sec * 1000).toLong()
    }
    return when (this) {
        // wink：bloub wink duration 1.6s
        PidaiBeat.IDLE -> 1_600L
        // poke：0.7s 收回静息脸
        PidaiBeat.TAP -> 700L
        // 连戳彩蛋（转圈转晕）：2.5s 回到静息脸
        PidaiBeat.COMET -> 2_600L
        else -> null
    }
}
