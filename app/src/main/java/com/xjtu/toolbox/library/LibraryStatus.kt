package com.xjtu.toolbox.library

import android.content.Context
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.home.HomeSignals
import com.xjtu.toolbox.inbox.InboxCategories
import com.xjtu.toolbox.inbox.InboxStore
import com.xjtu.toolbox.inbox.OwnInbox
import com.xjtu.toolbox.notification.LibraryReminderScheduler

/**
 * 拿到一份新的「我的预约」后统一往外发：后台提醒、首页和屁岱的待办信号、收纳待办。
 * 首页后台刷新和图书馆页都走这里，哪边先查到哪边更新，签到后待办立刻消失。
 */
object LibraryStatus {
    fun urgentAction(booking: MyBookingInfo?): String? =
        booking?.actionUrls?.keys?.firstOrNull { it in LibraryApi.URGENT_ACTIONS }

    fun publish(context: Context, booking: MyBookingInfo?, account: String? = AccountContext.activeAccountId) {
        LibraryReminderScheduler.sync(context, booking)
        val action = urgentAction(booking)
        if (account == AccountContext.activeAccountId) HomeSignals.libraryUrgentAction = action
        InboxStore.setTodos(InboxCategories.LIBRARY, listOfNotNull(action?.let { OwnInbox.library(it, booking) }), account)
    }
}
