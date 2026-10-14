package com.xjtu.toolbox.archive

import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.library.LibraryFavorites
import com.xjtu.toolbox.platform.keyValueStore
import com.xjtu.toolbox.schedule.KeyValueScheduleCacheStore
import com.xjtu.toolbox.schedule.ScheduleCache
import com.xjtu.toolbox.settings.SettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S1b：**A4**（往返可读）在 jvm 上的可跑部分 —— 断言对象一律是端内**原来的读法**：
 * `SettingsStore.darkMode` / `SettingsStore.showQuickActions`、`LibraryFavorites.all()`、
 * `ScheduleCache.readCurrentTerm(cache)` / `readLastTerm(cache)`（`KeyValueScheduleCacheStore`
 * 就是桌面/web 的课表族缓存实现）。
 *
 * 三条用例各钉一件事：
 *  1. `import(export(端))` 之后，原读法读出的每条**逐字相同**，而且**一个键都不用写**
 *     （自导出那份在 §6.4 下全是「本端胜」）；
 *  2. 档案里那条比端内新时，导入**真写进端内**，原读法立刻读到档案里那条；
 *  3. 归档不覆盖本机：端内独有 / 端内更旧的键在导入后一字未变。
 *
 * ## 隔离口径
 *
 * 与 `LibraryFavoritesJvmTest` 同一条：jvm 的 `keyValueStore(name)` 是**进程内 memo**（真落盘），
 * 根由 `core/build.gradle.kts` 的 `jvmTest` 指到 build 目录（不碰 `~/.local/share/xjtu-toolbox`）；
 * 本文件用到的三个 store 名在前后各清一次，用例之间不互染。`import` 的读现状走
 * [archiveStoreBackend]（同一个数据根、逐文件读），落点由 [LocalStoreTarget] 填。
 */
class StoreArchiveImportJvmTest {

    // ── A4 ──────────────────────────────────────────────────────────────────

    @Test
    fun `A4 往返可读：import(export(端)) 之后原读法逐字相同，且一个键都不用写`() = withLocalStores {
        val settings = SettingsStore(keyValueStore(SettingsStore.APP_SETTINGS_FILE))
        settings.darkMode = "dark"
        settings.showQuickActions = false
        settings.scheduleSource = "jwxt"
        LibraryFavorites.toggle(SEAT)

        val suffix = AccountContext.suffixFor(STUDENT_ID)
        val cache = KeyValueScheduleCacheStore(keyValueStore(SCHEDULE_CACHE_STORE + suffix), nowMs = { FIXED_NOW })
        ScheduleCache.writeCurrentTerm(cache, TERM)
        ScheduleCache.writeLastTerm(cache, PREVIOUS_TERM)

        val before = readsOf(settings, cache)

        // 端内投影（S2 的 jvm 实现）：三个 store 各自的带类型键值 + 旁挂时间戳
        val backend = archiveStoreBackend()
        val stores = listOf(SettingsStore.APP_SETTINGS_FILE, LIBRARY_FAVORITES_STORE, SCHEDULE_CACHE_STORE + suffix)
            .mapNotNull { backend.readStore(it) }
        assertEquals(3, stores.size, "三个 store 都应当被投影到（本端 store：${backend.storeNames()}）")

        val archive = StoreArchive.export(
            accounts = listOf(ArchiveAccount(STUDENT_ID, suffix, "UNDERGRADUATE", FIXED_NOW)),
            createdBy = "desktop-linux",
            appVersion = "test",
            stores = stores,
            files = emptyList(),
            options = ExportOptions(now = { FIXED_NOW }),
        )

        val result = StoreArchive.import(archiveSourceOf(archive), LocalStoreTarget())
        assertTrue(result is ImportResult.Ok, "$result")
        assertEquals(0, (result as ImportResult.Ok).keysWritten, "自导出那份在 §6.4 下全是「本端胜」⇒ 一个键都不写")

        assertEquals(before, readsOf(settings, cache), "A4：原读法读出的每条逐字相同")
    }

