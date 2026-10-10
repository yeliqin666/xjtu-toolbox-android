package com.xjtu.toolbox.inbox

import android.content.Context
import com.xjtu.toolbox.auth.LoginType
import com.xjtu.toolbox.auth.SessionManager
import com.xjtu.toolbox.auth.ensureSite
import com.xjtu.toolbox.library.AppLibrarySession
import com.xjtu.toolbox.library.LibraryApi
import com.xjtu.toolbox.library.LibraryStatus

/**
 * `:app` 侧那条「图书馆座位补拉」—— `:data` 的 [AppInboxSource] 收的那条构造参数就是它。
 *
 * 逐字来自搬进 `:data` 之前 `afterRefresh` 里那一段：有座位待办时现查一次座位系统，再把结果交给
 * `LibraryStatus.publish`（后台提醒 + 首页信号 + 收纳待办）。它留在 `:app` 的理由只有一件事：
 * 那三样搬不走的东西全绑着宿主 —— `LibraryReminderScheduler` 要 [Context]、`LibraryStatus` 自己在
 * `:app`、`AppLibrarySession` 是把 `SiteSession` 包成 `:data` 的 `LibrarySession` 的适配器
 * （`:data` 不认识 `SiteSession`，这正是那条缝的意义）。共享侧只该看见「本端要不要补拉」。
 */
fun appInboxLibraryBooking(context: Context, sessionManager: SessionManager?): suspend () -> Unit = booking@{
    val manager = sessionManager ?: return@booking
    runCatching {
        LibraryApi(AppLibrarySession(manager.ensureSite(LoginType.LIBRARY, userInitiated = true))).fetchMyBooking().getOrThrow()
    }.onSuccess { LibraryStatus.publish(context, it) }
}
