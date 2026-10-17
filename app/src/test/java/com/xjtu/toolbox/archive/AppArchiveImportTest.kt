package com.xjtu.toolbox.archive

import android.content.SharedPreferences
import com.xjtu.toolbox.data.InMemorySharedPreferences
import com.xjtu.toolbox.platform.KeyValueStore
import com.xjtu.toolbox.settings.SettingsStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 导入写侧接到 `:core` 的 [StoreArchive.import]（§6.4）后 `:app` 侧该成立的几条。 */
class AppArchiveImportTest {


    @Test
    fun `A4 导入之后原来的读法读得到档案里那条`() {
        val source = Prefs()
        SettingsStore(PrefsKeyValueStore(source.of("app_settings"))).apply {
            darkMode = "dark"
            showQuickActions = false
            scheduleSource = "jwxt"
        }
        val entries = archiveOf(source.backend.readStore("app_settings")!!)

        val local = Prefs()
        val outcome = importInto(local, entries)

        assertTrue("$outcome", outcome is ArchiveImportOutcome.Imported)
        val settings = SettingsStore(PrefsKeyValueStore(local.of("app_settings")))
        assertEquals("dark", settings.darkMode)
        assertFalse(settings.showQuickActions)
        assertEquals("jwxt", settings.scheduleSource)
    }

    @Test
    fun `A4 set 值按 putStringSet 落地 读回来还是集合`() {
        val entries = archiveOf(
            StoreSnapshot("library_favorites", mapOf("favorite_seats" to StoreValue.SetValue(listOf("A-101", "B-2")))),
        )
        val local = Prefs()
        val outcome = importInto(local, entries)

        assertEquals(ImportResult.Ok(keysWritten = 1, keysKept = 0, filesWritten = 0), (outcome as ArchiveImportOutcome.Imported).result)
        // §8.4：写成逗号串的话这一句就 ClassCastException 了（`getStringSet` 的属性就是「它必须真是集合」）
        assertEquals(setOf("A-101", "B-2"), local.of("library_favorites").getStringSet("favorite_seats", null))
    }


    @Test
    fun `A6 端内独有的键不变 档案有的写进来 缺 at 时本端胜`() {
        val local = Prefs()
        local.of("app_settings").edit()
            .putString("dark_mode", "light")
            .putInt("eula_accepted_version", 4)
            .commit()
        // 档案里：一条端内没有的键（写进来）+ 一条与端内不同但**没有 at** 的键（本端胜）
        val entries = archiveOf(
            StoreSnapshot("app_settings", mapOf("home_theme" to StoreValue.S("list"), "dark_mode" to StoreValue.S("dark"))),
        )

        val outcome = importInto(local, entries)

        assertEquals(ImportResult.Ok(keysWritten = 1, keysKept = 1, filesWritten = 0), (outcome as ArchiveImportOutcome.Imported).result)
        assertEquals("list", local.of("app_settings").getString("home_theme", null))
        assertEquals("light", local.of("app_settings").getString("dark_mode", null))
        assertEquals(4, local.of("app_settings").getInt("eula_accepted_version", 0))
    }

    @Test
    fun `Android 侧已有的键一律本端胜（端内没有 writtenAt）`() {
        val local = Prefs()
        local.of("schedule_cache_2253415556").edit().putString("schedule_current_term", "2026-2027-1").commit()
        val entries = archiveOf(
            StoreSnapshot(
                "schedule_cache_2253415556",
                mapOf("schedule_current_term" to StoreValue.S("2027-2028-1")),
                mapOf("schedule_current_term" to FIXED_NOW + 1),
            ),
        )

        val outcome = importInto(local, entries)

        assertEquals(ImportResult.Ok(keysWritten = 0, keysKept = 1, filesWritten = 0), (outcome as ArchiveImportOutcome.Imported).result)
        assertEquals("2026-2027-1", local.of("schedule_cache_2253415556").getString("schedule_current_term", null))
    }


