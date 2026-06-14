package com.xjtu.toolbox.jiaoxiaozhi

import android.util.Log
import com.google.gson.JsonObject
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
        val effectiveModel = JiaoxiaozhiModels.byId(modelId).id
            .takeIf { it in setOf("qwen-plus", "qwen-max") }
            ?: "qwen-plus"
        val request = Request.Builder()
            .url("${JiaoxiaozhiLogin.API_ROOT}/question/streamAnswer")
            .header("Accept", "text/event-stream")
            .header("Origin", "https://assistant.xjtu.edu.cn")
            .header("Referer", session.refererUrl)
            .post(
                FormBody.Builder()
                    .add("ask", question)
                    .add("sessionId", sessionId)
                    .add("model", effectiveModel)
                    .add("timestamp", System.currentTimeMillis().toString())
                    .add("serviceModel", "default")
                    .add("uploadUrl", "")
                    .add("datasetFlag", "0")
                    .add("networkFlag", if (networkEnabled) "1" else "0")
                    .build()
            )
            .build()

        Log.d(TAG, "streamAnswer request model=$effectiveModel sessionId=$sessionId token=${session.hasAccessTokenForLog()} cookie=${session.hasCasAuthCookieForLog()} referer=${session.refererUrl.take(96)}")
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
                Log.d(TAG, "streamAnswer response code=${resp.code} url=${resp.request.url}")
                if (resp.code == 401 || resp.code == 403) {
                    val preview = runCatching { resp.peekBody(1024).string() }.getOrNull()
                    Log.w(TAG, "streamAnswer auth failure body=${preview?.take(512)}")
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
                var eventCount = 0

                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = withContext(Dispatchers.IO) { source.readUtf8Line() } ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.substring(5).trim()
                    if (payload.isBlank() || payload == "[DONE]") continue
                    val root = runCatching {
                        JsonParser.parseString(payload).asJsonObject
                    }.getOrElse {
                        Log.w(TAG, "skip non-json SSE payload=${payload.take(240)}")
                        continue
                    }
                    val data = root.get("data")
                        ?.takeUnless { it.isJsonNull }
                        ?.takeIf { it.isJsonObject }
                        ?.asJsonObject
                        ?: root
                    eventCount += 1
                    val type = runCatching { data.get("type")?.asInt }.getOrNull()
                    val content = extractDisplayText(data)

                    if (type == -1) {
                        upstreamError = content.ifBlank { "请求被上游拒绝" }
                        Log.w(TAG, "upstream error: ${upstreamError.take(240)}")
                        if (isAuthExpiredText(upstreamError.orEmpty())) {
                            session.invalidateLogin()
                            throw AuthExpiredException("交晓智")
                        }
                        continue
                    }
                    // type=11 是 PCM 音频；其余事件格式上游偶有变化，按字段内容判断是否可展示。
                    if (type == 11 || content.isBlank() || !looksLikeReadableText(content)) {
                        continue
                    }

                    answer.append(content)
                    withContext(Dispatchers.Main.immediate) { onDelta(content) }
                }

                if (answer.isEmpty() && upstreamError != null) throw RuntimeException(upstreamError)
                val rawAnswer = answer.toString()
                val cleaned = cleanText(rawAnswer)
                if (cleaned.isBlank()) {
                    val fallback = rawAnswer
                        .replace(Regex("\\u0000|\\uFFFD"), "")
                        .replace(Regex("\\s+"), " ")
                        .trim()
                    if (fallback.isNotBlank()) return@coroutineScope fallback
                    Log.w(TAG, "streamAnswer blank text events=$eventCount model=$effectiveModel sessionId=$sessionId")
                    return@coroutineScope "交晓智本次返回为空，可能是上游响应格式波动；请换个问法或稍后重试。"
                }
                return@coroutineScope cleaned
            }
        } finally {
            cancellationWatcher.cancel()
        }
    }

    companion object {
        private const val TAG = "JiaoxiaozhiApi"
        private val TEXT_KEYS = listOf("content", "answer", "text", "message", "result", "reply", "delta")

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

        private fun extractDisplayText(data: JsonObject): String {
            for (key in TEXT_KEYS) {
                val value = data.get(key)?.takeUnless { it.isJsonNull } ?: continue
                if (value.isJsonPrimitive) {
                    val text = runCatching { value.asString }.getOrNull().orEmpty()
                    if (text.isNotBlank()) return text
                }
            }
            val nested = data.get("message")
                ?.takeUnless { it.isJsonNull }
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
            if (nested != null) {
                for (key in TEXT_KEYS) {
                    val text = nested.get(key)
                        ?.takeUnless { it.isJsonNull }
                        ?.takeIf { it.isJsonPrimitive }
                        ?.let { runCatching { it.asString }.getOrNull() }
                        .orEmpty()
                    if (text.isNotBlank()) return text
                }
            }
            return ""
        }

        private fun looksLikeReadableText(text: String): Boolean {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return false
            if (trimmed.startsWith("data:audio", ignoreCase = true)) return false
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                val lower = trimmed.lowercase()
                if (
                    lower.contains("\"type\"") ||
                    lower.contains("\"source\"") ||
                    lower.contains("\"metadata\"") ||
                    lower.contains("\"reference\"") ||
                    lower.contains("\"docs\"")
                ) return false
            }
            return true
        }
    }
}
