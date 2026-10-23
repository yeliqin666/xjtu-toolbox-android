package com.xjtu.toolbox.desktop

import com.xjtu.toolbox.account.Account
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.account.AccountStore
import com.xjtu.toolbox.archive.ArchiveAccount
import com.xjtu.toolbox.archive.ArchiveCodec
import com.xjtu.toolbox.archive.StoreSnapshot
import com.xjtu.toolbox.archive.StoreValue
import com.xjtu.toolbox.archive.archiveDataRoot
import com.xjtu.toolbox.archive.archiveStoreBackend
import com.xjtu.toolbox.desktop.archive.ArchiveCheck
import com.xjtu.toolbox.desktop.archive.DesktopArchiveHost
import com.xjtu.toolbox.desktop.archive.DesktopArchiveIo
import com.xjtu.toolbox.platform.dataRootOverride
import com.xjtu.toolbox.platform.dataFilesDir
import com.xjtu.toolbox.platform.keyValueStore
import com.xjtu.toolbox.platform.keyValueStoreRootOverride
import com.xjtu.toolbox.platform.secureKeyValueStore
import com.xjtu.toolbox.platform.wipeSecureStore
import com.xjtu.toolbox.schedule.KeyValueScheduleCacheStore
import com.xjtu.toolbox.schedule.ScheduleCache
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 桌面（jvm）档案宿主的验收（§7）。 */
class DesktopArchiveJvmTest {

