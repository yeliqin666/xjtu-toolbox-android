package com.xjtu.toolbox.web

import com.xjtu.toolbox.archive.ArchiveFixture
import com.xjtu.toolbox.archive.ExportOptions
import com.xjtu.toolbox.archive.ImportResult
import com.xjtu.toolbox.archive.StoreArchive
import com.xjtu.toolbox.archive.StoreSnapshot
import com.xjtu.toolbox.archive.StoreValue
import com.xjtu.toolbox.archive.archiveStoreBackend
import com.xjtu.toolbox.platform.keyValueStore
import com.xjtu.toolbox.schedule.KeyValueScheduleCacheStore
import com.xjtu.toolbox.schedule.ScheduleCache
import com.xjtu.toolbox.settings.SettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S6 收尾：Web 这一端的**导入写侧**真的接到 `:core` 的 [StoreArchive.import]（§6.4）之后该成立的那几条。
 *
 * 这一端跑在**真浏览器**里（`:web:wasmJsBrowserTest` = karma + chromium，见 `web/build.gradle.kts`）
 * ⇒ `localStorage` 是真的，所以 A4 的断言对象就是**端内原来的读法**
 * （`SettingsStore(keyValueStore(...)).darkMode`、`ScheduleCache.readCurrentTerm(cache)`、
 * `keyValueStore("library_favorites").getStringSet(...)`），不是另写一个读法。
 *
 * 每个用例前后把碰到的 store 清一遍：karma 里所有用例共用同一个页面的 `localStorage`。
 * 夹具是 `:core` 的 `commonTest` 那一份（[ArchiveFixture]，A5 要求三端同一份）。
 */
class WebArchiveImportTest {

    /** 与 `ArchiveInteropTest` 同参：同一份夹具、同一个 `export` 调用。 */
    private fun archiveFromFixture(): Map<String, String> = StoreArchive.export(
        ArchiveFixture.accounts,
        "android",
        "5.1.1 (471)",
        ArchiveFixture.stores,
        ArchiveFixture.files,
        ArchiveFixture.fixedClock(),
    )

    private fun archiveOf(vararg stores: StoreSnapshot): Map<String, String> = StoreArchive.export(
        accounts = emptyList(),
        createdBy = "desktop-linux",
        appVersion = "test",
        stores = stores.toList(),
        files = emptyList(),
        options = ExportOptions(now = { ArchiveFixture.FIXED_NOW }),
    )

    private fun cleanup() {
        TOUCHED_STORES.forEach { keyValueStore(it).clear() }
    }

    // ── A5 / A4：夹具真落进 localStorage，端内原来的读法读得到 ────────────────

    @Test
    fun `A5 同一份夹具真落进 localStorage，本端原读法读得到`() {
        cleanup()
        try {
            val result = WebArchiveHost.importArchive(archiveFromFixture())

            // `files/` 那一层在导入前被滤掉（本端没有文件系统）⇒ 写进去的 files 条目数是 0（不是谎话）
            assertEquals(ImportResult.Ok(keysWritten = 7, keysKept = 0, filesWritten = 0), result)

            val settings = SettingsStore(keyValueStore(SettingsStore.APP_SETTINGS_FILE))
            assertEquals("dark", settings.darkMode)
            assertTrue(settings.showQuickActions)
            assertEquals(4, keyValueStore(SettingsStore.APP_SETTINGS_FILE).getInt("eula_accepted_version", 0))
            // A10 的 web 形态：夹具里的 `at` 落成旁挂键，读回来时又折进 `writtenAt`
            assertEquals(
                setOf("A-1", "B-2"),
                keyValueStore("library_favorites").getStringSet("favorite_seats"),
            )
            val schedule = archiveStoreBackend().readStore("schedule_cache${ArchiveFixture.SUFFIX}")
            assertEquals(ArchiveFixture.FIXED_NOW, schedule?.writtenAt?.get("schedule_current_term"))
        } finally {
            cleanup()
        }
    }

    @Test
    fun `A4 往返可读：SettingsStore 与 ScheduleCache 的原读法`() {
        cleanup()
        try {
            // 端内的形状（§6.3 的 web 行）：`l`/`f` 在 web 上没有槽位 —— 夹具里那两条按字符串落，
            // 读回来会被反推成 `s`（见 `WebArchiveImportTarget` 的 KDoc；桌面同一档，靠 toLongOrNull 反推）
            val archive = archiveOf(
                StoreSnapshot(
                    "app_settings",
                    mapOf(
                        "dark_mode" to StoreValue.S("dark"),
                        "show_quick_actions" to StoreValue.B(true),
                        "home_theme" to StoreValue.S("cards"),
                    ),
                ),
                StoreSnapshot(
                    "schedule_cache${ArchiveFixture.SUFFIX}",
                    mapOf("schedule_current_term" to StoreValue.S("\"2026-2027-1\"")),
                    mapOf("schedule_current_term" to ArchiveFixture.FIXED_NOW),
                ),
            )

            val result = WebArchiveHost.importArchive(archive)

            assertEquals(ImportResult.Ok(keysWritten = 4, keysKept = 0, filesWritten = 0), result)
            val settings = SettingsStore(keyValueStore(SettingsStore.APP_SETTINGS_FILE))
            assertEquals("dark", settings.darkMode)
            assertTrue(settings.showQuickActions)
            assertEquals("cards", settings.homeTheme)
            // 课表族：`at` 落成旁挂键 ⇒ 原来的读法（`ScheduleCache.readCurrentTerm`）真的读得回来
            val cache = KeyValueScheduleCacheStore(keyValueStore("schedule_cache${ArchiveFixture.SUFFIX}"))
            assertEquals("2026-2027-1", ScheduleCache.readCurrentTerm(cache))
        } finally {
            cleanup()
        }
    }

