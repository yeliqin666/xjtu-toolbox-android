package com.xjtu.toolbox.desktop

import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.desktop.jiaocai1.DesktopJiaocai1ShelfStore
import com.xjtu.toolbox.desktop.media.DesktopDownloadTaskStore
import com.xjtu.toolbox.desktop.score.DesktopScoreReportCache
import com.xjtu.toolbox.jiaocai1.Jiaocai1ShelfRecord
import com.xjtu.toolbox.media.DownloadStatus
import com.xjtu.toolbox.media.DownloadTask
import com.xjtu.toolbox.platform.dataRootOverride
import com.xjtu.toolbox.platform.keyValueStore
import com.xjtu.toolbox.platform.keyValueStoreRootOverride
import com.xjtu.toolbox.platform.secureKeyValueStore
import com.xjtu.toolbox.platform.wipeSecureStore
import com.xjtu.toolbox.score.ReportedGrade
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * S7b：桌面端那三处「按账号分的落盘」的**端内名**与**旧名一次性读入**。
 *
 * 这个类只钉两件事（其余行为仍由各 Store 自己的验收钉）：
 *
 *  1. 端内名 = `base + AccountContext.suffixFor(accountId)`（规格 §3.5④ / 审计 #09 的端侧那半）——
 *     下载队列、教材书架、成绩报表三处与课表族（`DesktopScheduleHost.cache`）现在是**同一条规则**；
 *  2. 加后缀之前那份旧名**只被只读读一次**（§6.1 第 2 条「旧形状只读」）：新名里没有数据时读到旧数据，
 *     而且旧名那份一个字节都不变（不往回写、也不删）；新名里已经有时旧名不干扰。
 *
 * 隔离口径：`dataRootOverride` + `keyValueStoreRootOverride` 都指到临时目录（前者是密文缝，
 * 后者是普通键值缝），账号 id 用本类专属的一个，避开 `keyValueStore(name)` 的进程内 memo 串根。
 */
class DesktopStoreSuffixJvmTest {

    private lateinit var root: File

