package com.xjtu.toolbox.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * `:core` 的 [DownloadManager]（队列 / 状态机）在**两个假端口**上的行为 —— 执行器与存储都是假的，
 * 所以这些用例钉的是搬迁时最该守住的那部分：命名/消毒、状态迁移、暂停=取消不写 failed、
 * 取消与删除的文件处置、启动自愈、统计口径。
 *
 * 与搬迁前的对位：那套状态机原先写在 `:app`（`media/DownloadManager.kt`）里、直接调 Room，
 * 没有可测的缝；切成端口之后它在 `:core` 里跑同一份 commonTest（Android 与桌面共用）。
 *
 * 真搬运（HTTP / `Range` / 落盘）由另外两层钉：`:data:jvmTest` 的 `JvmDownloadEngineJvmTest`
 * 与 `:desktop:test` 的端到端那条。
 */
class DownloadManagerTest {

    @Test
    fun `排队：文件名消毒、课程目录、初始状态与搬迁前逐字一致`() = runTest {
        val store = FakeStore()
        val fs = FakeFileSystem()
        val engine = FakeEngine { _, onProgress ->
            onProgress(0, 100)
            DownloadOutcome(100, 100)
        }
        val manager = DownloadManager(store, engine, fs, scope = this)

        val ids = manager.enqueueDownloads(
            courseName = "数据结构/示例:课",
            activityTitle = "第 3 讲   回放",
            activityId = 42,
            videos = listOf(
                DownloadItem("instructor", "http://example.invalid/a.mp4"),
                DownloadItem("encoder", "http://example.invalid/b.mp4"),
            ),
        )
        advanceUntilIdle()

        assertEquals(listOf(1L, 2L), ids, "两条任务各拿到一个自增 id")
        // `sanitizeFileName` 的原样口径：非法字符换 `_`、连续空白压成一个空格、收尾 trim
        assertEquals(
            setOf(
                "/fake/ClassReplay/数据结构_示例_课/第 3 讲 回放_教师直播.mp4",
                "/fake/ClassReplay/数据结构_示例_课/第 3 讲 回放_电脑屏幕.mp4",
            ),
            store.rows.map { it.filePath }.toSet(),
        )
        assertEquals(
            listOf("/fake/ClassReplay/数据结构_示例_课"),
            fs.directories.distinct(),
            "课程目录在排队时就建出来（搬迁前那句 mkdirs()，每条机位各来一次）",
        )
        val done = store.rows.single { it.cameraType == "instructor" }
        assertEquals(DownloadStatus.COMPLETED, done.status)
        assertEquals(100L, done.downloadedSize)
        assertEquals(100L, done.fileSize)
        assertEquals(42, done.activityId)
        assertEquals("数据结构/示例:课", done.courseName, "库里存的是原名，消毒只用于文件名")
        assertEquals("instructor", done.audioSource, "audioSource 仍是机位占位（与搬迁前同一个字）")
        assertEquals("http://example.invalid/a.mp4", done.videoUrl)
    }

    @Test
    fun `执行器抛异常：状态落 failed 且写进 errorMessage，不吞进度事件`() = runBlocking {
        val store = FakeStore()
        val engine = FakeEngine { _, _ -> throw IllegalStateException("HTTP 500: boom") }
        // 这一条不用 `runTest`：进度流是 `MutableSharedFlow(replay = 0)`，收集器必须**在发出之前**
        // 真的订上，而虚拟调度器下「订阅」与「投递」的先后不好赌（实测拿到过空列表）。
        // `Dispatchers.Unconfined`：订阅与投递都同步发生在当前调用栈里，恰好就是这条用例要的东西。
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val manager = DownloadManager(store, engine, FakeFileSystem(), scope = scope)

        val events = mutableListOf<DownloadProgress>()
        val collector = scope.launch(Dispatchers.Unconfined) {
            manager.progressFlow.collect { events.add(it) }
        }

        val id = manager.enqueueDownloads("课", "活动", 1, listOf(DownloadItem("instructor", "u"))).single()

        val row = store.rows.single { it.id == id }
        assertEquals(DownloadStatus.FAILED, row.status)
        assertTrue(row.errorMessage?.isNotBlank() == true, "失败原因要写进记录：${row.errorMessage}")
        assertEquals(
            listOf(DownloadStatus.DOWNLOADING, DownloadStatus.FAILED),
            events.map { it.status },
        )
        collector.cancel()
    }

