package com.xjtu.toolbox.archive

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 分类表（§2.4 / §8.10）的验收。 */
class ArchiveRegistryTest {

    @Test
    fun `A8 凭据族六类标为 credential，非凭据不误标`() {
        val credential = listOf(
            "xjtu_credentials", "xjtu_accounts", "xjtu_cookies", "github_community", "serve",
            "cookies_normal", "cookies_webvpn", "sites_normal", "sites_webvpn", "agent_config_secure",
        )
        credential.forEach { assertTrue(ArchiveRegistry.isCredential(it), "应为凭据族：$it") }

        val notCredential = listOf(
            "agent_config", "app_settings", "schedule_cache", "library_favorites", "venue_favorites",
        )
        notCredential.forEach { assertFalse(ArchiveRegistry.isCredential(it), "不应误标凭据族：$it") }
    }

    @Test
    fun `端内私有项标为 excluded：换包判据 门禁令牌 迁移残留`() {
        assertTrue(ArchiveRegistry.isExcluded("data_cache_meta"))
        assertTrue(ArchiveRegistry.isExcluded("serve"))
        assertTrue(ArchiveRegistry.isExcluded("agent_config_fallback"))
        assertFalse(ArchiveRegistry.isExcluded("app_settings"))
    }

    @Test
    fun `守卫：没有任何 store 名同时是 plain 与 secure`() {
        val plain = ArchiveRegistry.allEntries()
            .filterValues { it.carrier == ArchiveRegistry.Carrier.PLAIN_KV }
            .keys
        val secure = ArchiveRegistry.allEntries()
            .filterValues { it.carrier == ArchiveRegistry.Carrier.SECURE_KV }
            .keys
        assertTrue(plain.intersect(secure).isEmpty(), "同名不得同时 plain 与 secure：${plain.intersect(secure)}")
    }
}
