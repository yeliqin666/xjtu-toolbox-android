package com.xjtu.toolbox.archive

import com.xjtu.toolbox.account.Account
import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.data.InMemorySharedPreferences
import com.xjtu.toolbox.jiaocai1.Jiaocai1ShelfEntity
import com.xjtu.toolbox.media.DownloadTaskEntity
import com.xjtu.toolbox.schedule.CustomCourseEntity
import com.xjtu.toolbox.util.AppJson
import java.io.File
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S4：`:app` 侧接线点的验收 —— 断言「导出/导入入口存在且默认不动数据」这类可断言的事实。
 *
 * 覆盖：
 *  - Room 三表行适配器调 `:core` 的 [ArchiveFileRows] 排序（§2.3）；
 *  - [AppArchiveHost] 的纯组装（accounts/stores/files → [StoreArchive.export]）+ 落盘只写目标目录；
 *  - SAF 通道的 zip 运输形态（§2.0）；
 *  - 导入触发点默认不动数据（§6.2，合并语义排在后续工单 ⇒ 本工单只做入口与通道）。
 *
 * 全部用例不依赖真 Context / Room 库：`InMemorySharedPreferences` + 临时目录驱动。
 */
class AppArchiveWiringTest {

    private fun newTempDir(prefix: String): File {
        val f = File.createTempFile(prefix, "")
        f.delete()
        f.mkdirs()
        return f
    }

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.content.orEmpty()

    private fun rowIds(json: String): List<String> =
        AppJson.parseToJsonElement(json).jsonArray.map { (it as JsonObject).str("id") }

    private fun rowSsnos(json: String): List<String> =
        AppJson.parseToJsonElement(json).jsonArray.map { (it as JsonObject).str("ssno") }

    // ── Room 三表行适配器：调 ArchiveFileRows 排序 ──

    @Test
    fun `custom_courses 行适配器按六键升序`() {
        val rows = listOf(
            CustomCourseEntity(id = 3, accountId = "B", courseName = "c", weekBits = "1", dayOfWeek = 1, startSection = 3, endSection = 4, termCode = "2026-2027-1"),
            CustomCourseEntity(id = 1, accountId = "A", courseName = "a", weekBits = "1", dayOfWeek = 1, startSection = 1, endSection = 2, termCode = "2026-2027-1"),
            CustomCourseEntity(id = 2, accountId = "A", courseName = "b", weekBits = "1", dayOfWeek = 1, startSection = 1, endSection = 2, startMinuteOfDay = 480, termCode = "2026-2027-1"),
            CustomCourseEntity(id = 4, accountId = "A", courseName = "d", weekBits = "1", dayOfWeek = 7, startSection = 9, endSection = 10, termCode = "2025-2026-2"),
        )
        val file = RoomArchiveFamilies.customCoursesFile(rows)
        assertEquals("", file.suffix)
        assertEquals(RoomArchiveFamilies.CUSTOM_COURSES_REL, file.relPath)
        assertEquals(listOf("4", "1", "2", "3"), rowIds(file.content))
    }

    @Test
    fun `download_tasks 行适配器按 createTime desc 再 id 升序`() {
        val rows = listOf(
            DownloadTaskEntity(id = 1, activityId = 0, courseName = "", activityTitle = "", cameraType = "", videoUrl = "", audioSource = "", filePath = "", fileSize = 0, downloadedSize = 0, status = "pending", createTime = 100, completeTime = null, errorMessage = null),
            DownloadTaskEntity(id = 2, activityId = 0, courseName = "", activityTitle = "", cameraType = "", videoUrl = "", audioSource = "", filePath = "", fileSize = 0, downloadedSize = 0, status = "pending", createTime = 300, completeTime = null, errorMessage = null),
            DownloadTaskEntity(id = 3, activityId = 0, courseName = "", activityTitle = "", cameraType = "", videoUrl = "", audioSource = "", filePath = "", fileSize = 0, downloadedSize = 0, status = "pending", createTime = 300, completeTime = null, errorMessage = null),
            DownloadTaskEntity(id = 4, activityId = 0, courseName = "", activityTitle = "", cameraType = "", videoUrl = "", audioSource = "", filePath = "", fileSize = 0, downloadedSize = 0, status = "pending", createTime = 200, completeTime = null, errorMessage = null),
        )
        val file = RoomArchiveFamilies.downloadTasksFile(rows)
        assertEquals(RoomArchiveFamilies.DOWNLOAD_TASKS_REL, file.relPath)
        assertEquals(listOf("2", "3", "4", "1"), rowIds(file.content))
    }

