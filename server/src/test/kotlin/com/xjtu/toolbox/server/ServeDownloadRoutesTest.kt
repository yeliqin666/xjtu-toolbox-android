package com.xjtu.toolbox.server

import com.xjtu.toolbox.library.LibraryFakeUpstream
import com.xjtu.toolbox.media.DownloadEngine
import com.xjtu.toolbox.media.DownloadFileSystem
import com.xjtu.toolbox.media.DownloadManager
import com.xjtu.toolbox.media.DownloadOutcome
import com.xjtu.toolbox.media.DownloadRequest
import com.xjtu.toolbox.media.DownloadStatus
import com.xjtu.toolbox.media.DownloadTask
import com.xjtu.toolbox.media.DownloadTaskStore
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject

/**
 * 下载那一族的契约测试（`docs/api-contract.md` §5.13）。
 *
 * ⚠️ 注入一份**可控的** [DownloadManager]：生产装配里的 `JvmDownloadEngine` 会真发 HTTP、
 * `JvmDownloadFileSystem` 会往操作者的 `~/Downloads` 写文件 —— 契约测试不该碰这两样。
 * 引擎用一个闸门卡住，好让「下载中」那一档能被钉住（进度那一条是屏上轮询的）。
 */
class ServeDownloadRoutesTest {

    private var started: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    @AfterTest
    fun stopServer() {
        started?.stop(gracePeriodMillis = 100, timeoutMillis = 500)
        started = null
    }

    private fun serve(session: ServeSession, token: String, manager: DownloadManager): EndpointHarness {
        val (server, http) = startEndpointServer(token) {
            sessionRoutes(session, token)
            downloadRoutes(session, manager)
        }
        started = server
        return http
    }

    private fun newManager(root: File, engine: DownloadEngine = GatedEngine()) = DownloadManager(
        store = FakeTaskStore(),
        engine = engine,
        fileSystem = TempFileSystem(root),
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    )

    // ── 进门条件 ───────────────────────────────────────────────────────

