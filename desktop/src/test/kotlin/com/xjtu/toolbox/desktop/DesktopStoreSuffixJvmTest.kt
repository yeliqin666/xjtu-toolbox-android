package com.xjtu.toolbox.desktop

import com.xjtu.toolbox.account.Account
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.account.AccountStore
import com.xjtu.toolbox.desktop.jiaocai1.DesktopJiaocai1ShelfStore
import com.xjtu.toolbox.desktop.media.DesktopDownloadTaskStore
import com.xjtu.toolbox.jiaocai1.Jiaocai1ShelfRecord
import com.xjtu.toolbox.media.DownloadStatus
import com.xjtu.toolbox.media.DownloadTask
import com.xjtu.toolbox.platform.dataRootOverride
import com.xjtu.toolbox.platform.keyValueStoreRootOverride
import com.xjtu.toolbox.platform.secureKeyValueStore
import com.xjtu.toolbox.platform.wipeSecureStore
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** 桌面端账号命名空间及旧数据隔离。 */
class DesktopStoreSuffixJvmTest {

    private lateinit var root: File

    /** 本类专属的账号（后缀唯一 ⇒ 与别处的 store 名不撞）。 */
    private val account = "2990000001"
    private val otherAccount = "2990000002"

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("xjtu-desktop-suffix-test").toFile()
        dataRootOverride = root
        keyValueStoreRootOverride = root
        AccountContext.activeAccountId = null
        for (name in listOf(
            DesktopDownloadTaskStore.STORE_NAME,
            DesktopDownloadTaskStore.storeName(account),
            DesktopDownloadTaskStore.storeName(otherAccount),
            DesktopJiaocai1ShelfStore.STORE_NAME,
            DesktopJiaocai1ShelfStore.storeName(account),
            DesktopJiaocai1ShelfStore.storeName(otherAccount),
            AccountStore.FILE_NAME,
        )) {
            wipeSecureStore(name)
        }
    }

    @AfterTest
    fun tearDown() {
        for (name in listOf(
            DesktopDownloadTaskStore.STORE_NAME,
            DesktopDownloadTaskStore.storeName(account),
            DesktopDownloadTaskStore.storeName(otherAccount),
            DesktopJiaocai1ShelfStore.STORE_NAME,
            DesktopJiaocai1ShelfStore.storeName(account),
            DesktopJiaocai1ShelfStore.storeName(otherAccount),
            AccountStore.FILE_NAME,
        )) wipeSecureStore(name)
        dataRootOverride = null
        keyValueStoreRootOverride = null
        AccountContext.activeAccountId = null
        root.deleteRecursively()
    }

    // ── 端内名 ──────────────────────────────────────────────────────────────

    @Test
    fun `端内名 = base + 账号后缀，三档后缀逐字来自 AccountContext`() {
        assertEquals("download_tasksdefault", accountScopedStoreName("download_tasks", null))
        assertEquals("download_tasks_", accountScopedStoreName("download_tasks", ""))
        assertEquals("download_tasks_2990000001", accountScopedStoreName("download_tasks", account))

        // 四处都是同一条规则：三个 Store 自己那一声 + 课表族那一处
        assertEquals(DesktopDownloadTaskStore.storeName(account), accountScopedStoreName(DesktopDownloadTaskStore.STORE_NAME, account))
        assertEquals(DesktopJiaocai1ShelfStore.storeName(account), accountScopedStoreName(DesktopJiaocai1ShelfStore.STORE_NAME, account))

        // 不给 accountId 时读的是当前 `AccountContext`（外壳那几处就是这么建的）
        AccountContext.activeAccountId = null
        assertEquals("download_tasksdefault", DesktopDownloadTaskStore.storeName())
        AccountContext.activeAccountId = account
        assertEquals("download_tasks_2990000001", DesktopDownloadTaskStore.storeName())
        assertEquals("jiaocai1_shelf_2990000001", DesktopJiaocai1ShelfStore.storeName())
    }

    @Test
    fun unownedDownloadRowsAreNotInheritedByAccounts() = runBlocking {
        val legacy = secureKeyValueStore(DesktopDownloadTaskStore.STORE_NAME)
        DesktopDownloadTaskStore(legacy).insert(task(filePath = "/tmp/old.mp4"))
        val legacyRaw = legacy.getString(KEY_ROWS)
        val first = DesktopDownloadTaskStore(account)
        val second = DesktopDownloadTaskStore(otherAccount)

        assertTrue(first.getAll().isEmpty())
        assertTrue(second.getAll().isEmpty())
        first.insert(task(filePath = "/tmp/new.mp4"))
        assertEquals(listOf("/tmp/new.mp4"), DesktopDownloadTaskStore(account).getAll().map { it.filePath })
        assertTrue(DesktopDownloadTaskStore(otherAccount).getAll().isEmpty())
        assertEquals(legacyRaw, legacy.getString(KEY_ROWS))
        assertTrue(File(root, "download_tasks.properties").isFile)
    }

    @Test
    fun unownedShelfRowsAreNotInheritedByAccounts() = runBlocking {
        val legacy = secureKeyValueStore(DesktopJiaocai1ShelfStore.STORE_NAME)
        DesktopJiaocai1ShelfStore(legacy).upsert(shelfRow("A", "旧名那本"))
        val legacyRaw = legacy.getString(KEY_ROWS)
        val first = DesktopJiaocai1ShelfStore(account)
        val second = DesktopJiaocai1ShelfStore(otherAccount)

        assertTrue(first.observeAll().first().isEmpty())
        assertTrue(second.observeAll().first().isEmpty())
        first.upsert(shelfRow("B", "新名那本"))
        assertEquals(listOf("新名那本"), DesktopJiaocai1ShelfStore(account).observeAll().first().map { it.title })
        assertTrue(DesktopJiaocai1ShelfStore(otherAccount).observeAll().first().isEmpty())
        assertEquals(legacyRaw, legacy.getString(KEY_ROWS))
        assertTrue(File(root, "jiaocai1_shelf.properties").isFile)
    }

    @Test
    fun singleAccountAdoptsLegacyRowsOnce() = runBlocking {
        AccountStore().upsert(Account(accountId = account))
        val legacy = secureKeyValueStore(DesktopDownloadTaskStore.STORE_NAME)
        DesktopDownloadTaskStore(legacy).insert(task(filePath = "/tmp/old.mp4"))

        assertEquals(listOf("/tmp/old.mp4"), DesktopDownloadTaskStore(account).getAll().map { it.filePath })
        // 只接收一次：清掉新名里的行后旧数据不会回来，旧文件仍保留
        secureKeyValueStore(DesktopDownloadTaskStore.storeName(account)).remove(KEY_ROWS)
        assertTrue(DesktopDownloadTaskStore(account).getAll().isEmpty())
        assertTrue(File(root, "download_tasks.properties").isFile)
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