    @Test
    fun `A7 坏档案端内零改动且不落已导入标记`() {
        val cases = listOf(
            "空目录" to emptyMap(),
            "缺 manifest 但有 store" to mapOf(
                "global/stores/app_settings.json" to
                    """{"format":"xjtu-toolbox/store","version":1,"store":"app_settings","suffix":"","keys":{"dark_mode":{"t":"s","v":"dark"}}}""",
            ),
            "version 不认识" to mapOf("manifest.json" to """{"format":"xjtu-toolbox-archive","version":99}"""),
            "manifest 截断" to mapOf("manifest.json" to """{"format":"xjtu"""),
            "store 文件截断" to mapOf(
                "manifest.json" to MINIMAL_MANIFEST,
                "global/stores/app_settings.json" to """{"format":"xjtu-toolbox""",
            ),
            "行数组解不开" to mapOf(
                "manifest.json" to MINIMAL_MANIFEST,
                "global/stores/app_settings.json" to
                    """{"format":"xjtu-toolbox/store","version":1,"store":"app_settings","suffix":"","keys":{"dark_mode":{"t":"s","v":"dark"}}}""",
                "global/files/courses/custom_courses.json" to "{ 这不是行数组",
            ),
        )

        cases.forEach { (name, entries) ->
            val local = Prefs()
            local.of("app_settings").edit().putString("dark_mode", "light").putInt("eula_accepted_version", 4).commit()
            val before = local.snapshot()

            val root = newTempDir("xjtu-bad")
            try {
                writeEntries(root, entries)
                val outcome = importInto(local, AppArchiveUx.readArchive(root).orEmpty(), root)

                assertTrue("$name：$outcome", outcome is ArchiveImportOutcome.Failed)
                assertEquals("$name：端内键集合与值零变化", before, local.snapshot())
                assertFalse("$name：失败不落标记", AppArchiveImport.markerFileFor(root).exists())
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun `A7 行数组预检在写第一笔之前就停住`() {
        val local = Prefs()
        val before = local.snapshot()
        val entries = archiveOf(
            StoreSnapshot("app_settings", mapOf("home_theme" to StoreValue.S("list"))),
            files = listOf(ArchiveFile("", RoomArchiveFamilies.JIAOCAI_SHELF_REL, "{ 坏的行数组")),
        )
        val root = newTempDir("xjtu-precheck")
        try {
            writeEntries(root, entries)
            val outcome = importInto(local, AppArchiveUx.readArchive(root).orEmpty(), root)

            assertTrue("$outcome", outcome is ArchiveImportOutcome.Failed)
            // 预检排在 §6.4 合并之前 ⇒ 连那条「端内没有的键」都没被写进来
            assertEquals(before, local.snapshot())
            assertNull(local.of("app_settings").getString("home_theme", null))
        } finally {
            root.deleteRecursively()
        }
    }

    // ── 「已导入」标记 + 幂等 ───────────────────────────────────────────────

    @Test
    fun `已导入标记写在档案落地目录里 身份一致则再导一次只跳过`() {
        val local = Prefs()
        val entries = archiveOf(
            StoreSnapshot("library_favorites", mapOf("favorite_seats" to StoreValue.SetValue(listOf("A-101")))),
        )
        val root = newTempDir("xjtu-archive")
        try {
            writeEntries(root, entries)
            val first = importInto(local, AppArchiveUx.readArchive(root).orEmpty(), root)
            assertTrue("$first", first is ArchiveImportOutcome.Imported)

            val marker = File(root, AppArchiveImport.MARKER_NAME)
            assertTrue("标记落在档案目录里：${marker.path}", marker.isFile)
            val identity = AppArchiveImport.identityOf(ArchiveCodec.decodeManifest(entries.getValue("manifest.json")))
            assertEquals(identity, AppArchiveImport.readMarkerIdentity(marker))
            // 标记不当成档案条目：读档案时被跳过（点开头）
            assertFalse(AppArchiveUx.readArchive(root).orEmpty().containsKey(AppArchiveImport.MARKER_NAME))

            // 端内先被改掉，再导一次同一份档案：跳过（不重写、标记也不变）
            local.of("library_favorites").edit().putStringSet("favorite_seats", setOf("Z-9")).commit()
            val second = importInto(local, AppArchiveUx.readArchive(root).orEmpty(), root)

            assertTrue("$second", second is ArchiveImportOutcome.AlreadyImported)
            assertEquals(setOf("Z-9"), local.of("library_favorites").getStringSet("favorite_seats", null))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `zip 形态的标记写在 zip 旁边`() {
        val local = Prefs()
        val entries = archiveOf(StoreSnapshot("app_settings", mapOf("home_theme" to StoreValue.S("list"))))
        val root = newTempDir("xjtu-zip")
        try {
            val zip = File(root, "xjtu-toolbox-archive.zip")
            zip.writeBytes(AppArchiveUx.zipArchive(entries))
            assertEquals(entries, AppArchiveUx.readArchive(zip))

            val outcome = importInto(local, AppArchiveUx.readArchive(zip).orEmpty(), zip)

            assertTrue("$outcome", outcome is ArchiveImportOutcome.Imported)
            assertTrue(File(root, "xjtu-toolbox-archive.zip.imported").isFile)
            assertEquals("list", local.of("app_settings").getString("home_theme", null))
        } finally {
            root.deleteRecursively()
        }
    }

    // ── files 层：Room 三表的行数组 ─────────────────────────────────────────

    @Test
    fun `Room 行数组真写回 且再导出时六键升序稳定`() {
        // 档案里的行数组故意是乱的（id 3 / 1 / 2），写回之后「再导出一次」必须是 §2.3 的排序
        val rows = """
            [{"id":3,"accountId":"B","courseName":"c","weekBits":"1","dayOfWeek":5,"startSection":3,"endSection":4,"termCode":"2026-2027-1"},
             {"id":1,"accountId":"A","courseName":"a","weekBits":"1","dayOfWeek":1,"startSection":1,"endSection":2,"termCode":"2026-2027-1"},
             {"id":2,"accountId":"A","courseName":"b","weekBits":"1","dayOfWeek":1,"startSection":2,"endSection":3,"termCode":"2026-2027-1"}]
        """.trimIndent()
        val room = RecordingRoomRows()
        val entries = archiveOf(files = listOf(ArchiveFile("", RoomArchiveFamilies.CUSTOM_COURSES_REL, rows)))

        val outcome = importInto(Prefs(), entries, files = AndroidArchiveFileWriter(newTempDir("xjtu-f"), null, room))

        assertEquals(ImportResult.Ok(keysWritten = 0, keysKept = 0, filesWritten = 1), (outcome as ArchiveImportOutcome.Imported).result)
        val written = room.written.getValue(RoomArchiveFamilies.CUSTOM_COURSES_REL)
        assertEquals(listOf(3L, 1L, 2L), RoomArchiveFamilies.customCoursesFrom(written).map { it.id })
        val reexported = RoomArchiveFamilies.customCoursesFile(RoomArchiveFamilies.customCoursesFrom(written)).content
        assertEquals(reexported, RoomArchiveFamilies.customCoursesFile(RoomArchiveFamilies.customCoursesFrom(reexported)).content)
        assertEquals(listOf("1", "2", "3"), idsOf(reexported))
    }

    @Test
    fun `本端已有那一族行数组时不动它`() {
        val room = RecordingRoomRows(
            local = mutableMapOf(RoomArchiveFamilies.DOWNLOAD_TASKS_REL to """[{"id":9,"status":"completed"}]"""),
        )
        val entries = archiveOf(files = listOf(ArchiveFile("", RoomArchiveFamilies.DOWNLOAD_TASKS_REL, """[{"id":1}]""")))

        val outcome = importInto(Prefs(), entries, files = AndroidArchiveFileWriter(newTempDir("xjtu-f"), null, room))

        assertEquals(ImportResult.Ok(keysWritten = 0, keysKept = 0, filesWritten = 0), (outcome as ArchiveImportOutcome.Imported).result)
        assertTrue("本端那一族没被覆盖", room.written.isEmpty())
    }

    // ── files 层：两族真文件 ────────────────────────────────────────────────

    @Test
    fun `agent_sessions 与 schedule 两族真文件写回且不覆盖本端已有`() {
        val filesRoot = newTempDir("xjtu-files")
        val cacheRoot = newTempDir("xjtu-cache")
        try {
            val existing = File(filesRoot, "agent_sessions_2253415556/index.json")
            existing.parentFile!!.mkdirs()
            existing.writeText("""[{"id":"local"}]""")

            val writer = AndroidArchiveFileWriter(filesRoot, cacheRoot, RecordingRoomRows())
            val entries = archiveOf(
                files = listOf(
                    ArchiveFile("_2253415556", "agent_sessions/index.json", """[{"id":"archive"}]"""),
                    ArchiveFile("_2253415556", "agent_sessions/convo_1.json", """{"id":"1"}"""),
                    ArchiveFile("", "schedule/schedule_current_term.json", "\"2026-2027-1\""),
                ),
            )

            val outcome = importInto(Prefs(), entries, files = writer)

            assertEquals(ImportResult.Ok(keysWritten = 0, keysKept = 0, filesWritten = 2), (outcome as ArchiveImportOutcome.Imported).result)
            assertEquals("本端已有 ⇒ 保持不变", """[{"id":"local"}]""", existing.readText())
            assertEquals("""{"id":"1"}""", File(filesRoot, "agent_sessions_2253415556/convo_1.json").readText())
            assertEquals("\"2026-2027-1\"", File(cacheRoot, "data_cache/schedule_current_term.json").readText())
            // §8.8：原子写的临时文件不留残片
            assertFalse(File(filesRoot, "agent_sessions_2253415556/convo_1.json.tmp").exists())
            assertFalse(File(cacheRoot, "data_cache/schedule_current_term.json.tmp").exists())
        } finally {
            filesRoot.deleteRecursively()
            cacheRoot.deleteRecursively()
        }
    }

    // ── 通道发现（§6.2 的三种落地形态） ─────────────────────────────────────

    @Test
    fun `待导入档案的三种形态都认得出来`() {
        val outer = newTempDir("xjtu-roots")
        val empty = newTempDir("xjtu-empty")
        try {
            val self = File(outer, "self").apply { mkdirs() }
            File(self, "manifest.json").writeText(MINIMAL_MANIFEST)
            val zipRoot = File(outer, "zip").apply { mkdirs() }
            File(zipRoot, "b.zip").writeBytes(AppArchiveUx.zipArchive(mapOf("manifest.json" to MINIMAL_MANIFEST)))
            val subRoot = File(outer, "sub").apply { mkdirs() }
            File(subRoot, "dropped").apply { mkdirs() }
            File(File(subRoot, "dropped"), "manifest.json").writeText(MINIMAL_MANIFEST)

            assertEquals(listOf(self), AppArchiveUx.pendingArchiveCandidates(listOf(self)))
            assertEquals(listOf(File(zipRoot, "b.zip")), AppArchiveUx.pendingArchiveCandidates(listOf(zipRoot)))
            assertEquals(listOf(File(subRoot, "dropped")), AppArchiveUx.pendingArchiveCandidates(listOf(subRoot)))
            assertEquals(emptyList<File>(), AppArchiveUx.pendingArchiveCandidates(listOf(empty)))
            // 根与根的次序按调用方给的那一份
            assertEquals(
                listOf(self, File(zipRoot, "b.zip")),
                AppArchiveUx.pendingArchiveCandidates(listOf(self, zipRoot)),
            )
        } finally {
            outer.deleteRecursively()
            empty.deleteRecursively()
        }
    }

    // ── 夹具 ────────────────────────────────────────────────────────────────

    /** 一份真的档案（走 [StoreArchive.export]，注入固定时钟 ⇒ 逐字节可复现）。 */
    private fun archiveOf(
        vararg stores: StoreSnapshot,
        files: List<ArchiveFile> = emptyList(),
    ): Map<String, String> = StoreArchive.export(
        accounts = emptyList(),
        createdBy = "android-test",
        appVersion = "test",
        stores = stores.toList(),
        files = files,
        options = ExportOptions(now = { FIXED_NOW }),
    )

    /** 自校验 → 行数组预检 → §6.4 合并 → 标记。 */
    private fun importInto(
        prefs: Prefs,
        entries: Map<String, String>,
        archive: File = newTempDir("xjtu-archive-target"),
        files: AppArchiveFileWriter? = null,
    ): ArchiveImportOutcome =
        AppArchiveImport.run(archive, entries) {
            AppArchiveImportTarget(prefsOf = { name -> prefs.of(name) }, storeBackend = prefs.backend, files = files)
        }

    private fun writeEntries(root: File, entries: Map<String, String>) {
        entries.forEach { (rel, content) ->
            val file = File(root, rel)
            file.parentFile?.mkdirs()
            file.writeText(content)
        }
    }

    private fun idsOf(rowsJson: String): List<String> =
        RoomArchiveFamilies.customCoursesFrom(rowsJson).map { it.id.toString() }

    private fun newTempDir(prefix: String): File {
        val file = File.createTempFile(prefix, "")
        file.delete()
        file.mkdirs()
        return file
    }

    private companion object {
        const val FIXED_NOW = 1_760_000_000_000L

        /** 最小合法 `manifest.json`（`:core` 只要求 `format` + 认识的 `version`）。 */
        const val MINIMAL_MANIFEST = """{"format":"xjtu-toolbox-archive","version":1}"""
    }
}

/** 端内的那几个 store（按名字各一份 `InMemorySharedPreferences`，:app 单测里没有真 `Context`）。 */
private class Prefs {

    private val stores = mutableMapOf<String, InMemorySharedPreferences>()

    fun of(name: String): InMemorySharedPreferences = stores.getOrPut(name) { InMemorySharedPreferences() }

    /** 读侧现状：与宿主同一个 [AndroidPrefsBackend]（`prefs.all` ⇒ 带类型的键值）。 */
    val backend: ArchiveStoreBackend
        get() = AndroidPrefsBackend(prefsOf = ::of, namesOf = { stores.keys.sorted() })

    /** 端内现状的快照。 */
    fun snapshot(): Map<String, Map<String, Any?>> =
        stores.mapValues { (_, prefs) -> prefs.all.mapValues { (_, value) -> value } }
}

/** Room 三表那一半的记账实现（真 DAO 要真 SQLite，`:app:testDebugUnitTest` 里没有） */
private class RecordingRoomRows(private val local: MutableMap<String, String> = mutableMapOf()) : RoomRowWriter {

    val written = mutableMapOf<String, String>()

    override fun read(relPath: String): String? = local[relPath]

    override fun write(relPath: String, content: String) {
        written[relPath] = content
    }
}

/** `:app` 单测没有真 `Context`，把 `AndroidKeyValueStore` 的同一串调用架在 `InMemorySharedPreferences` 上。 */
private class PrefsKeyValueStore(private val prefs: SharedPreferences) : KeyValueStore {

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String?) {
        if (value == null) prefs.edit().remove(key).commit() else prefs.edit().putString(key, value).commit()
    }

    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)

    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).commit()
    }

    override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)

    override fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).commit()
    }

    override fun getStringSet(key: String): Set<String>? = prefs.getStringSet(key, null)?.toSet()

    override fun putStringSet(key: String, value: Set<String>) {
        prefs.edit().putStringSet(key, LinkedHashSet(value)).commit()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).commit()
    }

    override fun contains(key: String): Boolean = prefs.contains(key)

    override fun clear() {
        prefs.edit().clear().commit()
    }
}