    @Test
    fun `七条都要求登录：未登录一律 401 指路，且不建任何任务`() = withFakeProxy {
        val token = AccessToken.newToken()
        val session = ServeSession()
        val root = Files.createTempDirectory("serve-download-anon").toFile()
        val http = serve(session, token, newManager(root))

        val anonymous = listOf(
            http.get(DOWNLOAD_PATH),
            http.get("$DOWNLOAD_PATH/$DOWNLOAD_PROGRESS"),
            http.postJson(DOWNLOAD_PATH, """{"url":"https://example.com/a.mp4"}"""),
            http.delete("$DOWNLOAD_PATH/1"),
            http.postJson("$DOWNLOAD_PATH/1/$DOWNLOAD_PAUSE", ""),
            http.postJson("$DOWNLOAD_PATH/1/$DOWNLOAD_RESUME", ""),
            http.postJson("$DOWNLOAD_PATH/1/$DOWNLOAD_CANCEL", ""),
        )
        for (response in anonymous) {
            assertEquals(401, response.statusCode(), response.request().uri().path)
            assertEquals(ApiErrors.UNAUTHORIZED, envelopeCode(response))
            assertTrue(envelopeMessage(response).orEmpty().contains("登录"), "401 要指路「先登录」")
        }

        // 未登录那一发**没有**建任务：登录之后列表是空的
        assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())
        assertEquals(0, envelopeData(http.get(DOWNLOAD_PATH)).optInt("count"))
    }

    // ── 列 / 建 / 进度 / 删 ────────────────────────────────────────────

    @Test
    fun `新建一条走完整状态机：下载中那一档在 progress 里，完成后在列表里`() = withFakeProxy {
        val token = AccessToken.newToken()
        val session = ServeSession()
        val root = Files.createTempDirectory("serve-download-flow").toFile()
        val engine = GatedEngine()
        val http = serve(session, token, newManager(root, engine))
        assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

        // ── 空队列 + 目录那一格 ──
        val blank = http.get(DOWNLOAD_PATH)
        assertEquals(200, blank.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(blank))
        assertNoIdentity(blank.body(), "GET $DOWNLOAD_PATH", IDENTITY)
        val blankData = envelopeData(blank)
        assertEquals(setOf("count", "downloading", "completed", "active", "dir", "tasks"), blankData.keys)
        assertEquals(0, blankData.optInt("count"))
        assertEquals(0, blankData.optInt("downloading"))
        assertEquals(0, blankData.optInt("completed"))
        assertEquals(0, blankData.optInt("active"))
        val dir = blankData.getValue("dir") as JsonObject
        assertEquals(setOf("location", "notes"), dir.keys)
        assertEquals(root.absolutePath, dir.optString("location"), "目录那一格说的是**跑 serve 那台机器**的目录")
        assertEquals(3, dir.arrOf("notes").size, "三条说明（含「不是浏览器所在的设备」那一条）")
        assertTrue(
            dir.arrOf("notes").any { it.toString().contains("serve") },
            "说明里要说清落在哪台机器上：${dir.arrOf("notes")}",
        )

        // ── 新建一条 ──
        val url = "https://lms.example.com/replay/1.mp4"
        val enqueued = http.postJson(
            DOWNLOAD_PATH,
            """{"url":"$url","cameraType":"instructor","courseName":"示例课程",
                "activityTitle":"示例回放","activityId":7}""".trimIndent(),
        )
        assertEquals(200, enqueued.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(enqueued))
        val ids = envelopeData(enqueued).arrOf("ids")
        assertEquals(1, ids.size)
        val id = ids[0].toString().toLong()
        assertTrue(id > 0, "回的是登记表里的自增主键：$id")

        // 引擎真被叫起来了（闸门卡在那里 ⇒ 任务停在「下载中」）
        runBlocking { withTimeout(10_000) { engine.started.await() } }
        assertEquals(url, engine.lastUrl, "打给引擎的就是请求里那个直链")

        // ── 进度那一条（屏上轮询它）──
        val progress = http.get("$DOWNLOAD_PATH/$DOWNLOAD_PROGRESS")
        assertEquals(200, progress.statusCode())
        assertNoIdentity(progress.body(), "GET $DOWNLOAD_PATH/$DOWNLOAD_PROGRESS", IDENTITY)
        val progressData = envelopeData(progress)
        assertEquals(setOf("downloading", "active", "completed", "tasks"), progressData.keys)
        assertEquals(1, progressData.optInt("downloading"))
        assertEquals(1, progressData.optInt("active"))
        assertEquals(0, progressData.optInt("completed"))
        val row = progressData.arrOf("tasks")[0] as JsonObject
        assertEquals(
            setOf("id", "title", "downloadedBytes", "totalBytes", "progress", "status", "statusLabel", "errorMessage"),
            row.keys,
        )
        assertEquals(id, row.optLong("id"))
        assertEquals("示例回放", row.optString("title"))
        assertEquals(50L, row.optLong("downloadedBytes"), "引擎报过一次 50/100")
        assertEquals(100L, row.optLong("totalBytes"))
        assertEquals(DownloadStatus.DOWNLOADING, row.optString("status"))
        assertEquals("下载中", row.optString("statusLabel"), "状态标签是中文短句（屏上直接用）")

        // ── 列表那一条（全表 + 统计）──
        val listed = http.get(DOWNLOAD_PATH)
        val listedData = envelopeData(listed)
        assertEquals(1, listedData.optInt("count"))
        assertEquals(1, listedData.optInt("downloading"))
        assertEquals(1, listedData.optInt("active"))
        assertEquals(0, listedData.optInt("completed"))
        val task = listedData.arrOf("tasks")[0] as JsonObject
        assertEquals(
            setOf(
                "id", "title", "courseName", "cameraType", "filePath", "fileSize",
                "downloadedBytes", "progress", "status", "statusLabel", "createTime", "errorMessage",
            ),
            task.keys,
            "一条任务在屏上的十二格",
        )
        assertEquals("示例课程", task.optString("courseName"))
        assertEquals("instructor", task.optString("cameraType"))
        assertTrue(
            task.optString("filePath").orEmpty().startsWith(root.absolutePath),
            "落点在那台机器的下载目录下：${task.optString("filePath")}",
        )
        assertNotNull(task.optString("createTime"), "创建时间是 ISO-8601（契约 §4）")
        assertTrue(task.optString("createTime").orEmpty().contains("+08:00"), "带时区：${task.optString("createTime")}")

        // ── 放行闸门 ⇒ 完成 ──
        engine.release.complete(Unit)
        val completed = awaitCompleted(http, id)
        assertEquals(DownloadStatus.COMPLETED, completed.optString("status"))
        assertEquals("已完成", completed.optString("statusLabel"))
        assertEquals(100L, completed.optLong("downloadedBytes"))
        assertEquals(0, envelopeData(http.get(DOWNLOAD_PATH)).optInt("active"))
        assertEquals(1, envelopeData(http.get(DOWNLOAD_PATH)).optInt("completed"))

        // 完成之后不再出现在「进度」那一屏（它只列活跃任务）
        assertEquals(0, envelopeData(http.get("$DOWNLOAD_PATH/$DOWNLOAD_PROGRESS")).arrOf("tasks").size)

        // ── 删一条：默认连文件一起删 ──
        val removed = http.delete("$DOWNLOAD_PATH/$id")
        assertEquals(200, removed.statusCode())
        val removedData = envelopeData(removed)
        assertEquals(setOf("id", "deletedFile"), removedData.keys)
        assertEquals(id, removedData.optLong("id"))
        assertEquals(true, removedData.optBoolean("deletedFile"))
        assertEquals(0, envelopeData(http.get(DOWNLOAD_PATH)).optInt("count"))
    }

    @Test
    fun `deleteFile=false 只删记录；删一个不在表里的 id 也是空操作`() = withFakeProxy {
        val token = AccessToken.newToken()
        val session = ServeSession()
        val root = Files.createTempDirectory("serve-download-delete").toFile()
        val engine = GatedEngine()
        val http = serve(session, token, newManager(root, engine))
        assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

        val id = envelopeData(
            http.postJson(DOWNLOAD_PATH, """{"url":"https://lms.example.com/replay/2.mp4"}"""),
        ).arrOf("ids")[0].toString().toLong()
        runBlocking { withTimeout(10_000) { engine.started.await() } }

        // ⚠️ 还在下载中那一条：`:data` 的 `deleteTask` 不删它 ⇒ 本层如实回 409（不谎报成功）
        val busy = http.delete("$DOWNLOAD_PATH/$id?deleteFile=false")
        assertEquals(409, busy.statusCode())
        assertEquals(ApiErrors.ACTION_REJECTED, envelopeCode(busy))
        assertTrue(envelopeMessage(busy).orEmpty().contains("下载中"), "409 要说清为什么：${envelopeMessage(busy)}")
        assertEquals(1, envelopeData(http.get(DOWNLOAD_PATH)).optInt("count"), "那一条确实还在")

        // 放行闸门 → 跑完 → 这时候才删得掉（deleteFile=false 只删记录）
        engine.release.complete(Unit)
        awaitCompleted(http, id)
        val kept = http.delete("$DOWNLOAD_PATH/$id?deleteFile=false")
        assertEquals(200, kept.statusCode())
        assertEquals(false, envelopeData(kept).optBoolean("deletedFile"), "带 deleteFile=false 时如实回 false")
        assertEquals(0, envelopeData(http.get(DOWNLOAD_PATH)).optInt("count"))

        // 再删一次（这次不在表里）：仍然是 200 + 空操作（幂等），不是 404
        val again = http.delete("$DOWNLOAD_PATH/$id")
        assertEquals(200, again.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(again))

        // id 形状不对 ⇒ 400
        for (bad in listOf("abc", "1.5", "%20")) {
            val response = http.delete("$DOWNLOAD_PATH/$bad")
            assertEquals(400, response.statusCode(), bad)
            assertEquals(ApiErrors.BAD_REQUEST, envelopeCode(response), bad)
            assertTrue(envelopeMessage(response).orEmpty().contains("id"), "400 要说出是哪个参数：$bad")
        }
    }

    @Test
    fun `请求体与直链形状：空 url 与 m3u8 都是 400，且一条都不建`() = withFakeProxy {
        val token = AccessToken.newToken()
        val session = ServeSession()
        val root = Files.createTempDirectory("serve-download-body").toFile()
        val engine = GatedEngine()
        val http = serve(session, token, newManager(root, engine))
        assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

        // 请求体不是 JSON / 缺 url / url 是空白
        for (body in listOf("not json", "{}", """{"url":""}""", """{"url":"   "}""")) {
            val response = http.postJson(DOWNLOAD_PATH, body)
            assertEquals(400, response.statusCode(), body)
            assertEquals(ApiErrors.BAD_REQUEST, envelopeCode(response), body)
            assertTrue(envelopeMessage(response).orEmpty().contains("url"), "400 要说出缺什么：$body")
        }

        // HLS 在这一层就拒（`:data` 的引擎只支持可直接 GET 的直链）
        val m3u8 = http.postJson(DOWNLOAD_PATH, """{"url":"https://lms.example.com/replay/index.m3u8"}""")
        assertEquals(400, m3u8.statusCode())
        assertEquals(ApiErrors.BAD_REQUEST, envelopeCode(m3u8))
        assertTrue(envelopeMessage(m3u8).orEmpty().contains("m3u8"), "400 要说清是 HLS 不支持：${envelopeMessage(m3u8)}")

        // 一条都没建、引擎一次都没被叫
        assertEquals(0, envelopeData(http.get(DOWNLOAD_PATH)).optInt("count"))
        assertEquals(0, engine.calls.get(), "400 那几枪都不该到引擎")

        // 正主：一条能建的直链 ⇒ 引擎真被叫起来
        val ok = http.postJson(DOWNLOAD_PATH, """{"url":"https://lms.example.com/replay/3.mp4"}""")
        assertEquals(200, ok.statusCode())
        runBlocking { withTimeout(10_000) { engine.started.await() } }
        assertEquals(1, engine.calls.get())
    }

    @Test
    fun `三个动作动词：暂停到恢复到取消，目标不在表里或状态不对时 409`() = withFakeProxy {
        val token = AccessToken.newToken()
        val session = ServeSession()
        val root = Files.createTempDirectory("serve-download-verbs").toFile()
        val engine = GatedEngine()
        val http = serve(session, token, newManager(root, engine))
        assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

        // id 形状不对 ⇒ 400（三个动词都一样）
        for (verb in listOf(DOWNLOAD_PAUSE, DOWNLOAD_RESUME, DOWNLOAD_CANCEL)) {
            val response = http.postJson("$DOWNLOAD_PATH/abc/$verb", "")
            assertEquals(400, response.statusCode(), verb)
            assertTrue(envelopeMessage(response).orEmpty().contains("id"), "400 要说出是哪个参数：$verb")
        }

        // 目标不在表里 ⇒ 409（不谎报成功）
        for (verb in listOf(DOWNLOAD_PAUSE, DOWNLOAD_RESUME, DOWNLOAD_CANCEL)) {
            val response = http.postJson("$DOWNLOAD_PATH/999/$verb", "")
            assertEquals(409, response.statusCode(), verb)
            assertEquals(ApiErrors.ACTION_REJECTED, envelopeCode(response), verb)
            assertTrue(envelopeMessage(response).orEmpty().contains("没有这条"), "409 要说清为什么：$verb")
        }

        val id = envelopeData(
            http.postJson(DOWNLOAD_PATH, """{"url":"https://lms.example.com/replay/4.mp4"}"""),
        ).arrOf("ids")[0].toString().toLong()
        runBlocking { withTimeout(10_000) { engine.started.await() } }

        // 正在下载：不能恢复（状态不对），但能暂停
        val resumeTooEarly = http.postJson("$DOWNLOAD_PATH/$id/$DOWNLOAD_RESUME", "")
        assertEquals(409, resumeTooEarly.statusCode())
        assertTrue(envelopeMessage(resumeTooEarly).orEmpty().contains("下载中"), "409 要写出当前状态：${envelopeMessage(resumeTooEarly)}")

        val paused = http.postJson("$DOWNLOAD_PATH/$id/$DOWNLOAD_PAUSE", "")
        assertEquals(200, paused.statusCode())
        val pausedData = envelopeData(paused)
        assertEquals(setOf("ok", "id"), pausedData.keys, "只回 ok 与 id（不回状态，见 KDoc）")
        assertEquals(true, pausedData.optBoolean("ok"))
        assertEquals(id, pausedData.optLong("id"))
        assertEquals(DownloadStatus.PAUSED, taskRow(http, id).optString("status"))
        assertEquals("已暂停", taskRow(http, id).optString("statusLabel"))
        assertEquals(0, envelopeData(http.get(DOWNLOAD_PATH)).optInt("downloading"))
        assertEquals(1, envelopeData(http.get(DOWNLOAD_PATH)).optInt("active"), "暂停中仍是活跃任务")

        // 已暂停：不能再暂停（状态不对）
        val pauseAgain = http.postJson("$DOWNLOAD_PATH/$id/$DOWNLOAD_PAUSE", "")
        assertEquals(409, pauseAgain.statusCode())
        assertTrue(envelopeMessage(pauseAgain).orEmpty().contains("已暂停"), "409 要写出当前状态")

        // 恢复 ⇒ 引擎被再叫一次（`:data` 的 resumeDownload 只丢一个协程 ⇒ 本层不回状态，屏上轮询）
        val resumed = http.postJson("$DOWNLOAD_PATH/$id/$DOWNLOAD_RESUME", "")
        assertEquals(200, resumed.statusCode())
        assertEquals(true, envelopeData(resumed).optBoolean("ok"))
        assertEquals(DownloadStatus.DOWNLOADING, awaitStatus(http, id, DownloadStatus.DOWNLOADING).optString("status"))
        assertTrue(engine.calls.get() >= 2, "恢复之后引擎又跑了一遍（实际 ${engine.calls.get()}）")

        // 取消：停掉在跑的那一发 + 删文件 + 删记录 ⇒ 列表里那一条没了
        val cancelled = http.postJson("$DOWNLOAD_PATH/$id/$DOWNLOAD_CANCEL", "")
        assertEquals(200, cancelled.statusCode())
        assertEquals(true, envelopeData(cancelled).optBoolean("ok"))
        assertEquals(0, envelopeData(http.get(DOWNLOAD_PATH)).optInt("count"), "取消会把那一条从表里删掉")

        // 取消之后它就不在了 ⇒ 再动它就是 409
        assertEquals(409, http.postJson("$DOWNLOAD_PATH/$id/$DOWNLOAD_PAUSE", "").statusCode())
    }

    // ── 脚手架 ─────────────────────────────────────────────────────────

    /** 列表里那一条（不在就是断言失败 —— 调用方都刚建过它）。 */
    private fun taskRow(http: EndpointHarness, id: Long): JsonObject =
        envelopeData(http.get(DOWNLOAD_PATH)).arrOf("tasks")
            .map { it as JsonObject }
            .firstOrNull { it.optLong("id") == id }
            ?: throw AssertionError("列表里找不到 id=$id 那一条")

    /** 轮询列表直到那条任务变成 [status]（恢复是异步的，见那一条的 KDoc）。 */
    private fun awaitStatus(http: EndpointHarness, id: Long, status: String): JsonObject {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val row = envelopeData(http.get(DOWNLOAD_PATH)).arrOf("tasks")
                .map { it as JsonObject }
                .firstOrNull { it.optLong("id") == id }
            if (row != null && row.optString("status") == status) return row
            Thread.sleep(20)
        }
        throw AssertionError("等这条任务变成 $status 超时了（id=$id）")
    }

    /** 轮询列表直到那条任务不再活跃（闸门放行之后引擎立刻回完成）。 */
    private fun awaitCompleted(http: EndpointHarness, id: Long): JsonObject = awaitStatus(http, id, DownloadStatus.COMPLETED)

    private companion object {
        val LOGIN_BODY =
            """{"username":"${LibraryFakeUpstream.USERNAME}","password":"${LibraryFakeUpstream.PASSWORD}"}"""

        val IDENTITY = listOf("username", "studentId", "学号", "姓名", LibraryFakeUpstream.USERNAME)
    }
}

