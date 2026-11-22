package com.xjtu.toolbox.schedule

import kotlin.time.Instant

/**
 * 系统日历里的一条日程（一次发生；重复日程已经按次展开）。只读：岱宗盒子只显示，不改系统日历。
 *
 * 全天日程的 [begin] / [end] 由宿主换成校园时区那天的 0 点（`end` 是最后一天的次日 0 点），
 * 和定时日程按同一套「这段时间落在哪几天」的规则处理。
 */
data class SystemCalendarEvent(
    val title: String,
    val begin: Instant,
    val end: Instant,
    val allDay: Boolean,
    val location: String = "",
    /** 来自哪个日历账户（「工作」「生日」…），显示在副标题里。 */
    val calendarName: String = "",
)