    // ── A6 不丢键 + 不覆盖本机 ──────────────────────────────────────────────

    @Test
    fun `A6 本机独有的键不变，档案有的写进来`() {
        cleanup()
        try {
            val settings = SettingsStore(keyValueStore(SettingsStore.APP_SETTINGS_FILE))
            settings.darkMode = "light"

            val result = WebArchiveHost.importArchive(
                archiveOf(
                    StoreSnapshot(
                        "app_settings",
                        mapOf("dark_mode" to StoreValue.S("dark"), "home_theme" to StoreValue.S("list")),
                    ),
                ),
            )

            // 两侧都有的键缺 `at` ⇒ 本端胜；只有档案有的键 ⇒ 写进来
            assertEquals(ImportResult.Ok(keysWritten = 1, keysKept = 1, filesWritten = 0), result)
            assertEquals("list", settings.homeTheme)
            assertEquals("light", settings.darkMode)
        } finally {
            cleanup()
        }
    }

    // ── A7 坏档案不动端（表驱动） ───────────────────────────────────────────

    @Test
    fun `A7 坏档案本机零改动`() {
        val cases = listOf(
            "空档案" to emptyMap(),
            "缺 manifest 但有 store" to mapOf(
                "global/stores/app_settings.json" to
                    """{"format":"xjtu-toolbox/store","version":1,"store":"app_settings","suffix":"","keys":{"dark_mode":{"t":"s","v":"dark"}}}""",
            ),
            "version 不认识" to mapOf("manifest.json" to """{"format":"xjtu-toolbox-archive","version":99}"""),
            "manifest 截断" to mapOf("manifest.json" to """{"format":"xjtu"""),
        )

        cases.forEach { (name, entries) ->
            cleanup()
            try {
                keyValueStore(SettingsStore.APP_SETTINGS_FILE).putString("dark_mode", "light")
                val before = archiveStoreBackend().storeNames().associateWith { archiveStoreBackend().readStore(it) }

                val result = WebArchiveHost.importArchive(entries)

                assertTrue(result is ImportResult.Failed, "$name：$result")
                assertEquals(
                    before,
                    archiveStoreBackend().storeNames().associateWith { archiveStoreBackend().readStore(it) },
                    "$name：本机键集合与值零变化",
                )
                assertEquals("light", keyValueStore(SettingsStore.APP_SETTINGS_FILE).getString("dark_mode"))
            } finally {
                cleanup()
            }
        }
    }

    // ── 状态文本（屏上那一行） ──────────────────────────────────────────────

    @Test
    fun `状态文本：导成功如实报几笔，files 条目如实说未落地`() {
        cleanup()
        try {
            val text = WebArchiveHost.importStatusText(
                PickedArchive.Entries("fixture.zip", archiveFromFixture()),
            )

            assertTrue(text.contains("已导入"), text)
            assertTrue(text.contains("StoreArchive.import"), text)
            assertTrue(text.contains("写入 7 个键"), text)
            // 夹具里有 2 条 `files/`（agent_sessions/index.json + courses/custom_courses.json）
            assertTrue(text.contains("2 个 files/ 条目未落地"), text)
        } finally {
            cleanup()
        }
    }

    @Test
    fun `状态文本：坏档案说不可用并明说本机未改动`() {
        cleanup()
        try {
            val text = WebArchiveHost.importStatusText(
                PickedArchive.Entries(
                    "bad.zip",
                    mapOf("manifest.json" to """{"format":"xjtu-toolbox-archive","version":99}"""),
                ),
            )

            assertTrue(text.contains("不可用"), text)
            assertTrue(text.contains("本机数据未改动"), text)
        } finally {
            cleanup()
        }
    }

    private companion object {
        /** 这些用例会碰到的 store（前后各清一次；karma 里所有用例共用同一个页面的 `localStorage`）。 */
        val TOUCHED_STORES = listOf(
            "app_settings",
            "library_favorites",
            "schedule_cache${ArchiveFixture.SUFFIX}",
        )
    }
}
