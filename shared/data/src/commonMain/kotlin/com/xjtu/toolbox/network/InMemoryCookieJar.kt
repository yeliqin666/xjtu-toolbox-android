package com.xjtu.toolbox.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 只在内存里的 CookieJar：联网搜索、网页阅读这类不登录的匿名会话用。
 *
 * 和 [PersistentCookieJar] 是一对：那边存学校站点的登录态并落盘，这边进程一退就没，也不按账号分。
 * 同名同域同路径的 cookie 后到的顶掉先到的；过期的读时剔掉。[clear] 给「每次换一份新 cookie」的场景（百度）。
 */
class InMemoryCookieJar : CookieJar {
    private val store = CopyOnWriteArrayList<Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(store) {
            for (c in cookies) {
                store.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
                store.add(c)
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        store.removeAll { it.expiresAt <= now }
        return store.filter { it.matches(url) }
    }

    fun clear() = store.clear()

    /** 现存几条（测试用）。 */
    val size: Int get() = store.size
}