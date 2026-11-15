package com.xjtu.toolbox.desktop

import com.xjtu.toolbox.archive.ArchiveCodec
import com.xjtu.toolbox.archive.ArchiveFile
import com.xjtu.toolbox.archive.ImportResult
import com.xjtu.toolbox.archive.StoreSnapshot
import com.xjtu.toolbox.archive.StoreValue
import com.xjtu.toolbox.archive.archiveStoreBackend
import com.xjtu.toolbox.desktop.archive.DesktopArchiveHost
import com.xjtu.toolbox.desktop.archive.DesktopArchiveImport
import com.xjtu.toolbox.desktop.archive.DesktopArchiveIo
import com.xjtu.toolbox.desktop.archive.DesktopImportOutcome
import com.xjtu.toolbox.platform.dataRootOverride
import com.xjtu.toolbox.platform.keyValueStoreRootOverride
import com.xjtu.toolbox.platform.secureKeyValueStore
import com.xjtu.toolbox.platform.wipeSecureStore
import com.xjtu.toolbox.schedule.KeyValueScheduleCacheStore
import com.xjtu.toolbox.schedule.ScheduleCache
import com.xjtu.toolbox.settings.SettingsStore
import java.io.File
import java.nio.file.Files
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 桌面端导入写侧接到 `StoreArchive.import`（§6.4）后该成立的几条。 */
class DesktopArchiveImportJvmTest {