    @Test
    fun `A4 档案更新时真写进端内：原读法立刻读到档案里那条（at 新者胜）`() = withLocalStores {
        val suffix = AccountContext.suffixFor(STUDENT_ID)
        val cache = KeyValueScheduleCacheStore(keyValueStore(SCHEDULE_CACHE_STORE + suffix), nowMs = { FIXED_NOW })
        ScheduleCache.writeCurrentTerm(cache, TERM)
        assertEquals(TERM, ScheduleCache.readCurrentTerm(cache))

        // 档案里那条比端内新 1ms；值就是端内那种形状（`ScheduleCache` 写的是 AppJson 编出来的 JSON 串）
        val archive = StoreArchive.export(
            accounts = listOf(ArchiveAccount(STUDENT_ID, suffix, "UNDERGRADUATE", FIXED_NOW)),
            createdBy = "desktop-linux",
            appVersion = "test",
            stores = listOf(
                StoreSnapshot(
                    SCHEDULE_CACHE_STORE + suffix,
                    mapOf("schedule_current_term" to StoreValue.S("\"$NEW_TERM\"")),
                    mapOf("schedule_current_term" to FIXED_NOW + 1),
                )
            ),
            files = emptyList(),
            options = ExportOptions(now = { FIXED_NOW }),
        )

        val result = StoreArchive.import(archiveSourceOf(archive), LocalStoreTarget())
        assertEquals(ImportResult.Ok(keysWritten = 1, keysKept = 0, filesWritten = 0), result)
        assertEquals(NEW_TERM, ScheduleCache.readCurrentTerm(cache), "A4：导入之后原读法读到档案里那条")
    }

    @Test
    fun `A4 不覆盖本机：端内独有与端内更旧的键导入后一字未变（A6 的 jvm 形态）`() = withLocalStores {
        val settings = SettingsStore(keyValueStore(SettingsStore.APP_SETTINGS_FILE))
        settings.darkMode = "dark"
        LibraryFavorites.toggle(SEAT)

        val suffix = AccountContext.suffixFor(STUDENT_ID)
        val cache = KeyValueScheduleCacheStore(keyValueStore(SCHEDULE_CACHE_STORE + suffix), nowMs = { FIXED_NOW })
        ScheduleCache.writeCurrentTerm(cache, TERM)

        // 档案里：app_settings 一条端内没有的键 + 课表族一条**更旧**的值（at 比端内小）
        val archive = StoreArchive.export(
            accounts = listOf(ArchiveAccount(STUDENT_ID, suffix, "UNDERGRADUATE", FIXED_NOW)),
            createdBy = "desktop-linux",
            appVersion = "test",
            stores = listOf(
                StoreSnapshot("app_settings", mapOf("home_theme" to StoreValue.S("list"))),
                StoreSnapshot(
                    SCHEDULE_CACHE_STORE + suffix,
                    mapOf("schedule_current_term" to StoreValue.S("\"$NEW_TERM\"")),
                    mapOf("schedule_current_term" to FIXED_NOW - 1),
                ),
            ),
            files = emptyList(),
            options = ExportOptions(now = { FIXED_NOW }),
        )

        val result = StoreArchive.import(archiveSourceOf(archive), LocalStoreTarget())
        assertEquals(ImportResult.Ok(keysWritten = 1, keysKept = 1, filesWritten = 0), result)

        // 档案有、端内没有 ⇒ 写入（走原读法读得回来）
        assertEquals("list", settings.homeTheme)
        // 端内更旧那条：本端胜，一个字都不动
        assertEquals(TERM, ScheduleCache.readCurrentTerm(cache))
        assertEquals("dark", settings.darkMode)
        assertEquals(setOf(SEAT), LibraryFavorites.all())
    }

    // ── 夹具 ────────────────────────────────────────────────────────────────

