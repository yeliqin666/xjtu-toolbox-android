package com.xjtu.toolbox.agent

/** `set_alarm` 的判据：参数合不合法、该重复哪几天。 */
sealed interface AgentAlarmPlan {

    /** 参数不合法：把这句话原样回给模型。 */
    data class Invalid(val reply: String) : AgentAlarmPlan

    /** 可以开闹钟。 */
    data class Ok(val hour: Int, val minute: Int, val repeatOnIsoDays: List<Int>) : AgentAlarmPlan
}

fun agentAlarmPlan(hour: Int?, minute: Int?, days: String?): AgentAlarmPlan {
    val h = hour ?: return AgentAlarmPlan.Invalid(ToolReply.missing("hour"))
    val m = minute ?: 0
    if (h !in 0..23) return AgentAlarmPlan.Invalid(ToolReply.outOfRange("hour", "0-23"))
    if (m !in 0..59) return AgentAlarmPlan.Invalid(ToolReply.outOfRange("minute", "0-59"))
    val repeatOnIsoDays = days.orEmpty()
        .split(",", "，", " ")
        .mapNotNull { day ->
            when (day.trim().uppercase()) {
                "MON", "MONDAY", "周一", "星期一" -> 1
                "TUE", "TUESDAY", "周二", "星期二" -> 2
                "WED", "WEDNESDAY", "周三", "星期三" -> 3
                "THU", "THURSDAY", "周四", "星期四" -> 4
                "FRI", "FRIDAY", "周五", "星期五" -> 5
                "SAT", "SATURDAY", "周六", "星期六" -> 6
                "SUN", "SUNDAY", "周日", "星期日" -> 7
                else -> null
            }
        }
    return AgentAlarmPlan.Ok(h, m, repeatOnIsoDays.distinct())
}
