package com.xjtu.toolbox.desktop

import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.archive.ArchiveFile
import com.xjtu.toolbox.archive.ArchivePaths
import com.xjtu.toolbox.archive.ArchiveRowFamilies
import com.xjtu.toolbox.archive.ImportResult
import com.xjtu.toolbox.desktop.archive.DesktopArchiveHost
import com.xjtu.toolbox.desktop.archive.DesktopArchiveImport
import com.xjtu.toolbox.desktop.archive.DesktopArchiveIo
import com.xjtu.toolbox.desktop.archive.DesktopImportOutcome
import com.xjtu.toolbox.desktop.jiaocai1.DesktopJiaocai1ShelfStore
import com.xjtu.toolbox.desktop.media.DesktopDownloadTaskStore
import com.xjtu.toolbox.jiaocai1.Jiaocai1ShelfRecord
import com.xjtu.toolbox.media.DownloadStatus
import com.xjtu.toolbox.media.DownloadTask
import com.xjtu.toolbox.platform.dataRootOverride
import com.xjtu.toolbox.platform.keyValueStoreRootOverride
import com.xjtu.toolbox.platform.secureKeyValueStore
import com.xjtu.toolbox.platform.wipeSecureStore
import com.xjtu.toolbox.schedule.CustomCourse
import com.xjtu.toolbox.schedule.KeyValueCustomCourseStore
import com.xjtu.toolbox.util.AppJson
import java.io.File
import java.nio.file.Files
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 桌面端与「三族行数组」的接缝验收（§2.3 表尾三行 / §6.3 / §6.4）。
 *
 * 钉住两件事，都是**跨端**的：
 *  1. **导出**：端上三族键值店里**有行**的那几族，投影成 `files/<相对路径>` 的行数组 —— 列名逐字照
 *     Room 那几张表、`id` 保持端内原值；**没行的那一族不写**（不拿一个空数组声称本端这一族是空的）。
 *  2. **导入**：档案里那几族行数组按主键合并进端内键值店 —— 端内已有的主键**整行保留**（含 `id`），
 *     只追加端内没有的主键；端内多出来的行一行不动。
 *
 * 隔离：`dataRootOverride` + `keyValueStoreRootOverride` 都指到临时目录，并把进程内 memo 过的
 * 那几份 store 清掉（`wipeSecureStore`）⇒ 绝不碰用户真实的 `~/.local/share/xjtu-toolbox`。
 *
 * ⚠️ 一处如实记下的错位：这三族的端内名带**当前账号后缀**，而导入发生在「用户还没登录」的时刻
 * （`DesktopAuth` 之后才有 `activeAccountId`）⇒ 那时写进的是**未登录命名空间**（后缀 `default`，
 * 与 `DesktopDownloadsJvmTest` 同一处口径）。用例按同一命名空间读写，所以是确定的；错位本身记在
 * `DesktopArchiveRows` 的注释里，不在这里假装它不存在。
 */
class DesktopArchiveRowsJvmTest {

    // ── 夹具与隔离 ─────────────────────────────────────────────────────

