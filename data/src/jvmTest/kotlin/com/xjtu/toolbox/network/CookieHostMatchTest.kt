package com.xjtu.toolbox.network

/** 被测的是 `internal` 的纯判据，所以测试放在 `:data:jvmTest`。 */

import okhttp3.Cookie
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CookieHostMatchTest {

    private val hostOnly = Cookie.Builder().name("a").value("1").hostOnlyDomain("login.xjtu.edu.cn").build()
    private val domain = Cookie.Builder().name("b").value("1").domain("xjtu.edu.cn").build()

    @Test
    fun `hostOnly 的 cookie 只发给自己那个主机`() {
        assertTrue(PersistentCookieJar.hostMatches("login.xjtu.edu.cn", hostOnly))
        assertFalse(PersistentCookieJar.hostMatches("a.login.xjtu.edu.cn", hostOnly))
        assertFalse(PersistentCookieJar.hostMatches("xjtu.edu.cn", hostOnly))
    }

    @Test
    fun `带 Domain 的 cookie 发给本域和子域`() {
        assertTrue(PersistentCookieJar.hostMatches("xjtu.edu.cn", domain))
        assertTrue(PersistentCookieJar.hostMatches("login.xjtu.edu.cn", domain))
        assertFalse(PersistentCookieJar.hostMatches("evilxjtu.edu.cn", domain))
        assertFalse(PersistentCookieJar.hostMatches("example.com", domain))
    }
}