    private fun withRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("xjtu-desktop-import").toFile()
        dataRootOverride = root
        keyValueStoreRootOverride = root
        try {
            block(root)
        } finally {
            dataRootOverride = null
            keyValueStoreRootOverride = null
        }
    }

    /** 一份真的档案（走 `StoreArchive.export`，注入固定时钟 ⇒ 逐字节可复现）。 */
    private fun archiveOf(
        stores: List<StoreSnapshot>,
        files: List<ArchiveFile> = emptyList(),
    ): Map<String, String> = DesktopArchiveHost.buildArchive(emptyList(), stores, files, now = { FIXED_NOW })

    /** 把档案字节映射落成一个**档案目录**（§2.0 的目录形态），返回那个目录。 */
    private fun dropArchiveDir(root: File, entries: Map<String, String>): File =
        File(root, DesktopArchiveHost.ARCHIVE_DIR).also { DesktopArchiveIo.writeDirectory(entries, it) }

    /** 端内现状快照。 */
    private fun storesNow(): Map<String, StoreSnapshot?> {
        val backend = archiveStoreBackend()
        return backend.storeNames().associateWith { backend.readStore(it) }
    }

    /** 数据根下一份 `.properties` 的解析后键值（不比字节）。 */
    private fun props(root: File, name: String): Properties =
        Properties().apply { File(root, "$name.properties").inputStream().use { load(it) } }


    @Test
    fun `A4 导入之后原来的读法读得到档案里那条`() = withRoot { root ->
        wipeSecureStore(SettingsStore.APP_SETTINGS_FILE)
        val entries = archiveOf(
            listOf(
                StoreSnapshot(
                    "app_settings",
                    mapOf("dark_mode" to StoreValue.S("dark"), "default_tab" to StoreValue.S("HOME")),
                ),
            ),
        )

        val outcome = DesktopArchiveImport.run(dropArchiveDir(root, entries), entries)

        assertTrue(outcome is DesktopImportOutcome.Imported, "$outcome")
        val settings = SettingsStore(secureKeyValueStore(SettingsStore.APP_SETTINGS_FILE))
        assertEquals("dark", settings.darkMode)
        assertEquals("HOME", settings.defaultTab)
        assertEquals("dark", props(root, SettingsStore.APP_SETTINGS_FILE).getProperty("dark_mode"))
        wipeSecureStore(SettingsStore.APP_SETTINGS_FILE)
    }

    @Test
    fun `A4 课表族往返：值读得回来，at 落在旁挂键上`() = withRoot { root ->
        val storeName = "schedule_cache_2253415556"
        wipeSecureStore(storeName)
        val entries = archiveOf(
            listOf(
                StoreSnapshot(
                    storeName,
                    mapOf("schedule_current_term" to StoreValue.S("\"2026-2027-1\"")),
                    mapOf("schedule_current_term" to FIXED_NOW),
                ),
            ),
        )

        val outcome = DesktopArchiveImport.run(dropArchiveDir(root, entries), entries)

        assertTrue(outcome is DesktopImportOutcome.Imported, "$outcome")
        val cache = KeyValueScheduleCacheStore(secureKeyValueStore(storeName), nowMs = { FIXED_NOW })
        assertEquals("2026-2027-1", ScheduleCache.readCurrentTerm(cache))
        // §5.2 第 5 条：档案里的 `at` 落回旁挂键（下一次导入才判得出新旧）
        assertEquals(
            FIXED_NOW.toString(),
            props(root, storeName).getProperty("schedule_current_term${ArchiveCodec.WRITTEN_AT_SUFFIX}"),
        )
        assertEquals(FIXED_NOW, archiveStoreBackend().readStore(storeName)!!.writtenAt["schedule_current_term"])
        wipeSecureStore(storeName)
    }

    // ── §6.4 的裁决（桌面有 `at`，所以「新者胜」在这里真的生效） ─────────────

    @Test
    fun `A6 档案更新时真写进端内（at 新者胜）`() = withRoot { root ->
        val storeName = "schedule_cache_2253415556"
        wipeSecureStore(storeName)
        val cache = KeyValueScheduleCacheStore(secureKeyValueStore(storeName), nowMs = { FIXED_NOW })
        ScheduleCache.writeCurrentTerm(cache, "2026-2027-1")

        val newer = archiveOf(
            listOf(
                StoreSnapshot(
                    storeName,
                    mapOf("schedule_current_term" to StoreValue.S("\"2027-2028-1\"")),
                    mapOf("schedule_current_term" to FIXED_NOW + 1),
                ),
            ),
        )
        val outcome = DesktopArchiveImport.run(dropArchiveDir(root, newer), newer)

        assertEquals(
            ImportResult.Ok(keysWritten = 1, keysKept = 0, filesWritten = 0),
            (outcome as DesktopImportOutcome.Imported).result,
        )
        assertEquals("2027-2028-1", ScheduleCache.readCurrentTerm(cache))
        wipeSecureStore(storeName)
    }

    @Test
    fun `A6 档案更旧时本端胜，端内独有的键一字未变`() = withRoot { root ->
        val storeName = "schedule_cache_2253415556"
        wipeSecureStore(storeName)
        wipeSecureStore("app_settings")
        val cache = KeyValueScheduleCacheStore(secureKeyValueStore(storeName), nowMs = { FIXED_NOW })
        ScheduleCache.writeCurrentTerm(cache, "2026-2027-1")
        val settings = SettingsStore(secureKeyValueStore(SettingsStore.APP_SETTINGS_FILE))
        settings.darkMode = "light"
        val before = storesNow()

        val older = archiveOf(
            listOf(
                StoreSnapshot(
                    storeName,
                    mapOf("schedule_current_term" to StoreValue.S("\"2025-2026-2\"")),
                    mapOf("schedule_current_term" to FIXED_NOW - 1),
                ),
                StoreSnapshot(
                    "library_favorites",
                    mapOf("favorite_seats" to StoreValue.SetValue(listOf("A-101", "B-2"))),
                ),
            ),
        )
        val outcome = DesktopArchiveImport.run(dropArchiveDir(root, older), older)

        assertEquals(
            ImportResult.Ok(keysWritten = 1, keysKept = 1, filesWritten = 0),
            (outcome as DesktopImportOutcome.Imported).result,
        )
        assertEquals("2026-2027-1", ScheduleCache.readCurrentTerm(cache), "端内更旧那条没被覆盖")
        assertEquals("light", settings.darkMode, "端内独有的键一字未变")
        // §8.4：`set` 值按元素集合读回来（写出去的那一份不是逗号串）
        assertEquals(
            setOf("A-101", "B-2"),
            (archiveStoreBackend().readStore("library_favorites")!!.keys.getValue("favorite_seats") as StoreValue.SetValue).v.toSet(),
        )
        assertEquals(storesNow().keys, before.keys + setOf("library_favorites"), "只多了那一族")
        wipeSecureStore(storeName)
        wipeSecureStore("app_settings")
        wipeSecureStore("library_favorites")
    }


    @Test
    fun `A7 坏档案端内零改动且不落已导入标记`() = withRoot { root ->
        val cases = listOf(
            "空目录" to emptyMap(),
            "缺 manifest 但有 store" to mapOf(
                "global/stores/app_settings.json" to
                    """{"format":"xjtu-toolbox/store","version":1,"store":"app_settings","suffix":"","keys":{"dark_mode":{"t":"s","v":"dark"}}}""",
            ),
            "version 不认识" to mapOf("manifest.json" to """{"format":"xjtu-toolbox-archive","version":99}"""),
            "manifest 截断" to mapOf("manifest.json" to """{"format":"xjtu"""),
        )

        cases.forEach { (name, entries) ->
            wipeSecureStore("app_settings")
            val store = secureKeyValueStore("app_settings")
            store.putString("dark_mode", "light")
            store.putInt("eula_accepted_version", 4)
            val before = storesNow()

            val dir = File(root, "bad-${name.hashCode()}")
            dir.mkdirs()
            writeEntries(dir, entries)

            val outcome = DesktopArchiveImport.run(dir, DesktopArchiveIo.read(dir))

            assertTrue(outcome is DesktopImportOutcome.Failed, "$name：$outcome")
            assertEquals(before, storesNow(), "$name：端内键集合与值零变化")
            assertFalse(DesktopArchiveImport.markerFileFor(dir).exists(), "$name：失败不落标记")
            dir.deleteRecursively()
        }
        wipeSecureStore("app_settings")
    }

    // ── 「已导入」标记 + 幂等 ───────────────────────────────────────────────

    @Test
    fun `已导入标记写在档案落地目录里 身份一致则再导一次只跳过`() = withRoot { root ->
        wipeSecureStore("app_settings")
        val entries = archiveOf(listOf(StoreSnapshot("app_settings", mapOf("default_tab" to StoreValue.S("HOME")))))
        val dir = dropArchiveDir(root, entries)

        val first = DesktopArchiveImport.run(dir, DesktopArchiveIo.read(dir))
        assertTrue(first is DesktopImportOutcome.Imported, "$first")

        val marker = File(dir, DesktopArchiveImport.MARKER_NAME)
        assertTrue(marker.isFile, "标记落在档案目录里：${marker.path}")
        val identity = DesktopArchiveImport.identityOf(ArchiveCodec.decodeManifest(entries.getValue("manifest.json")))
        assertEquals(identity, DesktopArchiveImport.readMarkerIdentity(marker))
        // 标记不当成档案条目（读档案时跳过点开头）
        assertFalse(DesktopArchiveIo.read(dir).containsKey(DesktopArchiveImport.MARKER_NAME))

        // 端内先改掉，再导一次同一份档案 ⇒ 跳过（不重写）
        SettingsStore(secureKeyValueStore(SettingsStore.APP_SETTINGS_FILE)).defaultTab = "PROFILE"
        val second = DesktopArchiveImport.run(dir, DesktopArchiveIo.read(dir))

        assertTrue(second is DesktopImportOutcome.AlreadyImported, "$second")
        assertEquals("PROFILE", SettingsStore(secureKeyValueStore(SettingsStore.APP_SETTINGS_FILE)).defaultTab)

        // 扫数据根时，只剩标记的目录不算「一份待导入的档案」
        assertEquals(listOf(dir), DesktopArchiveHost.pendingArchives())
        wipeSecureStore(SettingsStore.APP_SETTINGS_FILE)
    }

    // ── `files/` 层：agent_sessions 这一族 ──────────────────────────────────

    @Test
    fun `agent_sessions 文件族写回且不覆盖端内已有`() = withRoot { root ->
        val suffix = "_2253415556"
        val existingDir = File(root, "agent_sessions$suffix").apply { mkdirs() }
        val existing = File(existingDir, "index.json").apply { writeText("""[{"id":"local"}]""") }

        val entries = archiveOf(
            stores = emptyList(),
            files = listOf(
                ArchiveFile(suffix, "agent_sessions/index.json", """[{"id":"archive"}]"""),
                ArchiveFile(suffix, "agent_sessions/convo_1.json", """{"id":"1"}"""),
            ),
        )
        val outcome = DesktopArchiveImport.run(dropArchiveDir(root, entries), entries)

        assertEquals(
            ImportResult.Ok(keysWritten = 0, keysKept = 0, filesWritten = 1),
            (outcome as DesktopImportOutcome.Imported).result,
        )
        assertEquals("""[{"id":"local"}]""", existing.readText(), "端内已有 ⇒ 保持不变（§6.4 文件层）")
        assertEquals("""{"id":"1"}""", File(existingDir, "convo_1.json").readText())
        assertFalse(File(existingDir, "convo_1.json.tmp").exists(), "§8.8：不留 .tmp 残片")
    }

    @Test
    fun `课表缓存族（B-9）：files schedule 并回 schedule_cache 店，端内已有则不动`() = withRoot { root ->
        val suffix = "_2253415556"
        val storeName = DesktopScheduleHost.SCHEDULE_CACHE_STORE + suffix
        wipeSecureStore(storeName)
        val cache = KeyValueScheduleCacheStore(secureKeyValueStore(storeName), nowMs = { FIXED_NOW })
        ScheduleCache.writeCurrentTerm(cache, "2026-2027-1")

        val entries = archiveOf(
            stores = emptyList(),
            files = listOf(
                ArchiveFile(suffix, "schedule/schedule_current_term.json", "\"2027-2028-1\""),
                ArchiveFile(suffix, "schedule/exams_2026-2027-1.json", """[{"name":"高等数学"}]"""),
            ),
        )
        val outcome = DesktopArchiveImport.run(dropArchiveDir(root, entries), entries)

        // 第一条端内已有 ⇒ 「保持不变」不算写；第二条才落 ⇒ filesWritten = 1、没有未落地的
        assertEquals(
            ImportResult.Ok(keysWritten = 0, keysKept = 0, filesWritten = 1),
            (outcome as DesktopImportOutcome.Imported).result,
        )
        assertEquals("2026-2027-1", ScheduleCache.readCurrentTerm(cache), "端内已有 ⇒ 保持不变（§6.4 文件层）")
        assertEquals(
            """[{"name":"高等数学"}]""",
            cache.readJsonStale("exams_2026-2027-1"),
            "课表缓存族的落地路径就是店里的同名键",
        )
        assertTrue(
            props(root, storeName).getProperty("exams_2026-2027-1${ArchiveCodec.WRITTEN_AT_SUFFIX}") != null,
            "侧挂时间戳也补上（下一次才判得出新旧）",
        )
        wipeSecureStore(storeName)
    }

    @Test
    fun `桌面没有落点的族如实计进 filesSkipped`() = withRoot { root ->
        val suffix = "_2253415556"
        val entries = archiveOf(
            stores = emptyList(),
            files = listOf(
                ArchiveFile(suffix, "download_tasks/rows.json", "[]"),
            ),
        )
        val outcome = DesktopArchiveImport.run(dropArchiveDir(root, entries), entries)

        assertEquals(
            ImportResult.Ok(keysWritten = 0, keysKept = 0, filesWritten = 0, filesSkipped = 1),
            (outcome as DesktopImportOutcome.Imported).result,
            "没有落点的族：宁可计数也不静默丢",
        )
    }

    @Test
    fun `zip 形态的标记写在 zip 旁边`() = withRoot { root ->
        wipeSecureStore("app_settings")
        val entries = archiveOf(listOf(StoreSnapshot("app_settings", mapOf("default_tab" to StoreValue.S("HOME")))))
        val zip = File(root, DesktopArchiveHost.ARCHIVE_ZIP_NAME)
        DesktopArchiveIo.writeFile(zip, DesktopArchiveIo.zip(entries))

        val outcome = DesktopArchiveImport.run(zip, DesktopArchiveIo.read(zip))

        assertTrue(outcome is DesktopImportOutcome.Imported, "$outcome")
        assertTrue(File(root, "${DesktopArchiveHost.ARCHIVE_ZIP_NAME}.imported").isFile)
        assertEquals("HOME", SettingsStore(secureKeyValueStore(SettingsStore.APP_SETTINGS_FILE)).defaultTab)
        wipeSecureStore(SettingsStore.APP_SETTINGS_FILE)
    }

    // ── 端到端：启动检查 + 设置页那一行的措辞 ───────────────────────────────

    @Test
    fun `端到端：扫到档案就真导，状态文本如实`() = withRoot { root ->
        wipeSecureStore("app_settings")
        assertEquals("数据根下没有待导入的档案", importStatusText())

        val entries = archiveOf(listOf(StoreSnapshot("app_settings", mapOf("default_tab" to StoreValue.S("HOME")))))
        dropArchiveDir(root, entries)

        val pending = DesktopArchiveHost.maybeImportPendingArchive()
        assertTrue(pending?.outcome is DesktopImportOutcome.Imported, "${pending?.outcome}")
        assertTrue(pending?.check is com.xjtu.toolbox.desktop.archive.ArchiveCheck.Ok)
        assertEquals("HOME", SettingsStore(secureKeyValueStore(SettingsStore.APP_SETTINGS_FILE)).defaultTab)

        val text = importStatusText()
        assertTrue(text.startsWith("发现待导入档案 ${DesktopArchiveHost.ARCHIVE_DIR}"), text)
        assertTrue(text.contains("已经导过"), text)
        wipeSecureStore(SettingsStore.APP_SETTINGS_FILE)
    }

    // ── 夹具 ────────────────────────────────────────────────────────────────

    private fun writeEntries(root: File, entries: Map<String, String>) {
        entries.forEach { (rel, content) ->
            val file = File(root, rel)
            file.parentFile?.mkdirs()
            file.writeText(content)
        }
    }

    private companion object {
        const val FIXED_NOW = 1_760_000_000_000L
    }
}
