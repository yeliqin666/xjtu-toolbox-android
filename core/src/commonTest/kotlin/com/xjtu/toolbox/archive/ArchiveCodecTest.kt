package com.xjtu.toolbox.archive

import com.xjtu.toolbox.util.AppJson
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * codec + 纯函数门面的验收（§7）：
 *  - **A2** 值逐字相等（六种类型标签、`set` 空数组 ≠ 键不存在、`putString(key,null)` = 删键）；
 *  - **A3** 导出字节稳定（注入固定时钟后两次导出字节相同，`keys`/`set`/`stores[]`/`files[]` 全升序）；
 *  - **A8** 凭据默认不出现（对 `export()` 产物断言禁止路径 + `redacted` 字段）；
 *  - **A10** `#written_at` 归一化成 `at`，`keys` 里永不出现 `*#written_at`。
 */
class ArchiveCodecTest {

    // ── A2 ──

    @Test
    fun `A2 六种类型标签 s b i l f set 往返逐字相等`() {
        val snap = StoreSnapshot(
            rawName = "app_settings",
            keys = mapOf(
                "dark_mode" to StoreValue.S("dark"),
                "show_quick_actions" to StoreValue.B(true),
                "eula_accepted_version" to StoreValue.I(4),
                "last_auto_update_check_at" to StoreValue.L(ArchiveFixture.FIXED_NOW),
                "card_today_dinner_cache" to StoreValue.F(12.5f),
                // 乱序 + 重复，往返后应去重升序
                "favorite_seats" to StoreValue.SetValue(listOf("B-2", "A-1", "A-1")),
            ),
        )
        val record = ArchiveCodec.decodeStore(ArchiveCodec.encodeStore(snap, "app_settings", ""))

        assertEquals("app_settings", record.base)
        assertEquals("", record.suffix)
        assertEquals("app_settings", record.snapshot.rawName)
        assertEquals(StoreValue.S("dark"), record.snapshot.keys["dark_mode"])
        assertEquals(StoreValue.B(true), record.snapshot.keys["show_quick_actions"])
        assertEquals(StoreValue.I(4), record.snapshot.keys["eula_accepted_version"])
        assertEquals(StoreValue.L(ArchiveFixture.FIXED_NOW), record.snapshot.keys["last_auto_update_check_at"])
        assertEquals(StoreValue.F(12.5f), record.snapshot.keys["card_today_dinner_cache"])
        assertEquals(StoreValue.SetValue(listOf("A-1", "B-2")), record.snapshot.keys["favorite_seats"])
    }

    @Test
    fun `A2 set 空数组不等于键不存在，putString null 等于删键`() {
        // set 空数组是合法值（≠ 键不存在）
        val withEmptySet = StoreSnapshot(
            "venue_favorites",
            mapOf("favorite_venue_ids" to StoreValue.SetValue(emptyList())),
        )
        val encoded = ArchiveCodec.encodeStore(withEmptySet, "venue_favorites", "")
        assertTrue(encoded.contains(""""favorite_venue_ids":{"t":"set","v":[]}"""))
        assertEquals(
            StoreValue.SetValue(emptyList()),
            ArchiveCodec.decodeStore(encoded).snapshot.keys["favorite_venue_ids"],
        )

        // 键不存在 = 该键不在 keys 里（三端 putString(key, null) 都等于删键后的投影）
        val absent = ArchiveCodec.decodeStore(
            ArchiveCodec.encodeStore(StoreSnapshot("venue_favorites", emptyMap()), "venue_favorites", "")
        )
        assertNull(absent.snapshot.keys["favorite_venue_ids"])

        // 空字符串是「键存在但值为空」，≠ 键不存在（§2.2）
        val emptyStr = ArchiveCodec.decodeStore(
            ArchiveCodec.encodeStore(
                StoreSnapshot("app_settings", mapOf("dark_mode" to StoreValue.S(""))), "app_settings", ""
            )
        )
        assertEquals(StoreValue.S(""), emptyStr.snapshot.keys["dark_mode"])
    }

    // ── A10 ──

    @Test
    fun `A10 旁挂 written_at 折进 at，keys 里永不出现 written_at 键`() {
        val raw = mapOf(
            "schedule_2026-2027-1" to StoreValue.S("""[{"courseName":"高等数学"}]"""),
            "schedule_2026-2027-1#written_at" to StoreValue.S("1760000000000"),
        )
        val snap = ArchiveCodec.normalizeWrittenAt("schedule_cache_2253415556", raw)

        assertFalse(snap.keys.containsKey("schedule_2026-2027-1#written_at"))
        assertTrue(snap.keys.keys.none { it.endsWith(ArchiveCodec.WRITTEN_AT_SUFFIX) })
        assertEquals(1_760_000_000_000L, snap.writtenAt["schedule_2026-2027-1"])

        val encoded = ArchiveCodec.encodeStore(snap, "schedule_cache", "_2253415556")
        assertFalse(encoded.contains(ArchiveCodec.WRITTEN_AT_SUFFIX), "编码产物里不该有 #written_at")
        assertTrue(encoded.contains(""""at":1760000000000"""), "该时间应出现在 at：$encoded")
    }

