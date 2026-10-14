package com.xjtu.toolbox.desktop

import com.xjtu.toolbox.FakeCampusProxy
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.desktop.media.DesktopDownloadTaskStore
import com.xjtu.toolbox.media.DownloadFakeUpstream
import com.xjtu.toolbox.media.DownloadItem
import com.xjtu.toolbox.media.DownloadManager
import com.xjtu.toolbox.media.DownloadStatus
import com.xjtu.toolbox.media.DownloadTask
import com.xjtu.toolbox.media.JvmDownloadEngine
import com.xjtu.toolbox.media.JvmDownloadFileSystem
import com.xjtu.toolbox.platform.dataRootOverride
import com.xjtu.toolbox.platform.keyValueStore
import com.xjtu.toolbox.platform.wipeSecureStore
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

/**
 * 下载管理在**桌面端的装配验收** —— 与 `DesktopAuthJiaocai1JvmTest` 同一批的宿主能力，也是这一轮
 * 唯一「端到端」的那条：**真发 HTTP、真把字节落到磁盘**。
 *
 * 上游是 `:testkit` 的 [DownloadFakeUpstream]（`video-download.xjtu.edu.cn`，明文 http，
 * 一个几十 KB 的假 mp4），走 `FakeCampusProxy` 那个按 host 分派的本地代理 ⇒ **URL 一个字符不改**，
 * 下载用的是 `:data` 里那份真实现（`JvmDownloadEngine`，同一个 `HttpClients.base`）。
 *
 * 三条用例各钉一件事：
 *  1. 全新下载：状态 `pending → completed`、文件落盘且**与夹具逐字节相同**、记录真的写进那份 0600 的 JSON；
 *  2. 断点续传：本地已有前半段时那一枪带 `Range`、服务器回 206，最终字节仍是整份（不是叠加）；
 *  3. 存储语义：状态/进度/速度的窄更新、创建时间倒序、四个计数口径（与 Android 那几条 SQL 同义）。
 *
 * ⚠️ 落盘位置：`dataRootOverride` 指到临时目录 ⇒ 下载根是 `<临时根>/Downloads/ClassReplay`，
 * **绝不碰用户真实的 `~/Downloads`**（这条断言写在用例里，而不是靠「我记得」）。
 */
class DesktopDownloadsJvmTest {

    private lateinit var fake: FakeCampusProxy
    private lateinit var root: File

    /**
     * 起假上游 + 把落盘存储指到临时目录。
     *
     * `installFakeUpstreams()` 必须在碰 `HttpClients` 之前调（`JvmDownloadEngine` 那个客户端是
     * `HttpClients.base` 派生的、进程级 `by lazy`，它会把当时的 selector 抄走）—— 与
     * `DesktopAuthLmsJvmTest` / `RenderScreens` 是同一条口径。
     */
    private fun withFakeCampus(block: () -> Unit) {
        root = Files.createTempDirectory("xjtu-desktop-download-test").toFile()
        dataRootOverride = root
        // S7b：队列的端内名带账号后缀 ⇒ 先把命名空间钉成「未登录」（后缀 `default`，无前导下划线）
        AccountContext.activeAccountId = null
        wipeSecureStore("download_tasksdefault")
        FakeCampusProxy.installFakeUpstreams()
        fake = FakeCampusProxy(casEnabled = false)
        fake.start()
        try {
            block()
        } finally {
            fake.close()
            dataRootOverride = null
            AccountContext.activeAccountId = null
        }
    }

    /** 与 `DesktopDownloads.manager` 同一套装配（只是每次测试自己建一个，作用域可回收）。 */
    private fun newManager(): DownloadManager = DownloadManager(
        store = DesktopDownloadTaskStore(),
        engine = JvmDownloadEngine(),
        fileSystem = JvmDownloadFileSystem(),
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    )