    @Test
    fun `暂停：状态先落 paused，再取消那个协程（绝不落 failed）`() = runTest {
        val store = FakeStore()
        val gate = CompletableDeferred<Unit>()
        val engine = FakeEngine { _, onProgress ->
            onProgress(0, 200)
            gate.await()
            DownloadOutcome(200, 200)
        }
        val manager = DownloadManager(store, engine, FakeFileSystem(), scope = this)

        val id = manager.enqueueDownloads("课", "活动", 1, listOf(DownloadItem("instructor", "u"))).single()
        runCurrent() // 让任务跑到 gate.await()

        manager.pauseDownload(id)
        advanceUntilIdle()

        val row = store.rows.single { it.id == id }
        assertEquals(DownloadStatus.PAUSED, row.status)
        assertTrue(row.errorMessage.isNullOrBlank(), "暂停不是失败，errorMessage 不该被写")
        assertEquals(0L, row.downloadedSize, "被取消的那一次没有走完，字节数没被改写成「完成」")
    }

    @Test
    fun `恢复：把暂停的任务重新排进队列并跑完`() = runTest {
        val store = FakeStore()
        var failFirst = true
        val engine = FakeEngine { _, onProgress ->
            if (failFirst) {
                failFirst = false
                throw IllegalStateException("第一次失败")
            }
            onProgress(0, 50)
            DownloadOutcome(50, 50)
        }
        val manager = DownloadManager(store, engine, FakeFileSystem(), scope = this)

        val id = manager.enqueueDownloads("课", "活动", 1, listOf(DownloadItem("instructor", "u"))).single()
        advanceUntilIdle()
        assertEquals(DownloadStatus.FAILED, store.rows.single().status)

        manager.resumeDownload(id)
        advanceUntilIdle()

        val row = store.rows.single { it.id == id }
        assertEquals(DownloadStatus.COMPLETED, row.status)
        assertTrue(row.errorMessage == null, "成功后失败原因要被清掉（与搬迁前那条 UPDATE 同一语义）")
    }

    @Test
    fun `取消：删文件 + 删记录；删除任务：按状态与 deleteFile 决定动不动文件`() = runTest {
        val store = FakeStore()
        val fs = FakeFileSystem()
        val engine = FakeEngine { _, _ -> DownloadOutcome(10, 10) }
        val manager = DownloadManager(store, engine, fs, scope = this)

        val ids = manager.enqueueDownloads(
            "课", "活动", 1,
            listOf(DownloadItem("instructor", "u1"), DownloadItem("encoder", "u2")),
        )
        advanceUntilIdle()

        // 取消：删文件 + 删记录（删的是**被取消那条**的文件，不是剩下那条）
        val cancelledPath = store.getAll().first { it.id == ids[0] }.filePath
        manager.cancelDownload(ids[0])
        advanceUntilIdle()
        assertEquals(1, store.rows.size)
        assertEquals(cancelledPath, fs.deleted.single())

        // 已完成 + deleteFile = false：只删记录
        fs.deleted.clear()
        manager.deleteTask(ids[1], deleteFile = false)
        advanceUntilIdle()
        assertTrue(store.rows.isEmpty())
        assertTrue(fs.deleted.isEmpty(), "deleteFile = false 时不该碰文件")

        // 暂停中的任务：无论 deleteFile 传什么都要删文件（搬迁前就是这么写的）
        val id3 = manager.enqueueDownloads("课", "活动", 1, listOf(DownloadItem("instructor", "u3"))).single()
        advanceUntilIdle()
        manager.pauseDownload(id3)
        advanceUntilIdle()
        manager.deleteTask(id3, deleteFile = false)
        advanceUntilIdle()
        assertEquals(1, fs.deleted.size)
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun `启动自愈：上次还在 downloading 的任务被置成 paused`() = runTest {
        val store = FakeStore()
        val id = store.insert(
            DownloadTask(
                activityId = 1,
                courseName = "课",
                activityTitle = "上次没下完",
                cameraType = "instructor",
                videoUrl = "u",
                audioSource = "instructor",
                filePath = "/fake/x.mp4",
                fileSize = -1,
                downloadedSize = 12,
                status = DownloadStatus.DOWNLOADING,
                createTime = 1L,
            ),
        )

        DownloadManager(store, FakeEngine { _, _ -> DownloadOutcome(0, 0) }, FakeFileSystem(), scope = this)
        advanceUntilIdle()

        val row = store.rows.single { it.id == id }
        assertEquals(DownloadStatus.PAUSED, row.status)
        assertEquals(12L, row.downloadedSize, "只是把状态改成暂停，进度不丢（下次带 Range 续传）")
    }

    @Test
    fun `统计与删除已完成：计数口径与搬迁前那三条 COUNT 一致`() = runTest {
        val store = FakeStore()
        var n = 0
        val engine = FakeEngine { _, onProgress ->
            n++
            if (n == 1) throw IllegalStateException("这条要失败")
            onProgress(0, 5)
            DownloadOutcome(5, 5)
        }
        val manager = DownloadManager(store, engine, FakeFileSystem(), scope = this)

        manager.enqueueDownloads(
            "课", "活动", 1,
            listOf(DownloadItem("instructor", "u1"), DownloadItem("encoder", "u2")),
        )
        advanceUntilIdle()

        val stats = manager.getDownloadStats()
        assertEquals(0, stats.downloadingCount)
        assertEquals(1, stats.completedCount)
        assertEquals(0, stats.activeCount, "活跃 = downloading/paused/pending，失败的不算")

        manager.deleteCompleted()
        advanceUntilIdle()
        assertEquals(1, store.rows.size, "只删了已完成那条")
        assertEquals(DownloadStatus.FAILED, store.rows.single().status, "只删了已完成那条")
    }
}
/** 内存存储：语义照 [DownloadTaskStore] 的 KDoc（排序/计数口径与 Android 那条 SQL 一致）。 */
private class FakeStore : DownloadTaskStore {

