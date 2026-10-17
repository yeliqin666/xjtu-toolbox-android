package com.xjtu.toolbox.library

import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.auth.SiteSession
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** 把 Android 侧的 CAS 会话（[SiteSession]）包成 `:data` 的 [LibrarySession]。 */
class AppLibrarySession(private val site: SiteSession) : LibrarySession {

    override val client: OkHttpClient get() = site.client

    override suspend fun fetch(request: Request): Response = site.executeWithReAuth(request)

    override fun authExpired(siteName: String) = AuthExpiredException(siteName)
}