    private fun withRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("xjtu-desktop-archive").toFile()
        dataRootOverride = root
        keyValueStoreRootOverride = root
        try {
            block(root)
        } finally {
            dataRootOverride = null
            keyValueStoreRootOverride = null
        }
    }

    /** 端内全部 store 的投影（= 合并导入前后要比的那份「端内现状」）。 */
    private fun storesNow(): Map<String, StoreSnapshot?> {
        val backend = archiveStoreBackend()
        return backend.storeNames().associateWith { backend.readStore(it) }
    }


    @Test
    fun `A13 端内 store 名与档案路径是纯字符串拼接（正向）`() = withRoot { root ->
        val suffix = AccountContext.suffixFor(STUDENT_ID)
        assertEquals("_2253415556", suffix, "后缀规则照 AccountContext.suffixFor 逐字")

        // 判据用一个**仍然走 `stores/`** 的账号档店来钉：课表缓存族已按 B-9 收成 `files/schedule/`，
        // 它在档案里不再有 `stores/schedule_cache.json` 这条路径（见下面那条端到端用例）。
        keyValueStore("course_colors$suffix").putString("高等数学", "#1F8A70")
        val physical = File(root, "course_colors_2253415556.properties")
        assertTrue(physical.exists(), "端内那份文件应当真的落在这个根下：${physical.absolutePath}")

        val accounts = listOf(ArchiveAccount(STUDENT_ID, suffix, "UNDERGRADUATE", FIXED_NOW))
        val archive = DesktopArchiveHost.buildArchive(
            accounts = accounts,
            stores = DesktopArchiveHost.readStores(archiveStoreBackend()),
            files = emptyList(),
            now = { FIXED_NOW },
        )

        val path = "accounts/$suffix/stores/course_colors.json"
        assertEquals("accounts/_2253415556/stores/course_colors.json", path, "A13 逐字")
        assertTrue(path in archive, "档案里应当有这条路径（实际：${archive.keys.sorted()}）")
    }

    @Test
    fun `A13 档案路径到数据根文件名是纯字符串拼接（反向两个方向）`() = withRoot { root ->
        val suffixes = listOf("_2253415556", "default", "_")

        // 档案路径 → 数据根文件名（只有 `stores/` 那一层才拼得出端内文件名）
        assertEquals(
            File(root, "course_colors_2253415556.properties"),
            DesktopArchiveIo.dataRootTarget(root, "accounts/_2253415556/stores/course_colors.json"),
        )
        assertEquals(
            File(root, "app_settings.properties"),
            DesktopArchiveIo.dataRootTarget(root, "global/stores/app_settings.json"),
        )
        assertNull(DesktopArchiveIo.dataRootTarget(root, "manifest.json"))
        assertNull(DesktopArchiveIo.dataRootTarget(root, "global/files/agent_sessions/index.json"))
        // B-9 之后课表族走文件层 ⇒ 它这一条也不该拼出端内 store 文件名
        assertNull(
            DesktopArchiveIo.dataRootTarget(
                root,
                "accounts/_2253415556/files/schedule/schedule_current_term.json",
            ),
        )

        // 数据根文件名 → 档案路径（拆分要靠后缀清单，§2.0）
        assertEquals(
            "accounts/_2253415556/stores/course_colors.json",
            DesktopArchiveIo.storeArchivePath("course_colors_2253415556", suffixes),
        )
        assertEquals(
            "global/stores/app_settings.json",
            DesktopArchiveIo.storeArchivePath("app_settings", suffixes),
        )

        // 两个方向互为逆：拼出来的名字再拆回去还是同一条路径
        val path = "accounts/_2253415556/stores/course_colors.json"
        val file = DesktopArchiveIo.dataRootTarget(root, path)!!
        assertEquals(path, DesktopArchiveIo.storeArchivePath(file.name.removeSuffix(".properties"), suffixes))
    }

    @Test
    fun `A13 端到端：导出到数据根后，课表族只剩 files schedule 一份且值与原读法同源`() = withRoot { root ->
        wipeSecureStore("xjtu_accounts")
        val suffix = AccountContext.suffixFor(STUDENT_ID)
        wipeSecureStore(DesktopScheduleHost.SCHEDULE_CACHE_STORE + suffix)
        AccountStore().upsert(
            Account(
                accountId = STUDENT_ID,
                password = PASSWORD,
                lastUsedAt = FIXED_NOW,
            ),
            setActive = true,
        )
        val cache = KeyValueScheduleCacheStore(
            secureKeyValueStore(DesktopScheduleHost.SCHEDULE_CACHE_STORE + suffix),
            nowMs = { FIXED_NOW },
        )
        ScheduleCache.writeCurrentTerm(cache, "2026-2027-1")

        val outcome = DesktopArchiveHost.exportToDataRoot(now = { FIXED_NOW })
        assertTrue(outcome.directory.isDirectory, "目录形态（档案本体）：${outcome.directory}")
        assertTrue(outcome.zip.isFile, "zip 运输形态：${outcome.zip}")
        assertEquals(root.absolutePath, archiveDataRoot(), "档案落在本用例的隔离根里")
        assertEquals(dataFilesDir().absolutePath, archiveDataRoot(), "A13 前半：与 :data 的数据根同值")

        // 端内原读法读出来的值（不是从档案里读的）
        assertEquals("2026-2027-1", ScheduleCache.readCurrentTerm(cache))

        val archive = DesktopArchiveIo.readDirectory(outcome.directory)

        // B-9 收口：课表族在档案里**只有** `files/schedule/` 这一份（不再在 stores 里出现第二遍）
        assertFalse(
            "accounts/$suffix/stores/${DesktopScheduleHost.SCHEDULE_CACHE_STORE}.json" in archive,
            "课表族的店不进 stores（实际：${archive.keys.sorted()}）",
        )
        val path = "accounts/$suffix/files/schedule/schedule_current_term.json"
        // 值本身是 AppJson 编出来的 JSON 串（§2.3「原样搬运、不重新建模」）⇒ 档案里是带引号的原文
        assertEquals("\"2026-2027-1\"", outcome.directory.resolve(path).readText(), "档案里那份值：$path")
        assertTrue(
            "schedule/schedule_current_term.json" in ArchiveCodec
                .decodeManifest(outcome.directory.resolve("manifest.json").readText()).files,
            "manifest 的 files 清单里应当有这一条",
        )
        // A10 的同一句话在文件层也成立：侧挂的 `#written_at` 不落成条目（年龄只在端内）
        assertTrue(
            archive.keys.none { it.endsWith(ArchiveCodec.WRITTEN_AT_SUFFIX) },
            "档案里不该出现 *${ArchiveCodec.WRITTEN_AT_SUFFIX} 的条目（A10）",
        )
    }


    @Test
    fun `A11 断言对象是解析后的键值：properties 字节不可比，档案两次导出逐字相同`() = withRoot { root ->
        val store = keyValueStore("s5_a11_plain")
        store.putString("dark_mode", "dark")
        store.putBoolean("show_quick_actions", true)
        store.putInt("eula_accepted_version", 4)
        store.putStringSet("favorite_seats", setOf("B-2", "A-1"))

        val snap = archiveStoreBackend().readStore("s5_a11_plain")!!
        assertEquals(StoreValue.S("dark"), snap.keys["dark_mode"])
        assertEquals(StoreValue.B(true), snap.keys["show_quick_actions"])
        assertEquals(StoreValue.I(4), snap.keys["eula_accepted_version"])
        assertEquals(
            setOf("A-1", "B-2"),
            (snap.keys["favorite_seats"] as StoreValue.SetValue).v.toSet(),
        )
        assertEquals(setOf("dark_mode", "show_quick_actions", "eula_accepted_version", "favorite_seats"), snap.keys.keys)

        val firstLine = File(root, "s5_a11_plain.properties").readLines().first()
        assertTrue(firstLine.startsWith("#"), "首行应当是 Properties.store 的时间戳注释，实际：$firstLine")

        val first = DesktopArchiveHost.buildArchive(emptyList(), listOf(snap), emptyList(), now = { FIXED_NOW })
        val second = DesktopArchiveHost.buildArchive(emptyList(), listOf(snap), emptyList(), now = { FIXED_NOW })
        assertEquals(first.entries.toList(), second.entries.toList(), "两次导出逐条相同（含顺序）")
    }

    // ── 导出形态：目录 / zip ────────────────────────────────────────────────

    @Test
    fun `导出到目录与 zip 两份产物都能原样读回`() = withRoot { root ->
        val store = keyValueStore("s5_io_plain")
        store.putString("default_tab", "HOME")
        val suffix = AccountContext.suffixFor(STUDENT_ID)
        wipeSecureStore(DesktopScheduleHost.SCHEDULE_CACHE_STORE + suffix)
        val cache = KeyValueScheduleCacheStore(
            secureKeyValueStore(DesktopScheduleHost.SCHEDULE_CACHE_STORE + suffix),
        )
        ScheduleCache.writeCurrentTerm(cache, "2026-2027-1")

        val archive = DesktopArchiveHost.buildArchive(
            accounts = listOf(ArchiveAccount(STUDENT_ID, suffix, "UNDERGRADUATE", FIXED_NOW)),
            stores = DesktopArchiveHost.readStores(archiveStoreBackend()),
            files = emptyList(),
            now = { FIXED_NOW },
        )

        val dir = File(root, "io-check")
        DesktopArchiveIo.writeDirectory(archive, dir)
        assertEquals(archive, DesktopArchiveIo.readDirectory(dir))

        val zip = DesktopArchiveIo.zip(archive)
        assertEquals(archive, DesktopArchiveIo.unzip(zip))
        assertEquals(zip.toList(), DesktopArchiveIo.zip(archive).toList(), "zip 字节可复现（条目时间是固定值）")
    }

    // ── 数据根扫描 + 一次性导入的闸门（§6.2） ───────────────────────────────

    @Test
    fun `数据根扫描：没有档案时静默跳过，导出后能扫到目录形态与 zip 形态`() = withRoot { root ->
        assertTrue(DesktopArchiveHost.pendingArchives().isEmpty())
        assertNull(DesktopArchiveHost.maybeImportPendingArchive(), "没档案 ⇒ 静默跳过（§6.2 第 1 条）")
        assertEquals("数据根下没有待导入的档案", importStatusText())

        DesktopArchiveHost.exportToDataRoot(now = { FIXED_NOW })

        val pending = DesktopArchiveHost.pendingArchives().map { it.name }
        assertEquals(listOf(DesktopArchiveHost.ARCHIVE_DIR, DesktopArchiveHost.ARCHIVE_ZIP_NAME), pending)

        // 目录形态与 zip 形态各自都自校验通过（zip 走的是真解包）
        val directory = DesktopArchiveHost.checkArchive(DesktopArchiveHost.archiveRoot())
        assertTrue(directory is ArchiveCheck.Ok, "目录形态自校验：$directory")
        assertTrue(DesktopArchiveHost.checkArchive(DesktopArchiveHost.archiveZip()) is ArchiveCheck.Ok)
        assertTrue(
            DesktopArchiveHost.archiveRoot().resolve("manifest.json").readText().isNotBlank(),
            "目录形态里得有 manifest.json：${root.absolutePath}",
        )

        val text = importStatusText()
        assertTrue(text.startsWith("发现待导入档案 ${DesktopArchiveHost.ARCHIVE_DIR}"), text)
        assertTrue(text.contains("StoreArchive.import"), "如实交代「合并那一半还没落地」：$text")
    }

    @Test
    fun `一次性导入的门禁：坏档案明确失败且端内零变化（A7）`() = withRoot { root ->
        val store = keyValueStore("s5_a7_plain")
        store.putString("dark_mode", "dark")
        store.putBoolean("show_quick_actions", true)
        val before = storesNow()

        val missingManifest = File(root, "missing-manifest")
        File(missingManifest, "global/stores/app_settings.json").apply { parentFile.mkdirs() }.writeText("{}")
        assertTrue(DesktopArchiveHost.checkArchive(missingManifest) is ArchiveCheck.Failed)

        // ② version=99（不认识就明确失败，不猜）
        val badVersion = File(root, "bad-version")
        File(badVersion, "manifest.json").apply { parentFile.mkdirs() }.writeText(
            """{"format":"xjtu-toolbox-archive","version":99,"createdAt":"2026-10-14T08:10:56Z",""" +
                """"createdBy":"desktop-linux","appVersion":"v5.1.1 (desktop)","accounts":[],"stores":[],"files":[]}""",
        )
        assertTrue(DesktopArchiveHost.checkArchive(badVersion) is ArchiveCheck.Failed)

        val truncated = File(root, "truncated")
        File(truncated, "manifest.json").apply { parentFile.mkdirs() }.writeText("""{"format":"xjtu-toolbox-archive",""")
        assertTrue(DesktopArchiveHost.checkArchive(truncated) is ArchiveCheck.Failed)

        DesktopArchiveHost.exportToDataRoot(now = { FIXED_NOW })
        val pending = DesktopArchiveHost.maybeImportPendingArchive()
        assertTrue(pending?.check is ArchiveCheck.Ok, "这一份是自家导出的，应当自校验通过：$pending")
        assertEquals(before, storesNow(), "端内每个 store 的键集合与值逐字未变（A7）")
    }


    @Test
    fun `A8 凭据族默认不进档案、点名列进 redacted，noAccount 如实置位`() = withRoot {
        wipeSecureStore("xjtu_credentials")
        wipeSecureStore("github_community")
        secureKeyValueStore("xjtu_credentials").putString("password", PASSWORD)
        secureKeyValueStore("github_community").putString("token", "t")
        val plain = keyValueStore("s5_a8_plain")
        plain.putString("dark_mode", "light")

        val archive = DesktopArchiveHost.buildArchive(
            accounts = emptyList(),
            stores = DesktopArchiveHost.readStores(archiveStoreBackend()),
            files = emptyList(),
            now = { FIXED_NOW },
        )
        val manifest = ArchiveCodec.decodeManifest(archive.getValue("manifest.json"))

        assertFalse("global/stores/xjtu_credentials.json" in archive, "凭据族默认不写")
        assertFalse("global/stores/github_community.json" in archive)
        assertTrue("global/stores/xjtu_credentials.json" in manifest.redacted)
        assertTrue("global/stores/github_community.json" in manifest.redacted)
        assertTrue(manifest.noAccount, "没有账号 ⇒ noAccount=true（§2.1）")
        assertTrue("global/stores/s5_a8_plain.json" in archive, "非凭据族的 global store 照写")
        assertEquals(listOf("s5_a8_plain"), manifest.stores)
    }

    private companion object {
        const val STUDENT_ID = "2253415556"
        const val PASSWORD = "s5-test-password"
        const val FIXED_NOW = 1_760_000_000_000L
    }
}
