package com.xjtu.toolbox.archive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S1b：`StoreArchive.import` 的逐键裁决（§6.4）与坏档案不动端（§7 的 **A6 / A7**）。
 *
 * 为什么这两条在**内存 source/sink** 上跑（规格 A7 那行的口径）：`import` 是纯函数门面 ——
 * 字节从 [ArchiveSource] 进来、要写的那些键从 [ArchiveImportTarget] 出去；两头都在内存里，
 * 「端内零变化」才能逐字比（`Map` 的相等），不依赖任何端的内核文件。
 *
 * 端内现状由 [MemoryTarget] 持有；档案那半一律走 [StoreArchive.export] 现编（顺带把
 * 「导出 → 导入」这条必经之路也跑一遍）；只有「store 路径读不出来」那一条需要手写一个 [ArchiveSource]。
 */
class StoreArchiveImportTest {

    private val suffix = ArchiveFixture.SUFFIX
    private val now = ArchiveFixture.FIXED_NOW

    // ── A6：不丢键 + 不覆盖本机（表驱动，逐条钉 §6.4 的裁决） ──────────────────

    @Test
    fun `A6 逐键裁决：at 新者胜、任一方缺 at 本端胜、本端独有保持不变、set 覆盖不并集`() {
        mergeCases.forEach { case ->
            val target = MemoryTarget()
            case.local?.let { target.seed(it) }

            val result = StoreArchive.import(sourceOfArchive(listOf(case.archive)), target)

            assertEquals(ImportResult.Ok(case.keysWritten, case.keysKept, 0), result, "用例：${case.name}")
            val after = assertNotNull(target.store(case.archive.rawName), "用例：${case.name}：导入后该 store 应当在")
            assertEquals(case.expected, after, "用例：${case.name}")

            // A6 的前半：「导入前后端内键集合 ⊇ 档案键集合」
            case.archive.keys.keys.forEach { key ->
                assertTrue(after.keys.containsKey(key), "用例：${case.name}：少了档案里的键 $key")
            }
        }
    }

    @Test
    fun `A6 同名键的仲裁之外：档案里另外的键（含 set 空数组）照写，本端那份不动`() {
        val target = MemoryTarget()
        target.seed(StoreSnapshot("library_favorites", mapOf("favorite_seats" to StoreValue.SetValue(listOf("本机")))))

        val archiveValue = StoreSnapshot(
            "library_favorites",
            mapOf(
                "favorite_seats" to StoreValue.SetValue(listOf("档案")),
                "favorite_venue_ids" to StoreValue.SetValue(emptyList()),
            ),
        )

        val result = StoreArchive.import(sourceOfArchive(listOf(archiveValue)), target)
        assertEquals(ImportResult.Ok(keysWritten = 1, keysKept = 1, filesWritten = 0), result)

        // `favorite_seats` 两边都没有 at ⇒ 本端胜；`favorite_venue_ids` 端上没有 ⇒ 写入
        //（§2.2：`set` 空数组是合法值，≠ 键不存在）
        assertEquals(
            StoreSnapshot(
                "library_favorites",
                mapOf(
                    "favorite_seats" to StoreValue.SetValue(listOf("本机")),
                    "favorite_venue_ids" to StoreValue.SetValue(emptyList()),
                ),
            ),
            target.store("library_favorites"),
        )
    }

    @Test
    fun `A6 files 层：本端没有的条目写进去，本端已有的保持不变（缺 at ⇒ 本端胜）`() {
        val target = MemoryTarget()
        target.seedFile(suffix, "agent_sessions/index.json", "本机那份")
        val source = sourceOfArchive(
            stores = emptyList(),
            files = listOf(
                ArchiveFile(suffix, "agent_sessions/index.json", """[{"id":"档案那份"}]"""),
                ArchiveFile("", "courses/custom_courses.json", "[]"),
            ),
        )

        assertEquals(ImportResult.Ok(0, 0, 1), StoreArchive.import(source, target))
        assertEquals("本机那份", target.file(suffix, "agent_sessions/index.json"))
        assertEquals("[]", target.file("", "courses/custom_courses.json"))
    }

    @Test
    fun `端内私有名（serve 那一档）即使档案里带着也不写`() {
        val entries = mapOf(
            "manifest.json" to manifestJson(),
            "accounts/$suffix/stores/serve.json" to ArchiveCodec.encodeStore(
                StoreSnapshot("serve$suffix", mapOf("access_token" to StoreValue.S("x"))), "serve", suffix,
            ),
        )
        val target = MemoryTarget()

        assertEquals(ImportResult.Ok(0, 0, 0), StoreArchive.import(sourceOf(entries), target))
        assertNull(target.store("serve$suffix"), "端内私有项一律不落（与导出侧不写它们是同一条规则的两半）")
    }