    private fun enqueueOne(manager: DownloadManager): Long = runBlocking {
        manager.enqueueDownloads(
            courseName = DownloadFakeUpstream.COURSE_NAME,
            activityTitle = DownloadFakeUpstream.ACTIVITY_TITLE,
            activityId = 7,
            videos = listOf(DownloadItem("instructor", DownloadFakeUpstream.INSTRUCTOR_URL)),
        ).single()
    }

    /** 等任务离开 pending/downloading（真下载在自己的 IO 作用域上跑，这里只轮询状态）。 */
    private fun awaitFinished(manager: DownloadManager, id: Long, timeoutMs: Long = 20_000): DownloadTask {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val task = runBlocking { manager.getAllTasks() }.firstOrNull { it.id == id }
            if (task != null && task.status != DownloadStatus.PENDING && task.status != DownloadStatus.DOWNLOADING) {
                return task
            }
            Thread.sleep(50)
        }
        error("等不到任务 $id 跑完（超时 ${timeoutMs}ms）")
    }

    @Test
    fun `真下载一个夹具文件并落盘：状态走到 completed，字节与夹具逐字节相同`() = withFakeCampus {
        val manager = newManager()
        val id = enqueueOne(manager)

        val task = awaitFinished(manager, id)
        assertEquals(DownloadStatus.COMPLETED, task.status, "夹具那条直链应当真下得下来")

        // 落盘位置：本端下载根 + 课程目录 + 「活动名_机位.mp4」（与 Android 命名口径逐字一致）
        assertTrue(
            manager.videoDirPath.startsWith(root.absolutePath),
            "下载根要落在临时数据根下（绝不碰用户真实的 ~/Downloads）：${manager.videoDirPath}",
        )
        assertEquals(
            "${manager.videoDirPath}/${DownloadFakeUpstream.COURSE_NAME}/" +
                "${DownloadFakeUpstream.ACTIVITY_TITLE}_教师直播.mp4",
            task.filePath,
        )

        val file = File(task.filePath)
        assertTrue(file.isFile, "文件要真的落到磁盘：${task.filePath}")
        assertContentEquals(DownloadFakeUpstream.BYTES, file.readBytes(), "磁盘上的字节要与夹具逐字节相同")
        assertEquals(DownloadFakeUpstream.SIZE_BYTES.toLong(), task.downloadedSize)
        assertEquals(DownloadFakeUpstream.SIZE_BYTES.toLong(), task.fileSize, "总大小来自响应的 Content-Length")

        assertTrue(fake.download.requests.get() >= 1, "假上游那条直链真被请求过")
        assertEquals(0L, fake.download.rangeRequests.get(), "全新下载不该带 Range")

        // 记录真的落在那份 0600 的 JSON 里（不是只在内存里）：这是「桌面端没有 Room」的替代物
        val stored = File(root, "download_tasksdefault.properties")
        assertTrue(stored.isFile, "下载任务要落在 ${stored.path}")
        val raw = stored.readText()
        assertTrue(raw.contains("\"activityTitle\""), "落盘的 JSON 里要有任务记录：${raw.take(120)}")
        assertTrue(raw.contains("\"filePath\""), "而且路径与体积也要在里面（屏与续传都靠它）")
        // ⚠️ `java.util.Properties` 会把非 ASCII 字符转义成 unicode 转义序列（与 cookie / 凭据
        // 同一套落盘口径），所以中文标题要按**转义后**的形状找 —— 这不是「没写进去」。
        assertTrue(
            raw.contains(propertiesEscaped(DownloadFakeUpstream.ACTIVITY_TITLE)),
            "落盘的 JSON 里要有这场活动：${raw.take(200)}",
        )
    }

    @Test
    fun `断点续传：本地已有前半段时带 Range 接着下，最终字节仍是整份`() = withFakeCampus {
        val manager = newManager()
        // 先把「上次下到一半」的文件摆好（路径就是状态机会算出来的那一个）
        val dir = File(manager.videoDirPath, DownloadFakeUpstream.COURSE_NAME)
        assertTrue(dir.mkdirs() || dir.isDirectory, "课程目录要能建出来：$dir")
        val target = File(dir, "${DownloadFakeUpstream.ACTIVITY_TITLE}_教师直播.mp4")
        val head = 12_345
        assertTrue(head < DownloadFakeUpstream.SIZE_BYTES)
        target.writeBytes(DownloadFakeUpstream.BYTES.copyOfRange(0, head))

        val id = enqueueOne(manager)
        val task = awaitFinished(manager, id)

        assertEquals(DownloadStatus.COMPLETED, task.status, "续传应当跑完")
        assertContentEquals(
            DownloadFakeUpstream.BYTES,
            target.readBytes(),
            "续传后的文件必须是整份夹具（旧的那半段要接上，不是叠加）",
        )
        assertEquals(DownloadFakeUpstream.SIZE_BYTES.toLong(), task.downloadedSize)
        assertEquals(DownloadFakeUpstream.SIZE_BYTES.toLong(), task.fileSize, "总大小来自 Content-Range")
        assertTrue(fake.download.rangeRequests.get() >= 1, "续传那一枪要带 Range（夹具记着这件事）")
    }

    @Test
    fun `存储：窄更新、创建时间倒序与四个计数口径（与 Android 那几条 SQL 同义）`() {
        val store = DesktopDownloadTaskStore(keyValueStore("download_tasks_unit_test"))
        runBlocking {
            val older = store.insert(task("先排的", createTime = 100L))
            val newer = store.insert(task("后排的", createTime = 200L))

            assertEquals(
                listOf(200L, 100L),
                store.getAll().map { it.createTime },
                "与 Android 那条 ORDER BY createTime DESC 同口径",
            )

            store.updateStatus(older, DownloadStatus.COMPLETED)
            assertEquals(1, store.getCompletedCount())
            assertEquals(1, store.getActiveCount(), "没完成的那条仍是活跃（pending）")
            assertEquals(0, store.getDownloadingCount())

            store.updateProgress(newer, downloadedSize = 5, fileSize = 10)
            store.updateSpeed(newer, 123)
            val updated = store.getAll().single { it.id == newer }
            assertEquals(5L, updated.downloadedSize)
            assertEquals(10L, updated.fileSize)
            assertEquals(123L, updated.downloadSpeed)
            assertEquals(0.5f, updated.progress, "进度是模型上的派生值")

            store.updateStatus(newer, DownloadStatus.FAILED, "示例失败原因")
            val failed = store.getByStatus(DownloadStatus.FAILED).single()
            assertEquals("示例失败原因", failed.errorMessage)
            assertEquals(0, store.getActiveCount(), "失败的不算活跃")

            // 同一个存储名建出来的第二份实例要把 JSON 读回来（落盘往返，不是只写在内存里）
            val reopened = DesktopDownloadTaskStore(keyValueStore("download_tasks_unit_test"))
            assertEquals(2, reopened.getAll().size)
            assertEquals("示例失败原因", reopened.getByStatus(DownloadStatus.FAILED).single().errorMessage)

            store.deleteCompleted()
            assertEquals(listOf(newer), store.getAll().map { it.id })
            store.deleteById(newer)
            assertTrue(store.getAll().isEmpty())
        }
    }

/** `java.util.Properties` 落盘时对非 ASCII 字符的转义形状（反斜杠 + `u` + 四位大写十六进制）。 */
private fun propertiesEscaped(text: String): String = text.map { ch ->
    if (ch.code in 0x20..0x7E) ch.toString() else "\\u%04X".format(ch.code)
}.joinToString("")

private fun task(title: String, createTime: Long): DownloadTask = DownloadTask(
        activityId = 1,
        courseName = "示例课（下载夹具）",
        activityTitle = title,
        cameraType = "instructor",
        videoUrl = DownloadFakeUpstream.INSTRUCTOR_URL,
        audioSource = "instructor",
        filePath = "/tmp/$title.mp4",
        fileSize = -1,
        downloadedSize = 0,
        status = DownloadStatus.PENDING,
        createTime = createTime,
    )
}