    /** 端内**原来的读法**读一遍（A4 的断言对象）。 */
    private fun readsOf(settings: SettingsStore, cache: KeyValueScheduleCacheStore): List<Pair<String, String>> = listOf(
        "darkMode" to settings.darkMode,
        "showQuickActions" to settings.showQuickActions.toString(),
        "scheduleSource" to settings.scheduleSource,
        "libraryFavorites" to LibraryFavorites.all().sorted().toString(),
        "currentTerm" to ScheduleCache.readCurrentTerm(cache).toString(),
        "lastTerm" to ScheduleCache.readLastTerm(cache).toString(),
    )

    /** 本文件碰到的三个 store（第三个带账号后缀）。 */
    private fun withLocalStores(block: () -> Unit) {
        clearLocalStores()
        try {
            block()
        } finally {
            clearLocalStores()
        }
    }

    /** 与 `LibraryFavoritesJvmTest` 同一条写法：jvm 的键值缝是进程内 memo，用例前后各清一次。 */
    private fun clearLocalStores() {
        keyValueStore(SettingsStore.APP_SETTINGS_FILE).clear()
        keyValueStore(LIBRARY_FAVORITES_STORE).clear()
        keyValueStore(SCHEDULE_CACHE_STORE + AccountContext.suffixFor(STUDENT_ID)).clear()
    }

    private companion object {
        const val STUDENT_ID = "2253415556"
        const val TERM = "2026-2027-1"
        const val PREVIOUS_TERM = "2025-2026-2"
        const val NEW_TERM = "2027-2028-1"
        const val SEAT = "A-101"

        /** `LibraryFavorites` 的存储名（它自己没把常量露出来，值的真源是它的 KDoc）。 */
        const val LIBRARY_FAVORITES_STORE = "library_favorites"

        /** 课表族缓存的 store 名（`DesktopScheduleHost.SCHEDULE_CACHE_STORE` 同一个字面）。 */
        const val SCHEDULE_CACHE_STORE = "schedule_cache"


        const val FIXED_NOW = 1_760_000_000_000L
    }
}

/**
 * 端内写入缝（`ArchiveImportTarget`）在 jvm 上的落点。
 *
 * 本文件里那三个 store 都走**普通**键值缝（`:core:jvmTest` 够不着 `:data` 的密文缝），
 * 读现状交给 [archiveStoreBackend]（同一个数据根、逐文件读）。
 */
private class LocalStoreTarget : ArchiveImportTarget {

    override fun readStore(rawName: String): StoreSnapshot? = archiveStoreBackend().readStore(rawName)

    override fun putKey(rawName: String, key: String, value: StoreValue, at: Long?) {
        val store = keyValueStore(rawName)
        // 键值缝只有 String / Boolean / Int / Set 四档（见它的 KDoc）：`l`/`f` 按 Properties 那一套
        // 写字符串，读回来由 §6.3 的反推规则还原成带标签的值。
        when (value) {
            is StoreValue.S -> store.putString(key, value.v)
            is StoreValue.B -> store.putBoolean(key, value.v)
            is StoreValue.I -> store.putInt(key, value.v)
            is StoreValue.L -> store.putString(key, value.v.toString())
            is StoreValue.F -> store.putString(key, value.v.toString())
            is StoreValue.SetValue -> store.putStringSet(key, value.v.toSet())
        }
        // 课表族的 TTL 靠 `<key>#written_at`（§5.2 第 5 条）⇒ 档案带了 `at` 就把它重建回去
        if (at != null) store.putString(key + ArchiveCodec.WRITTEN_AT_SUFFIX, at.toString())
    }

    override fun readFile(suffix: String, relPath: String): String? = archiveFileBackend()?.read(suffix, relPath)

    override fun putFile(suffix: String, relPath: String, content: String) {
        error("A4 这几条用例的档案里没有 files 层（都是 export 现编、files 传空表）")
    }
}