    /** 临时数据根 + 三族 store 的进程内 memo 清场（起止各一次）。 */
    private fun withRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("xjtu-desktop-rows").toFile()
        dataRootOverride = root
        keyValueStoreRootOverride = root
        AccountContext.activeAccountId = null
        WIPE.forEach { wipeSecureStore(it) }
        try {
            block(root)
        } finally {
            WIPE.forEach { wipeSecureStore(it) }
            dataRootOverride = null
            keyValueStoreRootOverride = null
            AccountContext.activeAccountId = null
        }
    }

    /** 自建日程那一族：与 `DesktopScheduleHost.customCourses` 同一份键值店（无账号后缀）。 */
    private fun courses(): KeyValueCustomCourseStore =
        KeyValueCustomCourseStore(secureKeyValueStore(DesktopScheduleHost.CUSTOM_COURSE_STORE))

    /** 下载队列那一族：与 `DesktopDownloads.manager` 同一套装配（端内名带后缀 + 旧名只读兜底）。 */
    private fun downloads(): DesktopDownloadTaskStore = DesktopDownloadTaskStore()

    /** 教材书架那一族：与桌面 jiaocai1 那一屏同一套装配。 */
    private fun shelf(): DesktopJiaocai1ShelfStore = DesktopJiaocai1ShelfStore()

    /** 端内三族键值店落盘的那份 properties（`keyValueStoreRootOverride` 下）。 */
    private fun props(root: File, name: String): Properties =
        Properties().apply { File(root, "$name.properties").inputStream().use { load(it) } }

    // ── 档案侧夹具 ──────────────────────────────────────────────────

    /** 一份只带 `files/` 的档案；`now` 钉死 ⇒ 两次导出字节相同。 */
    private fun archiveOf(vararg families: Pair<String, String>): Map<String, String> =
        DesktopArchiveHost.buildArchive(
            accounts = emptyList(),
            stores = emptyList(),
            files = families.map { (relPath, content) -> ArchiveFile("", relPath, content) },
            now = { FIXED_NOW },
        )

    /** 档案里 `global/files/<relPath>` 那一条解出来的行数组。 */
    private fun rowsOf(entries: Map<String, String>, relPath: String): JsonArray {
        val key = ArchivePaths.filePath("", relPath)
        val content = entries[key] ?: error("档案里没有 $key；实际有 ${entries.keys.sorted()}")
        return AppJson.parseToJsonElement(content) as JsonArray
    }

    /** 三族里真写进档案的那几条路径（用来断言「没行的那一族不写」）。 */
    private fun familyKeys(entries: Map<String, String>): List<String> =
        entries.keys.filter { key ->
            ArchiveRowFamilies.Family.entries.any { ArchivePaths.filePath("", it.relPath) == key }
        }.sorted()

    /** 写进 `<dataRoot>/archive/` 再走 `DesktopArchiveHost.maybeImportPendingArchive` 那条路。 */
    private fun importArchive(entries: Map<String, String>, root: File): DesktopImportOutcome {
        val dir = File(root, DesktopArchiveHost.ARCHIVE_DIR)
        DesktopArchiveIo.writeDirectory(entries, dir)
        return DesktopArchiveImport.run(dir, entries)
    }

    private fun rowsText(vararg rows: String): String =
        JsonArray(rows.map { AppJson.parseToJsonElement(it) }).toString()

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.content ?: ""
    private fun JsonObject.long(key: String): Long = (this[key] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
    private fun JsonArray.ids(): List<Long> = map { (it as JsonObject).long("id") }

    // ── 端内那一行（真实对象，由各自的 Store 写出） ──────────────────────────

    private fun course(name: String, dayOfWeek: Int = 1): CustomCourse = CustomCourse(
        accountId = ACCOUNT_ID,
        courseName = name,
        location = "西-101",
        weekBits = "01111111111111111100",
        dayOfWeek = dayOfWeek,
        startSection = 5,
        endSection = 6,
        termCode = TERM,
    )

    /** 端内那一行下载；`completeTime` 非 null，而另一条可空列保持 null 考「端内 JSON 里就没有这个键」。 */
    private fun task(title: String, createTime: Long): DownloadTask = DownloadTask(
        activityId = 100,
        courseName = "在线课程",
        activityTitle = title,
        cameraType = "teacher",
        videoUrl = "https://example.invalid/activity/100.m3u8",
        audioSource = "",
        filePath = "/tmp/xjtu-desktop-rows/100.ts",
        fileSize = 10_000,
        downloadedSize = 10_000,
        status = DownloadStatus.COMPLETED,
        createTime = createTime,
        completeTime = createTime,
    )

    private fun shelfRow(ssno: String, title: String): Jiaocai1ShelfRecord = Jiaocai1ShelfRecord(
        ssno = ssno,
        title = title,
        author = "甲",
        coverUrl = "https://example.invalid/$ssno.png",
        totalPages = 10,
        lastReadIndex = 3,
        lastReadAt = 1_000,
        addedAt = 1_000,
    )

    // ── 档案里那一行（Android 导出的形状，逐列写出） ───────────────────────────

    private fun archiveCourseRow(id: Long, name: String, dayOfWeek: Int): String = buildJsonObject {
        put("id", id)
        put("accountId", ACCOUNT_ID)
        put("courseName", name)
        put("teacher", "")
        put("location", "西-101")
        put("weekBits", "01111111111111111100")
        put("dayOfWeek", dayOfWeek)
        put("startSection", 5)
        put("endSection", 6)
        put("startMinuteOfDay", -1)
        put("endMinuteOfDay", -1)
        put("termCode", TERM)
        put("note", "")
        put("createdAt", FIXED_NOW)
    }.toString()

    private fun archiveDownloadRow(id: Long, title: String, createTime: Long): String = buildJsonObject {
        put("id", id)
        put("activityId", 100)
        put("courseName", "在线课程")
        put("activityTitle", title)
        put("cameraType", "teacher")
        put("videoUrl", "https://example.invalid/activity/100.m3u8")
        put("audioSource", "")
        put("filePath", "/tmp/xjtu-archive-rows/100.ts")
        put("fileSize", 10_000)
        put("downloadedSize", 10_000)
        put("status", DownloadStatus.COMPLETED)
        put("createTime", createTime)
        put("completeTime", JsonNull)
        put("errorMessage", JsonNull)
        put("downloadSpeed", 0)
    }.toString()

    private fun archiveShelfRow(ssno: String, title: String): String = buildJsonObject {
        put("ssno", ssno)
        put("title", title)
        put("author", "乙")
        put("coverUrl", "https://example.invalid/$ssno.png")
        put("totalPages", 10)
        put("lastReadIndex", 0)
        put("lastReadAt", 0)
        put("addedAt", FIXED_NOW)
    }.toString()

    // ── 导出（§2.3 表尾三行） ─────────────────────────────────────

    @Test
    fun `导出 三族里有行的都进 files 行数组 列名逐字且 id 保持原值`() = withRoot { root ->
        runBlocking {
            courses().insert(course(name = "本机的日程"))
            downloads().insert(task(title = "第 1 讲", createTime = 1_000))
            shelf().upsert(shelfRow(ssno = "S-1", title = "本机的教材"))
        }

        val entries = DesktopArchiveHost.exportArchive(now = { FIXED_NOW })

        val courseRow = rowsOf(entries, ArchiveRowFamilies.CUSTOM_COURSES_REL).single() as JsonObject
        assertEquals(COLUMNS_CUSTOM_COURSES, courseRow.keys.toList(), "列名逐字照 Room 那 14 列（顺序也一样）")
        assertEquals(1L, courseRow.long("id"), "id 保持端内原值（不重编号）")
        assertEquals("本机的日程", courseRow.str("courseName"))
        assertEquals(ACCOUNT_ID, courseRow.str("accountId"))

        val downloadRow = rowsOf(entries, ArchiveRowFamilies.DOWNLOAD_TASKS_REL).single() as JsonObject
        assertEquals(1L, downloadRow.long("id"))
        assertEquals("第 1 讲", downloadRow.str("activityTitle"))
        assertEquals(1_000L, downloadRow.long("completeTime"))
        // 端内那段 JSON 里那一段是显式 `null`（`AppJson` 的 `encodeDefaults` 让可空字段也写出来）
        // ⇒ 档案里照它原样搬运：不补一个进去、也不把它删掉
        assertEquals(
            JsonNull,
            downloadRow["errorMessage"],
            "端内那份 JSON 里 errorMessage 是 null ⇒ 档案里也照它原样",
        )
        // 只有一行 ⇒ 排序是恒等变换 ⇒ 档案里那一行的字节必须与端内那段 JSON 里那一行**逐字相同**
        //（键名 / 值 / 字段集一个不改：这一段是 §2.3 的「原样搬运」那一条）
        val localRow = (
            AppJson.parseToJsonElement(
                props(root, DesktopDownloadTaskStore.storeName(null))
                    .getProperty(ArchiveRowFamilies.Family.DOWNLOAD_TASKS.key),
            ) as JsonArray
        ).single()
        assertEquals(localRow.toString(), downloadRow.toString())

        val shelfJson = rowsOf(entries, ArchiveRowFamilies.JIAOCAI_SHELF_REL).single() as JsonObject
        assertEquals(COLUMNS_JIAOCAI_SHELF, shelfJson.keys.toList())
        assertEquals("S-1", shelfJson.str("ssno"))
    }

    @Test
    fun `导出 端上没有行的那一族不写 不拿一个空数组声称这一族是空的`() = withRoot { _ ->
        runBlocking { shelf().upsert(shelfRow(ssno = "S-9", title = "只有书架有行")) }

        val entries = DesktopArchiveHost.exportArchive(now = { FIXED_NOW })

        assertEquals(
            listOf(ArchivePaths.filePath("", ArchiveRowFamilies.JIAOCAI_SHELF_REL)),
            familyKeys(entries),
            "只有书架那一族有行 ⇒ 三族里只写它；另两族一条都不写",
        )
        assertEquals(1, rowsOf(entries, ArchiveRowFamilies.JIAOCAI_SHELF_REL).size)
    }

    // ── 导入（§6.4 合并） ───────────────────────────────────────────

    @Test
    fun `导入 三族分别合进三个键值店 端内已有主键整行保留 只追加新主键`() = withRoot { root ->
        AccountContext.activeAccountId = ACCOUNT_ID
        runBlocking {
            courses().insert(course(name = "本机的日程", dayOfWeek = 2))
            courses().insert(course(name = "端内多出来的一行", dayOfWeek = 3))
            downloads().insert(task(title = "本机的课", createTime = 1_000))
            shelf().upsert(shelfRow(ssno = "S-1", title = "本机的教材"))
        }
        val localCourses = secureKeyValueStore(DesktopScheduleHost.CUSTOM_COURSE_STORE)
            .getString(KeyValueCustomCourseStore.KEY)!!

        val entries = archiveOf(
            ArchiveRowFamilies.CUSTOM_COURSES_REL to rowsText(
                archiveCourseRow(id = 1, name = "档案里的日程", dayOfWeek = 1),
                archiveCourseRow(id = 5, name = "档案新增的日程", dayOfWeek = 4),
            ),
            ArchiveRowFamilies.DOWNLOAD_TASKS_REL to rowsText(
                archiveDownloadRow(id = 1, title = "档案里的课", createTime = 1_500),
                archiveDownloadRow(id = 7, title = "档案新增的下载", createTime = 2_000),
            ),
            ArchiveRowFamilies.JIAOCAI_SHELF_REL to rowsText(
                archiveShelfRow(ssno = "S-1", title = "档案里的教材"),
                archiveShelfRow(ssno = "S-2", title = "档案新增的教材"),
            ),
        )

        val outcome = importArchive(entries, root)
        assertTrue(outcome is DesktopImportOutcome.Imported, "$outcome")
        assertEquals(
            ImportResult.Ok(keysWritten = 0, keysKept = 0, filesWritten = 3),
            outcome.result,
            "三族各一条行数组文件（这三族在桌面不是 store）",
        )

        val merged = secureKeyValueStore(DesktopScheduleHost.CUSTOM_COURSE_STORE)
            .getString(KeyValueCustomCourseStore.KEY)!!
        assertTrue(merged.startsWith(localCourses.dropLast(1)), "端内已有主键整行保留，只往后面追加：$merged")
        assertEquals(listOf(1L, 2L, 5L), (AppJson.parseToJsonElement(merged) as JsonArray).ids())

        val mine = runBlocking { courses().getAll(ACCOUNT_ID) }
        assertEquals("本机的日程", mine.first { it.id == 1L }.courseName, "主键撞上时端内胜")
        assertEquals("档案新增的日程", mine.first { it.id == 5L }.courseName)
        assertEquals(TERM, mine.first { it.id == 5L }.termCode, "新行按档案里自己的值装回来")

        val downloadsNow = runBlocking { downloads().getAll() }
        assertEquals(listOf(7L, 1L), downloadsNow.map { it.id }, "按 createTime 倒序：档案那一行 2000 在前")
        assertEquals("本机的课", downloadsNow.first { it.id == 1L }.activityTitle, "主键撞上时端内胜")
        assertEquals(2_000L, downloadsNow.first { it.id == 7L }.createTime)

        assertEquals("本机的教材", runBlocking { shelf().get("S-1")!!.title }, "主键撞上时端内胜")
        assertEquals("档案新增的教材", runBlocking { shelf().get("S-2")!!.title })
    }

    @Test
    fun `导入 新名没有行时合并基取旧名 旧文件一个字不动`() = withRoot { root ->
        val legacyRows = rowsText(archiveDownloadRow(id = 1, title = "旧名里的课", createTime = 1_000))
        secureKeyValueStore(DesktopDownloadTaskStore.STORE_NAME)
            .putString(ArchiveRowFamilies.Family.DOWNLOAD_TASKS.key, legacyRows)

        // 导出侧同一条兜底：新名里没有行 ⇒ 档案里那一族取旧名里那一行
        val exported = DesktopArchiveHost.exportArchive(now = { FIXED_NOW })
        assertEquals(listOf(1L), rowsOf(exported, ArchiveRowFamilies.DOWNLOAD_TASKS_REL).ids())

        val entries = archiveOf(
            ArchiveRowFamilies.DOWNLOAD_TASKS_REL to
                rowsText(archiveDownloadRow(id = 8, title = "档案新增的下载", createTime = 2_000)),
        )
        val outcome = importArchive(entries, root)
        assertTrue(outcome is DesktopImportOutcome.Imported, "$outcome")

        assertEquals(
            legacyRows,
            props(root, DesktopDownloadTaskStore.STORE_NAME)
                .getProperty(ArchiveRowFamilies.Family.DOWNLOAD_TASKS.key),
            "旧名那份文件只读：一个字没改",
        )
        val merged = props(root, DesktopDownloadTaskStore.storeName(null))
            .getProperty(ArchiveRowFamilies.Family.DOWNLOAD_TASKS.key)
        assertEquals(
            listOf(1L, 8L),
            (AppJson.parseToJsonElement(merged) as JsonArray).ids(),
            "合并基含旧名里那一行（不然旧队列导入时会被当成空的）",
        )
        assertEquals(
            listOf(8L, 1L),
            runBlocking { downloads().getAll() }.map { it.id },
            "业务读法（同一命名空间）两行都在，按 createTime 倒序",
        )
    }

    private companion object {
        const val ACCOUNT_ID = "2253415556"
        const val TERM = "2026-2027-1"
        const val FIXED_NOW = 1_760_000_000_000L

        /** Room 那张自建日程表的 14 列（逐字，顺序也一样）。 */
        val COLUMNS_CUSTOM_COURSES = listOf(
            "id", "accountId", "courseName", "teacher", "location", "weekBits", "dayOfWeek",
            "startSection", "endSection", "startMinuteOfDay", "endMinuteOfDay", "termCode", "note", "createdAt",
        )

        /** Room 那张教材书架表的 8 列。 */
        val COLUMNS_JIAOCAI_SHELF = listOf(
            "ssno", "title", "author", "coverUrl", "totalPages", "lastReadIndex", "lastReadAt", "addedAt",
        )

        /** 三族的端内名（含各自的旧名）—— 清进程内 memo 用，名字逐字取自各 Store 自己的口径。 */
        val WIPE = listOf(
            DesktopScheduleHost.CUSTOM_COURSE_STORE,
            DesktopDownloadTaskStore.STORE_NAME,
            DesktopDownloadTaskStore.storeName(null),
            DesktopDownloadTaskStore.storeName(ACCOUNT_ID),
            DesktopJiaocai1ShelfStore.STORE_NAME,
            DesktopJiaocai1ShelfStore.storeName(null),
            DesktopJiaocai1ShelfStore.storeName(ACCOUNT_ID),
        )
    }
}
