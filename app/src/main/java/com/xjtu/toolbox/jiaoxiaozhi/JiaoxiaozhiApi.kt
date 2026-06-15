package com.xjtu.toolbox.jiaoxiaozhi

import android.util.Log
import com.google.gson.JsonParser
import com.xjtu.toolbox.auth.AuthExpiredException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import java.util.concurrent.TimeUnit

class JiaoxiaozhiApi(private val session: JiaoxiaozhiSiteSession) {

    suspend fun ask(
        question: String,
        sessionId: String,
        modelId: String = JiaoxiaozhiModels.DEFAULT_ID,
        networkEnabled: Boolean = true,
        onDelta: suspend (String) -> Unit = {},
    ): String = coroutineScope {
        val request = Request.Builder()
            .url("${JiaoxiaozhiLogin.API_ROOT}/question/streamAnswer")
            .header("Accept", "text/event-stream")
            .header("Origin", "https://assistant.xjtu.edu.cn")
            .header("Referer", session.refererUrl)
            .post(
                FormBody.Builder()
                    .add("ask", question)
                    .add("sessionId", sessionId)
                    .add("model", modelId)
                    .add("timestamp", System.currentTimeMillis().toString())
                    .add("serviceModel", "default")
                    .add("uploadUrl", "")
                    .add("datasetFlag", "0")
                    .add("networkFlag", if (networkEnabled) "1" else "0")
                    .build()
            )
            .build()

        val call = session.apiClient.newBuilder()
            .readTimeout(180, TimeUnit.SECONDS)
            .build()
            .newCall(session.decorateRequest(request.newBuilder()).build())
        val cancellationWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }

        try {
            val response = withContext(Dispatchers.IO) { call.execute() }
            response.use { resp ->
                if (resp.code == 401 || resp.code == 403) {
                    session.invalidateLogin()
                    throw AuthExpiredException("交晓智")
                }
                if (!resp.isSuccessful) {
                    val preview = runCatching { resp.peekBody(512).string() }.getOrNull()
                    throw RuntimeException("交晓智服务响应 HTTP ${resp.code}${preview?.let { "：$it" }.orEmpty()}")
                }
                val source = resp.body?.source() ?: throw RuntimeException("交晓智响应为空")
                val answer = StringBuilder()
                var upstreamError: String? = null

                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = withContext(Dispatchers.IO) { source.readUtf8Line() } ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.substring(5).trim()
                    if (payload.isBlank() || payload == "[DONE]") continue
                    val root = runCatching {
                        JsonParser.parseString(payload).asJsonObject
                    }.getOrNull() ?: continue
                    val data = root.getAsJsonObject("data") ?: continue
                    val type = data.get("type")?.asInt
                    val content = data.get("content")
                        ?.takeUnless { it.isJsonNull }
                        ?.asString
                        .orEmpty()

                    if (type == -1) {
                        upstreamError = content.ifBlank { "请求被上游拒绝" }
                        if (isAuthExpiredText(upstreamError.orEmpty())) {
                            session.invalidateLogin()
                            throw AuthExpiredException("交晓智")
                        }
                        continue
                    }
                    // 白名单：只取 type=12 的文本 delta；type=11 是 PCM 音频，type=14 是引用文档
                    if (type != 12 || content.isEmpty()) continue

                    answer.append(content)
                    withContext(Dispatchers.Main.immediate) { onDelta(content) }
                }

                if (answer.isEmpty() && upstreamError != null) throw RuntimeException(upstreamError)
                val cleaned = cleanText(answer.toString())
                if (cleaned.isBlank()) throw RuntimeException("交晓智未返回文本内容")
                return@coroutineScope cleaned
            }
        } finally {
            cancellationWatcher.cancel()
        }
    }

    companion object {
        fun isAuthExpiredText(text: String): Boolean {
            val normalized = text.lowercase()
            return text.contains("登录态已失效") ||
                text.contains("登录已失效") ||
                text.contains("登录过期") ||
                text.contains("请重新登录") ||
                text.contains("未登录") ||
                normalized.contains("token") && (
                    normalized.contains("expired") ||
                        normalized.contains("invalid") ||
                        normalized.contains("unauthorized")
                    )
        }

        fun cleanText(text: String): String = text
            .replace(Regex("(?s)```\\s*(?:json)?\\s*\\{[^`]*```"), "")
            .replace(Regex("(?s)\\{\\s*\"(?:type|source|reference|metadata|citations?|docs?)\"\\s*:[\\s\\S]*?\\}"), "")
            .replace(Regex("(?s)<\\|[^|]{0,80}\\|>"), "")
            .replace(Regex("(?s)\\[\\[(?:citation|source|ref|doc)[^\\]]*\\]\\]"), "")
            .replace(Regex("\\s*!!\\d+!!\\s*"), " ")   // !!N!! 引用标号，带周边空白替换为单空格
            .replace(Regex(" +([。，！？、；：])"), "$1") // 标点前多余空格
            .replace(Regex("\\u0000|\\uFFFD"), "")
            .replace(Regex("[ \\t]+\\n"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }
}
