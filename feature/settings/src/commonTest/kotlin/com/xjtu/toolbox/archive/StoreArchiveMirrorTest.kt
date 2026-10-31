package com.xjtu.toolbox.archive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §6.4「显式镜像档」：先算差异（端内不动）→ 确认后一次性落地 → 中途失败整体回滚。
 *
 * 三条不许动的口径：
 * ① 红线清单上的店**永不删**；② 档案里没出现的店**一个字不动**；③ 默认档（MERGE）语义与以前逐字相同。
 */
class StoreArchiveMirrorTest {

    private val suffix = ArchiveFixture.SUFFIX
    private val now = ArchiveFixture.FIXED_NOW

    @Test
    fun `镜像档：档案声明过的店按档案对齐，档案里没有的键删掉、值不同就覆盖、不看年龄`() {
        val target = MirrorTarget()
        target.seed(
            StoreSnapshot(
                "app_settings",
                mapOf(
                    "dark_mode" to StoreValue.S("light"),
                    "eula_accepted_version" to StoreValue.I(4),
                    "local_only_key" to StoreValue.S("档案里没有"),
                ),
                // 本端比档案新：默认档会「本端胜」，镜像档必须照样覆盖。
                writtenAt = mapOf("dark_mode" to now + 1_000, "eula_accepted_version" to now + 1_000),
            ),
        )
        val archive = archiveOf(
            listOf(
                StoreSnapshot(
                    "app_settings",
                    mapOf(
                        "dark_mode" to StoreValue.S("dark"),
                        "eula_accepted_version" to StoreValue.I(4),
                    ),
                ),
            ),
        )

        val planned = assertIs<ArchivePlanOutcome.Ok>(StoreArchive.plan(archive, target, ArchiveImportMode.MIRROR))
        assertEquals(2, planned.plan.diff.keysOverwritten)
        assertEquals(0, planned.plan.diff.keysAdded)
        assertEquals(1, planned.plan.diff.keysDeleted)
        assertEquals(0, planned.plan.diff.keysProtected)
        assertTrue(planned.plan.destructive)
        assertEquals("将删除 1 条、覆盖 2 条、新写入 0 条", planned.plan.summary)

        val result = StoreArchive.apply(planned.plan, target)
        assertEquals(
            ImportResult.Ok(keysWritten = 2, keysKept = 0, filesWritten = 0, keysDeleted = 1),
            result,
        )
        val after = assertNotNull(target.store("app_settings"))
        assertEquals(setOf("dark_mode", "eula_accepted_version"), after.keys.keys)
        assertEquals(StoreValue.S("dark"), after.keys["dark_mode"])
    }

    @Test
    fun `镜像档：红线清单上的店只写不删（凭据、cookies、sites、secure、社区）`() {
        val protected = listOf(
            "xjtu_credentials",
            "xjtu_accounts",
            "xjtu_cookies$suffix",
            "cookies_sample",
            "sites_sample",
            "agent_config_secure",
            "github_community",
        )
        val target = MirrorTarget()
        protected.forEach { name ->
            target.seed(StoreSnapshot(name, mapOf("only_local" to StoreValue.S("留着"))))
        }
        // 档案**声明了**这些店，但里面一个键都没有 ⇒ 一般店会把本机那份对齐成空；红线店必须挡住。
        val archive = archiveOf(protected.map { StoreSnapshot(it, emptyMap()) }, credentials = true)

        val planned = assertIs<ArchivePlanOutcome.Ok>(StoreArchive.plan(archive, target, ArchiveImportMode.MIRROR))
        assertEquals(0, planned.plan.diff.keysDeleted)
        assertEquals(protected.size, planned.plan.diff.keysProtected)
        assertFalse(planned.plan.destructive)

        val result = StoreArchive.apply(planned.plan, target)
        assertEquals(ImportResult.Ok(0, 0, 0), result)
        protected.forEach { name ->
            assertEquals(setOf("only_local"), assertNotNull(target.store(name)).keys.keys, "红线店被删了：$name")
        }
    }

    @Test
    fun `镜像档：档案里没出现的店一个字不动`() {
        val target = MirrorTarget()
        target.seed(StoreSnapshot("library_favorites", mapOf("favorite_seats" to StoreValue.SetValue(listOf("本机")))))
        val archive = archiveOf(listOf(StoreSnapshot("app_settings", mapOf("dark_mode" to StoreValue.S("dark")))))

        val planned = assertIs<ArchivePlanOutcome.Ok>(StoreArchive.plan(archive, target, ArchiveImportMode.MIRROR))
        assertEquals(0, planned.plan.diff.keysDeleted)
        StoreArchive.apply(planned.plan, target)

        assertEquals(
            StoreSnapshot("library_favorites", mapOf("favorite_seats" to StoreValue.SetValue(listOf("本机")))),
            target.store("library_favorites"),
        )
    }