    @Test
    fun `幂等：同一份档案导入两次与导入一次的结果相同`() {
        val archive = StoreSnapshot(
            "library_favorites",
            mapOf("favorite_seats" to StoreValue.SetValue(listOf("A-1"))),
            mapOf("favorite_seats" to now),
        )
        val source = sourceOfArchive(listOf(archive))
        val target = MemoryTarget()

        val first = StoreArchive.import(source, target)
        val afterFirst = target.state()
        val second = StoreArchive.import(source, target)

        assertEquals(ImportResult.Ok(1, 0, 0), first)
        assertEquals(ImportResult.Ok(0, 1, 0), second, "第二次跑：端内已经是最新 ⇒ 一个键都不写")
        assertEquals(afterFirst, target.state(), "两次导入之后的端内状态逐字相同")
    }

    // ── A7：坏档案不动端（表驱动） ──────────────────────────────────────────

    @Test
    fun `A7 每个坏档案都明确失败，且端内键集合与值零变化`() {
        badArchives.forEach { case ->
            val target = MemoryTarget()
            target.seed(StoreSnapshot("app_settings", mapOf("dark_mode" to StoreValue.S("dark"))))
            target.seed(StoreSnapshot("library_favorites", mapOf("favorite_seats" to StoreValue.SetValue(listOf("A-1")))))
            target.seedFile(suffix, "agent_sessions/index.json", "本机那份")
            val before = target.state()

            val result = StoreArchive.import(case.source, target)

            assertTrue(result is ImportResult.Failed, "用例：${case.name}：应当明确失败，实际 $result")
            assertTrue((result as ImportResult.Failed).reason.isNotBlank(), "用例：${case.name}：失败要带一句原因")
            assertEquals(before, target.state(), "用例：${case.name}：端内零变化（A7）")
        }
    }

    // ── 自校验（S5/S6 那两份判定收口到 :core 之后） ────────────────────────

    @Test
    fun `自校验：自编的档案通过，条目按 stores 那一层列出来（码点升序）`() {
        val archive = StoreArchive.export(
            ArchiveFixture.accounts, "android", "5.1.1 (471)",
            ArchiveFixture.stores, ArchiveFixture.files, ArchiveFixture.fixedClock(),
        )

        val inspection = StoreArchive.inspect(sourceOf(archive))
        assertTrue(inspection is ArchiveInspection.Ok, "$inspection")
        inspection as ArchiveInspection.Ok
        assertEquals(ARCHIVE_FORMAT, inspection.manifest.format)
        assertEquals(ARCHIVE_VERSION, inspection.manifest.version)

        // 凭据族默认不写（A8）⇒ 只剩三个非凭据 store
        assertEquals(
            listOf(
                "accounts/$suffix/stores/schedule_cache.json",
                "global/stores/app_settings.json",
                "global/stores/library_favorites.json",
            ),
            inspection.stores,
        )
        assertEquals(2, inspection.files, "两个 files 条目（账号档 + 全局档各一个）")
    }

    @Test
    fun `自校验：坏档案给出一句原因而不是抛异常`() {
        badArchives.forEach { case ->
            val inspection = StoreArchive.inspect(case.source)
            assertTrue(inspection is ArchiveInspection.Failed, "用例：${case.name}：应当失败，实际 $inspection")
            assertTrue((inspection as ArchiveInspection.Failed).reason.isNotBlank(), "用例：${case.name}")
        }
    }

    // ── 夹具 ────────────────────────────────────────────────────────────────

    /** 一条合并用例：档案里那份 × 端内现状 ⇒ 期待端内变成什么样 + 两个计数（§6.4）。 */
    private data class MergeCase(
        val name: String,
        val archive: StoreSnapshot,
        val local: StoreSnapshot?,
        val expected: StoreSnapshot,
        val keysWritten: Int,
        val keysKept: Int,
    )

