package com.xjtu.toolbox.agent

/**
 * `set_alarm` 的**判据**：参数合不合法、该重复哪几天。**一个字都不碰 Intent**。
 *
 * 搬迁前这段判据与「发 Intent」写在一起（`AgentTool.kt:2610`）；拆开的界线就在「谁需要
 * Android」：判据是纯逻辑（进 `:core`，[AgentHost.setAlarm] 那边只负责发出去），
 * 而重复星期原来直接写 `java.util.Calendar.MONDAY` 这类常量 —— 那是 JVM 类型，
 * 这里改回 **ISO 星期号（周一 = 1 … 周日 = 7）**，由宿主翻成它那一套（`:app` 翻成
 * `Calendar.*`：周日在它那儿是 1）。
 */
sealed interface AgentAlarmPlan {

    /** 参数不合法：把这句话原样回给模型（与搬迁前同一批文案）。 */
    data class Invalid(val reply: String) : AgentAlarmPlan

    /**
     * 可以开闹钟。[repeatOnIsoDays] 空 = 单次；顺序是用户写下的顺序去重后的
     * （与搬迁前 `ArrayList(repeatDays.distinct())` 逐字同一条判据）。
     */
    data class Ok(val hour: Int, val minute: Int, val repeatOnIsoDays: List<Int>) : AgentAlarmPlan
}

/**
 * 判据本体，逐字来自搬迁前的 `AgentToolRegistry.setAlarm`：`hour` 必填且 0–23、`minute` 缺省 0
 * 且 0–59、[days] 按 `,`／`，`／空格 切开，认中英文与缩写（认不出来的一律丢掉），去重保序。
 */
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