    @Test
    fun `jiaocai1_shelf 行适配器按 addedAt desc 再 ssno 升序`() {
        val rows = listOf(
            Jiaocai1ShelfEntity(ssno = "b", title = "", author = "", coverUrl = "", totalPages = 0, lastReadIndex = 0, lastReadAt = 0, addedAt = 100),
            Jiaocai1ShelfEntity(ssno = "a", title = "", author = "", coverUrl = "", totalPages = 0, lastReadIndex = 0, lastReadAt = 0, addedAt = 300),
            Jiaocai1ShelfEntity(ssno = "c", title = "", author = "", coverUrl = "", totalPages = 0, lastReadIndex = 0, lastReadAt = 0, addedAt = 300),
            Jiaocai1ShelfEntity(ssno = "d", title = "", author = "", coverUrl = "", totalPages = 0, lastReadIndex = 0, lastReadAt = 0, addedAt = 200),
        )
        val file = RoomArchiveFamilies.jiaocaiShelfFile(rows)
        assertEquals(RoomArchiveFamilies.JIAOCAI_SHELF_REL, file.relPath)
        assertEquals(listOf("a", "c", "d", "b"), rowSsnos(file.content))
    }

    // ── 导出入口：纯组装 + 落盘只写目标目录、不写端内 store ──

    @Test
    fun `导出入口组装并落盘 且不写端内 store 数据`() {
        val prefs = InMemorySharedPreferences()
        prefs.edit().putString("dark_mode", "dark").commit()
        val backend = AndroidPrefsBackend(prefsOf = { prefs }, namesOf = { listOf("app_settings") })

        val accounts = listOf(ArchiveAccount("2253415556", "_2253415556", "UNDERGRADUATE", 1_760_000_000_000L))
        val files = listOf(RoomArchiveFamilies.downloadTasksFile(emptyList()))
        val archive = AppArchiveHost.assemble(
            accounts = accounts,
            stores = AppArchiveHost.readStores(backend),
            files = files,
            appVersion = "5.1.1 (471)",
            now = { 1_760_000_000_000L },
        )
        assertTrue("manifest 必须在档案里", archive.containsKey("manifest.json"))
        assertTrue("store 投影必须进档案", archive.containsKey("global/stores/app_settings.json"))
        assertTrue("Room 行数组必须进档案", archive.containsKey("global/files/media/download_tasks.json"))

        val root = newTempDir("xjtu-archive")
        try {
            AppArchiveHost.writeToDirectory(archive, root)
            assertTrue(File(root, "manifest.json").isFile)
            assertTrue(File(root, "global/stores/app_settings.json").isFile)
            assertTrue(File(root, "global/files/media/download_tasks.json").isFile)
        } finally {
            root.deleteRecursively()
        }

        // 默认不动数据：导出只读端内 store，键值逐字不变
        assertEquals("dark", prefs.getString("dark_mode", null))
    }

    @Test
    fun `accountsFrom 按 suffixFor 逐字映射`() {
        val mapped = AppArchiveHost.accountsFrom(
            listOf(Account(accountId = "2253415556", accountType = AccountType.UNDERGRADUATE, lastUsedAt = 123L)),
        )
        assertEquals(1, mapped.size)
        assertEquals(ArchiveAccount("2253415556", "_2253415556", "UNDERGRADUATE", 123L), mapped[0])
    }

    // ── SAF 通道：zip 是运输形态 ──

