package com.xjtu.toolbox.core.net

import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.http.Cookie
import io.ktor.http.Url

/**
 * 探针版会话存储：内存实现，用来验证最关键的一条链路 ——
 * 「Set-Cookie 收得到 → 下一次请求真的发得回去」。
 *
 * ⚠️ **第一个实测结论**（2026-10-06，Ktor 3.6.0，`javap` 验过接口）：
 *   Ktor 3 的 `CookiesStorage` 只剩两个方法
 *     `suspend get(Url): List<Cookie>` / `suspend addCookie(Url, Cookie)`
 *   外加继承自 `java.io.Closeable` 的 `close()`。
 *   Ktor 2.x 时代的 `storeCookies` / `getAll` / `cookieHeader` **已经不存在** ——
 *   Set-Cookie 的解析与请求头的拼装都由 `HttpCookies` 插件内部完成。
 *   照旧文档/旧记忆写会直接编译不过，这正是探针要先做的原因。
 *
 * 另一个必须现在就定下来的事：`close()` 由 HttpClient 持有并调用，
 * 所以**持久化实现绝不能在 close() 里清空** —— 否则「关掉一个 client」就等于把登录态丢了。
 * okhttp 的 `CookieJar` 没有 close 语义，这是换 Ktor 时最容易踩的隐性行为差异。
 */
class MemoryCookieJar : CookiesStorage {
    private val byHost = mutableMapOf<String, MutableMap<String, Cookie>>()

    /** 探针观测点：确认 Set-Cookie 真的被解析并落库了（而不是被插件丢掉）。 */
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

    // ---------- 以下是探针自己的观测接口，不属于 Ktor 接口 ----------

    suspend fun allCookies(): List<Cookie> = byHost.values.flatMap { it.values }

    suspend fun cookieHeaderFor(url: Url): String? =
        get(url)
            .joinToString("; ") { "${it.name}=${it.value}" }
            .takeIf { it.isNotEmpty() }
}
