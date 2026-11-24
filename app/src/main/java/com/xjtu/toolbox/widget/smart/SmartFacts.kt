package com.xjtu.toolbox.widget.smart

import com.xjtu.toolbox.inbox.InboxItem
import com.xjtu.toolbox.library.LibrarySeatFact
import com.xjtu.toolbox.schedule.ExamCountdown
import com.xjtu.toolbox.schedule.SlotAttendanceMarks
import com.xjtu.toolbox.widget.LocalSwipe
import com.xjtu.toolbox.widget.WidgetCourse

/**
 * 卡片一次重画时**已经读进本机的全部事实**。
 *
 * 这一层刻意做成一个哑数据类：读缓存（SharedPreferences / Room / DataCache）留在
 * `SmartWidgetUpdater.factsOf`，判「这一屏画什么」留在 [SmartArbiter]——于是仲裁那套
 * 顺序表能在普通 JVM 单测里逐条摆事实、断言结果，不用起模拟器、也不碰任何存储。
 *
 * 每一项都允许缺失（null / 空表），因为**拿不到就不画**是这张卡片的底线（§9.1）：
 * 谁缺了谁那一档退让，绝不画占位符、也绝不把「没数据」显示成「没有事」。
 */
internal data class SmartFacts(
    /** 距 00:00 的分钟。 */
    val nowMinute: Int,
    /** 当前时刻（epoch millis）：算待办剩余时间用。 */
    val nowMs: Long = 0L,
    /** 本机时区相对 UTC 的分钟偏移（`+08:00` ⇒ 480）：把 epoch millis 换回「几点几分」。 */
    val zoneOffsetMinutes: Int = 480,
    /** 今天的课（含自建日程），按开始时间排。 */
    val courses: List<WidgetCourse> = emptyList(),
    /** 这一学期落在本机的考勤索引；null = 没有考勤缓存。 */
    val attendance: SlotAttendanceMarks? = null,
    /** 今天的日期（`2026-11-18`），按日期 + 节次去考勤流水里取这一格。 */
    val date: String = "",
    /** 本机记下的今天那条刷卡（后台问到的第一手事实）。 */
    val swipe: LocalSwipe? = null,
    /** 上次**成功**问到学校是几分钟前；null = 从没问过。 */
    val checkedAgoMin: Int? = null,
    /** 上次成功问到的时刻（epoch millis）；判「本堂课的 Q1/Q2 还欠着」用它，跨零点也不会算错。 */
    val lastCheckAtMs: Long? = null,
    /** 上次成功问到的时刻（`08:05`）。 */
    val checkedAtClock: String? = null,
    /** 校园卡余额文本（`¥128.60`）；null = 没有缓存，卡片写 `--` 而不是 `¥0.00`。 */
    val balance: String? = null,
    /** 余额那份缓存是什么时候更新的（`12:41`）。 */
    val balanceClock: String? = null,
    /** 今日消费（`￥18.50`），饭点细节用。 */
    val todaySpend: String? = null,
    /** 收纳里还没办的待办（已按截止时间排）。 */
    val todos: List<InboxItem> = emptyList(),
    /** 图书馆座位事实（账号隔离，见 `LibrarySeatStore`）。 */
    val library: LibrarySeatFact? = null,
    /** 最近一场还没考完的考试。 */
    val exam: ExamCountdown.Next? = null,
    /** 今天放假的话是哪个节日 / 假期。 */
    val holiday: String? = null,
    /** 调课待报的一句摘要（`ScheduleDiff.pending`）。 */
    val scheduleDiff: String? = null,
    /** 教务通知有新的话的一句钩子（`教务处 · 新通知`）。 */
    val notice: String? = null,
) {
    /** epoch millis → 当天的「距 00:00 的分钟」。 */
    fun minuteOf(ms: Long): Int {
        val raw = (ms / 60_000L + zoneOffsetMinutes) % 1440L
        return ((raw + 1440L) % 1440L).toInt()
    }
}
