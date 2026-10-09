package com.xjtu.toolbox.auth

/**
 * 随被测代码从 `:app` 搬进 `:data:jvmTest`：被测的是 `internal` 的纯判据，跨模块看不见
 * （`SiteSnapshots.Companion.encode/decode`、`PersistentCookieJar.Companion.hostMatches`）。
 * 判据只留一份，测试跟着代码走。断言逐条保留，只把 JUnit4 的注解/断言换成 `kotlin.test`。
 */

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SiteSnapshotsTest {
    private fun jwt(exp: Long) =
        "h." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("""{"exp":$exp}""".toByteArray()) + ".s"

    @Test
    fun `往返不丢，空令牌也算一份快照`() {
        val tokens = mapOf("auth_token" to "abc", "kq_base_url" to "https://bk-kq.xjtu.edu.cn/sa")
        assertEquals(tokens, SiteSnapshots.decode(SiteSnapshots.encode(tokens, 1000L), 2000L))
        assertEquals(emptyMap<String, String>(), SiteSnapshots.decode(SiteSnapshots.encode(emptyMap(), 0L), 0L))
    }

    @Test
    fun `太旧的不恢复`() {
        val raw = SiteSnapshots.encode(mapOf("a" to "b"), 0L)
        assertEquals("b", SiteSnapshots.decode(raw, SiteSnapshots.MAX_AGE_MS)?.get("a"))
        assertNull(SiteSnapshots.decode(raw, SiteSnapshots.MAX_AGE_MS + 1))
    }

    @Test
    fun `令牌已过期的不恢复`() {
        val raw = SiteSnapshots.encode(mapOf("access_token" to jwt(1000), "user" to "x"), 0L)
        assertEquals("x", SiteSnapshots.decode(raw, 500_000L)?.get("user"))
        assertNull(SiteSnapshots.decode(raw, 1_000_000L))
    }

    @Test
    fun `坏数据当没有`() {
        assertNull(SiteSnapshots.decode("not json", 0L))
        assertNull(SiteSnapshots.decode("""{"at":0,"tokens":{"a":{"b":1}}}""", 0L))
        assertNull(SiteSnapshots.decode("""{"at":0,"tokens":{"a":null}}""", 0L))
        assertNull(SiteSnapshots.decode("""{"tokens":{}}""", 0L))
    }
}