    val rows = mutableListOf<DownloadTask>()
    private var lastId = 0L

    override suspend fun insert(task: DownloadTask): Long {
        val id = ++lastId
        rows.add(task.copy(id = id))
        return id
    }

    override suspend fun getAll(): List<DownloadTask> = rows.sortedByDescending { it.createTime }

    override suspend fun getActiveTasks(): List<DownloadTask> = rows.filter { it.isActive }.sortedByDescending { it.createTime }

    override suspend fun getCompletedTasks(): List<DownloadTask> =
        rows.filter { it.status == DownloadStatus.COMPLETED }.sortedByDescending { it.createTime }

    override suspend fun getDownloadingCount(): Int = rows.count { it.status == DownloadStatus.DOWNLOADING }

    override suspend fun getCompletedCount(): Int = rows.count { it.status == DownloadStatus.COMPLETED }

    override suspend fun getActiveCount(): Int = rows.count { it.isActive }

    override suspend fun getByStatus(status: String): List<DownloadTask> =
        rows.filter { it.status == status }.sortedByDescending { it.createTime }

    override suspend fun deleteById(id: Long) {
        rows.removeAll { it.id == id }
    }

    override suspend fun deleteCompleted() {
        rows.removeAll { it.status == DownloadStatus.COMPLETED }
    }

    override suspend fun updateStatus(id: Long, status: String, errorMessage: String?) {
        val index = rows.indexOfFirst { it.id == id }
        if (index >= 0) rows[index] = rows[index].copy(status = status, errorMessage = errorMessage)
    }

    override suspend fun updateProgress(id: Long, downloadedSize: Long, fileSize: Long) {
        val index = rows.indexOfFirst { it.id == id }
        if (index >= 0) rows[index] = rows[index].copy(downloadedSize = downloadedSize, fileSize = fileSize)
    }

    override suspend fun updateSpeed(id: Long, speed: Long) {
        val index = rows.indexOfFirst { it.id == id }
        if (index >= 0) rows[index] = rows[index].copy(downloadSpeed = speed)
    }
}

/** 内存落盘：只记录调用（真落盘由 `:data` / `:desktop` 两侧的测试验）。 */
private class FakeFileSystem : DownloadFileSystem {

    override val downloadRoot: String = "/fake/ClassReplay"

    val directories = mutableListOf<String>()
    val sizes = mutableMapOf<String, Long>()
    val deleted = mutableListOf<String>()

    override suspend fun ensureDirectory(path: String): Boolean {
        directories.add(path)
        return true
    }

    override suspend fun fileSize(path: String): Long = sizes[path] ?: 0L

    override suspend fun deleteFile(path: String): Boolean {
        deleted.add(path)
        sizes.remove(path)
        return true
    }
}

/** 假执行器：[behavior] 决定这一发怎么结束（正常完成 / 抛异常 / 挂起等取消）。 */
private class FakeEngine(
    private val behavior: suspend (DownloadRequest, suspend (Long, Long) -> Unit) -> DownloadOutcome,
) : DownloadEngine {

    val requests = mutableListOf<DownloadRequest>()

    override suspend fun download(
        request: DownloadRequest,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): DownloadOutcome {
        requests.add(request)
        return behavior(request, onProgress)
    }
}