    @Test
    fun `默认档语义不变：本机多余的键一个字不动，且从不删`() {
        val target = MirrorTarget()
        target.seed(StoreSnapshot("app_settings", mapOf("local_only_key" to StoreValue.S("本机有档案没有"))))
        val archive = archiveOf(
            listOf(
                StoreSnapshot(
                    "app_settings",
                    mapOf("dark_mode" to StoreValue.S("dark")),
                ),
            ),
        )

        val planned = assertIs<ArchivePlanOutcome.Ok>(StoreArchive.plan(archive, target, ArchiveImportMode.MERGE))
        assertEquals(0, planned.plan.diff.keysDeleted)
        assertEquals(0, planned.plan.diff.keysProtected)
        assertFalse(planned.plan.destructive)
        assertEquals("将覆盖 0 条、新写入 1 条", planned.plan.summary)

        val result = StoreArchive.import(archive, target)
        assertEquals(ImportResult.Ok(keysWritten = 1, keysKept = 0, filesWritten = 0), result)
        assertEquals(
            setOf("local_only_key", "dark_mode"),
            assertNotNull(target.store("app_settings")).keys.keys,
        )
    }

    @Test
    fun `镜像档：只对档案里出现过的 files 族对齐，别的族（含 Room 行族）不删`() {
        val target = MirrorTarget()
        target.seedFile(suffix, "agent_sessions/index.json", """[{"id":"s1"}]""")
        target.seedFile(suffix, "agent_sessions/stale.json", """[{"id":"老的"}]""")
        target.seedFile("", "courses/custom_courses.json", """[{"id":"本机自建"}]""")
        val archive = archiveOf(
            stores = emptyList(),
            files = listOf(ArchiveFile(suffix, "agent_sessions/index.json", """[{"id":"s1"}]""")),
        )

        val planned = assertIs<ArchivePlanOutcome.Ok>(StoreArchive.plan(archive, target, ArchiveImportMode.MIRROR))
        assertEquals(1, planned.plan.diff.filesDeleted)
        assertEquals(0, planned.plan.diff.filesWritten)
        assertEquals(1, planned.plan.diff.filesUnchanged)

        val result = StoreArchive.apply(planned.plan, target)
        assertEquals(ImportResult.Ok(0, 0, 0, filesDeleted = 1), result)
        assertNull(target.file(suffix, "agent_sessions/stale.json"))
        assertEquals("""[{"id":"s1"}]""", target.file(suffix, "agent_sessions/index.json"))
        // `courses/` 那一族档案里没出现 ⇒ 一个字都不动
        assertEquals("""[{"id":"本机自建"}]""", target.file("", "courses/custom_courses.json"))
    }

    @Test
    fun `镜像档：一族里有一条拼不出端内名就整族不删（保守口径）`() {
        val target = MirrorTarget()
        target.seedFile(suffix, "agent_sessions/stale.json", """[{"id":"老的"}]""")
        target.unmappedPaths += "agent_sessions/broken.json"
        val archive = archiveOf(
            stores = emptyList(),
            files = listOf(
                ArchiveFile(suffix, "agent_sessions/index.json", """[{"id":"s1"}]"""),
                ArchiveFile(suffix, "agent_sessions/broken.json", """[{"id":"转不动"}]"""),
            ),
        )

        val planned = assertIs<ArchivePlanOutcome.Ok>(StoreArchive.plan(archive, target, ArchiveImportMode.MIRROR))
        assertEquals(0, planned.plan.diff.filesDeleted)
        assertEquals(1, planned.plan.diff.filesSkipped)
        StoreArchive.apply(planned.plan, target)
        assertEquals("""[{"id":"老的"}]""", target.file(suffix, "agent_sessions/stale.json"))
    }

    @Test
    fun `plan 不动端内一个字节`() {
        val target = MirrorTarget()
        target.seed(StoreSnapshot("app_settings", mapOf("dark_mode" to StoreValue.S("light"), "gone" to StoreValue.S("x"))))
        target.seedFile(suffix, "agent_sessions/stale.json", "老的")
        val archive = archiveOf(
            listOf(StoreSnapshot("app_settings", mapOf("dark_mode" to StoreValue.S("dark")))),
            files = listOf(ArchiveFile(suffix, "agent_sessions/index.json", "{}")),
        )
        val before = target.state()

        listOf(ArchiveImportMode.MERGE, ArchiveImportMode.MIRROR).forEach { mode ->
            assertIs<ArchivePlanOutcome.Ok>(StoreArchive.plan(archive, target, mode))
            assertEquals(before, target.state(), "plan（$mode）动了端内")
        }
    }

    @Test
    fun `镜像档中途失败整体回滚：端内与导入前逐字相同`() {
        val target = MirrorTarget(failWriteAt = 3)
        target.seed(
            StoreSnapshot(
                "app_settings",
                mapOf("a" to StoreValue.S("本机a"), "b" to StoreValue.S("本机b"), "gone" to StoreValue.S("x")),
                writtenAt = mapOf("a" to now),
            ),
        )
        target.seedFile(suffix, "agent_sessions/stale.json", "老的")
        val archive = archiveOf(
            listOf(StoreSnapshot("app_settings", mapOf("a" to StoreValue.S("档案a"), "b" to StoreValue.S("档案b")))),
            files = listOf(ArchiveFile(suffix, "agent_sessions/index.json", "{}")),
        )
        val before = target.state()

        val failed = assertIs<ImportResult.Failed>(StoreArchive.import(archive, target, ArchiveImportMode.MIRROR))

        assertTrue(failed.reason.contains("整体回滚"), "失败原因要说清回滚结果：${failed.reason}")
        assertEquals(before, target.state(), "回滚后端内应当与导入前逐字相同")
    }

