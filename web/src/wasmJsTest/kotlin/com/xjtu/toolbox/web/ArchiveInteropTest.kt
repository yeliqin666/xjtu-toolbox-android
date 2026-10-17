package com.xjtu.toolbox.web

import com.xjtu.toolbox.archive.ArchiveFile
import com.xjtu.toolbox.archive.ArchiveFixture
import com.xjtu.toolbox.archive.ArchiveImportTarget
import com.xjtu.toolbox.archive.ImportResult
import com.xjtu.toolbox.archive.StoreArchive
import com.xjtu.toolbox.archive.StoreSnapshot
import com.xjtu.toolbox.archive.StoreValue
import com.xjtu.toolbox.archive.archiveSourceOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 同一份档案夹具在本端 `import` 一次，读出的值与另外两端同一批（`docs/storage-layout.md` §7）。 */
class ArchiveInteropTest {

    /** 与 `:core` 的 `StoreArchiveImportTest.sourceOfArchive` 同参：同一份夹具、同一个 `export` 调用。 */
    private fun archiveFromFixture(): Map<String, String> = StoreArchive.export(
        ArchiveFixture.accounts,
        "android",
        "5.1.1 (471)",
        ArchiveFixture.stores,
        ArchiveFixture.files,
        ArchiveFixture.fixedClock(),
    )

    @Test
    fun `A5 同一份夹具在本端 import 一次：三个 store 的键、值与 at 加上 files 层都落到本端`() {
        val target = MemoryTarget()

        val result = StoreArchive.import(archiveSourceOf(archiveFromFixture()), target)

        assertEquals(ImportResult.Ok(keysWritten = 7, keysKept = 0, filesWritten = 2), result)

        val appSettings = assertNotNull(target.store("app_settings"), "app_settings 应当落下来了")
        assertEquals(ArchiveFixture.appSettings.keys, appSettings.keys)

        assertEquals(StoreValue.S("dark"), appSettings.keys["dark_mode"])
        assertEquals(StoreValue.B(true), appSettings.keys["show_quick_actions"])
        assertEquals(StoreValue.I(4), appSettings.keys["eula_accepted_version"])
        assertEquals(StoreValue.L(ArchiveFixture.FIXED_NOW), appSettings.keys["last_auto_update_check_at"])
        assertEquals(StoreValue.F(12.5f), appSettings.keys["card_today_dinner_cache"])

        // 账号档：端内名带后缀（§2.0），旁挂时间戳以 `at` 的形状进来（§5.2 第 5 条）
        val scheduleCache = assertNotNull(
            target.store("schedule_cache${ArchiveFixture.SUFFIX}"),
            "带后缀的账号档应当落下来了",
        )
        assertEquals(ArchiveFixture.scheduleCache.keys, scheduleCache.keys)
        assertEquals(StoreValue.S("2026-2027-1"), scheduleCache.keys["schedule_current_term"])
        assertEquals(ArchiveFixture.FIXED_NOW, scheduleCache.writtenAt["schedule_current_term"])
        assertTrue(
            scheduleCache.keys.keys.none { it.endsWith("#written_at") },
            "`keys` 里永不出现旁挂时间戳键（A10）：${scheduleCache.keys.keys}",
        )

        val favorites = assertNotNull(target.store("library_favorites"))
        assertEquals(ArchiveFixture.libraryFavorites.keys.keys, favorites.keys.keys)
        assertEquals(
            setOf("A-1", "B-2"),
            (favorites.keys["favorite_seats"] as StoreValue.SetValue).v.toSet(),
        )

        assertNull(target.store("xjtu_credentials"), "凭据族不进档案（A8），导入也不该写它")

        // files 那一层：两条都带着自己的命名空间落下来（§2.3）
        assertEquals(ArchiveFixture.files.map { it.relPath }.sorted(), target.filePaths().sorted())
    }

    @Test
    fun `A5 本端把导进来的那份再导出回去，stores 与 files 的字节与另外两端读到的逐字相同`() {
        val target = MemoryTarget()
        val archive = archiveFromFixture()
        StoreArchive.import(archiveSourceOf(archive), target)

        // 再导出一遍（`createdBy` 换成 `web`：那是"谁导的"，本来就是各端自己写的，§2.1）
        val again = StoreArchive.export(
            ArchiveFixture.accounts,
            WebArchiveHost.CREATED_BY,
            WebArchiveHost.APP_VERSION,
            target.stores(),
            target.files(),
            ArchiveFixture.fixedClock(),
        )

        // manifest 元数据各端不同 ⇒ 只比 stores/ 与 files/ 的字节；路径集合不比顺序。
        assertEquals(
            archive.keys.filterNot { it == "manifest.json" }.toSet(),
            again.keys.filterNot { it == "manifest.json" }.toSet(),
            "档案里的条目集合在本端往返之后应当逐字相同",
        )
        archive.filterKeys { it != "manifest.json" }.forEach { (path, bytes) ->
            assertEquals(bytes, again[path], "「$path」在本端往返之后应当逐字相同")
        }
    }
}

/** 内存里的「本端」：导入落点，也保有导入后的现状供断言。 */
private class MemoryTarget : ArchiveImportTarget {

    private val stores = linkedMapOf<String, StoreSnapshot>()
    private val files = linkedMapOf<Pair<String, String>, String>()

    override fun readStore(rawName: String): StoreSnapshot? = stores[rawName]

    override fun putKey(rawName: String, key: String, value: StoreValue, at: Long?) {
        val current = stores[rawName]
        stores[rawName] = StoreSnapshot(
            rawName = rawName,
            keys = (current?.keys.orEmpty()) + (key to value),
            writtenAt = when {
                at == null -> current?.writtenAt.orEmpty()
                else -> (current?.writtenAt.orEmpty()) + (key to at)
            },
        )
    }

    override fun readFile(suffix: String, relPath: String): String? = files[suffix to relPath]

    override fun putFile(suffix: String, relPath: String, content: String) {
        files[suffix to relPath] = content
    }

    /** 端上某个 store 的现状（读法同 [ArchiveImportTarget.readStore]）。 */
    fun store(rawName: String): StoreSnapshot? = readStore(rawName)

    /** 端内现状（供断言与再导出）：按端内名的码点升序。 */
    fun stores(): List<StoreSnapshot> = stores.values.sortedBy { it.rawName }
    /** 端内 `files/` 那一层（供再导出）：`(suffix, relPath)` → [ArchiveFile]。 */
    fun files(): List<ArchiveFile> = files.entries
        .map { (key, content) -> ArchiveFile(key.first, key.second, content) }
        .sortedBy { it.relPath }

    /** 端内现有文件的相对路径（断言用）。 */
    fun filePaths(): List<String> = files.keys.map { it.second }
}