    // ── A3 ──

    @Test
    fun `A3 注入固定时钟后两次导出字节相同，且 keys set stores files 全部升序`() {
        val first = StoreArchive.export(
            ArchiveFixture.accounts, "android", "5.1.1 (471)",
            ArchiveFixture.stores, ArchiveFixture.files, ArchiveFixture.fixedClock(),
        )
        val second = StoreArchive.export(
            ArchiveFixture.accounts, "android", "5.1.1 (471)",
            ArchiveFixture.stores, ArchiveFixture.files, ArchiveFixture.fixedClock(),
        )

        assertEquals(first, second, "每个路径的字节都应相同")
        assertEquals(stableBytes(first), stableBytes(second), "按路径拼接后的字节应逐字相同")

        // manifest 里 stores[] / files[] 按码点升序
        val manifest = ArchiveCodec.decodeManifest(first.getValue("manifest.json"))
        assertEquals(manifest.stores, manifest.stores.sorted())
        assertEquals(manifest.files, manifest.files.sorted())

        // store 文件里 keys 按码点升序
        val appSettingsJson = first.getValue("global/stores/app_settings.json")
        assertEquals(storeKeys(appSettingsJson), storeKeys(appSettingsJson).sorted())

        // set 去重 + 升序（夹具故意给了乱序 + 重复）
        val libraryJson = first.getValue("global/stores/library_favorites.json")
        assertTrue(libraryJson.contains(""""favorite_seats":{"t":"set","v":["A-1","B-2"]}"""))
    }

    // ── A8 ──

    @Test
    fun `A8 凭据族默认不进档案且列进 redacted，显式开关后写出且 redacted 为空`() {
        val stores = listOf(
            ArchiveFixture.credential, // xjtu_credentials
            StoreSnapshot("cookies_normal${ArchiveFixture.SUFFIX}", mapOf("all_cookies" to StoreValue.S("x"))),
            StoreSnapshot("sites_normal${ArchiveFixture.SUFFIX}", mapOf("jwxt" to StoreValue.S("""{"at":0}"""))),
            StoreSnapshot("agent_config_secure${ArchiveFixture.SUFFIX}", mapOf("api_key" to StoreValue.S("k"))),
            StoreSnapshot("github_community", mapOf("token" to StoreValue.S("t"))),
            StoreSnapshot("serve", mapOf("access_token" to StoreValue.S("s"))),
            ArchiveFixture.appSettings, // 对照：非凭据
        )
        val out = StoreArchive.export(
            ArchiveFixture.accounts, "android", "x", stores, emptyList(), ArchiveFixture.fixedClock(),
        )

        val forbidden = listOf("xjtu_credentials", "cookies_", "sites_", "agent_config_secure", "github_community", "serve")
        forbidden.forEach { f ->
            assertTrue(out.keys.none { it.contains(f) }, "禁止路径不该出现：$f")
        }
        // 非凭据照常写出
        assertTrue(out.containsKey("global/stores/app_settings.json"))

        val manifest = ArchiveCodec.decodeManifest(out.getValue("manifest.json"))
        assertTrue(manifest.redacted.contains("global/stores/xjtu_credentials.json"))
        assertTrue(manifest.redacted.contains("accounts/_2253415556/stores/cookies_normal.json"))
        assertTrue(manifest.redacted.contains("accounts/_2253415556/stores/sites_normal.json"))
        assertTrue(manifest.redacted.contains("accounts/_2253415556/stores/agent_config_secure.json"))
        assertTrue(manifest.redacted.contains("global/stores/github_community.json"))
        // serve 是端内私有（§2.4）：既不写、也不进 redacted
        assertFalse(manifest.redacted.any { it.contains("serve") })

        // 显式开关：凭据族写出，redacted 为空（A8「此时 redacted 字段为空」）
        val withCred = StoreArchive.export(
            ArchiveFixture.accounts, "android", "x", stores, emptyList(),
            ExportOptions(includeCredentials = true, now = { ArchiveFixture.FIXED_NOW }),
        )
        assertTrue(withCred.containsKey("global/stores/xjtu_credentials.json"))
        assertTrue(withCred.containsKey("accounts/_2253415556/stores/agent_config_secure.json"))
        assertTrue(ArchiveCodec.decodeManifest(withCred.getValue("manifest.json")).redacted.isEmpty())
    }

    // ── 辅助 ──

    private fun stableBytes(map: Map<String, String>): String =
        map.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}:${it.value}" }

    private fun storeKeys(json: String): List<String> {
        val obj = AppJson.parseToJsonElement(json) as JsonObject
        return (obj["keys"] as JsonObject).keys.toList()
    }
}
