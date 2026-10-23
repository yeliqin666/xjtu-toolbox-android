package com.xjtu.toolbox.web

import com.xjtu.toolbox.core.net.createToolboxClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * 真浏览器里核 Ktor 的**字节读**（`bodyAsBytes`）。
 *
 * 为什么要有这一条：serve 契约里只有一条端点**成功时回字节**（成绩单 PDF，`application/pdf` 那条例外），
 * `CampusTranscriptApi.downloadPdf` 走的就是这一支 —— 而 `:web` 这条线上它此前只**编过**、没跑过。
 * 这里用 `MockEngine`（wasmJs 上可用）在 Chrome 里真读一遍四格字节，证明这条缝在浏览器端是通的。
 */
class WebServeBytesTest {

    @Test
    fun `bodyAsBytes 在浏览器里真读回字节（成绩单 PDF 那一发走的就是它）`() = runTest {
        val pdf = byteArrayOf(0x25, 0x50, 0x44, 0x46)
        val engine = MockEngine {
            respond(
                content = pdf,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType to listOf("application/pdf")),
            )
        }
        val response: HttpResponse = createToolboxClient(engine = engine).get("https://example.invalid/x.pdf")
        assertEquals(200, response.status.value)
        assertEquals(pdf.toList(), response.bodyAsBytes().toList())
    }
}