    @Test
    fun `SAF 通道 zip 是档案字节的运输形态`() {
        val archive = mapOf(
            "manifest.json" to """{"format":"xjtu-toolbox-archive","version":1}""",
            "global/stores/app_settings.json" to """{"store":"app_settings"}""",
        )
        val bytes = AppArchiveUx.zipArchive(archive)
        val unzipped = mutableMapOf<String, String>()
        ZipInputStream(bytes.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                unzipped[entry.name] = zis.readBytes().toString(Charsets.UTF_8)
                entry = zis.nextEntry
            }
        }
        assertEquals(archive, unzipped)
    }

    // ── 导入触发点：默认不动数据 ──

    @Test
    fun `导入触发点默认不动数据`() {
        val staging = newTempDir("xjtu-staging")
        try {
            // 空目录 = 没有待导入档案 ⇒ 静默跳过（§6.2 第 1 条：NOT_FOUND 之外不报错）
            AppArchiveUx.maybeImportPendingArchive(listOf(staging))
            assertEquals(emptyList<File>(), staging.listFiles()?.toList().orEmpty())

            // 有档案也只是判读，不 merge、不删、不写（合并语义在后续工单）
            val pending = File(staging, "pending.zip")
            pending.writeText("staged")
            AppArchiveUx.maybeImportPendingArchive(listOf(staging))
            assertEquals(listOf("pending.zip"), staging.listFiles()?.map { it.name }?.sorted().orEmpty())
            assertEquals("staged", pending.readText())
        } finally {
            staging.deleteRecursively()
        }
    }

    @Test
    fun `档案落地目录里的整目录形态也算待导入`() {
        val archiveRoot = newTempDir("xjtu-archive-root")
        val staging = newTempDir("xjtu-staging")
        try {
            // §2.0：目录即档案本体（`files/archive/<archive>/manifest.json`），不是只有 zip 才算
            val dropped = File(archiveRoot, "archive")
            dropped.mkdirs()
            File(dropped, "manifest.json").writeText(ARCHIVE_MANIFEST_FIXTURE)

            AppArchiveUx.maybeImportPendingArchive(listOf(archiveRoot, staging))
            assertEquals(listOf("archive"), archiveRoot.listFiles()?.map { it.name }.orEmpty())
            assertEquals(ARCHIVE_MANIFEST_FIXTURE, File(dropped, "manifest.json").readText())
            // 空的那一档照旧为空（判读不改任何东西）
            assertEquals(emptyList<File>(), staging.listFiles()?.toList().orEmpty())
        } finally {
            archiveRoot.deleteRecursively()
            staging.deleteRecursively()
        }
    }

    @Test
    fun `坏档案落在档案目录里端内键集合与值零变化`() {
        val prefs = InMemorySharedPreferences()
        prefs.edit().putString("dark_mode", "dark").putInt("eula_accepted_version", 4).commit()
        val backend = AndroidPrefsBackend(prefsOf = { prefs }, namesOf = { listOf("app_settings") })
        val before = backend.readStore("app_settings")!!.keys

        val archiveRoot = newTempDir("xjtu-archive-root")
        try {
            // 空目录 / 截断的 manifest：坏档案不能让端内 store 动一个键（§7 A7）
            File(archiveRoot, "empty").mkdirs()
            val broken = File(archiveRoot, "broken")
            broken.mkdirs()
            File(broken, "manifest.json").writeText("""{"format":"xjtu""")

            AppArchiveUx.maybeImportPendingArchive(listOf(archiveRoot))

            assertEquals(before, backend.readStore("app_settings")!!.keys)
            assertEquals(StoreValue.S("dark"), before["dark_mode"])
            assertEquals(StoreValue.I(4), before["eula_accepted_version"])
            assertEquals("dark", prefs.getString("dark_mode", null))
        } finally {
            archiveRoot.deleteRecursively()
        }
    }

    private companion object {
        const val ARCHIVE_MANIFEST_FIXTURE = """{"format":"xjtu-toolbox-archive","version":1}"""
    }
}