    private val mergeCases = listOf(
        MergeCase(
            name = "双方都有 at、档案更新 ⇒ 档案胜（连 at 一起落下去）",
            archive = favorites(listOf("A-1"), at = now + 1),
            local = favorites(listOf("B-2"), at = now),
            expected = favorites(listOf("A-1"), at = now + 1),
            keysWritten = 1,
            keysKept = 0,
        ),
        MergeCase(
            name = "双方都有 at、本端更新 ⇒ 本端胜（一个字都不动）",
            archive = favorites(listOf("A-1"), at = now),
            local = favorites(listOf("B-2"), at = now + 1),
            expected = favorites(listOf("B-2"), at = now + 1),
            keysWritten = 0,
            keysKept = 1,
        ),
        MergeCase(
            name = "at 相同 ⇒ 本端胜（新者胜不是「不小于者胜」）",
            archive = favorites(listOf("A-1"), at = now),
            local = favorites(listOf("B-2"), at = now),
            expected = favorites(listOf("B-2"), at = now),
            keysWritten = 0,
            keysKept = 1,
        ),
        MergeCase(
            name = "档案缺 at ⇒ 本端胜",
            archive = favorites(listOf("A-1"), at = null),
            local = favorites(listOf("B-2"), at = now),
            expected = favorites(listOf("B-2"), at = now),
            keysWritten = 0,
            keysKept = 1,
        ),
        MergeCase(
            name = "本端缺 at ⇒ 本端胜",
            archive = favorites(listOf("A-1"), at = now),
            local = favorites(listOf("B-2"), at = null),
            expected = favorites(listOf("B-2"), at = null),
            keysWritten = 0,
            keysKept = 1,
        ),
        MergeCase(
            name = "档案有、本端没有 ⇒ 写入；同一个 store 里本端独有的那条不动",
            archive = StoreSnapshot("app_settings", mapOf("dark_mode" to StoreValue.S("dark"))),
            local = StoreSnapshot("app_settings", mapOf("show_quick_actions" to StoreValue.B(false))),
            expected = StoreSnapshot(
                "app_settings",
                mapOf("show_quick_actions" to StoreValue.B(false), "dark_mode" to StoreValue.S("dark")),
            ),
            keysWritten = 1,
            keysKept = 0,
        ),
        MergeCase(
            name = "本端根本没有这个 store ⇒ 档案里那些键整套写入（含 set 空数组与空串）",
            archive = StoreSnapshot(
                "venue_favorites",
                mapOf("favorite_venue_ids" to StoreValue.SetValue(emptyList()), "last_venue" to StoreValue.S("")),
            ),
            local = null,
            expected = StoreSnapshot(
                "venue_favorites",
                mapOf("favorite_venue_ids" to StoreValue.SetValue(emptyList()), "last_venue" to StoreValue.S("")),
            ),
            keysWritten = 2,
            keysKept = 0,
        ),
        MergeCase(
            name = "set 是覆盖不是并集：档案更新时把本端那条整份换掉",
            archive = favorites(listOf("A-1"), at = now + 1),
            local = StoreSnapshot(
                "library_favorites",
                mapOf(
                    "favorite_seats" to StoreValue.SetValue(listOf("A-1", "B-2")),
                    "favorite_venue_ids" to StoreValue.SetValue(listOf("V-1")),
                ),
                mapOf("favorite_seats" to now),
            ),
            expected = StoreSnapshot(
                "library_favorites",
                mapOf(
                    "favorite_seats" to StoreValue.SetValue(listOf("A-1")),
                    "favorite_venue_ids" to StoreValue.SetValue(listOf("V-1")),
                ),
                mapOf("favorite_seats" to now + 1),
            ),
            keysWritten = 1,
            keysKept = 0,
        ),
    )

    /** 一份坏档案 + 它是哪一档坏。 */
    private data class BadArchive(val name: String, val source: ArchiveSource)

