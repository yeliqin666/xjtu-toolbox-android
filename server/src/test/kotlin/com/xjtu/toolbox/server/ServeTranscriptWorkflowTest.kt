package com.xjtu.toolbox.server

import com.xjtu.toolbox.dzpz.DzpzFakeUpstream
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 成绩单两段式提交（契约 §5.2 例外 ①）：建申请单 → 出 PDF 票据 → 一次性下载。 */
class ServeTranscriptWorkflowTest {

    private var started: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    @AfterTest
    fun stopServer() {
        started?.stop(gracePeriodMillis = 100, timeoutMillis = 500)
        started = null
    }

    @Test
    fun `dzpz transcript 写一半：两段式 + 幂等 + 一次性票据`() = withFakeProxy { fake ->
        val token = AccessToken.newToken()
        val session = ServeSession()
        val (server, http) = startEndpointServer(token) {
            sessionRoutes(session, token)
            dzpzRoutes(session)
        }
        started = server
        // 进程内的单格记忆跟着测试走（上一轮用例可能留了一张申请单）
        TranscriptPending.reset()
        TranscriptDownloads.reset()

        // 未登录：三个写端点都 401，且一枪都不该到取数层
        assertEquals(401, http.postJson(SUBMIT_PATH, "{}").statusCode())
        assertEquals(401, http.postJson(PREVIEW_PATH, "{}").statusCode())
        assertEquals(401, http.get(DOWNLOAD_PATH + "?ticket=x").statusCode())
        assertEquals(0, fake.dzpz.submitBodies.size, "未登录那一枪不该到取数层")

        assertEquals(200, http.postJson(SESSION_PATH + "/login", LOGIN_BODY).statusCode())

        // 还没申请单就要预览 ⇒ 409（先 submit）
        val tooEarly = http.postJson(PREVIEW_PATH, "{}")
        assertEquals(409, tooEarly.statusCode())
        assertEquals(ApiErrors.ACTION_REJECTED, envelopeCode(tooEarly))

        // 形状：类型不在选项里 ⇒ 400
        assertEquals(400, http.postJson(SUBMIT_PATH, """{"type":99}""").statusCode())

        // 第一段：建申请单
        val created = http.postJson(SUBMIT_PATH, "{}")
        assertEquals(200, created.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(created))
        assertNoIdentity(created.body(), "POST $SUBMIT_PATH", IDENTITY)
        val createdData = envelopeData(created)
        assertEquals(setOf("requestId", "type", "created", "message"), createdData.keys)
        assertEquals(DzpzFakeUpstream.REQUEST_ID.toLong(), createdData.optLong("requestId"))
        assertEquals(DzpzFakeUpstream.TYPE_VALUE.toLong(), createdData.optLong("type"), "省略 type ⇒ 表单第一项")
        assertEquals(true, createdData.optBoolean("created"))
        assertEquals(DZPZ_SUBMIT_CREATED_MESSAGE, createdData.optString("message"))
        assertEquals(1, fake.dzpz.submitBodies.size, "建单就是那一枪")

        // 幂等：再点一次「提交」⇒ 复用上一张，不再建第二张
        val again = http.postJson(SUBMIT_PATH, "{}")
        assertEquals(200, again.statusCode())
        val againData = envelopeData(again)
        assertEquals(false, againData.optBoolean("created"), "重复点击不得开第二张申请单")
        assertEquals(DzpzFakeUpstream.REQUEST_ID.toLong(), againData.optLong("requestId"), "回的还是那一张")
        assertEquals(DZPZ_SUBMIT_REUSED_MESSAGE, againData.optString("message"))
        assertEquals(1, fake.dzpz.submitBodies.size, "幂等命中不得再打建单那一枪")

        // 第二段：生成 + 签章 + 取下载信息 ⇒ 只用本机票据，不把学校链接漏给客户端
        val preview = http.postJson(PREVIEW_PATH, "{}")
        assertEquals(200, preview.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(preview))
        assertNoIdentity(preview.body(), "POST $PREVIEW_PATH", IDENTITY)
        val previewData = envelopeData(preview)
        assertEquals(setOf("ok", "filename", "filesize", "downloadPath"), previewData.keys)
        assertEquals(true, previewData.optBoolean("ok"))
        assertEquals(DzpzFakeUpstream.PDF_FILENAME, previewData.optString("filename"))
        assertEquals(DzpzFakeUpstream.PDF_FILESIZE, previewData.optString("filesize"))
        val downloadPath = previewData.optString("downloadPath").orEmpty()
        assertTrue(
            downloadPath.startsWith("$API_PREFIX/$DZPZ_SEGMENT/$DZPZ_TRANSCRIPT_DOWNLOAD?"),
            "下载不该直指学校：$downloadPath",
        )
        assertFalse(downloadPath.contains(DzpzFakeUpstream.HOST), "上游域名不得出现在响应里：$downloadPath")
        assertNotNull(fake.dzpz.downloadReferer, "下载那一枪必须带 Referer")

        // 第三段：票据换 PDF（一次性）
        val pdf = http.get(downloadPath)
        assertEquals(200, pdf.statusCode())
        assertTrue(
            pdf.headers().firstValue("Content-Type").orElse("").startsWith("application/pdf"),
            "第三段就是那一条 application/pdf 例外",
        )
        assertTrue(
            pdf.headers().firstValue("Content-Disposition").orElse("").contains(DZPZ_DOWNLOAD_FALLBACK_NAME),
            "下载名要有 ASCII 回退",
        )
        val pdfText = pdf.body()
        assertTrue(pdfText.contains("%PDF-1.4"), "回的该是学校那份 PDF：$pdfText")
        assertTrue(pdfText.contains("%%EOF"), "回的该是学校那份 PDF（结尾）：$pdfText")

        // 同一张票据再用一次 ⇒ 404（一次性，不可重放）
        assertEquals(404, http.get(downloadPath).statusCode())

        // 缺票据 ⇒ 400
        assertEquals(400, http.get(DOWNLOAD_PATH).statusCode())

        // 票据用掉后，第二段该重新要申请单（上一张已消费）
        assertEquals(409, http.postJson(PREVIEW_PATH, "{}").statusCode())
    }

    private companion object {
        val LOGIN_BODY =
            """{"username":"${com.xjtu.toolbox.library.LibraryFakeUpstream.USERNAME}","password":"${com.xjtu.toolbox.library.LibraryFakeUpstream.PASSWORD}"}"""

        const val SUBMIT_PATH = "$API_PREFIX/$DZPZ_SEGMENT/$DZPZ_TRANSCRIPT_SUBMIT"
        const val PREVIEW_PATH = "$API_PREFIX/$DZPZ_SEGMENT/$DZPZ_TRANSCRIPT_PREVIEW"
        const val DOWNLOAD_PATH = "$API_PREFIX/$DZPZ_SEGMENT/$DZPZ_TRANSCRIPT_DOWNLOAD"

        /** §3.4 红线的钉子组：键名 + 典型值（学号/姓名都来自夹具备份，不是真人）。 */
        val IDENTITY = listOf(
            "username",
            "studentId",
            "学号",
            "姓名",
            com.xjtu.toolbox.library.LibraryFakeUpstream.USERNAME,
        )
    }
}
