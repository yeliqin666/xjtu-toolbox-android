package com.xjtu.toolbox.core.net

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

/**
 * serve 契约 §5.12 的**两段式成绩单**在客户端那一侧的核（`CampusTranscriptApi`）。
 *
 * 屏上那七步在这一端有几步是「同一发 HTTP」或「空壳」，这里逐条把它们钉住（口径写在那份 API 的 KDoc 表格里）。
 */
class ServeTranscriptWorkflowApiTest {

    private data class Call(val path: String, val method: HttpMethod, val body: String?, val query: Map<String, String>)

    /** 一个只按路径答夹具的 MockEngine；PDF 那一发回**字节**，其余回 JSON。 */
    private fun engineFor(
        vararg bodies: Pair<String, String>,
        pdf: ByteArray? = null,
        pdfStatus: HttpStatusCode = HttpStatusCode.OK,
        calls: MutableList<Call>? = null,
    ): MockEngine {
        val byPath = bodies.toMap()
        return MockEngine { request ->
            calls?.add(
                Call(
                    path = request.url.encodedPath,
                    method = request.method,
                    body = (request.body as? TextContent)?.text,
                    query = request.url.parameters.names().associateWith { request.url.parameters[it].orEmpty() },
                ),
            )
            val path = request.url.encodedPath
            when {
                pdf != null && path.endsWith("/api/dzpz/transcript/download") -> respond(
                    content = pdf,
                    status = pdfStatus,
                    headers = headersOf(
                        HttpHeaders.ContentType to listOf(
                            if (pdfStatus == HttpStatusCode.OK) "application/pdf" else "application/json",
                        ),
                    ),
                )
                else -> respond(
                    content = byPath.entries.firstOrNull { path.endsWith(it.key) }?.value
                        ?: error("测试没有为 $path 准备响应"),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType to listOf("application/json")),
                )
            }
        }
    }

    private fun api(engine: MockEngine, mode: ApiMode = ApiMode.SERVE) =
        CampusTranscriptApi(createToolboxClient(engine = engine), "", mode)

    private val formBody = """
        {"code":0,"data":{"workflowId":29,"typeOptions":[{"name":"本科生中文成绩单","value":0}],
         "defaultDate":"2026-10-22","defaultRequestName":"成绩单申请"}}
    """.trimIndent()

    private val submitBody =
        """{"code":0,"data":{"requestId":8801,"type":0,"created":true,"message":"申请单已创建"}}"""

    private val previewBody = """
        {"code":0,"data":{"ok":true,"filename":"成绩单-示例.pdf","filesize":"128.5 KB",
         "downloadPath":"/api/dzpz/transcript/download?ticket=T-1"}}
    """.trimIndent()

    /** PDF 的魔数开头的几个字节（夹具，不是真 PDF）。 */
    private val pdfBytes = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D, 0x31)

    @Test
    fun `两段式：建单 → 生成 → 取下载信息 → 票据换字节，七步里那两步是空壳或直通`() = runTest {
        val calls = mutableListOf<Call>()
        val api = api(
            engineFor(
                "/api/dzpz/transcript" to formBody,
                "/api/dzpz/transcript/submit" to submitBody,
                "/api/dzpz/transcript/preview" to previewBody,
                pdf = pdfBytes,
                calls = calls,
            ),
        )

        assertTrue(api.canRunWorkflow, "serve 形态下整条链走得通（契约 §5.12）")
        val form = api.loadCreateForm(29)
        assertEquals(29, form.workflowId)
        assertEquals(1, form.typeOptions.size)

        // Step 2：空壳（服务端在建单那一发里自己跑联动，返回值里有学号 ⇒ 不投影）
        val linkage = api.getLinkageData(form, 0)
        assertTrue(linkage.studentId.isEmpty(), "学号是红线 ⇒ 这一端拿不到，也不编一个")

        // Step 3：第一段
        val first = api.submitCreate(form, linkage, typeValue = 0)
        assertEquals(8801, first.requestId)
        assertEquals(HttpMethod.Post, calls[1].method)
        assertEquals("/api/dzpz/transcript/submit", calls[1].path)
        assertEquals("""{"type":0}""", calls[1].body, "请求体就一格 type")

        // Step 4：第二段（生成 + 签章 + 取下载信息）
        api.generatePreviewPdf(form.workflowId, 0, first.requestId)
        assertEquals(HttpMethod.Post, calls[2].method)
        assertEquals("/api/dzpz/transcript/preview", calls[2].path)

        // Step 5：两段式里没有这一步 ⇒ 原样交回
        val second = api.reloadAndForward(form, first, 0)
        assertEquals(8801, second.requestId, "第二次提交在这一端不存在，交回上一步的结果")
        assertEquals(3, calls.size, "Step 5 不发请求")

        // Step 6：`preview` 存下的三格
        val info = api.getDownloadInfo(second)
        assertEquals("成绩单-示例.pdf", info.filename)
        assertEquals("128.5 KB", info.filesize)
        assertEquals("/api/dzpz/transcript/download?ticket=T-1", info.downloadUrl, "票据是本机路径，不是上游 link")

        // Step 7：票据换字节
        val bytes = api.downloadPdf(info.downloadUrl)
        assertEquals(pdfBytes.toList(), bytes.toList(), "回的就是那六格字节（PDF 魔数打头）")
        assertEquals(HttpMethod.Get, calls.last().method)
        assertEquals("/api/dzpz/transcript/download", calls.last().path)
        assertEquals("T-1", calls.last().query["ticket"])
    }

    @Test
    fun `幂等命中（created false）也是成功：同一个 requestId 原样带回`() = runTest {
        val api = api(
            engineFor(
                "/api/dzpz/transcript" to formBody,
                "/api/dzpz/transcript/submit" to
                    """{"code":0,"data":{"requestId":8801,"type":0,"created":false,"message":"复用 10 分钟内那一张"}}""",
            ),
        )
        val form = api.loadCreateForm(29)
        val first = api.submitCreate(form, api.getLinkageData(form, 0), 0)
        assertEquals(8801, first.requestId)
    }

    @Test
    fun `票据过期（404 回的是信封）要报错，不把一段 JSON 当 PDF 交上去`() = runTest {
        val api = api(engineFor(pdf = "票据已经用过或过期了".encodeToByteArray(), pdfStatus = HttpStatusCode.NotFound))
        val error = assertFailsWith<IllegalStateException> { api.downloadPdf("/api/dzpz/transcript/download?ticket=旧") }
        assertTrue(error.message.orEmpty().contains("PDF"), "要说清「没拿到 PDF」：${error.message}")
        assertTrue(error.message.orEmpty().contains("404"), "实际：${error.message}")
    }

    @Test
    fun `上游拒（409）时是异常，文案原样带上`() = runTest {
        val api = api(
            engineFor(
                "/api/dzpz/transcript" to formBody,
                "/api/dzpz/transcript/submit" to """{"code":409,"message":"上一次申请还在处理中，请稍后再点"}""",
            ),
        )
        val form = api.loadCreateForm(29)
        val error = assertFailsWith<IllegalStateException> {
            api.submitCreate(form, api.getLinkageData(form, 0), 0)
        }
        assertTrue(error.message.orEmpty().contains("还在处理中"), "实际：${error.message}")
    }

    @Test
    fun `campus-api 形态：canRunWorkflow 为 false，写那几步一律 serveOnly`() = runTest {
        val api = api(engineFor(), mode = ApiMode.CAMPUS_API)
        assertFalse(api.canRunWorkflow, "campus-api 那一形态只画得了「选类型」那一步")
        for (block in listOf<suspend () -> Unit>(
            { api.getLinkageData(formContext(), 0) },
            { api.submitCreate(formContext(), linkageResult(), 0) },
            { api.generatePreviewPdf(29, 0, 1) },
            { api.downloadPdf("/api/dzpz/transcript/download?ticket=x") },
        )) {
            val error = assertFailsWith<UnsupportedOperationException> { block() }
            assertTrue(error.message.orEmpty().contains("serve"), "要说清「只在 serve 模式下有」：${error.message}")
        }
    }

    private fun formContext() = com.xjtu.toolbox.dzpz.FormContext(
        workflowId = 29,
        params = kotlinx.serialization.json.JsonObject(emptyMap()),
        submitParams = kotlinx.serialization.json.JsonObject(emptyMap()),
        maindata = kotlinx.serialization.json.JsonObject(emptyMap()),
        typeOptions = emptyList(),
        linkageUUID = "",
        signatureAttributesStr = "",
        signatureSecretKey = "",
        defaultDate = "",
        defaultRequestName = "",
    )

    private fun linkageResult() = com.xjtu.toolbox.dzpz.LinkageResult(
        studentId = "",
        enrollYear = "",
        templatePath = "",
        categoryName = "",
        workflowIdField = "",
    )
}
