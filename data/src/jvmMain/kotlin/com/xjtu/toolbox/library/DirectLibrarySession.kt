package com.xjtu.toolbox.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** 没有会话内核的宿主用的直连会话（JVM 单测也用它）。 */
class DirectLibrarySession(override val client: OkHttpClient) : LibrarySession {

    override suspend fun fetch(request: Request): Response =
        withContext(Dispatchers.IO) { client.newCall(request).execute() }

    /** 没有会话内核 ⇒ 抛基类本身。 */
    override fun authExpired(siteName: String) = com.xjtu.toolbox.auth.SessionExpiredException(siteName)
}
