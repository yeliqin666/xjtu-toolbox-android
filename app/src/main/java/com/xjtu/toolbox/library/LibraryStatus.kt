package com.xjtu.toolbox.library

import android.content.Context
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.home.HomeSignals
import com.xjtu.toolbox.inbox.InboxCategories
import com.xjtu.toolbox.inbox.InboxStore
import com.xjtu.toolbox.inbox.OwnInbox
import com.xjtu.toolbox.notification.AutoDndScheduler
import com.xjtu.toolbox.notification.LibraryReminderScheduler

/**
 * 拿到一份新的「我的预约」后统一往外发：后台提醒、首页和屁岱的待办信号、收纳待办、自动勿扰。
 * 首页后台刷新和图书馆页都走这里，哪边先查到哪边更新，签到后待办立刻消失。
 */
object LibraryStatus {
    private const val PREFS = "library_status"
    private const val KEY_DESK_SEAT = "desk_booked_seat"

    /** 按账号分开存：别的账号扫码约的座位不能顶到当前账号头上。 */
    private fun prefs(context: Context) =
        context.getSharedPreferences("$PREFS${AccountContext.safeSuffix()}", Context.MODE_PRIVATE)

    fun urgentAction(booking: MyBookingInfo?): String? =
        booking?.actionUrls?.keys?.firstOrNull { it in URGENT_BOOKING_ACTIONS }

    /**
     * @param bookedAtDesk 这张预约是不是**在座位前扫码**约下来的（`LibraryScreen` 的 `bookedAtDesk`）。
     *
     * 学校只在闸机签到之后才把状态改成「使用中」，可用户扫码约座时人已经坐在座位上了——
     * 那时状态还是「已预约」。所以「人在馆里」不能只看状态，见 [isSeatedInside]。
     *
     * 这个信号必须**记到盘上**：扫码约座之后，首页每 10 分钟还会后台查一次预约
     * （[com.xjtu.toolbox.home.HomeStatsRefresher]），它拿到的状态仍然是「已预约」、
     * 也天然没有 `bookedAtDesk` 这个上下文。不落盘的话，下一轮刷新就会把刚开的勿扰关掉。
     * 落的是**座位号**，只有"还是同一张预约"才算数，换座 / 取消 / 离馆都会让它失效。
     */
    fun publish(
        context: Context,
        booking: MyBookingInfo?,
        account: String? = AccountContext.activeAccountId,
        bookedAtDesk: Boolean = false,
    ) {
        LibraryReminderScheduler.sync(context, booking)

        // 只认当前账号的预约：别的账号那一轮不能改这个账号的勿扰
        val isCurrent = account == AccountContext.activeAccountId
        if (isCurrent) {
            val p = prefs(context)
            val seat = booking?.seatId?.takeIf { it.isNotBlank() }
            val remembered = p.getString(KEY_DESK_SEAT, null)
            // 这一轮新扫码约成的座位，或者（还是同一张预约时）沿用之前记下的
            val current = when {
                bookedAtDesk && seat != null -> seat
                seat != null && seat == remembered -> remembered
                else -> null
            }
            // 闸机签到之后状态自己就说明在馆，这条标记可以退休
            val seatedAtDesk = current != null && booking?.statusText != "使用中"
            val keep = if (seatedAtDesk) current else null
            if (keep == null) p.edit().remove(KEY_DESK_SEAT).apply()
            else if (keep != remembered) p.edit().putString(KEY_DESK_SEAT, keep).apply()
            AutoDndScheduler.setLibraryInside(context, isSeatedInside(booking?.statusText, seatedAtDesk))
        }

        val action = urgentAction(booking)
        if (isCurrent) HomeSignals.libraryUrgentAction = action
        InboxStore.setTodos(InboxCategories.LIBRARY, listOfNotNull(action?.let { OwnInbox.library(it, booking) }), account)
    }
}
