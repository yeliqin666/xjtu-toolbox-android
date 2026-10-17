package com.xjtu.toolbox.inbox

import android.content.Context
import com.xjtu.toolbox.auth.LoginType
import com.xjtu.toolbox.auth.SessionManager
import com.xjtu.toolbox.auth.ensureSite
import com.xjtu.toolbox.library.AppLibrarySession
import com.xjtu.toolbox.library.LibraryApi
import com.xjtu.toolbox.library.LibraryStatus

/** `:app` 侧那条「图书馆座位补拉」—— `:data` 的 [AppInboxSource] 收的那条构造参数就是它。 */
fun appInboxLibraryBooking(context: Context, sessionManager: SessionManager?): suspend () -> Unit = booking@{
    val manager = sessionManager ?: return@booking
    runCatching {
        LibraryApi(AppLibrarySession(manager.ensureSite(LoginType.LIBRARY, userInitiated = true))).fetchMyBooking().getOrThrow()
    }.onSuccess { LibraryStatus.publish(context, it) }
}
