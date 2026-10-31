package com.xjtu.toolbox.media

import com.xjtu.toolbox.core.net.ApiMode
import com.xjtu.toolbox.core.net.createToolboxClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** serve 契约 §5.13 的客户端那一侧（`ServeDownloadsApi`）：形状、动作、拒绕那一档。 */
class ServeDownloadsApiTest {

    /** 一条发出去的请求：路径 + 方法 + 完整 URL（判参数 / 方法有没有对靠它）。 */
    private class Call(val path: String, val method: HttpMethod, val url: String, val body: String?)

    private fun engineFor(
        vararg bodies: Pair<String, String>,
        status: HttpStatusCode = HttpStatusCode.OK,
        calls: MutableList<Call>? = null,
    ): MockEngine = MockEngine { request ->
        val body = (request.body as? TextContent)?.text.orEmpty()
        calls?.add(Call(request.url.encodedPath, request.method, request.url.toString(), body.ifBlank { null }))
        val payload = bodies.toMap().entries.firstOrNull { request.url.encodedPath.endsWith(it.key) }?.value
            ?: error("测试没有为 ${request.url.encodedPath} 准备响应")
        respond(
            content = payload,
            status = status,
            headers = headersOf(HttpHeaders.ContentType to listOf("application/json")),
        )
    }

    private fun api(engine: MockEngine) = ServeDownloadsApi(
        client = createToolboxClient(engine = engine),
        baseUrl = "",
        mode = ApiMode.SERVE,
    )

    /** `GET /api/downloads` 的形状（契约 §5.13）：任务全表 + 三个计数 + 目录那一格。 */
    private val listBody = """
        {"code":0,"data":{"count":2,"downloading":1,"completed":1,"active":1,
         "dir":{"location":"/home/示例/Downloads","notes":["下载落在**运行 serve 的那台机器**上（不是浏览器所在的设备）",
                 "浏览器只是界面：队列、进度、删除都是 serve 在真的做","只支持可直接 GET 的直链；HLS（m3u8）不支持"]},
         "tasks":[
          {"id":7,"title":"示例回放","courseName":"示例课程","cameraType":"instructor",
           "filePath":"/home/示例/Downloads/示例课程/示例回放_教师直播.mp4","fileSize":2048,
           "downloadedBytes":1024,"progress":0.5,"status":"downloading","statusLabel":"下载中",
           "createTime":"2026-10-21T10:00:00+08:00","errorMessage":null},
          {"id":6,"title":"示例回放二","courseName":"示例课程","cameraType":"encoder",
           "filePath":"/home/示例/Downloads/示例课程/示例回放二_电脑屏幕.mp4","fileSize":4096,
           "downloadedBytes":4096,"progress":1.0,"status":"completed","statusLabel":"已完成",
           "createTime":"2026-10-20T09:00:00+08:00","errorMessage":"上一次失败过"}]}}
    """.trimIndent()

    @Test
    fun `list 逐字段：任务十二格、三个计数、目录那一格（videoUrl 不投影）`() = runTest {
        val api = api(engineFor("/api/downloads" to listBody))
        val list = api.list()

        assertEquals(2, list.tasks.size)
        val first = list.tasks[0]
        assertEquals(7L, first.id)
        assertEquals("示例回放", first.activityTitle, "线上 `title` 就是模型里的 `activityTitle`")
        assertEquals("示例课程", first.courseName)
        assertEquals("instructor", first.cameraType)
        assertEquals(2048L, first.fileSize)
        assertEquals(1024L, first.downloadedSize, "线上 `downloadedBytes` 就是模型里的 `downloadedSize`")
        assertEquals("downloading", first.status)
        assertEquals(0.5f, first.progress)
        assertEquals("下载中", first.statusLabel)
        assertTrue(first.isActive)
        // ⚠️ 直链不投影（serve 那台机器自己在用）⇒ 这一格必须是空串，不是编一个
        assertEquals("", first.videoUrl)

        // `createTime` 是 ISO-8601（契约 §4），模型那一格是 epoch 毫秒
        assertEquals(1_792_548_000_000L, first.createTime, "2026-10-21T10:00:00+08:00 的毫秒数")
        assertEquals(null, first.errorMessage, "JSON null ⇒ 模型里也是 null（没失败过）")
        assertEquals("上一次失败过", list.tasks[1].errorMessage, "有失败原因就原样带上")
        assertFalse(list.tasks[1].isActive, "已完成不算活跃")

        assertEquals(1, list.stats.downloadingCount)
        assertEquals(1, list.stats.completedCount)
        assertEquals(1, list.stats.activeCount)

        assertEquals("/home/示例/Downloads", list.dir.filesLocation)
        assertEquals(3, list.dir.notes.size, "三条说明原样带过来（屏上那张弹窗读它）")
    }

