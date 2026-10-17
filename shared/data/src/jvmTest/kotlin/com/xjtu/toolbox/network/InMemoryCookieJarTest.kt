package com.xjtu.toolbox.network

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InMemoryCookieJarTest {

    private val url = "https://www.baidu.com/s".toHttpUrl()

    private fun cookie(name: String, value: String, expiresAt: Long = System.currentTimeMillis() + 60_000) =
        Cookie.Builder().name(name).value(value).domain("baidu.com").path("/").expiresAt(expiresAt).build()

    @Test
    fun sameNameReplacesAndExpiredDrops() {
        val jar = InMemoryCookieJar()
        jar.saveFromResponse(url, listOf(cookie("BAIDUID", "a"), cookie("old", "x", expiresAt = 1)))
        jar.saveFromResponse(url, listOf(cookie("BAIDUID", "b")))
        val sent = jar.loadForRequest(url)
        assertEquals(listOf("BAIDUID" to "b"), sent.map { it.name to it.value })
        assertEquals(1, jar.size)
    }

    @Test
    fun clearAndDomainMatching() {
        val jar = InMemoryCookieJar()
        jar.saveFromResponse(url, listOf(cookie("k", "v")))
        assertTrue(jar.loadForRequest("https://www.so.com/".toHttpUrl()).isEmpty())
        jar.clear()
        assertTrue(jar.loadForRequest(url).isEmpty())
    }
}