/** 内存版登记表（契约测试只需要状态机那一半）。 */
private class FakeTaskStore : DownloadTaskStore {

    private val rows = mutableListOf<DownloadTask>()
    private var lastId = 0L

    private fun byCreateTimeDesc() = rows.sortedByDescending { it.createTime }

    override suspend fun insert(task: DownloadTask): Long {
        val id = ++lastId
        rows.add(task.copy(id = id))
        return id
    }

    override suspend fun getAll(): List<DownloadTask> = byCreateTimeDesc()

    override suspend fun getActiveTasks(): List<DownloadTask> = byCreateTimeDesc().filter { it.isActive }

    override suspend fun getCompletedTasks(): List<DownloadTask> =
        byCreateTimeDesc().filter { it.status == DownloadStatus.COMPLETED }

    override suspend fun getDownloadingCount(): Int = rows.count { it.status == DownloadStatus.DOWNLOADING }

    override suspend fun getCompletedCount(): Int = rows.count { it.status == DownloadStatus.COMPLETED }

    override suspend fun getActiveCount(): Int = rows.count { it.isActive }

    override suspend fun getByStatus(status: String): List<DownloadTask> =
        byCreateTimeDesc().filter { it.status == status }

    override suspend fun deleteById(id: Long) {
        rows.removeAll { it.id == id }
    }

