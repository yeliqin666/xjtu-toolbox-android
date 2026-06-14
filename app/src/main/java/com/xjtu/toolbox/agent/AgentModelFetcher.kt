package com.xjtu.toolbox.agent

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 通过 OpenAI 兼容的 `GET /v1/models` 拉取服务商可用模型列表，
 * 供配置页一键选择填入，免去用户手敲模型名。
 *
 * DeepSeek / OpenAI / 绝大多数自定义服务商都实现了该接口；不支持时抛异常由调用方降级为手填。
 */
object AgentModelFetcher {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** 成功返回模型 id 列表（已排序）；失败抛出带可读信息的异常。 */
    suspend fun fetch(config: AgentConfig): List<String> = withContext(Dispatchers.IO) {
        if (config.apiKey.isBlank()) throw IllegalStateException("请先填写 API Key")

        val response = client.newCall(
            Request.Builder()
                .url("${config.effectiveBaseUrl}/models")
                .header("Authorization", "Bearer ${config.apiKey}")
                .get()
                .build()
        ).execute()

        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            val msg = runCatching {
                JsonParser.parseString(body).asJsonObject
                    .getAsJsonObject("error")?.get("message")?.asString
            }.getOrNull() ?: "HTTP ${response.code}"
            throw RuntimeException(msg)
        }

        val data = runCatching {
            JsonParser.parseString(body).asJsonObject.getAsJsonArray("data")
        }.getOrNull() ?: throw RuntimeException("响应格式无法解析")

        data.mapNotNull { it.asJsonObject.get("id")?.asString }
            .distinct()
            .sorted()
            .ifEmpty { throw RuntimeException("服务商未返回任何模型") }
    }
}
