package com.xjtu.toolbox.network

/**
 * 随被测代码从 `:app` 搬进 `:data:jvmTest`：被测的是 `internal` 的纯判据，跨模块看不见
 * （`SiteSnapshots.Companion.encode/decode`、`PersistentCookieJar.Companion.hostMatches`）。
 * 判据只留一份，测试跟着代码走。断言逐条保留，只把 JUnit4 的注解/断言换成 `kotlin.test`。
 */

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
