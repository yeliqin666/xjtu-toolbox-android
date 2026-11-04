package com.xjtu.toolbox.inbox

import com.xjtu.toolbox.auth.LoginType
import com.xjtu.toolbox.auth.SessionManager
import com.xjtu.toolbox.auth.ensureSite

/** `:core` 的 [InboxSource] 的实现（Android 与桌面侧共用）—— 包住 [SchoolInbox]。 */
class AppInboxSource(
    private val sessionManager: SessionManager?,
    /** 本端独有的图书馆座位补拉；`null` = 本端没有那一路（桌面端）。见类 KDoc。 */
    private val requeryLibraryBooking: (suspend () -> Unit)? = null,
) : InboxSource {

    override suspend fun refresh(account: String?) {
        val manager = sessionManager ?: return
        SchoolInbox.refresh(manager.ensureSite(LoginType.YWTB, userInitiated = true), account)
    }

    /** 用户没主动要刷新，登录要静默。 */
    override suspend fun refreshSchoolTodos(account: String?) {
        val manager = sessionManager ?: return
        SchoolInbox.refreshTodos(manager.ensureSite(LoginType.YWTB, silent = true), account)
    }

    override fun isDue(account: String?, now: Long): Boolean = SchoolInbox.isDue(account, now)

    override suspend fun afterRefresh(data: InboxData) {
        // 有座位待办时顺带现查一次，签过到的马上消失
        if (data.todos[InboxCategories.LIBRARY].isNullOrEmpty()) return
        requeryLibraryBooking?.invoke()
    }
}
