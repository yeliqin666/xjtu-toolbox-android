package com.xjtu.toolbox.library

import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.auth.SiteSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** 把 Android 侧的 CAS 会话（[SiteSession]）包成 `:data` 的 [LibrarySession]。 */
class AppLibrarySession(private val site: SiteSession) : LibrarySession {

    override val client: OkHttpClient get() = site.client

    override suspend fun fetch(request: Request): Response = site.executeWithReAuth(request)

    override fun authExpired(siteName: String) = AuthExpiredException(siteName)

    /** 顺手把这页上的预约卡和当前状态记进 [LibraryLog]（学期总结用）。解析、写盘都在后台，不拖调用方。 */
    override fun onMyPage(html: String, finalUrl: String, booking: MyBookingInfo?) {
        val account = AccountContext.activeAccountId
        logScope.launch {
            runCatching {
                LibraryLog.recordCards(account, LibraryPages.parseCards(html, finalUrl))
                LibraryLog.recordStatus(account, booking?.area, booking?.seatId, booking?.statusText)
            }
        }
    }

    private companion object {
        val logScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
