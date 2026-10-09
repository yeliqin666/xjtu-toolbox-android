package com.xjtu.toolbox.inbox

import android.content.Context
import com.xjtu.toolbox.auth.AppLoginState
import com.xjtu.toolbox.auth.LoginType
import com.xjtu.toolbox.auth.ensureSite
import com.xjtu.toolbox.library.AppLibrarySession
import com.xjtu.toolbox.library.LibraryApi
import com.xjtu.toolbox.library.LibraryStatus

/**
 * :core 的 [InboxSource] 在 Android 侧的实现 —— 包住原来的 `SchoolInbox`。
 *
 * 学校那四路（消息 / 事务中心 / 预约 / 校车）走一网通办站点，**实现一行未改**；
 * [afterRefresh] 是原来写在屏里的「有座位待办就现查一次图书馆」那一段，挪到这里
 * （屏在 :core 里拿不到 `LibraryApi`/`Context`）。
 */
class AppInboxSource(
    private val loginState: AppLoginState,
    private val context: Context,
) : InboxSource {

    override suspend fun refresh(account: String?) {
        val manager = loginState.sessionManager ?: return
        SchoolInbox.refresh(manager.ensureSite(LoginType.YWTB, userInitiated = true), account)
    }

    override fun isDue(account: String?, now: Long): Boolean = SchoolInbox.isDue(account, now)

    override suspend fun afterRefresh(data: InboxData) {
        // 有座位待办时顺带现查一次，签过到的马上消失
        if (data.todos[InboxCategories.LIBRARY].isNullOrEmpty()) return
        val manager = loginState.sessionManager ?: return
        runCatching {
            LibraryApi(AppLibrarySession(manager.ensureSite(LoginType.LIBRARY, userInitiated = true))).fetchMyBooking().getOrThrow()
        }.onSuccess { LibraryStatus.publish(context, it) }
    }
}