    override suspend fun deleteCompleted() {
        rows.removeAll { it.status == DownloadStatus.COMPLETED }
    }

    override suspend fun updateStatus(id: Long, status: String, errorMessage: String?) {
        replace(id) { it.copy(status = status, errorMessage = errorMessage) }
    }

    override suspend fun updateProgress(id: Long, downloadedSize: Long, fileSize: Long) {
        replace(id) { it.copy(downloadedSize = downloadedSize, fileSize = fileSize) }
    }

    override suspend fun updateSpeed(id: Long, speed: Long) {
        replace(id) { it.copy(downloadSpeed = speed) }
    }

    private fun replace(id: Long, transform: (DownloadTask) -> DownloadTask) {
        val index = rows.indexOfFirst { it.id == id }
        if (index >= 0) rows[index] = transform(rows[index])
    }
}

/** 卡在闸门上的引擎：报一次 50/100 之后停住，等测试放行（好钉住「下载中」那一档）。 */
private class GatedEngine : DownloadEngine {

    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val calls = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile
    var lastUrl: String? = null
        private set

    override suspend fun download(
        request: DownloadRequest,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): DownloadOutcome {
        calls.incrementAndGet()
        lastUrl = request.url
        onProgress(50, 100)
        started.complete(Unit)
        release.await()
        onProgress(100, 100)
        return DownloadOutcome(downloadedBytes = 100, totalBytes = 100)
    }
}

/** 临时目录版的落盘端口（绝不碰操作者真正的下载目录）。 */
private class TempFileSystem(private val root: File) : DownloadFileSystem {

    override val downloadRoot: String = root.absolutePath

    override suspend fun ensureDirectory(path: String): Boolean {
        val dir = File(path)
        return dir.isDirectory || dir.mkdirs()
    }

    override suspend fun fileSize(path: String): Long = File(path).takeIf { it.isFile }?.length() ?: 0L

    override suspend fun deleteFile(path: String): Boolean {
        val file = File(path)
        return !file.exists() || file.delete()
    }
}
