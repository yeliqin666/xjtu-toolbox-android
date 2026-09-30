package com.xjtu.toolbox.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SiteSnapshotsTest {
    private fun jwt(exp: Long) =
        "h." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("""{"exp":$exp}""".toByteArray()) + ".s"

    @Test
    fun `往返不丢，空令牌也算一份快照`() {
        val tokens = mapOf("auth_token" to "abc", "kq_base_url" to "https://bk-kq.xjtu.edu.cn/sa")
        assertEquals(tokens, SiteSnapshots.decode(SiteSnapshots.encode(tokens), 0L))
        assertEquals(emptyMap<String, String>(), SiteSnapshots.decode(SiteSnapshots.encode(emptyMap()), 0L))
    }

    @Test
    fun `令牌已过期的不恢复`() {
        val raw = SiteSnapshots.encode(mapOf("access_token" to jwt(1000), "user" to "x"))
        assertEquals("x", SiteSnapshots.decode(raw, 500_000L)?.get("user"))
        assertNull(SiteSnapshots.decode(raw, 1_000_000L))
    }

    @Test
    fun `坏数据当没有`() {
        assertNull(SiteSnapshots.decode("not json", 0L))
        assertNull(SiteSnapshots.decode("""{"a":{"b":1}}""", 0L))
    }
}