    private val badArchives = listOf(
        BadArchive("一个条目都没有", sourceOf(emptyMap())),
        BadArchive(
            "缺 manifest.json",
            sourceOf(
                mapOf(
                    "global/stores/app_settings.json" to
                        ArchiveCodec.encodeStore(StoreSnapshot("app_settings", mapOf("dark_mode" to StoreValue.S("dark"))), "app_settings", ""),
                )
            ),
        ),
        BadArchive(
            "format 不对",
            sourceOf(
                mapOf(
                    "manifest.json" to ArchiveCodec.encodeManifest(
                        ArchiveManifest(format = "not-an-archive", createdAt = "2026-10-14T08:10:56Z", createdBy = "x", appVersion = "y")
                    )
                )
            ),
        ),
        BadArchive(
            "version=99（不认识就明确失败，不猜）",
            sourceOf(
                mapOf(
                    "manifest.json" to ArchiveCodec.encodeManifest(
                        ArchiveManifest(version = 99, createdAt = "2026-10-14T08:10:56Z", createdBy = "x", appVersion = "y")
                    )
                )
            ),
        ),
        BadArchive("manifest.json 截断", sourceOf(mapOf("manifest.json" to """{"format":"$ARCHIVE_FORMAT",""")),
        ),
        BadArchive("manifest.json 不是 JSON 对象", sourceOf(mapOf("manifest.json" to """["nope"]"""))),
        BadArchive(
            "store 文件解不开（截断）",
            sourceOf(
                mapOf(
                    "manifest.json" to manifestJson(),
                    "global/stores/app_settings.json" to """{"format":"$ARCHIVE_STORE_FORMAT",""",
                )
            ),
        ),
        BadArchive(
            "store 文件缺值类型标签 t",
            sourceOf(
                mapOf(
                    "manifest.json" to manifestJson(),
                    "global/stores/app_settings.json" to
                        """{"format":"$ARCHIVE_STORE_FORMAT","version":1,"store":"app_settings","suffix":"","keys":{"dark_mode":{"v":"dark"}}}""",
                )
            ),
        ),
        BadArchive(
            "manifest 列了某个 store，但那条路径读不出来",
            object : ArchiveSource {
                override fun readManifest(): String = manifestJson()
                override fun storeNames(): List<String> = listOf("global/stores/app_settings.json")
                override fun readStore(name: String): String? = null
                override fun filePaths(): List<String> = emptyList()
                override fun readFile(rel: String): String? = null
            },
        ),
        BadArchive(
            "两个 accountId 落在同一个 suffix（§4 必须明确失败，不许静默合并）",
            sourceOf(
                StoreArchive.export(
                    accounts = listOf(
                        ArchiveAccount("a-b", "_a_b", "UNDERGRADUATE", now),
                        ArchiveAccount("a_b", "_a_b", "UNDERGRADUATE", now),
                    ),
                    createdBy = "android",
                    appVersion = "x",
                    stores = listOf(StoreSnapshot("app_settings", mapOf("dark_mode" to StoreValue.S("dark")))),
                    files = emptyList(),
                    options = ArchiveFixture.fixedClock(),
                )
            ),
        ),
    )

    private fun favorites(seats: List<String>, at: Long?) = StoreSnapshot(
        "library_favorites",
        mapOf("favorite_seats" to StoreValue.SetValue(seats)),
        at?.let { mapOf("favorite_seats" to it) } ?: emptyMap(),
    )

    private fun manifestJson() = ArchiveCodec.encodeManifest(
        ArchiveManifest(createdAt = "2026-10-14T08:10:56Z", createdBy = "android", appVersion = "x")
    )

    /** 「档案内相对路径 → 字节」→ [ArchiveSource]（三端的通道解出来的都是这个形状）。 */
    private fun sourceOf(entries: Map<String, String>): ArchiveSource = archiveSourceOf(entries)

    /** 已投影的 stores + files → 一份真档案（走 [StoreArchive.export]，不手搓 JSON）。 */
    private fun sourceOfArchive(stores: List<StoreSnapshot>, files: List<ArchiveFile> = emptyList()): ArchiveSource =
        archiveSourceOf(
            StoreArchive.export(
                ArchiveFixture.accounts, "android", "5.1.1 (471)", stores, files, ArchiveFixture.fixedClock(),
            )
        )
}

/**
 * 内存里的「一端」：既是导入的落点（[ArchiveImportTarget]），也保有端内现状供断言。
 *
 * 写入语义刻意照端内的键级写：`putKey` 只动这一个键（其余键与旁挂时间戳原样留着），
 * 所以「本端胜的键一个都没被碰」这件事在 [state] 里看得见。
 */
private class MemoryTarget : ArchiveImportTarget {

    private val stores = linkedMapOf<String, StoreSnapshot>()
    private val files = linkedMapOf<Pair<String, String>, String>()

    override fun readStore(rawName: String): StoreSnapshot? = stores[rawName]

    override fun putKey(rawName: String, key: String, value: StoreValue, at: Long?) {
        val old = stores[rawName]
        val keys = linkedMapOf<String, StoreValue>().apply {
            old?.keys?.let { putAll(it) }
            put(key, value)
        }
        val writtenAt = linkedMapOf<String, Long>().apply {
            old?.writtenAt?.let { putAll(it) }
            if (at != null) put(key, at)
        }
        stores[rawName] = StoreSnapshot(rawName, keys, writtenAt)
    }

    override fun readFile(suffix: String, relPath: String): String? = files[suffix to relPath]

    override fun putFile(suffix: String, relPath: String, content: String) {
        files[suffix to relPath] = content
    }

    fun seed(snapshot: StoreSnapshot) {
        stores[snapshot.rawName] = snapshot
    }

    fun seedFile(suffix: String, relPath: String, content: String) {
        files[suffix to relPath] = content
    }

    fun store(rawName: String): StoreSnapshot? = stores[rawName]

    fun file(suffix: String, relPath: String): String? = files[suffix to relPath]

    /** 端内现状的**副本**（A7 的「零变化」就是比它）。 */
    fun state(): Pair<Map<String, StoreSnapshot>, Map<Pair<String, String>, String>> = stores.toMap() to files.toMap()
}
