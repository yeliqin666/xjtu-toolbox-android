package com.xjtu.toolbox.agent

import com.xjtu.toolbox.network.HttpClients
import com.xjtu.toolbox.util.AppJson
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
 * DeepSeek 语音识别（`/v1/tools/asr`）：整段 16-bit PCM WAV 进、整段文字出，不支持流式。
 * 单段最长 120 秒、不超过 8 MiB；立体声只认左声道。
 */
class DeepSeekAsr(
    private val apiKey: String,
    private val baseUrl: String = AgentConfig.DEEPSEEK_BASE_URL,
) {
    /** 成功给文字，失败给一句能直接提示用户的原因。 */
    suspend fun transcribe(wav: ByteArray): Result<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext Result.failure(IllegalStateException("没有配置 DeepSeek Key"))
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "speech.wav", wav.toRequestBody(WAV))
            .build()
        runCatching {
            client.newCall(
                Request.Builder().url("$baseUrl/v1/tools/asr").header("Authorization", "Bearer $apiKey").post(body).build()
            ).execute().use { resp ->
                val json = runCatching { AppJson.parseToJsonElement(resp.body.string().trim()).jsonObject }.getOrNull()
                val message = json?.obj("error")?.get("message")?.stringValue
                if (!resp.isSuccessful) error(reasonOf(resp.code, message))
                json?.get("text").safeStringOrNull()?.trim() ?: error(message ?: "识别结果解析失败")
            }
        }.recoverCatching { e ->
            throw if (e is java.io.IOException) IllegalStateException(if (e is java.io.InterruptedIOException) "识别超时" else "网络不通，识别失败") else e
        }
    }

    private fun reasonOf(code: Int, message: String?): String = when (code) {
        401 -> "DeepSeek Key 无效"
        402 -> "DeepSeek 账户余额不足"
        413 -> "录音太长"
        429 -> "请求太频繁，稍后再试"
        else -> message?.takeIf { it.isNotBlank() } ?: "识别失败（HTTP $code）"
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        /** 服务端上限 120 秒，留点余量。 */
        const val MAX_SECONDS = 115

        private val WAV = "audio/wav".toMediaType()

        /** 单声道 16-bit PCM 套上 44 字节的 WAV 头。 */
        fun wav(pcm: ByteArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
            val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".encodeToByteArray()); putInt(36 + pcm.size); put("WAVE".encodeToByteArray())
                put("fmt ".encodeToByteArray()); putInt(16); putShort(1); putShort(1)
                putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
                put("data".encodeToByteArray()); putInt(pcm.size)
            }.array()
            return header + pcm
        }

        private val client by lazy {
            HttpClients.base.newBuilder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .callTimeout(90, TimeUnit.SECONDS)
                .build()
        }
    }
}
