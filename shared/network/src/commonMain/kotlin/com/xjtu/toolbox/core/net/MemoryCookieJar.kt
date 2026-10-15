package com.xjtu.toolbox.core.net

import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.http.Cookie
import io.ktor.http.Url

/** 内存版会话存储。 */
class MemoryCookieJar : CookiesStorage {
    private val byHost = mutableMapOf<String, MutableMap<String, Cookie>>()

    /** 确认 Set-Cookie 真的被解析并落库了。 */
    var addedCount: Int = 0
        private set

    override suspend fun addCookie(requestUrl: Url, cookie: Cookie) {
        addedCount++
        byHost.getOrPut(requestUrl.host) { mutableMapOf() }[cookie.name] = cookie
    }

    override suspend fun get(requestUrl: Url): List<Cookie> =
        byHost[requestUrl.host]?.values?.toList().orEmpty()

    /** 内存实现无资源可释放；持久化实现也必须在这里**什么都不做**（见类注释）。 */
    override fun close() = Unit


    suspend fun allCookies(): List<Cookie> = byHost.values.flatMap { it.values }

    suspend fun cookieHeaderFor(url: Url): String? =
        get(url)
            .joinToString("; ") { "${it.name}=${it.value}" }
            .takeIf { it.isNotEmpty() }
}