    /** 本类专属的账号（后缀唯一 ⇒ 与别处的 store 名不撞）。 */
    private val account = "2990000001"

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("xjtu-desktop-suffix-test").toFile()
        dataRootOverride = root
        keyValueStoreRootOverride = root
        AccountContext.activeAccountId = null
        // 密文缝是**进程内 memo**（同一个名字的实例绑在它第一次创建时的根上）⇒ 本类用到的名字先清掉，
        // 让它们绑到本用例的临时根（与 `DesktopDownloadsJvmTest` 同一条口径）。
        for (name in listOf(
            DesktopDownloadTaskStore.STORE_NAME,
            DesktopDownloadTaskStore.storeName(account),
            DesktopJiaocai1ShelfStore.STORE_NAME,
            DesktopJiaocai1ShelfStore.storeName(account),
        )) {
            wipeSecureStore(name)
        }
    }

    @AfterTest
    fun tearDown() {
        dataRootOverride = null
        keyValueStoreRootOverride = null
        AccountContext.activeAccountId = null
    }

    // ── 端内名 ──────────────────────────────────────────────────────────────

    @Test
    fun `端内名 = base + 账号后缀，三档后缀逐字来自 AccountContext`() {
        // `null` ⇒ `"default"`（**无前导下划线**，与 `schedule_cachedefault` / `data_cachedefault` 逐字一致）；
        // `""` ⇒ `"_"`（空串不是 null）
        assertEquals("download_tasksdefault", accountScopedStoreName("download_tasks", null))
        assertEquals("download_tasks_", accountScopedStoreName("download_tasks", ""))
        assertEquals("download_tasks_2990000001", accountScopedStoreName("download_tasks", account))

        // 四处都是同一条规则：三个 Store 自己那一声 + 课表族那一处
        assertEquals(DesktopDownloadTaskStore.storeName(account), accountScopedStoreName(DesktopDownloadTaskStore.STORE_NAME, account))
        assertEquals(DesktopJiaocai1ShelfStore.storeName(account), accountScopedStoreName(DesktopJiaocai1ShelfStore.STORE_NAME, account))
        assertEquals(DesktopScoreReportCache.storeName(account), accountScopedStoreName(DesktopScoreReportCache.STORE_NAME, account))

        // 不给 accountId 时读的是当前 `AccountContext`（外壳那几处就是这么建的）
        AccountContext.activeAccountId = null
        assertEquals("download_tasksdefault", DesktopDownloadTaskStore.storeName())
        AccountContext.activeAccountId = account
        assertEquals("download_tasks_2990000001", DesktopDownloadTaskStore.storeName())
        assertEquals("jiaocai1_shelf_2990000001", DesktopJiaocai1ShelfStore.storeName())
        assertEquals("score_report_2990000001", DesktopScoreReportCache.storeName())
    }

    // ── 旧名一次性读入（三处各一条） ────────────────────────────────────────

    @Test
    fun `下载队列：新名里没有行时读旧名那份，且旧名一个字节没变`() = runBlocking {
        val legacy = secureKeyValueStore(DesktopDownloadTaskStore.STORE_NAME)
        DesktopDownloadTaskStore(store = legacy, legacyStore = null)
            .insert(task(filePath = "/tmp/old.mp4"))
        val legacyRaw = legacy.getString(KEY_ROWS)!!

        val moved = DesktopDownloadTaskStore(
            store = secureKeyValueStore(DesktopDownloadTaskStore.storeName(account)),
            legacyStore = secureKeyValueStore(DesktopDownloadTaskStore.STORE_NAME),
        )
        assertEquals(listOf("/tmp/old.mp4"), moved.getAll().map { it.filePath }, "新名里没有 ⇒ 读旧名那份")

        // 新名里已经有行之后，旧名仍不参与
        moved.insert(task(filePath = "/tmp/new.mp4"))
        assertEquals(setOf("/tmp/old.mp4", "/tmp/new.mp4"), moved.getAll().map { it.filePath }.toSet())

        assertEquals(legacyRaw, legacy.getString(KEY_ROWS), "旧形状只读：不往回写、也不删")
        assertTrue(File(root, "download_tasks.properties").isFile, "旧文件仍在数据根下")
        assertTrue(File(root, "download_tasks_2990000001.properties").isFile, "新名那份落在数据根下")
    }

    @Test
    fun `教材书架：新名里没有行时读旧名那份，且旧名一个字节没变`() = runBlocking {
        val legacy = secureKeyValueStore(DesktopJiaocai1ShelfStore.STORE_NAME)
        DesktopJiaocai1ShelfStore(store = legacy, legacyStore = null).upsert(shelfRow("A", "旧名那本"))
        val legacyRaw = legacy.getString(KEY_ROWS)!!

        val moved = DesktopJiaocai1ShelfStore(
            store = secureKeyValueStore(DesktopJiaocai1ShelfStore.storeName(account)),
            legacyStore = secureKeyValueStore(DesktopJiaocai1ShelfStore.STORE_NAME),
        )
        assertEquals(listOf("旧名那本"), moved.observeAll().first().map { it.title })

        moved.upsert(shelfRow("B", "新名那本"))
        assertEquals(setOf("旧名那本", "新名那本"), moved.observeAll().first().map { it.title }.toSet())
        assertEquals(legacyRaw, legacy.getString(KEY_ROWS), "旧形状只读")
    }

    @Test
    fun `成绩报表：新名里没有这个键时读旧名那份，且旧名那份不动`() {
        val studentId = "2990000001"
        val grades = listOf(ReportedGrade(courseName = "示例课（假）", coursePoint = 3.0, score = "92", gpa = 4.0, term = "2025-2026-1"))
        val legacy = keyValueStore(DesktopScoreReportCache.STORE_NAME)
        DesktopScoreReportCache(studentId, store = legacy, legacyStore = null).write(grades)
        val legacyRaw = legacy.getString(DesktopScoreReportCache.keyFor(studentId))!!

        // 端内名带后缀的那一份：新名里没有这个键 ⇒ 读旧名那份（键不变，仍是 `score_report_<学号>`）
        val moved = DesktopScoreReportCache(
            studentId,
            store = keyValueStore(DesktopScoreReportCache.storeName(account)),
            legacyStore = keyValueStore(DesktopScoreReportCache.STORE_NAME),
        )
        assertEquals(grades, moved.read())

        // 新名里写过之后，旧名不再参与（同一个键、新的 store 名）
        moved.write(grades.drop(1))
        assertEquals(grades.drop(1), moved.read(), "新名里已经有时以新名为准")
        assertEquals(legacyRaw, legacy.getString(DesktopScoreReportCache.keyFor(studentId)), "旧形状只读")

        assertTrue(File(root, "score_report.properties").isFile, "旧名那份仍在数据根下")
        assertTrue(File(root, "score_report_2990000001.properties").isFile, "新名那份落在数据根下")
    }

    // ── 夹具 ────────────────────────────────────────────────────────────────

    private fun task(filePath: String) = DownloadTask(
        id = 0L,
        activityId = 7,
        courseName = "示例课",
        activityTitle = "示例活动",
        cameraType = "instructor",
        videoUrl = "http://example.invalid/v.mp4",
        audioSource = "",
        filePath = filePath,
        fileSize = 10L,
        downloadedSize = 10L,
        status = DownloadStatus.COMPLETED,
        createTime = 100L,
        completeTime = null,
        errorMessage = null,
        downloadSpeed = 0L,
    )

    private fun shelfRow(ssno: String, title: String) = Jiaocai1ShelfRecord(
        ssno = ssno,
        title = title,
        author = "示例作者",
        coverUrl = "",
        totalPages = 10,
        lastReadIndex = 0,
        lastReadAt = 100L,
        addedAt = 100L,
    )

    private companion object {
        /** 两处落盘都只有这一个键（`DesktopDownloadTaskStore` / `DesktopJiaocai1ShelfStore`）。 */
        const val KEY_ROWS = "rows"
    }
}