    @Test
    fun `红线清单守卫：§6 第 4 条的逐字名单与前后缀一个不少`() {
        // 集合比较（不比较顺序）：`allNames()` 的迭代顺序不是契约，逐字名单才是。
        assertEquals(
            setOf("data_cache_meta", "github_community", "serve", "xjtu_accounts", "xjtu_credentials", "xjtu_cookies"),
            ArchiveRedLines.allNames().toSet(),
        )
        assertEquals(setOf("_fallback", "agent_config_secure", "cookies_", "sites_"), ArchiveRedLines.allPatterns().toSet())
        listOf(
            "xjtu_credentials",
            "xjtu_accounts",
            "xjtu_cookies",
            "cookies_anything",
            "sites_anything",
            "agent_config_secure",
            "agent_config_secure_v2",
            "github_community",
            "serve",
            "agent_config_fallback",
            "data_cache_meta",
        ).forEach { name -> assertTrue(ArchiveRedLines.isProtected(name), "红线清单漏了：$name") }
        listOf("app_settings", "library_favorites", "schedule_cache", "agent_sessions").forEach { name ->
            assertFalse(ArchiveRedLines.isProtected(name), "不该被当成红线：$name")
        }
    }

    // ── 夹具 ────────────────────────────────────────────────────────────────

    /** 一份真档案（走 [StoreArchive.export]，固定时钟 ⇒ 逐字节可复现）。 */
    private fun archiveOf(
        stores: List<StoreSnapshot>,
        files: List<ArchiveFile> = emptyList(),
        credentials: Boolean = false,
    ): ArchiveSource = archiveSourceOf(
        StoreArchive.export(
            ArchiveFixture.accounts,
            "android",
            "5.1.1 (471)",
            stores,
            files,
            ExportOptions(includeCredentials = credentials, now = { now }),
        ),
    )
}

/** 会删键 / 能枚举 `files/` 族的一端：既是落点，也保有端内现状供断言。 */
private class MirrorTarget(
    /** 第 N 次写（从 1 起）在这里抛出去，用来验「中途失败整体回滚」；null = 不抛。 */
    private val failWriteAt: Int? = null,
) : ArchiveImportTarget {

    private val stores = linkedMapOf<String, StoreSnapshot>()
    private val files = linkedMapOf<Pair<String, String>, String>()
    private var writes = 0

    /** 这些 `files/` 档案路径在本端拼不出端内名（[fileRelPath] ⇒ null）。 */
    val unmappedPaths = mutableSetOf<String>()

    /** 这些店「不支持删键」（[removeKey] ⇒ false）。 */
    val noRemoveStores = mutableSetOf<String>()

    private fun tick() {
        writes++
        if (failWriteAt != null && writes == failWriteAt) throw IllegalStateException("模拟第 $writes 笔写失败")
    }

    override fun readStore(rawName: String): StoreSnapshot? = stores[rawName]

    override fun putKey(rawName: String, key: String, value: StoreValue, at: Long?) {
        tick()
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

    override fun removeKey(rawName: String, key: String): Boolean {
        if (rawName in noRemoveStores) return false
        val old = stores[rawName] ?: return false
        if (!old.keys.containsKey(key)) return false
        stores[rawName] = StoreSnapshot(rawName, old.keys - key, old.writtenAt - key)
        return true
    }

    override fun readFile(suffix: String, relPath: String): String? = files[suffix to relPath]

    override fun putFile(suffix: String, relPath: String, content: String) {
        tick()
        files[suffix to relPath] = content
    }

    override fun removeFile(suffix: String, relPath: String): Boolean = files.remove(suffix to relPath) != null

    override fun listFiles(suffix: String, family: String): List<String> =
        files.keys
            .filter { it.first == suffix && it.second.startsWith("$family/") }
            .map { it.second }
            .sorted()

    override fun fileRelPath(suffix: String, accountId: String?, relPath: String): String? =
        if (relPath in unmappedPaths) null else relPath

    fun seed(snapshot: StoreSnapshot) {
        stores[snapshot.rawName] = snapshot
    }

    fun seedFile(suffix: String, relPath: String, content: String) {
        files[suffix to relPath] = content
    }

    fun store(rawName: String): StoreSnapshot? = stores[rawName]

    fun file(suffix: String, relPath: String): String? = files[suffix to relPath]

    /** 端内现状的副本（回滚断言用它比「逐字相同」）。 */
    fun state(): Pair<Map<String, StoreSnapshot>, Map<Pair<String, String>, String>> = stores.toMap() to files.toMap()
}
