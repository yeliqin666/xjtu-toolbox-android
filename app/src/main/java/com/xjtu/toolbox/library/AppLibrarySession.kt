package com.xjtu.toolbox.library

import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.auth.SiteSession
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * 把 Android 侧的 CAS 会话（[SiteSession]）包成 `:data` 的 [LibrarySession]。
 *
 * 只有三行转发，但每一行都有理由（见 [LibrarySession] 的 KDoc）：
 *
 *  - [client] / [fetch] → `SiteSession` 那套「命中认证失效就 invalidate + 重认证 + 原样重放一次」
 *    的语义**一字未改**，只是换了个入口名（`executeWithReAuth` → `fetch`）；
 *  - [authExpired] → 仍然抛 `:app` 的 [AuthExpiredException]（它现在继承 `:data` 的
 *    `SessionExpiredException`，所以既是「数据层认得的会话失效」、也仍是 `:app` 各调用点按类型
 *    分支的那个类，文案逐字相同）。
 *
 * 适配器留在 `:app` 而不是 `:data`：`:data` 不认识 `SiteSession`，也不该认识 —— 这正是这条缝的意义。
 */
class AppLibrarySession(private val site: SiteSession) : LibrarySession {

    override val client: OkHttpClient get() = site.client

    override suspend fun fetch(request: Request): Response = site.executeWithReAuth(request)

    override fun authExpired(siteName: String) = AuthExpiredException(siteName)
}
