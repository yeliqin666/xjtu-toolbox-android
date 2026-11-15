package com.xjtu.toolbox.agent

import com.xjtu.toolbox.network.HttpClients
import com.xjtu.toolbox.util.AppJson
import com.xjtu.toolbox.util.arr
import com.xjtu.toolbox.util.obj
import com.xjtu.toolbox.util.safeStringOrNull
import com.xjtu.toolbox.util.stringValue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * DeepSeek 独立 OCR（`/v1/tools/ocr`）：文件进、逐页文字出，与联网搜索共用 Key，1.5 元/千页。
 * 接受 PDF、DOC/DOCX、PPT/PPTX 和图片；Word、PPT 无图时服务端直接抽字。
 */
class DeepSeekOcr(
    private val apiKey: String,
    private val baseUrl: String = AgentConfig.DEEPSEEK_BASE_URL,
) {
    sealed interface Result {
        class Ok(val pages: List<String>) : Result
        class Failed(val reason: String) : Result
    }

    /**
     * 服务端会偶发把整页返回成空串且照样 stop（2026-11 实测），所以有空页就整份重识别，
     * 按页取首个非空结果，最多 [ATTEMPTS] 次。真空白页也会多跑这几次，调用方宜按单页送。
     */
    suspend fun recognize(fileName: String, bytes: ByteArray): Result {
        var merged: MutableList<String>? = null
        var lastFailure: Result.Failed? = null
        repeat(ATTEMPTS) {
            when (val r = once(fileName, bytes)) {
                is Result.Failed -> lastFailure = r
                is Result.Ok -> {
                    val pages = merged?.also { m -> r.pages.forEachIndexed { i, t -> if (i < m.size && m[i].isBlank()) m[i] = t } }
                        ?: r.pages.toMutableList().also { merged = it }
                    if (pages.none { it.isBlank() }) return Result.Ok(pages)
                }
            }
            if (lastFailure?.retryable == false) return lastFailure!!
        }
        return merged?.let { Result.Ok(it) } ?: lastFailure ?: Result.Failed("network")
    }

    private val Result.Failed.retryable: Boolean
        get() = reason == "timeout" || reason == "rate_limited" || reason.startsWith("server_") || reason == "network"

    private suspend fun once(fileName: String, bytes: ByteArray): Result = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext Result.Failed("api_key_missing")
        // 文档要求 purpose 在 file 之前
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("purpose", "text_recognition")
            .addFormDataPart("file", fileName, bytes.toRequestBody(OCTET))
            .build()
        runCatching {
            client.newCall(
                Request.Builder().url("$baseUrl/v1/tools/ocr").header("Authorization", "Bearer $apiKey").post(body).build()
            ).execute().use { resp ->
                // 识别期间服务端发换行保活，正文前可能有空白
                val json = runCatching { AppJson.parseToJsonElement(resp.body.string().trim()).jsonObject }.getOrNull()
                val message = json?.obj("error")?.get("message")?.stringValue
                when {
                    !resp.isSuccessful -> Result.Failed(reasonOf(resp.code, message))
                    json == null -> Result.Failed("bad_response")
                    message != null -> Result.Failed(message)
                    else -> Result.Ok(json.arr("pages").orEmpty().map { clean(it.jsonObject["text"].safeStringOrNull().orEmpty()) })
                }
            }
        }.getOrElse { Result.Failed(if (it is java.io.InterruptedIOException) "timeout" else "network") }
    }

    private fun reasonOf(code: Int, message: String?): String = when (code) {
        401 -> "api_key_invalid"
        402 -> "insufficient_balance"
        408, 504 -> "timeout"
        413 -> "file_too_large"
        429 -> "rate_limited"
        in 500..599 -> "server_$code"
        else -> message?.takeIf { it.isNotBlank() } ?: "http_$code"
    }

    companion object {
        private const val ATTEMPTS = 3
        private val OCTET = "application/octet-stream".toMediaType()

        /** 版面识别页带 `<｜ref｜>类型<｜/ref｜><｜box｜>[[坐标]]<｜/box｜>` 标记，只留文字。 */
        private val MARKUP = Regex("""<｜ref｜>.*?<｜/ref｜>|<｜box｜>.*?<｜/box｜>|<｜/?[a-z_]+｜>""")

        internal fun clean(text: String): String = text.replace(MARKUP, "").lines().joinToString("\n") { it.trim() }.trim()

        private val client by lazy {
            HttpClients.base.newBuilder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .callTimeout(190, TimeUnit.SECONDS)
                .build()
        }
    }
}