    @Test
    fun `enqueue 发的请求体就是契约那几格，回的 ids 原样解出来`() = runTest {
        val calls = mutableListOf<Call>()
        val api = api(
            engineFor(
                "/api/downloads" to """{"code":0,"data":{"ids":[11,12]}}""",
                calls = calls,
            ),
        )

        val ids = api.enqueue(
            url = "https://lms.example.com/replay/1.mp4",
            cameraType = "instructor",
            courseName = "示例课程",
            activityTitle = "示例回放",
            activityId = 7,
        )

        assertEquals(listOf(11L, 12L), ids)
        assertEquals(1, calls.size)
        val call = calls.single()
        assertEquals(HttpMethod.Post, call.method)
        assertEquals("/api/downloads", call.path)
        val body = call.body.orEmpty()
        for (needle in listOf(
            "\"url\":\"https://lms.example.com/replay/1.mp4\"",
            "\"cameraType\":\"instructor\"",
            "\"courseName\":\"示例课程\"",
            "\"activityTitle\":\"示例回放\"",
            "\"activityId\":7",
        )) {
            assertTrue(body.contains(needle), "请求体里该有 $needle，实际：$body")
        }
    }

    @Test
    fun `三个动词走 POST 子路径、删除走 DELETE 带 deleteFile；409 是结果不是异常`() = runTest {
        val calls = mutableListOf<Call>()
        val api = api(
            engineFor(
                "/pause" to """{"code":0,"data":{"ok":true,"id":7}}""",
                "/resume" to """{"code":409,"message":"这条任务是「已完成」，现在这个动作对它没有意义"}""",
                "/cancel" to """{"code":0,"data":{"ok":true,"id":7}}""",
                "/api/downloads/7" to """{"code":0,"data":{"id":7,"deletedFile":false}}""",
                calls = calls,
            ),
        )

        assertTrue(api.pause(7).ok)
        assertEquals(HttpMethod.Post, calls.last().method)
        assertEquals("/api/downloads/7/pause", calls.last().path)
        assertEquals("{}", calls.last().body, "空 JSON 体（serve 那一侧不读它）")

        // 409：**结果**（ok=false + 中文短句），不是抛异常
        val refused = api.resume(7)
        assertFalse(refused.ok)
        assertEquals(409, refused.code)
        assertTrue(refused.message.contains("已完成"), "拒绕的文案原样交回：${refused.message}")

        assertTrue(api.cancel(7).ok)

        // 删除：DELETE + `?deleteFile=false`
        val deleted = api.delete(7, deleteFile = false)
        assertTrue(deleted.ok)
        assertEquals(HttpMethod.Delete, calls.last().method)
        assertEquals("/api/downloads/7", calls.last().path)
        assertTrue(calls.last().url.endsWith("deleteFile=false"), "实际 URL：${calls.last().url}")
        assertEquals(null, calls.last().body, "DELETE 不带请求体")
    }

    @Test
    fun `campus-api 形态一律 serveOnly（这一族只在 serve 契约里）`() = runTest {
        val api = ServeDownloadsApi(client = createToolboxClient(engine = engineFor()), mode = ApiMode.CAMPUS_API)
        for (block in listOf<suspend () -> Unit>(
            { api.list() },
            { api.enqueue("https://example.com/a.mp4") },
            { api.pause(1) },
            { api.resume(1) },
            { api.cancel(1) },
            { api.delete(1) },
        )) {
            val error = assertFailsWith<UnsupportedOperationException> { block() }
            assertTrue(
                error.message.orEmpty().contains("serve"),
                "要说清「只在 serve 模式下有」：${error.message}",
            )
        }
    }
}
